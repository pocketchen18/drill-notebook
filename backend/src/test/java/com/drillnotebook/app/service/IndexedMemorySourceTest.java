package com.drillnotebook.app.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.drillnotebook.app.config.DatabaseInitializer;
import com.drillnotebook.app.memory.Schema9Fixture;
import com.drillnotebook.app.memory.TestWorkspace;
import com.drillnotebook.app.repository.ChatLegacyWiring;
import com.drillnotebook.app.repository.EmbeddingJobRepository;
import com.drillnotebook.app.repository.MemoryJobRepository;
import com.drillnotebook.app.repository.MemoryOperationRepository;
import com.drillnotebook.app.repository.MemoryProposalRepository;
import com.drillnotebook.app.repository.MemoryRepository;
import com.drillnotebook.app.repository.MemorySuppressionRepository;
import com.drillnotebook.app.service.memory.MemoryApiResult;
import com.drillnotebook.app.service.memory.MemoryIndexService;
import com.drillnotebook.app.service.memory.MemoryItemService;
import com.drillnotebook.app.service.memory.MemoryVaultService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * P5 的读路径端到端（执行方案 §14 P5、验收 M08/M09/M10/M13/M14/S08/S11/S12/F06）：
 * 事实全部走生产写路径落库，检索只经 {@link IndexedMemorySource} → {@link MemoryIndexService} 的内存快照，
 * 断言"谁能进本轮上下文"和"快照怎么建、怎么失效"。
 *
 * <p>同步建快照的用例走 {@code buildNow}（与后台任务同一份实现）；只有"异步、可取消"那两条按 §9.1-1
 * 真的等后台线程，并用有界等待收口，不靠 sleep 猜时间。</p>
 */
class IndexedMemorySourceTest {
    private static final String PASSWORD = "correct horse battery";
    private static final String GLOBAL_STYLE = "以后所有解释都简短一些";
    private static final String NOTEBOOK_8_STYLE = "这本笔记要详细展开推导";
    private static final String NOTEBOOK_9_STYLE = "那本笔记只要要点";
    private static final int DEADLINE_MILLIS = 100;

    private Path appRoot;
    private DataSource dataSource;
    private JdbcTemplate jdbc;
    private ChatLegacyWiring.Wiring wiring;
    private ChatContextWiring.Stack stack;
    private MemoryItemService items;
    private MemoryIndexService indexes;
    private IndexedMemorySource source;
    private String vaultId;
    private int sequence;

    @BeforeEach
    void createDatabase() throws Exception {
        appRoot = TestWorkspace.scratchDirectory("p5-source-");
        dataSource = Schema9Fixture.dataSource(appRoot);
        new DatabaseInitializer(dataSource, Schema9Fixture.resolver(appRoot)).initialize();
        jdbc = new JdbcTemplate(dataSource);
        wiring = ChatLegacyWiring.wire(dataSource, jdbc);
        stack = ChatContextWiring.wire(dataSource, jdbc, wiring);
        ObjectMapper mapper = new ObjectMapper();
        MemorySuppressionRepository suppressions = new MemorySuppressionRepository(dataSource, wiring.immediate());
        items = new MemoryItemService(stack.crypto(), wiring.memory(), stack.vault(), stack.policy(),
                new MemoryOperationRepository(dataSource, wiring.immediate()),
                new MemoryProposalRepository(dataSource, wiring.immediate()), suppressions,
                new MemoryJobRepository(dataSource, wiring.immediate()), wiring.metadata());
        indexes = stack.indexes();
        source = stack.memories();
        jdbc.update("INSERT INTO notebook(id, title) VALUES (8, '操作系统')");
        jdbc.update("INSERT INTO notebook(id, title) VALUES (9, '线性代数')");
        unlock();
    }

    @AfterEach
    void removeDatabase() throws Exception {
        indexes.shutdown();
        TestWorkspace.deleteRecursively(appRoot);
    }

    // ---- §9.1-5 作用域、过期、抑制与覆盖 ----

