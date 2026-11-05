package com.drillnotebook.app.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.drillnotebook.app.service.AiUpstreamClient;
import com.drillnotebook.app.service.ApiKeyEncryptor;
import com.drillnotebook.app.service.memory.MemoryValidator;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * 120-case live end-to-end evaluation.
 *
 * <p>Every fixture is evaluated in three tracks with the real configured model:
 * A = full session history, B = bounded history (current question only),
 * C = bounded history plus the facts extracted by the model. The report keeps
 * failures and all token usage; it never turns a failed call into a pass.</p>
 *
 * <p>Gate: {@code -Dmemory.eval=120}. Use {@code -Dmemory.eval.limit=N} for a
 * paid smoke subset. The database is read-only for configuration only.</p>
 */
class Memory120EvalTest {

    private static final long PACE_MILLIS = Long.getLong("memory.eval.paceMs", 3100L);
    private static long lastCallNanos;

    @Test
    void runEndToEndSet() throws Exception {
        Assumptions.assumeTrue("120".equals(System.getProperty("memory.eval")),
                "set -Dmemory.eval=120 to run the paid 120-case evaluation");
        Path db = Path.of(System.getProperty("memory.eval.db",
                "..\\runtime-portable\\data\\study.db")).toAbsolutePath().normalize();
        ObjectMapper mapper = new ObjectMapper();
        Config config = readConfig(db, mapper);
        int limit = Math.max(1, Integer.getInteger("memory.eval.limit", 120));
        System.out.println("eval=120 target=" + config.endpoint() + " model=" + config.model()
                + " limit=" + limit);

        List<JsonNode> cases = new ArrayList<>();
        readFixture(mapper).path("cases").forEach(cases::add);
        Holdout90Fixtures.root(mapper).path("cases").forEach(cases::add);
        assertEquals(120, cases.size(), "fixture must contain 30 dev + 90 holdout cases");
        if (limit > cases.size()) {
            limit = cases.size();
        }

        AiUpstreamClient client = new AiUpstreamClient(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build(), mapper);
        AiUpstreamClient.Upstream extraction = new AiUpstreamClient.Upstream(
                config.endpoint(), config.apiKey(), config.model(), config.apiFormat(), 1024, true, true);
        AiUpstreamClient.Upstream chat = new AiUpstreamClient.Upstream(
                config.endpoint(), config.apiKey(), config.model(), config.apiFormat(), 256, true, false);
        String extractionSystem = MemoryValidator.systemPrompt();
        String chatSystem = "你是学习助手。只根据对话和记忆回答，不要编造；不知道就明确说不知道。回答控制在两句话内。";

        List<Map<String, Object>> rows = new ArrayList<>();
        long totalInput = 0;
        long totalOutput = 0;
        long totalCacheRead = 0;
        long totalCacheWrite = 0;
        int extractionFormatOk = 0;
        List<Track> tracks = List.of(new Track("A"), new Track("B"), new Track("C"));

        for (int index = 0; index < limit; index++) {
            JsonNode node = cases.get(index);
            String id = node.path("id").asText("case-" + index);
            String category = categoryOf(id);
            JsonNode input = node.path("input");
            List<JsonNode> messages = iterable(input.path("messages"));
            List<JsonNode> expected = iterable(node.path("expected"));
            List<String> forbidden = strings(node.path("forbidden"));
            List<Claim> claims = claims(expected);
            String question = questionFor(expected);
            String payload = inputPayload(mapper, input, messages);

            AiUpstreamClient.Result extractionResult;
            String extractionError = null;
            long extractionStarted = System.nanoTime();
            try {
                extractionResult = paced(client, extraction,
                        List.of(Map.of("role", "system", "content", extractionSystem),
                                Map.of("role", "user", "content", payload)),
                        60);
            } catch (Exception failure) {
                extractionResult = null;
                extractionError = failure.getClass().getSimpleName() + ": " + failure.getMessage();
            }
            long extractionMs = (System.nanoTime() - extractionStarted) / 1_000_000L;
            JsonNode parsed = null;
            if (extractionResult != null && extractionResult.text() != null
                    && !extractionResult.text().isBlank()) {
                try {
                    parsed = mapper.readTree(extractionResult.text());
                } catch (Exception failure) {
                    extractionError = "JSON: " + failure.getMessage();
                }
            }
            List<JsonNode> candidates = parsed == null ? List.of() : iterable(parsed.path("candidates"));
            boolean extractionFormat = parsed != null && parsed.path("candidates").isArray();
            if (extractionFormat) {
                extractionFormatOk++;
            }
            String memory = memoryBlock(candidates);

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", id);
            row.put("category", category);
            row.put("expectedClaims", claims.stream().map(Claim::toString).toList());
            row.put("forbidden", forbidden);
            row.put("extractionFormat", extractionFormat);
            row.put("extractionCandidates", candidates.size());
            row.put("extractionError", extractionError);
            row.put("extractionMs", extractionMs);
            row.put("extractionUsage", usage(extractionResult));
            totalInput += usageLong(extractionResult, "input");
            totalOutput += usageLong(extractionResult, "output");
            totalCacheRead += usageLong(extractionResult, "cacheRead");
            totalCacheWrite += usageLong(extractionResult, "cacheWrite");

            List<Map<String, Object>> trackRows = new ArrayList<>();
            for (Track track : tracks) {
                List<Map<String, Object>> chatMessages = chatMessages(chatSystem, messages, question, memory, track.name);
                AiUpstreamClient.Result answer;
                String error = null;
                long started = System.nanoTime();
                try {
                    answer = paced(client, chat, chatMessages, 60);
                } catch (Exception failure) {
                    answer = null;
                    error = failure.getClass().getSimpleName() + ": " + failure.getMessage();
                }
                long elapsed = (System.nanoTime() - started) / 1_000_000L;
                String text = answer == null || answer.text() == null ? "" : answer.text();
                boolean ok = answerOk(text, claims, forbidden);
                Map<String, Object> trackRow = new LinkedHashMap<>();
                trackRow.put("track", track.name);
                trackRow.put("ok", ok);
                trackRow.put("answer", text);
                trackRow.put("error", error);
                trackRow.put("latencyMs", elapsed);
                trackRow.put("usage", usage(answer));
                trackRows.add(trackRow);
                totalInput += usageLong(answer, "input");
                totalOutput += usageLong(answer, "output");
                totalCacheRead += usageLong(answer, "cacheRead");
                totalCacheWrite += usageLong(answer, "cacheWrite");
                if (ok) {
                    track.ok++;
                }
                track.total++;
            }
            row.put("tracks", trackRows);
            rows.add(row);
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("cases", rows.size());
        summary.put("extractionFormatOk", extractionFormatOk);
        summary.put("tracks", tracks.stream().map(track -> Map.of(
                "track", track.name, "ok", track.ok, "total", track.total,
                "rate", track.total == 0 ? 0.0 : round((double) track.ok / track.total))).toList());
        summary.put("totalInputTokens", totalInput);
        summary.put("totalOutputTokens", totalOutput);
        summary.put("totalCacheReadTokens", totalCacheRead);
        summary.put("totalCacheWriteTokens", totalCacheWrite);
        summary.put("results", rows);
        Path output = Path.of("target", "memory-120-report.json").toAbsolutePath();
        Files.createDirectories(output.getParent());
        Files.writeString(output, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(summary),
                StandardCharsets.UTF_8);
        System.out.println("SUMMARY " + mapper.writeValueAsString(summary));
        System.out.println("report=" + output);

        assertEquals(limit, rows.size(), "every requested fixture must produce a report row");
        assertTrue(extractionFormatOk > 0, "the configured model returned no valid extraction envelope");
    }

    private static AiUpstreamClient.Result paced(AiUpstreamClient client, AiUpstreamClient.Upstream upstream,
                                                  List<Map<String, Object>> messages, int timeoutSeconds)
            throws Exception {
        long wait = PACE_MILLIS - (System.nanoTime() - lastCallNanos) / 1_000_000L;
        if (wait > 0) {
            Thread.sleep(wait);
        }
        lastCallNanos = System.nanoTime();
        try {
            return client.call(upstream, messages, false, null, timeoutSeconds);
        } catch (IllegalArgumentException failure) {
            if (failure.getMessage() != null && failure.getMessage().contains("HTTP 429")) {
                Thread.sleep(65_000L);
                lastCallNanos = System.nanoTime();
                return client.call(upstream, messages, false, null, timeoutSeconds);
            }
            throw failure;
        }
    }

    private static List<Map<String, Object>> chatMessages(String system, List<JsonNode> messages,
                                                           String question, String memory, String track) {
        List<Map<String, Object>> chat = new ArrayList<>();
        chat.add(Map.of("role", "system", "content", system));
        if ("A".equals(track)) {
            for (JsonNode message : messages) {
                String text = message.path("text").asText("");
                if (!text.isBlank()) {
                    chat.add(Map.of("role", "user", "content", text));
                    chat.add(Map.of("role", "assistant", "content", "好的，我记住了。"));
                }
            }
        } else if ("C".equals(track)) {
            chat.add(Map.of("role", "user", "content", "长期记忆：\n" + memory));
        }
        chat.add(Map.of("role", "user", "content", question));
        return chat;
    }

    private static String memoryBlock(List<JsonNode> candidates) {
        if (candidates.isEmpty()) {
            return "（无）";
        }
        List<String> lines = new ArrayList<>();
        for (JsonNode candidate : candidates) {
            String value = candidate.path("value").asText("");
            if (!value.isBlank()) {
                lines.add("- " + candidate.path("predicate").asText("other") + ": " + value);
            }
        }
        return lines.isEmpty() ? "（无）" : String.join("\n", lines);
    }

    private static boolean answerOk(String answer, List<Claim> claims, List<String> forbidden) {
        String compact = compact(answer);
        if (compact.isEmpty()) {
            return false;
        }
        for (String bad : forbidden) {
            if (!bad.isBlank() && compact.contains(compact(bad))) {
                return false;
            }
        }
        for (Claim claim : claims) {
            boolean all = claim.all.stream().map(Memory120EvalTest::compact).allMatch(compact::contains);
            boolean any = claim.any.isEmpty()
                    || claim.any.stream().map(Memory120EvalTest::compact).anyMatch(compact::contains);
            if (!all || !any) {
                return false;
            }
        }
        return true;
    }

    private static List<Claim> claims(List<JsonNode> expected) {
        List<Claim> claims = new ArrayList<>();
        for (JsonNode item : expected) {
            List<String> all = strings(item.path("valueContains"));
            List<String> any = strings(item.path("valueAny"));
            if (!all.isEmpty() || !any.isEmpty()) {
                claims.add(new Claim(all, any));
            }
        }
        return claims;
    }

    private static String questionFor(List<JsonNode> expected) {
        boolean language = false;
        boolean detail = false;
        boolean order = false;
        boolean goal = false;
        boolean background = false;
        boolean constraint = false;
        for (JsonNode item : expected) {
            for (String predicate : strings(item.path("predicates"))) {
                language |= "answer.language".equals(predicate);
                detail |= "answer.detail".equals(predicate);
                order |= "answer.order".equals(predicate);
                goal |= "study.goal".equals(predicate);
                background |= "study.background".equals(predicate);
                constraint |= "task.constraint".equals(predicate);
            }
        }
        if (language) {
            return "以后回答我时用哪种语言？只回答语言名称。";
        }
        if (order) {
            return "以后回答某类问题时先给什么？只回答关键词。";
        }
        if (detail) {
            return "以后解释时希望详细还是简短？只回答一个词。";
        }
        if (goal) {
            return "我的目标是什么？只回答目标。";
        }
        if (background) {
            return "我的背景是什么？只回答地点、身份或相关经历。";
        }
        if (constraint) {
            return "我的任务约束是什么？只回答约束。";
        }
        return "请复述我让你长期记住的内容。只回答该内容。";
    }

    private static String categoryOf(String id) {
        int dash = id.indexOf('-');
        return dash >= 0 ? id.substring(dash + 1) : "uncategorized";
    }

    private static Map<String, Object> usage(AiUpstreamClient.Result result) {
        if (result == null || result.usage() == null) {
            return Map.of("source", "none");
        }
        AiUpstreamClient.Usage usage = result.usage();
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("source", usage.source());
        row.put("input", usage.inputTokens());
        row.put("output", usage.outputTokens());
        row.put("cacheRead", usage.cacheReadTokens());
        row.put("cacheWrite", usage.cacheWriteTokens());
        row.put("totalInput", usage.totalInputTokens());
        return row;
    }

    private static long usageLong(AiUpstreamClient.Result result, String field) {
        if (result == null || result.usage() == null) {
            return 0;
        }
        AiUpstreamClient.Usage usage = result.usage();
        Integer value = switch (field) {
            case "input" -> usage.inputTokens();
            case "output" -> usage.outputTokens();
            case "cacheRead" -> usage.cacheReadTokens();
            case "cacheWrite" -> usage.cacheWriteTokens();
            default -> null;
        };
        return value == null ? 0 : value;
    }

    private static String inputPayload(ObjectMapper mapper, JsonNode input, List<JsonNode> messages)
            throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schemaVersion", 2);
        payload.put("scope", Map.of("type", input.path("scopeType").asText("GLOBAL"),
                "id", input.path("scopeId").asLong(0)));
        payload.put("sourceTimezone", input.path("timezone").asText("Asia/Shanghai"));
        payload.put("allowedPredicates", MemoryValidator.ALLOWED_PREDICATES);
        List<Map<String, Object>> userMessages = new ArrayList<>();
        for (JsonNode message : messages) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", message.path("id").asLong());
            row.put("turnId", message.path("turnId").asLong());
            row.put("blockId", message.path("blockId").asText());
            row.put("origin", "USER_AUTHORED");
            row.put("createdAt", message.path("createdAt").asText());
            row.put("text", message.path("text").asText());
            userMessages.add(row);
        }
        payload.put("userMessages", userMessages);
        payload.put("existingFacts", List.of());
        return mapper.writeValueAsString(payload);
    }

    private static Config readConfig(Path db, ObjectMapper mapper) throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + db.toAbsolutePath());
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT provider, endpoint, model, encrypted_key, key_meta, params FROM ai_config WHERE purpose='chat'")) {
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) {
                    throw new IllegalStateException("no chat ai_config row in " + db);
                }
                Map<String, Object> meta = mapper.readValue(row.getString("key_meta"), new TypeReference<>() {});
                String mode = String.valueOf(meta.getOrDefault("mode", "fingerprint"));
                if (!"fingerprint".equals(mode)) {
                    throw new IllegalStateException("chat key uses password mode; eval needs fingerprint mode");
                }
                ApiKeyEncryptor encryptor = new ApiKeyEncryptor();
                String key = encryptor.decrypt(row.getString("encrypted_key"),
                        String.valueOf(meta.get("salt")), String.valueOf(meta.get("iv")),
                        encryptor.fingerprintMaterial());
                String params = row.getString("params");
                String apiFormat = "chat_completions";
                if (params != null && !params.isBlank()) {
                    Map<String, Object> parsed = mapper.readValue(params, new TypeReference<>() {});
                    if (parsed.get("apiFormat") != null) {
                        apiFormat = String.valueOf(parsed.get("apiFormat"));
                    }
                }
                return new Config(row.getString("endpoint"), key, row.getString("model"), apiFormat);
            }
        }
    }

    private static JsonNode readFixture(ObjectMapper mapper) throws Exception {
        try (InputStream stream = Memory120EvalTest.class.getResourceAsStream("/eval/memory-dev-30.json")) {
            if (stream == null) {
                throw new IllegalStateException("missing /eval/memory-dev-30.json");
            }
            return mapper.readTree(stream);
        }
    }

    private static List<JsonNode> iterable(JsonNode node) {
        List<JsonNode> values = new ArrayList<>();
        if (node != null && node.isArray()) {
            node.forEach(values::add);
        }
        return values;
    }

    private static List<String> strings(JsonNode node) {
        List<String> values = new ArrayList<>();
        if (node != null && node.isArray()) {
            node.forEach(value -> values.add(value.asText()));
        }
        return values;
    }

    private static String compact(String value) {
        return value == null ? "" : value.replaceAll("\\s+", "");
    }

    private static double round(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }

    private record Config(String endpoint, String apiKey, String model, String apiFormat) {
    }

    private record Claim(List<String> all, List<String> any) {
        @Override
        public String toString() {
            return all + (any.isEmpty() ? "" : " any=" + any);
        }
    }

    private static final class Track {
        private final String name;
        private int ok;
        private int total;

        private Track(String name) {
            this.name = name;
        }
    }
}
