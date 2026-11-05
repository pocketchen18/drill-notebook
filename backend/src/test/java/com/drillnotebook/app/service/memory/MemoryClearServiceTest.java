package com.drillnotebook.app.service.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 清空全部 AI 记忆（执行方案 §11.3、contracts §3）：先停用学习与使用、先立遗忘边界，再分批删行；
 * 没删完必须回 202 而不是假 204；每个会话的扫描水位要推到头，重新启用不得回填旧历史。
 */
class MemoryClearServiceTest {
    private static final String PREDICATE = "answer.detail";
    private static final String OTHER = "study.goal";

    private Path appRoot;
    private DataSource dataSource;
    private JdbcTemplate jdbc;
    private MemoryRepository memory;
    private MemoryVaultService vault;
    private MemoryPolicyService policy;
    private MemoryItemService items;
    private MemoryReviewService reviews;
    private MemoryClearService clears;
    private ChatSessionMetadataRepository metadata;
    private String vaultId;
    private long sessionId;

    @BeforeEach
    void createDatabase() throws Exception {
        appRoot = TestWorkspace.scratchDirectory("p9b-clear-");
        dataSource = Schema9Fixture.dataSource(appRoot);
        new DatabaseInitializer(dataSource, Schema9Fixture.resolver(appRoot)).initialize();
        jdbc = new JdbcTemplate(dataSource);
        ImmediateTransaction immediate = new ImmediateTransaction(dataSource);
        MemoryCryptoService crypto = new MemoryCryptoService(new ApiKeyEncryptor());
        memory = new MemoryRepository(dataSource, immediate);
        MemoryOperationRepository operations = new MemoryOperationRepository(dataSource, immediate);
        metadata = new ChatSessionMetadataRepository(dataSource, immediate);
        vault = new MemoryVaultService(crypto, memory, operations, metadata);
        ObjectMapper mapper = new ObjectMapper();
        policy = new MemoryPolicyService(new MemorySettingsRepository(dataSource, immediate), memory,
                new MemoryJobRepository(dataSource, immediate), new AiConfigRepository(jdbc),
                new NotebookRepository(jdbc, mapper), vault);
        MemoryProposalRepository proposals = new MemoryProposalRepository(dataSource, immediate);
        items = new MemoryItemService(crypto, memory, vault, policy, operations, proposals,
                new MemorySuppressionRepository(dataSource, immediate),
                new MemoryJobRepository(dataSource, immediate), metadata);
        reviews = new MemoryReviewService(crypto, memory, proposals,
                new MemorySuppressionRepository(dataSource, immediate), operations, vault, policy,
                new MemoryJobRepository(dataSource, immediate), immediate, mapper);
        clears = new MemoryClearService(memory, policy, metadata, operations, mapper);
        sessionId = jdbc.queryForObject("SELECT id FROM ai_chat_session ORDER BY id LIMIT 1", Long.class);
    }

    @AfterEach
    void cleanup() throws Exception {
        TestWorkspace.deleteRecursively(appRoot);
    }

    @Test
    void confirmationAndEpochAreBothMandatory() throws Exception {
        createVault();
        seedEverything();

        assertEquals("MEMORY_CLEAR_CONFIRMATION_REQUIRED", clears.clear(false, epoch(), null).body().get("code"));
        assertEquals(409, clears.clear(false, epoch(), null).status(), "清空是破坏性动作，缺确认不得放行");
        assertEquals("MEMORY_EPOCH_REQUIRED", clears.clear(true, null, null).body().get("code"));
        MemoryApiResult stale = clears.clear(true, epoch() + 5, null);
        assertEquals("MEMORY_EPOCH_CHANGED", stale.body().get("code"));
        assertEquals(epoch(), ((Map<?, ?>) stale.body().get("details")).get("epoch"),
                "409 要带上现在的 epoch，界面才能提示重载");
        assertTrue(count("ai_memory_item") > 0, "以上校验都发生在动手之前");
    }