    @Test
    void notebookSessionsReadTheirOwnScopeAndGlobalButNeverAnotherNotebook() {
        add("PREFERENCE", "GLOBAL", 0L, "answer.detail", null, GLOBAL_STYLE, null);
        add("PREFERENCE", "NOTEBOOK", 8L, "answer.language", null, NOTEBOOK_8_STYLE, null);
        add("PREFERENCE", "NOTEBOOK", 9L, "answer.language", null, NOTEBOOK_9_STYLE, null);
        rebuild();

        ContextMemorySource.Selection inNotebook8 = select("NOTEBOOK", 8L, "这本笔记要详细展开推导吗");
        assertTrue(values(inNotebook8).contains(NOTEBOOK_8_STYLE), "自身作用域的事实要能进来（M08）");
        assertTrue(values(inNotebook8).contains(GLOBAL_STYLE), "NOTEBOOK 作用域同时读 GLOBAL（M08）");
        assertFalse(values(inNotebook8).contains(NOTEBOOK_9_STYLE), "不得看见别的笔记本的事实（M08）");

        ContextMemorySource.Selection inNotebook9 = select("NOTEBOOK", 9L, "那本笔记只要要点行吗");
        assertTrue(values(inNotebook9).contains(NOTEBOOK_9_STYLE));
        assertFalse(values(inNotebook9).contains(NOTEBOOK_8_STYLE), "跨本泄漏是一条都不能有");

        ContextMemorySource.Selection global = select("GLOBAL", 0L, "这本笔记要详细展开推导吗");
        assertFalse(values(global).contains(NOTEBOOK_8_STYLE) || values(global).contains(NOTEBOOK_9_STYLE),
                "全局会话不读任何笔记本作用域（M08）");
    }

    @Test
    void notebookPreferenceReplacesTheGlobalOneWithTheSamePredicate() {
        add("PREFERENCE", "GLOBAL", 0L, "answer.detail", null, GLOBAL_STYLE, null);
        add("PREFERENCE", "NOTEBOOK", 8L, "answer.detail", null, NOTEBOOK_8_STYLE, null);
        rebuild();

        List<String> inNotebook = values(select("NOTEBOOK", 8L, "解释要简短还是详细展开"));
        assertTrue(inNotebook.contains(NOTEBOOK_8_STYLE));
        assertFalse(inNotebook.contains(GLOBAL_STYLE), "笔记本偏好覆盖全局的同谓词偏好（§9.1-5）");
        assertTrue(values(select("GLOBAL", 0L, "解释要简短还是详细")).contains(GLOBAL_STYLE),
                "全局会话没有可覆盖它的那条，仍读全局");
    }

    @Test
    void anExpiredNotebookPreferenceGivesTheGlobalOneBack() {
        add("PREFERENCE", "GLOBAL", 0L, "answer.detail", null, GLOBAL_STYLE, null);
        add("PREFERENCE", "NOTEBOOK", 8L, "answer.detail", null, NOTEBOOK_8_STYLE,
                Instant.ofEpochMilli(System.currentTimeMillis() + 3_600_000L).toString());
        rebuild();
        long now = System.currentTimeMillis();

        assertEquals(List.of(NOTEBOOK_8_STYLE),
                values(selectAt("NOTEBOOK", 8L, "解释要简短还是详细展开", now, DEADLINE_MILLIS)), "有效期内以笔记本为准");
        assertEquals(List.of(GLOBAL_STYLE),
                values(selectAt("NOTEBOOK", 8L, "解释要简短还是详细展开", now + 3_700_000L, DEADLINE_MILLIS)),
                "笔记本偏好一到期就必须把全局那条让回来：否则两条都拿不到，且不跑清理 job 就会一直是这个状态");
    }

    @Test
    void expiredFactDropsOutOfInjectionTheMomentTheClockCrossesItsDeadline() {
        String deadline = Instant.ofEpochMilli(System.currentTimeMillis() + 3_600_000L).toString();
        add("TASK", "GLOBAL", 0L, "task.constraint", null, "这周的复习计划只排三步", deadline);
        rebuild();
        long now = System.currentTimeMillis();

        assertTrue(values(selectAt("GLOBAL", 0L, "这周的复习计划排几步", now, DEADLINE_MILLIS)).contains(
                "这周的复习计划只排三步"), "有效期内照常注入");
        List<String> after = values(selectAt("GLOBAL", 0L, "这周的复习计划排几步",
                now + 3_700_000L, DEADLINE_MILLIS));
        assertFalse(after.contains("这周的复习计划只排三步"), "跨到期时刻立刻不注入，不等清理 job（M10）");
    }

