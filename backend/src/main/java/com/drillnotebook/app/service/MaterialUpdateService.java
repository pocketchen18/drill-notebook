package com.drillnotebook.app.service;

import com.drillnotebook.app.model.ChatContextPlan;
import com.drillnotebook.app.repository.*;
import com.fasterxml.jackson.databind.*;
import java.util.*;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class MaterialUpdateService {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final MaterialReadRepository materials;
    private final NotebookRepository notebooks;
    private final BankRepository banks;
    private final KnowledgePointRepository knowledge;
    private final QuestionRepository questions;
    private final NoteIndexingService indexing;
    private final ActiveTurnRegistry active;
    private final ObjectMapper json;

    public MaterialUpdateService(JdbcTemplate jdbc, PlatformTransactionManager transactions, MaterialReadRepository materials,
            NotebookRepository notebooks, BankRepository banks, KnowledgePointRepository knowledge,
            QuestionRepository questions, NoteIndexingService indexing, ActiveTurnRegistry active, ObjectMapper json) {
        this.jdbc = jdbc; this.transaction = new TransactionTemplate(transactions); this.materials = materials;
        this.notebooks = notebooks; this.banks = banks; this.knowledge = knowledge; this.questions = questions;
        this.indexing = indexing; this.active = active; this.json = json;
    }

    Map<String,Object> update(ChatContextPlan plan, AiService.ToolInvocation call, MaterialUpdateArguments.Input input) {
        if (plan == null || call == null || call.handle() == null || call.handle().sessionId() != plan.sessionId()
                || !Objects.equals(call.requestId(), call.handle().requestId())
                || active.find(call.requestId()).orElse(null) != call.handle()) return MaterialReadService.error("REJECTED");
        try {
            cancelled(call);
            String fingerprint = PageSnapshot.hash(json.writer().with(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).writeValueAsString(
                    Map.of("type", input.type(), "id", input.id(), "expectedVersion", input.expectedVersion(), "changes", input.changes())));
            return transaction.execute(tx -> {
                // Acquire SQLite's writer lock before reading the version or existing receipts.
                jdbc.update("INSERT OR IGNORE INTO ai_material_update(request_id,operation_id,session_id,fingerprint,material_type,material_id) VALUES(?,?,?,?,?,?)",
                        call.requestId(), input.operationId(), plan.sessionId(), fingerprint, input.type(), input.id());
                requireCurrent(plan, call);
                Receipt previous = previous(plan.sessionId(), call.requestId(), input.operationId(), fingerprint);
                Map<String,Object> row = detail(input);
                String currentVersion = MaterialVersion.of(input.type(), row);
                if (previous != null) {
                    completeReceipt(call, input, previous.version(), previous.outcome());
                    cancelled(call);
                    return result(input, row, "DUPLICATE", List.of(), !previous.version().equals(currentVersion));
                }
                if (!currentVersion.equals(input.expectedVersion())) throw new Failure("CONFLICT");
                if (input.type().equals("note") && call.pageSnapshots().values().stream().anyMatch(snapshot ->
                        Boolean.TRUE.equals(snapshot.data().get("isDraft")) && snapshot.data().get("pageId") instanceof Number page && page.longValue() == input.id()))
                    throw new Failure("DRAFT_UNSAVED");
                List<String> changed = save(input, row);
                var saved = detail(input);
                String outcome = changed.isEmpty() ? "NO_CHANGE" : "UPDATED";
                completeReceipt(call, input, MaterialVersion.of(input.type(), saved), outcome);
                cancelled(call);
                return result(input, saved, outcome, changed, false);
            });
        } catch (Failure error) { return MaterialReadService.error(error.status); }
        catch (MaterialNoteEdits.Conflict error) { return MaterialReadService.error("CONFLICT"); }
        catch (EmptyResultDataAccessException error) { return MaterialReadService.error("NOT_FOUND"); }
        catch (IllegalArgumentException error) { return MaterialReadService.error("INVALID_ARGUMENT"); }
        catch (Exception error) { return MaterialReadService.error("UNAVAILABLE"); }
    }

    private void requireCurrent(ChatContextPlan plan, AiService.ToolInvocation call) {
        cancelled(call);
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM ai_chat_turn t JOIN ai_chat_session s ON s.id=t.session_id
                WHERE t.id=? AND t.request_id=? AND t.user_message_id=? AND t.session_id=?
                  AND t.status='RUNNING' AND t.content_deleted_at IS NULL
                  AND t.context_generation=? AND s.context_generation=t.context_generation AND s.archived_at IS NULL
                """, Integer.class, call.turnId(), call.requestId(), call.userMessageId(), plan.sessionId(), plan.contextGeneration());
        if (count == null || count != 1) throw new Failure("STALE");
    }
    private static void cancelled(AiService.ToolInvocation call) {
        if (call.handle().cancelRequested() || Thread.currentThread().isInterrupted()) throw new Failure("CANCELLED");
    }
    private record Receipt(String version, String outcome) { }
    private Receipt previous(long session, String request, String operation, String fingerprint) {
        var seen = new HashSet<String>();
        for (int depth = 0; request != null && depth < 64; depth++) {
            if (!seen.add(request)) throw new Failure("STALE");
            var sameOperation = jdbc.queryForList("SELECT fingerprint,result_version,outcome FROM ai_material_update WHERE request_id=? AND operation_id=? AND session_id=?", request, operation, session);
            if (!sameOperation.isEmpty()) {
                var row = sameOperation.get(0);
                if (!fingerprint.equals(row.get("fingerprint"))) throw new Failure("CONFLICT");
                if (row.get("result_version") instanceof String version) return new Receipt(version, (String) row.get("outcome"));
            }
            var sameContent = jdbc.queryForList("SELECT result_version,outcome FROM ai_material_update WHERE request_id=? AND fingerprint=? AND session_id=? AND result_version IS NOT NULL LIMIT 1", request, fingerprint, session);
            if (!sameContent.isEmpty()) return new Receipt((String)sameContent.get(0).get("result_version"), (String)sameContent.get(0).get("outcome"));
            request = com.drillnotebook.app.repository.ChatTurnRepository.retryParent(jdbc, request, session);
        }
        if (request != null) throw new Failure("STALE");
        return null;
    }
    private void completeReceipt(AiService.ToolInvocation call, MaterialUpdateArguments.Input input, String version, String outcome) {
        jdbc.update("UPDATE ai_material_update SET result_version=?,outcome=? WHERE request_id=? AND operation_id=?",
                version, outcome, call.requestId(), input.operationId());
    }
    private Map<String,Object> detail(MaterialUpdateArguments.Input input) {
        var row = materials.detail(input.type().equals("wrong") ? "question" : input.type(), input.id());
        if (row == null || input.type().equals("wrong") && !questions.inWrongBook(input.id())) throw new Failure("NOT_FOUND");
        return row;
    }

    private List<String> save(MaterialUpdateArguments.Input input, Map<String,Object> before) {
        Map<String,Object> changes = new LinkedHashMap<>(input.changes());
        if (changes.containsKey("answer")) changes.put("answer", QuestionTypeRules.canonicalAnswer(
                Objects.toString(changes.getOrDefault("questionType", before.get("questionType"))), (String) changes.get("answer")));
        changes.entrySet().removeIf(entry -> !Set.of("edits", "append").contains(entry.getKey())
                && Objects.equals(normalized(before.get(entry.getKey()), entry.getKey()), normalized(entry.getValue(), entry.getKey())));
        if (changes.isEmpty()) return List.of();
        try {
            switch (input.type()) {
                case "notebook" -> notebooks.update(input.id(), (String) changes.get("title"));
                case "bank" -> banks.update(input.id(), (String)changes.get("title"), (String)changes.get("description"), null);
                case "note" -> {
                    JsonNode document = null;
                    if (changes.containsKey("edits") || changes.containsKey("append")) {
                        document = MaterialNoteEdits.apply((String)before.get("content"), changes, json);
                        if (document.equals(json.readTree((String)before.get("content")))) {
                            document = null; changes.remove("edits"); changes.remove("append");
                        }
                    }
                    if (!changes.isEmpty()) indexing.savePageAndIndex(input.id(), (String) changes.get("title"), document);
                }
                case "knowledge" -> {
                    var current = knowledge.findById(input.id());
                    knowledge.update(input.id(), (String)changes.getOrDefault("title", current.title), (String)changes.getOrDefault("content", current.content),
                            (String)changes.getOrDefault("category", current.category), list(changes.getOrDefault("tags", current.tags)),
                            list(changes.getOrDefault("headingPath", current.headingPath)), list(changes.getOrDefault("questionIds", knowledge.questionIds(input.id()))));
                }
                default -> {
                    var body = new LinkedHashMap<>(changes);
                    if (body.containsKey("questionType")) body.put("type", body.remove("questionType"));
                    questions.update(input.id(), body);
                }
            }
            return List.copyOf(changes.keySet());
        } catch (com.fasterxml.jackson.core.JsonProcessingException error) { throw new IllegalArgumentException(error); }
    }
    private Object normalized(Object value, String key) {
        if (Set.of("tags", "headingPath", "questionIds", "options").contains(key)) {
            try {
                JsonNode array = value == null ? json.createArrayNode() : value instanceof String raw ? json.readTree(raw) : json.valueToTree(value);
                if (key.equals("questionIds")) {
                    var ids = new ArrayList<Long>();
                    for (JsonNode id : array) ids.add(id.longValue());
                    ids.sort(Long::compareTo);
                    return ids;
                }
                return array;
            }
            catch (Exception error) { throw new IllegalArgumentException(error); }
        }
        if (value == null && Set.of("analysis", "category", "chapter", "description").contains(key)) return "";
        return value;
    }
    @SuppressWarnings("unchecked") private static <T> List<T> list(Object value) { return (List<T>) value; }
    private Map<String,Object> result(MaterialUpdateArguments.Input input, Map<String,Object> row, String outcome, List<String> changed, boolean changedSince) {
        String title = Objects.toString(row.getOrDefault("title", row.get("stem")), "");
        String link = MaterialReadService.link(input.type(), input.id());
        var item = new LinkedHashMap<String,Object>();
        item.put("type", input.type()); item.put("id", input.id()); item.put("title", MaterialReadService.clip(title, 200)); item.put("sourceLink", link);
        for (String key : List.of("notebookId", "bankId")) if (row.get(key) != null) item.put(key, row.get(key));
        item.put("changedFields", changed);
        item.put("changedSinceOperation", changedSince);
        if (changedSince) item.put("notice", "原操作已处理；资料随后有变化，本次未重新修改。请重新读取后核对。");
        return Map.of("status", outcome, "items", List.of(item), "sourceLink", link, "incomplete", false);
    }
    private static final class Failure extends RuntimeException {
        final String status;
        Failure(String status) { this.status = status; }
    }
}
