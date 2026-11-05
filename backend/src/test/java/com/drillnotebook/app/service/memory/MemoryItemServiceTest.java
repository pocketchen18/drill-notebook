package com.drillnotebook.app.service.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.drillnotebook.app.config.DatabaseInitializer;
import com.drillnotebook.app.config.ImmediateTransaction;
import com.drillnotebook.app.memory.Schema9Fixture;
import com.drillnotebook.app.memory.TestWorkspace;
import com.drillnotebook.app.repository.AiConfigRepository;
import com.drillnotebook.app.repository.ChatSessionMetadataRepository;
import com.drillnotebook.app.repository.MemoryJobRepository;
import com.drillnotebook.app.repository.MemoryOperationRepository;
import com.drillnotebook.app.repository.MemoryProposalRepository;
import com.drillnotebook.app.repository.MemoryRepository;
import com.drillnotebook.app.repository.MemorySettingsRepository;
import com.drillnotebook.app.repository.MemorySuppressionRepository;
import com.drillnotebook.app.repository.NotebookRepository;
import com.drillnotebook.app.service.ApiKeyEncryptor;
import com.drillnotebook.app.service.memory.MemoryItemService.AddInput;
import com.drillnotebook.app.service.memory.MemoryItemService.EditInput;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 手动管理一条本地加密记忆的完整链路（执行方案 P2 退出条件）：
 * 明文只回到刚提交它的用户手里，落库全是密文与派生键；版本 CAS、停用/启用、分页与本地检索、
 * 删除的遗忘屏障与抑制都由服务层把关，不靠前端禁用按钮。
 */
class MemoryItemServiceTest {
    private static final String VALUE = "以后解释算法先给例子";
    private static final String PREDICATE = "answer.style";

    private Path appRoot;
    private DataSource dataSource;
    private JdbcTemplate jdbc;
    private MemoryCryptoService crypto;
    private MemoryRepository memory;
    private MemoryVaultService vault;
    private MemoryPolicyService policy;
    private MemoryItemService items;
    private String vaultId;
    private long sessionId;
    private long nextTurnId = 700;

    @BeforeEach
    void createDatabase() throws Exception {
        appRoot = TestWorkspace.scratchDirectory("p2-items-");
        dataSource = Schema9Fixture.dataSource(appRoot);
        new DatabaseInitializer(dataSource, Schema9Fixture.resolver(appRoot)).initialize();
        jdbc = new JdbcTemplate(dataSource);
        ImmediateTransaction immediate = new ImmediateTransaction(dataSource);
        crypto = new MemoryCryptoService(new ApiKeyEncryptor());
        memory = new MemoryRepository(dataSource, immediate);
        MemoryOperationRepository operations = new MemoryOperationRepository(dataSource, immediate);
        ChatSessionMetadataRepository metadata = new ChatSessionMetadataRepository(dataSource, immediate);
        vault = new MemoryVaultService(crypto, memory, operations, metadata);
        policy = new MemoryPolicyService(new MemorySettingsRepository(dataSource, immediate), memory,
                new MemoryJobRepository(dataSource, immediate), new AiConfigRepository(jdbc),
                new NotebookRepository(jdbc, new ObjectMapper()), vault);
        items = new MemoryItemService(crypto, memory, vault, policy, operations,
                new MemoryProposalRepository(dataSource, immediate),
                new MemorySuppressionRepository(dataSource, immediate),
                new MemoryJobRepository(dataSource, immediate), metadata);
        sessionId = jdbc.queryForObject("SELECT id FROM ai_chat_session ORDER BY id LIMIT 1", Long.class);
    }

    @AfterEach
    void removeDatabase() throws Exception {
        TestWorkspace.deleteRecursively(appRoot);
    }

    // ---- 新增 ----

