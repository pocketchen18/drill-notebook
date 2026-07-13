package com.drillnotebook.app.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import com.drillnotebook.app.model.QuestionRecord;
import com.drillnotebook.app.repository.QuestionRepository;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class QuizServiceTest {
    @Test
    void preservesExplicitQuestionOrder() {
        QuestionRepository repository = mock(QuestionRepository.class);
        AiService ai = mock(AiService.class);
        QuestionRecord first = question(1);
        QuestionRecord second = question(2);
        when(repository.findByIds(List.of(2L, 1L))).thenReturn(List.of(first, second));
        Map<String, Object> result = new QuizService(repository, ai).start(Map.of("questionIds", List.of(2, 1), "shuffle", false));
        @SuppressWarnings("unchecked") List<Map<String, Object>> questions = (List<Map<String, Object>>) result.get("questions");
        assertEquals(List.of(2L, 1L), questions.stream().map(item -> item.get("id")).toList());
    }

    @Test
    void gradesFillAndTrueFalseDeterministically() {
        QuestionRepository repository = mock(QuestionRepository.class);
        AiService ai = mock(AiService.class);
        QuizService service = new QuizService(repository, ai);
        QuestionRecord fill = question(3); fill.type = "fill"; fill.answer = "Java Virtual Machine";
        when(repository.findByIds(List.of(3L))).thenReturn(List.of(fill)); when(repository.findById(3L)).thenReturn(fill);
        String sessionId = String.valueOf(service.start(Map.of("questionIds", List.of(3), "shuffle", false)).get("sessionId"));
        assertEquals(true, service.submit(sessionId, Map.of("questionId", 3, "userAnswer", "  java   virtual machine ")).get("isCorrect"));

        QuestionRecord judgement = question(4); judgement.type = "true_false"; judgement.answer = "true";
        when(repository.findByIds(List.of(4L))).thenReturn(List.of(judgement)); when(repository.findById(4L)).thenReturn(judgement);
        String judgementSession = String.valueOf(service.start(Map.of("questionIds", List.of(4), "shuffle", false)).get("sessionId"));
        assertEquals(true, service.submit(judgementSession, Map.of("questionId", 4, "userAnswer", "对")).get("isCorrect"));
    }

    @Test
    void keepsAiEssayGradeAdvisory() {
        QuestionRepository repository = mock(QuestionRepository.class);
        AiService ai = mock(AiService.class);
        QuizService service = new QuizService(repository, ai);
        QuestionRecord essay = question(5); essay.type = "essay"; essay.answer = "参考";
        when(repository.findByIds(List.of(5L))).thenReturn(List.of(essay)); when(repository.findById(5L)).thenReturn(essay);
        when(ai.gradeEssay(essay, "我的答案", "secret")).thenReturn(Map.of("score", 80, "suggestedCorrect", true, "confidence", 0.8, "explanation", "覆盖主要要点"));
        String sessionId = String.valueOf(service.start(Map.of("questionIds", List.of(5), "shuffle", false)).get("sessionId"));
        Map<String, Object> result = service.submit(sessionId, Map.of("questionId", 5, "userAnswer", "我的答案", "masterPassword", "secret"));
        assertNull(result.get("isCorrect"));
        assertEquals("ai_suggested", result.get("gradingStatus"));
        verify(ai).gradeEssay(essay, "我的答案", "secret");
    }

    private static QuestionRecord question(long id) {
        QuestionRecord question = new QuestionRecord();
        question.id = id; question.bankId = 1; question.type = "single"; question.stem = "Q" + id; question.answer = "A";
        return question;
    }
}