    @Test
    void forgottenFamilyStaysForgottenEvenAfterTheSameFactIsAddedAgain() throws SQLException {
        MemoryApiResult created = add("GOAL", "GLOBAL", 0L, "study.goal", "goal-ielts", "正在准备雅思考试", null);
        rebuild();
        String memoryId = String.valueOf(created.body().get("memoryId"));
        assertTrue(values(select("GLOBAL", 0L, "我在备考什么")).contains("正在准备雅思考试"));

        assertEquals(204, items.delete(memoryId, 1, false, op("delete")).status());
        // 删完再手工录一条同族事实：表里没有 ACTIVE 冲突，但 FAMILY 抑制必须让它进不了注入（F06/S08）
        add("GOAL", "GLOBAL", 0L, "study.goal", "goal-ielts", "正在准备雅思考试", null);
        rebuild();
        assertEquals(1, suppressionCount("GLOBAL", 0L, MemorySuppressionRepository.KIND_FAMILY),
                "删除必须写下家族抑制键");
        assertFalse(values(select("GLOBAL", 0L, "我在备考什么")).contains("正在准备雅思考试"),
                "被遗忘的同族事实不得借新行复活");
    }

    @Test
    void predicateSuppressionDoesNotHideFactsTheUserNeverDeleted() throws SQLException {
        add("GOAL", "GLOBAL", 0L, "study.goal", "goal-ielts", "正在准备雅思考试", null);
        MemoryApiResult second = add("GOAL", "GLOBAL", 0L, "study.goal", "goal-toefl", "同时在准备托福考试", null);
        rebuild();

        assertEquals(204, items.delete(String.valueOf(second.body().get("memoryId")), 1, false, op("delete-ielts"))
                .status());
        rebuild();
        List<String> remaining = values(select("GLOBAL", 0L, "我的考试目标是什么"));
        assertTrue(remaining.contains("正在准备雅思考试"),
                "删掉一个学习目标不能把另一个也屏蔽掉：抑制只认 FAMILY 档（M13）");
    }

    @Test
    void corruptRowIsIsolatedInsteadOfReachingTheModel() {
        MemoryApiResult good = add("GOAL", "GLOBAL", 0L, "study.goal", "goal-ok", "每周固定复习两次", null);
        MemoryApiResult broken = add("GOAL", "GLOBAL", 0L, "study.goal", "goal-bad", "周末补一次错题整理", null);
        String brokenMemoryId = String.valueOf(broken.body().get("memoryId"));
        String cipher = jdbc.queryForObject("SELECT payload_cipher FROM ai_memory_item WHERE memory_id = ?",
                String.class, brokenMemoryId);
        jdbc.update("UPDATE ai_memory_item SET payload_cipher = ? WHERE memory_id = ?",
                "M" + cipher.substring(1), brokenMemoryId);

        MemoryIndexService.Snapshot snapshot = rebuild();
        assertEquals(1, snapshot.corruptRows(), "坏密文按行隔离，不拖累整份快照（M14）");
        List<String> injected = values(select("GLOBAL", 0L, "复习节奏是每周几次"));
        assertTrue(injected.contains("每周固定复习两次"), "其它事实照常可用（M14）");
        assertFalse(injected.contains("周末补一次错题整理"));
        assertFalse(injected.toString().contains("M0"), "不得把乱码或占位塞给模型");
        assertNotNull(good.body().get("memoryId"));
    }

    // ---- §9.1-1 快照的版本一致、异步构建与锁定清理 ----

    @Test
    void staleSnapshotIsNeverReusedAfterTheVaultRevisionMoves() {
        add("GOAL", "GLOBAL", 0L, "study.goal", "goal-old", "旧目标：准备雅思", null);
        rebuild();
        assertEquals(1, select("GLOBAL", 0L, "我在备考什么").topical().size());

        add("GOAL", "GLOBAL", 0L, "study.goal", "goal-new", "新目标：准备托福", null);
        ContextMemorySource.Selection duringRebuild = select("GLOBAL", 0L, "我在备考什么");
        assertEquals(ContextMemorySource.Availability.BUILDING, duringRebuild.availability(),
                "库版本一变，旧快照就必须当作不可用（§9.1-1、交接 B 项）");
        assertTrue(duringRebuild.topical().isEmpty(), "重建期间宁可整轮不带记忆，也不能只带旧的那半份");

        rebuild();
        List<String> injected = values(select("GLOBAL", 0L, "我的备考目标有两个"));
        assertTrue(injected.contains("新目标：准备托福"), "新写入的事实要能进来");
    }