    @Test
    void manualAddKeepsOnlyCipherAndKeysInTheDatabase() throws Exception {
        createFingerprintVault();
        MemoryApiResult created = items.add(add(PREDICATE, VALUE));
        assertEquals(201, created.status());
        String memoryId = (String) created.body().get("memoryId");
        assertEquals(VALUE, created.body().get("value"), "刚提交的人当然看得见自己写的正文");
        assertEquals(1, created.body().get("version"));
        assertEquals("ACTIVE", created.body().get("status"));

        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM ai_memory_item WHERE memory_id = ?", memoryId);
        String stored = String.valueOf(row.values());
        assertFalse(stored.contains(VALUE), "表里任何一列都不该有明文");
        assertFalse(stored.contains(PREDICATE));
        assertNotNull(row.get("predicate_key_hmac"));
        assertNotNull(row.get("family_key_hmac"));
        assertEquals("MANUAL", row.get("origin"));
        assertEquals(1, ((Number) row.get("manual_protected")).intValue());

        jdbc.execute("PRAGMA wal_checkpoint(FULL)");
        byte[] db = Files.readAllBytes(appRoot.resolve("data/study.db"));
        Path walPath = appRoot.resolve("data/study.db-wal");
        byte[] wal = Files.exists(walPath) ? Files.readAllBytes(walPath) : new byte[0];
        assertFalse(contains(db, VALUE), "明文字节不得出现在主库文件里");
        assertFalse(contains(wal, VALUE), "明文字节不得出现在 WAL 里");

        MemoryApiResult read = items.get(memoryId);
        assertEquals(200, read.status());
        assertEquals(VALUE, read.body().get("value"));
        assertEquals(List.of(), read.body().get("sources"));
    }

    @Test
    void sameFamilyAddIsRefusedWithMetadataOnlyCandidates() throws Exception {
        createFingerprintVault();
        String memoryId = (String) items.add(add(PREDICATE, VALUE)).body().get("memoryId");

        MemoryApiResult conflict = items.add(add(PREDICATE, "改成先给结论"));
        assertEquals(409, conflict.status());
        assertEquals("MEMORY_FAMILY_CONFLICT", conflict.body().get("code"));
        List<?> candidates = (List<?>) ((Map<?, ?>) conflict.body().get("details")).get("candidates");
        assertEquals(1, candidates.size());
        Map<?, ?> candidate = (Map<?, ?>) candidates.get(0);
        assertEquals(memoryId, candidate.get("memoryId"));
        assertEquals(1, candidate.get("version"));
        assertFalse(String.valueOf(candidates).contains(VALUE), "冲突响应不回别人的正文");
    }

    /**
     * §12 的"手动添加两条学习目标"能并存：族键必须由服务端按 {@code predicate:value} 派生。
     * 早先手动路径只看前端传来的 family，两条不同目标撞在同一个空族键上被 409 挡回，
     * 而自动提取链用的是另一套派生——两条路径口径不同（P9b 的 D99）。
     */
    @Test
    void manualMultiValuedGoalsEachGetTheirOwnFamily() throws Exception {
        createFingerprintVault();
        String firstGoal = (String) items.add(new AddInput("GOAL", "GLOBAL", 0L, "study.goal", null, "雅思 7 分",
                null, null, null)).body().get("memoryId");

        MemoryApiResult second = items.add(new AddInput("GOAL", "GLOBAL", 0L, "study.goal", null, "准备托福",
                null, null, null));
        assertEquals(201, second.status(), String.valueOf(second.body()));
        String otherGoal = (String) second.body().get("memoryId");
        assertEquals(2, count("ai_memory_item"), "多值事实各占一族，谁也不顶掉谁");
        assertFalse(jdbc.queryForObject("SELECT family_key_hmac FROM ai_memory_item WHERE memory_id = ?",
                        String.class, firstGoal)
                .equals(jdbc.queryForObject("SELECT family_key_hmac FROM ai_memory_item WHERE memory_id = ?",
                        String.class, otherGoal)), "族键里没带值，两条目标就成了同族冲突");

        MemoryApiResult duplicate = items.add(new AddInput("GOAL", "GLOBAL", 0L, "study.goal", null, "雅思 7 分",
                null, null, null));
        assertEquals(409, duplicate.status(), "换个值才算另一条，抄一遍还是冲突");
        assertEquals("MEMORY_FAMILY_CONFLICT", duplicate.body().get("code"));
        assertEquals(true, items.get(firstGoal).body().get("multiValued"),
                "候选能不能「分别保留」由服务端这张表判定，界面不许复刻一份谓词清单");
    }

