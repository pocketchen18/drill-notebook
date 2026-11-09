package com.drillnotebook.app.service.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
import com.drillnotebook.app.repository.MemoryProposalRepository.ProposalDraft;
import com.drillnotebook.app.repository.MemoryProposalRepository.ProposalRow;
import com.drillnotebook.app.repository.MemoryRepository;
import com.drillnotebook.app.repository.MemoryRepository.EvidenceDraft;
import com.drillnotebook.app.repository.MemorySettingsRepository;
import com.drillnotebook.app.repository.MemorySuppressionRepository;
import com.drillnotebook.app.repository.NotebookRepository;
import com.drillnotebook.app.service.ApiKeyEncryptor;
import com.drillnotebook.app.service.memory.MemoryCryptoService.EncryptedValue;
import com.drillnotebook.app.service.memory.MemoryCryptoService.MemoryKey;
import com.drillnotebook.app.service.memory.MemoryItemService.AddInput;
import com.drillnotebook.app.service.memory.MemoryItemService.EditInput;
import com.drillnotebook.app.service.memory.MemoryReviewService.ItemReviewInput;
import com.drillnotebook.app.service.memory.MemoryReviewService.ReviewInput;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 建议审阅事务（contracts §4.4、执行方案 §12 的三按钮）：采纳与并存都只走人工保护那套身份，
 * 拒绝不动事实只抑制来源；任何一条前置判据不过就整笔不动，不留半成品版本。
 */
class MemoryReviewServiceTest {
    private static final String DETAIL = "answer.detail";
    private static final String GOAL = "study.goal";
    private static final String OLD_VALUE = "detailed";
    private static final String NEW_VALUE = "concise";
    private static final String QUOTE = "以后简短点讲就行";

    private Path appRoot;
    private DataSource dataSource;
    private JdbcTemplate jdbc;
    private MemoryCryptoService crypto;
    private MemoryRepository memory;
    private MemoryVaultService vault;
    private MemoryItemService items;
    private MemoryProposalRepository proposals;
    private MemorySuppressionRepository suppressions;
    private MemoryJobRepository jobs;
    private MemoryReviewService reviews;
    private final ObjectMapper mapper = new ObjectMapper();
    private String vaultId;
    private long sessionId;
    private long nextTurnId = 900;

    @BeforeEach
    void createDatabase() throws Exception {
        appRoot = TestWorkspace.scratchDirectory("p9b-review-");
        dataSource = Schema9Fixture.dataSource(appRoot);
        new DatabaseInitializer(dataSource, Schema9Fixture.resolver(appRoot)).initialize();
        jdbc = new JdbcTemplate(dataSource);
        ImmediateTransaction immediate = new ImmediateTransaction(dataSource);
        crypto = new MemoryCryptoService(new ApiKeyEncryptor());
        memory = new MemoryRepository(dataSource, immediate);
        MemoryOperationRepository operations = new MemoryOperationRepository(dataSource, immediate);
        ChatSessionMetadataRepository metadata = new ChatSessionMetadataRepository(dataSource, immediate);
        vault = new MemoryVaultService(crypto, memory, operations, metadata);
        // 三个服务共用同一份任务仓储：撤销采纳的提交后 barrier 要在真仓储上作废任务，不能各造各的
        jobs = new MemoryJobRepository(dataSource, immediate);
        MemoryPolicyService policy = new MemoryPolicyService(new MemorySettingsRepository(dataSource, immediate),
                memory, jobs, new AiConfigRepository(jdbc), new NotebookRepository(jdbc, mapper), vault);
        proposals = new MemoryProposalRepository(dataSource, immediate);
        suppressions = new MemorySuppressionRepository(dataSource, immediate);
        items = new MemoryItemService(crypto, memory, vault, policy, operations, proposals, suppressions, jobs,
                metadata);
        reviews = new MemoryReviewService(crypto, memory, proposals, suppressions, operations, vault, policy, jobs,
                immediate, mapper);
        sessionId = com.drillnotebook.app.repository.ChatLegacyWiring.ensureSession(jdbc);
    }

    @AfterEach
    void cleanup() throws Exception {
        TestWorkspace.deleteRecursively(appRoot);
    }

    @Test
    void acceptedReplacementGrowsTheSameMemoryIdAndProtectsIt() throws Exception {
        createVault();
        String memoryId = add(DETAIL, OLD_VALUE);
        String proposalId = propose("REPLACE_PROPOSAL", DETAIL, NEW_VALUE, memoryId, 1);
        long revisionBefore = vaultRevision();

        MemoryApiResult reviewed = reviews.review(proposalId, review("accept", 1, 1));

        assertEquals(200, reviewed.status(), String.valueOf(reviewed.body()));
        assertEquals(memoryId, reviewed.body().get("memoryId"));
        assertEquals(2, reviewed.body().get("version"));
        Map<String, Object> current = items.get(memoryId).body();
        assertEquals(NEW_VALUE, current.get("value"), "采纳后当前值就是新值");
        assertEquals(2, current.get("version"));
        assertEquals(true, current.get("manualProtected"), "采纳是人工动作，之后自动提取不得覆盖它");
        assertEquals(1, count("SELECT COUNT(*) FROM ai_memory_item WHERE memory_id = ? AND status = 'SUPERSEDED'",
                memoryId));
        assertEquals(MemoryProposalRepository.STATUS_ACCEPTED, proposal(proposalId).status());
        assertEquals(revisionBefore + 1, vaultRevision(), "内容变更只推 revision");
        assertEquals(0, count("SELECT COUNT(*) FROM ai_memory_suppression WHERE suppression_kind = 'FAMILY'"),
                "单值谓词的新旧族键相同，抑制它等于关掉用户刚采纳的这条通道");
    }

    /** D97 的另一半：族真的换了（多值谓词换值）就必须抑制旧族，否则用户刚否掉的旧值会被下一次整理重提回来。 */
    @Test
    void aReplacementIntoADifferentFamilySuppressesTheOldOne() throws Exception {
        createVault();
        String memoryId = add(GOAL, "雅思照旧");
        String oldFamily = jdbc.queryForObject("SELECT family_key_hmac FROM ai_memory_item WHERE memory_id = ?"
                + " AND version = 1", String.class, memoryId);
        String proposalId = propose("REPLACE_PROPOSAL", GOAL, "准备托福", memoryId, 1);

        assertEquals(200, reviews.review(proposalId, review("accept", 1, 1)).status());

        assertEquals(1, count("SELECT COUNT(*) FROM ai_memory_suppression WHERE suppression_kind = 'FAMILY'"),
                "换族替换要抑制旧族（候选 replace 走同一段写入，共用这条断言口径）");
        assertEquals(oldFamily, jdbc.queryForObject("SELECT key_hmac FROM ai_memory_suppression WHERE"
                + " suppression_kind = 'FAMILY'", String.class), "抑制的就是旧那一族的键");
        assertEquals("准备托福", items.get(memoryId).body().get("value"));
    }

