package com.drillnotebook.app.service.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.drillnotebook.app.service.memory.MemorySearchIndex.Doc;
import com.drillnotebook.app.service.memory.MemorySearchIndex.Hit;
import com.drillnotebook.app.service.memory.MemorySearchIndex.Result;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * §9.1 的检索算法本身（执行方案 §14 P5）：BM25 小数据手算、rank ties 与中文短词规则、
 * 跨本隔离、无关 query 返回 0、2,000 条 warm 性能。全部直接跑生产类，不经过数据库。
 *
 * <p>手算用例固定用 Latin 文本：分词结果与文档长度可以直接数出来，公式才有"手算"的意义。
 * 中文那一路由开发集与短词规则用例覆盖。</p>
 */
class MemorySearchIndexTest {
    static final long SEED = 20261008L;
    static final int LIBRARY_SIZE = 2000;
    static final long NOW = Instant.parse("2026-10-13T00:00:00Z").toEpochMilli();
    static final long DAY = 86_400_000L;
    static final long FOREVER = Long.MAX_VALUE;
    static final String GLOBAL = "GLOBAL";
    static final long NOTEBOOK_3 = 3L;
    static final long NOTEBOOK_4 = 4L;
    static final long DEADLINE_MILLIS = 100L;

    private static MemorySearchIndex library;

    record DevCase(String query, String scopeType, long scopeId, String goldId) {
    }

    // ---- fixture ----

    private static Doc doc(long id, String kind, String predicate, String value, String scopeType, long scopeId,
                           String family) {
        return doc(id, kind, predicate, value, scopeType, scopeId, family, FOREVER, NOW - 5 * DAY);
    }

    private static Doc doc(long id, String kind, String predicate, String value, String scopeType, long scopeId,
                           String family, long validUntil, long confirmedAt) {
        return new Doc(String.format("m%05d", id), 1, kind, predicate, value, scopeType, scopeId,
                "F:" + (family == null ? predicate : family), "P:" + predicate, validUntil, confirmedAt);
    }

    /** contracts §1 的 kind/predicate 配对：answer.* 才是 PREFERENCE（风格区），其余按谓词归属。 */
    private static String kindFor(String predicate) {
        if (predicate.startsWith("answer.")) {
            return "PREFERENCE";
        }
        return switch (predicate) {
            case "study.goal" -> "GOAL";
            case "study.background" -> "BACKGROUND";
            case "task.constraint" -> "TASK";
            default -> "BACKGROUND";
        };
    }

    private static MemorySearchIndex build(List<Doc> docs) {
        return MemorySearchIndex.build(docs);
    }

    private static Result search(MemorySearchIndex index, String query, String scopeType, long scopeId) {
        return index.search(query, scopeType, scopeId, NOW, MemorySearchIndex.TOPICAL_LIMIT, DEADLINE_MILLIS, null);
    }

    private static List<String> ids(List<Hit> hits) {
        return hits.stream().map(hit -> hit.doc().memoryId()).toList();
    }

    @BeforeAll
    static void buildLibrary() {
        List<Doc> docs = new ArrayList<>(goldDocs());
        docs.addAll(noiseDocs(LIBRARY_SIZE, SEED));
        library = build(docs);
    }