    @Test
    void credentialsAreRefusedEvenWhenTheUserInsists() throws Exception {
        createFingerprintVault();

        MemoryApiResult key = items.add(add("contact.key", "我的 api key 是 sk-abcdefghijklmnopqrstuvwxyz"));
        assertEquals(422, key.status());
        assertEquals("MEMORY_SENSITIVE_CREDENTIAL", key.body().get("code"));
        assertEquals(422, items.add(add("contact.key", "密码：hunter2hunter2")).status());
        assertEquals(422, items.add(add("contact.card", "6222021234567890123")).status());

        MemoryApiResult unconfirmed = items.add(add("contact.email", "我的邮箱是 me@example.com"));
        assertEquals(409, unconfirmed.status());
        assertEquals("MEMORY_SENSITIVE_CONFIRMATION_REQUIRED", unconfirmed.body().get("code"));
        assertEquals(0L, count("ai_memory_item"));

        MemoryApiResult confirmed = items.add(new AddInput("BACKGROUND", "GLOBAL", 0L, "contact.email", null,
                "我的邮箱是 me@example.com", null, true, null));
        assertEquals(201, confirmed.status(), "敏感但用户明确确认的人工条目可以存（§5.3）");
    }

    @Test
    void malformedRequestsAreRejectedBeforeAnyWrite() throws Exception {
        createFingerprintVault();

        assertEquals("MEMORY_KIND_INVALID", items.add(new AddInput("NOTE", "GLOBAL", 0L, "p", null, "v",
                null, null, null)).body().get("code"));
        assertEquals("MEMORY_SCOPE_INVALID", items.add(new AddInput("GOAL", "SPACE", 0L, "p", null, "v",
                null, null, null)).body().get("code"));
        assertEquals("MEMORY_PREDICATE_INVALID", items.add(add("  ", VALUE)).body().get("code"));
        assertEquals("MEMORY_VALUE_INVALID", items.add(add(PREDICATE, "雅".repeat(1001))).body().get("code"));
        assertEquals("MEMORY_EXPIRY_INVALID", items.add(new AddInput("TASK", "GLOBAL", 0L, "p", null, "v",
                "昨天", null, null)).body().get("code"));
        assertEquals("MEMORY_EXPIRY_IN_PAST", items.add(new AddInput("TASK", "GLOBAL", 0L, "p", null, "v",
                Instant.now().minus(1, ChronoUnit.DAYS).toString(), null, null)).body().get("code"));
        assertEquals("MEMORY_SCOPE_INVALID", items.add(new AddInput("GOAL", "NOTEBOOK", 424242L, "p", null, "v",
                null, null, null)).body().get("code"));
        assertEquals(0L, count("ai_memory_item"), "被拒的请求一条都不写");
    }

    @Test
    void lockedVaultRefusesEveryRouteWith423() throws Exception {
        createFingerprintVault();
        String memoryId = (String) items.add(add(PREDICATE, VALUE)).body().get("memoryId");
        vault.lock();

        MemoryApiResult refusedAdd = items.add(add("answer.length", "短一点"));
        assertEquals(423, refusedAdd.status());
        assertEquals("MEMORY_LOCKED", refusedAdd.body().get("code"));
        assertEquals(423, items.get(memoryId).status());
        assertEquals(423, items.list(null, null, null, null, null, null).status());
        assertEquals(423, items.edit(memoryId, new EditInput(1, null, null, "长一点", null, null)).status());
        assertEquals(423, items.delete(memoryId, 1, null).status());
    }

    // ---- 改与停用 ----