    @Test
    void firstTurnAfterUnlockDegradesToNoMemoryWhileTheSnapshotIsBuiltInBackground() throws Exception {
        add("GOAL", "GLOBAL", 0L, "study.goal", "goal-async", "正在准备雅思考试", null);
        indexes.clear();

        ContextMemorySource.Selection first = select("GLOBAL", 0L, "我在备考什么");
        assertEquals(ContextMemorySource.Availability.BUILDING, first.availability(),
                "解锁后第一条聊天不陪跑几千次解密（§9.1-1）");
        assertTrue(first.topical().isEmpty() && first.candidatesSeen() == 0, "本轮就是不带记忆，不半投");

        awaitSnapshot();
        ContextMemorySource.Selection ready = select("GLOBAL", 0L, "我在备考什么");
        assertEquals(ContextMemorySource.Availability.READY, ready.availability());
        assertTrue(values(ready).contains("正在准备雅思考试"), "后台建好后下一轮照常注入");
    }

    @Test
    void lockingDropsTheCachedSnapshotAndUnlockingRebuildsIt() throws Exception {
        add("GOAL", "GLOBAL", 0L, "study.goal", "goal-lock", "正在准备雅思考试", null);
        rebuild();
        assertTrue(values(select("GLOBAL", 0L, "我在备考什么")).contains("正在准备雅思考试"));

        stack.vault().lock();
        ContextMemorySource.Selection locked = select("GLOBAL", 0L, "我在备考什么");
        assertEquals(ContextMemorySource.Availability.LOCKED, locked.availability(), "锁定状态要说清是锁着（S11）");
        assertTrue(locked.topical().isEmpty());
        assertEquals(Optional.empty(), indexes.ready(vaultId, revision().epoch(), revision().revision()),
                "钥匙没了，解密出来的正文也不能再留在内存里");

        stack.vault().unlock(PASSWORD, List.of());
        awaitSnapshot();
        assertTrue(values(select("GLOBAL", 0L, "我在备考什么")).contains("正在准备雅思考试"),
                "重新解锁后不需要重跑提取就能用（M02）");
    }

    @Test
    void concurrentReadsDuringADeleteNeverSeeATornOrHalfBuiltSnapshot() throws Exception {
        add("GOAL", "GLOBAL", 0L, "study.goal", "goal-a", "第一个目标是准备雅思", null);
        MemoryApiResult second = add("GOAL", "GLOBAL", 0L, "study.goal", "goal-b", "第二个目标是学完 Rust", null);
        add("GOAL", "GLOBAL", 0L, "study.goal", "goal-c", "第三个目标是刷完图论", null);
        rebuild();
        String deletedId = String.valueOf(second.body().get("memoryId"));

        int readers = 8;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(readers);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicBoolean sawBlankButReady = new AtomicBoolean(false);
        List<Thread> threads = new ArrayList<>();
        for (int index = 0; index < readers; index++) {
            Thread thread = new Thread(() -> {
                try {
                    start.await();
                    for (int round = 0; round < 40; round++) {
                        ContextMemorySource.Selection selection = select("GLOBAL", 0L, "我一共说过几个目标");
                        if (selection.availability() == ContextMemorySource.Availability.READY) {
                            // READY 的每一轮都必须带着完整正文：半份快照会被读成"事实凭空消失或变空串"
                            for (ContextMemorySource.Fact fact : selection.topical()) {
                                if (fact.value().isBlank() || fact.memoryId().isBlank() || fact.version() <= 0) {
                                    sawBlankButReady.set(true);
                                }
                            }
                        }
                    }
                } catch (Throwable error) {
                    failure.compareAndSet(null, error);
                } finally {
                    done.countDown();
                }
            }, "p5-concurrent-reader-" + index);
            threads.add(thread);
            thread.start();
        }
        start.countDown();
        assertEquals(204, items.delete(deletedId, 1, false, op("delete-concurrent")).status());
        rebuild();
        done.await();
        for (Thread thread : threads) {
            thread.join(10_000);
        }
        assertEquals(null, failure.get(), "并发读快照不得抛异常：" + failure.get());
        assertFalse(sawBlankButReady.get(), "READY 的结果里不能出现空正文（快照必须整份换入）");
        List<String> after = values(select("GLOBAL", 0L, "我一共说过几个目标"));
        assertFalse(after.contains("第二个目标是学完 Rust"), "重建后删除的那条不再回来");
    }