    @Test
    void acceptedRetractionRunsTheWholeDeleteChainAndStaysAccepted() throws Exception {
        createVault();
        String memoryId = add(DETAIL, OLD_VALUE);
        long epochBefore = vaultEpoch();
        String proposalId = propose("RETRACT_PROPOSAL", DETAIL, null, memoryId, 1);

        MemoryApiResult reviewed = reviews.review(proposalId, review("accept", 1, 1));

        assertEquals(200, reviewed.status(), String.valueOf(reviewed.body()));
        assertEquals(404, items.get(memoryId).status(), "整条版本链都删了");
        assertEquals(0, count("SELECT COUNT(*) FROM ai_memory_item WHERE memory_id = ?", memoryId));
        assertEquals(2, count("SELECT COUNT(*) FROM ai_memory_suppression WHERE suppression_kind IN"
                + " ('FAMILY','PREDICATE')"), "撤销复用 deleteMemory 的抑制与边界");
        assertEquals(epochBefore + 1, vaultEpoch(), "遗忘边界照样推进");
        assertEquals(MemoryProposalRepository.STATUS_ACCEPTED, proposal(proposalId).status(),
                "先落终态再删：不能被删除逻辑把自己打成 STALE");
    }

    /**
     * 撤销采纳推了遗忘边界，就得跟手动删除同口径在提交后作废旧 epoch 的任务与摘要检查点。
     * 漏掉这半边时残留行没人清：那个会话之后每次摘要都以新 epoch 去 {@code saveIn}，撞上残留行被判
     * BatchChanged，水位不推进、每轮冷却后重发一次真实外发，摘要永远存不进去。
     */
    @Test
    void retractionAcceptanceRunsThePostCommitEpochBarrier() throws Exception {
        createVault();
        String memoryId = add(DETAIL, OLD_VALUE);
        long epochBefore = vaultEpoch();
        String proposalId = propose("RETRACT_PROPOSAL", DETAIL, null, memoryId, 1);
        jdbc.update("INSERT INTO ai_session_context(session_id, revision, through_turn_id, context_generation,"
                + " vault_epoch, payload_cipher, crypto_meta, estimated_tokens, updated_at)"
                + " VALUES (?, 1, 1, 1, ?, 'c', '{}', 10, ?)", sessionId, epochBefore, MemoryTimes.now());
        jdbc.update("INSERT INTO ai_memory_job(id, kind, session_id, from_turn_id, through_turn_id,"
                + " context_generation, source_scope_type, source_scope_id, source_timezone, vault_epoch,"
                + " policy_revision, target_fingerprint, prompt_version, input_revision, status, created_at,"
                + " updated_at) VALUES ('job-probe', 'EXTRACT', ?, 1, 2, 1, 'GLOBAL', 0, 'UTC', ?, 1, 'fp', 'v1',"
                + " 'ir-1', 'QUEUED', ?, ?)", sessionId, epochBefore, MemoryTimes.now(), MemoryTimes.now());

        assertEquals(200, reviews.review(proposalId, review("accept", 1, 1)).status(), "先确认采纳本身成功");

        long epochAfter = vaultEpoch();
        assertEquals(epochBefore + 1, epochAfter, "撤销采纳推进遗忘边界");
        assertEquals(0, count("SELECT COUNT(*) FROM ai_session_context WHERE vault_epoch < ?", epochAfter),
                "旧 epoch 的摘要检查点必须与手动删除同口径作废");
        assertEquals("SUPERSEDED", jdbc.queryForObject("SELECT status FROM ai_memory_job WHERE id = 'job-probe'",
                String.class), "旧 epoch 的任务必须与手动删除同口径作废");
    }

    /** 拒绝一条建议不删事实，也就没有屏障可跑：不许顺手把别的会话的检查点抹了。 */
    @Test
    void refusingAProposalLeavesEverySessionCheckpointAlone() throws Exception {
        createVault();
        String memoryId = add(DETAIL, OLD_VALUE);
        long epoch = vaultEpoch();
        String proposalId = propose("RETRACT_PROPOSAL", DETAIL, null, memoryId, 1);
        jdbc.update("INSERT INTO ai_session_context(session_id, revision, through_turn_id, context_generation,"
                + " vault_epoch, payload_cipher, crypto_meta, estimated_tokens, updated_at)"
                + " VALUES (?, 1, 1, 1, ?, 'c', '{}', 10, ?)", sessionId, epoch, MemoryTimes.now());

        assertEquals(200, reviews.review(proposalId, review("reject", 1, null)).status());

        assertEquals(1, count("SELECT COUNT(*) FROM ai_session_context WHERE session_id = ?", sessionId),
                "拒绝只写来源抑制，不动任何派生内容");
        assertEquals(epoch, vaultEpoch(), "拒绝不推遗忘边界");
    }

    @Test
    void rejectedProposalKeepsTheFactAndSuppressesOnlyThatSource() throws Exception {
        createVault();
        String memoryId = add(DETAIL, OLD_VALUE);
        long revisionBefore = vaultRevision();
        String proposalId = propose("REPLACE_PROPOSAL", DETAIL, NEW_VALUE, memoryId, 1);

        MemoryApiResult reviewed = reviews.review(proposalId, review("reject", 1, null));

        assertEquals(200, reviewed.status());
        assertEquals(MemoryProposalRepository.STATUS_REJECTED, proposal(proposalId).status());
        Map<String, Object> current = items.get(memoryId).body();
        assertEquals(OLD_VALUE, current.get("value"), "保留当前就是什么都不动");
        assertEquals(1, current.get("version"));
        assertEquals(1, count("SELECT COUNT(*) FROM ai_memory_suppression WHERE suppression_kind = 'EVIDENCE'"),
                "§4.4：拒绝要保存来源抑制，同一句话不再重提");
        assertEquals(0, count("SELECT COUNT(*) FROM ai_memory_suppression WHERE suppression_kind = 'FAMILY'"));
        assertEquals(revisionBefore, vaultRevision(), "库里没动就不涨内容代次");
    }

    @Test
    void keepBothAddsASecondProtectedFactForMultiValuedGoals() throws Exception {
        createVault();
        String memoryId = add(GOAL, "雅思照旧");
        String proposalId = propose("REPLACE_PROPOSAL", GOAL, "准备托福", memoryId, 1);

        MemoryApiResult reviewed = reviews.review(proposalId, review("keep_both", 1, 1));

        assertEquals(200, reviewed.status(), String.valueOf(reviewed.body()));
        String sibling = String.valueOf(reviewed.body().get("memoryId"));
        assertFalse(memoryId.equals(sibling), "并存另起一条 memoryId，不动用户已有的那条");
        assertEquals(1, reviewed.body().get("version"));
        assertEquals("雅思照旧", items.get(memoryId).body().get("value"));
        assertEquals("准备托福", items.get(sibling).body().get("value"));
        assertEquals(true, items.get(sibling).body().get("manualProtected"));
        assertEquals(2, count("SELECT COUNT(*) FROM ai_memory_item WHERE status = 'ACTIVE'"));
        assertEquals(MemoryProposalRepository.STATUS_ACCEPTED, proposal(proposalId).status());
    }