    /** 30 条 gold 事实（P0a 开发集原样搬过来）+ 3 条状态对照。 */
    static List<Doc> goldDocs() {
        List<Doc> docs = new ArrayList<>();
        Object[][] rows = {
                {1L, "answer.language", "以后都用英语回答", GLOBAL, 0L},
                {2L, "answer.detail", "解释请简短一些", GLOBAL, 0L},
                {3L, "answer.order", "先举例子再讲原理", GLOBAL, 0L},
                {4L, "answer.language", "这本笔记用中文回答", "NOTEBOOK", NOTEBOOK_3},
                {5L, "answer.language", "这本笔记用英文回答", "NOTEBOOK", NOTEBOOK_4},
                {6L, "study.goal", "正在准备雅思考试", GLOBAL, 0L},
                {7L, "study.goal", "线性代数要掌握特征值与相似对角化", "NOTEBOOK", NOTEBOOK_3},
                {8L, "study.background", "我是计算机专业大三学生", GLOBAL, 0L},
                {9L, "task.constraint", "每次只给三步以内的操作", GLOBAL, 0L},
                {10L, "other", "我习惯用键盘快捷键操作", GLOBAL, 0L},
                {11L, "study.goal", "下半年想学 Rust 的所有权模型", GLOBAL, 0L},
                {12L, "study.goal", "离散数学重点在图论", "NOTEBOOK", NOTEBOOK_4},
                {13L, "answer.detail", "公式推导要写完整步骤", "NOTEBOOK", NOTEBOOK_3},
                {14L, "study.background", "我在准备研究生入学考试的数学一", GLOBAL, 0L},
                {15L, "other", "每天学习时间不超过一小时", GLOBAL, 0L},
                {16L, "answer.order", "先看结论再看证明", "NOTEBOOK", NOTEBOOK_3},
                {17L, "answer.language", "中文回答里可以夹带英文术语", GLOBAL, 0L},
                {18L, "study.goal", "想在一个月内学完正则表达式", GLOBAL, 0L},
                {19L, "task.constraint", "不要用表格形式回答", GLOBAL, 0L},
                {20L, "answer.detail", "只要要点不要长段落", "NOTEBOOK", NOTEBOOK_4},
                {21L, "other", "我对音阶和和弦有基础", GLOBAL, 0L},
                {22L, "study.background", "做过三年 Java 后端开发", GLOBAL, 0L},
                {23L, "study.goal", "概率论重点复习大数定律", "NOTEBOOK", NOTEBOOK_3},
                {24L, "answer.order", "先给可运行代码再解释", "NOTEBOOK", NOTEBOOK_4},
                {25L, "study.goal", "本周把二叉树遍历刷完", GLOBAL, 0L},
                {26L, "study.goal", "操作系统重点在内存管理", "NOTEBOOK", NOTEBOOK_4},
                {27L, "other", "复习时喜欢用问答卡片", GLOBAL, 0L},
                {28L, "task.constraint", "解释时避免使用日语", GLOBAL, 0L},
                {29L, "answer.language", "这本笔记请中英混用", "NOTEBOOK", NOTEBOOK_3},
                {30L, "study.goal", "准备参加学校的算法竞赛", GLOBAL, 0L},
        };
        for (Object[] row : rows) {
            String predicate = (String) row[1];
            String value = (String) row[2];
            docs.add(doc((Long) row[0], kindFor(predicate), predicate, value, (String) row[3], (Long) row[4],
                    // contracts §1 的家族口径：单值 answer.* 同键一条，多值把规范化 value 算进家族
                    predicate.startsWith("answer.") ? predicate : predicate + "|" + value));
        }
        // 状态对照：已过有效期、停用（不入库）、被替换（不入库）都不该被注入
        docs.add(doc(41L, "GOAL", "study.goal", "想学钢琴", GLOBAL, 0L, null, NOW - DAY, NOW));
        return docs;
    }

    /** 30 条开发集：措辞刻意与原文不同，考察词法召回的真实上限（验收 §9.1.5）。 */
    static List<DevCase> devSet() {
        return List.of(
                new DevCase("以后回答都用英文好吗", GLOBAL, 0L, "m00001"),
                new DevCase("讲简单点，别太啰嗦", GLOBAL, 0L, "m00002"),
                new DevCase("能不能先给例子", GLOBAL, 0L, "m00003"),
                new DevCase("这本笔记要用中文还是英文", "NOTEBOOK", NOTEBOOK_3, "m00004"),
                new DevCase("用英文解释这本笔记", "NOTEBOOK", NOTEBOOK_4, "m00005"),
                new DevCase("我最近在备考什么", GLOBAL, 0L, "m00006"),
                new DevCase("线性代数要学什么", "NOTEBOOK", NOTEBOOK_3, "m00007"),
                new DevCase("介绍下我的背景", GLOBAL, 0L, "m00008"),
                new DevCase("操作别超过三步", GLOBAL, 0L, "m00009"),
                new DevCase("我习惯用键盘快捷键", GLOBAL, 0L, "m00010"),
                new DevCase("Rust 的所有权怎么学", GLOBAL, 0L, "m00011"),
                new DevCase("离散数学的重点是什么", "NOTEBOOK", NOTEBOOK_4, "m00012"),
                new DevCase("公式推导希望怎么写", "NOTEBOOK", NOTEBOOK_3, "m00013"),
                new DevCase("研究生入学考试数学一", GLOBAL, 0L, "m00014"),
                new DevCase("每天学习时间有多少", GLOBAL, 0L, "m00015"),
                new DevCase("先看结论还是先证明", "NOTEBOOK", NOTEBOOK_3, "m00016"),
                new DevCase("回答里可以带英文术语吗", GLOBAL, 0L, "m00017"),
                new DevCase("正则表达式打算多久学完", GLOBAL, 0L, "m00018"),
                new DevCase("别用表格回答", GLOBAL, 0L, "m00019"),
                new DevCase("只要要点可以吗", "NOTEBOOK", NOTEBOOK_4, "m00020"),
                new DevCase("音阶和和弦我有基础吗", GLOBAL, 0L, "m00021"),
                new DevCase("我做过多久后端", GLOBAL, 0L, "m00022"),
                new DevCase("概率论复习哪一章", "NOTEBOOK", NOTEBOOK_3, "m00023"),
                new DevCase("先给能跑的代码", "NOTEBOOK", NOTEBOOK_4, "m00024"),
                new DevCase("这周要刷完什么", GLOBAL, 0L, "m00025"),
                new DevCase("操作系统重点在哪", "NOTEBOOK", NOTEBOOK_4, "m00026"),
                new DevCase("复习喜欢用什么方式", GLOBAL, 0L, "m00027"),
                new DevCase("解释时能用日语吗", GLOBAL, 0L, "m00028"),
                new DevCase("中英混着说行吗", "NOTEBOOK", NOTEBOOK_3, "m00029"),
                new DevCase("算法竞赛", GLOBAL, 0L, "m00030"));
    }