    @Test
    void retrievalUsesNoPlaintextTableAndLeavesNotebookCoverageBaselinesUntouched() throws Exception {
        seedNotebookSpace();
        EmbeddingJobRepository jobs = new EmbeddingJobRepository(jdbc);
        double coverageBefore = jobs.computeCoverage("NOTEBOOK", "space-p5");
        int chunksBefore = count("retrieval_chunk");
        int ftsBefore = count("retrieval_chunk_fts");
        Set<String> tablesBefore = tables();
        add("GOAL", "GLOBAL", 0L, "study.goal", "goal-x", "正在准备雅思考试", null);

        for (int round = 0; round < 5; round++) {
            rebuild();
            select("GLOBAL", 0L, "我在备考什么");
        }
        stack.vault().lock();
        stack.vault().unlock(PASSWORD, List.of());

        assertEquals(coverageBefore, jobs.computeCoverage("NOTEBOOK", "space-p5"), 1e-9,
                "记忆检索不能动 NOTEBOOK 既有 coverage 契约（§14 P5 退出条件）");
        assertEquals(chunksBefore, count("retrieval_chunk"), "不能往笔记向量表里写东西");
        assertEquals(ftsBefore, count("retrieval_chunk_fts"), "不创建、也不写入任何 FTS 明文表");
        assertEquals(tablesBefore, tables(), "P5 全程不新增表");
        assertFalse(databaseBytesContain("正在准备雅思考试"), "库里不能出现记忆明文（S12）");
        assertFalse(jdbc.queryForList("SELECT result_meta FROM ai_memory_operation", String.class).toString()
                .contains("正在准备雅思考试"), "幂等台账是明文列，只准存元信息（S12）");
    }

    @Test
    void aResetVaultStartsFromAnEmptyIndexAndNeverShowsTheOldVaultsFacts() throws Exception {
        add("GOAL", "GLOBAL", 0L, "study.goal", "goal-old-vault", "旧库里的备考目标", null);
        rebuild();
        assertTrue(values(select("GLOBAL", 0L, "我在备考什么")).contains("旧库里的备考目标"));

        assertEquals(200, stack.vault().reset(true, "password", "new master phrase", op("reset")).status());
        String newVaultId = revision().vaultId();
        assertFalse(newVaultId.equals(vaultId), "重置换的是新库 id");
        ContextMemorySource.Selection afterReset = source.select(newVaultId, "GLOBAL", 0L, "我在备考什么",
                System.currentTimeMillis(), DEADLINE_MILLIS);
        assertTrue(afterReset.topical().isEmpty() && afterReset.style().isEmpty(),
                "旧库正文不能出现在新库的任何一轮里（S10）");

        awaitSnapshot();
        List<String> injected = values(source.select(newVaultId, "GLOBAL", 0L, "我在备考什么",
                System.currentTimeMillis(), DEADLINE_MILLIS));
        assertFalse(injected.contains("旧库里的备考目标"), "重建后的快照只含新库事实");
    }

    // ---- 辅助 ----

    private void unlock() {
        assertEquals(201, stack.vault().create("password", PASSWORD, op("vault")).status());
        assertEquals(200, stack.vault().unlock(PASSWORD, List.of()).status());
        vaultId = revision().vaultId();
    }

    private MemoryApiResult add(String kind, String scopeType, long scopeId, String predicate, String family,
                                String value, String validUntil) {
        MemoryApiResult result = items.add(new MemoryItemService.AddInput(kind, scopeType, scopeId, predicate,
                family, value, validUntil, false, op("add")));
        assertEquals(201, result.status(), value + " 没能入库: " + result.body());
        return result;
    }

