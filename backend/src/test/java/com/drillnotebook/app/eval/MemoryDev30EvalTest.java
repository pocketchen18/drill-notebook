package com.drillnotebook.app.eval;

import com.drillnotebook.app.service.AiUpstreamClient;
import com.drillnotebook.app.service.ApiKeyEncryptor;
import com.drillnotebook.app.service.memory.MemoryValidator;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 30-case development-set extraction evaluation. Disabled in the normal suite; enable with
 * {@code -Dmemory.eval=30 -Dmemory.eval.db=...}. Uses the real configured chat model and the
 * production extraction prompt, but only reads the config and never writes chat/memory rows.
 */
class MemoryDev30EvalTest {

    @Test
    void runDevelopmentSet() throws Exception {
        Assumptions.assumeTrue("30".equals(System.getProperty("memory.eval")),
                "set -Dmemory.eval=30 to run the paid live-model evaluation");
        Path db = Path.of(System.getProperty("memory.eval.db",
                "..\\runtime-portable\\data\\study.db")).toAbsolutePath().normalize();
        ObjectMapper mapper = new ObjectMapper();
        Config config = readConfig(db, mapper);
        System.out.println("eval target=" + config.endpoint() + " model=" + config.model()
                + " apiFormat=" + config.apiFormat());
        JsonNode fixture = readFixture(mapper);
        ArrayNode cases = (ArrayNode) fixture.get("cases");
        assertEquals(30, cases.size(), "fixture must contain exactly 30 cases");

        AiUpstreamClient client = new AiUpstreamClient(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build(), mapper);
        AiUpstreamClient.Upstream upstream = new AiUpstreamClient.Upstream(
                config.endpoint(), config.apiKey(), config.model(), config.apiFormat(), 1024, true, true);
        String system = MemoryValidator.systemPrompt();

        List<Map<String, Object>> report = new ArrayList<>();
        Set<String> only = new HashSet<>();
        for (String id : System.getProperty("memory.eval.only", "").split(",")) {
            if (!id.isBlank()) {
                only.add(id.trim());
            }
        }
        int formatOk = 0;
        int expectedTotal = 0;
        int matchedTotal = 0;
        int candidateTotal = 0;
        int quoteBad = 0;
        int forbiddenTotal = 0;
        int emptyFalsePositive = 0;

        for (JsonNode node : cases) {
            String id = node.path("id").asText();
            if (!only.isEmpty() && !only.contains(id)) {
                continue;
            }
            JsonNode input = node.path("input");
            List<JsonNode> messages = iterable(input.path("messages"));
            List<JsonNode> existing = iterable(input.path("existingFacts"));
            List<JsonNode> expected = iterable(node.path("expected"));
            List<String> forbidden = strings(node.path("forbidden"));
            boolean expectEmpty = expected.isEmpty();
            String payload = inputPayload(mapper, input, messages, existing);

            String raw;
            String error = null;
            long started = System.nanoTime();
            try {
                AiUpstreamClient.Result result = client.call(upstream,
                        List.of(Map.of("role", "system", "content", system),
                                Map.of("role", "user", "content", payload)),
                        false, null, 60);
                raw = result.text();
            } catch (Exception failure) {
                raw = "";
                error = failure.getClass().getSimpleName() + ": " + failure.getMessage();
            }
            long elapsedMs = (System.nanoTime() - started) / 1_000_000L;

            JsonNode parsed = null;
            boolean schema = false;
            String formatReason = "";
            if (error == null && raw != null && !raw.isBlank()) {
                try {
                    parsed = mapper.readTree(raw);
                    String version = parsed.path("schemaVersion").asText("").trim().toLowerCase();
                    boolean compatible = version.isEmpty() || version.equals("1") || version.equals("1.0")
                            || version.equals("2") || version.equals("2.0") || version.equals("v2");
                    schema = compatible && parsed.path("candidates").isArray();
                    if (!schema) {
                        formatReason = "schemaVersion=" + version + ", candidatesArray="
                                + parsed.path("candidates").isArray();
                    }
                } catch (Exception parseFailure) {
                    error = "JSON: " + parseFailure.getMessage();
                }
            }

            List<JsonNode> candidates = parsed == null ? List.of() : iterable(parsed.path("candidates"));
            List<Map<String, Object>> candidateDetails = new ArrayList<>();
            boolean quotes = true;
            boolean fields = schema;
            for (JsonNode candidate : candidates) {
                candidateTotal++;
                String operation = candidate.path("operation").asText();
                String kind = candidate.path("kind").asText();
                String predicate = candidate.path("predicate").asText();
                String quote = candidate.path("quote").asText();
                int userId = candidate.path("userMessageId").asInt(-1);
                String blockId = candidate.path("sourceBlockId").asText();
                if (!Set.of("ADD", "REPLACE_PROPOSAL", "RETRACT_PROPOSAL").contains(operation)
                        || !Set.of("PREFERENCE", "GOAL", "BACKGROUND", "TASK").contains(kind)
                        || !MemoryValidator.ALLOWED_PREDICATES.contains(predicate)
                        || quote.isBlank() || blockId.isBlank() || userId < 0) {
                    fields = false;
                    formatReason = "candidate field invalid: operation=" + operation + ", kind=" + kind
                            + ", predicate=" + predicate + ", quote=" + !quote.isBlank()
                            + ", block=" + blockId + ", userMessageId=" + userId;
                }
                if (!quoteInMessages(messages, userId, blockId, quote)) {
                    quotes = false;
                    quoteBad++;
                    formatReason = "quote not found in source block";
                }
                Map<String, Object> detail = new LinkedHashMap<>();
                detail.put("operation", operation);
                detail.put("kind", kind);
                detail.put("predicate", predicate);
                detail.put("value", candidate.path("value").isNull() ? null : candidate.path("value").asText());
                detail.put("quote", quote);
                detail.put("userMessageId", userId);
                detail.put("sourceBlockId", blockId);
                detail.put("targetMemoryId", candidate.path("targetMemoryId").isNull()
                        ? null : candidate.path("targetMemoryId").asText());
                candidateDetails.add(detail);
            }
            if (!quotes || !fields) {
                schema = false;
            }
            if (schema) {
                formatOk++;
            }

            boolean[] used = new boolean[candidates.size()];
            int matched = 0;
            for (JsonNode want : expected) {
                expectedTotal++;
                int found = matchCandidate(want, candidates, used);
                if (found >= 0) {
                    used[found] = true;
                    matched++;
                    matchedTotal++;
                }
            }
            int extras = 0;
            for (int i = 0; i < used.length; i++) {
                if (!used[i]) {
                    extras++;
                }
            }
            int forbiddenHits = 0;
            for (JsonNode candidate : candidates) {
                String value = candidate.path("value").asText("");
                for (String bad : forbidden) {
                    if (!bad.isBlank() && value.contains(bad)) {
                        forbiddenHits++;
                        forbiddenTotal++;
                    }
                }
            }
            if (expectEmpty && !candidates.isEmpty()) {
                emptyFalsePositive++;
            }

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", id);
            row.put("format", schema ? "OK" : "INVALID");
            row.put("candidates", candidates.size());
            row.put("expected", expected.size());
            row.put("matched", matched);
            row.put("extras", extras);
            row.put("quotes", quotes ? "OK" : "BAD");
            row.put("forbidden", forbiddenHits);
            row.put("elapsedMs", elapsedMs);
            row.put("candidateDetails", candidateDetails);
            if (!formatReason.isBlank()) {
                row.put("formatReason", formatReason);
            }
            if (error != null) {
                row.put("error", error);
            }
            if (!schema && raw != null && !raw.isBlank()) {
                row.put("raw", raw.length() > 2000 ? raw.substring(0, 2000) : raw);
            }
            report.add(row);
            System.out.printf("%-24s format=%-7s candidates=%d expected=%d matched=%d extras=%d quotes=%s forbidden=%d %dms%n",
                    id, schema ? "OK" : "INVALID", candidates.size(), expected.size(), matched, extras,
                    quotes ? "OK" : "BAD", forbiddenHits, elapsedMs);
        }

        double recall = expectedTotal == 0 ? 1.0 : (double) matchedTotal / expectedTotal;
        double precision = candidateTotal == 0 ? 1.0 : (double) matchedTotal / candidateTotal;
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("cases", report.size());
        summary.put("formatOk", formatOk);
        summary.put("expectedFacts", expectedTotal);
        summary.put("matchedFacts", matchedTotal);
        summary.put("candidates", candidateTotal);
        summary.put("recall", round(recall));
        summary.put("precision", round(precision));
        summary.put("quoteErrors", quoteBad);
        summary.put("forbiddenHits", forbiddenTotal);
        summary.put("emptyFalsePositiveCases", emptyFalsePositive);
        summary.put("results", report);
        Path output = Path.of("target", "memory-dev30-report.json").toAbsolutePath();
        Files.createDirectories(output.getParent());
        Files.writeString(output, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(summary),
                StandardCharsets.UTF_8);
        System.out.println("SUMMARY " + mapper.writeValueAsString(summary));
        System.out.println("report=" + output);
        if (!only.isEmpty()) {
            System.out.println("diagnostic subset: assertions skipped");
            return;
        }

        assertTrue(formatOk >= 28, "format validity below 28/30: " + formatOk);
        assertTrue(recall >= 0.80, "recall below 80%: " + recall);
        assertTrue(precision >= 0.75, "precision below 75%: " + precision);
        assertEquals(0, forbiddenTotal, "forbidden facts emitted");
        assertEquals(0, emptyFalsePositive, "empty cases emitted candidates");
    }