    @Test
    void keepBothIsRefusedWhereThePredicateIsSingleValued() throws Exception {
        createVault();
        String memoryId = add(DETAIL, OLD_VALUE);
        String proposalId = propose("REPLACE_PROPOSAL", DETAIL, NEW_VALUE, memoryId, 1);

        MemoryApiResult refused = reviews.review(proposalId, review("keep_both", 1, 1));

        assertEquals(400, refused.status());
        assertEquals("MEMORY_DECISION_NOT_ALLOWED", refused.body().get("code"));
        assertEquals(MemoryProposalRepository.STATUS_PENDING, proposal(proposalId).status());
        assertEquals(1, count("SELECT COUNT(*) FROM ai_memory_item"), "按钮不合法就不能写出第二行");
    }

    @Test
    void keepBothIsRefusedForRetractions() throws Exception {
        createVault();
        String memoryId = add(DETAIL, OLD_VALUE);
        String proposalId = propose("RETRACT_PROPOSAL", DETAIL, null, memoryId, 1);

        assertEquals(400, reviews.review(proposalId, review("keep_both", 1, 1)).status());
        assertEquals(MemoryProposalRepository.STATUS_PENDING, proposal(proposalId).status());
        assertEquals(1, count("SELECT COUNT(*) FROM ai_memory_item WHERE memory_id = ?", memoryId));
    }

    @Test
    void aStaleClickOnTheProposalConflictsAndWritesNothing() throws Exception {
        createVault();
        String memoryId = add(DETAIL, OLD_VALUE);
        String proposalId = propose("REPLACE_PROPOSAL", DETAIL, NEW_VALUE, memoryId, 1);

        MemoryApiResult conflict = reviews.review(proposalId, review("accept", 9, 1));

        assertEquals(409, conflict.status());
        assertEquals("MEMORY_VERSION_CONFLICT", conflict.body().get("code"));
        assertEquals(1, ((Map<?, ?>) conflict.body().get("details")).get("version"));
        assertEquals(1, count("SELECT COUNT(*) FROM ai_memory_item WHERE memory_id = ?", memoryId), "只留原本那一版");
        assertFalse(String.valueOf(conflict.body()).contains(OLD_VALUE), "冲突响应不回正文");
    }

    @Test
    void editingTheTargetStalesItsPendingProposalRightAway() throws Exception {
        createVault();
        String memoryId = add(DETAIL, OLD_VALUE);
        String proposalId = propose("REPLACE_PROPOSAL", DETAIL, NEW_VALUE, memoryId, 1);

        assertEquals(200, items.edit(memoryId, new EditInput(1, null, null, "detailed and worked examples", null,
                null)).status(), "用户先手动改了当前值");
        assertEquals(MemoryProposalRepository.STATUS_STALE, proposal(proposalId).status(),
                "指向旧版本的建议再也采纳不了，别继续留在待确认列表里");

        MemoryApiResult clicked = reviews.review(proposalId, review("accept", 1, 1));
        assertEquals(200, clicked.status());
        assertEquals(false, clicked.body().get("changed"), "已作废的建议点第二次也只是回状态，不再动事实");
        assertEquals(MemoryProposalRepository.STATUS_STALE, clicked.body().get("status"));
        assertEquals(2, count("SELECT COUNT(*) FROM ai_memory_item WHERE memory_id = ?", memoryId));
    }

    @Test
    void aProposalWhoseTargetAlreadyMovedIsRefusedAndStaledInsideTheTransaction() throws Exception {
        createVault();
        String memoryId = add(DETAIL, OLD_VALUE);
        String first = propose("REPLACE_PROPOSAL", DETAIL, NEW_VALUE, memoryId, 1);
        String second = propose("REPLACE_PROPOSAL", DETAIL, "detailed but with worked examples", memoryId, 1);

        assertEquals(200, reviews.review(first, review("accept", 1, 1)).status(), "第一条先采纳，长出 v2");

        MemoryApiResult conflict = reviews.review(second, review("accept", 1, 1));
        assertEquals(409, conflict.status(), "第二条建议的目标已经不是当初那一版");
        assertEquals("MEMORY_VERSION_CONFLICT", conflict.body().get("code"));
        assertEquals(MemoryProposalRepository.STATUS_STALE, proposal(second).status());
        assertEquals(2, count("SELECT COUNT(*) FROM ai_memory_item WHERE memory_id = ?", memoryId),
                "不得冒出第三版");
    }

    @Test
    void expiredProposalsAreRefusedAndMarkedStale() throws Exception {
        createVault();
        String memoryId = add(DETAIL, OLD_VALUE);
        String proposalId = propose("REPLACE_PROPOSAL", DETAIL, NEW_VALUE, memoryId, 1,
                MemoryTimes.now(), "2020-01-01T00:00:00.000Z");

        MemoryApiResult refused = reviews.review(proposalId, review("accept", 1, 1));

        assertEquals(409, refused.status());
        assertEquals("MEMORY_PROPOSAL_EXPIRED", refused.body().get("code"));
        assertEquals(MemoryProposalRepository.STATUS_STALE, proposal(proposalId).status());
        assertEquals(1, count("SELECT COUNT(*) FROM ai_memory_item WHERE memory_id = ?", memoryId));
    }

    @Test
    void aCleanedUpSourceQuoteTakesTheProposalOutOfPlay() throws Exception {
        createVault();
        String memoryId = add(DETAIL, OLD_VALUE);
        String proposalId = propose("REPLACE_PROPOSAL", DETAIL, NEW_VALUE, memoryId, 1);
        jdbc.update("DELETE FROM ai_memory_item_evidence WHERE evidence_id = (SELECT evidence_id"
                + " FROM ai_memory_proposal WHERE id = ?)", proposalId);
        jdbc.update("DELETE FROM ai_memory_evidence WHERE id = (SELECT evidence_id FROM ai_memory_proposal"
                + " WHERE id = ?)", proposalId);

        MemoryApiResult refused = reviews.review(proposalId, review("accept", 1, 1));

        assertEquals(409, refused.status());
        assertEquals("MEMORY_PROPOSAL_STALE", refused.body().get("code"));
        assertEquals(MemoryProposalRepository.STATUS_STALE, proposal(proposalId).status());
        assertEquals(OLD_VALUE, items.get(memoryId).body().get("value"), "原话没了就不拿建议硬套事实");
    }

    @Test
    void aSessionGenerationChangeStalesTheProposal() throws Exception {
        createVault();
        String memoryId = add(DETAIL, OLD_VALUE);
        String proposalId = propose("REPLACE_PROPOSAL", DETAIL, NEW_VALUE, memoryId, 1);
        jdbc.update("UPDATE ai_chat_session SET context_generation = context_generation + 1 WHERE id = ?",
                sessionId);

        MemoryApiResult refused = reviews.review(proposalId, review("accept", 1, 1));

        assertEquals(409, refused.status());
        assertEquals("MEMORY_PROPOSAL_STALE", refused.body().get("code"));
        assertEquals(MemoryProposalRepository.STATUS_STALE, proposal(proposalId).status());
    }

