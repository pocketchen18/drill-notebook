package com.drillnotebook.app.memory.prototype;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.drillnotebook.app.memory.prototype.MemorySearchIndexPrototype.Fact;
import com.drillnotebook.app.memory.prototype.MemorySearchIndexPrototype.Hit;
import com.drillnotebook.app.memory.prototype.MemorySearchIndexPrototype.Kind;
import com.drillnotebook.app.memory.prototype.MemorySearchIndexPrototype.SearchResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * P0a 第①组（执行方案 §9.1、§13.3，验收 §9.1.5）：中文词法召回与 2,000 条规模的 warm 延迟。
 * 固定 seed 的合成库 + 30 条开发集；先核实 gold 事实确实在库里，再测召回。
 * 这里的成绩不代表真实小模型的 120 例效果评测，原型通过也不能替代最终评测。
 */
class MemorySearchIndexPrototypeTest {

    static final long SEED = 20261008L;
    static final int LIBRARY_SIZE = 2000;
    static final long TODAY = 20_600L;
    static final String GLOBAL = "GLOBAL";
    static final String NOTEBOOK_3 = "NOTEBOOK|3";
    static final String NOTEBOOK_4 = "NOTEBOOK|4";

    private static MemorySearchIndexPrototype library;

    record DevCase(String query, String scope, long goldId) {
    }

    @BeforeAll
    static void buildLibrary() {
        List<Fact> facts = new ArrayList<>(goldFacts());
        facts.addAll(syntheticFacts(LIBRARY_SIZE, SEED));
        library = build(facts);
    }

    static MemorySearchIndexPrototype build(List<Fact> facts) {
        MemorySearchIndexPrototype index = new MemorySearchIndexPrototype();
        for (Fact fact : facts) {
            index.add(fact);
        }
        return index;
    }

    static Fact fact(long id, String subject, String predicate, String label, String value, String scope,
            String status, long validUntil, Kind kind) {
        return new Fact(id, subject, predicate, label, value, scope, status, validUntil, TODAY - 5, kind);
    }

