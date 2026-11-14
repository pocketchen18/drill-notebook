package com.drillnotebook.app.service;

import com.drillnotebook.app.model.ChatContextPlan;
import com.drillnotebook.app.repository.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.util.*;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 删除与回执同成同败；级联复用人工删除路径，回执只留身份与结果。 */
@Service
public class MaterialDeleteService {
    static final Set<String> TYPES = Set.of("notebook", "note", "knowledge", "bank", "question");
    /** 移出错题本共用同一套回执，类型记为 wrong；它不删除原题。 */
    static final String WRONG = "wrong";

    record Input(String type, long id, String operationId, String expectedVersion, boolean cascade) { }

    /** 两个工具共用的严格参数：类型由调用方决定是否来自模型。 */
    static Input parse(String raw, String fixedType) {
        Set<String> fields = fixedType == null ? Set.of("type", "id", "operationId", "expectedVersion", "cascade")
                : Set.of("questionId", "operationId", "expectedVersion");
        JsonNode node = SessionToolArguments.parse(raw, fields);
        String idField = fixedType == null ? "id" : "questionId";
        if (node == null || !node.has(idField)) throw new IllegalArgumentException();
        String type = fixedType == null ? SessionToolArguments.text(node, "type", "", 20) : fixedType;
        if (fixedType == null && !TYPES.contains(type)) throw new IllegalArgumentException();
        String operation = SessionToolArguments.text(node, "operationId", "", 80);
        String version = SessionToolArguments.text(node, "expectedVersion", "", 64);
        if (!operation.matches("[a-zA-Z0-9_-]{1,80}") || !version.matches("[a-f0-9]{64}")) throw new IllegalArgumentException();
        boolean cascade = fixedType == null && SessionToolArguments.flag(node, "cascade");
        if (cascade && !Set.of("notebook", "bank").contains(type)) throw new IllegalArgumentException();
        return new Input(type, SessionToolArguments.number(node, idField, 0, Long.MAX_VALUE), operation, version, cascade);
    }

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final MaterialReadRepository materials;
    private final BankRepository banks;
    private final KnowledgePointRepository knowledge;
    private final QuestionRepository questions;
    private final NoteIndexingService indexing;
    private final ActiveTurnRegistry active;
    private final ObjectMapper json;

    public MaterialDeleteService(JdbcTemplate jdbc, PlatformTransactionManager transactions, MaterialReadRepository materials,
            BankRepository banks, KnowledgePointRepository knowledge, QuestionRepository questions,
            NoteIndexingService indexing, ActiveTurnRegistry active, ObjectMapper json) {
        this.jdbc = jdbc; this.transaction = new TransactionTemplate(transactions); this.materials = materials;
        this.banks = banks; this.knowledge = knowledge; this.questions = questions;
        this.indexing = indexing; this.active = active; this.json = json;
    }