    @Test
    void aVaultEpochChangeStalesTheProposalBecauseForgetHappenedSince() throws Exception {
        createVault();
        String memoryId = add(DETAIL, OLD_VALUE);
        String proposalId = propose("REPLACE_PROPOSAL", DETAIL, NEW_VALUE, memoryId, 1);
        memory.bumpEpoch(vaultId);

        MemoryApiResult refused = reviews.review(proposalId, review("accept", 1, 1));

        assertEquals(409, refused.status());
        assertEquals("MEMORY_PROPOSAL_STALE", refused.body().get("code"));
        assertEquals(MemoryProposalRepository.STATUS_STALE, proposal(proposalId).status());
    }

    @Test
    void aThirdActiveFactInTheNewFamilyBlocksAcceptanceWithoutHalfWrittenState() throws Exception {
        createVault();
        String target = add(GOAL, "雅思照旧");
        String holder = add(GOAL, "准备托福");
        String proposalId = propose("REPLACE_PROPOSAL", GOAL, "准备托福", target, 1);

        MemoryApiResult conflict = reviews.review(proposalId, review("accept", 1, 1));

        assertEquals(409, conflict.status());
        assertEquals("MEMORY_FAMILY_CONFLICT", conflict.body().get("code"));
        assertTrue(String.valueOf(conflict.body().get("details")).contains(holder), "把占着新族的告诉用户");
        assertEquals(MemoryProposalRepository.STATUS_PENDING, proposal(proposalId).status(),
                "整笔回滚：不能建议已 ACCEPTED 而事实没落地");
        assertEquals(0, count("SELECT COUNT(*) FROM ai_memory_item WHERE memory_id = ? AND version = 2", target),
                "整笔回滚：不能只把旧版置 SUPERSEDED 就完事");
        assertEquals(1, count("SELECT COUNT(*) FROM ai_memory_item WHERE memory_id = ? AND status = 'ACTIVE'"
                + " AND version = 1", target), "旧值仍是那条记忆的生效版本");
        assertEquals(2, count("SELECT COUNT(*) FROM ai_memory_item WHERE status = 'ACTIVE'"));
    }

    @Test
    void theSameOperationIdReplaysInsteadOfWritingAnotherVersion() throws Exception {
        createVault();
        String memoryId = add(DETAIL, OLD_VALUE);
        String proposalId = propose("REPLACE_PROPOSAL", DETAIL, NEW_VALUE, memoryId, 1);
        String operationId = "op-review-1";

        MemoryApiResult first = reviews.review(proposalId, new ReviewInput(1, 1, "accept", operationId));
        MemoryApiResult second = reviews.review(proposalId, new ReviewInput(1, 1, "accept", operationId));

        assertEquals(200, first.status());
        assertEquals(200, second.status(), "重放不因为建议版本已推进就变成 409");
        assertEquals(true, second.body().get("replayed"));
        assertEquals(MemoryProposalRepository.STATUS_ACCEPTED, second.body().get("status"));
        assertEquals(2, count("SELECT COUNT(*) FROM ai_memory_item WHERE memory_id = ?", memoryId),
                "双击只长一个版本");
    }

    @Test
    void lockedVaultRefusesToReviewAnything() throws Exception {
        createVault();
        String memoryId = add(DETAIL, OLD_VALUE);
        String proposalId = propose("REPLACE_PROPOSAL", DETAIL, NEW_VALUE, memoryId, 1);
        assertEquals(204, vault.lock().status());

        MemoryApiResult refused = reviews.review(proposalId, review("accept", 1, 1));

        assertEquals(MemoryVaultService.HTTP_LOCKED, refused.status());
        assertEquals("MEMORY_LOCKED", refused.body().get("code"));
        assertEquals(MemoryProposalRepository.STATUS_PENDING, proposal(proposalId).status());
    }

    @Test
    void missingVersionOrUnknownProposalAreRefusedBeforeTouchingAnything() throws Exception {
        createVault();
        String memoryId = add(DETAIL, OLD_VALUE);
        String proposalId = propose("REPLACE_PROPOSAL", DETAIL, NEW_VALUE, memoryId, 1);

        assertEquals(400, reviews.review(proposalId, review("accept", null, 1)).status());
        assertEquals(400, reviews.review(proposalId, review("accept", 1, null)).status());
        assertEquals("MEMORY_TARGET_VERSION_REQUIRED",
                reviews.review(proposalId, review("accept", 1, null)).body().get("code"));
        assertEquals(400, reviews.review(proposalId, review("approve", 1, 1)).status());
        assertEquals(404, reviews.review("no-such-proposal", review("accept", 1, 1)).status());
        assertEquals(MemoryProposalRepository.STATUS_PENDING, proposal(proposalId).status());
        assertEquals(1, items.get(memoryId).body().get("version"));
    }

    @Test
    void reviewedFactIsReadableThroughTheManualReadPathWithItsQuote() throws Exception {
        createVault();
        String memoryId = add(DETAIL, OLD_VALUE);
        String proposalId = propose("REPLACE_PROPOSAL", DETAIL, NEW_VALUE, memoryId, 1);
        assertEquals(200, reviews.review(proposalId, review("accept", 1, 1)).status());

        Map<String, Object> detail = items.get(memoryId).body();
        assertEquals(NEW_VALUE, detail.get("value"));
        assertNotNull(detail.get("predicate"));
        assertEquals(QUOTE, firstQuote(detail));
        assertTrue(memory.readEnvelope().isPresent());
    }

    // ---- 待确认候选（§3 items/{id}/review）----

    @Test
    void acceptPromotesThePendingCandidateAndKeepsItsModelProvenance() throws Exception {
        createVault();
        String candidateId = addPending(DETAIL, NEW_VALUE);
        long epochBefore = vaultEpoch();
        long revisionBefore = vaultRevision();

        MemoryApiResult reviewed = reviews.reviewItem(candidateId, itemReview("accept", 1, null, null));

        assertEquals(200, reviewed.status(), String.valueOf(reviewed.body()));
        assertEquals(MemoryRepository.STATUS_ACTIVE, reviewed.body().get("status"));
        assertEquals(true, reviewed.body().get("changed"));
        Map<String, Object> row = jdbc.queryForMap("SELECT status, manual_protected, origin FROM ai_memory_item"
                + " WHERE memory_id = ?", candidateId);
        assertEquals("ACTIVE", row.get("status"), "候选就地转正，不再多插一版");
        assertEquals(1, row.get("manual_protected"), "人点过头之后自动提取不得覆盖它");
        assertEquals("AUTO", row.get("origin"), "内容仍是模型提的，来源不改写成人工");
        assertEquals(epochBefore, vaultEpoch(), "转正一条候选不是遗忘边界");
        assertEquals(revisionBefore + 1, vaultRevision());
    }