    @Test
    void editNeedsTheVersionItSawAndReturnsOnlyMetadataOnConflict() throws Exception {
        createFingerprintVault();
        String memoryId = (String) items.add(add(PREDICATE, VALUE)).body().get("memoryId");

        MemoryApiResult missingVersion = items.edit(memoryId, new EditInput(null, null, null, "长一点", null, null));
        assertEquals(400, missingVersion.status());
        assertEquals("MEMORY_VERSION_REQUIRED", missingVersion.body().get("code"));

        MemoryApiResult conflict = items.edit(memoryId, new EditInput(9, null, null, "长一点", null, null));
        assertEquals(409, conflict.status());
        assertEquals("MEMORY_VERSION_CONFLICT", conflict.body().get("code"));
        assertEquals(1, ((Map<?, ?>) conflict.body().get("details")).get("version"));
        assertFalse(String.valueOf(conflict.body()).contains(VALUE), "冲突响应不回正文");
        assertEquals(400, items.edit(memoryId, new EditInput(1, null, null, null, null, null)).status());
    }

    @Test
    void contentEditCreatesTheNextVersionAndKeepsFamilyAndObservation() throws Exception {
        createFingerprintVault();
        String memoryId = (String) items.add(add(PREDICATE, VALUE)).body().get("memoryId");

        MemoryApiResult edited = items.edit(memoryId, new EditInput(1, null, null, "先给反例再给正例", null, null));
        assertEquals(200, edited.status());
        assertEquals(2, edited.body().get("version"));
        assertEquals("先给反例再给正例", edited.body().get("value"));
        assertEquals(PREDICATE, edited.body().get("predicate"), "没改谓词就沿用原来的");

        List<Map<String, Object>> rows = jdbc.queryForList("SELECT version, status, family_key_hmac,"
                + " predicate_key_hmac, observed_at FROM ai_memory_item WHERE memory_id = ? ORDER BY version",
                memoryId);
        assertEquals(2, rows.size());
        assertEquals("SUPERSEDED", rows.get(0).get("status"));
        assertEquals("ACTIVE", rows.get(1).get("status"));
        assertEquals(rows.get(0).get("family_key_hmac"), rows.get(1).get("family_key_hmac"));
        assertEquals(rows.get(0).get("predicate_key_hmac"), rows.get(1).get("predicate_key_hmac"));
        assertEquals(rows.get(0).get("observed_at"), rows.get(1).get("observed_at"), "来源时间跟着事实走，不改成现在");

        assertEquals("MEMORY_FAMILY_CONFLICT", items.add(add(PREDICATE, "又一条同族")).body().get("code"));
    }

    @Test
    void disableAndReEnableShareOneVersionCas() throws Exception {
        createFingerprintVault();
        String memoryId = (String) items.add(add(PREDICATE, VALUE)).body().get("memoryId");

        assertEquals("DISABLED", items.edit(memoryId, new EditInput(1, null, null, null, null, "DISABLED"))
                .body().get("status"));
        assertEquals(200, items.get(memoryId).status(), "停用后仍能打开详情");
        assertEquals("DISABLED", items.get(memoryId).body().get("status"));
        assertEquals(200, items.edit(memoryId, new EditInput(1, null, null, null, null, "disabled")).status(),
                "模式大小写不影响判定");
        assertEquals(false, items.edit(memoryId, new EditInput(1, null, null, null, null, "DISABLED"))
                .body().get("changed"), "重复停用是幂等无变化");

        assertEquals("ACTIVE", items.edit(memoryId, new EditInput(1, null, null, null, null, "ACTIVE"))
                .body().get("status"));
        assertEquals(200, items.get(memoryId).status());
        assertEquals(400, items.edit(memoryId, new EditInput(1, null, null, null, null, "EXPIRED")).status());
        assertEquals(1L, count("ai_memory_item"), "停用与启用都不造新版");
    }

