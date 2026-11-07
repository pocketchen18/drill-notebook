package com.drillnotebook.app.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Predicate;
import java.util.function.Supplier;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageOutputStream;

/** 仅使用内存生成的测试内容；不访问业务工具、用户资料或临时文件。 */
final class AiModelVerifier {
    record Check(String status, String message, long elapsedMs) {
        boolean passed() { return "passed".equals(status); }
    }
    record Verification(Check text, Check image, Check tools, String checkedAt) { }
    record Challenge(String answer, String dataUrl) { }
    private final AiUpstreamClient client;
    private final ObjectMapper mapper;
    AiModelVerifier(AiUpstreamClient client, ObjectMapper mapper) { this.client = client; this.mapper = mapper; }

    Verification verify(AiUpstreamClient.Upstream target, ActiveTurnRegistry.Handle handle) {
        Check text = probe(handle, () -> client.call(target,
                List.of(Map.of("role", "user", "content", "Connection check. Please reply with pong.")), false, null, 30, handle),
                result -> result.text() != null && !result.text().isBlank(), "服务未返回有效正文");
        Check skipped = new Check("skipped", "文本未通过，未检测", 0);
        if (!text.passed()) return new Verification(text, skipped, skipped, Instant.now().toString());
        Check vision = probeImage(target, handle);
        String nonce = UUID.randomUUID().toString();
        var tool = new AiUpstreamClient.ToolDefinition("connection_check", "Echo the supplied nonce for a connection check. No side effects.",
                Map.of("type", "object", "properties", Map.of("nonce", Map.of("type", "string", "enum", List.of(nonce))),
                        "required", List.of("nonce"), "additionalProperties", false));
        Check tools = probe(handle, () -> client.callWithTools(target, List.of(Map.of("role", "user", "content",
                "Call connection_check exactly once with nonce " + nonce + ". Do not answer in text.")), List.of(tool), false, null, 20, handle),
                result -> validToolCall(result, nonce), "未返回正确的测试工具调用，本次未通过");
        return new Verification(text, vision, tools, Instant.now().toString());
    }

    private Check probeImage(AiUpstreamClient.Upstream target, ActiveTurnRegistry.Handle handle) {
        if (handle.cancelRequested()) return new Check("cancelled", "已取消", 0);
        try {
            Challenge image = imageChallenge();
            return probe(handle, () -> client.call(target, List.of(Map.of("role", "user", "content", List.of(
                Map.of("type", "text", "text", "Read the six black digits in this image from left to right. Reply with only the six digits."),
                Map.of("type", "image_url", "image_url", Map.of("url", image.dataUrl()))))), false, null, 20, handle),
                result -> image.answer().equals(result.text() == null ? "" : result.text().replaceAll("[^0-9]", "")), "未正确识别测试图片，本次未通过");
        } catch (RuntimeException error) { return new Check("failed", "测试图片生成失败，本次未检测", 0); }
    }

    private Check probe(ActiveTurnRegistry.Handle handle, Supplier<AiUpstreamClient.Result> send,
                        Predicate<AiUpstreamClient.Result> accept, String mismatch) {
        long start = System.currentTimeMillis();
        if (handle.cancelRequested()) return new Check("cancelled", "已取消", 0);
        try {
            var result = send.get();
            if (handle.cancelRequested()) return new Check("cancelled", "已取消", System.currentTimeMillis() - start);
            boolean valid = result.terminalObserved() && !result.captureLimited() && !result.downstreamGone()
                    && !java.util.Arrays.asList("length", "max_tokens", "content_filter").contains(result.finishReason()) && accept.test(result);
            return new Check(valid ? "passed" : "failed", valid ? "通过" : mismatch, System.currentTimeMillis() - start);
        } catch (RuntimeException error) {
            // 不向 UI/数据库复制上游响应，避免服务回显密钥、请求体或内部地址。
            String message = error.getMessage() != null && error.getMessage().contains("超时") ? "请求超时，本次未通过" : "请求失败，请检查连接或稍后重试";
            return new Check(handle.cancelRequested() ? "cancelled" : "failed", handle.cancelRequested() ? "已取消" : message, System.currentTimeMillis() - start);
        }
    }

    private boolean validToolCall(AiUpstreamClient.Result result, String nonce) {
        if (result.toolCalls().size() != 1) return false;
        var call = result.toolCalls().get(0);
        try {
            var args = mapper.readTree(call.arguments());
            return "connection_check".equals(call.name()) && args.isObject() && args.size() == 1 && nonce.equals(args.path("nonce").asText());
        } catch (Exception ignored) { return false; }
    }

    static Challenge imageChallenge() {
        String digits = String.format("%06d", ThreadLocalRandom.current().nextInt(1_000_000));
        int[] masks = {0x3f, 0x06, 0x5b, 0x4f, 0x66, 0x6d, 0x7d, 0x07, 0x7f, 0x6f};
        int[][] rects = {{8,4,28,6},{32,8,6,26},{32,38,6,26},{8,64,28,6},{4,38,6,26},{4,8,6,26},{8,34,28,6}};
        BufferedImage image = new BufferedImage(328, 90, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < image.getHeight(); y++) for (int x = 0; x < image.getWidth(); x++) image.setRGB(x, y, 0xffffff);
        for (int digit = 0; digit < digits.length(); digit++) for (int segment = 0; segment < 7; segment++) {
            if ((masks[digits.charAt(digit) - '0'] & (1 << segment)) == 0) continue;
            int[] r = rects[segment];
            for (int y = 0; y < r[3]; y++) for (int x = 0; x < r[2]; x++) image.setRGB(12 + digit * 52 + r[0] + x, 8 + r[1] + y, 0);
        }
        try (var bytes = new ByteArrayOutputStream(); var output = new MemoryCacheImageOutputStream(bytes)) {
            if (!ImageIO.write(image, "png", output)) throw new IllegalStateException("无法生成测试图片");
            output.flush();
            return new Challenge(digits, "data:image/png;base64," + Base64.getEncoder().encodeToString(bytes.toByteArray()));
        } catch (Exception error) { throw new IllegalStateException("无法生成测试图片", error); }
    }
}