    @Test
    void acceptRefusesWhileTheFamilyAlreadyHasAnActiveFact() throws Exception {
        createVault();
        String activeId = add(DETAIL, OLD_VALUE);
        String candidateId = addPending(DETAIL, NEW_VALUE);

        MemoryApiResult reviewed = reviews.reviewItem(candidateId, itemReview("accept", 1, null, null));

        assertEquals(409, reviewed.status(), String.valueOf(reviewed.body()));
        assertEquals("MEMORY_FAMILY_CONFLICT", reviewed.body().get("code"));
        assertEquals(activeId, firstCandidate(reviewed).get("memoryId"), "让人先整理那条，或改用替换");
        assertEquals(MemoryRepository.STATUS_PENDING, itemStatus(candidateId), "被拒时候选一个字不动");
    }

    @Test
    void keepBothActivatesASecondMultiValuedCandidateButNotASingleValuedOne() throws Exception {
        createVault();
        add(GOAL, "雅思照旧");
        String goalCandidate = addPending(GOAL, "准备托福");
        MemoryApiResult kept = reviews.reviewItem(goalCandidate, itemReview("keep_both", 1, null, null));
        assertEquals(200, kept.status(), String.valueOf(kept.body()));
        assertEquals(2, count("SELECT COUNT(*) FROM ai_memory_item WHERE status = 'ACTIVE'"),
                "多值事实允许两条并存（§2）");

        String detailCandidate = addPending(DETAIL, NEW_VALUE);
        MemoryApiResult refused = reviews.reviewItem(detailCandidate, itemReview("keep_both", 1, null, null));
        assertEquals(400, refused.status(), String.valueOf(refused.body()));
        assertEquals("MEMORY_DECISION_NOT_ALLOWED", refused.body().get("code"), "单值偏好没有可并存的第二条");
        assertEquals(MemoryRepository.STATUS_PENDING, itemStatus(detailCandidate));
    }

    @Test
    void rejectedCandidateLeavesNoRowAndSuppressesOnlyItsOwnQuote() throws Exception {
        createVault();
        String candidateId = addPending(DETAIL, NEW_VALUE);
        long epochBefore = vaultEpoch();

        MemoryApiResult reviewed = reviews.reviewItem(candidateId, itemReview("reject", 1, null, null));

        assertEquals(200, reviewed.status(), String.valueOf(reviewed.body()));
        assertEquals("REJECTED", reviewed.body().get("status"));
        assertEquals(0, count("SELECT COUNT(*) FROM ai_memory_item WHERE memory_id = ?", candidateId));
        assertEquals(1, count("SELECT COUNT(*) FROM ai_memory_suppression WHERE suppression_kind = 'EVIDENCE'"),
                "同一句话下次整理不得再提回来");
        assertEquals(0, count("SELECT COUNT(*) FROM ai_memory_suppression WHERE suppression_kind = 'FAMILY'"),
                "拒绝一条候选不等于忘掉这个族");
        assertEquals(epochBefore, vaultEpoch(), "拒绝不推全局遗忘边界");
        assertEquals(404, items.get(candidateId).status());
    }

    @Test
    void replaceGrowsTheHumanChosenTargetAndRemovesTheCandidate() throws Exception {
        createVault();
        String activeId = add(DETAIL, OLD_VALUE);
        String candidateId = addPending(DETAIL, NEW_VALUE);
        long epochBefore = vaultEpoch();

        MemoryApiResult reviewed = reviews.reviewItem(candidateId, itemReview("replace", 1, activeId, 1));

        assertEquals(200, reviewed.status(), String.valueOf(reviewed.body()));
        assertEquals(activeId, reviewed.body().get("memoryId"), "新内容沿用被点名那条的 memoryId");
        assertEquals(2, reviewed.body().get("version"));
        Map<String, Object> current = items.get(activeId).body();
        assertEquals(NEW_VALUE, current.get("value"));
        assertEquals(true, current.get("manualProtected"));
        assertEquals(1, count("SELECT COUNT(*) FROM ai_memory_item WHERE memory_id = ? AND status = 'SUPERSEDED'",
                activeId));
        assertEquals(0, count("SELECT COUNT(*) FROM ai_memory_item WHERE memory_id = ?", candidateId),
                "候选已经被采纳进新版本，不留第二条待确认");
        assertEquals(1, count("SELECT COUNT(*) FROM ai_memory_item WHERE status = 'ACTIVE'"), "族里仍然只有一条生效");
        assertEquals(epochBefore, vaultEpoch(), "同族替换不是遗忘边界，只推 revision");
    }

    @Test
    void replaceRefusesWhenTheTargetMovedAndWritesNothing() throws Exception {
        createVault();
        String activeId = add(DETAIL, OLD_VALUE);
        String candidateId = addPending(DETAIL, NEW_VALUE);
        assertEquals(200, items.edit(activeId, new EditInput(1, null, null, "verbose", null, null)).status());

        MemoryApiResult reviewed = reviews.reviewItem(candidateId, itemReview("replace", 1, activeId, 1));

        assertEquals(409, reviewed.status(), String.valueOf(reviewed.body()));
        assertEquals("MEMORY_VERSION_CONFLICT", reviewed.body().get("code"));
        @SuppressWarnings("unchecked")
        Map<String, Object> target = (Map<String, Object>) ((Map<String, Object>) reviewed.body().get("details"))
                .get("target");
        assertEquals(2, target.get("version"), "只回最新元信息，正文要另走 GET");
        assertEquals(MemoryRepository.STATUS_PENDING, itemStatus(candidateId));
        assertEquals("verbose", items.get(activeId).body().get("value"), "旧值没被动过");
    }

    @Test
    void replaceRejectsAKnifePointedAtItselfOrAnotherScope() throws Exception {
        createVault();
        String activeId = add(DETAIL, OLD_VALUE);
        String candidateId = addPending(DETAIL, NEW_VALUE);

        MemoryApiResult self = reviews.reviewItem(candidateId, itemReview("replace", 1, candidateId, 1));
        assertEquals(400, self.status(), String.valueOf(self.body()));
        assertEquals("MEMORY_TARGET_INVALID", self.body().get("code"));

        long notebookId = 7L;
        jdbc.update("INSERT INTO notebook(id, title) VALUES (?, '测试笔记本')", notebookId);
        String notebookGoal = addIn("NOTEBOOK", notebookId, GOAL, "雅思照旧");
        MemoryApiResult crossScope = reviews.reviewItem(candidateId, itemReview("replace", 1, notebookGoal, 1));
        assertEquals(400, crossScope.status(), String.valueOf(crossScope.body()));
        assertTrue(String.valueOf(crossScope.body().get("message")).contains("作用域"),
                "全局候选不得顶掉笔记本里的记忆");
    }

    @Test
    void aCandidateAlreadyPromotedIsReportedAsUnchanged() throws Exception {
        createVault();
        String candidateId = addPending(DETAIL, NEW_VALUE);
        assertEquals(200, reviews.reviewItem(candidateId, itemReview("accept", 1, null, null)).status());

        MemoryApiResult again = reviews.reviewItem(candidateId, itemReview("accept", 1, null, null));

        assertEquals(200, again.status(), "重复点击不是冲突，是已经办过");
        assertEquals(false, again.body().get("changed"));
        assertEquals(1, count("SELECT COUNT(*) FROM ai_memory_item WHERE memory_id = ? AND status = 'ACTIVE'",
                candidateId));
    }

