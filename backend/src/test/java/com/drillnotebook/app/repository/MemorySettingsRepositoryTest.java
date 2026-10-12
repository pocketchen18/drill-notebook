package com.drillnotebook.app.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.drillnotebook.app.config.DatabaseInitializer;
import com.drillnotebook.app.config.ImmediateTransaction;
import com.drillnotebook.app.memory.Schema9Fixture;
import com.drillnotebook.app.memory.TestWorkspace;
import com.drillnotebook.app.repository.MemorySettingsRepository.Settings;
import com.drillnotebook.app.repository.MemorySettingsRepository.WriteOutcome;
import java.nio.file.Path;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 记忆设置单行（contracts §2.1）：默认值、版本 CAS、授权列与开关列互不越界。
 */
class MemorySettingsRepositoryTest {
    private static final String NOW = "2026-10-12T00:00:00.000Z";
    private static final String LATER = "2026-10-12T00:00:01.000Z";
    /** 仓储把 policy_json 当不透明串，这里不需要知道策略字段。 */
    private static final String DEFAULT_POLICY = "{\"policyVersion\":\"memory-policy-v1\"}";

    private Path appRoot;
    private DataSource dataSource;
    private JdbcTemplate jdbc;
    private MemorySettingsRepository settings;

    @BeforeEach
    void createDatabase() throws Exception {
        appRoot = TestWorkspace.scratchDirectory("p2-settings-");
        dataSource = Schema9Fixture.dataSource(appRoot);
        new DatabaseInitializer(dataSource, Schema9Fixture.resolver(appRoot)).initialize();
        jdbc = new JdbcTemplate(dataSource);
        settings = new MemorySettingsRepository(dataSource, new ImmediateTransaction(dataSource));
    }

    @AfterEach
    void removeDatabase() throws Exception {
        TestWorkspace.deleteRecursively(appRoot);
    }

    @Test
    void firstReadCreatesTheSingletonWithDocumentedDefaults() throws Exception {
        assertTrue(settings.find().isEmpty(), "迁移不自动建设置行");

        Settings row = settings.ensure(DEFAULT_POLICY, NOW);
        assertEquals(1L, row.revision());
        assertFalse(row.enabled());
        assertFalse(row.autoExtract());
        assertFalse(row.compressSession());
        assertTrue(row.autoApplyLowRisk(), "低风险自动应用默认可用，但仍要 autoExtract 先开");
        assertEquals(DEFAULT_POLICY, row.policyJson());
        assertNull(row.useConsentFingerprint());
        assertNull(row.maintenanceConsentFingerprint());
        assertNull(row.consentedAt());

        assertEquals(row.revision(), settings.ensure(DEFAULT_POLICY, LATER).revision());
        assertEquals(1L, count(), "重复 ensure 只有一行");
    }

    @Test
    void switchWritesOnlyLandOnTheExpectedRevision() throws Exception {
        settings.ensure(DEFAULT_POLICY, NOW);

        var applied = settings.write(1, true, true, false, true, DEFAULT_POLICY, LATER);
        assertEquals(WriteOutcome.APPLIED, applied.outcome());
        assertEquals(2L, applied.settings().revision());
        assertTrue(applied.settings().enabled());
        assertTrue(applied.settings().autoExtract());

        var stale = settings.write(1, false, false, true, false, DEFAULT_POLICY, LATER);
        assertEquals(WriteOutcome.CONFLICT, stale.outcome());
        assertEquals(2L, stale.settings().revision());
        assertTrue(stale.settings().enabled(), "撞版本时一行都不改");
        assertFalse(stale.settings().compressSession());
    }

    @Test
    void consentColumnsSurviveSwitchWrites() throws Exception {
        settings.ensure(DEFAULT_POLICY, NOW);
        settings.grantConsent(MemorySettingsRepository.PURPOSE_USE, "fp-use", LATER);

        settings.write(2, true, false, false, true, DEFAULT_POLICY, LATER);

        Settings row = settings.require();
        assertEquals("fp-use", row.useConsentFingerprint(), "改开关不能顺带撤销授权");
        assertEquals(3L, row.revision());
    }

    @Test
    void grantAndRevokeTouchOnlyTheirOwnPurpose() throws Exception {
        settings.ensure(DEFAULT_POLICY, NOW);

        Settings granted = settings.grantConsent(MemorySettingsRepository.PURPOSE_MAINTENANCE, "fp-maint", LATER);
        assertNull(granted.useConsentFingerprint());
        assertEquals("fp-maint", granted.maintenanceConsentFingerprint());
        assertEquals("fp-maint", granted.consentFingerprint(MemorySettingsRepository.PURPOSE_MAINTENANCE));
        assertTrue(granted.consented(MemorySettingsRepository.PURPOSE_MAINTENANCE));
        assertFalse(granted.consented(MemorySettingsRepository.PURPOSE_USE));
        assertEquals(LATER, granted.consentedAt());
        assertEquals(2L, granted.revision());

        Settings revoked = settings.revokeConsent(MemorySettingsRepository.PURPOSE_MAINTENANCE, NOW);
        assertNull(revoked.maintenanceConsentFingerprint());
        assertFalse(revoked.consented(MemorySettingsRepository.PURPOSE_MAINTENANCE));
        assertEquals(3L, revoked.revision());
    }

    @Test
    void bumpRevisionLeavesSwitchesAndConsentsAlone() throws Exception {
        settings.ensure(DEFAULT_POLICY, NOW);
        Settings before = settings.grantConsent(MemorySettingsRepository.PURPOSE_USE, "fp-use", LATER);

        Settings after = settings.bumpRevision(LATER);

        assertEquals(before.revision() + 1, after.revision());
        assertEquals("fp-use", after.useConsentFingerprint());
        assertFalse(after.enabled());
    }

    @Test
    void unknownPurposeOrMissingRowFailLoudly() throws Exception {
        assertThrows(IllegalArgumentException.class,
                () -> settings.grantConsent("MEMORY_WHATEVER", "fp", NOW));
        assertThrows(IllegalArgumentException.class,
                () -> settings.revokeConsent(null, NOW));
        assertThrows(SQLException.class, () -> settings.require());
        assertThrows(SQLException.class,
                () -> settings.write(1, true, true, true, true, DEFAULT_POLICY, NOW));
    }

    private long count() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM ai_memory_settings", Long.class);
    }
}
