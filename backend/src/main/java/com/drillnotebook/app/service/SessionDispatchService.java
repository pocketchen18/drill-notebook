package com.drillnotebook.app.service;

import com.drillnotebook.app.model.ChatContextPlan;
import com.drillnotebook.app.model.UuidV7;
import com.drillnotebook.app.repository.*;
import com.drillnotebook.app.repository.SessionDispatchRepository.Receipt;
import com.drillnotebook.app.service.memory.MemoryVaultService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BiConsumer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Owns execution independently of browser subscriptions. No network I/O under the scheduler lock. */
@Service
public class SessionDispatchService {
    private final ObjectProvider<AiService> ai;
    private final SessionDispatchRepository receipts;
    private final AiChatSessionRepository sessions;
    private final ChatTurnService turns;
    private final MemoryVaultService vault;
    private final ChatMessageCipher cipher;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final Map<Long, ArrayDeque<Run>> queues = new HashMap<>();
    private final Map<String, Run> active = new HashMap<>();
    private final ExecutorService workers = Executors.newFixedThreadPool(64, task -> {
        var t = new Thread(task, "ai-session-run"); t.setDaemon(true); return t;
    });
    private boolean closed;

    private static final class Run {
        final Receipt receipt;
        final Map<String,Object> body;
        final AiService.AcceptedConfig config;
        final SessionExecution execution;
        final CompletableFuture<Map<String,Object>> done = new CompletableFuture<>();
        final List<BiConsumer<String,Map<String,Object>>> subscribers = new CopyOnWriteArrayList<>();
        final StringBuilder text = new StringBuilder(), reasoning = new StringBuilder();
        Map<String,Object> metadata = Map.of();
        volatile boolean cancelled, started;
        volatile String phase = "queued";
        Run(Receipt receipt,Map<String,Object> body,AiService.AcceptedConfig config,SessionExecution execution) {
            this.receipt=receipt; this.body=body; this.config=config; this.execution=execution;
        }
        synchronized void event(String name,Map<String,Object> value) {
            if (name.equals("text")) { text.append(value.get("text")); phase="answer"; }
            if (name.equals("reasoning")) { reasoning.append(value.get("text")); phase="thinking"; }
            if (name.equals("metadata") || name.equals("tool_activity")) {
                var merged=new LinkedHashMap<>(metadata); merged.putAll(value); metadata=merged;
                phase=String.valueOf(value.getOrDefault("phase","running"));
            }
            for(var subscriber:subscribers) { try { subscriber.accept(name,value); } catch(RuntimeException e) { subscribers.remove(subscriber); } }
        }
        synchronized Map<String,Object> snapshot() {
            var view=new LinkedHashMap<String,Object>(metadata);
            view.put("requestId",receipt.requestId()); view.put("sessionId",receipt.sessionId());
            view.put("status",started ? "RUNNING" : "QUEUED"); view.put("active",true);
            view.put("phase",cancelled ? "stopping" : phase);
            view.put("reply",text.toString()); view.put("reasoning",reasoning.toString());
            if(started) view.put("userText",inputText(body));
            return view;
        }
    }

    public SessionDispatchService(ObjectProvider<AiService> ai,SessionDispatchRepository receipts,
                                  AiChatSessionRepository sessions,ChatTurnService turns,MemoryVaultService vault,
                                  ChatMessageCipher cipher,JdbcTemplate jdbc,ObjectMapper json) {
        this.ai=ai; this.receipts=receipts; this.sessions=sessions; this.turns=turns; this.vault=vault;
        this.cipher=cipher; this.jdbc=jdbc; this.json=json;
    }
    @EventListener(ApplicationReadyEvent.class)
    public synchronized void recover() { receipts.recover(); }