    @Test
    void theSameOperationIdReplaysForACandidateToo() throws Exception {
        createVault();
        String candidateId = addPending(DETAIL, NEW_VALUE);
        ItemReviewInput first = new ItemReviewInput(1, "accept", null, null, "op-candidate-1");

        MemoryApiResult reviewed = reviews.reviewItem(candidateId, first);
        MemoryApiResult replayed = reviews.reviewItem(candidateId, first);

        assertEquals(200, reviewed.status());
        assertEquals(200, replayed.status(), String.valueOf(replayed.body()));
        assertEquals(true, replayed.body().get("replayed"));
        assertEquals(1, count("SELECT COUNT(*) FROM ai_memory_item WHERE memory_id = ? AND status = 'ACTIVE'",
                candidateId), "双击不得长出第二条生效记忆");
    }

    @Test
    void aRefusedReviewFreesTheOperationIdForTheNextTry() throws Exception {
        createVault();
        String activeId = add(DETAIL, OLD_VALUE);
        String candidateId = addPending(DETAIL, NEW_VALUE);
        String operationId = "op-retry-1";

        assertEquals(409, reviews.reviewItem(candidateId, new ItemReviewInput(1, "accept", null, null, operationId))
                .status(), "同族已有 ACTIVE：这一枪必须打空");

        MemoryApiResult retry = reviews.reviewItem(candidateId, new ItemReviewInput(1, "replace", activeId, 1,
                operationId));
        assertEquals(200, retry.status(), "被拒的决定不得把 operationId 锁成 IN_FLIGHT：" + retry.body());
        assertEquals(NEW_VALUE, items.get(activeId).body().get("value"));
    }

    @Test
    void candidateReviewRefusesABogusDecisionAndAMissingVersion() throws Exception {
        createVault();
        String candidateId = addPending(DETAIL, NEW_VALUE);

        assertEquals("MEMORY_DECISION_INVALID",
                reviews.reviewItem(candidateId, itemReview("approve", 1, null, null)).body().get("code"));
        assertEquals("MEMORY_VERSION_REQUIRED",
                reviews.reviewItem(candidateId, itemReview("accept", null, null, null)).body().get("code"));
        assertEquals("MEMORY_TARGET_REQUIRED",
                reviews.reviewItem(candidateId, itemReview("replace", 1, null, null)).body().get("code"));
        assertEquals(404, reviews.reviewItem("missing-candidate", itemReview("accept", 1, null, null)).status());
        assertEquals(MemoryRepository.STATUS_PENDING, itemStatus(candidateId), "校验都在落库之前");
    }

    /** §12 的审阅界面要显示"为什么待确认"和可替换的旧目标，这些只能由服务端随列表给出来。 */
    @Test
    void pendingCandidatesCarryTheirReasonAndTheFactTheyClashWith() throws Exception {
        createVault();
        String activeId = add(DETAIL, OLD_VALUE);
        String candidateId = addPending(DETAIL, NEW_VALUE, activeId, 1);

        Map<String, Object> view = firstItem(items.list(null, null, MemoryRepository.STATUS_PENDING, null, null, null));

        assertEquals(candidateId, view.get("memoryId"));
        assertEquals("MANUAL_PROTECTED_TARGET", view.get("reviewReason"));
        assertEquals("旧值是你亲手改过的，后台不得自行覆盖", view.get("reviewReasonText"));
        assertEquals(false, view.get("multiValued"), "单值谓词的候选不给并存按钮");
        assertEquals(activeId, view.get("proposedAgainstMemoryId"), "替换选择器要有预选的旧目标");
        assertEquals(1, view.get("proposedAgainstVersion"));
    }

    // ---- 建议列表（§3 GET /memory/proposals）----

    @Test
    void proposalsListCarriesNewValueQuoteAndTheCurrentValue() throws Exception {
        createVault();
        String activeId = add(DETAIL, OLD_VALUE);
        propose("REPLACE_PROPOSAL", DETAIL, NEW_VALUE, activeId, 1, "2026-01-01T00:00:00.000Z",
                "2099-01-01T00:00:00.000Z");
        String goalId = add(GOAL, "雅思照旧");
        propose("REPLACE_PROPOSAL", GOAL, "准备托福", goalId, 1, "2026-01-02T00:00:00.000Z",
                "2099-01-01T00:00:00.000Z");

        MemoryApiResult listed = reviews.list(null, null, null, null);

        assertEquals(200, listed.status(), String.valueOf(listed.body()));
        assertEquals(2, proposalsOf(listed).size());
        Map<String, Object> row = proposalsOf(listed).get(0);
        assertEquals(NEW_VALUE, row.get("newValue"));
        assertEquals(QUOTE, row.get("quote"), "审阅要看原话，不然只能凭模型措辞猜");
        assertEquals("SAME_FAMILY_DIFFERENT_VALUE", row.get("reviewReason"), "为什么待确认必须跟着列表回来");
        assertEquals("GLOBAL", row.get("scopeType"));
        assertEquals(false, row.get("corrupt"));
        assertEquals(false, row.get("multiValued"), "单值偏好不给「分别保留」——判定表只许服务端有一份");
        assertEquals("同一类里已有别的值，需要你选一个", row.get("reviewReasonText"),
                "§12 要的是给用户读的一句话，不是裸枚举");
        assertTrue(proposalsOf(listed).get(1).containsKey("multiValued"));
        assertEquals(true, proposalsOf(listed).get(1).get("multiValued"), "study.goal 是多值类别");
        @SuppressWarnings("unchecked")
        Map<String, Object> target = (Map<String, Object>) row.get("target");
        assertEquals(OLD_VALUE, target.get("value"), "新值与当前值并排给，三按钮才按得下去");
        assertEquals(1, target.get("version"));
    }

    @Test
    void proposalsListFiltersByScopeInsteadOfTheCurrentSession() throws Exception {
        createVault();
        String globalTarget = add(DETAIL, OLD_VALUE);
        propose("REPLACE_PROPOSAL", DETAIL, NEW_VALUE, globalTarget, 1);
        long notebookId = 7L;
        jdbc.update("INSERT INTO notebook(id, title) VALUES (?, '测试笔记本')", notebookId);
        String notebookGoal = addIn("NOTEBOOK", notebookId, GOAL, "雅思照旧");
        proposeScoped("RETRACT_PROPOSAL", GOAL, null, notebookGoal, 1, "NOTEBOOK", notebookId);

        assertEquals(1, proposalsOf(reviews.list("GLOBAL", 0L, null, null)).size());
        List<Map<String, Object>> scoped = proposalsOf(reviews.list("NOTEBOOK", notebookId, null, null));
        assertEquals(1, scoped.size(), "另一条建议来自同一个会话，但作用域不同——不得被顺手列出");
        assertEquals(notebookGoal, scoped.get(0).get("targetMemoryId"));
        assertEquals(2, proposalsOf(reviews.list(null, null, null, null)).size(), "不给筛选就是全库");
    }

