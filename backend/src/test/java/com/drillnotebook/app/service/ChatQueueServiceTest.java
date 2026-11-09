package com.drillnotebook.app.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import com.drillnotebook.app.config.ImmediateTransaction;
import com.drillnotebook.app.model.UuidV7;
import com.drillnotebook.app.repository.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

/** Reuses the real SQLite/cipher/scheduler fixture; no in-memory queue implementation in the tests. */
class ChatQueueServiceTest extends SessionDispatchServiceTest {
    Map<String,Object> human(long session,String text){return Map.of("protocolVersion",2,"requestId",UuidV7.generate(System.currentTimeMillis()),"input",Map.of("role","user","userText",text),"mode","queue");}
    @SuppressWarnings("unchecked") List<Map<String,Object>> items(long session){return (List<Map<String,Object>>)dispatch.queueView(session).get("items");}
    Map<String,Object> act(long session,Map<String,Object> item,String action){return dispatch.queueAction(session,(String)item.get("requestId"),Map.of("action",action,"revision",item.get("revision")));}

    @Test void humanQueueIsEncryptedIdempotentEditableAndFifo() throws Exception {
        var entered=new CountDownLatch(1);var release=block("hold",entered);
        var first=dispatch.submitHuman(source,human(source,"hold"));assertTrue(entered.await(3,TimeUnit.SECONDS));
        var body=human(source,"秘密待发");var second=dispatch.submitHuman(source,body);
        var third=dispatch.submitHuman(source,human(source,"第三条"));
        assertEquals(request(second),request(dispatch.submitHuman(source,body)));
        var pending=items(source).get(0);act(source,pending,"beginEdit");
        release.countDown();terminal(request(first));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM ai_chat_turn WHERE request_id=?",Integer.class,request(second)));
        assertTrue((boolean)dispatch.queueView(source).get("paused"));
        var save=new LinkedHashMap<String,Object>();save.put("action","save");save.put("revision",pending.get("revision"));save.put("userText","已编辑的待发");
        dispatch.queueAction(source,request(second),save);
        terminal(request(second));terminal(request(third));
        var rows=wiring.sessions().messages(source);
        assertEquals("已编辑的待发",wiring.cipher().readLegacy(rows.get(2).content(),rows.get(2).contentCipher(),rows.get(2).contentMeta(),"").value());
        assertFalse(jdbc.queryForList("SELECT * FROM ai_chat_pending").toString().contains("秘密待发"));
        assertEquals(0L,jdbc.queryForObject("SELECT SUM(payload_bytes) FROM ai_chat_pending",Long.class));
    }

    @Test void stopPreservesHumanButCancelsToolQueue() throws Exception {
        var entered=new CountDownLatch(1);var release=block("hold",entered);
        var first=dispatch.dispatch(plan,call("start"),null,"hold",null);long session=target(first);
        assertTrue(entered.await(3,TimeUnit.SECONDS));
        var human=dispatch.submitHuman(session,human(session,"保留我"));var tool=send("tool",session,"取消我");
        assertEquals(List.of("human", "tool"), items(session).stream().map(item -> item.get("source")).toList());
        dispatch.stop(session,request(first));release.countDown();terminal(request(first));
        assertEquals("CANCELLED",dispatch.status(request(tool)).get("status"));
        assertEquals("PAUSED",dispatch.status(request(human)).get("status"));
        assertEquals("保留我",items(session).get(0).get("userText"));
        dispatch.pauseQueue(session,false);terminal(request(human));assertEquals("COMPLETED",dispatch.status(request(human)).get("status"));
    }

    @Test void immediateHumanSendNeverAppearsAsAToolQueueItemBeforeWorkerStarts() {
        Map<String,Object> accepted;
        // Hold the scheduler lock so the worker cannot enter RUNNING before the view is read.
        synchronized (dispatch) {
            var body = new LinkedHashMap<>(human(source, "直接发送"));
            body.put("sessionId", source);
            accepted = dispatch.submit(body);
            assertTrue(items(source).isEmpty());
        }
        terminal(request(accepted));
    }

    @Test void removedMessagesCanBeUndoneWithCasAndEditingBlocksResume() {
        dispatch.pauseQueue(source,true);
        var sent=dispatch.submitHuman(source,human(source,"保留的草稿"));var row=items(source).get(0);
        act(source,row,"beginEdit");assertThrows(ChatTurnRejectException.class,()->dispatch.pauseQueue(source,false));
        act(source,row,"cancelEdit");var removed=act(source,row,"remove");assertTrue(items(source).isEmpty());
        assertThrows(ChatTurnRejectException.class,()->act(source,row,"undo"));
        dispatch.queueAction(source,request(sent),Map.of("action","undo","revision",removed.get("itemRevision")));
        assertEquals(1,items(source).size());assertTrue((boolean)dispatch.queueView(source).get("paused"));
    }