    @Test
    void clearWipesEveryDerivedTableAndRotatesTheEpoch() throws Exception {
        createVault();
        seedEverything();
        long before = epoch();
        long revisionBefore = revision();

        MemoryApiResult cleared = clears.clear(true, before, null);

        assertEquals(200, cleared.status(), String.valueOf(cleared.body()));
        assertEquals(true, cleared.body().get("cleared"));
        assertEquals(before + 1, cleared.body().get("epoch"), "遗忘边界与全库清空是同一次推进");
        assertTrue((long) cleared.body().get("revision") > revisionBefore, "内容变了要推 revision");
        assertEquals(0, count("ai_memory_item"));
        assertEquals(0, count("ai_memory_evidence"));
        assertEquals(0, count("ai_memory_item_evidence"));
        assertEquals(0, count("ai_memory_proposal"));
        assertEquals(0, count("ai_memory_suppression"));
        assertEquals(0, count("ai_memory_job"));
        assertEquals(0, count("ai_session_context"), "摘要检查点也是这个库封的，留着就是垃圾");
        assertEquals(0, count("ai_memory_vault") - 1, "库行本身不删：换 epoch 不换 DEK");
        assertEquals(0, ((java.util.List<?>) items.list(null, null, null, null, null, null).body().get("items")).size(),
                "清空后管理页列表读不到任何旧内容");
        assertEquals(0, ((java.util.List<?>) reviews.list(null, null, null, null).body().get("proposals")).size());
    }

    @Test
    void clearTurnsOffUseAndLearningAndFloorsEverySession() throws Exception {
        createVault();
        enableBoth();
        seedEverything();
        jdbc.update("UPDATE ai_chat_session SET memory_scan_after_turn = 0, summary_scan_after_turn = 0");
        long newestTurn = jdbc.queryForObject("SELECT COALESCE(MAX(id), 0) FROM ai_chat_turn", Long.class);

        assertEquals(200, clears.clear(true, epoch(), null).status());

        Map<String, Object> settings = policy.snapshot().body();
        assertEquals(false, settings.get("enabled"), "§11.3：清空后默认不使用记忆");
        assertEquals(false, settings.get("autoExtract"), "§11.3：清空后默认不自动学习");
        assertEquals(newestTurn, scanWatermark("memory_scan_after_turn"), "重新启用只学新消息，不回填旧历史");
        assertEquals(newestTurn, scanWatermark("summary_scan_after_turn"), "摘要扫描同样从清空点开始");
    }

    @Test
    void unfinishedClearSaysTwentyTwoAndFinishesOnTheNextPass() throws Exception {
        createVault();
        seedEverything();
        long before = epoch();

        MemoryApiResult partial = clears.clear(true, before, null, 1);

        assertEquals(202, partial.status(), "§3：清理未完要回 202，不能用 204 假装删完：" + partial.body());
        assertEquals(false, partial.body().get("cleared"));
        assertEquals(true, partial.body().get("remaining"));
        assertEquals(before + 1, partial.body().get("epoch"), "边界先立，读路径已经取不到旧内容");
        assertTrue(count("ai_memory_item") > 0, "预算用完就停，剩下的交给下一趟");

        MemoryApiResult finished = clears.clear(true, ((Number) partial.body().get("epoch")).longValue(), null);
        assertEquals(200, finished.status(), String.valueOf(finished.body()));
        assertEquals(true, finished.body().get("cleared"));
        assertEquals(0, count("ai_memory_item"));
        assertEquals(epoch(), finished.body().get("epoch"),
                "响应里的 epoch 必须就是现在的边界，客户端才能拿着它续接下一趟");
        assertTrue((long) finished.body().get("epoch") > before, "一趟请求就是一道遗忘边界，允许连续推进");
    }

    @Test
    void theSameOperationIdReplaysWithItsOriginalStatusCode() throws Exception {
        createVault();
        seedEverything();
        long before = epoch();

        MemoryApiResult partial = clears.clear(true, before, "op-clear-1", 1);
        MemoryApiResult replay = clears.clear(true, before, "op-clear-1", 1);

        assertEquals(202, partial.status());
        assertEquals(202, replay.status(), "重放连当初的状态码一起还回去，不能报成已清完；"
                + "客户端重试时手里还是旧 epoch，也不能被 409 顶回来");
        assertEquals(true, replay.body().get("replayed"));
        assertEquals("MEMORY_EPOCH_CHANGED", clears.clear(true, before, null).body().get("code"),
                "不带 operationId 的第二次请求看的是现在的 epoch，边界已经推走就该 409 让人重载");
    }