    @Test
    void proposalsListRefusesToRunWhileLocked() throws Exception {
        createVault();
        propose("RETRACT_PROPOSAL", DETAIL, null, add(DETAIL, OLD_VALUE), 1);
        assertEquals(204, vault.lock().status());

        MemoryApiResult listed = reviews.list(null, null, null, null);
        assertEquals(MemoryVaultService.HTTP_LOCKED, listed.status(), "载荷解不开就没法按 scope 过滤，只能要解锁");
        assertEquals("MEMORY_LOCKED", listed.body().get("code"));
        assertEquals(true, listed.body().get("retryable"));
    }

    @Test
    void proposalsListSkipsExpiredAndDecidedProposals() throws Exception {
        createVault();
        String activeId = add(DETAIL, OLD_VALUE);
        propose("REPLACE_PROPOSAL", DETAIL, NEW_VALUE, activeId, 1, "2020-01-01T00:00:00.000Z",
                "2020-01-02T00:00:00.000Z");
        String live = propose("REPLACE_PROPOSAL", GOAL, "准备托福", add(GOAL, "雅思照旧"), 1);
        assertEquals(200, reviews.review(live, review("reject", 1, null)).status());

        assertEquals(0, proposalsOf(reviews.list(null, null, null, null)).size(),
                "过期的与已决定的都不该再出现在待确认列表里");
    }

    @Test
    void proposalsCursorIsBoundToTheFilterAndTheVaultGeneration() throws Exception {
        createVault();
        String detailTarget = add(DETAIL, OLD_VALUE);
        String goalTarget = add(GOAL, "雅思照旧");
        String first = propose("REPLACE_PROPOSAL", DETAIL, NEW_VALUE, detailTarget, 1, "2026-01-01T00:00:00.000Z",
                "2099-01-01T00:00:00.000Z");
        String secondId = propose("REPLACE_PROPOSAL", GOAL, "准备托福", goalTarget, 1, "2026-01-02T00:00:00.000Z",
                "2099-01-01T00:00:00.000Z");

        MemoryApiResult page = reviews.list(null, null, 1, null);
        assertEquals(200, page.status(), String.valueOf(page.body()));
        assertEquals(1, proposalsOf(page).size());
        assertEquals(true, page.body().get("hasMore"));
        assertEquals(first, proposalsOf(page).get(0).get("proposalId"));
        String cursor = String.valueOf(page.body().get("nextCursor"));

        MemoryApiResult second = reviews.list(null, null, 1, cursor);
        assertEquals(200, second.status(), String.valueOf(second.body()));
        assertEquals(secondId, proposalsOf(second).get(0).get("proposalId"),
                "游标是上一页最后一行的 keyset，第二页不得重复第一条");

        assertEquals("MEMORY_PAGE_STALE", reviews.list("GLOBAL", 0L, 1, cursor).body().get("code"),
                "换了筛选条件不得沿用旧游标（§3：不跨 scope 混用）");
        String scopedCursor = String.valueOf(reviews.list("GLOBAL", 0L, 1, null).body().get("nextCursor"));
        assertEquals("MEMORY_PAGE_STALE", reviews.list(null, null, 1, scopedCursor).body().get("code"));

        add("answer.order", "example_first");
        assertEquals("MEMORY_PAGE_STALE", reviews.list(null, null, 1, cursor).body().get("code"),
                "库的 revision 变了要给可重置提示，而不是静默漏项或重项");
    }

    @Test
    void proposalsListRejectsBadFiltersAndCursors() throws Exception {
        createVault();
        assertEquals("MEMORY_SCOPE_INVALID", reviews.list("SESSION", 3L, null, null).body().get("code"));
        assertEquals("MEMORY_SCOPE_INVALID", reviews.list("NOTEBOOK", null, null, null).body().get("code"));
        assertEquals("MEMORY_CURSOR_INVALID", reviews.list(null, null, null, "not-a-cursor").body().get("code"));
        propose("RETRACT_PROPOSAL", DETAIL, null, add(DETAIL, OLD_VALUE), 1);

        MemoryApiList listed = new MemoryApiList(reviews.list(null, null, 500, null));
        assertEquals(1, listed.rows().size(), "limit 越界只做夹取，不把整表端回来");
        assertEquals(50, listed.body().get("limit"), String.valueOf(listed.body()));
    }

    /** 只为把"响应体"与"列表行"分开取，免得用例里到处是强转。 */
    private record MemoryApiList(MemoryApiResult result) {
        Map<String, Object> body() {
            return result.body();
        }

        List<Map<String, Object>> rows() {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> rows = (List<Map<String, Object>>) result.body().get("proposals");
            return rows;
        }
    }

    // ---- 夹具 ----

    private static ReviewInput review(String decision, Integer expectedVersion, Integer expectedTargetVersion) {
        return new ReviewInput(expectedVersion, expectedTargetVersion, decision, null);
    }

    private static ItemReviewInput itemReview(String decision, Integer expectedVersion, String targetMemoryId,
                                              Integer expectedTargetVersion) {
        return new ItemReviewInput(expectedVersion, decision, targetMemoryId, expectedTargetVersion, null);
    }

    /** 建库并记下 vaultId：指纹模式创建即解锁，之后 requireKey/读库计数都用这一套真钥匙。 */
    private void createVault() throws SQLException {
        assertEquals(201, vault.create("fingerprint", null, null).status());
        vaultId = memory.readEnvelope().orElseThrow().vaultId();
    }

    private String add(String predicate, String value) {
        MemoryApiResult created = items.add(new AddInput(kindOf(predicate), "GLOBAL", 0L, predicate, null, value,
                null, null, null));
        assertEquals(201, created.status(), String.valueOf(created.body()));
        return String.valueOf(created.body().get("memoryId"));
    }

    private static String kindOf(String predicate) {
        return switch (predicate) {
            case GOAL -> "GOAL";
            case DETAIL -> "PREFERENCE";
            default -> "PREFERENCE";
        };
    }

    /** 走真实加密链路造一条建议：载荷形状与 {@link MemoryExtractionWriter} 完全一致。 */
    private String propose(String operation, String predicate, String newValue, String targetMemoryId,
                           int targetVersion) throws Exception {
        return propose(operation, predicate, newValue, targetMemoryId, targetVersion, MemoryTimes.now(),
                "2099-01-01T00:00:00.000Z");
    }

    private String propose(String operation, String predicate, String newValue, String targetMemoryId,
                           int targetVersion, String createdAt, String expiresAt) throws Exception {
        return proposeScoped(operation, predicate, newValue, targetMemoryId, targetVersion, createdAt, expiresAt,
                "GLOBAL", 0L);
    }

