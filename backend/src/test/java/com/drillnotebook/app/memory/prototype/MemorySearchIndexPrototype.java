package com.drillnotebook.app.memory.prototype;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * P0a 第①组原型（执行方案 §9.1）：无远程调用的进程内词法检索。
 * 验证两件事：中文短事实靠"二元组 + 三元组 + Latin 词"能不能召回；2,000 条 ACTIVE 下 warm 延迟
 * 能不能压进 p95 &lt; 50ms、单次 deadline 100ms。
 *
 * <p>只存在于测试作用域，正式实现属于 P5。规则严格照 §9.1：NFKC + Latin 小写；汉字连续段生成
 * 相邻二元组和三元组；Latin/数字按词切分；固定小停用词表；BM25 k1=1.2 b=0.75 取前 30 候选；
 * 排序层依次为精确短语、predicate、同 scope，再按 BM25 降序，最后 confirmedAt/id 破平局；
 * 关键词零重合的主题事实不进候选；注入上限 3 条主题事实 + 2 条回答风格偏好。</p>
 */
public final class MemorySearchIndexPrototype {

    public static final double BM25_K1 = 1.2;
    public static final double BM25_B = 0.75;
    public static final int CANDIDATE_LIMIT = 30;
    public static final int TOPIC_FACT_LIMIT = 3;
    public static final int STYLE_PREFERENCE_LIMIT = 2;
    public static final long DEFAULT_DEADLINE_NANOS = 100_000_000L;
    /** 相关性下限：主题事实至少要命中两个查询词。 */
    public static final int MIN_MATCHED_TERMS = 2;
    /** 相关性下限：命中词数至少占查询词数的两成。 */
    public static final double MIN_MATCH_RATIO = 0.2;
    /**
     * 泛词上限：出现在四成以上事实里的查询词直接跳过。P0a 实测必要——2,000 条库里
     * "笔记/复习"这类 bigram 的 posting 列表近乎全库，不截断时 p95 会被拖到几百毫秒。
     */
    public static final double MAX_DOC_FREQUENCY_RATIO = 0.4;
    /** 候选截断：按 BM25 先取前 N 条，再套用分层排序。 */
    public static final int CANDIDATE_PRUNE_LIMIT = 200;

    private static final Set<String> STOPWORDS = Set.of("的", "了", "是", "请", "帮我", "一下", "吧", "呢", "吗", "and",
            "the", "a", "an", "to", "of", "me", "my", "please");

    public MemorySearchIndexPrototype() {
    }

    public enum Kind {
        TOPIC, STYLE
    }

    /**
     * @param predicateLabel 有限词表的中文标签词（如"简短 详细 啰嗦"）。P0a 实测：只用英文
     *                       predicate 名时中文查询零命中，标签词表是中文词法召回的必要条件。
     */
    public record Fact(long id, String subject, String predicate, String predicateLabel, String value, String scope,
            String status, long validUntilEpochDay, long confirmedAtEpochDay, Kind kind) {
    }

    public record Hit(Fact fact, double score, boolean exactPhrase, boolean predicateMatch, boolean scopeMatch) {
    }

    public record SearchResult(List<Hit> topicHits, List<Hit> styleHits, long scannedFacts, boolean timedOut,
            long elapsedNanos) {

        public List<Fact> injectedFacts() {
            List<Fact> facts = new ArrayList<>();
            topicHits.forEach(hit -> facts.add(hit.fact()));
            styleHits.forEach(hit -> facts.add(hit.fact()));
            return facts;
        }
    }

    private static final class Posting {
        int termFrequency;
    }

    private final List<Fact> facts = new ArrayList<>();
    private final Map<Long, Fact> factsById = new HashMap<>();
    private final Map<Long, String> familyKeys = new HashMap<>();
    private final Map<Long, Integer> docLengths = new HashMap<>();
    private final Map<String, Map<Long, Posting>> index = new HashMap<>();
    private final Map<String, Integer> documentFrequency = new HashMap<>();
    private final Map<Long, String> normalizedValues = new HashMap<>();
    private final Map<Long, Set<String>> predicateTokens = new HashMap<>();
    private double averageLength = 1;
    private long totalLength;

