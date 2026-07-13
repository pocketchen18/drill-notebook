package com.drillnotebook.app.service;

import com.drillnotebook.app.model.QuestionRecord;
import com.drillnotebook.app.repository.QuestionRepository;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

@Service
public class QuizService {
    private final QuestionRepository questions;
    private final AiService ai;
    private final Map<String, SessionState> sessions = new ConcurrentHashMap<>();

    public QuizService(QuestionRepository questions, AiService ai) { this.questions = questions; this.ai = ai; }

    public Map<String, Object> start(Map<String, Object> body) {
        List<Long> requested = toIds(body.get("questionIds"));
        List<QuestionRecord> selected;
        if (!requested.isEmpty()) {
            Map<Long, QuestionRecord> byId = questions.findByIds(requested).stream().collect(Collectors.toMap((question) -> question.id, (question) -> question));
            selected = requested.stream().distinct().map(byId::get).filter(java.util.Objects::nonNull).collect(Collectors.toCollection(ArrayList::new));
        }
        else {
            Object bankValue = body.get("bankId");
            if (bankValue == null) throw new IllegalArgumentException("需要 bankId 或 questionIds");
            selected = new ArrayList<>(questions.findByBank(Long.parseLong(String.valueOf(bankValue))));
            Map<String, Object> filter = body.get("filter") instanceof Map<?, ?> value ? (Map<String, Object>) value : Map.of();
            List<String> requestedTypes = strings(filter.get("types"));
            final List<String> types = requestedTypes.isEmpty() && filter.get("type") != null ? List.of(String.valueOf(filter.get("type"))) : requestedTypes;
            if (!types.isEmpty()) selected.removeIf((question) -> types.stream().noneMatch(type -> type.equalsIgnoreCase(question.type)));
            List<String> requestedChapters = strings(filter.get("chapters"));
            final List<String> chapters = requestedChapters.isEmpty() && filter.get("chapter") != null ? List.of(String.valueOf(filter.get("chapter"))) : requestedChapters;
            if (!chapters.isEmpty()) selected.removeIf((question) -> question.chapter == null || !chapters.contains(question.chapter));
            List<String> tags = strings(filter.get("tags"));
            if (!tags.isEmpty()) selected.removeIf((question) -> question.tags == null || question.tags.stream().noneMatch(tags::contains));
        }
        if (selected.isEmpty()) throw new IllegalArgumentException("没有可练习的题目");
        if (Boolean.parseBoolean(String.valueOf(body.getOrDefault("shuffle", true)))) selected = shuffleGroups(selected);
        int limit = body.get("limit") == null ? selected.size() : Math.max(1, Integer.parseInt(String.valueOf(body.get("limit"))));
        if (selected.size() > limit) selected = new ArrayList<>(selected.subList(0, limit));
        String sessionId = UUID.randomUUID().toString();
        sessions.put(sessionId, new SessionState(selected.stream().map((question) -> question.id).toList()));
        return Map.of("sessionId", sessionId, "questions", selected.stream().map((question) -> question.toMap(false)).toList());
    }

    private List<QuestionRecord> shuffleGroups(List<QuestionRecord> input) {
        boolean hasGroups = input.stream().anyMatch((question) -> question.groupId != null && !question.groupId.isBlank());
        if (!hasGroups) { Collections.shuffle(input); return input; }
        Map<String, List<QuestionRecord>> groups = new LinkedHashMap<>();
        for (QuestionRecord question : input) groups.computeIfAbsent(question.groupId == null || question.groupId.isBlank() ? "solo-" + question.id : question.groupId, (ignored) -> new ArrayList<>()).add(question);
        List<List<QuestionRecord>> blocks = new ArrayList<>(groups.values());
        blocks.forEach((block) -> block.sort((left, right) -> {
            int leftOrder = left.orderInGroup == null ? Integer.MAX_VALUE : left.orderInGroup;
            int rightOrder = right.orderInGroup == null ? Integer.MAX_VALUE : right.orderInGroup;
            return leftOrder == rightOrder ? Long.compare(left.id, right.id) : Integer.compare(leftOrder, rightOrder);
        }));
        Collections.shuffle(blocks);
        return blocks.stream().flatMap(List::stream).collect(Collectors.toCollection(ArrayList::new));
    }