    @Test
    void disabledMemoryCanBeRenamedInPlaceWithoutCreatingASecondMemory() throws Exception {
        createFingerprintVault();
        String memoryId = (String) items.add(add(PREDICATE, VALUE)).body().get("memoryId");
        items.edit(memoryId, new EditInput(1, null, null, null, null, "DISABLED"));

        MemoryApiResult renamed = items.edit(memoryId, new EditInput(1, "新名字", null, null, null, null));
        assertEquals(200, renamed.status());
        assertEquals("新名字", renamed.body().get("predicate"));
        assertEquals("DISABLED", renamed.body().get("status"));
        assertEquals(2, renamed.body().get("version"));
        assertEquals(memoryId, renamed.body().get("memoryId"));

        List<?> listed = (List<?>) items.list(null, null, "ALL", null, 10, null).body().get("items");
        assertEquals(1, listed.size(), "列表只显示当前版本，不把历史版本当第二条记忆");
        assertEquals(memoryId, ((Map<?, ?>) listed.get(0)).get("memoryId"));
        assertEquals(2, ((Map<?, ?>) listed.get(0)).get("version"));
        assertEquals("DISABLED", ((Map<?, ?>) listed.get(0)).get("status"));
    }

    @Test
    void validUntilCanBeSetAndClearedWithoutChangingContent() throws Exception {
        createFingerprintVault();
        String memoryId = (String) items.add(add(PREDICATE, VALUE)).body().get("memoryId");
        String future = Instant.now().plus(30, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS).toString();

        MemoryApiResult set = items.edit(memoryId, new EditInput(1, null, null, null, future, null));
        assertEquals(200, set.status());
        assertEquals(future, set.body().get("validUntil"));
        assertEquals(2, set.body().get("version"));

        MemoryApiResult cleared = items.edit(memoryId, new EditInput(2, null, null, null, "", null));
        assertEquals(200, cleared.status());
        assertNull(cleared.body().get("validUntil"), "空字符串表示清除有效期");
        assertEquals(3, cleared.body().get("version"));
        assertEquals(memoryId, cleared.body().get("memoryId"));
    }

    @Test
    void metadataOnlyEditKeepsAutoOriginButContentEditClaimsIt() throws Exception {
        createFingerprintVault();
        String memoryId = (String) items.add(add(PREDICATE, VALUE)).body().get("memoryId");
        jdbc.update("UPDATE ai_memory_item SET origin = 'AUTO', manual_protected = 0 WHERE memory_id = ?",
                memoryId);
        long turn = insertTurn();
        memory.attachEvidence(new MemoryRepository.EvidenceDraft("ev-meta", vaultId, sessionId, turn, null, "b1",
                "src-meta", "密文原话", "{}", MemoryTimes.now()), currentRowId(memoryId));
        String future = Instant.now().plus(30, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS).toString();

        MemoryApiResult expiry = items.edit(memoryId, new EditInput(1, null, null, null, future, null));
        assertEquals(200, expiry.status());
        assertEquals("AUTO", expiry.body().get("origin"), "只改有效期不该把自动来源改成人工");
        assertEquals(false, expiry.body().get("manualProtected"), "只改有效期也不该顺手锁成人工保护");
        assertEquals(1, ((List<?>) items.get(memoryId).body().get("sources")).size(), "改有效期不能丢来源");

        MemoryApiResult renamed = items.edit(memoryId, new EditInput(2, "新名字", null, null, null, null));
        assertEquals(200, renamed.status());
        assertEquals("AUTO", renamed.body().get("origin"), "只改名称不该把自动来源改成人工");
        assertEquals(false, renamed.body().get("manualProtected"));
        assertEquals(1, ((List<?>) items.get(memoryId).body().get("sources")).size(), "改名称不能丢来源");

        MemoryApiResult rewritten = items.edit(memoryId, new EditInput(3, null, null, "新内容", null, null));
        assertEquals(200, rewritten.status(), String.valueOf(rewritten.body()));
        assertEquals("MANUAL", rewritten.body().get("origin"), "改内容才转成人工来源");
        assertEquals(true, rewritten.body().get("manualProtected"));
        assertEquals(1, ((List<?>) items.get(memoryId).body().get("sources")).size(), "改内容也保留来源");
    }

