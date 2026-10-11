package com.drillnotebook.app.service.memory;

import com.drillnotebook.app.repository.AiConfigRepository;
import com.drillnotebook.app.repository.MemoryJobRepository;
import com.drillnotebook.app.repository.MemoryRepository;
import com.drillnotebook.app.repository.MemorySettingsRepository;
import com.drillnotebook.app.repository.NotebookRepository;
import com.drillnotebook.app.service.AiService;
import com.drillnotebook.app.service.EmbeddingSpaceContracts;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.SQLException;
import java.time.ZoneId;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 记忆开关、外发授权与写入资格（执行方案 §5.1、§5.2、§6.2；contracts §3 的 settings/consents 行）。
 *
 * <p>三件事分开判定，谁也不继承谁：</p>
 * <ul>
 *   <li><b>开关</b>：{@code enabled/autoExtract/compressSession/autoApplyLowRisk} 各自独立，改开关
 *       永远不写授权列。</li>
 *   <li><b>授权</b>：指纹是 {@code purpose + normalizedEndpoint + apiFormat + model + policyVersion}
 *       的摘要。每次判定都拿<b>当前</b>配置重算比对，所以换 endpoint、换模型、换 API 格式、
 *       换 policy 版本或切换 chat/dedicated 都会让旧授权自动失效，不需要额外"撤销"代码。</li>
 *   <li><b>资格</b>：手动录入只要库存在且已解锁（不外发，所以不要授权）；自动提取与摘要还要开关
 *       和对应授权；注入还要 {@code enabled}。上限 2,000 ACTIVE / 500 PENDING 只拦自动新增，
 *       不暗中删人工记忆（§6.2）。</li>
 * </ul>
 *
 * <p>这里只存配置指纹（公开 endpoint + 模型名的摘要），不涉及个人事实键，因此用普通 SHA-256 即可；
 * 个人事实键必须走 {@link MemoryCryptoService#hmac} 的按库派生密钥（§6.1.3）。API key 只报"配了没有"，
 * 真能不能用由发送前的连通性检查决定（§5.1 换 key 不改变接收方）。</p>
 */
@Service
public class MemoryPolicyService {
    /** 进入授权指纹的策略版本；改动它会让所有已存授权失效。 */
    public static final String POLICY_VERSION = "memory-policy-v1";
    /** §6.2 的默认上限。 */
    public static final int ACTIVE_FACT_LIMIT = 2000;
    public static final int PENDING_FACT_LIMIT = MemoryRepository.PENDING_QUOTA;
    /** §8.1 的后台默认日限额。 */
    public static final int DEFAULT_DAILY_CALL_LIMIT = 20;
    public static final int DEFAULT_DAILY_TOKEN_LIMIT = 30_000;
    public static final String MODE_CHAT = "chat";
    public static final String MODE_DEDICATED = "dedicated";
    public static final String PURPOSE_USE = MemorySettingsRepository.PURPOSE_USE;
    public static final String PURPOSE_MAINTENANCE = MemorySettingsRepository.PURPOSE_MAINTENANCE;
    /** 客户端能改的 policy 键；其余（policyVersion、memoryHistoryFloor）只允许服务端推进。 */
    static final Set<String> CLIENT_POLICY_KEYS = Set.of("maintenanceModelMode", "dailyCallLimit",
            "dailyTokenLimit", "budgetTimezone");

    private static final Logger log = LoggerFactory.getLogger(MemoryPolicyService.class);
    private static final int MAX_CAS_ATTEMPTS = 3;

    private final MemorySettingsRepository settings;
    private final MemoryRepository memory;
    private final MemoryJobRepository jobs;
    private final AiConfigRepository configs;
    private final NotebookRepository notebooks;
    private final MemoryVaultService vault;
    private final ObjectMapper mapper = new ObjectMapper();

    public MemoryPolicyService(MemorySettingsRepository settings, MemoryRepository memory, MemoryJobRepository jobs,
                               AiConfigRepository configs, NotebookRepository notebooks, MemoryVaultService vault) {
        this.settings = settings;
        this.memory = memory;
        this.jobs = jobs;
        this.configs = configs;
        this.notebooks = notebooks;
        this.vault = vault;
    }

    /** 解析后的策略视图。 */
    public record Policy(int dailyCallLimit, int dailyTokenLimit, String maintenanceModelMode, String budgetTimezone,
                         long memoryHistoryFloor) {
    }

    /** 某用途当前解析出的真实外发目标（不含 key）。 */
    public record Target(String purpose, String slot, String provider, String host, String model, String apiFormat,
                         boolean configured, boolean keyPresent, String fingerprint) {
    }

    /** 授权状态：{@code granted} 只有在指纹与当前目标一致时才为真。 */
    public record ConsentStatus(String purpose, String state, boolean granted, Target target,
                                String storedFingerprint) {
    }

    /** 资格判定结果。 */
    public record Eligibility(Gate gate, String message) {
        public boolean allowed() {
            return gate == Gate.ALLOWED;
        }

        public static Eligibility ok() {
            return new Eligibility(Gate.ALLOWED, null);
        }

        public MemoryApiResult toResult() {
            return gate.toResult(message);
        }
    }

    /** 拦截原因；HTTP 映射集中在这里，免得每个调用方各猜一个状态码。 */
    public enum Gate {
        ALLOWED(200),
        MEMORY_DISABLED(409),
        AUTO_EXTRACT_DISABLED(409),
        COMPRESS_DISABLED(409),
        VAULT_MISSING(409),
        LOCKED(MemoryVaultService.HTTP_LOCKED),
        CONSENT_MISSING(409),
        CONSENT_STALE(409),
        TARGET_MISSING(409),
        SCOPE_INVALID(400),
        QUOTA_ACTIVE_FULL(409),
        QUOTA_PENDING_FULL(409);

        private final int status;

        Gate(int status) {
            this.status = status;
        }

        public int status() {
            return status;
        }

        public MemoryApiResult toResult(String message) {
            if (this == ALLOWED) {
                throw new IllegalStateException("ALLOWED 不是错误");
            }
            return MemoryApiResult.error(status, "MEMORY_" + name(), message,
                    this == LOCKED || this == QUOTA_PENDING_FULL, null);
        }
    }

    // ---- 读 ----

    /** 当前设置行（首次访问时按默认值初始化）。 */
    public MemorySettingsRepository.Settings current() throws SQLException {
        return settings.ensure(defaultPolicyJson(ZoneId.systemDefault()), MemoryTimes.now());
    }

    public Policy policyOf(MemorySettingsRepository.Settings row) {
        Map<String, Object> raw = readPolicy(row.policyJson());
        return new Policy(intOr(raw, "dailyCallLimit", DEFAULT_DAILY_CALL_LIMIT),
                intOr(raw, "dailyTokenLimit", DEFAULT_DAILY_TOKEN_LIMIT),
                stringOr(raw, "maintenanceModelMode", MODE_CHAT),
                stringOr(raw, "budgetTimezone", ZoneId.systemDefault().getId()),
                longOr(raw, "memoryHistoryFloor", 0L));
    }

    /** GET /memory/settings：脱敏设置 + 两种授权状态 + 实际目标 + 锁定状态（索引状态属 P5）。 */
    public MemoryApiResult snapshot() {
        try {
            MemorySettingsRepository.Settings row = current();
            Policy policy = policyOf(row);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("revision", row.revision());
            body.put("enabled", row.enabled());
            body.put("autoExtract", row.autoExtract());
            body.put("compressSession", row.compressSession());
            body.put("autoApplyLowRisk", row.autoApplyLowRisk());
            body.put("updatedAt", row.updatedAt());
            body.put("consentedAt", row.consentedAt());
            body.put("policyVersion", POLICY_VERSION);
            body.put("maintenanceModelMode", policy.maintenanceModelMode());
            body.put("dailyCallLimit", policy.dailyCallLimit());
            body.put("dailyTokenLimit", policy.dailyTokenLimit());
            body.put("budgetTimezone", policy.budgetTimezone());
            body.put("memoryHistoryFloor", policy.memoryHistoryFloor());
            body.put("consents", Map.of(PURPOSE_USE, consentStatus(PURPOSE_USE),
                    PURPOSE_MAINTENANCE, consentStatus(PURPOSE_MAINTENANCE)));
            body.put("limits", Map.of("activeFacts", ACTIVE_FACT_LIMIT, "pendingFacts", PENDING_FACT_LIMIT));
            body.putAll(vault.statusBody());
            return MemoryApiResult.of(200, body);
        } catch (SQLException error) {
            return storageError(error);
        }
    }

    // ---- 写设置 ----

    /**
     * PUT /memory/settings：按 revision CAS 改开关与允许客户端设置的 policy 键。
     * 撞版本返回 409 并带上最新 revision；绝不顺带写授权列。
     */
    public MemoryApiResult update(Long expectedRevision, Boolean enabled, Boolean autoExtract,
                                  Boolean compressSession, Boolean autoApplyLowRisk, Map<String, Object> policyPatch) {
        if (expectedRevision == null) {
            return MemoryApiResult.error(400, "MEMORY_REVISION_REQUIRED", "修改记忆设置必须带上读到的 revision");
        }
        MemoryApiResult patchError = policyPatchError(policyPatch);
        if (patchError != null) {
            return patchError;
        }
        try {
            MemorySettingsRepository.Settings row = current();
            Map<String, Object> merged = readPolicy(row.policyJson());
            if (policyPatch != null) {
                policyPatch.forEach((key, value) -> {
                    if (CLIENT_POLICY_KEYS.contains(key) && value != null) {
                        merged.put(key, normalizePatchValue(key, value));
                    }
                });
            }
            MemorySettingsRepository.WriteResult result = settings.write(expectedRevision,
                    enabled == null ? row.enabled() : enabled,
                    autoExtract == null ? row.autoExtract() : autoExtract,
                    compressSession == null ? row.compressSession() : compressSession,
                    autoApplyLowRisk == null ? row.autoApplyLowRisk() : autoApplyLowRisk,
                    writePolicy(merged), MemoryTimes.now());
            if (result.outcome() == MemorySettingsRepository.WriteOutcome.CONFLICT) {
                return MemoryApiResult.error(409, "MEMORY_SETTINGS_CONFLICT",
                        "记忆设置已被其它操作更新，请重载后再改", false,
                        Map.of("revision", result.settings().revision()));
            }
            log.info("记忆设置已更新: revision={}", result.settings().revision());
            return MemoryApiResult.of(200, Map.of("revision", result.settings().revision()));
        } catch (SQLException error) {
            return storageError(error);
        }
    }

    // ---- 授权 ----

    /**
     * POST /memory/consents：后端重新解析真实目标，只有客户端确认过的指纹与之一致才授权。
     * 目标没配、指纹不符都返回 409，并把真实目标（host/model/apiFormat/policyVersion）回给用户再看一眼。
     */
    public MemoryApiResult grantConsent(String purpose, String claimedFingerprint, String claimedPolicyVersion) {
        MemoryApiResult invalidPurpose = requirePurpose(purpose);
        if (invalidPurpose != null) {
            return invalidPurpose;
        }
        if (claimedFingerprint == null || claimedFingerprint.isBlank()) {
            return MemoryApiResult.error(400, "MEMORY_CONSENT_FINGERPRINT_REQUIRED", "必须提交用户确认过的目标指纹");
        }
        if (!POLICY_VERSION.equals(claimedPolicyVersion)) {
            return MemoryApiResult.error(409, "MEMORY_POLICY_VERSION_STALE", "授权凭的是另一版策略，需要重新确认",
                    false, Map.of("policyVersion", POLICY_VERSION));
        }
        Target target;
        try {
            target = targetOf(purpose);
        } catch (IllegalArgumentException error) {
            return MemoryApiResult.error(400, "MEMORY_ENDPOINT_INVALID", "当前模型的 endpoint 不是合法 URL");
        }
        if (!target.configured()) {
            return MemoryApiResult.error(409, "MEMORY_TARGET_MISSING",
                    "该用途的模型还没配置，先配好 endpoint 与模型再授权", false, Map.of("slot", target.slot()));
        }
        if (!target.fingerprint().equals(claimedFingerprint)) {
            return MemoryApiResult.error(409, "MEMORY_CONSENT_TARGET_MISMATCH",
                    "实际外发目标与你确认过的不一致，请把下面这个目标再确认一次", false, targetDetails(target));
        }
        try {
            current();   // 设置行是首次访问时才建的，授权写之前必须保证它在
            MemorySettingsRepository.Settings row = settings.grantConsent(purpose, target.fingerprint(),
                    MemoryTimes.now());
            log.info("记忆外发授权已授予: purpose={}, revision={}", purpose, row.revision());
            return MemoryApiResult.of(200, Map.of("revision", row.revision(), "purpose", purpose, "consented", true,
                    "target", targetDetails(target)));
        } catch (SQLException error) {
            return storageError(error);
        }
    }

    /** DELETE /memory/consents/{purpose}：撤销并作废按旧策略排队的任务。 */
    public MemoryApiResult revokeConsent(String purpose) {
        MemoryApiResult invalidPurpose = requirePurpose(purpose);
        if (invalidPurpose != null) {
            return invalidPurpose;
        }
        try {
            current();   // 同上：撤销也要先保证设置行存在
            MemorySettingsRepository.Settings row = settings.revokeConsent(purpose, MemoryTimes.now());
            int cancelled = PURPOSE_MAINTENANCE.equals(purpose)
                    ? jobs.supersedeBelowPolicyRevision(row.revision(), MemoryTimes.now()) : 0;
            log.info("记忆外发授权已撤销: purpose={}, revision={}, 作废任务 {} 个", purpose, row.revision(), cancelled);
            return MemoryApiResult.of(204, Map.of("revision", row.revision(), "jobsSuperseded", cancelled));
        } catch (SQLException error) {
            return storageError(error);
        }
    }

    /** 授权状态：每次都按当前配置重算指纹，所以"过期"无需单独存储。 */
    public ConsentStatus consentStatus(String purpose) throws SQLException {
        MemorySettingsRepository.Settings row = current();
        String stored = row.consentFingerprint(purpose);
        Target target;
        try {
            target = targetOf(purpose);
        } catch (IllegalArgumentException error) {
            target = new Target(purpose, slotOf(purpose), null, null, null, null, false, false, null);
        }
        if (!target.configured()) {
            return new ConsentStatus(purpose, "TARGET_MISSING", false, target, stored);
        }
        if (stored == null || stored.isBlank()) {
            return new ConsentStatus(purpose, "MISSING", false, target, null);
        }
        return target.fingerprint().equals(stored)
                ? new ConsentStatus(purpose, "GRANTED", true, target, stored)
                : new ConsentStatus(purpose, "STALE", false, target, stored);
    }

    /** 某用途的真实外发目标：MEMORY_USE 恒用主模型；维护按 chat/dedicated 切换，不擅自兜底（§5.1）。 */
    public Target targetOf(String purpose) {
        String slot = slotOf(purpose);
        AiConfigRepository.ConfigRow row = PURPOSE_USE.equals(purpose)
                ? configs.findChat()
                : MODE_DEDICATED.equals(maintenanceMode()) ? configs.findMemory() : configs.findChat();
        boolean keyPresent = row != null && row.encryptedKey() != null && !row.encryptedKey().isBlank();
        if (row == null || row.endpoint() == null || row.endpoint().isBlank()
                || row.model() == null || row.model().isBlank()) {
            return new Target(purpose, slot, row == null ? null : row.provider(), null, null, null, false,
                    keyPresent, null);
        }
        String normalized = EmbeddingSpaceContracts.normalizeEndpoint(row.endpoint());
        String apiFormat = AiService.apiFormatOf(row);
        return new Target(purpose, slot, row.provider(), hostOf(normalized), row.model(), apiFormat, true, keyPresent,
                consentFingerprint(purpose, normalized, apiFormat, row.model()));
    }

    /** 授权指纹：purpose + 归一化 endpoint + apiFormat + model + policyVersion 的 SHA-256 摘要。 */
    public String consentFingerprint(String purpose, String normalizedEndpoint, String apiFormat, String model) {
        String canonical = String.join("|", purpose, normalizedEndpoint, apiFormat, model, POLICY_VERSION);
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 不可用", error);
        }
    }

    // ---- 资格判定 ----

    /** 手动录入：库存在且已解锁 + scope 合法；不需要开关，也不需要外发授权（§5.1）。 */
    public Eligibility manualWriteEligibility(String scopeType, long scopeId) throws SQLException {
        Eligibility state = vaultStateGate();
        return state.allowed() ? scopeEligibility(scopeType, scopeId) : state;
    }

    /** 自动提取：开关 + 解锁 + 维护授权 + scope + 上限，任一不满足都不算一次远程尝试（§11.2）。 */
    public Eligibility extractionEligibility(String scopeType, long scopeId) throws SQLException {
        MemorySettingsRepository.Settings row = current();
        if (!row.enabled()) {
            return new Eligibility(Gate.MEMORY_DISABLED, "长期记忆开关是关的");
        }
        if (!row.autoExtract()) {
            return new Eligibility(Gate.AUTO_EXTRACT_DISABLED, "自动提取开关是关的");
        }
        Eligibility state = vaultStateGate();
        if (!state.allowed()) {
            return state;
        }
        Eligibility scope = scopeEligibility(scopeType, scopeId);
        if (!scope.allowed()) {
            return scope;
        }
        Eligibility consent = consentGate(PURPOSE_MAINTENANCE);
        return consent.allowed() ? quotaGate() : consent;
    }

    /** 会话摘要：{@code compressSession} 独立判断，长期记忆关闭也能压（§2.1）。 */
    public Eligibility summaryEligibility() throws SQLException {
        MemorySettingsRepository.Settings row = current();
        if (!row.compressSession()) {
            return new Eligibility(Gate.COMPRESS_DISABLED, "会话压缩开关是关的");
        }
        if (memory.readEnvelope().isEmpty()) {
            return new Eligibility(Gate.VAULT_MISSING, "摘要也要本地记忆库保护内容");
        }
        if (vault.currentKey().isEmpty()) {
            return new Eligibility(Gate.LOCKED, "记忆库锁定中，摘要任务在等待解锁");
        }
        return consentGate(PURPOSE_MAINTENANCE);
    }

    /** 注入使用：{@code enabled} + 解锁 + MEMORY_USE 授权有效。 */
    public Eligibility useEligibility() throws SQLException {
        MemorySettingsRepository.Settings row = current();
        if (!row.enabled()) {
            return new Eligibility(Gate.MEMORY_DISABLED, "长期记忆开关是关的");
        }
        if (vault.currentKey().isEmpty()) {
            return new Eligibility(Gate.LOCKED, "记忆库锁定中，本轮不提供记忆");
        }
        return consentGate(PURPOSE_USE);
    }

    /**
     * 遗忘边界单调推进：只允许往前走，且走服务端 CAS，客户端 PUT 改不到（§2.9）。
     * 没变化时原样返回，不推版本。
     */
    public MemorySettingsRepository.Settings advanceHistoryFloor(long throughTurnId) throws SQLException {
        for (int attempt = 0; attempt < MAX_CAS_ATTEMPTS; attempt++) {
            MemorySettingsRepository.Settings row = current();
            Map<String, Object> policy = readPolicy(row.policyJson());
            long floor = longOr(policy, "memoryHistoryFloor", 0L);
            if (throughTurnId <= floor) {
                return row;
            }
            policy.put("memoryHistoryFloor", throughTurnId);
            MemorySettingsRepository.WriteResult result = settings.write(row.revision(), row.enabled(),
                    row.autoExtract(), row.compressSession(), row.autoApplyLowRisk(), writePolicy(policy),
                    MemoryTimes.now());
            if (result.outcome() == MemorySettingsRepository.WriteOutcome.APPLIED) {
                return result.settings();
            }
        }
        throw new SQLException("遗忘边界推进连续撞上并发写入，请重试");
    }

    // ---- 内部 ----

    private Eligibility vaultStateGate() throws SQLException {
        if (memory.readEnvelope().isEmpty()) {
            return new Eligibility(Gate.VAULT_MISSING, "还没有记忆库，先创建再录入");
        }
        return vault.currentKey().isEmpty()
                ? new Eligibility(Gate.LOCKED, "记忆库已锁定，需要重新解锁")
                : Eligibility.ok();
    }

    private Eligibility consentGate(String purpose) throws SQLException {
        ConsentStatus status = consentStatus(purpose);
        if (status.granted()) {
            return Eligibility.ok();
        }
        return switch (status.state()) {
            case "TARGET_MISSING" -> new Eligibility(Gate.TARGET_MISSING, "外发目标还没配置好，不能发送");
            case "STALE" -> new Eligibility(Gate.CONSENT_STALE, "外发目标或策略版本变了，需要重新确认授权");
            default -> new Eligibility(Gate.CONSENT_MISSING, "该用途还没有获得外发授权");
        };
    }

    private Eligibility scopeEligibility(String scopeType, long scopeId) {
        if ("GLOBAL".equals(scopeType)) {
            return scopeId == 0
                    ? Eligibility.ok()
                    : new Eligibility(Gate.SCOPE_INVALID, "GLOBAL 作用域的 scopeId 必须是 0");
        }
        if ("NOTEBOOK".equals(scopeType)) {
            if (scopeId <= 0) {
                return new Eligibility(Gate.SCOPE_INVALID, "NOTEBOOK 作用域必须带正的笔记本 ID");
            }
            return notebooks.exists(scopeId)
                    ? Eligibility.ok()
                    : new Eligibility(Gate.SCOPE_INVALID, "笔记本 " + scopeId + " 不存在");
        }
        return new Eligibility(Gate.SCOPE_INVALID, "非法的记忆作用域: " + scopeType);
    }

    private Eligibility quotaGate() throws SQLException {
        String vaultId = memory.readEnvelope().map(MemoryRepository.VaultEnvelope::vaultId).orElse(null);
        if (vaultId == null) {
            return new Eligibility(Gate.VAULT_MISSING, "还没有记忆库");
        }
        if (memory.activeCount(vaultId) >= ACTIVE_FACT_LIMIT) {
            return new Eligibility(Gate.QUOTA_ACTIVE_FULL,
                    "ACTIVE 事实已达 " + ACTIVE_FACT_LIMIT + " 条上限，自动新增暂停，请先整理");
        }
        if (memory.pendingCount(vaultId) >= PENDING_FACT_LIMIT) {
            return new Eligibility(Gate.QUOTA_PENDING_FULL,
                    "待确认条目已达 " + PENDING_FACT_LIMIT + " 条上限，自动新增暂停");
        }
        return Eligibility.ok();
    }

    private String maintenanceMode() {
        try {
            return policyOf(current()).maintenanceModelMode();
        } catch (SQLException error) {
            return MODE_CHAT;
        }
    }

    private static MemoryApiResult requirePurpose(String purpose) {
        if (!PURPOSE_USE.equals(purpose) && !PURPOSE_MAINTENANCE.equals(purpose)) {
            return MemoryApiResult.error(400, "MEMORY_PURPOSE_INVALID",
                    "授权用途只能是 MEMORY_USE 或 MEMORY_MAINTENANCE");
        }
        return null;
    }

    private static String slotOf(String purpose) {
        return PURPOSE_USE.equals(purpose) ? AiConfigRepository.PURPOSE_CHAT : AiConfigRepository.PURPOSE_MEMORY;
    }

    private static Map<String, Object> targetDetails(Target target) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("slot", target.slot());
        details.put("host", target.host());
        details.put("model", target.model());
        details.put("apiFormat", target.apiFormat());
        details.put("policyVersion", POLICY_VERSION);
        details.put("fingerprint", target.fingerprint());
        return details;
    }

    /** 客户端 policy 补丁的白名单与取值校验：不合法就给出可直接回给前端的 400。 */
    private static MemoryApiResult policyPatchError(Map<String, Object> patch) {
        if (patch == null) {
            return null;
        }
        for (Map.Entry<String, Object> entry : patch.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            if (value == null) {
                continue;
            }
            if (!CLIENT_POLICY_KEYS.contains(key)) {
                return MemoryApiResult.error(400, "MEMORY_POLICY_KEY_READONLY",
                        "设置项 " + key + " 由服务端维护，客户端不能改");
            }
            switch (key) {
                case "maintenanceModelMode" -> {
                    String mode = String.valueOf(value);
                    if (!MODE_CHAT.equals(mode) && !MODE_DEDICATED.equals(mode)) {
                        return MemoryApiResult.error(400, "MEMORY_MAINTENANCE_MODE_INVALID",
                                "维护模型模式只能是 chat 或 dedicated");
                    }
                }
                case "dailyCallLimit" -> {
                    int limit = asInt(value);
                    if (limit < 1 || limit > 1000) {
                        return MemoryApiResult.error(400, "MEMORY_DAILY_CALL_LIMIT_INVALID",
                                "每日维护调用次数上限必须在 1..1000");
                    }
                }
                case "dailyTokenLimit" -> {
                    int limit = asInt(value);
                    if (limit < 1000 || limit > 1_000_000) {
                        return MemoryApiResult.error(400, "MEMORY_DAILY_TOKEN_LIMIT_INVALID",
                                "每日维护 Token 上限必须在 1000..1000000");
                    }
                }
                case "budgetTimezone" -> {
                    try {
                        ZoneId.of(String.valueOf(value));
                    } catch (RuntimeException error) {
                        return MemoryApiResult.error(400, "MEMORY_TIMEZONE_INVALID", "时区名无法识别");
                    }
                }
                default -> {
                    return MemoryApiResult.error(400, "MEMORY_POLICY_KEY_READONLY",
                            "设置项 " + key + " 由服务端维护，客户端不能改");
                }
            }
        }
        return null;
    }

    private static Object normalizePatchValue(String key, Object value) {
        if ("budgetTimezone".equals(key)) {
            return String.valueOf(value).trim();
        }
        if ("maintenanceModelMode".equals(key)) {
            return String.valueOf(value).trim().toLowerCase(Locale.ROOT);
        }
        return value instanceof Number number ? number.intValue() : value;
    }

    private static int asInt(Object value) {
        return value instanceof Number number ? number.intValue() : -1;
    }

    static String defaultPolicyJson(ZoneId zone) {
        Map<String, Object> policy = new LinkedHashMap<>();
        policy.put("policyVersion", POLICY_VERSION);
        policy.put("maintenanceModelMode", MODE_CHAT);
        policy.put("dailyCallLimit", DEFAULT_DAILY_CALL_LIMIT);
        policy.put("dailyTokenLimit", DEFAULT_DAILY_TOKEN_LIMIT);
        policy.put("budgetTimezone", zone.getId());
        policy.put("memoryHistoryFloor", 0L);
        try {
            return new ObjectMapper().writeValueAsString(policy);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("默认记忆策略无法序列化", error);
        }
    }

    private Map<String, Object> readPolicy(String json) {
        String source = json == null || json.isBlank() ? defaultPolicyJson(ZoneId.systemDefault()) : json;
        try {
            return mapper.readValue(source, new TypeReference<LinkedHashMap<String, Object>>() {
            });
        } catch (Exception error) {
            log.warn("记忆策略 JSON 解析失败，按默认值继续: {}", error.getClass().getSimpleName());
            try {
                return mapper.readValue(defaultPolicyJson(ZoneId.systemDefault()),
                        new TypeReference<LinkedHashMap<String, Object>>() {
                        });
            } catch (Exception fatal) {
                return new LinkedHashMap<>();
            }
        }
    }

    private String writePolicy(Map<String, Object> policy) throws SQLException {
        policy.put("policyVersion", POLICY_VERSION);
        try {
            return mapper.writeValueAsString(policy);
        } catch (JsonProcessingException error) {
            throw new SQLException("记忆策略无法序列化", error);
        }
    }

    private static String hostOf(String normalizedEndpoint) {
        try {
            return URI.create(normalizedEndpoint).getHost();
        } catch (RuntimeException error) {
            return normalizedEndpoint;
        }
    }

    private static int intOr(Map<String, Object> values, String key, int fallback) {
        Object value = values.get(key);
        return value instanceof Number number ? number.intValue() : fallback;
    }

    private static long longOr(Map<String, Object> values, String key, long fallback) {
        Object value = values.get(key);
        return value instanceof Number number ? number.longValue() : fallback;
    }

    private static String stringOr(Map<String, Object> values, String key, String fallback) {
        Object value = values.get(key);
        return value == null || String.valueOf(value).isBlank() ? fallback : String.valueOf(value);
    }

    private MemoryApiResult storageError(Exception error) {
        log.warn("记忆设置存储层失败: {}", error.getMessage());
        return MemoryApiResult.error(500, "MEMORY_STORAGE_UNAVAILABLE", "记忆设置暂时不可读写");
    }
}
