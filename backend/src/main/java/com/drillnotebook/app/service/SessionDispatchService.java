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
    private final ChatPendingRepository pending;
    private final Map<Long, ArrayDeque<Run>> queues = new HashMap<>();
    private final Map<String, Run> active = new HashMap<>();
    private record CachedPayload(int revision, String password, Map<String,Object> value) { }
    private final Map<String,CachedPayload> payloadCache = new LinkedHashMap<>();
    private final ExecutorService workers = Executors.newFixedThreadPool(64, task -> {
        var t = new Thread(task, "ai-session-run"); t.setDaemon(true); return t;
    });
    private boolean closed;

    private static final class Run {
        final Receipt receipt;
        final Map<String,Object> body;
        AiService.AcceptedConfig config;
        SessionExecution execution;
        CompletableFuture<Map<String,Object>> done = new CompletableFuture<>();
        final List<BiConsumer<String,Map<String,Object>>> subscribers = new CopyOnWriteArrayList<>();
        final StringBuilder text = new StringBuilder(), reasoning = new StringBuilder();
        Map<String,Object> metadata = Map.of();
        volatile boolean cancelled, started, scheduled, acceptingSteering=true;
        boolean human, toolDispatched;
        volatile String phase = "queued";
        Run(Receipt receipt,Map<String,Object> body,AiService.AcceptedConfig config,SessionExecution execution) {
            this.receipt=receipt; this.body=body; this.config=config; this.execution=execution;
        }
        synchronized void event(String name,Map<String,Object> value) {
            if (name.equals("text")) { text.append(value.get("text")); phase="answer"; }
            if (name.equals("reasoning")) { reasoning.append(value.get("text")); phase="thinking"; }
            if (name.equals("metadata") || name.equals("tool_activity")) {
                var merged=new LinkedHashMap<>(metadata); merged.putAll(value); metadata=merged;
                if(value.containsKey("phase"))phase=String.valueOf(value.get("phase"));
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
        this.pending=new ChatPendingRepository(jdbc,new com.drillnotebook.app.config.ImmediateTransaction(jdbc.getDataSource()));
    }
    @EventListener(ApplicationReadyEvent.class)
    public synchronized void recover() { receipts.recover(); pending.recover(); payloadCache.clear(); }

    /** All human queue mutations share the scheduler lock, including a run's final input boundary. */
    public synchronized Map<String,Object> submitHuman(long session,Map<String,Object> raw) {
        var body=new LinkedHashMap<String,Object>();
        for(String key:List.of("protocolVersion","requestId","input","pageContext","pageSnapshot","retrievalOptions","memoryOptions","retryOf"))
            if(raw.containsKey(key))body.put(key,raw.get(key));
        body.put("sessionId",session);
        String id=Objects.toString(body.get("requestId"),"");
        if(!Integer.valueOf(2).equals(body.get("protocolVersion"))||!UuidV7.isCanonical(id))throw reject("INVALID_ARGUMENT","发送标识无效");
        String mode=Objects.toString(raw.getOrDefault("mode","queue"));
        if(!Set.of("queue","steer").contains(mode))throw reject("INVALID_ARGUMENT","发送方式无效");
        String parent="steer".equals(mode)?Objects.toString(raw.get("targetRequestId"),""):null;
        String fingerprint=hash(Arrays.asList(body,mode,parent));
        var existing=receipts.find(id);
        if(existing!=null){checkFingerprint(existing,fingerprint);return humanAccepted(existing);}
        if(UuidV7.freshness(id,System.currentTimeMillis())!=UuidV7.Freshness.ACCEPTABLE)throw reject("REQUEST_ID_EXPIRED","发送标识已过期，请重新提交");
        ensureRestored(session);checkCapacity();
        if(pending.countPending()>=64)throw reject("LIMIT_REACHED","待发送消息已达上限，请等待或移除部分消息");
        Run target=parent==null?null:requireSteeringTarget(session,parent);
        String password=vault.sessionPassword(session).orElse(Objects.toString(raw.get("masterPassword"),""));
        checkReadable(session,password);ai.getObject().validatePending(body);
        if(!password.isBlank())vault.rememberSessionPassword(session,password);
        var config=ai.getObject().captureConfig(password);
        try {
            var payload=Map.<String,Object>of("body",body,"config",ai.getObject().configReference(config));
            var receipt=pending.accept(id,session,fingerprint,encryptPayload(payload,password),parent);
            if(target==null){var copy=new LinkedHashMap<>(body);copy.put("masterPassword",password);
                var run=new Run(receipt,copy,config,new SessionExecution(new AiTurnBudget(ai.getObject().dispatchSettings()),Set.of(session)));
                run.human=true;enqueue(run);
            }else config.close();
            return humanAccepted(receipt);
        }catch(RuntimeException error){config.close();throw error;}
    }

    private Map<String,Object> humanAccepted(Receipt receipt) {
        var row=pending.find(receipt.requestId());
        var result=new LinkedHashMap<String,Object>();result.put("requestId",receipt.requestId());result.put("sessionId",receipt.sessionId());
        result.put("status",row!=null&&row.parent()!=null?row.state():active.containsKey(receipt.requestId())&&active.get(receipt.requestId()).started?"RUNNING":receipt.state());
        return result;
    }
    @SuppressWarnings("unchecked") private synchronized Map<String,Object> payload(ChatPendingRepository.Pending row) {
        String password=vault.sessionPassword(row.sessionId()).orElse("");
        var cached=payloadCache.get(row.requestId());
        if(cached!=null&&cached.revision()==row.revision()&&cached.password().equals(password))return new LinkedHashMap<>(cached.value());
        var read=cipher.read(row.cipher(),row.meta(),password);
        if(read.status()!=ChatMessageCipher.ReadStatus.DECRYPTED)throw reject("LOCKED","待发内容无法解密，请先解锁会话");
        try{
            Map<String,Object> value=json.readValue(read.value(),Map.class);
            if(payloadCache.size()>=64)payloadCache.remove(payloadCache.keySet().iterator().next());
            payloadCache.put(row.requestId(),new CachedPayload(row.revision(),password,value));
            return new LinkedHashMap<>(value);
        }catch(Exception e){throw reject("QUEUE_UNREADABLE","待发内容无法读取");}
    }
    private ChatMessageCipher.Encrypted encryptPayload(Map<String,Object> value,String password) {
        try{return cipher.encrypt(json.writeValueAsString(value),password);}catch(com.fasterxml.jackson.core.JsonProcessingException e){throw new IllegalArgumentException("待发内容无法保存",e);}
    }
    @SuppressWarnings("unchecked") private Map<String,Object> pendingBody(ChatPendingRepository.Pending row) {return (Map<String,Object>)payload(row).get("body");}
    private void ensureRestored(long session) {
        for(var row:pending.session(session))if("PENDING".equals(row.state())&&!active.containsKey(row.requestId())){
            var receipt=receipts.find(row.requestId());
            var run=new Run(receipt,new LinkedHashMap<>(),null,new SessionExecution(new AiTurnBudget(ai.getObject().dispatchSettings()),Set.of(session)));
            run.human=true;active.put(receipt.requestId(),run);queues.computeIfAbsent(session,ignored->new ArrayDeque<>()).add(run);
        }
        var q=queues.get(session);if(q!=null){var sorted=q.stream().sorted(Comparator.comparingLong(r->r.receipt.sequence())).toList();q.clear();q.addAll(sorted);}
    }
    private Run requireSteeringTarget(long session,String id) {
        var run=active.get(id);
        if(run==null||run.receipt.sessionId()!=session||!run.started||run.cancelled||!run.acceptingSteering)
            throw reject("RUN_FINISHED","本轮已结束，请发送或加入队列");
        return run;
    }

    public synchronized Map<String,Object> queueView(long session) {
        pending.purgeRemoved();
        payloadCache.entrySet().removeIf(e->{var row=pending.find(e.getKey());return row==null||"CONSUMED".equals(row.state());});
        var items=new ArrayList<Map<String,Object>>();var steering=new ArrayList<Map<String,Object>>();
        for(var row:pending.session(session)) {
            if("RECEIVED".equals(row.state())&&!active.containsKey(row.parent()))continue;
            var run=active.get(row.requestId());if(run!=null&&run.started)continue;
            var view=new LinkedHashMap<String,Object>();view.put("requestId",row.requestId());view.put("revision",row.revision());view.put("state",row.state());view.put("source","human");view.put("sequence",row.sequence());
            if(row.parent()!=null)view.put("targetRequestId",row.parent());view.put("replyOffset",row.offset());
            try{
                var body=pendingBody(row);view.put("userText",inputText(body));
                if(body.get("input") instanceof Map<?,?> input&&input.get("attachments") instanceof List<?> attachments)
                    view.put("attachments",attachments.stream().filter(Map.class::isInstance).map(x->{var a=(Map<?,?>)x;return Map.of("clientId",Objects.toString(a.get("clientId"),""),"name",Objects.toString(a.get("name"),"附件"),"kind",Objects.toString(a.get("kind"),"text"));}).toList());
                if(body.get("pageContext") instanceof Map<?,?> context){view.put("context","笔记页面"+(context.get("pageId")==null?"快照":" #"+context.get("pageId")));}
                if(body.get("pageSnapshot") instanceof Map<?,?> context) view.put("context", Objects.toString(context.get("title"), "页面快照"));
            }catch(RuntimeException error){view.put("error",error.getMessage());view.put("userText","[待发内容暂时无法读取]");}
            if(run!=null&&run.metadata.get("message") instanceof String error)view.put("error",error);
            (row.parent()==null?items:steering).add(view);
        }
        var queue=queues.get(session);
        if(queue!=null)for(var run:queue)if(run.toolDispatched&&!run.started&&!run.cancelled){items.add(Map.of("requestId",run.receipt.requestId(),"userText",inputText(run.body),"source","tool","sequence",run.receipt.sequence(),"state","PENDING","revision",0));}
        items.sort(Comparator.comparingLong(x->((Number)x.get("sequence")).longValue()));
        var view=new LinkedHashMap<String,Object>();view.put("sessionId",session);view.put("paused",pending.paused(session));view.put("editingId",pending.editing(session));view.put("items",items);view.put("steering",steering);view.put("storedBytes",pending.storedBytes());return view;
    }
    public synchronized Map<String,Object> pauseQueue(long session,boolean paused) {
        if(resolve(String.valueOf(session))==null)throw reject("NOT_FOUND","目标会话不存在");
        if(!paused&&pending.editing(session)!=null)throw reject("QUEUE_EDITING","请先保存或取消正在编辑的消息");
        pending.pause(session,paused);ensureRestored(session);schedule(session);return queueView(session);
    }
    @SuppressWarnings("unchecked") public synchronized Map<String,Object> queueAction(long session,String id,Map<String,Object> action) {
        var row=pending.find(id);var run=active.get(id);String kind=Objects.toString(action.get("action"),"");
        if(row==null||row.sessionId()!=session||(run!=null&&run.started)||!("PENDING".equals(row.state())||"undo".equals(kind)&&"REMOVED".equals(row.state())||"remove".equals(kind)&&"UNKNOWN".equals(row.state())))
            throw reject("QUEUE_STARTED","这条消息已开始或已变化，无法修改");
        if(!(action.get("revision") instanceof Number version)||version.intValue()!=row.revision())throw reject("QUEUE_CHANGED","这条消息已变化，请刷新后重试");
        String editing=pending.editing(session);
        if(editing!=null&&!editing.equals(id))throw reject("QUEUE_EDITING","请先处理正在编辑的消息");
        switch(kind){
            case "beginEdit" -> {if(editing==null)pending.beginEdit(session,id);}
            case "cancelEdit" -> {if(!id.equals(editing))throw reject("QUEUE_CHANGED","编辑状态已变化");pending.endEdit(session);}
            case "save" -> {
                if(!id.equals(editing))throw reject("QUEUE_CHANGED","编辑状态已变化");
                var value=payload(row);var body=new LinkedHashMap<>((Map<String,Object>)value.get("body"));var input=new LinkedHashMap<>((Map<String,Object>)body.get("input"));
                input.put("userText",Objects.toString(action.get("userText"),""));
                if(input.get("attachments") instanceof List<?> attachments&&action.get("keepAttachments") instanceof List<?> keep)
                    input.put("attachments",attachments.stream().filter(x->keep.contains(((Map<?,?>)x).get("clientId"))).toList());
                body.put("input",input);ai.getObject().validatePending(body);value.put("body",body);
                pending.update(row,encryptPayload(value,vault.sessionPassword(session).orElse("")));
                if(run!=null){run.body.clear();run.body.putAll(body);run.body.put("masterPassword",vault.sessionPassword(session).orElse(""));}
                pending.endEdit(session);
            }
            case "remove" -> {if(editing!=null)throw reject("QUEUE_EDITING","请先结束编辑");pending.state(id,"REMOVED");receipts.state(id,"CANCELLED");detachPending(id);
                if(pending.session(session).stream().noneMatch(p->"PENDING".equals(p.state())))pending.pause(session,false);}
            case "undo" -> {if(!pending.canUndo(id))throw reject("UNDO_EXPIRED","撤销时间已过，或该消息的接收状态未知");checkCapacity();pending.restore(id);receipts.state(id,"QUEUED");if(!isRunning(session))pending.pause(session,true);ensureRestored(session);}
            case "steer" -> {if(editing!=null)throw reject("QUEUE_EDITING","编辑期间不能插话");var target=requireSteeringTarget(session,Objects.toString(action.get("targetRequestId"),""));pending.steer(id,target.receipt.requestId());detachPending(id);}
            default -> throw reject("INVALID_ARGUMENT","未知队列操作");
        }
        schedule(session);var response=new LinkedHashMap<>(queueView(session));response.put("itemRevision",pending.find(id).revision());return response;
    }
    private void detachPending(String id){var run=active.remove(id);if(run!=null){var q=queues.get(run.receipt.sessionId());if(q!=null){q.remove(run);if(q.isEmpty())queues.remove(run.receipt.sessionId());}if(run.config!=null)run.config.close();}}
    private boolean isRunning(long session){var q=queues.get(session);return q!=null&&q.stream().anyMatch(r->r.started);}

    private synchronized List<SessionExecution.Supplement> takeSteering(Run run,boolean finishing,int offset){
        if(run.cancelled){run.acceptingSteering=false;return List.of();}
        var batch=new ArrayList<SessionExecution.Supplement>();
        for(var row:pending.supplements(run.receipt.requestId()))if("STEERING".equals(row.state())){
            var body=pendingBody(row);pending.claim(row.requestId(),offset);
            batch.add(new SessionExecution.Supplement(row.requestId(),body,offset));
        }
        if(finishing&&batch.isEmpty())run.acceptingSteering=false;
        return batch;
    }
    private synchronized void markSteering(Run run,List<SessionExecution.Supplement> batch,String state){
        for(var item:batch){
            var row=pending.find(item.requestId());
            if(row==null||"RECEIVED".equals(row.state()))continue;
            pending.state(item.requestId(),state);
            if("RECEIVED".equals(state))receipts.state(item.requestId(),"COMPLETED");
        }
        run.event("metadata",Map.of("requestId",run.receipt.requestId(),"sessionId",run.receipt.sessionId(),"supplements",supplementViews(run.receipt.requestId())));
    }
    private List<Map<String,Object>> supplementViews(String parent){
        var result=new ArrayList<Map<String,Object>>();
        for(var row:pending.supplements(parent)){
            var item=new LinkedHashMap<String,Object>();item.put("requestId",row.requestId());item.put("state",row.state());item.put("replyOffset",row.offset());
            try{
                var body=pendingBody(row);item.put("userText",inputText(body));
                if(body.get("input") instanceof Map<?,?> input&&input.get("attachments") instanceof List<?> attachments)
                    item.put("attachments",attachments.stream().filter(Map.class::isInstance).map(a->Objects.toString(((Map<?,?>)a).get("name"),"附件")).toList());
            }catch(RuntimeException error){item.put("userText","[加密补充：无法读取]");}
            result.add(item);
        }
        return result;
    }
    private void restoreUnsentSteering(Run run){
        boolean restored=false;
        for(var row:pending.supplements(run.receipt.requestId())){
            if(Set.of("STEERING","CLAIMED").contains(row.state())){pending.restore(row.requestId());receipts.state(row.requestId(),"QUEUED");restored=true;}
            else if("SENDING".equals(row.state())){pending.state(row.requestId(),"UNKNOWN");receipts.state(row.requestId(),"INTERRUPTED");}
        }
        if(restored){pending.forcePause(run.receipt.sessionId());ensureRestored(run.receipt.sessionId());}
    }

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
        ensureRestored(n.longValue());
        if (busy(n.longValue()) || turns.runningRequestId(n.longValue()).isPresent()) throw reject("SESSION_BUSY","该会话正在回复或有待发消息");
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
            ensureRestored(r.sessionId());
            var run = new Run(r,body,config,new SessionExecution(current.budget,lineage));
            run.toolDispatched = true;
            enqueue(run);
            return accepted(r,selector==null);
        } catch(RuntimeException e) { config.close(); throw e; }
    }

    private void checkCapacity() {
        if(closed || active.size()>=64) throw reject("LIMIT_REACHED","当前待处理消息已达上限，请等待已有任务结束");
    }
    private void enqueue(Run run) {
        var queue=queues.computeIfAbsent(run.receipt.sessionId(),ignored -> new ArrayDeque<>());
        queue.add(run); active.put(run.receipt.requestId(),run);
        schedule(run.receipt.sessionId());
    }
    private void schedule(long session) {
        var queue=queues.get(session);var head=queue==null?null:queue.peek();
        if(head==null||head.started||head.scheduled||closed||pending.paused(session))return;
        if(head.done.isDone())head.done=new CompletableFuture<>();
        head.acceptingSteering=true;
        head.scheduled=true;workers.execute(()->execute(head));
    }
    @SuppressWarnings("unchecked")
    private void execute(Run run) {
        synchronized(this){
            if(active.get(run.receipt.requestId())!=run||pending.paused(run.receipt.sessionId())){run.scheduled=false;return;}
            run.started=true;run.phase="running";
        }
        String state="FAILED";
        boolean continueQueue=false;
        boolean retain=false;
        try {
            synchronized(this) {
                if(run.cancelled) { state="CANCELLED"; return; }
                if(run.human){
                    if(run.config==null){
                        var saved=payload(pending.find(run.receipt.requestId()));
                        run.body.putAll((Map<String,Object>)saved.get("body"));
                        String password=vault.sessionPassword(run.receipt.sessionId()).orElse("");run.body.put("masterPassword",password);
                        run.config=ai.getObject().restoreConfig((Map<String,Object>)saved.get("config"),password);
                    }
                    run.execution=new SessionExecution(new AiTurnBudget(ai.getObject().dispatchSettings()),Set.of(run.receipt.sessionId()));
                }
                run.execution.steering=new SessionExecution.Steering(){
                    @Override public List<SessionExecution.Supplement> take(boolean finishing,int offset){return takeSteering(run,finishing,offset);}
                    @Override public void sending(List<SessionExecution.Supplement> batch){markSteering(run,batch,"SENDING");}
                    @Override public void received(List<SessionExecution.Supplement> batch){markSteering(run,batch,"RECEIVED");}
                };
                run.execution.acceptedRequestId=run.receipt.requestId();
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
            synchronized(this) { if(run.cancelled) { state="CANCELLED"; return; } receipts.state(run.receipt.requestId(),"RUNNING"); }
            SessionExecution.CURRENT.set(run.execution);
            var result=ai.getObject().runAccepted(run.body,run.config,(type,text) -> {
                run.event(type,Map.of("text",text)); return !run.cancelled;
            },event -> {
                if(run.human&&event.containsKey("requestId"))synchronized(this){
                    var row=pending.find(run.receipt.requestId());
                    if(row!=null&&"PENDING".equals(row.state())){pending.state(run.receipt.requestId(),"CONSUMED");payloadCache.remove(run.receipt.requestId());}
                }
                run.event(event.containsKey("toolActivities") ? "tool_activity" : "metadata",event);
                if(run.cancelled) turns.cancelActive(run.receipt.requestId());
            });
            state=result.status();
            String finish=Objects.toString(result.resultView().get("finishReason"),"");
            continueQueue="COMPLETED".equals(state) && !finish.startsWith("budget_") && !finish.startsWith("request_timeout");
            var view=new LinkedHashMap<>(result.resultView()); view.put("reasoning",Objects.toString(result.reasoning(),""));
            view.put("supplements",supplementViews(run.receipt.requestId()));
            synchronized(run) { run.event("done",view); run.done.complete(view); }
        } catch(Exception e) {
            if(run.human&&jdbc.queryForObject("SELECT count(*) FROM ai_chat_turn WHERE request_id=?",Integer.class,run.receipt.requestId())==0){
                synchronized(this){retain=true;run.started=false;run.scheduled=false;run.phase="blocked";pending.forcePause(run.receipt.sessionId());run.metadata=Map.of("message",Objects.toString(e.getMessage(),"待发消息暂时无法执行"));}
            }
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
                // An accepted human message must never be replayed after begin, even if metadata delivery failed.
                if(run.human){
                    var row=pending.find(run.receipt.requestId());
                    if(row!=null&&"PENDING".equals(row.state())){
                        if(jdbc.queryForObject("SELECT count(*) FROM ai_chat_turn WHERE request_id=?",Integer.class,run.receipt.requestId())>0){
                            pending.state(run.receipt.requestId(),"CONSUMED");payloadCache.remove(run.receipt.requestId());
                        }else{
                            retain=true;run.started=false;run.scheduled=false;run.cancelled=false;
                            pending.forcePause(run.receipt.sessionId());
                        }
                    }
                }
                run.acceptingSteering=false;
                restoreUnsentSteering(run);
                try { receipts.state(run.receipt.requestId(),retain?"QUEUED":state); }
                finally {
                    if(!retain)active.remove(run.receipt.requestId());
                    if(!retain&&run.config!=null)run.config.close();
                    var queue=queues.get(run.receipt.sessionId());
                    if(queue!=null) {
                        if(!retain)queue.remove(run);
                        if(!continueQueue || run.cancelled || closed) preserveHumanPending(queue,run.receipt.sessionId());
                        if(queue.isEmpty()) queues.remove(run.receipt.sessionId());
                        else schedule(run.receipt.sessionId());
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
            if(r.config!=null)r.config.close(); r.done.complete(Map.of("status","CANCELLED","requestId",r.receipt.requestId()));
        }
        queue.clear();
    }
    private void preserveHumanPending(ArrayDeque<Run> queue,long session){
        var tools=new ArrayDeque<Run>();
        for(var r:List.copyOf(queue))if(!r.human){queue.remove(r);tools.add(r);}
        cancelPending(tools);
        if(!queue.isEmpty())pending.forcePause(session);
    }
    public synchronized boolean busy(long session) { return queues.containsKey(session); }
    public synchronized Map<String,Object> stop(long session,String expected) {
        var queue=queues.get(session);
        if(queue==null || queue.isEmpty()) return Map.of("status","IDLE");
        Run head=queue.peek();
        if(expected==null || !head.receipt.requestId().equals(expected)) throw reject("STALE","该会话已进入另一轮，请刷新后重试");
        if(!head.started&&head.human){pending.forcePause(session);return Map.of("status","PAUSED");}
        head.cancelled=true;head.acceptingSteering=false;
        var waiting=new ArrayDeque<Run>();while(queue.size()>1)waiting.addFirst(queue.removeLast());preserveHumanPending(waiting,session);queue.addAll(waiting);
        turns.cancelActive(head.receipt.requestId());
        return Map.of("status","STOPPING","requestId",head.receipt.requestId());
    }
    public Map<String,Object> cancelRequest(String request) {
        var r=receipts.find(request); if(r==null) return turns.cancel(request);
        return stop(r.sessionId(),request);
    }
    public Map<String,Object> status(String request) {
        synchronized(this) { var run=active.get(request); if(run!=null) {var view=run.snapshot();if(!run.started&&pending.paused(run.receipt.sessionId())){view.put("status","PAUSED");view.put("active",false);}return view;} }
        var r=receipts.find(request);
        if(r==null) return turns.status(request);
        try { var view=new LinkedHashMap<>(turns.status(request,vault.sessionPassword(r.sessionId()).orElse("")));view.put("supplements",supplementViews(request));return view; }
        catch(ChatTurnRejectException e) {
            if(!Set.of("TURN_NOT_FOUND","RESULT_EXPIRED").contains(e.code())) throw e;
            return Map.of("requestId",request,"sessionId",r.sessionId(),"status",r.state(),"active",false,"stateDurable",true);
        }
    }
    public synchronized Map<String,Object> runtime(Long session) {
        var states=new LinkedHashMap<Long,Map<String,Object>>();
        for(var r:receipts.latest()) states.put(r.sessionId(),Map.of("sessionId",r.sessionId(),"requestId",r.requestId(),"status",r.state(),"queueCount",0));
        for(var entry:queues.entrySet()) {
            var head=entry.getValue().peek();
            states.put(entry.getKey(),Map.of("sessionId",entry.getKey(),"requestId",head.receipt.requestId(),
                    "status",head.started ? "RUNNING" : pending.paused(entry.getKey())?"PAUSED":"QUEUED","phase",head.cancelled ? "stopping" : head.phase,
                    "paused",pending.paused(entry.getKey()),"queueCount",Math.max(0,entry.getValue().size()-(head.started?1:0))));
        }
        for(var row:pending.recoverable())if(!queues.containsKey(row.sessionId())){
            long count=pending.session(row.sessionId()).stream().filter(p->"PENDING".equals(p.state())).count();
            states.put(row.sessionId(),Map.of("sessionId",row.sessionId(),"requestId",row.requestId(),"status","PAUSED","paused",true,"queueCount",count));
        }
        var result=new LinkedHashMap<String,Object>(); result.put("sessions",states.values());
        if(session!=null && states.containsKey(session)&&!"PAUSED".equals(states.get(session).get("status"))) result.put("current",status((String)states.get(session).get("requestId")));
        return result;
    }
    public AutoCloseable subscribe(String id,BiConsumer<String,Map<String,Object>> subscriber) {
        Run run;
        synchronized(this) { run=active.get(id); }
        if(run==null) { subscriber.accept("done",status(id)); return () -> { }; }
        synchronized(run) {
            var metadata=new LinkedHashMap<>(run.metadata);metadata.put("requestId",id);metadata.put("sessionId",run.receipt.sessionId());
            subscriber.accept("metadata",metadata);
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
            String content=text.value();
            if("assistant".equals(row[0]))try(var connection=jdbc.getDataSource().getConnection()){
                content=ChatSupplement.transcript(content,ChatSupplement.read(connection,requestId,cipher,vault.sessionPassword(session).orElse("")));
            }catch(java.sql.SQLException|ChatCryptoException error){return error("UNAVAILABLE");}
            int length=content.codePointCount(0,content.length()); int count=Math.min(remaining,length);
            messages.add(Map.of("role",row[0],"content",content.substring(0,content.offsetByCodePoints(0,count))));
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
            pending.forcePause(head.receipt.sessionId());
            var waiting=new ArrayDeque<Run>(); while(q.size()>1) waiting.addFirst(q.removeLast()); preserveHumanPending(waiting,head.receipt.sessionId());q.addAll(waiting);
        }
        workers.shutdownNow();
        payloadCache.clear();
        for(var run:active.values())if(!run.started&&run.config!=null){run.config.close();run.config=null;}
    }
}