    /** 30 条 gold 事实 + 3 条状态过滤用的对照事实。 */
    static List<Fact> goldFacts() {
        List<Fact> facts = new ArrayList<>();
        facts.add(fact(1, "全局", "answer.language", "英语 英文 中文 语言", "以后都用英语回答", GLOBAL, "ACTIVE",
                Long.MAX_VALUE, Kind.STYLE));
        facts.add(fact(2, "全局", "answer.detail", "简短 简洁 详细 啰嗦 冗长", "解释请简短一些", GLOBAL, "ACTIVE",
                Long.MAX_VALUE, Kind.STYLE));
        facts.add(fact(3, "全局", "answer.order", "例子 举例 原理 顺序 结论", "先举例子再讲原理", GLOBAL, "ACTIVE",
                Long.MAX_VALUE, Kind.STYLE));
        facts.add(fact(4, "线性代数笔记", "answer.language", "英语 英文 中文 语言", "这本笔记用中文回答", NOTEBOOK_3,
                "ACTIVE", Long.MAX_VALUE, Kind.STYLE));
        facts.add(fact(5, "操作系统笔记", "answer.language", "英语 英文 中文 语言", "这本笔记用英文回答", NOTEBOOK_4,
                "ACTIVE", Long.MAX_VALUE, Kind.STYLE));
        facts.add(fact(6, "全局", "study.goal", "雅思 托福 考试 备考 目标", "正在准备雅思考试", GLOBAL, "ACTIVE",
                Long.MAX_VALUE, Kind.TOPIC));
        facts.add(fact(7, "线性代数笔记", "study.goal", "特征值 矩阵 章节 目标", "线性代数要掌握特征值与相似对角化",
                NOTEBOOK_3, "ACTIVE", Long.MAX_VALUE, Kind.TOPIC));
        facts.add(fact(8, "全局", "study.background", "背景 专业 学生 年级", "我是计算机专业大三学生", GLOBAL, "ACTIVE",
                Long.MAX_VALUE, Kind.TOPIC));
        facts.add(fact(9, "全局", "task.constraint", "步骤 操作 三步 限制", "每次只给三步以内的操作", GLOBAL, "ACTIVE",
                Long.MAX_VALUE, Kind.TOPIC));
        facts.add(fact(10, "全局", "other", "快捷键 键盘 习惯", "我习惯用键盘快捷键操作", GLOBAL, "ACTIVE",
                Long.MAX_VALUE, Kind.TOPIC));
        facts.add(fact(11, "全局", "study.goal", "语言 Rust 所有权 学习", "下半年想学 Rust 的所有权模型", GLOBAL,
                "ACTIVE", Long.MAX_VALUE, Kind.TOPIC));
        facts.add(fact(12, "离散数学笔记", "study.goal", "图论 重点 章节", "离散数学重点在图论", NOTEBOOK_4, "ACTIVE",
                Long.MAX_VALUE, Kind.TOPIC));
        facts.add(fact(13, "线性代数笔记", "answer.detail", "简短 简洁 详细 步骤 推导", "公式推导要写完整步骤", NOTEBOOK_3,
                "ACTIVE", Long.MAX_VALUE, Kind.STYLE));
        facts.add(fact(14, "全局", "study.background", "考研 数学 入学 考试", "我在准备研究生入学考试的数学一", GLOBAL,
                "ACTIVE", Long.MAX_VALUE, Kind.TOPIC));
        facts.add(fact(15, "全局", "other", "时间 每天 小时 安排", "每天学习时间不超过一小时", GLOBAL, "ACTIVE",
                Long.MAX_VALUE, Kind.TOPIC));
        facts.add(fact(16, "线性代数笔记", "answer.order", "例子 举例 原理 顺序 结论", "先看结论再看证明", NOTEBOOK_3,
                "ACTIVE", Long.MAX_VALUE, Kind.STYLE));
        facts.add(fact(17, "全局", "answer.language", "术语 英文 夹杂 中文", "中文回答里可以夹带英文术语", GLOBAL,
                "ACTIVE", Long.MAX_VALUE, Kind.STYLE));
        facts.add(fact(18, "全局", "study.goal", "正则 表达式 个月 学完", "想在一个月内学完正则表达式", GLOBAL, "ACTIVE",
                Long.MAX_VALUE, Kind.TOPIC));
        facts.add(fact(19, "全局", "task.constraint", "表格 格式 避免", "不要用表格形式回答", GLOBAL, "ACTIVE",
                Long.MAX_VALUE, Kind.TOPIC));
        facts.add(fact(20, "操作系统笔记", "answer.detail", "要点 简短 段落 详细", "只要要点不要长段落", NOTEBOOK_4, "ACTIVE",
                Long.MAX_VALUE, Kind.STYLE));
        facts.add(fact(21, "全局", "other", "音阶 和弦 音乐 基础", "我对音阶和和弦有基础", GLOBAL, "ACTIVE",
                Long.MAX_VALUE, Kind.TOPIC));
        facts.add(fact(22, "全局", "study.background", "后端 Java 开发 工作年限", "做过三年 Java 后端开发", GLOBAL,
                "ACTIVE", Long.MAX_VALUE, Kind.TOPIC));
        facts.add(fact(23, "线性代数笔记", "study.goal", "概率论 大数定律 章节 复习", "概率论重点复习大数定律", NOTEBOOK_3,
                "ACTIVE", Long.MAX_VALUE, Kind.TOPIC));
        facts.add(fact(24, "操作系统笔记", "answer.order", "代码 可运行 例子 顺序", "先给可运行代码再解释", NOTEBOOK_4,
                "ACTIVE", Long.MAX_VALUE, Kind.STYLE));
        facts.add(fact(25, "全局", "study.goal", "二叉树 遍历 本周 刷题", "本周把二叉树遍历刷完", GLOBAL, "ACTIVE",
                Long.MAX_VALUE, Kind.TOPIC));
        facts.add(fact(26, "操作系统笔记", "study.goal", "内存管理 重点 章节", "操作系统重点在内存管理", NOTEBOOK_4, "ACTIVE",
                Long.MAX_VALUE, Kind.TOPIC));
        facts.add(fact(27, "全局", "other", "卡片 问答 复习 方式", "复习时喜欢用问答卡片", GLOBAL, "ACTIVE",
                Long.MAX_VALUE, Kind.TOPIC));
        facts.add(fact(28, "全局", "task.constraint", "日语 避免 语言", "解释时避免使用日语", GLOBAL, "ACTIVE",
                Long.MAX_VALUE, Kind.TOPIC));
        facts.add(fact(29, "线性代数笔记", "answer.language", "中英 混用 术语", "这本笔记请中英混用", NOTEBOOK_3, "ACTIVE",
                Long.MAX_VALUE, Kind.STYLE));
        facts.add(fact(30, "全局", "study.goal", "竞赛 算法 比赛 参加", "准备参加学校的算法竞赛", GLOBAL, "ACTIVE",
                Long.MAX_VALUE, Kind.TOPIC));
        // 状态对照：过期、失效、停用都不该被注入
        facts.add(fact(41, "全局", "study.goal", "游泳 技能 目标", "想学会游泳", GLOBAL, "EXPIRED", Long.MAX_VALUE,
                Kind.TOPIC));
        facts.add(fact(42, "全局", "study.goal", "钢琴 技能 目标", "想学钢琴", GLOBAL, "ACTIVE", TODAY - 1, Kind.TOPIC));
        facts.add(fact(43, "全局", "study.goal", "吉他 技能 目标", "想学吉他", GLOBAL, "DISABLED", Long.MAX_VALUE,
                Kind.TOPIC));
        return facts;
    }

