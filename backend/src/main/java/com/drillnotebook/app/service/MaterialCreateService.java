package com.drillnotebook.app.service;

import com.drillnotebook.app.model.ChatContextPlan;
import com.drillnotebook.app.model.QuestionRecord;
import com.drillnotebook.app.repository.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.util.*;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** A receipt and its ordinary business writes commit together, without remote calls inside the transaction. */
@Service
public class MaterialCreateService {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final NotebookRepository notebooks;
    private final BankRepository banks;
    private final KnowledgePointRepository knowledge;
    private final QuestionRepository questions;
    private final NoteIndexingService indexing;
    private final ActiveTurnRegistry active;
    private final ObjectMapper json;

    public MaterialCreateService(JdbcTemplate jdbc, PlatformTransactionManager transactions,
            NotebookRepository notebooks, BankRepository banks, KnowledgePointRepository knowledge,
            QuestionRepository questions, NoteIndexingService indexing, ActiveTurnRegistry active, ObjectMapper json) {
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(transactions);
        this.notebooks = notebooks;
        this.banks = banks;
        this.knowledge = knowledge;
        this.questions = questions;
        this.indexing = indexing;
        this.active = active;
        this.json = json;
    }

    Map<String, Object> create(ChatContextPlan plan, AiService.ToolInvocation call, MaterialCreateArguments.Input input) {
        if (plan == null || call == null || call.handle() == null || call.handle().sessionId() != plan.sessionId()
                || !Objects.equals(call.requestId(), call.handle().requestId())
                || active.find(call.requestId()).orElse(null) != call.handle()) return error("REJECTED");
        try {
            checkCancellation(call);
            String fingerprint = NoteIndexingService.sha256(json.writer().with(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                    .writeValueAsString(input.values()));
            return transaction.execute(tx -> {
                // First statement is a write: competing creators wait for SQLite's writer lock before reading.
                jdbc.update("INSERT OR IGNORE INTO ai_material_creation(request_id,operation_id,session_id,fingerprint,material_type) VALUES(?,?,?,?,?)",
                        call.requestId(), input.operationId(), plan.sessionId(), fingerprint, input.type());
                requireCurrent(plan, call);
                Receipt previous = findPrevious(plan.sessionId(), call.requestId(), input.operationId(), fingerprint);
                String outcome;
                long id;
                if (previous != null) {
                    if (!previous.fingerprint().equals(fingerprint)) throw new Failure("CONFLICT");
                    id = previous.id();
                    outcome = "DUPLICATE";
                } else {
                    Created created = save(input);
                    id = created.id();
                    outcome = created.changed() ? "CREATED" : "NO_CHANGE";
                }
                Map<String, Object> item = describe(input.type(), id);
                checkCancellation(call);
                jdbc.update("UPDATE ai_material_creation SET material_id=? WHERE request_id=? AND operation_id=?",
                        id, call.requestId(), input.operationId());
                return Map.of("status", outcome, "items", List.of(item), "sourceLink", item.get("sourceLink"), "incomplete", false);
            });
        } catch (Failure e) {
            return error(e.status);
        } catch (EmptyResultDataAccessException e) {
            return error("NOT_FOUND");
        } catch (IllegalArgumentException e) {
            return error("INVALID_ARGUMENT");
        } catch (Exception e) {
            // A commit / connection failure can be ambiguous; reuse operationId to resolve it safely.
            return error("UNAVAILABLE");
        }
    }

    private void requireCurrent(ChatContextPlan plan, AiService.ToolInvocation call) {
        checkCancellation(call);
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM ai_chat_turn t JOIN ai_chat_session s ON s.id=t.session_id
                WHERE t.id=? AND t.request_id=? AND t.user_message_id=? AND t.session_id=?
                  AND t.status='RUNNING' AND t.content_deleted_at IS NULL
                  AND t.context_generation=? AND s.context_generation=t.context_generation
                  AND s.archived_at IS NULL
                """, Integer.class, call.turnId(), call.requestId(), call.userMessageId(), plan.sessionId(), plan.contextGeneration());
        if (count == null || count != 1) throw new Failure("STALE");
    }

    private static void checkCancellation(AiService.ToolInvocation call) {
        if (call.handle().cancelRequested() || Thread.currentThread().isInterrupted()) throw new Failure("CANCELLED");
    }

    private record Receipt(String fingerprint, long id) { }
    private Receipt findPrevious(long sessionId, String request, String operation, String fingerprint) {
        Set<String> seen = new HashSet<>();
        for (int depth = 0; request != null && depth < 64; depth++) {
            if (!seen.add(request)) throw new Failure("STALE");
            var sameOperation = jdbc.queryForList("SELECT fingerprint,material_id FROM ai_material_creation WHERE request_id=? AND operation_id=? AND session_id=?",
                    request, operation, sessionId);
            if (!sameOperation.isEmpty()) {
                var row = sameOperation.get(0);
                if (!fingerprint.equals(row.get("fingerprint"))) throw new Failure("CONFLICT");
                if (row.get("material_id") instanceof Number id) return new Receipt(fingerprint, id.longValue());
            }
            // Re-emitting an identical operation under a different tool-call / operation ID is still a retry.
            var sameContent = jdbc.query("SELECT material_id FROM ai_material_creation WHERE request_id=? AND fingerprint=? AND session_id=? AND material_id IS NOT NULL LIMIT 1",
                    (row, index) -> row.getLong(1), request, fingerprint, sessionId);
            if (!sameContent.isEmpty()) return new Receipt(fingerprint, sameContent.get(0));
            request = com.drillnotebook.app.repository.ChatTurnRepository.retryParent(jdbc, request, sessionId);
        }
        if (request != null) throw new Failure("STALE");
        return null;
    }

    private record Created(long id, boolean changed) { }
    private Created save(MaterialCreateArguments.Input input) {
        try {
            long id;
            switch (input.type()) {
                case "notebook" -> {
                    id = notebooks.insert(input.text("title"));
                    notebooks.insertPage(id, "未命名页面", null);
                }
                case "note" -> {
                    long notebookId = input.id("notebookId");
                    notebooks.find(notebookId);
                    if (input.id("parentPageId") != null) {
                        Map<String, Object> parent = notebooks.findPage(input.id("parentPageId"));
                        if (((Number) parent.get("notebookId")).longValue() != notebookId) throw new Failure("NOT_FOUND");
                    }
                    Map<String, Object> document = Map.of("type", "doc", "content", List.of(
                            Map.of("type", "markdownBlock", "attrs", Map.of("markdown", input.text("content"))),
                            Map.of("type", "paragraph")));
                    id = ((Number) indexing.createAndIndexPage(notebookId, input.text("title"), document, input.id("parentPageId")).get("id")).longValue();
                }
                case "bank" -> id = banks.insert(input.text("title"), input.text("description"), "manual");
                case "knowledge" -> {
                    if (input.id("bankId") != null) banks.find(input.id("bankId"));
                    id = knowledge.insert(input.id("bankId"), input.text("title"), input.text("content"), input.text("category"),
                            input.list("tags"), input.list("headingPath"), input.list("questionIds"));
                }
                case "question" -> id = insertQuestion(input);
                case "wrong" -> {
                    id = input.id("questionId") == null ? insertQuestion(input) : input.id("questionId");
                    return new Created(id, questions.addToWrongBook(id));
                }
                default -> throw new IllegalArgumentException();
            }
            return new Created(id, true);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("资料内容无法保存");
        }
    }

    private long insertQuestion(MaterialCreateArguments.Input input) throws JsonProcessingException {
        banks.find(input.id("bankId"));
        return questions.insert(input.id("bankId"), input.text("questionType"), input.text("stem"),
                questions.optionsJson(input.list("options")), input.text("answer"), input.text("analysis"),
                (int) input.values().get("difficulty"), questions.tagsJson(input.list("tags")), input.text("chapter"), null, null, null);
    }

    private Map<String, Object> describe(String type, long id) {
        var item = new LinkedHashMap<String, Object>();
        item.put("type", type); item.put("id", id);
        item.put("sourceLink", "drill-notebook://material/" + type + "/" + id);
        switch (type) {
            case "notebook" -> item.put("title", notebooks.find(id).get("title"));
            case "note" -> {
                var page = notebooks.findPage(id);
                item.put("title", page.get("title")); item.put("notebookId", page.get("notebookId"));
                item.put("parentPageId", page.get("parentId"));
            }
            case "bank" -> item.put("title", banks.find(id).get("name"));
            case "knowledge" -> {
                var point = knowledge.findById(id);
                item.put("title", point.title); item.put("bankId", point.bankId);
            }
            default -> {
                QuestionRecord question = questions.findById(id);
                item.put("title", question.stem.substring(0, question.stem.offsetByCodePoints(0, Math.min(200, question.stem.codePointCount(0, question.stem.length())))));
                item.put("bankId", question.bankId);
                if ("wrong".equals(type)) {
                    if (!questions.inWrongBook(id)) throw new Failure("NOT_FOUND");
                    item.put("attemptCount", 0); item.put("wrongCount", 0);
                    Map<String, Object> stats = questions.answerStats(List.of(id)).getOrDefault(id, Map.of());
                    for (String key : List.of("attemptCount", "wrongCount")) if (stats.containsKey(key)) item.put(key, stats.get(key));
                }
            }
        }
        return item;
    }

    static Map<String, Object> error(String status) { return Map.of("status", status, "items", List.of(), "incomplete", true); }
    private static final class Failure extends RuntimeException {
        final String status;
        Failure(String status) { this.status = status; }
    }
}
