package com.drillnotebook.app.service;

import com.drillnotebook.app.repository.MemoryRepository;
import com.drillnotebook.app.service.memory.MemoryCryptoService;
import com.drillnotebook.app.service.memory.MemoryVaultService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * P4 的记忆来源实现：手工录入的 ACTIVE 事实 + 一份<b>最小</b>词法相关性判定。
 *
 * <p>它刻意只做三件事——作用域过滤、到期过滤、"有一个共同词才可能入选"，其余（BM25 排序、索引快照、
 * 冲突抑制、版本一致缓存）都属于 P5；P5 换掉这个类即可，组装器与预算都不动。
 * 之所以现在就加相关性门槛，是因为 §9.1-6 与 M12 要求"不为凑数注入无关记忆"，
 * 而"取最近 3 条"会把完全无关的事实送进 prompt。</p>
 */
@Component
public class ManualMemorySource implements ContextMemorySource {
    /** §9.1-5：主题事实最多 3 条。 */
    public static final int TOPICAL_LIMIT = 3;
    /** §9.1-3/5：通用回答风格偏好最多 2 条。 */
    public static final int STYLE_LIMIT = 2;
    /** 一次扫描的候选上限：作用域内 ACTIVE 再大也不逐条解密（§9.1-7 的读边界）。 */
    static final int CANDIDATE_SCAN_LIMIT = 200;

    private static final Logger log = LoggerFactory.getLogger(ManualMemorySource.class);
    private static final String KIND_PREFERENCE = "PREFERENCE";

    private final MemoryRepository memory;
    private final MemoryVaultService vault;
    private final MemoryCryptoService crypto;
    private final ObjectMapper mapper;

    public ManualMemorySource(MemoryRepository memory, MemoryVaultService vault, MemoryCryptoService crypto,
                              ObjectMapper mapper) {
        this.memory = memory;
        this.vault = vault;
        this.crypto = crypto;
        this.mapper = mapper;
    }

    @Override
    public Selection select(String vaultId, String scopeType, long scopeId, String queryText, long nowMillis,
                           int deadlineMillis) {
        Optional<MemoryCryptoService.MemoryKey> key = vault.currentKey();
        if (key.isEmpty() || vaultId == null) return Selection.EMPTY;
        long deadlineNanos = System.nanoTime() + Math.max(1, deadlineMillis) * 1_000_000L;
        Set<String> queryTokens = tokens(queryText);
        List<Scored> topical = new ArrayList<>();
        List<ContextMemorySource.Fact> style = new ArrayList<>();
        int seen = 0;
        try {
            // NOTEBOOK 作用域读自身 + GLOBAL；GLOBAL 作用域只读 GLOBAL（M08）
            seen += collect(key.get(), vaultId, "GLOBAL", 0L, queryTokens, topical, style, nowMillis, deadlineNanos);
            if ("NOTEBOOK".equals(scopeType) && scopeId > 0) {
                seen += collect(key.get(), vaultId, "NOTEBOOK", scopeId, queryTokens, topical, style, nowMillis,
                        deadlineNanos);
            }
        } catch (SQLException error) {
            // 记忆读失败只让本轮不带记忆，不能把用户的聊天打掉（§9.1-7）
            log.warn("记忆候选读取失败，本轮降级为无记忆: {}", error.toString());
            return Selection.EMPTY;
        }
        topical.sort(Comparator.comparingInt(Scored::score).reversed()
                .thenComparing(Scored::confirmedAt, Comparator.reverseOrder())
                .thenComparing(row -> row.fact().memoryId()));
        List<ContextMemorySource.Fact> chosenTopical = new ArrayList<>();
        for (Scored scored : topical) {
            if (chosenTopical.size() >= TOPICAL_LIMIT) break;
            chosenTopical.add(scored.fact());
        }
        List<ContextMemorySource.Fact> chosenStyle = style.size() > STYLE_LIMIT
                ? List.copyOf(style.subList(0, STYLE_LIMIT)) : style;
        return new Selection(chosenTopical, chosenStyle, seen, System.nanoTime() > deadlineNanos);
    }

    private record Scored(ContextMemorySource.Fact fact, int score, String confirmedAt) {
    }