    Map<String,Object> delete(ChatContextPlan plan, AiService.ToolInvocation call, Input input) {
        if (plan == null || call == null || call.handle() == null || call.handle().sessionId() != plan.sessionId()
                || !Objects.equals(call.requestId(), call.handle().requestId())
                || active.find(call.requestId()).orElse(null) != call.handle()) return MaterialReadService.error("REJECTED");
        try {
            cancelled(call);
            String fingerprint = PageSnapshot.hash(json.writer().with(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).writeValueAsString(
                    Map.of("type", input.type(), "id", input.id(), "expectedVersion", input.expectedVersion(), "cascade", input.cascade())));
            boolean[] vaultChanged = new boolean[1];
            Map<String,Object> result = transaction.execute(tx -> {
                // 第一条语句即写入：竞争者先等到 SQLite 写锁，再读取版本与既有回执。
                jdbc.update("INSERT OR IGNORE INTO ai_material_deletion(request_id,operation_id,session_id,fingerprint,material_type,material_id) VALUES(?,?,?,?,?,?)",
                        call.requestId(), input.operationId(), plan.sessionId(), fingerprint, input.type(), input.id());
                requireCurrent(plan, call);
                String previous = previous(plan.sessionId(), call.requestId(), input.operationId(), fingerprint);
                if (previous != null) {
                    completeReceipt(call, input, previous);
                    cancelled(call);
                    return result(input, null, "DUPLICATE", Map.of());
                }
                Map<String,Object> row = materials.detail(WRONG.equals(input.type()) ? "question" : input.type(), input.id());
                if (row == null) throw new Failure("NOT_FOUND");
                if (!MaterialVersion.of(input.type(), row).equals(input.expectedVersion())) throw new Failure("CONFLICT");
                Map<String,Object> removed = new LinkedHashMap<>();
                String outcome = "DELETED";
                switch (input.type()) {
                    case WRONG -> {
                        if (questions.inWrongBook(input.id())) { questions.setWrongExcluded(input.id(), true); outcome = "REMOVED"; }
                        else outcome = "NO_CHANGE";
                    }
                    case "note" -> indexing.deletePage(input.id());
                    case "knowledge" -> knowledge.delete(input.id());
                    case "question" -> questions.delete(input.id());
                    case "notebook" -> {
                        int pages = count("SELECT COUNT(*) FROM note_page WHERE notebook_id=?", input.id());
                        if (pages > 0 && !input.cascade()) throw new Failure("NOT_EMPTY", Map.of("pages", pages));
                        // 清理记忆会重置绑定该笔记本的会话上下文；调用会话自身被重置时本轮无法继续。
                        if (count("SELECT COUNT(*) FROM ai_chat_session WHERE id=? AND memory_scope_type='NOTEBOOK' AND memory_notebook_id=?",
                                plan.sessionId(), input.id()) > 0) throw new Failure("IN_USE");
                        vaultChanged[0] = purgeNotebookMemories(input.id());
                        indexing.deleteNotebook(input.id());
                        removed.put("pages", pages);
                    }
                    default -> {
                        int questionCount = count("SELECT COUNT(*) FROM question WHERE bank_id=?", input.id());
                        int pointCount = count("SELECT COUNT(*) FROM knowledge_point WHERE bank_id=?", input.id());
                        if (questionCount + pointCount > 0 && !input.cascade())
                            throw new Failure("NOT_EMPTY", Map.of("questions", questionCount, "knowledgePoints", pointCount));
                        banks.delete(input.id());
                        removed.put("questions", questionCount); removed.put("knowledgePoints", pointCount);
                    }
                }
                completeReceipt(call, input, outcome);
                cancelled(call);
                return result(input, row, outcome, removed);
            });
            if (!vaultChanged[0]) return result;
            var marked = new LinkedHashMap<>(result);
            marked.put(AiService.VAULT_CHANGED, true);
            return marked;
        } catch (Failure error) {
            if (error.contains.isEmpty()) return MaterialReadService.error(error.status);
            return Map.of("status", error.status, "items", List.of(), "contains", error.contains, "incomplete", true);
        }
        catch (EmptyResultDataAccessException error) { return MaterialReadService.error("NOT_FOUND"); }
        catch (IllegalArgumentException error) { return MaterialReadService.error("INVALID_ARGUMENT"); }
        // 提交或连接失败时结果可能未知；复用 operationId 重试即可安全核对。
        catch (Exception error) { return MaterialReadService.error("UNAVAILABLE"); }
    }

    /** 与笔记本删除同一事务清理其 NOTEBOOK 记忆；返回记忆库版本是否因此推进。 */
    private boolean purgeNotebookMemories(long notebookId) {
        var vault = jdbc.queryForList("SELECT id,revision FROM ai_memory_vault WHERE singleton=1");
        if (vault.isEmpty()) return false;
        String vaultId = (String) vault.get(0).get("id");
        jdbc.execute((ConnectionCallback<Integer>) connection -> MemoryRepository.purgeNotebookScopeIn(connection, vaultId, notebookId));
        Long revision = jdbc.queryForObject("SELECT revision FROM ai_memory_vault WHERE id=?", Long.class, vaultId);
        return !Objects.equals(revision, ((Number) vault.get(0).get("revision")).longValue());
    }