    /** 与后台任务同一份构建实现，只是同步跑：绝大多数用例要的是确定性，不是线程时序。 */
    private MemoryIndexService.Snapshot rebuild() {
        MemoryRepository.VaultEnvelope envelope = revision();
        try {
            return indexes.buildNow(envelope.vaultId(), envelope.epoch(), envelope.revision());
        } catch (SQLException error) {
            throw new IllegalStateException(error);
        }
    }

    private MemoryRepository.VaultEnvelope revision() {
        try {
            return wiring.memory().readEnvelope().orElseThrow();
        } catch (SQLException error) {
            throw new IllegalStateException(error);
        }
    }

    /** 有界等待后台快照，避免用 sleep 猜时间；超时即失败。 */
    private void awaitSnapshot() throws InterruptedException {
        MemoryRepository.VaultEnvelope envelope = revision();
        long deadline = System.currentTimeMillis() + 10_000L;
        while (System.currentTimeMillis() < deadline) {
            if (indexes.ready(envelope.vaultId(), envelope.epoch(), envelope.revision()).isPresent()) {
                return;
            }
            Thread.sleep(5L);
        }
        throw new AssertionError("后台构建没有在 10 秒内产出快照");
    }

    private ContextMemorySource.Selection select(String scopeType, long scopeId, String query) {
        return selectAt(scopeType, scopeId, query, System.currentTimeMillis(), DEADLINE_MILLIS);
    }

    private ContextMemorySource.Selection selectAt(String scopeType, long scopeId, String query, long nowMillis,
                                                   int deadlineMillis) {
        return source.select(vaultId, scopeType, scopeId, query, nowMillis, deadlineMillis);
    }

    private static List<String> values(ContextMemorySource.Selection selection) {
        List<String> values = new ArrayList<>();
        selection.topical().forEach(fact -> values.add(fact.value()));
        selection.style().forEach(fact -> values.add(fact.value()));
        return values;
    }

    private int suppressionCount(String scopeType, long scopeId, String kind) throws SQLException {
        return new MemorySuppressionRepository(dataSource, wiring.immediate())
                .activeKeys(vaultId, scopeType, scopeId, kind).size();
    }

    private void seedNotebookSpace() {
        jdbc.update("INSERT INTO embedding_space(embedding_space_id, canonical_contract_json, provider_type,"
                + " model_identifier, dimensions, state, coverage, is_selected)"
                + " VALUES ('space-p5', '{}', 'LOCAL', 'stub', 4, 'ACTIVE', 1.0, 1)");
        for (int index = 0; index < 4; index++) {
            jdbc.update("INSERT INTO retrieval_chunk(corpus_type, corpus_id, source_id, chunk_index, title, text,"
                    + " start_offset, end_offset, content_hash) VALUES ('NOTEBOOK', 8, ?, ?, '页面', ?, 0, 8, ?)",
                    800 + index, index, "正文片段" + index, "hash-" + index);
            jdbc.update("INSERT INTO retrieval_embedding(chunk_id, corpus_type, embedding_space_id, dimensions,"
                    + " content_hash, vector_blob) SELECT id, 'NOTEBOOK', 'space-p5', 4, ?, X'00' FROM"
                    + " retrieval_chunk WHERE source_id = ?", "hash-" + index, 800 + index);
        }
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    private Set<String> tables() {
        return new LinkedHashSet<>(jdbc.queryForList(
                "SELECT name FROM sqlite_master WHERE type IN ('table','view') ORDER BY name", String.class));
    }

    private boolean databaseBytesContain(String needle) throws java.io.IOException {
        List<Path> files = new ArrayList<>();
        files.add(appRoot.resolve("data").resolve("study.db"));
        Path wal = appRoot.resolve("data").resolve("study.db-wal");
        if (java.nio.file.Files.exists(wal)) {
            files.add(wal);
        }
        StringBuilder text = new StringBuilder();
        for (Path file : files) {
            try {
                text.append(new String(java.nio.file.Files.readAllBytes(file), StandardCharsets.UTF_8));
            } catch (java.nio.file.NoSuchFileException vanished) {
                // WAL 会在 checkpoint 时消失：没这个文件就等于没有未落盘内容，不用为此让用例翻脸
            }
        }
        return text.toString().contains(needle);
    }

    private String op(String action) {
        return "p5-" + action + "-" + (++sequence);
    }
}