    /** 读一个作用域内的 ACTIVE 事实；返回看了多少候选，超时的作用域直接停止扫描。 */
    private int collect(MemoryCryptoService.MemoryKey key, String vaultId, String scopeType, long scopeId,
                        Set<String> queryTokens, List<Scored> topical, List<ContextMemorySource.Fact> style,
                        long nowMillis, long deadlineNanos) throws SQLException {
        List<MemoryRepository.FactRow> rows = memory.findPage(vaultId, MemoryRepository.STATUS_ACTIVE, scopeType,
                scopeId, null, null, CANDIDATE_SCAN_LIMIT);
        int seen = 0;
        for (MemoryRepository.FactRow row : rows) {
            if (System.nanoTime() > deadlineNanos) {
                log.warn("记忆候选扫描超过总时限，本轮按无记忆降级");
                return seen;
            }
            seen++;
            if (expired(row, nowMillis)) continue;
            Map<String, Object> payload = decrypt(key, row);
            if (payload == null) continue;
            ContextMemorySource.Fact fact = new ContextMemorySource.Fact(row.memoryId(), row.version(), row.kind(),
                    text(payload.get("predicate")), text(payload.get("value")), scopeType, scopeId);
            if (KIND_PREFERENCE.equals(row.kind()) && "GLOBAL".equals(scopeType)) {
                style.add(fact);
                continue;
            }
            int score = overlap(queryTokens, payload);
            // 零重合的主题事实不入候选（§9.1-4）：宁可少注入，也不拿无关记忆凑数
            if (score <= 0) continue;
            topical.add(new Scored(fact, score, row.lastConfirmedAt()));
        }
        return seen;
    }

    private Map<String, Object> decrypt(MemoryCryptoService.MemoryKey key, MemoryRepository.FactRow row) {
        try {
            String json = crypto.decrypt(key, "ai_memory_item", row.rowId(), row.version(), row.payloadCipher(),
                    row.cryptoMeta());
            return mapper.readValue(json, new TypeReference<LinkedHashMap<String, Object>>() {
            });
        } catch (Exception error) {
            // 单行损坏只隔离这一条，不把乱码送进模型（M14）
            return null;
        }
    }

    private static boolean expired(MemoryRepository.FactRow row, long nowMillis) {
        if (row.validUntil() == null || row.validUntil().isBlank()) return false;
        try {
            return java.time.Instant.parse(row.validUntil()).toEpochMilli() <= nowMillis;
        } catch (Exception error) {
            return true;
        }
    }

    /** 相关性打分：query 与 predicate/value 的共同词数；零分不入候选（§9.1-4）。 */
    private static int overlap(Set<String> queryTokens, Map<String, Object> payload) {
        if (queryTokens.isEmpty()) return 0;
        Set<String> factTokens = tokens(text(payload.get("predicate")) + ' ' + text(payload.get("value")));
        int shared = 0;
        for (String token : queryTokens) {
            if (factTokens.contains(token)) shared++;
        }
        return shared;
    }

    /**
     * 最小分词（§9.1-2 的形状，但不是 P5 的正式实现）：NFKC + 小写，Latin/数字按词切，
     * 汉字连续段取相邻二元组；单字词丢弃，避免"我/你/的"这类把什么都判成相关。
     */
    static Set<String> tokens(String value) {
        if (value == null || value.isBlank()) return Set.of();
        String normalized = java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT);
        Set<String> tokens = new HashSet<>();
        StringBuilder latin = new StringBuilder();
        List<Character> cjkRun = new ArrayList<>();
        for (int index = 0; index < normalized.length(); index++) {
            char symbol = normalized.charAt(index);
            if (isCjkIdeograph(symbol)) {
                flushLatin(latin, tokens);
                cjkRun.add(symbol);
                continue;
            }
            flushCjk(cjkRun, tokens);
            if (Character.isLetterOrDigit(symbol)) {
                latin.append(symbol);
            } else {
                flushLatin(latin, tokens);
            }
        }
        flushLatin(latin, tokens);
        flushCjk(cjkRun, tokens);
        return tokens;
    }

    private static boolean isCjkIdeograph(char symbol) {
        return symbol >= 0x4E00 && symbol <= 0x9FFF;
    }

    private static void flushLatin(StringBuilder latin, Set<String> tokens) {
        if (latin.length() > 1) tokens.add(latin.toString());
        latin.setLength(0);
    }

    private static void flushCjk(List<Character> run, Set<String> tokens) {
        if (run.size() >= 2) {
            for (int index = 0; index + 1 < run.size(); index++) {
                tokens.add(String.join("", String.valueOf(run.get(index)), String.valueOf(run.get(index + 1))));
            }
        }
        run.clear();
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