    private static String inputPayload(ObjectMapper mapper, JsonNode input, List<JsonNode> messages,
                                       List<JsonNode> existing) throws Exception {
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
        List<Map<String, Object>> facts = new ArrayList<>();
        for (JsonNode fact : existing) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("memoryId", fact.path("memoryId").asText());
            row.put("version", fact.path("version").asInt());
            row.put("kind", fact.path("kind").asText());
            row.put("predicate", fact.path("predicate").asText());
            row.put("value", fact.path("value").asText());
            row.put("manualProtected", fact.path("manualProtected").asBoolean(false));
            facts.add(row);
        }
        payload.put("existingFacts", facts);
        return mapper.writeValueAsString(payload);
    }

    private static int matchCandidate(JsonNode want, List<JsonNode> candidates, boolean[] used) {
        String operation = want.path("operation").asText("");
        List<String> operations = strings(want.path("operations"));
        String kind = want.path("kind").asText("");
        List<String> predicates = strings(want.path("predicates"));
        List<String> contains = strings(want.path("valueContains"));
        List<String> any = strings(want.path("valueAny"));
        String target = want.path("targetMemoryId").asText("");
        for (int i = 0; i < candidates.size(); i++) {
            if (used[i]) {
                continue;
            }
            JsonNode candidate = candidates.get(i);
            if (!operation.isBlank() && !operation.equals(candidate.path("operation").asText())) {
                continue;
            }
            if (!operations.isEmpty() && !operations.contains(candidate.path("operation").asText())) {
                continue;
            }
            if (!kind.isBlank() && !kind.equals(candidate.path("kind").asText())) {
                continue;
            }
            if (!predicates.isEmpty() && !predicates.contains(candidate.path("predicate").asText())) {
                continue;
            }
            if (!target.isBlank() && !target.equals(candidate.path("targetMemoryId").asText())) {
                continue;
            }
            if (!"RETRACT_PROPOSAL".equals(candidate.path("operation").asText())) {
                String value = compact(candidate.path("value").asText(""));
                boolean all = contains.stream().map(MemoryDev30EvalTest::compact).allMatch(value::contains);
                boolean one = any.isEmpty()
                        || any.stream().map(MemoryDev30EvalTest::compact).anyMatch(value::contains);
                if (!all || !one) {
                    continue;
                }
            }
            return i;
        }
        return -1;
    }

    private static String compact(String value) {
        return value == null ? "" : value.replaceAll("\\s+", "");
    }

    private static boolean quoteInMessages(List<JsonNode> messages, int userId, String blockId, String quote) {
        for (JsonNode message : messages) {
            if (message.path("id").asInt() == userId && blockId.equals(message.path("blockId").asText())) {
                return message.path("text").asText().contains(quote);
            }
        }
        return false;
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
        try (InputStream stream = MemoryDev30EvalTest.class.getResourceAsStream("/eval/memory-dev-30.json")) {
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

    private static double round(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }

    private record Config(String endpoint, String apiKey, String model, String apiFormat) {
    }
}