    /** 30 条开发集：措辞刻意与原文不同，考察词法召回的真实上限。 */
    static List<DevCase> devSet() {
        return List.of(
                new DevCase("以后回答都用英文好吗", GLOBAL, 1),
                new DevCase("讲简单点，别太啰嗦", GLOBAL, 2),
                new DevCase("能不能先给例子", GLOBAL, 3),
                new DevCase("这本笔记要用中文还是英文", NOTEBOOK_3, 4),
                new DevCase("用英文解释这本笔记", NOTEBOOK_4, 5),
                new DevCase("我最近在备考什么", GLOBAL, 6),
                new DevCase("线性代数要学什么", NOTEBOOK_3, 7),
                new DevCase("介绍下我的背景", GLOBAL, 8),
                new DevCase("操作别超过三步", GLOBAL, 9),
                new DevCase("我习惯用键盘快捷键", GLOBAL, 10),
                new DevCase("Rust 的所有权怎么学", GLOBAL, 11),
                new DevCase("离散数学的重点是什么", NOTEBOOK_4, 12),
                new DevCase("公式推导希望怎么写", NOTEBOOK_3, 13),
                new DevCase("研究生入学考试数学一", GLOBAL, 14),
                new DevCase("每天学习时间有多少", GLOBAL, 15),
                new DevCase("先看结论还是先证明", NOTEBOOK_3, 16),
                new DevCase("回答里可以带英文术语吗", GLOBAL, 17),
                new DevCase("正则表达式打算多久学完", GLOBAL, 18),
                new DevCase("别用表格回答", GLOBAL, 19),
                new DevCase("只要要点可以吗", NOTEBOOK_4, 20),
                new DevCase("音阶和和弦我有基础吗", GLOBAL, 21),
                new DevCase("我做过多久后端", GLOBAL, 22),
                new DevCase("概率论复习哪一章", NOTEBOOK_3, 23),
                new DevCase("先给能跑的代码", NOTEBOOK_4, 24),
                new DevCase("这周要刷完什么", GLOBAL, 25),
                new DevCase("操作系统重点在哪", NOTEBOOK_4, 26),
                new DevCase("复习喜欢用什么方式", GLOBAL, 27),
                new DevCase("解释时能用日语吗", GLOBAL, 28),
                new DevCase("中英混着说行吗", NOTEBOOK_3, 29),
                new DevCase("算法竞赛", GLOBAL, 30));
    }