    @Test
    void corruptedRowIsIsolatedInsteadOfBreakingTheList() throws Exception {
        createFingerprintVault();
        String good = (String) items.add(add("answer.length", "回答短一点")).body().get("memoryId");
        String broken = (String) items.add(add(PREDICATE, VALUE)).body().get("memoryId");
        String rowId = currentRowId(broken);
        jdbc.update("UPDATE ai_memory_item SET payload_cipher = 'AAAA' WHERE row_id = ?", rowId);

        MemoryApiResult read = items.get(broken);
        assertEquals(200, read.status(), "坏一行不该让整个读取失败");
        assertEquals(true, read.body().get("corrupt"));
        assertNull(read.body().get("value"));

        List<?> listed = (List<?>) items.list(null, null, null, null, null, null).body().get("items");
        assertEquals(2, listed.size());
        assertTrue(listed.stream().anyMatch(entry -> Boolean.TRUE.equals(((Map<?, ?>) entry).get("corrupt"))));
        assertEquals("回答短一点", items.get(good).body().get("value"));
    }

    // ---- 列表与检索 ----

    @Test
    void listPagesByKeysetAndSearchesDecryptedText() throws Exception {
        createFingerprintVault();
        String first = (String) items.add(add("answer.style", VALUE)).body().get("memoryId");
        items.add(add("goal.language", "我在准备雅思"));
        items.add(add("background.course", "每周两次课"));

        MemoryApiResult all = items.list(null, null, null, null, null, null);
        assertEquals(200, all.status());
        assertEquals(3, ((List<?>) all.body().get("items")).size());
        assertEquals(false, all.body().get("hasMore"));
        assertNotNull(all.body().get("nextCursor"));

        MemoryApiResult page = items.list(null, null, null, null, 2, null);
        assertEquals(2, ((List<?>) page.body().get("items")).size());
        assertEquals(true, page.body().get("hasMore"));
        MemoryApiResult next = items.list(null, null, null, null, 2, (String) page.body().get("nextCursor"));
        assertEquals(1, ((List<?>) next.body().get("items")).size(), "第二页拿到剩下的那条，不重复也不丢");
        assertEquals(false, next.body().get("hasMore"));

        List<?> matched = (List<?>) items.list(null, null, null, "雅思", 10, null).body().get("items");
        assertEquals(1, matched.size());
        assertEquals("我在准备雅思", ((Map<?, ?>) matched.get(0)).get("value"));
        assertEquals(1, ((List<?>) items.list("GLOBAL", 0L, null, "雅思", 10, null).body().get("items")).size());
        assertEquals(0, ((List<?>) items.list("NOTEBOOK", 424242L, "ACTIVE", null, 10, null)
                .body().get("items")).size(), "没有这条作用域就不返回别人的内容");
        assertEquals(50, items.list(null, null, null, null, 500, null).body().get("limit"), "每页最多 50");

        assertEquals(400, items.list("SPACE", null, null, null, null, null).status());
        assertEquals(400, items.list(null, null, "DONE", null, null, null).status());
        assertEquals(400, items.list(null, null, null, null, null, "不是游标").status());
        assertEquals(400, items.list("NOTEBOOK", null, "ACTIVE", null, 10, null).status());

        items.edit(first, new EditInput(1, null, null, null, null, "DISABLED"));
        List<?> disabled = (List<?>) items.list(null, null, "DISABLED", null, 10, null).body().get("items");
        assertEquals(1, disabled.size());
        assertEquals(first, ((Map<?, ?>) disabled.get(0)).get("memoryId"));
        List<?> active = (List<?>) items.list(null, null, null, null, 10, null).body().get("items");
        assertEquals(2, active.size());

        MemoryApiResult everything = items.list(null, null, "ALL", null, 10, null);
        assertEquals(200, everything.status());
        assertEquals("ALL", everything.body().get("status"));
        assertEquals(3, ((List<?>) everything.body().get("items")).size(),
                "ALL 不按状态过滤，停用的那条也要出现");
    }

    // ---- 删除 ----

