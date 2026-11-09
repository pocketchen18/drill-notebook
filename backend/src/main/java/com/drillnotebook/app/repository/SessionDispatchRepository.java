package com.drillnotebook.app.repository;

import com.drillnotebook.app.config.ImmediateTransaction;
import com.drillnotebook.app.service.ChatTurnRejectException;
import java.sql.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Receipts survive restarts; prompts, API keys and unlock material do not. */
@Repository
public class SessionDispatchRepository {
    public record Receipt(String requestId, long sessionId, String fingerprint, String state, long sequence) { }
    private final JdbcTemplate jdbc;
    private final ImmediateTransaction tx;
    public SessionDispatchRepository(JdbcTemplate jdbc, ImmediateTransaction tx) { this.jdbc = jdbc; this.tx = tx; }
    private static Receipt row(ResultSet r) throws SQLException {
        return new Receipt(r.getString("request_id"), r.getLong("session_id"), r.getString("fingerprint"),
                r.getString("state"), r.getLong("sequence"));
    }
    public Receipt find(String id) {
        return jdbc.query("SELECT * FROM ai_session_dispatch WHERE request_id = ?", (r,n) -> row(r), id)
                .stream().findFirst().orElse(null);
    }
    public Receipt bySource(String key) {
        return jdbc.query("SELECT * FROM ai_session_dispatch WHERE source_key = ?", (r,n) -> row(r), key)
                .stream().findFirst().orElse(null);
    }
    public List<Receipt> latest() {
        return jdbc.query("""
                SELECT * FROM ai_session_dispatch WHERE sequence IN (
                  SELECT COALESCE(MAX(CASE WHEN d.state <> 'CANCELLED' THEN d.sequence END),MAX(d.sequence))
                  FROM ai_session_dispatch d JOIN ai_chat_session s ON s.id=d.session_id
                  WHERE NOT EXISTS(SELECT 1 FROM ai_chat_pending p WHERE p.request_id=d.request_id AND p.parent_request_id IS NOT NULL) GROUP BY d.session_id)
                """,
                (r,n) -> row(r));
    }
    public Receipt accept(String id, Long target, Long source, String sourceKey, String fingerprint,
                          String title, String model) {
        return accept(id,target,source,sourceKey,fingerprint,title,model,null);
    }
    public Receipt accept(String id, Long target, Long source, String sourceKey, String fingerprint,
                          String title, String model, Long sourceGeneration) {
        try { return tx.execute(c -> {
            if(sourceGeneration!=null) {
                var caller=ChatSessionMetadataRepository.read(c,source);
                if(caller.isEmpty() || caller.get().contextGeneration()!=sourceGeneration)
                    throw reject("STALE","来源会话已变化，请重新发起请求");
            }
            long session = target == null ? create(c, source, title, model) : target;
            try (var s = c.prepareStatement("SELECT archived, archived_at FROM ai_chat_session WHERE id = ?")) {
                s.setLong(1, session);
                try (var r = s.executeQuery()) {
                    if (!r.next()) throw reject("NOT_FOUND", "目标会话不存在");
                    if (r.getBoolean(1) || r.getString(2) != null) throw reject("ARCHIVED", "目标会话已归档");
                }
            }
            try (var s = c.prepareStatement("INSERT INTO ai_session_dispatch(request_id,session_id,source_key,fingerprint,state) VALUES(?,?,?,?,'QUEUED')")) {
                s.setString(1,id); s.setLong(2,session); s.setString(3,sourceKey); s.setString(4,fingerprint); s.executeUpdate();
            }
            try (var s = c.prepareStatement("SELECT * FROM ai_session_dispatch WHERE request_id = ?")) {
                s.setString(1,id); try(var r=s.executeQuery()) { r.next(); return row(r); }
            }
        }); } catch(SQLException e) { throw new IllegalStateException("会话消息受理失败", e); }
    }
    private long create(Connection c, Long source, String title, String model) throws SQLException {
        var state = source == null ? null : ChatSessionMetadataRepository.read(c, source).orElseThrow(() -> reject("NOT_FOUND", "来源会话不存在"));
        try (var s = c.prepareStatement("""
                INSERT INTO ai_chat_session(title,model,session_ref,title_source,title_status,memory_scope_type,memory_notebook_id)
                VALUES(?,?,?,'temporary','pending',?,?)
                """, Statement.RETURN_GENERATED_KEYS)) {
            s.setString(1, title == null ? "新会话" : title); s.setString(2,model);
            s.setString(3,UUID.randomUUID().toString().replace("-",""));
            s.setString(4,state == null ? "GLOBAL" : state.memoryScopeType());
            s.setObject(5,state == null ? null : state.memoryNotebookId()); s.executeUpdate();
            try(var r=s.getGeneratedKeys()) { r.next(); return r.getLong(1); }
        }
    }
    public void state(String id, String state) {
        jdbc.update("UPDATE ai_session_dispatch SET state=?, finished_at=CASE WHEN ? IN ('RUNNING','QUEUED') THEN NULL ELSE datetime('now') END WHERE request_id=?",
                state,state,id);
    }
    public void recover() {
        // A completed turn may have committed just before the receipt checkpoint was interrupted.
        jdbc.update("""
                UPDATE ai_session_dispatch SET state=COALESCE(
                  (SELECT status FROM ai_chat_turn WHERE request_id=ai_session_dispatch.request_id AND status <> 'RUNNING'),
                  CASE WHEN state='QUEUED' THEN 'CANCELLED' ELSE 'INTERRUPTED' END), finished_at=datetime('now')
                WHERE state IN ('QUEUED','RUNNING')
                """);
    }
    public static boolean busy(Connection c,long session) throws SQLException {
        try(var s=c.prepareStatement("SELECT 1 FROM ai_session_dispatch WHERE session_id=? AND state IN ('QUEUED','RUNNING') LIMIT 1")) {
            s.setLong(1,session); try(var r=s.executeQuery()) { return r.next(); }
        }
    }
    public static boolean blocksStart(Connection c,long session,String request) throws SQLException {
        try(var s=c.prepareStatement("SELECT request_id FROM ai_session_dispatch d WHERE session_id=? AND state IN ('QUEUED','RUNNING') AND NOT EXISTS(SELECT 1 FROM ai_chat_pending p WHERE p.request_id=d.request_id AND p.parent_request_id IS NOT NULL) ORDER BY sequence LIMIT 1")) {
            s.setLong(1,session); try(var r=s.executeQuery()) { return r.next() && !r.getString(1).equals(request); }
        }
    }
    private static ChatTurnRejectException reject(String code,String text) { return new ChatTurnRejectException(409,code,text,Map.of()); }
}