    private int count(String sql, Object... args) {
        Integer value = jdbc.queryForObject(sql, Integer.class, args);
        return value == null ? 0 : value;
    }

    private void requireCurrent(ChatContextPlan plan, AiService.ToolInvocation call) {
        cancelled(call);
        int running = count("""
                SELECT COUNT(*) FROM ai_chat_turn t JOIN ai_chat_session s ON s.id=t.session_id
                WHERE t.id=? AND t.request_id=? AND t.user_message_id=? AND t.session_id=?
                  AND t.status='RUNNING' AND t.content_deleted_at IS NULL
                  AND t.context_generation=? AND s.context_generation=t.context_generation AND s.archived_at IS NULL
                """, call.turnId(), call.requestId(), call.userMessageId(), plan.sessionId(), plan.contextGeneration());
        if (running != 1) throw new Failure("STALE");
    }
    private static void cancelled(AiService.ToolInvocation call) {
        if (call.handle().cancelRequested() || Thread.currentThread().isInterrupted()) throw new Failure("CANCELLED");
    }

    /** 同操作、同指纹或显式重试链上已完成的回执；参数变化视为冲突，不换标识绕过。 */
    private String previous(long session, String request, String operation, String fingerprint) {
        var seen = new HashSet<String>();
        for (int depth = 0; request != null && depth < 64; depth++) {
            if (!seen.add(request)) throw new Failure("STALE");
            var sameOperation = jdbc.queryForList("SELECT fingerprint,outcome FROM ai_material_deletion WHERE request_id=? AND operation_id=? AND session_id=?", request, operation, session);
            if (!sameOperation.isEmpty()) {
                var row = sameOperation.get(0);
                if (!fingerprint.equals(row.get("fingerprint"))) throw new Failure("CONFLICT");
                if (row.get("outcome") instanceof String outcome) return outcome;
            }
            var sameContent = jdbc.queryForList("SELECT outcome FROM ai_material_deletion WHERE request_id=? AND fingerprint=? AND session_id=? AND outcome IS NOT NULL LIMIT 1", request, fingerprint, session);
            if (!sameContent.isEmpty()) return (String) sameContent.get(0).get("outcome");
            request = com.drillnotebook.app.repository.ChatTurnRepository.retryParent(jdbc, request, session);
        }
        if (request != null) throw new Failure("STALE");
        return null;
    }
    private void completeReceipt(AiService.ToolInvocation call, Input input, String outcome) {
        jdbc.update("UPDATE ai_material_deletion SET outcome=? WHERE request_id=? AND operation_id=?",
                outcome, call.requestId(), input.operationId());
    }

    private Map<String,Object> result(Input input, Map<String,Object> row, String outcome, Map<String,Object> removed) {
        // 移出错题本后原题仍在，来源指向原题；已删除对象的链接只用于界面刷新，不再可打开。
        String link = MaterialReadService.link(WRONG.equals(input.type()) ? "question" : input.type(), input.id());
        var item = new LinkedHashMap<String,Object>();
        item.put("type", input.type()); item.put("id", input.id()); item.put("sourceLink", link);
        if (row != null) {
            item.put("title", MaterialReadService.clip(Objects.toString(row.getOrDefault("title", row.get("stem")), ""), 200));
            for (String key : List.of("notebookId", "bankId")) if (row.get(key) != null) item.put(key, row.get(key));
        }
        if (!removed.isEmpty()) item.put("deleted", removed);
        return Map.of("status", outcome, "items", List.of(item), "sourceLink", link, "incomplete", false);
    }

    private static final class Failure extends RuntimeException {
        final String status;
        final Map<String,Object> contains;
        Failure(String status) { this(status, Map.of()); }
        Failure(String status, Map<String,Object> contains) { this.status = status; this.contains = contains; }
    }
}