    /** 合成噪声库：与开发集同域但不同事实，用来压出词法歧义与规模成本。 */
    static List<Doc> noiseDocs(int count, long seed) {
        Random random = new Random(seed);
        String[] subjects = {"高数笔记", "英语听力笔记", "结构力学笔记", "有机化学笔记", "世界史笔记", "微观经济学笔记",
                "电路原理笔记", "离散数学笔记", "操作系统笔记", "机器学习笔记"};
        String[] topics = {"极限与连续", "导数应用", "积分不等式", "听力速记", "桁架内力", "取代反应", "冷战起因",
                "边际效用", "基尔霍夫定律", "图的着色", "进程调度", "反向传播", "特征值", "傅里叶级数", "数列递推",
                "动量守恒", "遗传规律", "三元相图", "复变函数", "拉普拉斯变换"};
        String[] verbs = {"需要多做练习", "重点在于理解定义", "要先背公式", "常用错题本整理", "结合例题来看",
                "按章节复习", "每天回顾一遍", "先跳过难题", "用思维导图串联", "考前再刷一遍"};
        String[] predicates = {"study.goal", "study.background", "task.constraint", "other"};
        List<Doc> docs = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            long id = 101L + index;
            String subject = subjects[random.nextInt(subjects.length)];
            String topic = topics[random.nextInt(topics.length)];
            String verb = verbs[random.nextInt(verbs.length)];
            String predicate = predicates[random.nextInt(predicates.length)];
            boolean global = random.nextInt(4) == 0;
            long scopeId = global ? 0L : NOTEBOOK_3 + random.nextInt(3);
            docs.add(new Doc(String.format("m%05d", id), 1, kindFor(predicate), predicate,
                    subject + "的" + topic + verb, global ? GLOBAL : "NOTEBOOK", scopeId,
                    // 每条噪声事实自成家族：家族去重不得替 §9.1 的"多值并存"做主（M13）
                    "F" + id, "P" + predicate, FOREVER, NOW - random.nextInt(40) * DAY));
        }
        return docs;
    }

    // ---- 手算 BM25 ----

    @Test
    void bm25ScoreMatchesTheHandComputedStandardFormula() {
        // 三条等长文档、纯 Latin 词，文档长度与词频都能直接数出来：
        //   d1 = rust ownership book，d2 = rust traits impl，d3 = binary tree traversal
        // N=3，avgLen=3，k1=1.2、b=0.75 ⇒ tf=1、docLen=avgLen 的词内部分恒为 2.2/(1+1.2)=1
        // idf(rust)（df=2）= ln(1+(3-2+0.5)/(2+0.5)) = ln(8/5)
        // idf(book)（df=1）= ln(1+(3-1+0.5)/(1+0.5)) = ln(8/3)
        MemorySearchIndex index = build(List.of(
                doc(1L, "GOAL", "", "rust ownership book", GLOBAL, 0L, "g1"),
                doc(2L, "GOAL", "", "rust traits impl", GLOBAL, 0L, "g2"),
                doc(3L, "GOAL", "", "binary tree traversal", GLOBAL, 0L, "g3")));

        Result result = search(index, "rust book", GLOBAL, 0L);
        assertEquals(1, result.topical().size(), "d2 只命中一个查询词，相关性下限不该放行");
        assertEquals("m00001", result.topical().get(0).doc().memoryId());
        assertEquals(Math.log(8.0 / 5.0) + Math.log(8.0 / 3.0), result.topical().get(0).score(), 1e-9,
                "BM25 必须等于 k1=1.2、b=0.75 的标准公式手算值");
    }

    @Test
    void termFrequencySaturatesAndLongDocumentsArePenalised() {
        // A = "cat cat"（len 2，tf 2），B = "cat dog bird fish"（len 4，tf 1），avgLen = 3
        // A 的词内部分 = 2×2.2/(2+1.2×(0.25+0.75×2/3)) = 4.4/2.9
        // B 的词内部分 = 1×2.2/(1+1.2×(0.25+0.75×4/3)) = 2.2/2.5
        // 两者共享同一个 idf(cat) = ln(1+(2-2+0.5)/(2+0.5)) = ln(1.2)
        MemorySearchIndex index = build(List.of(
                doc(1L, "GOAL", "", "cat cat", GLOBAL, 0L, "g1"),
                doc(2L, "GOAL", "", "cat dog bird fish", GLOBAL, 0L, "g2")));

        Result result = search(index, "cat", GLOBAL, 0L);
        double expectedFirst = Math.log(1.2) * 4.4 / 2.9;
        double expectedSecond = Math.log(1.2) * 2.2 / 2.5;
        assertEquals("m00001", result.topical().get(0).doc().memoryId(), "同词频内重复要加权，但不能线性翻倍");
        assertEquals("m00002", result.topical().get(1).doc().memoryId());
        assertEquals(expectedFirst, result.topical().get(0).score(), 1e-9);
        assertEquals(expectedSecond, result.topical().get(1).score(), 1e-9);
        assertTrue(expectedFirst > expectedSecond, "词内饱和与长度归一必须让短文档占先");
    }

    // ---- rank ties 与分层 ----

    @Test
    void tiesBreakOnConfirmedAtThenMemoryIdAndStayDeterministic() {
        // 三条文档正文完全一样 ⇒ BM25 分数完全一样；排序层只剩 confirmedAt 与 memoryId
        Doc older = new Doc("m00001", 1, "GOAL", "", "同一句完全一样的话", GLOBAL, 0L, "F:a", "P:", FOREVER,
                NOW - 30 * DAY);
        Doc newer = new Doc("m00002", 1, "GOAL", "", "同一句完全一样的话", GLOBAL, 0L, "F:b", "P:", FOREVER,
                NOW - DAY);
        Doc sameTime = new Doc("m00003", 1, "GOAL", "", "同一句完全一样的话", GLOBAL, 0L, "F:c", "P:", FOREVER,
                NOW - DAY);
        MemorySearchIndex index = build(List.of(older, newer, sameTime));

        List<String> first = ids(search(index, "同一句完全一样的话", GLOBAL, 0L).topical());
        List<String> second = ids(search(index, "同一句完全一样的话", GLOBAL, 0L).topical());
        assertEquals(first, second, "同一份快照里两次检索的顺序必须完全一致");
        assertEquals("m00003", first.get(0), "confirmedAt 相同才轮到 memoryId 破平局，新的在前");
        assertEquals("m00002", first.get(1));
        assertEquals("m00001", first.get(2));
    }

    @Test
    void exactPhraseAndPredicateLabelRankAbovePlainBm25Hits() {
        List<Doc> docs = new ArrayList<>();
        // 精确短语：正文完整包含查询
        docs.add(doc(1L, "BACKGROUND", "other", "我在准备研究生入学考试的数学一", GLOBAL, 0L, null));
        // predicate 标签命中：查询"简短"只落在 answer.detail 的标签词表上
        docs.add(doc(2L, "PREFERENCE", "answer.detail", "解释请简短一些", GLOBAL, 0L, null));
        // 普通 BM25 命中：共享若干二元组但既非短语也非标签
        docs.add(doc(3L, "BACKGROUND", "study.background", "考研数学一真题已经刷完一半", GLOBAL, 0L, null));
        MemorySearchIndex index = build(docs);

        Result phrase = search(index, "我在准备研究生入学考试的数学一", GLOBAL, 0L);
        assertEquals("m00001", phrase.topical().get(0).doc().memoryId(), "精确短语必须排在纯词法命中之前");
        assertTrue(phrase.topical().get(0).exactPhrase());

        Result style = search(index, "简短", GLOBAL, 0L);
        assertEquals(List.of("m00002"), ids(style.style()), "单条中文短词靠 predicate 标签召回");
        assertTrue(style.style().get(0).predicateMatch());
    }

    @Test
    void sameScopeRanksAboveGlobalForTheSameQuery() {
        MemorySearchIndex index = build(List.of(
                doc(1L, "GOAL", "study.goal", "操作系统重点在内存管理", "NOTEBOOK", NOTEBOOK_4, null),
                doc(2L, "GOAL", "study.goal", "全局也有一句内存管理重点", GLOBAL, 0L, null)));

        Result inNotebook4 = search(index, "内存管理重点在哪", "NOTEBOOK", NOTEBOOK_4);
        assertEquals("m00001", inNotebook4.topical().get(0).doc().memoryId(), "同 scope 是 BM25 之前的一层");
        assertTrue(inNotebook4.topical().get(0).sameScope());
        assertFalse(inNotebook4.topical().get(1).sameScope());
    }

    // ---- 中文短词规则 ----

    @Test
    void singleCharacterQueryDoesNotScanTheWholeLibrary() {
        // §9.1 第 2 条：正文里的汉字连续段永远不会被切成单字 token，所以"卡"不得命中"问答卡片"
        Result result = search(library, "卡", GLOBAL, 0L);
        assertTrue(result.topical().isEmpty() && result.style().isEmpty(), "单字查询不得命中多字事实");
        assertEquals(0, result.candidates(), "单字查询零候选时要如实报空，而不是凑一条");
    }

    @Test
    void twoCharacterChineseQueryRecallsThroughThePredicateLabelTable() {
        // 只用英文 predicate 名时中文查询零命中（P0a 实测），标签词表是中文词法召回的必要条件
        Result result = search(library, "详细", GLOBAL, 0L);
        assertFalse(result.style().isEmpty(), "answer.detail 的中文标签必须能被两条汉字召回到");
        assertTrue(result.style().stream().anyMatch(hit -> hit.predicateMatch()), "命中的是标签词表而不是正文巧合");
    }

    @Test
    void unrelatedQueryReturnsNoCandidateInsteadOfPaddingThreeFacts() {
        Result result = search(library, "量子色动力学的格点规约怎么写", GLOBAL, 0L);
        assertTrue(result.topical().isEmpty() && result.style().isEmpty(),
                "零重合的主题查询不得凑数注入：" + result.injectedFacts());
        assertEquals(0, result.candidates(), "§9.1-6 要如实记录无相关候选");
    }

    @Test
    void aSuppressedNotebookPreferenceDoesNotHideTheGlobalOne() {
        MemorySearchIndex index = build(List.of(
                doc(1L, "PREFERENCE", "answer.detail", "以后所有解释都简短一些", GLOBAL, 0L, null),
                doc(2L, "PREFERENCE", "answer.detail", "这本笔记要详细展开推导", "NOTEBOOK", NOTEBOOK_3, null)));

        Result live = search(index, "解释要简短还是详细", "NOTEBOOK", NOTEBOOK_3);
        assertEquals(List.of("m00002"), ids(live.style()), "对照：笔记本偏好未被抑制时由它覆盖全局");
        Result suppressed = index.search("解释要简短还是详细", "NOTEBOOK", NOTEBOOK_3, NOW,
                MemorySearchIndex.TOPICAL_LIMIT, DEADLINE_MILLIS,
                doc -> !doc.scopeKey().equals("NOTEBOOK|" + NOTEBOOK_3));
        assertEquals(List.of("m00001"), ids(suppressed.style()),
                "被遗忘抑制按住的笔记本偏好本轮根本进不来，不能替全局做主（遗忘一条不该抹掉整条偏好）");
    }

    @Test
    void generalTermCapSkipsANearWholeLibraryTermAndOnlyThatTerm() {
        List<Doc> docs = new ArrayList<>();
        for (int index = 0; index < 30; index++) {
            docs.add(doc(200L + index, "GOAL", "study.goal", "按章节复习" + index, GLOBAL, 0L, "g" + index));
        }
        docs.add(doc(900L, "GOAL", "study.goal", "二叉树遍历专项突破", GLOBAL, 0L, "g900"));
        for (int index = 0; index < 9; index++) {
            docs.add(doc(300L + index, "GOAL", "study.goal", "动量守恒" + index, GLOBAL, 0L, "g3" + index));
        }
        MemorySearchIndex big = build(docs);

        // 40 条库已过 GENERAL_TERM_MIN_CORPUS：df(复习)=30 > 40% ⇒ 这一个词整条跳过，一条都不给
        assertEquals(0, big.search("复习", GLOBAL, 0L, NOW, MemorySearchIndex.TOPICAL_LIMIT, DEADLINE_MILLIS, null)
                .candidates(), "泛词必须被上限截掉，而不是靠标签命中铺满候选");
        // 同一批文档只取 20 条时上限不生效：小库没有扫描成本，截断只会改变结果（D44）
        MemorySearchIndex small = build(docs.subList(0, 20));
        assertTrue(small.search("复习", GLOBAL, 0L, NOW, MemorySearchIndex.TOPICAL_LIMIT, DEADLINE_MILLIS, null)
                        .candidates() > 0, "库小于 32 条时同一个词照常参与打分");
        // 稀有词仍然要把目标事实捞上来
        Result mixed = big.search("复习 二叉树 遍历 专项 突破", GLOBAL, 0L, NOW, MemorySearchIndex.TOPICAL_LIMIT,
                DEADLINE_MILLIS, null);
        assertTrue(mixed.topical().stream().anyMatch(hit -> hit.doc().memoryId().equals("m00900")),
                "泛词被跳过后，稀有的区分词仍要把目标事实捞上来");
    }

    // ---- 作用域、家族去重与上限 ----

    @Test
    void notebookFactsAreInvisibleToAnotherNotebookAndToGlobalSessions() {
        MemorySearchIndex index = build(List.of(
                doc(1L, "GOAL", "study.goal", "离散数学重点在图论", "NOTEBOOK", NOTEBOOK_3, null),
                doc(2L, "GOAL", "study.goal", "操作系统重点在内存管理", "NOTEBOOK", NOTEBOOK_4, null)));

        Result inNotebook4 = search(index, "离散数学重点在图论", "NOTEBOOK", NOTEBOOK_4);
        assertTrue(inNotebook4.injectedFacts().stream().noneMatch(doc -> doc.memoryId().equals("m00001")),
                "笔记本 4 看不见笔记本 3 的事实（M08）");
        Result inNotebook3 = search(index, "离散数学重点在图论", "NOTEBOOK", NOTEBOOK_3);
        assertEquals(List.of("m00001"), ids(inNotebook3.topical()), "自己的作用域照常命中");
        assertTrue(search(index, "离散数学重点在图论", GLOBAL, 0L).topical().isEmpty(),
                "全局会话不读任何笔记本作用域（M08）");
    }

    @Test
    void notebookPreferenceHidesTheGlobalOneWithTheSamePredicate() {
        MemorySearchIndex index = build(List.of(
                doc(1L, "PREFERENCE", "answer.language", "以后都用英语回答", GLOBAL, 0L, null),
                doc(2L, "PREFERENCE", "answer.language", "这本笔记用中文回答", "NOTEBOOK", NOTEBOOK_3, null)));

        Result inNotebook = search(index, "用英语回答", "NOTEBOOK", NOTEBOOK_3);
        assertEquals(List.of("m00002"), ids(inNotebook.style()), "笔记本偏好覆盖全局（§9.1-5），全局那条不进候选");
        assertEquals(List.of("m00001"), ids(search(index, "用英语回答", GLOBAL, 0L).style()),
                "全局会话没有笔记本偏好可覆盖，仍读全局");
    }

    @Test
    void suppressedFamilyLeavesTheSlotForTheNextCandidate() {
        List<Doc> docs = List.of(
                doc(1L, "GOAL", "study.goal", "正在准备雅思考试", GLOBAL, 0L, "goal-1"),
                doc(2L, "GOAL", "study.goal", "准备参加学校的算法竞赛", GLOBAL, 0L, "goal-2"),
                doc(3L, "GOAL", "study.goal", "想在一个月内学完正则表达式", GLOBAL, 0L, "goal-3"));
        MemorySearchIndex index = build(docs);
        // 抑制键在打分前生效：被遗忘的事实不能占住 3 条名额里的一格（§9.1-5）
        Result unrestricted = index.search("考试 目标 备考 竞赛", GLOBAL, 0L, NOW,
                MemorySearchIndex.TOPICAL_LIMIT, DEADLINE_MILLIS, null);
        Result suppressed = index.search("考试 目标 备考 竞赛", GLOBAL, 0L, NOW,
                MemorySearchIndex.TOPICAL_LIMIT, DEADLINE_MILLIS, doc -> !doc.familyKeyHmac().equals("F:goal-1"));
        assertFalse(unrestricted.injectedFacts().isEmpty(), "对照：不加抑制时至少有一条");
        assertTrue(unrestricted.injectedFacts().stream().anyMatch(doc -> doc.memoryId().equals("m00001")),
                "对照：不加抑制时这条本来会占掉一个名额");
        assertTrue(suppressed.injectedFacts().stream().noneMatch(doc -> doc.memoryId().equals("m00001")),
                "被抑制的家族不得再出现在注入结果里");
        assertFalse(suppressed.injectedFacts().isEmpty(), "名额要让给下一条候选，而不是空掉");
    }

    @Test
    void anExpiredNotebookPreferenceStopsHidingTheGlobalOne() {
        MemorySearchIndex index = build(List.of(
                doc(1L, "PREFERENCE", "answer.detail", "以后所有解释都简短一些", GLOBAL, 0L, null),
                new Doc("m00002", 1, "PREFERENCE", "answer.detail", "这本笔记要详细展开推导", "NOTEBOOK", NOTEBOOK_3,
                        "F:answer.detail", "P:answer.detail", NOW + DAY, NOW, List.of())));

        Result live = search(index, "解释要简短还是详细", "NOTEBOOK", NOTEBOOK_3);
        assertEquals(List.of("m00002"), ids(live.style()), "有效期内由笔记本偏好覆盖全局");
        Result afterExpiry = index.search("解释要简短还是详细", "NOTEBOOK", NOTEBOOK_3, NOW + 2 * DAY,
                MemorySearchIndex.TOPICAL_LIMIT, DEADLINE_MILLIS, null);
        assertEquals(List.of("m00001"), ids(afterExpiry.style()),
                "笔记本偏好一到期就必须把全局那条让回来，否则这个笔记本两条都拿不到");
    }

    @Test
    void sameFamilyCollapsesToOneFactWhileDistinctFamiliesCoexist() {
        List<Doc> docs = new ArrayList<>();
        docs.add(doc(1L, "PREFERENCE", "answer.detail", "解释请简短一些", GLOBAL, 0L, "answer.detail"));
        docs.add(doc(2L, "PREFERENCE", "answer.detail", "回答要详细展开", GLOBAL, 0L, "answer.detail"));
        docs.add(doc(3L, "GOAL", "study.goal", "正在准备雅思考试", GLOBAL, 0L, "goal-雅思"));
        docs.add(doc(4L, "GOAL", "study.goal", "同时在准备托福考试", GLOBAL, 0L, "goal-托福"));
        MemorySearchIndex index = build(docs);

        Result styles = search(index, "解释要简短还是详细", GLOBAL, 0L);
        assertEquals(1, styles.style().size(), "单值 predicate 同族只注入一条（contracts §1）");

        Result goals = search(index, "我的考试目标雅思和托福都要提", GLOBAL, 0L);
        List<String> injected = ids(goals.topical());
        assertTrue(injected.contains("m00003") && injected.contains("m00004"),
                "两个并存学习目标都能进候选，多值谓词不能当单值合并（M13）：" + injected);
    }

    @Test
    void injectionNeverExceedsThreeTopicalFactsPlusTwoStylePreferences() {
        Result result = search(library, "复习 目标 章节 练习 笔记 整理 每天回顾", GLOBAL, 0L);
        assertTrue(result.topical().size() <= MemorySearchIndex.TOPICAL_LIMIT, "主题事实最多 3 条");
        assertTrue(result.style().size() <= MemorySearchIndex.STYLE_LIMIT, "风格偏好最多 2 条");
        assertEquals(MemorySearchIndex.TOPICAL_LIMIT, result.topical().size(), "候选充足时确实取满 3 条");
    }

    @Test
    void expiredFactsDropOutWithoutAnyCleanupJob() {
        Result result = search(library, "想学钢琴的目标还在吗", GLOBAL, 0L);
        assertTrue(result.injectedFacts().stream().noneMatch(doc -> doc.memoryId().equals("m00041")),
                "有效期限一过立刻不注入，不等清理任务（M10）");
    }

    @Test
    void deadlineOverrunDegradesToNoFactsAndFlagsTimeout() {
        Result result = library.search("我最近在备考什么", GLOBAL, 0L, NOW, MemorySearchIndex.TOPICAL_LIMIT, 0L, null);
        assertTrue(result.timedOut(), "极小 deadline 必须触发超时降级");
        assertTrue(result.topical().isEmpty() && result.style().isEmpty(), "超时后不得半投记忆");
    }

    // ---- §14 P5 门禁：2,000 条规模与开发集召回 ----

    @Test
    void goldFactsExistBeforeMeasuringRecall() {
        Set<String> goldIds = goldDocs().stream().map(Doc::memoryId).collect(Collectors.toCollection(TreeSet::new));
        Set<String> referenced = new TreeSet<>();
        for (DevCase testCase : devSet()) {
            assertTrue(goldIds.contains(testCase.goldId()), "开发集引用了不存在的 gold 事实: " + testCase);
            referenced.add(testCase.goldId());
        }
        assertEquals(30, referenced.size(), "开发集必须是 30 条不同事实");
        assertEquals(LIBRARY_SIZE + goldDocs().size(), library.size());
    }

    @Test
    void recallOnThirtyCaseChineseDevSetStaysAtTheP0aLevel() {
        int hits = 0;
        List<String> misses = new ArrayList<>();
        for (DevCase testCase : devSet()) {
            Result result = search(library, testCase.query(), testCase.scopeType(), testCase.scopeId());
            if (result.injectedFacts().stream().anyMatch(doc -> doc.memoryId().equals(testCase.goldId()))) {
                hits++;
            } else {
                misses.add(testCase.query() + " -> 期望 " + testCase.goldId() + "，候选 " + result.candidates()
                        + "，实际 " + result.injectedFacts().stream().map(Doc::memoryId).toList());
            }
        }
        System.out.println("[P5] recall@3 = " + hits + "/" + devSet().size() + " (seed=" + SEED
                + ", library=" + library.size() + ")");
        misses.forEach(miss -> System.out.println("[P5][miss] " + miss));
        assertTrue(hits >= 27, "30 条开发集召回必须 >= 27（P0a 实测 29/30），实际 " + hits + "；未命中：" + misses);
    }

    @Test
    void warmSearchUnderTwoThousandFactsStaysBelowFiftyMillisP95() {
        List<String> queries = devSet().stream().map(DevCase::query).toList();
        for (int index = 0; index < 50; index++) {
            DevCase testCase = devSet().get(index % devSet().size());
            search(library, testCase.query(), testCase.scopeType(), testCase.scopeId());
        }
        List<Long> samples = new ArrayList<>();
        for (int index = 0; index < 200; index++) {
            DevCase testCase = devSet().get(index % devSet().size());
            long started = System.nanoTime();
            search(library, testCase.query(), testCase.scopeType(), testCase.scopeId());
            samples.add(System.nanoTime() - started);
        }
        samples.sort(Comparator.naturalOrder());
        double p50 = samples.get(samples.size() / 2) / 1_000_000.0;
        double p95 = samples.get((int) (samples.size() * 0.95)) / 1_000_000.0;
        double p99 = samples.get((int) (samples.size() * 0.99)) / 1_000_000.0;
        System.out.println("[P5] warm latency library=" + library.size() + " p50=" + p50 + "ms p95=" + p95
                + "ms p99=" + p99 + "ms vm=" + Runtime.version());
        assertTrue(p95 < 50.0, "§9.1-7 门禁：2,000 条下 warm p95 必须 < 50ms，实际 " + p95 + "ms");
        assertTrue(p99 < DEADLINE_MILLIS, "p99 必须落在 100ms 单次 deadline 内，实际 " + p99 + "ms");
    }

    // ---- 分词口径 ----

    @Test
    void tokenizerProducesBigramsTrigramsAndLatinWords() {
        List<String> tokens = MemoryLexicon.tokens("解释请简短一些，使用 Rust 2024");
        assertTrue(tokens.contains("解释"));
        assertTrue(tokens.contains("简短"));
        assertTrue(tokens.contains("释请简"), "三元组也要生成，否则长句里的短词召不回");
        assertTrue(tokens.contains("rust"));
        assertTrue(tokens.contains("2024"));
        assertFalse(tokens.contains("的"), "停用词必须过滤");
        assertEquals(MemoryLexicon.tokens("Ｒｕｓｔ"), MemoryLexicon.tokens("rust"), "NFKC 让全角与半角同 token");
    }

    @Test
    void approvedKeywordExpansionsParticipateInLexicalRecall() {
        // contracts §5 的 keywords[]：服务端模板生成的扩展词参与词法召回，但仍然要过相关性下限
        Doc withKeywords = new Doc("m00101", 1, "BACKGROUND", "study.background", "做过三年 Java 后端开发",
                GLOBAL, 0L, "F:study.background|java", "P:study.background", FOREVER, NOW,
                List.of("服务端", "写接口"));
        Doc noise = doc(102L, "GOAL", "study.goal", "高数笔记的极限与连续需要多做练习", GLOBAL, 0L, "g102");
        MemorySearchIndex index = build(List.of(withKeywords, noise));

        Result result = search(index, "服务端接口怎么设计", GLOBAL, 0L);
        assertEquals(List.of("m00101"), ids(result.topical()), "批准的 keywords 参与召回");
        Result unrelated = search(index, "极限与连续要不要刷题", GLOBAL, 0L);
        assertTrue(unrelated.injectedFacts().stream().noneMatch(doc -> doc.memoryId().equals("m00101")),
                "扩展词不能变成通行证：与查询无关的事实依旧不入候选");
    }

    @Test
    void answerStyleRegionOnlyTakesPreferenceFactsWithAnswerPredicates() {
        MemorySearchIndex index = build(List.of(
                doc(1L, "PREFERENCE", "answer.detail", "解释请简短一些", GLOBAL, 0L, null),
                doc(2L, "GOAL", "study.goal", "目标是把统计学习 Chapter 2 的公式推导看完", GLOBAL, 0L, null)));
        Result result = search(index, "解释要简短一些", GLOBAL, 0L);
        assertEquals(List.of("m00001"), ids(result.style()), "风格区只收 PREFERENCE + answer.* 谓词");
        assertTrue(result.topical().stream().noneMatch(hit -> hit.doc().memoryId().equals("m00001")));
    }
}
