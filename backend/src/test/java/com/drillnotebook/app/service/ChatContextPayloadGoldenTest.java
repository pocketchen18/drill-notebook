package com.drillnotebook.app.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.drillnotebook.app.model.ChatContextPlan;
import com.drillnotebook.app.model.Citation;
import com.drillnotebook.app.model.ContextAudit;
import com.drillnotebook.app.model.RetrievalHit;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 发给厂商的请求体 golden（执行方案 §9.3，验收 T08/T09/T11）。
 *
 * <p>形状固定成三段：只有固定规则的 system → 一条 {@code CONTEXT_DATA_V1} 数据消息 → 按时间的近期真实对话
 * → 当前请求最后。Anthropic 不接受连续同角色消息，合并后必须<b>保持块序</b>，当前请求仍是最后一个块。</p>
 */
class ChatContextPayloadGoldenTest {
    private static final String USER_TEXT = "当前问题";

    /** 期望的数据块原文：换一次分区名、分区顺序或键序就会在这里炸，而不是在某个前端里悄悄漂移。 */
    private static final String EXPECTED_ENVELOPE = """
            {"schemaVersion":1,"kind":"CONTEXT_DATA_V1","summary":null,\
            "memories":[{"sourceKind":"MEMORY","id":"m1","version":"1","text":"偏好：先看例子"}],\
            "notebookExcerpts":[{"sourceKind":"NOTEBOOK_RAG","id":"9","version":"11@chk-11",\
            "text":"[1] 标题9（章节）\\n片段正文"}],\
            "page":{"sourceKind":"PAGE","id":"9","version":"ph-9","text":"页面正文"},\
            "attachments":[{"sourceKind":"TEXT_ATTACHMENT","id":"t1","version":"b1","text":"附件正文"}],\
            "resumeState":null}\
            """;


    private final ObjectMapper mapper = new ObjectMapper();
    private final TokenEstimator estimator = new TokenEstimator(mapper);
    private final ChatContextAssembler assembler = new ChatContextAssembler(estimator,
            new ChatAttachmentReader(null, null, null, mapper));
    private final ContextBudgeter budgeter = new ContextBudgeter(estimator, mapper);
    private final AiUpstreamClient client = new AiUpstreamClient(java.net.http.HttpClient.newHttpClient(), mapper);

    @Test
    void chatCompletionsPayloadKeepsTheFrozenOrder() {
        ChatContextPlan plan = plan(List.of());

        assertEquals(List.of("system", "user", "user", "assistant", "user"),
                plan.messages().stream().map(row -> row.get("role")).toList());
        assertEquals(ChatContextAssembler.SYSTEM_PREFIX, textOf(plan.messages().get(0)),
                "system 只有固定规则，不带时间戳、ID 与个人数据（T11）");
        assertFalse(textOf(plan.messages().get(0)).contains(USER_TEXT));
        assertEquals(EXPECTED_ENVELOPE, textOf(plan.messages().get(1)));
        assertEquals("上一轮问题", textOf(plan.messages().get(2)));
        assertEquals("上一轮回答", textOf(plan.messages().get(3)));
        assertEquals(USER_TEXT, textOf(plan.messages().get(4)), "当前请求永远最后");

        Map<String, Object> body = client.buildChatCompletionRequest(
                upstream(AiUpstreamClient.FORMAT_CHAT_COMPLETIONS), plan.messages(), false);
        assertEquals("golden-model", body.get("model"));
        assertEquals(false, body.get("stream"));
        assertEquals(2048, body.get("max_tokens"));
        assertEquals(plan.messages(), body.get("messages"), "请求体里的消息就是计划本身，不再二次拼装");
    }

    @Test
    void anthropicPayloadPromotesSystemAndMergesUserBlocksInOrder() {
        ChatContextPlan plan = plan(List.of());

        Map<String, Object> body = client.buildAnthropicRequest(
                upstream(AiUpstreamClient.FORMAT_ANTHROPIC), plan.messages(), false);
        assertEquals(ChatContextAssembler.SYSTEM_PREFIX, body.get("system"), "system 提升到顶层，不进 messages");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> messages = (List<Map<String, Object>>) body.get("messages");
        assertEquals(List.of("user", "assistant", "user"), messages.stream().map(row -> row.get("role")).toList(),
                "连续 user 块合并成一条，仍保留 assistant 交替");
        assertEquals(2, blocks(messages.get(0)).size(), "数据块与上一轮用户发言并成一条多块消息");
        assertEquals(EXPECTED_ENVELOPE, String.valueOf(blocks(messages.get(0)).get(0).get("text")));
        assertEquals("上一轮问题", String.valueOf(blocks(messages.get(0)).get(1).get("text")), "合并不能换块序");
        assertEquals(USER_TEXT, String.valueOf(blocks(messages.get(2)).get(0).get("text")), "当前请求仍是最后一个块");
        assertEquals(2048, body.get("max_tokens"));
    }