    public Map<String, Object> submit(String sessionId, Map<String, Object> body) {
        SessionState session = sessions.get(sessionId);
        if (session == null) throw new IllegalArgumentException("练习 session 不存在或已过期");
        long questionId = Long.parseLong(String.valueOf(body.get("questionId")));
        if (!session.questionIds().contains(questionId)) throw new IllegalArgumentException("题目不属于当前练习");
        QuestionRecord question = questions.findById(questionId);
        String userAnswer = answerText(body.get("userAnswer"));
        if (userAnswer.isBlank()) throw new IllegalArgumentException("答案不能为空");
        Boolean correct = null;
        String gradingStatus = "deterministic";
        Map<String, Object> grading = null;
        if ("essay".equals(question.type)) {
            boolean useAi = !Boolean.FALSE.equals(body.get("useAiGrading")) && !"false".equalsIgnoreCase(String.valueOf(body.get("useAiGrading")));
            if (useAi) {
                try {
                    grading = ai.gradeEssay(question, userAnswer, String.valueOf(body.getOrDefault("masterPassword", "")));
                    gradingStatus = "ai_suggested";
                } catch (IllegalArgumentException error) {
                    gradingStatus = "unavailable";
                    grading = Map.of("version", 1, "available", false, "message", "AI 辅助判题不可用，答案已保存。请结合参考答案自行复核。");
                }
            } else {
                gradingStatus = "ungraded";
                grading = Map.of("version", 1, "available", false, "message", "已保存答案，本次未使用 AI 辅助判题。");
            }
        } else {
            correct = QuestionTypeRules.deterministicCorrect(question.type, userAnswer, question.answer);
        }
        questions.recordAnswer(questionId, userAnswer, correct, body.get("timeSpent") == null ? 0 : Integer.parseInt(String.valueOf(body.get("timeSpent"))), sessionId, gradingStatus, grading);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("isCorrect", correct); response.put("correctAnswer", question.answer == null ? "" : question.answer); response.put("analysis", question.analysis == null ? "" : question.analysis);
        response.put("gradingStatus", gradingStatus); response.put("grading", grading);
        return response;
    }

    public List<Map<String, Object>> wrong() { return questions.wrongQuestions().stream().map((question) -> question.toMap(true)).toList(); }

    public Map<String, Object> summary(String sessionId) {
        SessionState session = sessions.get(sessionId);
        if (session == null) throw new IllegalArgumentException("练习 session 不存在或已过期");
        List<Map<String, Object>> answers = questions.sessionAnswers(sessionId);
        long correct = answers.stream().filter((answer) -> Boolean.TRUE.equals(answer.get("isCorrect"))).count();
        long wrong = answers.stream().filter((answer) -> Boolean.FALSE.equals(answer.get("isCorrect"))).count();
        long ungraded = answers.stream().filter((answer) -> answer.get("isCorrect") == null).count();
        return Map.of("sessionId", sessionId, "total", session.questionIds().size(), "answered", answers.size(), "correct", correct, "wrong", wrong, "ungraded", ungraded, "answers", answers);
    }

    private static String answerText(Object value) {
        if (value instanceof List<?> list) return list.stream().map(String::valueOf).collect(Collectors.joining(","));
        if (value == null) return "";
        return String.valueOf(value);
    }

    private static List<Long> toIds(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        return list.stream().map((item) -> Long.parseLong(String.valueOf(item))).toList();
    }

    private static List<String> strings(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        return list.stream().map(String::valueOf).map(String::trim).filter(item -> !item.isBlank()).toList();
    }

    private record SessionState(List<Long> questionIds) {}
}