    private String proposeScoped(String operation, String predicate, String newValue, String targetMemoryId,
                                 int targetVersion, String scopeType, long scopeId) throws Exception {
        return proposeScoped(operation, predicate, newValue, targetMemoryId, targetVersion, MemoryTimes.now(),
                "2099-01-01T00:00:00.000Z", scopeType, scopeId);
    }

    /** 走真实加密链路造一条建议：载荷形状与 {@link MemoryExtractionWriter} 完全一致。 */
    private String proposeScoped(String operation, String predicate, String newValue, String targetMemoryId,
                                 int targetVersion, String createdAt, String expiresAt, String scopeType, long scopeId)
            throws Exception {
        MemoryKey key = vault.requireKey();
        long turnId = insertTurn();
        String evidenceId = UUID.randomUUID().toString();
        EncryptedValue quote = crypto.encrypt(key, "ai_memory_evidence", evidenceId, 1, QUOTE);
        memory.insertEvidence(new EvidenceDraft(evidenceId, vaultId, sessionId, turnId, null, "u0",
                "hmac-source-" + turnId, quote.cipher(), quote.cryptoMeta(), createdAt));
        String proposalId = UUID.randomUUID().toString();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schemaVersion", 1);
        payload.put("scopeType", scopeType);
        payload.put("scopeId", scopeId);
        payload.put("operation", operation);
        payload.put("kind", kindOf(predicate));
        payload.put("predicate", predicate);
        payload.put("newValue", newValue);
        payload.put("blockId", "u0");
        payload.put("sourceTurnId", turnId);
        payload.put("sourceGeneration", sessionGeneration());
        payload.put("sourceEpoch", vaultEpoch());
        payload.put("reviewReason", "SAME_FAMILY_DIFFERENT_VALUE");
        payload.put("target", Map.of("memoryId", targetMemoryId, "version", targetVersion));
        EncryptedValue encrypted = crypto.encrypt(key, "ai_memory_proposal", proposalId, 1,
                mapper.writeValueAsString(payload));
        ProposalDraft draft = new ProposalDraft(proposalId, vaultId, sessionId, turnId, evidenceId, targetMemoryId,
                targetVersion, operation, encrypted.cipher(), encrypted.cryptoMeta(), "dedup-" + proposalId,
                createdAt, expiresAt);
        assertEquals(MemoryProposalRepository.SubmitOutcome.SUBMITTED, proposals.submit(draft).outcome());
        return proposalId;
    }

    /**
     * 造一条提取链留下的待确认候选：状态 PENDING、来源 AUTO、载荷与展示句都按 {@link MemoryExtractionWriter}
     * 那一套，并挂上同一句原话——候选审阅测的就是"人点一下头之后这一行怎么变"。
     */
    private String addPending(String predicate, String value) throws Exception {
        return addPending(predicate, value, null, null);
    }

    private String addPending(String predicate, String value, String againstMemoryId, Integer againstVersion)
            throws Exception {
        MemoryKey key = vault.requireKey();
        long turnId = insertTurn();
        String evidenceId = UUID.randomUUID().toString();
        EncryptedValue quote = crypto.encrypt(key, "ai_memory_evidence", evidenceId, 1, QUOTE);
        String asOf = MemoryTimes.now();
        memory.insertEvidence(new EvidenceDraft(evidenceId, vaultId, sessionId, turnId, null, "u0",
                "hmac-source-" + turnId, quote.cipher(), quote.cryptoMeta(), asOf));
        String rowId = UUID.randomUUID().toString();
        String family = MemoryValidator.familyKey(predicate, value);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schemaVersion", 1);
        payload.put("subject", "self");
        payload.put("kind", kindOf(predicate));
        payload.put("predicate", predicate);
        payload.put("family", family);
        payload.put("value", value);
        payload.put("normalizedValue", value);
        payload.put("displayText", MemoryExtractionWriter.displayText(predicate, value));
        payload.put("keywords", java.util.List.of());
        payload.put("sensitivity", "NORMAL");
        payload.put("sourceTimezone", "UTC");
        payload.put("conflictReason", "MANUAL_PROTECTED_TARGET");
        if (againstMemoryId != null) {
            payload.put("proposedAgainstMemoryId", againstMemoryId);
            payload.put("proposedAgainstVersion", againstVersion);
        }
        EncryptedValue encrypted = crypto.encrypt(key, "ai_memory_item", rowId, 1, mapper.writeValueAsString(payload));
        // memoryId 由仓储自己生成（P1c 的纪律），所以这里必须取回来用，不能自己编一个。
        String memoryId = memory.add(new MemoryRepository.NewMemory(rowId, vaultId,
                "GLOBAL", 0L, kindOf(predicate), crypto.hmac(key, "predicate:" + predicate),
                crypto.hmac(key, "family:" + family), crypto.hmac(key, "value:" + value),
                MemoryRepository.STATUS_PENDING, encrypted.cipher(), encrypted.cryptoMeta(), "AUTO", false, false,
                null, null, asOf, asOf, asOf)).memoryId();
        memory.attachEvidence(new EvidenceDraft(null, vaultId, sessionId, turnId, null, "u0",
                "hmac-source-" + turnId, quote.cipher(), quote.cryptoMeta(), asOf), rowId);
        return memoryId;
    }

    private String addIn(String scopeType, long scopeId, String predicate, String value) {
        MemoryApiResult created = items.add(new AddInput(kindOf(predicate), scopeType, scopeId, predicate, null,
                value, null, null, null));
        assertEquals(201, created.status(), String.valueOf(created.body()));
        return String.valueOf(created.body().get("memoryId"));
    }

    private String itemStatus(String memoryId) {
        return jdbc.queryForObject("SELECT status FROM ai_memory_item WHERE memory_id = ?", String.class, memoryId);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> proposalsOf(MemoryApiResult result) {
        assertEquals(200, result.status(), String.valueOf(result.body()));
        return (List<Map<String, Object>>) result.body().get("proposals");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> firstItem(MemoryApiResult result) {
        assertEquals(200, result.status(), String.valueOf(result.body()));
        return ((List<Map<String, Object>>) result.body().get("items")).get(0);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> firstCandidate(MemoryApiResult result) {
        return ((List<Map<String, Object>>) ((Map<String, Object>) result.body().get("details")).get("candidates"))
                .get(0);
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

    private ProposalRow proposal(String proposalId) throws SQLException {
        return proposals.find(proposalId).orElseThrow();
    }

    private long vaultRevision() throws SQLException {
        return memory.readVault(vaultId).orElseThrow().revision();
    }

    private long vaultEpoch() throws SQLException {
        return memory.readVault(vaultId).orElseThrow().epoch();
    }

    private long sessionGeneration() {
        return jdbc.queryForObject("SELECT context_generation FROM ai_chat_session WHERE id = ?", Long.class,
                sessionId);
    }

    @SuppressWarnings("unchecked")
    private static String firstQuote(Map<String, Object> detail) {
        return (String) ((Map<String, Object>) ((java.util.List<Object>) detail.get("sources")).get(0))
                .get("quote");
    }

    private int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }
}