    @Test
    void clearWorksWhileTheVaultIsLockedAndNeedsNoKey() throws Exception {
        createVault();
        seedEverything();
        long before = epoch();
        assertEquals(204, vault.lock().status());

        MemoryApiResult cleared = clears.clear(true, before, null);

        assertEquals(200, cleared.status(), String.valueOf(cleared.body()));
        assertEquals(0, count("ai_memory_item"), "删行不需要解密，锁定状态也能清空——这是用户要抹掉本机记忆的时刻");
        assertEquals(MemoryVaultService.HTTP_LOCKED, reviews.list(null, null, null, null).status());
    }

    // ---- 夹具 ----

    private void createVault() throws SQLException {
        assertEquals(201, vault.create("fingerprint", null, null).status());
        vaultId = memory.readEnvelope().orElseThrow().vaultId();
    }

    private void enableBoth() throws SQLException {
        long revision = ((Number) policy.snapshot().body().get("revision")).longValue();
        assertEquals(200, policy.update(revision, true, true, null, null, null).status());
    }

    /** 每张表都放点行：真路径造事实，其余直接落库（清空只看行数，不碰密文）。 */
    private void seedEverything() throws Exception {
        assertEquals(201, items.add(new AddInput("PREFERENCE", "GLOBAL", 0L, PREDICATE, null, "concise", null, null,
                null)).status());
        assertEquals(201, items.add(new AddInput("GOAL", "GLOBAL", 0L, OTHER, null, "准备雅思", null, null, null))
                .status());
        jdbc.update("INSERT INTO ai_memory_evidence(id, vault_id, session_id, source_block_id, source_hmac,"
                + " quote_cipher, crypto_meta, source_created_at) VALUES ('ev-1', ?, ?, 'u0', 'hmac-1', 'q', '{}',"
                + " ?)", vaultId, sessionId, MemoryTimes.now());
        jdbc.update("INSERT INTO ai_memory_item_evidence(item_row_id, evidence_id)"
                + " SELECT row_id, 'ev-1' FROM ai_memory_item WHERE version = 1");
        jdbc.update("INSERT INTO ai_memory_proposal(id, vault_id, session_id, target_memory_id, target_version,"
                + " operation, status, payload_cipher, crypto_meta, dedup_hmac, created_at, expires_at)"
                + " VALUES ('prop-1', ?, ?, 'mem-1', 1, 'REPLACE_PROPOSAL', 'PENDING', 'p', '{}', 'dedup-1', ?, ?)",
                vaultId, sessionId, MemoryTimes.now(), "2099-01-01T00:00:00.000Z");
        jdbc.update("INSERT INTO ai_memory_suppression(vault_id, scope_type, scope_id, suppression_kind, key_hmac,"
                + " created_at) VALUES (?, 'GLOBAL', 0, 'EVIDENCE', 'hmac-1', ?)", vaultId, MemoryTimes.now());
        jdbc.update("INSERT INTO ai_memory_job(id, kind, session_id, from_turn_id, through_turn_id,"
                + " context_generation, source_scope_type, source_scope_id, source_timezone, vault_epoch,"
                + " policy_revision, target_fingerprint, prompt_version, input_revision, status, created_at,"
                + " updated_at) VALUES ('job-1', 'EXTRACT', ?, 1, 2, 1, 'GLOBAL', 0, 'UTC', 1, 1, 'fp', 'v1',"
                + " 'ir-1', 'QUEUED', ?, ?)", sessionId, MemoryTimes.now(), MemoryTimes.now());
        jdbc.update("INSERT INTO ai_session_context(session_id, revision, through_turn_id, context_generation,"
                + " vault_epoch, payload_cipher, crypto_meta, estimated_tokens, updated_at)"
                + " VALUES (?, 1, 2, 1, 1, 'c', '{}', 10, ?)", sessionId, MemoryTimes.now());
    }

    private long epoch() throws SQLException {
        return memory.readVault(vaultId).orElseThrow().epoch();
    }

    private long revision() throws SQLException {
        return memory.readVault(vaultId).orElseThrow().revision();
    }

    private long scanWatermark(String column) {
        return jdbc.queryForObject("SELECT " + column + " FROM ai_chat_session WHERE id = ?", Long.class, sessionId);
    }

    private long count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
    }
}
