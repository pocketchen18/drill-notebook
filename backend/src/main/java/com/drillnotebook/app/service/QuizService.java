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
    private final Map<String, SessionState> sessions = new ConcurrentHashMap<>();

    public QuizService(QuestionRepository questions) { this.questions = questions; }

    public Map<String, Object> start(Map<String, Object> body) {
        List<Long> requested = toIds(body.get("questionIds"));
        List<QuestionRecord> selected;
        if (!requested.isEmpty()) selected = new ArrayList<>(questions.findByIds(requested));
        else {
            Object bankValue = body.get("bankId");
            if (bankValue == null) throw new IllegalArgumentException("需要 bankId 或 questionIds");
            selected = new ArrayList<>(questions.findByBank(Long.parseLong(String.valueOf(bankValue))));
            Map<String, Object> filter = body.get("filter") instanceof Map<?, ?> value ? (Map<String, Object>) value : Map.of();
            if (filter.get("type") != null) selected.removeIf((question) -> !String.valueOf(filter.get("type")).equalsIgnoreCase(question.type));
            if (filter.get("chapter") != null) selected.removeIf((question) -> !String.valueOf(filter.get("chapter")).equals(question.chapter));
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
        boolean correct = normalize(userAnswer).equals(normalize(question.answer));
        questions.recordAnswer(questionId, userAnswer, correct, body.get("timeSpent") == null ? 0 : Integer.parseInt(String.valueOf(body.get("timeSpent"))), sessionId);
        return Map.of("isCorrect", correct, "correctAnswer", question.answer, "analysis", question.analysis == null ? "" : question.analysis);
    }

    public List<Map<String, Object>> wrong() { return questions.wrongQuestions().stream().map((question) -> question.toMap(true)).toList(); }

    public Map<String, Object> summary(String sessionId) {
        SessionState session = sessions.get(sessionId);
        if (session == null) throw new IllegalArgumentException("练习 session 不存在或已过期");
        List<Map<String, Object>> answers = questions.sessionAnswers(sessionId);
        long correct = answers.stream().filter((answer) -> Integer.valueOf(1).equals(answer.get("isCorrect"))).count();
        return Map.of("sessionId", sessionId, "total", session.questionIds().size(), "answered", answers.size(), "correct", correct, "wrong", answers.size() - correct, "answers", answers);
    }

    private static String answerText(Object value) {
        if (value instanceof List<?> list) return list.stream().map(String::valueOf).collect(Collectors.joining(","));
        if (value == null) return "";
        return String.valueOf(value);
    }

    private static String normalize(String value) {
        return java.util.Arrays.stream(value.toUpperCase().split(","))
                .map(String::trim).filter((item) -> !item.isBlank()).distinct().sorted().collect(Collectors.joining(","));
    }

    private static List<Long> toIds(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        return list.stream().map((item) -> Long.parseLong(String.valueOf(item))).toList();
    }

    private record SessionState(List<Long> questionIds) {}
}