    public synchronized Map<String,Object> submit(Map<String,Object> body) {
        if (!Integer.valueOf(2).equals(body.get("protocolVersion"))) throw reject("INVALID_ARGUMENT","后台会话要求 v2 请求");
        String id=Objects.toString(body.get("requestId"),"");
        if (!UuidV7.isCanonical(id) || !(body.get("sessionId") instanceof Number n)) throw reject("INVALID_ARGUMENT","发送标识或会话无效");
        String fingerprint=hash(body);
        Receipt existing=receipts.find(id);
        if (existing!=null) { checkFingerprint(existing,fingerprint); return accepted(existing,false); }
        if(jdbc.queryForObject("SELECT count(*) FROM ai_chat_turn WHERE request_id=?",Integer.class,id)==0) {
            var freshness=UuidV7.freshness(id,System.currentTimeMillis());
            if(freshness==UuidV7.Freshness.TOO_OLD)
                throw new ChatTurnRejectException(410,"RESULT_EXPIRED","requestId 超过 24 小时且没有记录",Map.of());
            if(freshness==UuidV7.Freshness.CLOCK_AHEAD)
                throw new ChatTurnRejectException(400,"REQUEST_ID_CLOCK_AHEAD","requestId 时间戳超前于服务器时间",Map.of());
        }
        if (inputText(body).isBlank()) throw reject("INPUT_EMPTY","消息不能为空");
        if (busy(n.longValue()) || turns.runningRequestId(n.longValue()).isPresent()) throw reject("SESSION_BUSY","该会话正在回复");
        checkCapacity();
        String password=Objects.toString(body.getOrDefault("masterPassword",vault.sessionPassword(n.longValue()).orElse("")),"");
        checkReadable(n.longValue(),password);
        var config=ai.getObject().captureConfig(password);
        try {
            Receipt r=receipts.accept(id,n.longValue(),null,null,fingerprint,null,config.row().model());
            var copy=new LinkedHashMap<>(body); copy.put("masterPassword",password);
            enqueue(new Run(r,copy,config,new SessionExecution(new AiTurnBudget(ai.getObject().dispatchSettings()),Set.of(n.longValue()))));
            return accepted(r,false);
        } catch(RuntimeException e) { config.close(); throw e; }
    }

    public synchronized Map<String,Object> dispatch(ChatContextPlan plan,AiService.ToolInvocation invocation,
                                                    String selector,String message,String title) {
        if (plan==null || invocation==null || invocation.callId()==null || invocation.callId().isBlank()
                || invocation.handle()==null || invocation.handle().sessionId()!=plan.sessionId()
                || invocation.handle().cancelRequested()) return error("REJECTED");
        if(!callerCurrent(plan)) return error("STALE");
        var current=SessionExecution.CURRENT.get();
        if (current==null) return error("UNAVAILABLE");
        String key=hash(List.of(invocation.requestId(),invocation.callId()));
        String fingerprint=hash(Arrays.asList(selector,message,title));
        var existing=receipts.bySource(key);
        if(existing!=null) {
            if (!existing.fingerprint().equals(fingerprint)) return error("REJECTED");
            return accepted(existing,selector==null);
        }
        Long target=selector==null ? null : resolve(selector);
        if(selector!=null && target==null) return error("NOT_FOUND");
        if(target!=null && current.ancestors.contains(target)) return error("CYCLE_BLOCKED");
        if(current.budget.secondsLeft()<=0) return error("TIME_LIMIT");
        checkCapacity();
        long passwordSession=target==null ? plan.sessionId() : target;
        String password=vault.sessionPassword(passwordSession).orElse("");
        if(target==null && password.isBlank()) {
            var origin=active.get(invocation.requestId());
            if(origin!=null) password=Objects.toString(origin.body.get("masterPassword"),"");
        }
        checkReadable(passwordSession,password);
        var config=ai.getObject().captureConfig(password);
        try {
            var r=receipts.accept(UuidV7.generate(System.currentTimeMillis()),target,plan.sessionId(),key,fingerprint,title,config.row().model(),plan.contextGeneration());
            if(target==null && !password.isBlank()) vault.rememberSessionPassword(r.sessionId(),password);
            var lineage=new HashSet<>(current.ancestors); lineage.add(r.sessionId());
            var body=new LinkedHashMap<String,Object>();
            body.put("protocolVersion",2); body.put("requestId",r.requestId()); body.put("sessionId",r.sessionId());
            body.put("input",Map.of("role","user","userText",message)); body.put("masterPassword",password);
            body.put("memoryOptions",Map.of("use",true));
            enqueue(new Run(r,body,config,new SessionExecution(current.budget,lineage)));
            return accepted(r,selector==null);
        } catch(RuntimeException e) { config.close(); throw e; }
    }