    @Test
    void imagesTravelAsBase64SourceAndStayAfterTheCurrentText() {
        ChatContextPlan plan = plan(List.of(new ChatAttachmentService.Sourced("a1", "b1", "image", "图.png",
                "image/png", "", "iVBORw0KGgo=")));

        Map<String, Object> cc = client.buildChatCompletionRequest(
                upstream(AiUpstreamClient.FORMAT_CHAT_COMPLETIONS), plan.messages(), false);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> ccMessages = (List<Map<String, Object>>) cc.get("messages");
        List<Map<String, Object>> current = blocks(ccMessages.get(ccMessages.size() - 1));
        assertEquals(2, current.size(), "文本在前、图片在后");
        assertEquals(USER_TEXT, current.get(0).get("text"));
        assertEquals("data:image/png;base64,iVBORw0KGgo=",
                String.valueOf(map(current.get(1).get("image_url")).get("url")));

        Map<String, Object> anthropic = client.buildAnthropicRequest(
                upstream(AiUpstreamClient.FORMAT_ANTHROPIC), plan.messages(), true);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> messages = (List<Map<String, Object>>) anthropic.get("messages");
        List<Map<String, Object>> last = blocks(messages.get(messages.size() - 1));
        assertEquals("text", last.get(0).get("type"));
        assertEquals("image", last.get(1).get("type"));
        Map<String, Object> source = map(last.get(1).get("source"));
        assertEquals("base64", source.get("type"));
        assertEquals("image/png", source.get("media_type"));
        assertEquals("iVBORw0KGgo=", source.get("data"), "base64 原样交给厂商，不按长度当 Token 计");
        assertEquals(true, anthropic.get("stream"));
    }

    // ---------- 装配 ----------

    private ChatContextPlan plan(List<ChatAttachmentService.Sourced> images) {
        List<ChatAttachmentService.Sourced> attachments = new java.util.ArrayList<>(images);
        attachments.add(new ChatAttachmentService.Sourced("t1", "b1", "text", "材料.txt", "text/plain", "附件正文",
                null));
        ConversationProjection.Projection projection = new ConversationProjection.Projection(
                List.of(new ConversationProjection.Item(7L, "req-7", false, 70L, 71L, "上一轮问题", "上一轮回答",
                        List.of(), "2026-10-12T00:00:00.000Z", estimator.estimateText("上一轮问题上一轮回答"))),
                new ContextAudit.Scan(7L, 7L, 1, false), null, null, List.of(), 42L, List.of(), null);
        ContextAudit.Builder audit = new ContextAudit.Builder("plan-golden", TokenEstimator.VERSION);
        ChatContextAssembler.Draft draft = assembler.assemble(
                new ChatContextAssembler.Input(USER_TEXT, "", attachments, page(), "ph-9", true), projection,
                new ContextMemorySource.Selection(List.of(new ContextMemorySource.Fact("m1", 1, "PREFERENCE", "",
                        "偏好：先看例子", "GLOBAL", 0)), List.of(), 1, false),
                List.of(new RetrievalHit(new Citation("NOTEBOOK", 3L, 9L, 11L, "标题9", "章节", "片段正文",
                        List.of("bm25"), 1, null, null), "片段正文", 9L, 0, "chk-11")), audit);
        return budgeter.fit(draft, projection,
                new ContextBudgeter.Target("plan-golden", 1L, 2L, 42L, null, null, null, 6L, 32000, true), audit);
    }

    private Map<String, Object> page() {
        return Map.of("pageId", "9", "markdown", "页面正文");
    }

    private static AiUpstreamClient.Upstream upstream(String format) {
        return new AiUpstreamClient.Upstream("https://example.invalid/v1", "k", "golden-model", format, 2048, false);
    }

    private static String textOf(Map<String, Object> message) {
        return String.valueOf(blocks(message).get(0).get("text"));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> blocks(Map<String, Object> message) {
        return (List<Map<String, Object>>) message.get("content");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }
}
