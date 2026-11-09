package com.drillnotebook.app.repository;

import com.drillnotebook.app.config.ImmediateTransaction;
import com.drillnotebook.app.service.ChatMessageCipher;
import com.drillnotebook.app.service.ChatTurnRejectException;
import java.sql.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Encrypted human input; accepting a receipt and its recoverable content is one transaction. */
@Repository
public class ChatPendingRepository {
    public record Pending(String requestId,long sessionId,String state,String parent,int offset,int revision,
                          String cipher,String meta,long sequence) { }
    private final JdbcTemplate jdbc;
    private final ImmediateTransaction tx;
    public ChatPendingRepository(JdbcTemplate jdbc,ImmediateTransaction tx) { this.jdbc=jdbc;this.tx=tx; }
    private static final String SELECT="SELECT p.*,d.sequence FROM ai_chat_pending p JOIN ai_session_dispatch d ON d.request_id=p.request_id";
    private static Pending row(ResultSet r) throws SQLException {
        return new Pending(r.getString("request_id"),r.getLong("session_id"),r.getString("state"),
                r.getString("parent_request_id"),r.getInt("reply_offset"),r.getInt("revision"),
                r.getString("payload_cipher"),r.getString("payload_meta"),r.getLong("sequence"));
    }
    public Pending find(String id) { return jdbc.query(SELECT+" WHERE p.request_id=?",(r,n)->row(r),id).stream().findFirst().orElse(null); }
    public List<Pending> session(long id) { return jdbc.query(SELECT+" WHERE p.session_id=? AND p.state NOT IN ('CONSUMED','REMOVED') ORDER BY d.sequence",(r,n)->row(r),id); }
    public List<Pending> supplements(String parent) { return jdbc.query(SELECT+" WHERE p.parent_request_id=? AND p.state IN ('STEERING','CLAIMED','SENDING','RECEIVED','UNKNOWN') ORDER BY d.sequence",(r,n)->row(r),parent); }
    public List<Pending> recoverable() { return jdbc.query(SELECT+" WHERE p.state='PENDING' ORDER BY d.sequence",(r,n)->row(r)); }
    public int countPending() { return jdbc.queryForObject("SELECT count(*) FROM ai_chat_pending WHERE state IN ('PENDING','STEERING','CLAIMED','SENDING','UNKNOWN')",Integer.class); }
    public boolean paused(long session) { return Boolean.TRUE.equals(jdbc.query("SELECT paused FROM ai_chat_queue_state WHERE session_id=?",(r,n)->r.getBoolean(1),session).stream().findFirst().orElse(false)); }
    public String editing(long session) { return jdbc.query("SELECT editing_id FROM ai_chat_queue_state WHERE session_id=?",(r,n)->r.getString(1),session).stream().filter(Objects::nonNull).findFirst().orElse(null); }
    public void pause(long session,boolean paused) {
        jdbc.update("INSERT INTO ai_chat_queue_state(session_id,paused) VALUES(?,?) ON CONFLICT(session_id) DO UPDATE SET paused=excluded.paused",session,paused?1:0);
    }
    public void beginEdit(long session,String id) {
        pause(session,paused(session));
        jdbc.update("UPDATE ai_chat_queue_state SET resume_after_edit=CASE WHEN paused=0 THEN 1 ELSE 0 END,paused=1,editing_id=? WHERE session_id=?",id,session);
    }
    public void endEdit(long session) { jdbc.update("UPDATE ai_chat_queue_state SET paused=CASE WHEN resume_after_edit=1 THEN 0 ELSE 1 END,editing_id=NULL,resume_after_edit=0 WHERE session_id=?",session); }
    public void forcePause(long session) { pause(session,true);jdbc.update("UPDATE ai_chat_queue_state SET resume_after_edit=0 WHERE session_id=?",session); }
    public SessionDispatchRepository.Receipt accept(String id,long session,String fingerprint,ChatMessageCipher.Encrypted value,String parent) {
        purgeRemoved();
        try { return tx.execute(c->{
            try(var s=c.prepareStatement("SELECT archived,archived_at FROM ai_chat_session WHERE id=?")) {
                s.setLong(1,session);try(var r=s.executeQuery()) {if(!r.next()||r.getBoolean(1)||r.getString(2)!=null)throw reject("NOT_FOUND","目标会话不存在或已归档");}
            }
            checkSpace(c,id,value);
            try(var s=c.prepareStatement("INSERT INTO ai_session_dispatch(request_id,session_id,fingerprint,state) VALUES(?,?,?,'QUEUED')")) {
                s.setString(1,id);s.setLong(2,session);s.setString(3,fingerprint);s.executeUpdate();
            }
            try(var s=c.prepareStatement("INSERT INTO ai_chat_pending(request_id,session_id,state,parent_request_id,payload_cipher,payload_meta,payload_bytes) VALUES(?,?,?,?,?,?,?)")) {
                s.setString(1,id);s.setLong(2,session);s.setString(3,parent==null?"PENDING":"STEERING");s.setString(4,parent);
                s.setString(5,value.cipher());s.setString(6,value.meta());s.setLong(7,bytes(value));s.executeUpdate();
            }
            try(var s=c.prepareStatement("SELECT sequence FROM ai_session_dispatch WHERE request_id=?")) {
                s.setString(1,id);try(var r=s.executeQuery()){r.next();return new SessionDispatchRepository.Receipt(id,session,fingerprint,"QUEUED",r.getLong(1));}
            }
        }); }catch(SQLException e){throw new IllegalStateException("待发送消息无法保存",e);}
    }
    public void update(Pending row,ChatMessageCipher.Encrypted value) {
        try { tx.execute(c->{
            checkSpace(c,row.requestId(),value);
            try(var s=c.prepareStatement("UPDATE ai_chat_pending SET payload_cipher=?,payload_meta=?,payload_bytes=?,revision=revision+1 WHERE request_id=? AND revision=? AND state='PENDING'")) {
                s.setString(1,value.cipher());s.setString(2,value.meta());s.setLong(3,bytes(value));s.setString(4,row.requestId());s.setInt(5,row.revision());
                if(s.executeUpdate()!=1)throw reject("QUEUE_CHANGED","这条消息已变化，请刷新后重试");
            }return null;
        }); }catch(SQLException e){throw new IllegalStateException("待发送消息无法保存",e);}
    }
    public void state(String id,String state) {
        jdbc.update("UPDATE ai_chat_pending SET state=?,revision=revision+1,removed_at=CASE WHEN ?='REMOVED' THEN datetime('now') ELSE NULL END WHERE request_id=?",state,state,id);
        if("CONSUMED".equals(state))jdbc.update("UPDATE ai_chat_pending SET payload_cipher='',payload_meta='',payload_bytes=0 WHERE request_id=?",id);
    }
    public void steer(String id,String parent) { jdbc.update("UPDATE ai_chat_pending SET state='STEERING',parent_request_id=?,revision=revision+1 WHERE request_id=?",parent,id); }
    public void claim(String id,int offset) { jdbc.update("UPDATE ai_chat_pending SET state='CLAIMED',reply_offset=?,revision=revision+1 WHERE request_id=? AND state='STEERING'",offset,id); }
    public void restore(String id) { jdbc.update("UPDATE ai_chat_pending SET state='PENDING',parent_request_id=NULL,reply_offset=0,removed_at=NULL,revision=revision+1 WHERE request_id=?",id); }
    public boolean canUndo(String id) { return Boolean.TRUE.equals(jdbc.queryForObject("SELECT state='REMOVED' AND parent_request_id IS NULL AND payload_bytes>0 AND removed_at>=datetime('now','-10 seconds') FROM ai_chat_pending WHERE request_id=?",Boolean.class,id)); }
    public void recover() {
        jdbc.update("UPDATE ai_chat_pending SET state='CONSUMED',payload_cipher='',payload_meta='',payload_bytes=0 WHERE state='PENDING' AND EXISTS(SELECT 1 FROM ai_chat_turn t WHERE t.request_id=ai_chat_pending.request_id)");
        jdbc.update("UPDATE ai_chat_pending SET state='UNKNOWN' WHERE state='SENDING'");
        jdbc.update("UPDATE ai_chat_pending SET state='PENDING',parent_request_id=NULL WHERE state IN ('STEERING','CLAIMED')");
        jdbc.update("INSERT INTO ai_chat_queue_state(session_id,paused) SELECT DISTINCT session_id,1 FROM ai_chat_pending WHERE state='PENDING' ON CONFLICT(session_id) DO UPDATE SET paused=1,editing_id=NULL,resume_after_edit=0");
        jdbc.update("UPDATE ai_session_dispatch SET state='QUEUED',finished_at=NULL WHERE request_id IN(SELECT request_id FROM ai_chat_pending WHERE state='PENDING')");
        jdbc.update("UPDATE ai_session_dispatch SET state='INTERRUPTED' WHERE request_id IN(SELECT request_id FROM ai_chat_pending WHERE state='UNKNOWN')");
        jdbc.update("UPDATE ai_chat_queue_state SET paused=0,editing_id=NULL,resume_after_edit=0 WHERE NOT EXISTS(SELECT 1 FROM ai_chat_pending p WHERE p.session_id=ai_chat_queue_state.session_id AND p.state='PENDING')");
        purgeRemoved();
    }
    public void purgeRemoved(){jdbc.update("UPDATE ai_chat_pending SET state='CONSUMED',payload_cipher='',payload_meta='',payload_bytes=0,revision=revision+1 WHERE state='REMOVED' AND removed_at < datetime('now','-10 seconds')");}
    public long storedBytes(){return jdbc.queryForObject("SELECT COALESCE(SUM(payload_bytes),0) FROM ai_chat_pending",Long.class);}
    private static long bytes(ChatMessageCipher.Encrypted v){return v.cipher().getBytes(StandardCharsets.UTF_8).length+v.meta().getBytes(StandardCharsets.UTF_8).length;}
    private static void checkSpace(Connection c,String id,ChatMessageCipher.Encrypted value)throws SQLException {
        try(var s=c.prepareStatement("SELECT COALESCE((SELECT SUM(payload_bytes) FROM ai_chat_pending WHERE state <> 'CONSUMED' AND request_id<>?),0),history_limit_bytes FROM ai_chat_storage WHERE id=1")) {
            s.setString(1,id);try(var r=s.executeQuery()){if(r.next()&&r.getLong(1)+bytes(value)>Math.min(64L*1024*1024,r.getLong(2)))throw reject("QUEUE_STORAGE_FULL","待发及插话内容存储空间不足，请移除待发消息或清理历史");}
        }
    }
    private static ChatTurnRejectException reject(String code,String message){return new ChatTurnRejectException(409,code,message,Map.of());}
}