    @Test
    void deleteWipesTheChainWithoutMovingScanBoundaries() throws Exception {
        createFingerprintVault();
        String memoryId = (String) items.add(add(PREDICATE, VALUE)).body().get("memoryId");
        long turn = insertTurn();
        memory.attachEvidence(new MemoryRepository.EvidenceDraft("ev-del", vaultId, sessionId, turn, null, "b1",
                "src-del", "密文原话", "{}", MemoryTimes.now()), currentRowId(memoryId));
        insertProposal("prop-del", memoryId);
        MemoryJobRepository jobs = new MemoryJobRepository(dataSource, new ImmediateTransaction(dataSource));
        String jobId = jobs.enqueue(new MemoryJobRepository.JobDraft(MemoryJobRepository.Kind.EXTRACT, sessionId,
                1, turn, 1, "GLOBAL", 0, "UTC", 1, 1, "fp", "extract-v1", "input-1", MemoryTimes.now())).jobId();
        jdbc.update("INSERT INTO ai_session_context(session_id, revision, through_turn_id, context_generation,"
                        + " vault_epoch, payload_cipher, crypto_meta, estimated_tokens, updated_at)"
                        + " VALUES (?, 1, ?, 1, 1, 'ctx', '{}', 20, ?)", sessionId, turn, MemoryTimes.now());
        long epochBefore = memory.readVault(vaultId).orElseThrow().epoch();
        jdbc.update("INSERT INTO ai_chat_message(session_id, role, content) VALUES (?, 'user', ?)",
                sessionId, "原始用户消息");

        MemoryApiResult deleted = items.delete(memoryId, 1, "op-del-1");
        assertEquals(204, deleted.status());
        assertTrue(deleted.body().isEmpty(), "204 不带正文");

        assertEquals(0L, count("ai_memory_item"), "整条版本链都删掉");
        assertEquals(0L, count("ai_memory_evidence"), "失去引用的证据一起删");
        assertEquals(0L, count("ai_memory_item_evidence"));
        assertEquals("STALE", jdbc.queryForObject("SELECT status FROM ai_memory_proposal", String.class),
                "指向被删记忆的建议置 STALE，不留在 PENDING");
        assertEquals(3L, count("ai_memory_suppression"), "family + predicate + 来源");
        assertEquals(epochBefore + 1, memory.readVault(vaultId).orElseThrow().epoch(), "删除推 epoch");
        assertEquals("SUPERSEDED", jdbc.queryForObject("SELECT status FROM ai_memory_job WHERE id = ?",
                String.class, jobId));
        assertEquals(0L, count("ai_session_context"), "旧 epoch 的摘要检查点作废");
        assertEquals(0L, scanWatermark("memory_scan_after_turn"), "删除不动会话扫描水位");
        assertEquals(0L, scanWatermark("summary_scan_after_turn"));
        assertEquals(0L, policy.policyOf(policy.current()).memoryHistoryFloor(), "删除不动全局边界");
        assertEquals(1L, count("ai_chat_message"), "只删记忆，不动原会话消息");
        assertEquals(1L, count("ai_chat_turn"), "原会话轮次仍可读");
        assertEquals(1L, count("ai_chat_session"), "原会话本身仍保留");

        assertEquals(200, items.list(null, null, null, null, null, null).status(), "删空后列表仍可用");
    }

    @Test
    void detailSourceUsesTheSessionTitleInsteadOfRawIds() throws Exception {
        createFingerprintVault();
        String memoryId = (String) items.add(add(PREDICATE, VALUE)).body().get("memoryId");
        long turn = insertTurn();
        jdbc.update("UPDATE ai_chat_session SET title = ? WHERE id = ?", "杭州生活", sessionId);
        memory.attachEvidence(new MemoryRepository.EvidenceDraft("ev-title", vaultId, sessionId, turn, null, "b1",
                "src-title", "cipher", "{}", MemoryTimes.now()), currentRowId(memoryId));

        List<?> sources = (List<?>) items.get(memoryId).body().get("sources");
        assertEquals(1, sources.size());
        assertEquals("杭州生活", ((Map<?, ?>) sources.get(0)).get("sessionTitle"));
    }