    public static String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        return Normalizer.normalize(raw, Normalizer.Form.NFKC).toLowerCase(java.util.Locale.ROOT);
    }

    public static boolean isHan(int codePoint) {
        return Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN;
    }

    /** 汉字连续段 → 相邻二元组 + 三元组；Latin/数字 → 词；停用词过滤。 */
    public static List<String> tokens(String raw) {
        String normalized = normalize(raw);
        List<String> tokens = new ArrayList<>();
        StringBuilder latin = new StringBuilder();
        List<Integer> hanRun = new ArrayList<>();
        for (int offset = 0; offset < normalized.length();) {
            int codePoint = normalized.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (isHan(codePoint)) {
                flushLatin(latin, tokens);
                hanRun.add(codePoint);
                continue;
            }
            flushHanRun(hanRun, tokens);
            if (Character.isLetterOrDigit(codePoint)) {
                latin.appendCodePoint(codePoint);
            } else {
                flushLatin(latin, tokens);
            }
        }
        flushLatin(latin, tokens);
        flushHanRun(hanRun, tokens);
        tokens.removeIf(STOPWORDS::contains);
        return tokens;
    }

    private static void flushLatin(StringBuilder latin, List<String> tokens) {
        if (latin.length() > 0) {
            String word = latin.toString();
            latin.setLength(0);
            if (!STOPWORDS.contains(word)) {
                tokens.add(word);
            }
        }
    }

    private static void flushHanRun(List<Integer> run, List<String> tokens) {
        if (run.isEmpty()) {
            return;
        }
        for (int index = 0; index + 1 < run.size(); index++) {
            tokens.add(new String(new int[] { run.get(index), run.get(index + 1) }, 0, 2));
        }
        for (int index = 0; index + 2 < run.size(); index++) {
            tokens.add(new String(new int[] { run.get(index), run.get(index + 1), run.get(index + 2) }, 0, 3));
        }
        if (run.size() == 1) {
            // 单字查询不跑全库模糊匹配，只作为显式 token 命中
            tokens.add(new String(new int[] { run.get(0) }, 0, 1));
        }
        run.clear();
    }

    public void add(Fact fact) {
        facts.add(fact);
        factsById.put(fact.id(), fact);
        String value = normalize(fact.value());
        normalizedValues.put(fact.id(), value);
        familyKeys.put(fact.id(), familyKey(fact, value));
        predicateTokens.put(fact.id(), new HashSet<>(tokens(fact.predicateLabel() + " " + fact.predicate())));
        Map<String, Posting> touched = new HashMap<>();
        List<String> terms = tokens(fact.value() + " " + fact.subject() + " " + fact.predicateLabel());
        for (String term : terms) {
            Posting posting = index.computeIfAbsent(term, key -> new HashMap<>())
                    .computeIfAbsent(fact.id(), key -> new Posting());
            posting.termFrequency++;
            touched.put(term, posting);
        }
        for (String term : touched.keySet()) {
            documentFrequency.merge(term, 1, Integer::sum);
        }
        docLengths.put(fact.id(), terms.size());
        totalLength += terms.size();
        averageLength = totalLength / (double) facts.size();
    }

    public int size() {
        return facts.size();
    }

    /**
     * 一次检索：状态/有效期/scope 过滤 → BM25 打分取前 30 → 排序分层 → 去重与注入上限。
     * 超时返回 timedOut=true 且结果为空，由调用方降级为"本轮不使用记忆"。
     */
    public SearchResult search(String query, String scope, long todayEpochDay, int limit, long deadlineNanos) {
        long started = System.nanoTime();
        Set<String> queryTokens = new LinkedHashSet<>(tokens(query));
        if (queryTokens.isEmpty()) {
            return new SearchResult(List.of(), List.of(), facts.size(), false, System.nanoTime() - started);
        }
        String normalizedQuery = normalize(query);

        // posting 驱动打分：代价随命中文档数增长，而不是随全库条数 × 查询词数增长（P0a 实测的关键修正）
        Map<Long, Accumulator> hits = new HashMap<>();
        int skippedGeneralTerms = 0;
        double generalCutoff = facts.size() * MAX_DOC_FREQUENCY_RATIO;
        for (String term : queryTokens) {
            Map<Long, Posting> postings = index.get(term);
            if (postings == null) {
                continue;
            }
            if (postings.size() > generalCutoff) {
                skippedGeneralTerms++;
                continue;
            }
            double idf = Math.log(1 + (facts.size() - postings.size() + 0.5) / (postings.size() + 0.5));
            for (Map.Entry<Long, Posting> entry : postings.entrySet()) {
                Accumulator accumulator = hits.computeIfAbsent(entry.getKey(), key -> new Accumulator());
                accumulator.score += idf * termContribution(entry.getValue().termFrequency,
                        docLengths.getOrDefault(entry.getKey(), 1));
                accumulator.matchedTerms++;
            }
            if (System.nanoTime() - started > deadlineNanos) {
                return new SearchResult(List.of(), List.of(), facts.size(), true, System.nanoTime() - started);
            }
        }

        List<Hit> scored = new ArrayList<>();
        for (Map.Entry<Long, Accumulator> entry : hits.entrySet()) {
            if ((entry.getValue().matchedTerms & 63) == 0 && System.nanoTime() - started > deadlineNanos) {
                return new SearchResult(List.of(), List.of(), facts.size(), true, System.nanoTime() - started);
            }
            Fact fact = factsById.get(entry.getKey());
            if (fact == null || !"ACTIVE".equals(fact.status()) || fact.validUntilEpochDay() < todayEpochDay) {
                continue;
            }
            boolean sameScope = scope == null || fact.scope().equals(scope);
            if (!sameScope && !"GLOBAL".equals(fact.scope())) {
                continue;
            }
            String value = normalizedValues.getOrDefault(fact.id(), "");
            boolean exactPhrase = !value.isEmpty()
                    && (value.contains(normalizedQuery) || normalizedQuery.contains(value));
            boolean predicateHit = false;
            for (String token : predicateTokens.getOrDefault(fact.id(), Set.of())) {
                if (queryTokens.contains(token)) {
                    predicateHit = true;
                    break;
                }
            }
            if (!passesRelevanceFloor(entry.getValue().matchedTerms, queryTokens.size(), exactPhrase,
                    predicateHit)) {
                continue;
            }
            scored.add(new Hit(fact, entry.getValue().score, exactPhrase, predicateHit, sameScope));
        }
        // 先按 BM25 截断到有限候选，再套用分层排序：分层比较器只做有限次
        if (scored.size() > CANDIDATE_PRUNE_LIMIT) {
            scored.sort(Comparator.comparingDouble(Hit::score).reversed());
            scored = new ArrayList<>(scored.subList(0, CANDIDATE_PRUNE_LIMIT));
        }
        scored.sort(rankComparator());
        List<Hit> candidates = scored.subList(0, Math.min(CANDIDATE_LIMIT, scored.size()));
        List<Hit> topics = new ArrayList<>();
        List<Hit> styles = new ArrayList<>();
        Set<String> familySeen = new HashSet<>();
        for (Hit hit : candidates) {
            if (!familySeen.add(familyKeys.get(hit.fact().id()))) {
                continue;
            }
            if (hit.fact().kind() == Kind.STYLE) {
                if (styles.size() < STYLE_PREFERENCE_LIMIT) {
                    styles.add(hit);
                }
                continue;
            }
            if (topics.size() < Math.min(TOPIC_FACT_LIMIT, Math.max(1, limit))) {
                topics.add(hit);
            }
        }
        return new SearchResult(List.copyOf(topics), List.copyOf(styles), facts.size(), false,
                System.nanoTime() - started);
    }

    /**
     * §9.1 第 6 条"初版记录候选少/无相关，不为了凑 3 条注入无关记忆"落地成一个下限：
     * 至少命中两个查询词且占查询词两成，或由 predicate 标签/精确短语直接命中放行。
     */
    static boolean passesRelevanceFloor(int matchedTerms, int queryTokenCount, boolean exactPhrase,
            boolean predicateHit) {
        if (exactPhrase || predicateHit) {
            return true;
        }
        return matchedTerms >= MIN_MATCHED_TERMS
                && matchedTerms >= Math.ceil(queryTokenCount * MIN_MATCH_RATIO);
    }

    /**
     * §9.1 第 5 条与 contracts §1：单值 predicate（answer.*）同键只注入一条；
     * 多值 predicate（study.goal 等）按规范化 value 去重，两个并存目标都能进候选。
     */
    static String familyKey(Fact fact, String normalizedValue) {
        String predicate = fact.predicate();
        if (predicate.startsWith("answer.")) {
            return fact.scope() + "|" + predicate;
        }
        return fact.scope() + "|" + predicate + "|" + normalizedValue;
    }

    private Comparator<Hit> rankComparator() {
        // §9.1 第 4 条的排序层：精确短语 → predicate 命中 → 同 scope → BM25 降序 → confirmedAt → id
        Comparator<Hit> byExactPhrase = (left, right) -> Boolean.compare(right.exactPhrase(), left.exactPhrase());
        Comparator<Hit> byPredicate = (left, right) -> Boolean.compare(right.predicateMatch(), left.predicateMatch());
        Comparator<Hit> byScope = (left, right) -> Boolean.compare(right.scopeMatch(), left.scopeMatch());
        Comparator<Hit> byScore = Comparator.comparingDouble(Hit::score).reversed();
        Comparator<Hit> byConfirmedAt = Comparator.comparingLong((Hit hit) -> hit.fact().confirmedAtEpochDay()).reversed();
        Comparator<Hit> byId = Comparator.comparingLong((Hit hit) -> hit.fact().id()).reversed();
        return byExactPhrase.thenComparing(byPredicate).thenComparing(byScope).thenComparing(byScore)
                .thenComparing(byConfirmedAt).thenComparing(byId);
    }

    private static final class Accumulator {
        double score;
        int matchedTerms;
    }

    /** BM25 的词内部分（idf 在每个查询词上只算一次）。 */
    private double termContribution(int termFrequency, int documentLength) {
        return termFrequency * (BM25_K1 + 1)
                / (termFrequency + BM25_K1 * (1 - BM25_B + BM25_B * documentLength / averageLength));
    }
}