    /** 合成噪声库：与开发集同域但不同事实，用来压出词法歧义和规模成本。 */
    static List<Fact> syntheticFacts(int count, long seed) {
        Random random = new Random(seed);
        String[] subjects = { "高数笔记", "英语听力笔记", "结构力学笔记", "有机化学笔记", "世界史笔记", "微观经济学笔记",
                "电路原理笔记", "离散数学笔记", "操作系统笔记", "机器学习笔记" };
        String[] topics = { "极限与连续", "导数应用", "积分不等式", "听力速记", "桁架内力", "取代反应", "冷战起因",
                "边际效用", "基尔霍夫定律", "图的着色", "进程调度", "反向传播", "特征值", "傅里叶级数", "数列递推",
                "动量守恒", "遗传规律", "三元相图", "复变函数", "拉普拉斯变换" };
        String[] verbs = { "需要多做练习", "重点在于理解定义", "要先背公式", "常用错题本整理", "结合例题来看",
                "按章节复习", "每天回顾一遍", "先跳过难题", "用思维导图串联", "考前再刷一遍" };
        String[] predicates = { "study.goal", "study.background", "task.constraint", "other" };
        String[] labels = { "复习 章节 目标", "练习 刷题 错题", "定义 公式 记忆", "例题 计算 步骤", "总结 笔记 整理" };
        List<Fact> facts = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            long id = 101L + index;
            String subject = subjects[random.nextInt(subjects.length)];
            String topic = topics[random.nextInt(topics.length)];
            String verb = verbs[random.nextInt(verbs.length)];
            String predicate = predicates[random.nextInt(predicates.length)];
            String scope = random.nextInt(4) == 0 ? GLOBAL : "NOTEBOOK|" + (3 + random.nextInt(3));
            facts.add(new Fact(id, subject, predicate, labels[random.nextInt(labels.length)],
                    subject + "的" + topic + verb, scope, "ACTIVE", Long.MAX_VALUE,
                    TODAY - random.nextInt(40), Kind.TOPIC));
        }
        return facts;
    }

    @Test
    void goldFactsExistBeforeMeasuringRecall() {
        Set<Long> goldIds = goldFacts().stream().map(Fact::id).collect(Collectors.toSet());
        Set<Long> libraryIds = new TreeSet<>();
        for (DevCase testCase : devSet()) {
            assertTrue(goldIds.contains(testCase.goldId()), "开发集引用了不存在的 gold 事实: " + testCase);
            libraryIds.add(testCase.goldId());
        }
        assertEquals(30, libraryIds.size(), "开发集必须是 30 条不同事实");
        assertEquals(LIBRARY_SIZE + goldFacts().size(), library.size());
    }

    @Test
    void recallOnThirtyCaseChineseDevSet() {
        List<String> misses = new ArrayList<>();
        int hits = 0;
        for (DevCase testCase : devSet()) {
            SearchResult result = library.search(testCase.query(), testCase.scope(), TODAY, 3,
                    MemorySearchIndexPrototype.DEFAULT_DEADLINE_NANOS);
            List<Long> ids = new ArrayList<>();
            result.topicHits().forEach(hit -> ids.add(hit.fact().id()));
            result.styleHits().forEach(hit -> ids.add(hit.fact().id()));
            if (ids.contains(testCase.goldId())) {
                hits++;
            } else {
                misses.add(testCase.query() + " -> 期望 " + testCase.goldId() + "，实际 " + ids);
            }
        }
        System.out.println("[P0a-①] recall@3 = " + hits + "/" + devSet().size() + " (seed=" + SEED
                + ", library=" + library.size() + ")");
        misses.forEach(miss -> System.out.println("[P0a-①][miss] " + miss));
        assertTrue(hits >= 27,
                "30 条开发集召回必须 >= 27，实际 " + hits + "；未命中：" + misses);
    }

    @Test
    void unrelatedQueryInjectsNothingInsteadOfPadding() {
        SearchResult result = library.search("量子色动力学的格点规约怎么写", GLOBAL, TODAY, 3,
                MemorySearchIndexPrototype.DEFAULT_DEADLINE_NANOS);
        assertTrue(result.topicHits().isEmpty() && result.styleHits().isEmpty(),
                "零重合的主题查询不得凑数注入：" + result.injectedFacts());
    }

    @Test
    void singleCharacterQueryDoesNotScanFuzzily() {
        // §9.1 第 2 条：单字查询不跑全库模糊匹配（库里"复习时喜欢用问答卡片"含"卡"，但未被切成单字 token）
        SearchResult result = library.search("卡", GLOBAL, TODAY, 3,
                MemorySearchIndexPrototype.DEFAULT_DEADLINE_NANOS);
        assertTrue(result.topicHits().isEmpty(), "单字查询不得命中多字事实");
    }

    @Test
    void scopeIsolationAndNotebookOverride() {
        SearchResult inNotebook3 = library.search("这本笔记用中文还是英文", NOTEBOOK_3, TODAY, 3,
                MemorySearchIndexPrototype.DEFAULT_DEADLINE_NANOS);
        assertFalse(inNotebook3.styleHits().isEmpty());
        assertEquals(NOTEBOOK_3, inNotebook3.styleHits().get(0).fact().scope(), "同 scope 的偏好必须压过全局偏好");
        assertEquals(4L, inNotebook3.styleHits().get(0).fact().id());
        // NOTEBOOK|4 的事实不得出现在 NOTEBOOK|3 的检索里
        for (Hit hit : inNotebook3.styleHits()) {
            assertTrue(hit.fact().scope().equals(NOTEBOOK_3) || hit.fact().scope().equals(GLOBAL),
                    "跨笔记本泄漏: " + hit.fact());
        }
        SearchResult inNotebook4 = library.search("这本笔记用中文还是英文", NOTEBOOK_4, TODAY, 3,
                MemorySearchIndexPrototype.DEFAULT_DEADLINE_NANOS);
        assertFalse(inNotebook4.styleHits().isEmpty());
        assertEquals(5L, inNotebook4.styleHits().get(0).fact().id(), "同一 predicate 各笔记本读自身值");
    }

    @Test
    void expiredDisabledAndSupersededFactsAreFiltered() {
        for (String query : List.of("想学会游泳的目标还在吗", "想学钢琴", "想学吉他")) {
            SearchResult result = library.search(query, GLOBAL, TODAY, 3,
                    MemorySearchIndexPrototype.DEFAULT_DEADLINE_NANOS);
            List<Long> ids = result.injectedFacts().stream().map(Fact::id).toList();
            assertFalse(ids.contains(41L), "EXPIRED 状态不得注入");
            assertFalse(ids.contains(42L), "已过有效期不得注入");
            assertFalse(ids.contains(43L), "DISABLED 状态不得注入");
        }
    }

    @Test
    void singleValuePredicateInjectsOneWhileMultiValueGoalsCoexist() {
        List<Fact> facts = new ArrayList<>();
        facts.add(fact(51, "全局", "answer.detail", "简短 详细", "解释请简短一些", GLOBAL, "ACTIVE", Long.MAX_VALUE,
                Kind.STYLE));
        facts.add(fact(52, "全局", "answer.detail", "简短 详细", "回答要详细展开", GLOBAL, "ACTIVE", Long.MAX_VALUE,
                Kind.STYLE));
        facts.add(fact(53, "全局", "study.goal", "目标 考试", "正在准备雅思考试", GLOBAL, "ACTIVE", Long.MAX_VALUE,
                Kind.TOPIC));
        facts.add(fact(54, "全局", "study.goal", "目标 考试", "同时在准备托福考试", GLOBAL, "ACTIVE", Long.MAX_VALUE,
                Kind.TOPIC));
        MemorySearchIndexPrototype index = build(facts);

        SearchResult styles = index.search("解释要简短还是详细", GLOBAL, TODAY, 3,
                MemorySearchIndexPrototype.DEFAULT_DEADLINE_NANOS);
        assertEquals(1, styles.styleHits().size(), "单值 predicate 同键只注入一条");

        SearchResult goals = index.search("我的考试目标雅思和托福", GLOBAL, TODAY, 3,
                MemorySearchIndexPrototype.DEFAULT_DEADLINE_NANOS);
        List<Long> goalIds = goals.topicHits().stream().map(hit -> hit.fact().id()).toList();
        assertTrue(goalIds.contains(53L) && goalIds.contains(54L),
                "两个并存的 Multivalue 学习目标都要能进候选（M13）：" + goalIds);
    }

    @Test
    void topicInjectionNeverExceedsThreeFactsPlusTwoStyles() {
        SearchResult result = library.search("复习 目标 章节 练习 笔记 整理 每天回顾", GLOBAL, TODAY, 10,
                MemorySearchIndexPrototype.DEFAULT_DEADLINE_NANOS);
        assertTrue(result.topicHits().size() <= MemorySearchIndexPrototype.TOPIC_FACT_LIMIT);
        assertTrue(result.styleHits().size() <= MemorySearchIndexPrototype.STYLE_PREFERENCE_LIMIT);
    }

    @Test
    void warmSearchLatencyStaysUnderFiftyMillisP95() {
        List<String> queries = devSet().stream().map(DevCase::query).toList();
        // 预热：让 JIT 稳定后再取样
        for (int index = 0; index < 50; index++) {
            library.search(queries.get(index % queries.size()), GLOBAL, TODAY, 3,
                    MemorySearchIndexPrototype.DEFAULT_DEADLINE_NANOS);
        }
        List<Long> samples = new ArrayList<>();
        long wallStarted = System.nanoTime();
        long wallMillisStarted = System.currentTimeMillis();
        for (int index = 0; index < 200; index++) {
            long started = System.nanoTime();
            library.search(queries.get(index % queries.size()), GLOBAL, TODAY, 3,
                    MemorySearchIndexPrototype.DEFAULT_DEADLINE_NANOS);
            samples.add(System.nanoTime() - started);
        }
        long wallNanos = System.nanoTime() - wallStarted;
        long sampleSum = samples.stream().mapToLong(Long::longValue).sum();
        samples.sort(Long::compare);
        double p50 = samples.get(samples.size() / 2) / 1_000_000.0;
        double p95 = samples.get((int) (samples.size() * 0.95)) / 1_000_000.0;
        double p99 = samples.get((int) (samples.size() * 0.99)) / 1_000_000.0;
        System.out.println("[P0a-①] warm latency library=" + library.size() + " p50=" + p50 + "ms p95=" + p95
                + "ms p99=" + p99 + "ms vm=" + Runtime.version());
        System.out.println("[P0a-①] wall=" + wallNanos / 1_000_000.0 + "ms (clockMillis="
                + (System.currentTimeMillis() - wallMillisStarted) + ") sampleSum=" + sampleSum / 1_000_000.0
                + "ms max=" + samples.get(samples.size() - 1) / 1_000_000.0 + "ms");
        assertTrue(p95 < 50.0, "2,000 条下 warm p95 必须 < 50ms，实际 " + p95 + "ms");
        assertTrue(p99 < MemorySearchIndexPrototype.DEFAULT_DEADLINE_NANOS / 1_000_000.0,
                "p99 必须落在 100ms deadline 内，实际 " + p99 + "ms");
    }

    @Test
    void deadlineOverrunDegradesToNoMemory() {
        SearchResult result = library.search("我最近在备考什么", GLOBAL, TODAY, 3, 1L);
        assertTrue(result.timedOut(), "极小 deadline 必须触发超时降级");
        assertTrue(result.topicHits().isEmpty() && result.styleHits().isEmpty(), "超时后不得半投记忆");
    }

    @Test
    void tokenizerProducesBigramsTrigramsAndLatinWords() {
        List<String> tokens = MemorySearchIndexPrototype.tokens("解释请简短一些，使用 Rust 2024");
        assertTrue(tokens.contains("解释"));
        assertTrue(tokens.contains("简短"));
        assertTrue(tokens.contains("rust"));
        assertTrue(tokens.contains("2024"));
        assertTrue(tokens.contains("释请简"));
        assertFalse(tokens.contains("的"), "停用词必须过滤");
        // NFKC 归一：全角与半角同一 token
        assertEquals(MemorySearchIndexPrototype.tokens("Ｒｕｓｔ"), MemorySearchIndexPrototype.tokens("rust"));
    }
}