    @Test void restartRestoresHumanPayloadPausedAndDoesNotReplayStartedWork() throws Exception {
        dispatch.pauseQueue(source,true);
        var queued=dispatch.submitHuman(source,human(source,"重启后继续"));dispatch.close();
        var beans=new DefaultListableBeanFactory();beans.registerSingleton("ai",ai);
        dispatch=new SessionDispatchService(beans.getBeanProvider(AiService.class),receipts,wiring.sessions(),wiring.turnService(),stack.vault(),wiring.cipher(),jdbc,json);
        dispatch.recover();
        assertEquals("重启后继续",items(source).get(0).get("userText"));
        assertTrue((boolean)dispatch.queueView(source).get("paused"));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM ai_chat_turn",Integer.class));
        dispatch.pauseQueue(source,false);terminal(request(queued));
        assertEquals("COMPLETED",dispatch.status(request(queued)).get("status"));
    }

    @Test void recoveredAlreadyAcceptedOldRequestCanStillStart() {
        dispatch.pauseQueue(source,true);
        String old=UuidV7.generate(System.currentTimeMillis()-TimeUnit.DAYS.toMillis(2));
        var body=new LinkedHashMap<>(human(source,"昨天受理"));body.put("requestId",old);body.put("sessionId",source);
        var config=ai.captureConfig("");
        var repository=new ChatPendingRepository(jdbc,new ImmediateTransaction(jdbc.getDataSource()));
        try {repository.accept(old,source,"old-receipt",wiring.cipher().encrypt(json.writeValueAsString(Map.of("body",body,"config",ai.configReference(config))),""),null);}
        catch(Exception error){fail(error);}finally{config.close();}
        dispatch.pauseQueue(source,false);terminal(old);assertEquals("COMPLETED",dispatch.status(old).get("status"));
    }

    @Test void unsentSteeringReturnsToPausedQueueOnStop() throws Exception {
        var entered=new CountDownLatch(1);var release=block("hold",entered);
        var first=dispatch.submitHuman(source,human(source,"hold"));assertTrue(entered.await(3,TimeUnit.SECONDS));
        var body=new LinkedHashMap<>(human(source,"尚未发送的插话"));body.put("mode","steer");body.put("targetRequestId",request(first));
        var supplement=dispatch.submitHuman(source,body);
        dispatch.stop(source,request(first));release.countDown();terminal(request(first));
        assertEquals("尚未发送的插话",items(source).get(0).get("userText"));
        assertEquals("PAUSED",dispatch.status(request(supplement)).get("status"));
    }

    @Test void expiredRemovalCannotBeUndoneAndReleasesPayload() {
        dispatch.pauseQueue(source,true);
        var sent=dispatch.submitHuman(source,human(source,"撤销窗口已过"));
        var removed=act(source,items(source).get(0),"remove");
        jdbc.update("UPDATE ai_chat_pending SET removed_at=datetime('now','-1 minute') WHERE request_id=?",request(sent));
        assertThrows(ChatTurnRejectException.class,()->dispatch.queueAction(source,request(sent),Map.of("action","undo","revision",removed.get("itemRevision"))));
        dispatch.queueView(source);
        assertEquals(0L,jdbc.queryForObject("SELECT payload_bytes FROM ai_chat_pending WHERE request_id=?",Long.class,request(sent)));
    }

    @Test void restartDoesNotLeaveAnEmptySessionPermanentlyPaused() {
        dispatch.pauseQueue(source,true);dispatch.recover();
        assertFalse((boolean)dispatch.queueView(source).get("paused"));
    }

    @Test void failureBeforeBeginKeepsHumanInputAndCanResumeAfterRepair() {
        doThrow(new IllegalArgumentException("连接暂不可用")).doCallRealMethod().when(ai).runAccepted(anyMap(),any(),any(),any());
        var sent=dispatch.submitHuman(source,human(source,"失败也保留"));terminal(request(sent));
        assertEquals("PAUSED",dispatch.status(request(sent)).get("status"));
        assertEquals("失败也保留",items(source).get(0).get("userText"));
        dispatch.pauseQueue(source,false);terminal(request(sent));
        assertEquals("COMPLETED",dispatch.status(request(sent)).get("status"));
        assertEquals(2,wiring.sessions().messages(source).size());
    }

    @Test void failureAfterBeginNeverRestoresAlreadyExecutedHumanInput() {
        doAnswer(invocation->{invocation.callRealMethod();throw new IllegalStateException("回执丢失");}).when(ai).runAccepted(anyMap(),any(),any(),any());
        var sent=dispatch.submitHuman(source,human(source,"已经执行"));terminal(request(sent));
        assertTrue(items(source).isEmpty());
        assertEquals("CONSUMED",jdbc.queryForObject("SELECT state FROM ai_chat_pending WHERE request_id=?",String.class,request(sent)));
        dispatch.recover();assertTrue(items(source).isEmpty());
        assertEquals(2,wiring.sessions().messages(source).size());
    }
}