    private void checkCapacity() {
        if(closed || active.size()>=64) throw reject("LIMIT_REACHED","当前待处理消息已达上限，请等待已有任务结束");
    }
    private void enqueue(Run run) {
        var queue=queues.computeIfAbsent(run.receipt.sessionId(),ignored -> new ArrayDeque<>());
        queue.add(run); active.put(run.receipt.requestId(),run);
        if(queue.size()==1) workers.execute(() -> execute(run));
    }
    private void execute(Run run) {
        String state="FAILED";
        boolean continueQueue=false;
        try {
            synchronized(this) {
                if(run.cancelled) { state="CANCELLED"; return; }
                // Old clients can still own a regular turn. Wait for its real terminal state.
            }
            var previous=turns.runningRequestId(run.receipt.sessionId());
            while(previous.isPresent()) {
                if(run.cancelled || run.execution.budget.secondsLeft()<=0) { state="CANCELLED"; return; }
                Thread.sleep(100);
                var status=turns.status(previous.get());
                if(!"RUNNING".equals(status.get("status"))) {
                    if(!"COMPLETED".equals(status.get("status"))) { state="CANCELLED"; return; }
                    break;
                }
            }
            synchronized(this) { if(run.cancelled) { state="CANCELLED"; return; } run.started=true; run.phase="running"; receipts.state(run.receipt.requestId(),"RUNNING"); }
            SessionExecution.CURRENT.set(run.execution);
            var result=ai.getObject().runAccepted(run.body,run.config,(type,text) -> {
                run.event(type,Map.of("text",text)); return !run.cancelled;
            },event -> {
                run.event(event.containsKey("toolActivities") ? "tool_activity" : "metadata",event);
                if(run.cancelled) turns.cancelActive(run.receipt.requestId());
            });
            state=result.status();
            String finish=Objects.toString(result.resultView().get("finishReason"),"");
            continueQueue="COMPLETED".equals(state) && !finish.startsWith("budget_") && !finish.startsWith("request_timeout");
            var view=new LinkedHashMap<>(result.resultView()); view.put("reasoning",Objects.toString(result.reasoning(),""));
            synchronized(run) { run.event("done",view); run.done.complete(view); }
        } catch(Exception e) {
            state=run.cancelled ? "ABORTED" : "FAILED";
            var failure=new LinkedHashMap<String,Object>();
            failure.put("requestId",run.receipt.requestId()); failure.put("sessionId",run.receipt.sessionId());
            failure.put("message",e.getMessage()==null ? "会话执行失败" : e.getMessage()); failure.put("partial",true);
            if(e instanceof ChatTurnRejectException rejected) failure.put("code",rejected.code());
            if(e instanceof AiService.StreamFailure sf) {
                failure.put("persistenceStatus",sf.persistenceStatus()); failure.put("stateDurable",sf.stateDurable());
                failure.put("toolActivities",sf.toolActivities());
            }
            synchronized(run) { run.event("error",failure); run.done.completeExceptionally(e); }
        } finally {
            SessionExecution.CURRENT.remove();
            synchronized(this) {
                try { receipts.state(run.receipt.requestId(),state); }
                finally {
                    active.remove(run.receipt.requestId()); run.config.close();
                    var queue=queues.get(run.receipt.sessionId());
                    if(queue!=null) {
                        queue.remove(run);
                        if(!continueQueue || run.cancelled || closed) cancelPending(queue);
                        if(queue.isEmpty()) queues.remove(run.receipt.sessionId());
                        else workers.execute(() -> execute(queue.peek()));
                    }
                    if(!run.done.isDone()) {
                        var terminal=Map.<String,Object>of("status",state,"requestId",run.receipt.requestId(),
                                "sessionId",run.receipt.sessionId(),"reply","","stateDurable",true,"persistenceStatus","not_started");
                        synchronized(run) { run.event("done",terminal); run.done.complete(terminal); }
                    }
                    run.subscribers.clear();
                }
            }
        }
    }
    private void cancelPending(ArrayDeque<Run> queue) {
        for(var r:queue) {
            r.cancelled=true; receipts.state(r.receipt.requestId(),"CANCELLED"); active.remove(r.receipt.requestId());
            r.config.close(); r.done.complete(Map.of("status","CANCELLED","requestId",r.receipt.requestId()));
        }
        queue.clear();
    }
    public synchronized boolean busy(long session) { return queues.containsKey(session); }
    public synchronized Map<String,Object> stop(long session,String expected) {
        var queue=queues.get(session);
        if(queue==null || queue.isEmpty()) return Map.of("status","IDLE");
        Run head=queue.peek();
        if(expected==null || !head.receipt.requestId().equals(expected)) throw reject("STALE","该会话已进入另一轮，请刷新后重试");
        head.cancelled=true;
        var pending=new ArrayDeque<Run>(); while(queue.size()>1) pending.add(queue.removeLast()); cancelPending(pending);
        turns.cancelActive(head.receipt.requestId());
        return Map.of("status","STOPPING","requestId",head.receipt.requestId());
    }
    public Map<String,Object> cancelRequest(String request) {
        var r=receipts.find(request); if(r==null) return turns.cancel(request);
        return stop(r.sessionId(),request);
    }
    public Map<String,Object> status(String request) {
        synchronized(this) { var run=active.get(request); if(run!=null) return run.snapshot(); }
        var r=receipts.find(request);
        if(r==null) return turns.status(request);
        try { return turns.status(request,vault.sessionPassword(r.sessionId()).orElse("")); }
        catch(ChatTurnRejectException e) {
            if(!"TURN_NOT_FOUND".equals(e.code())) throw e;
            return Map.of("requestId",request,"sessionId",r.sessionId(),"status",r.state(),"active",false,"stateDurable",true);
        }
    }
    public synchronized Map<String,Object> runtime(Long session) {
        var states=new LinkedHashMap<Long,Map<String,Object>>();
        for(var r:receipts.latest()) states.put(r.sessionId(),Map.of("sessionId",r.sessionId(),"requestId",r.requestId(),"status",r.state(),"queueCount",0));
        for(var entry:queues.entrySet()) {
            var head=entry.getValue().peek();
            states.put(entry.getKey(),Map.of("sessionId",entry.getKey(),"requestId",head.receipt.requestId(),
                    "status",head.started ? "RUNNING" : "QUEUED","phase",head.cancelled ? "stopping" : head.phase,
                    "queueCount",Math.max(0,entry.getValue().size()-1)));
        }
        var result=new LinkedHashMap<String,Object>(); result.put("sessions",states.values());
        if(session!=null && states.containsKey(session)) result.put("current",status((String)states.get(session).get("requestId")));
        return result;
    }
    public AutoCloseable subscribe(String id,BiConsumer<String,Map<String,Object>> subscriber) {
        Run run;
        synchronized(this) { run=active.get(id); }
        if(run==null) { subscriber.accept("done",status(id)); return () -> { }; }
        synchronized(run) {
            subscriber.accept("metadata",Map.of("requestId",id,"sessionId",run.receipt.sessionId()));
            if(!run.text.isEmpty()) subscriber.accept("text",Map.of("text",run.text.toString()));
            if(!run.reasoning.isEmpty()) subscriber.accept("reasoning",Map.of("text",run.reasoning.toString()));
            if(run.metadata.containsKey("toolActivities")) subscriber.accept("tool_activity",run.metadata);
            if(run.done.isDone()) {
                if(run.done.isCompletedExceptionally()) subscriber.accept("error",Map.of("message","会话执行失败","requestId",id));
                else subscriber.accept("done",run.done.join());
            }
            else run.subscribers.add(subscriber);
        }
        return () -> run.subscribers.remove(subscriber);
    }
    public Map<String,Object> await(String id) {
        Run run; synchronized(this) { run=active.get(id); }
        try { return run==null ? status(id) : run.done.join(); }
        catch(CompletionException error) {
            if(error.getCause() instanceof RuntimeException cause) throw cause;
            throw error;
        }
    }
    private Map<String,Object> accepted(Receipt r,boolean created) {
        var queue=queues.get(r.sessionId()); int position=0;
        if(queue!=null) for(var run:queue) { if(run.receipt.requestId().equals(r.requestId())) break; position++; }
        var result=new LinkedHashMap<String,Object>();
        result.put("status",created ? "CREATED" : position>0 ? "QUEUED" : "SENT");
        result.put("requestId",r.requestId()); result.put("queuePosition",position);
        Map<String,Object> session;
        try { session=sessions.find(r.sessionId()); }
        catch(org.springframework.dao.EmptyResultDataAccessException e) { return error("NOT_FOUND"); }
        result.put("session",Map.of("sessionId",r.sessionId(),"sessionLink",session.get("sessionLink"),"title",session.get("title")));
        result.put("sessionLink",session.get("sessionLink")); result.put("state",status(r.requestId()).get("status"));
        return result;
    }
    Long resolve(String selector) {
        if(selector==null) return null;
        String ref=selector.startsWith(SessionLookupService.LINK_PREFIX) ? selector.substring(SessionLookupService.LINK_PREFIX.length()) : selector;
        if(ref.matches("[a-f0-9]{32}")) return jdbc.query("SELECT id FROM ai_chat_session WHERE session_ref=?",(r,n)->r.getLong(1),ref).stream().findFirst().orElse(null);
        if(!selector.matches("[0-9]{1,18}")) return null;
        return jdbc.query("SELECT id FROM ai_chat_session WHERE id=?",(r,n)->r.getLong(1),Long.parseLong(selector)).stream().findFirst().orElse(null);
    }
    public Map<String,Object> readRequest(String selector,String requestId,int waitSeconds,AiService.ToolInvocation invocation) {
        return readRequest(null,selector,requestId,waitSeconds,invocation);
    }
    public Map<String,Object> readRequest(ChatContextPlan plan,String selector,String requestId,int waitSeconds,AiService.ToolInvocation invocation) {
        if(plan!=null && !callerCurrent(plan)) return error("STALE");
        Long session=resolve(selector);
        var receipt=receipts.find(requestId);
        if(session==null || receipt==null || receipt.sessionId()!=session) return error("NOT_FOUND");
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(waitSeconds);
        Map<String,Object> state;
        do {
            state=status(requestId);
            if(!Boolean.TRUE.equals(state.get("active")) || System.nanoTime()>=deadline) break;
            if(invocation!=null && invocation.handle().cancelRequested()) return error("CANCELLED");
            if(SessionExecution.CURRENT.get()!=null && SessionExecution.CURRENT.get().budget.secondsLeft()<=0) break;
            try { Thread.sleep(100); } catch(InterruptedException e) { Thread.currentThread().interrupt(); return error("CANCELLED"); }
        } while(true);
        var result=new LinkedHashMap<String,Object>();
        result.put("requestId",requestId); result.put("runStatus",state.get("status"));
        Map<String,Object> target;
        try { target=sessions.find(session); }
        catch(org.springframework.dao.EmptyResultDataAccessException e) { return error("NOT_FOUND"); }
        var version=readVersion(session);
        if(version.isEmpty()) return error("NOT_FOUND");
        result.put("session",Map.of("sessionId",session,"sessionLink",target.get("sessionLink"),"title",target.get("title")));
        if(Boolean.TRUE.equals(state.get("active"))) { result.put("status",state.get("status")); result.put("messages",List.of()); result.put("incomplete",true); return result; }
        var rows=jdbc.query("""
                SELECT m.role,m.content,m.content_cipher,m.content_meta FROM ai_chat_message m JOIN ai_chat_turn t
                ON m.id IN (t.user_message_id,t.assistant_message_id)
                JOIN ai_chat_session s ON s.id=t.session_id
                WHERE t.request_id=? AND t.session_id=? AND t.content_deleted_at IS NULL AND t.context_generation=s.context_generation
                ORDER BY m.id
                """,(r,n)->new String[]{r.getString(1),r.getString(2),r.getString(3),r.getString(4)},requestId,session);
        var messages=new ArrayList<Map<String,Object>>(); int remaining=6000; boolean incomplete=false;
        for(var row:rows) {
            var text=cipher.readLegacy(row[1],row[2],row[3],vault.sessionPassword(session).orElse(""));
            if(text.status()!=ChatMessageCipher.ReadStatus.DECRYPTED) { result.put("availability","LOCKED"); incomplete=true; continue; }
            int length=text.value().codePointCount(0,text.value().length()); int count=Math.min(remaining,length);
            messages.add(Map.of("role",row[0],"content",text.value().substring(0,text.value().offsetByCodePoints(0,count))));
            remaining-=count; incomplete|=count<length;
        }
        var current=readVersion(session);
        if(current.isEmpty()) return error("NOT_FOUND");
        if(!current.equals(version) || (plan!=null && !callerCurrent(plan))) return error("STALE");
        result.put("status",messages.isEmpty() ? "UNAVAILABLE" : "OK"); result.put("messages",messages);
        result.put("incomplete",incomplete || !"COMPLETED".equals(state.get("status")));
        result.put("omittedContent",List.of("reasoning","tool_payloads","attachment_payloads"));
        return result;
    }
    private List<Long> readVersion(long session) {
        return jdbc.query("SELECT context_generation,history_revision FROM ai_chat_session WHERE id=?",
                (r,n)->List.of(r.getLong(1),r.getLong(2)),session).stream().findFirst().orElse(List.of());
    }
    private boolean callerCurrent(ChatContextPlan plan) {
        var version=readVersion(plan.sessionId());
        return !version.isEmpty() && version.get(0)==plan.contextGeneration();
    }
    private void checkReadable(long session,String password) {
        for(var row:sessions.messages(session)) if(cipher.readLegacy(row.content(),row.contentCipher(),row.contentMeta(),password).status()!=ChatMessageCipher.ReadStatus.DECRYPTED)
            throw reject("LOCKED","目标会话需要解锁或内容无法解密");
    }
    private static String inputText(Map<String,Object> body) {
        return body.get("input") instanceof Map<?,?> input ? Objects.toString(input.get("userText"),"") : "";
    }
    private String hash(Object value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(
                json.writer().with(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).writeValueAsBytes(value))); }
        catch(Exception e) { throw new IllegalArgumentException("发送参数无效",e); }
    }
    private static void checkFingerprint(Receipt r,String fingerprint) { if(!r.fingerprint().equals(fingerprint)) throw reject("REQUEST_ID_CONFLICT","同一发送标识的内容不能更改"); }
    static Map<String,Object> error(String status) { return Map.of("status",status,"incomplete",true); }
    private static ChatTurnRejectException reject(String code,String text) { return new ChatTurnRejectException(409,code,text,Map.of()); }
    @jakarta.annotation.PreDestroy public synchronized void close() {
        closed=true;
        for(var q:queues.values()) {
            var head=q.peek(); if(head==null) continue;
            head.cancelled=true; turns.cancelActive(head.receipt.requestId());
            var pending=new ArrayDeque<Run>(); while(q.size()>1) pending.add(q.removeLast()); cancelPending(pending);
        }
        workers.shutdownNow();
    }
}