    @Test
    void deleteIsIdempotentAndReplaysItsOperationId() throws Exception {
        createFingerprintVault();
        String memoryId = (String) items.add(add(PREDICATE, VALUE)).body().get("memoryId");

        assertEquals(204, items.delete("mem-从未存在", 1, null).status(), "不存在按幂等 204");

        assertEquals(204, items.delete(memoryId, 1, "op-del-2").status());
        long epochAfterDelete = memory.readVault(vaultId).orElseThrow().epoch();
        assertEquals(204, items.delete(memoryId, 1, "op-del-2").status());
        assertEquals(epochAfterDelete, memory.readVault(vaultId).orElseThrow().epoch(), "重放不再推一次 epoch");

        String second = (String) items.add(add("goal.language", "我在准备雅思")).body().get("memoryId");
        assertEquals(409, items.delete(second, 99, null).status());
        assertEquals(1L, count("ai_memory_item"), "版本冲突时一条都不删");
        assertEquals(400, items.delete(second, null, null).status());
    }

    // ---- 状态计数 ----

    @Test
    void statusReportsCountsAndPauseReasonWithoutText() throws Exception {
        createFingerprintVault();
        items.add(add(PREDICATE, VALUE));

        MemoryApiResult status = items.status();
        assertEquals(200, status.status());
        assertEquals(1L, status.body().get("activeFacts"));
        assertEquals(0L, status.body().get("pendingFacts"));
        assertEquals(2000, ((Map<?, ?>) status.body().get("limits")).get("activeFacts"));
        assertEquals(Boolean.TRUE, status.body().get("unlocked"));
        MemoryPolicyService.Eligibility extraction = (MemoryPolicyService.Eligibility) status.body().get("extraction");
        assertEquals(MemoryPolicyService.Gate.MEMORY_DISABLED, extraction.gate(), "总开关先拦，暂停原因要看得见");
        assertFalse(String.valueOf(status.body()).contains(VALUE), "状态响应不含正文");
    }

    // ---- helpers ----

    private void createFingerprintVault() throws SQLException {
        assertEquals(201, vault.create("fingerprint", null, null).status());
        vaultId = memory.readEnvelope().orElseThrow().vaultId();
    }

    private static AddInput add(String predicate, String value) {
        return new AddInput("PREFERENCE", "GLOBAL", 0L, predicate, null, value, null, null, null);
    }

    private String currentRowId(String memoryId) {
        return jdbc.queryForObject("SELECT row_id FROM ai_memory_item WHERE memory_id = ? AND status = 'ACTIVE'",
                String.class, memoryId);
    }

    private long insertTurn() {
        long id = nextTurnId++;
        jdbc.update("INSERT INTO ai_chat_turn(id, request_id, session_id, status, request_meta_cipher,"
                        + " request_meta_crypto, context_generation, source_scope_type, source_scope_id,"
                        + " source_timezone, runtime_generation, started_at)"
                        + " VALUES (?, ?, ?, 'COMPLETED', 'm', '{}', 1, 'GLOBAL', 0, 'UTC', 'gen-test', ?)",
                id, "req-" + id, sessionId, MemoryTimes.now());
        return id;
    }

    private void insertProposal(String id, String targetMemoryId) {
        jdbc.update("INSERT INTO ai_memory_proposal(id, vault_id, session_id, target_memory_id, target_version,"
                        + " operation, status, payload_cipher, crypto_meta, dedup_hmac, created_at, expires_at)"
                        + " VALUES (?, ?, ?, ?, 1, 'REPLACE_PROPOSAL', 'PENDING', 'p', '{}', ?, ?, ?)",
                id, vaultId, sessionId, targetMemoryId, "dedup-" + id, MemoryTimes.now(), MemoryTimes.now());
    }

    private long scanWatermark(String column) {
        return jdbc.queryForObject("SELECT " + column + " FROM ai_chat_session WHERE id = ?", Long.class, sessionId);
    }

    private long count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
    }

    private static boolean contains(byte[] haystack, String needle) {
        byte[] target = needle.getBytes(StandardCharsets.UTF_8);
        outer:
        for (int start = 0; start + target.length <= haystack.length; start++) {
            for (int offset = 0; offset < target.length; offset++) {
                if (haystack[start + offset] != target[offset]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }
}
