package com.drillnotebook.app.service.memory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * 进程内不可变词法索引快照（执行方案 §9.1、§14 P5：无远程调用、无向量、不建 FTS 明文表）。
 *
 * <p>打分是 posting 驱动的：代价随"命中文档数"增长，而不是随"全库条数 × 查询词数"增长。P0a 实测同一份
 * 2,000 条库里，事实-major 扫描中位 0.269ms、posting 驱动 0.017ms。</p>
 *
 * <p>{@link #MAX_DOC_FREQUENCY_RATIO} 与 {@link #CANDIDATE_PRUNE_LIMIT} 同样是<b>延迟</b>护栏：
 * 常见二元组（"笔记""复习"）的 posting 近乎全库，不截断时 p95 会被拖到几百毫秒（P0a 实测）。</p>
 *
 * <p>{@link #MIN_MATCHED_TERMS} 与 {@link #MIN_MATCH_RATIO} 是 P0a 新发现的<b>必要</b>相关性下限，不是调参：
 * 只按 BM25 排序时，"量子色动力学…"的查询会因为共享一个"力学"二元组被塞进 3 条结构力学目标，
 * 直接违反 §9.1 第 6 条"不为凑 3 条注入无关记忆"。</p>
 */
public final class MemorySearchIndex {
    public static final double BM25_K1 = 1.2;
    public static final double BM25_B = 0.75;
    /** §9.1 第 3 条：BM25 生成前 30 条候选。 */
    public static final int CANDIDATE_LIMIT = 30;
    /** §9.1 第 5 条：主题事实最多 3 条。 */
    public static final int TOPICAL_LIMIT = 3;
    /** §9.1 第 3/5 条：另加最多 2 条通用回答风格偏好。 */
    public static final int STYLE_LIMIT = 2;
    /** 相关性下限：至少命中两个查询词。 */
    public static final int MIN_MATCHED_TERMS = 2;
    /** 相关性下限：命中词数至少占查询词数的两成。 */
    public static final double MIN_MATCH_RATIO = 0.2;
    /** 泛词上限：出现在四成以上候选里的查询词直接跳过（P0a 实测压 p95 用）。 */
    public static final double MAX_DOC_FREQUENCY_RATIO = 0.4;
    /** 泛词上限只在库大到确有扫描成本时才生效：小库里截断只会改变结果，换不来延迟。 */
    static final int GENERAL_TERM_MIN_CORPUS = 32;
    /** 候选截断：先按 BM25 取前 N 条，再套分层排序，保证分层比较器只做有限次比较。 */
    public static final int CANDIDATE_PRUNE_LIMIT = 200;

    /**
     * 一条已解密、状态为 ACTIVE 的事实；{@code validUntilEpochMilli} 用 {@link Long#MAX_VALUE} 表示永不过期。
     *
     * @param keywords 载荷里的检索扩展词（contracts §5 的 {@code keywords[]}，最多 8 个、由服务端模板把关）。
     *                 手工录入的事实没有这个词，留空即可。
     */
    public record Doc(String memoryId, int version, String kind, String predicate, String value, String scopeType,
                      long scopeId, String familyKeyHmac, String predicateKeyHmac, long validUntilEpochMilli,
                      long confirmedAtEpochMilli, List<String> keywords) {

        Doc(String memoryId, int version, String kind, String predicate, String value, String scopeType, long scopeId,
            String familyKeyHmac, String predicateKeyHmac, long validUntilEpochMilli, long confirmedAtEpochMilli) {
            this(memoryId, version, kind, predicate, value, scopeType, scopeId, familyKeyHmac, predicateKeyHmac,
                    validUntilEpochMilli, confirmedAtEpochMilli, List.of());
        }

        public Doc {
            keywords = keywords == null ? List.of() : List.copyOf(keywords);
        }

        /** §9.1 第 3 条的"通用回答风格偏好"：contracts §1 里只有 {@code answer.*} 属于这个区。 */
        public boolean style() {
            return MemoryLexicon.isAnswerStyle(kind, predicate);
        }

        public String scopeKey() {
            return scopeType + "|" + scopeId;
        }

        /** 进倒排索引的正文：内容 + 谓词 + 有限标签词表 + 服务端批准的检索扩展词。 */
        String indexedText(String predicateLabel) {
            return value + " " + predicate + " " + predicateLabel + " " + String.join(" ", keywords);
        }

        /** 判"predicate 命中"用的显式 key/标签：不含正文，所以单字查询不会撞上长句里的同一个字。 */
        String keyText(String predicateLabel) {
            return predicate + " " + predicateLabel;
        }
    }

    /** 一次命中：BM25 分数 + §9.1 第 4 条的三个稳定排序层标记。 */
    public record Hit(Doc doc, double score, boolean exactPhrase, boolean predicateMatch, boolean sameScope) {
    }

    /**
     * @param topical    主题事实区（≤3 条，已按 §9.1 第 4 条分层排序）
     * @param style      回答风格偏好区（≤2 条）
     * @param scanned    作用域/状态/抑制过滤后参与打分的文档数
     * @param candidates 通过相关性下限的候选数；为 0 就是 §9.1 第 6 条要如实记录的"无相关候选"
     * @param timedOut   超过 deadline：不带任何事实，调用方按"本轮不使用记忆"降级
     */
    public record Result(List<Hit> topical, List<Hit> style, int scanned, int candidates, boolean timedOut) {

        public List<Doc> injectedFacts() {
            List<Doc> docs = new ArrayList<>();
            topical.forEach(hit -> docs.add(hit.doc()));
            style.forEach(hit -> docs.add(hit.doc()));
            return docs;
        }
    }

    /** term → (文档下标 → 词频)；文档下标而不是 memoryId，省掉检索期的字符串哈希查找。 */
    private final Map<String, Map<Integer, Integer>> postings;
    private final List<Doc> docs;
    private final int[] documentLengths;
    private final List<Set<String>> keyTokens;
    private final List<String> normalizedValues;
    /**
     * scope key → 该作用域下的偏好事实。存整条 {@link Doc} 而不是 predicate 集合，是因为"笔记本偏好覆盖全局"
     * 只能由<b>当前还没过期</b>的那条偏好生效：快照是按写入时的版本建的，到期与否要看查询时钟（M10）。
     */
    private final Map<String, List<Doc>> preferenceDocsByScope;
    private final double averageLength;

    private MemorySearchIndex(List<Doc> docs, Map<String, Map<Integer, Integer>> postings, int[] documentLengths,
                              List<Set<String>> keyTokens, List<String> normalizedValues,
                              Map<String, List<Doc>> preferenceDocsByScope, double averageLength) {
        this.docs = docs;
        this.postings = postings;
        this.documentLengths = documentLengths;
        this.keyTokens = List.copyOf(keyTokens);
        this.normalizedValues = List.copyOf(normalizedValues);
        this.preferenceDocsByScope = preferenceDocsByScope;
        this.averageLength = averageLength;
    }

    /** 建一份不可变快照：调用方只传已解密、状态合法（ACTIVE）的事实。 */
    public static MemorySearchIndex build(List<Doc> facts) {
        List<Doc> docs = new ArrayList<>(facts);
        // 固定顺序：同一批事实无论入库/查询顺序如何，快照结构与破平局结果都一致
        docs.sort(Comparator.comparing(Doc::scopeKey).thenComparing(Doc::memoryId).thenComparingInt(Doc::version));
        Map<String, Map<Integer, Integer>> postings = new HashMap<>();
        int[] documentLengths = new int[docs.size()];
        List<Set<String>> keyTokens = new ArrayList<>();
        List<String> normalizedValues = new ArrayList<>();
        Map<String, List<Doc>> preferenceDocsByScope = new HashMap<>();
        long totalLength = 0;
        for (int position = 0; position < docs.size(); position++) {
            final int documentPosition = position;
            Doc doc = docs.get(position);
            String label = MemoryLexicon.predicateLabel(doc.predicate());
            List<String> terms = MemoryLexicon.tokens(doc.indexedText(label));
            Map<String, Integer> termFrequency = new HashMap<>();
            for (String term : terms) {
                termFrequency.merge(term, 1, Integer::sum);
            }
            termFrequency.forEach((term, frequency) -> postings
                    .computeIfAbsent(term, key -> new HashMap<>()).put(documentPosition, frequency));
            documentLengths[position] = terms.size();
            totalLength += terms.size();
            keyTokens.add(new HashSet<>(MemoryLexicon.tokens(doc.keyText(label))));
            normalizedValues.add(MemoryLexicon.normalize(doc.value()));
            if ("PREFERENCE".equals(doc.kind()) && doc.predicate() != null && !doc.predicate().isBlank()) {
                preferenceDocsByScope.computeIfAbsent(doc.scopeKey(), key -> new ArrayList<>()).add(doc);
            }
        }
        double average = docs.isEmpty() ? 1 : totalLength / (double) docs.size();
        return new MemorySearchIndex(List.copyOf(docs), postings, documentLengths, keyTokens, normalizedValues,
                freezePreferences(preferenceDocsByScope), average);
    }

    private static Map<String, List<Doc>> freezePreferences(Map<String, List<Doc>> source) {
        Map<String, List<Doc>> copy = new LinkedHashMap<>();
        source.forEach((key, value) -> copy.put(key, List.copyOf(value)));
        return Collections.unmodifiableMap(copy);
    }

    public int size() {
        return docs.size();
    }

    /**
     * 一次检索：作用域/有效期/抑制过滤 → posting 驱动 BM25 → 相关性下限 → 分层排序 → 家族去重与注入上限。
     *
     * @param limit          主题事实请求数；再大也只取 {@link #TOPICAL_LIMIT} 条（§9.1 第 5 条）
     * @param eligible       打分前就要生效的额外过滤（冲突抑制等），否则被抑制的事实会白占名额
     * @param deadlineMillis 整次检索总时限（§9.1 第 7 条），超时返回空结果 + {@code timedOut}
     */
    public Result search(String query, String scopeType, long scopeId, long nowMillis, int limit, long deadlineMillis,
                         Predicate<Doc> eligible) {
        long deadlineNanos = System.nanoTime() + deadlineMillis * 1_000_000L;
        List<Set<String>> queryConcepts = MemoryLexicon.concepts(query);
        Set<String> queryTokens = new LinkedHashSet<>(MemoryLexicon.tokens(query));
        if (queryTokens.isEmpty()) {
            return new Result(List.of(), List.of(), 0, 0, false);
        }
        if (System.nanoTime() > deadlineNanos) {
            // 预算为 0 就是"没时间了"，不能反过来给自己续一分钟再继续扫库（§9.1-7）
            return new Result(List.of(), List.of(), 0, 0, true);
        }
        String scopeKey = scopeType + "|" + scopeId;
        String normalizedQuery = MemoryLexicon.normalize(query);
        Set<String> overridden = overriddenPredicates(scopeKey, scopeType, nowMillis, eligible);
        boolean[] readable = new boolean[docs.size()];
        int corpus = 0;
        for (int position = 0; position < docs.size(); position++) {
            if (readable(docs.get(position), scopeType, scopeKey, nowMillis, overridden, eligible)) {
                readable[position] = true;
                corpus++;
            }
        }
        if (corpus == 0) {
            return new Result(List.of(), List.of(), 0, 0, false);
        }
        double generalCutoff = corpus >= GENERAL_TERM_MIN_CORPUS ? corpus * MAX_DOC_FREQUENCY_RATIO : Double.MAX_VALUE;

        double[] scores = new double[docs.size()];
        int[] matchedTerms = new int[docs.size()];
        int[] conceptMask = new int[docs.size()];
        for (int concept = 0; concept < queryConcepts.size(); concept++) {
            // 每个概念一个位；超过 int 位数的超长查询共用最后一位（只会把命中数算少，不会算多）
            int bit = 1 << Math.min(concept, Integer.SIZE - 2);
            for (String term : queryConcepts.get(concept)) {
                Map<Integer, Integer> termPostings = postings.get(term);
                if (termPostings == null) {
                    continue;
                }
                // df 只数"本次作用域内"的命中：跨笔记本的 posting 不能替本作用域算 idf
                List<Integer> positions = new ArrayList<>();
                for (Integer position : termPostings.keySet()) {
                    if (readable[position]) {
                        positions.add(position);
                    }
                }
                if (positions.isEmpty() || positions.size() > generalCutoff) {
                    continue;
                }
                double idf = Math.log(1 + (corpus - positions.size() + 0.5) / (positions.size() + 0.5));
                for (int position : positions) {
                    scores[position] += idf * termContribution(termPostings.get(position),
                            documentLengths[position]);
                    matchedTerms[position]++;
                    conceptMask[position] |= bit;
                }
            }
            if (System.nanoTime() > deadlineNanos) {
                return new Result(List.of(), List.of(), corpus, 0, true);
            }
        }

        List<Hit> scored = new ArrayList<>();
        for (int position = 0; position < docs.size(); position++) {
            if (!readable[position] || matchedTerms[position] == 0) {
                continue;
            }
            if ((position & 63) == 0 && System.nanoTime() > deadlineNanos) {
                return new Result(List.of(), List.of(), corpus, 0, true);
            }
            Doc doc = docs.get(position);
            String value = normalizedValues.get(position);
            boolean exactPhrase = !value.isEmpty()
                    && (value.contains(normalizedQuery) || normalizedQuery.contains(value));
            boolean predicateMatch = false;
            for (String token : keyTokens.get(position)) {
                if (queryTokens.contains(token)) {
                    predicateMatch = true;
                    break;
                }
            }
            if (!passesRelevanceFloor(matchedTerms[position], Integer.bitCount(conceptMask[position]),
                    queryConcepts.size(), exactPhrase, predicateMatch)) {
                continue;
            }
            scored.add(new Hit(doc, scores[position], exactPhrase, predicateMatch, scopeKey.equals(doc.scopeKey())));
        }
        if (scored.size() > CANDIDATE_PRUNE_LIMIT) {
            scored.sort(Comparator.comparingDouble(Hit::score).reversed());
            scored = new ArrayList<>(scored.subList(0, CANDIDATE_PRUNE_LIMIT));
        }
        scored.sort(rankComparator());
        List<Hit> candidates = scored.subList(0, Math.min(CANDIDATE_LIMIT, scored.size()));

        List<Hit> topical = new ArrayList<>();
        List<Hit> style = new ArrayList<>();
        Set<String> seenFamilies = new HashSet<>();
        int topicalLimit = Math.min(TOPICAL_LIMIT, Math.max(1, limit));
        for (Hit hit : candidates) {
            if (!seenFamilies.add(familyKey(hit.doc()))) {
                continue;
            }
            if (hit.doc().style()) {
                if (style.size() < STYLE_LIMIT) {
                    style.add(hit);
                }
            } else if (topical.size() < topicalLimit) {
                topical.add(hit);
            }
        }
        return new Result(List.copyOf(topical), List.copyOf(style), corpus, scored.size(), false);
    }

    /**
     * 本笔记本作用域内<b>当前仍未过期、且本轮真的可以注入</b>的偏好谓词：只有它们能盖住全局同谓词偏好。
     * 笔记本那条一旦到期就不该继续遮蔽全局（P5 外审发现 2）；同理，被 FAMILY 抑制按住的偏好本轮根本进不来，
     * 也不能替全局做主——否则"遗忘这条笔记本偏好"会变成"这个笔记本连全局偏好也没有"。
     */
    private Set<String> overriddenPredicates(String scopeKey, String scopeType, long nowMillis,
                                             Predicate<Doc> eligible) {
        if (!"NOTEBOOK".equals(scopeType)) {
            return Set.of();
        }
        List<Doc> preferences = preferenceDocsByScope.get(scopeKey);
        if (preferences == null) {
            return Set.of();
        }
        Set<String> live = new HashSet<>();
        for (Doc doc : preferences) {
            if (doc.validUntilEpochMilli() <= nowMillis) {
                continue;
            }
            if (eligible != null && !eligible.test(doc)) {
                continue;
            }
            live.add(doc.predicate());
        }
        return live;
    }

    /**
     * 作用域与状态过滤（M08/M10）：NOTEBOOK 读自身 + GLOBAL，GLOBAL 会话只读 GLOBAL；
     * 本笔记本仍有效的同 predicate 偏好存在时，全局那条不再入选（§9.1 第 5 条）。
     */
    private boolean readable(Doc doc, String scopeType, String scopeKey, long nowMillis,
                             Set<String> overridden, Predicate<Doc> eligible) {
        if (doc.validUntilEpochMilli() <= nowMillis) {
            return false;
        }
        boolean globalFallback = "NOTEBOOK".equals(scopeType) && "GLOBAL".equals(doc.scopeType());
        if (!scopeKey.equals(doc.scopeKey()) && !globalFallback) {
            return false;
        }
        if (globalFallback && overridden.contains(doc.predicate())) {
            return false;
        }
        return eligible == null || eligible.test(doc);
    }

    /**
     * §9.1 第 6 条"不为了凑 3 条注入无关记忆"落成的三道门：
     * <ul>
     *   <li><b>精确短语</b>直接放行——整条事实原文出现在查询里（或反之）是最强证据，与重合比例无关；</li>
     *   <li><b>比例门</b>按{@code 概念}计算：命中的查询概念至少占查询概念数两成。不能按 n-gram 数算，
     *       七个汉字就有一十个 n-gram，那样等于要求用户复述原文；</li>
     *   <li><b>绝对门</b>（至少两个 n-gram 重合）可以由 predicate 标签豁免——这就是 §9.1 第 4 条把
     *       "匹配 predicate" 单列一层的原因，也是两三个汉字的短查询唯一能召回的路径。
     *       标签是有限词表，所以豁免只放到概念比例这一层，不放行零重合。</li>
     * </ul>
     */
    static boolean passesRelevanceFloor(int matchedTerms, int matchedConcepts, int queryConceptCount,
                                        boolean exactPhrase, boolean predicateMatch) {
        if (exactPhrase) {
            return true;
        }
        if (matchedConcepts < Math.ceil(queryConceptCount * MIN_MATCH_RATIO)) {
            return false;
        }
        return predicateMatch || matchedTerms >= MIN_MATCHED_TERMS;
    }

    /**
     * 家族身份直接用表里的 {@code family_key_hmac}（contracts §1：单值 HMAC(scope+subject+predicate)，
     * 多值含规范化 value），不在检索侧另发明一套口径——否则两个并存学习目标会被这里错误合并（M13）。
     */
    private static String familyKey(Doc doc) {
        return doc.scopeKey() + "|" + doc.familyKeyHmac();
    }

    private static Comparator<Hit> rankComparator() {
        // §9.1 第 4 条：精确短语 → predicate 命中 → 同 scope → BM25 降序 → confirmedAt → id 破平局
        Comparator<Hit> byExactPhrase = (left, right) -> Boolean.compare(right.exactPhrase(), left.exactPhrase());
        Comparator<Hit> byPredicate = (left, right) -> Boolean.compare(right.predicateMatch(), left.predicateMatch());
        Comparator<Hit> byScope = (left, right) -> Boolean.compare(right.sameScope(), left.sameScope());
        Comparator<Hit> byScore = Comparator.comparingDouble(Hit::score).reversed();
        Comparator<Hit> byConfirmedAt = Comparator.comparingLong((Hit hit) -> hit.doc().confirmedAtEpochMilli())
                .reversed();
        Comparator<Hit> byMemoryId = Comparator.comparing((Hit hit) -> hit.doc().memoryId()).reversed();
        return byExactPhrase.thenComparing(byPredicate).thenComparing(byScope).thenComparing(byScore)
                .thenComparing(byConfirmedAt).thenComparing(byMemoryId);
    }

    /** BM25 的词内部分；idf 按查询词各算一次，所以这里只含 tf 归一。 */
    private double termContribution(int termFrequency, int documentLength) {
        return termFrequency * (BM25_K1 + 1)
                / (termFrequency + BM25_K1 * (1 - BM25_B + BM25_B * documentLength / averageLength));
    }
}
