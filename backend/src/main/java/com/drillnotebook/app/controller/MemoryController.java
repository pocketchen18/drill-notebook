package com.drillnotebook.app.controller;

import com.drillnotebook.app.service.memory.MemoryApiResult;
import com.drillnotebook.app.service.memory.MemoryClearService;
import com.drillnotebook.app.service.memory.MemoryItemService;
import com.drillnotebook.app.service.memory.MemoryPolicyService;
import com.drillnotebook.app.service.memory.MemoryReviewService;
import com.drillnotebook.app.service.memory.MemoryVaultService;
import com.drillnotebook.app.service.memory.MemoryVaultService.SessionUnlock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 记忆管理 REST（contracts §3 的 memory 路由，全部挂在 {@code /api/ai} 下）。
 *
 * <p>控制器只拆请求、映射状态码，判定一律在服务层：响应形状是 {@code {code,message,retryable?,details?}}
 * 或成功体，由 {@link MemoryApiResult} 决定，不走全局异常处理器，因此错误里不会出现堆栈、SQL 或正文。</p>
 *
 * <p>两条硬规矩：主密码、会话密码与用户原话只能走请求体（不进 querystring、不进日志；GET 列表只允许
 * scope/limit/cursor 这类非内容筛选条件）；
 * 编辑与删除必须带 {@code expectedVersion}，服务端不靠前端禁用按钮。</p>
 */
@RestController
@RequestMapping("/api/ai/memory")
public class MemoryController {

    private final MemoryVaultService vault;
    private final MemoryPolicyService policy;
    private final MemoryItemService items;
    private final MemoryReviewService reviews;
    private final MemoryClearService clears;

    public MemoryController(MemoryVaultService vault, MemoryPolicyService policy, MemoryItemService items,
                            MemoryReviewService reviews, MemoryClearService clears) {
        this.vault = vault;
        this.policy = policy;
        this.items = items;
        this.reviews = reviews;
        this.clears = clears;
    }

    // ---- 设置与授权 ----

    @GetMapping("/settings")
    public ResponseEntity<Map<String, Object>> settings() {
        return map(policy.snapshot());
    }

    @PutMapping("/settings")
    public ResponseEntity<Map<String, Object>> updateSettings(@RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> payload = body == null ? Map.of() : body;
        Map<String, Object> policyPatch = mapOf(payload.get("policy"));
        return map(policy.update(longObject(payload, "revision"), boolOf(payload, "enabled"),
                boolOf(payload, "autoExtract"), boolOf(payload, "compressSession"),
                boolOf(payload, "autoApplyLowRisk"), policyPatch));
    }

    @PostMapping("/consents")
    public ResponseEntity<Map<String, Object>> grantConsent(@RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> payload = body == null ? Map.of() : body;
        return map(policy.grantConsent(string(payload, "purpose"), string(payload, "targetFingerprint"),
                string(payload, "policyVersion")));
    }

    @DeleteMapping("/consents/{purpose}")
    public ResponseEntity<Map<String, Object>> revokeConsent(@PathVariable String purpose) {
        return map(policy.revokeConsent(purpose));
    }

    // ---- 记忆库 ----

    @PostMapping("/vault")
    public ResponseEntity<Map<String, Object>> createVault(@RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> payload = body == null ? Map.of() : body;
        return map(vault.create(string(payload, "mode"), string(payload, "password"),
                string(payload, "operationId")));
    }

    /** 解锁：密码与会话解锁材料只从请求体进来（旧 GET 的 query 密码参数不复用）。 */
    @PostMapping("/vault/unlock")
    public ResponseEntity<Map<String, Object>> unlock(@RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> payload = body == null ? Map.of() : body;
        return map(vault.unlock(string(payload, "password"), sessionUnlocks(payload.get("sessionUnlocks"))));
    }

    @PostMapping("/vault/lock")
    public ResponseEntity<Map<String, Object>> lock() {
        return map(vault.lock());
    }

    @PutMapping("/vault/password")
    public ResponseEntity<Map<String, Object>> changePassword(
            @RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> payload = body == null ? Map.of() : body;
        return map(vault.changePassword(string(payload, "oldPassword"), string(payload, "password")));
    }

    @PostMapping("/vault/reset")
    public ResponseEntity<Map<String, Object>> resetVault(@RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> payload = body == null ? Map.of() : body;
        return map(vault.reset(Boolean.TRUE.equals(boolOf(payload, "confirm")), string(payload, "mode"),
                string(payload, "password"), string(payload, "operationId")));
    }

    // ---- 条目 ----

    @PostMapping("/items")
    public ResponseEntity<Map<String, Object>> addItem(@RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> payload = body == null ? Map.of() : body;
        return map(items.add(new MemoryItemService.AddInput(string(payload, "kind"), string(payload, "scopeType"),
                longObject(payload, "scopeId"), string(payload, "predicate"), string(payload, "family"),
                string(payload, "value"), string(payload, "validUntil"), boolOf(payload, "sensitiveConfirmed"),
                string(payload, "operationId"))));
    }

    @GetMapping("/items/{memoryId}")
    public ResponseEntity<Map<String, Object>> getItem(@PathVariable String memoryId) {
        return map(items.get(memoryId));
    }

    @PatchMapping("/items/{memoryId}")
    public ResponseEntity<Map<String, Object>> editItem(@PathVariable String memoryId,
            @RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> payload = body == null ? Map.of() : body;
        Integer expectedVersion = intOf(payload, "expectedVersion");
        return map(items.edit(memoryId, new MemoryItemService.EditInput(expectedVersion, string(payload, "predicate"),
                string(payload, "family"), string(payload, "value"), string(payload, "validUntil"),
                string(payload, "status"))));
    }

    /** 删除走 body 传 expectedVersion 与 forget，不放 URL。 */
    @DeleteMapping("/items/{memoryId}")
    public ResponseEntity<Map<String, Object>> deleteItem(@PathVariable String memoryId,
            @RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> payload = body == null ? Map.of() : body;
        boolean forget = Boolean.TRUE.equals(boolOf(payload, "forget"));
        return map(items.delete(memoryId, intOf(payload, "expectedVersion"), forget, string(payload, "operationId")));
    }

    /**
     * 审阅一条待确认候选（§3）：decision 与版本都从请求体进来；只有 {@code replace} 才需要点名旧目标。
     */
    @PostMapping("/items/{memoryId}/review")
    public ResponseEntity<Map<String, Object>> reviewItem(@PathVariable String memoryId,
            @RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> payload = body == null ? Map.of() : body;
        return map(reviews.reviewItem(memoryId, new MemoryReviewService.ItemReviewInput(
                intOf(payload, "expectedVersion"), string(payload, "decision"), string(payload, "targetMemoryId"),
                intOf(payload, "expectedTargetVersion"), string(payload, "operationId"))));
    }

    /**
     * 建议列表（§3 定为 GET）：只有非内容筛选条件走查询串——scope 是枚举加数字、游标是不透明串，
     * 都不含用户原话；正文只在响应里出现，且必须已解锁（§3 末段）。
     */
    @GetMapping("/proposals")
    public ResponseEntity<Map<String, Object>> proposals(
            @RequestParam(name = "scopeType", required = false) String scopeType,
            @RequestParam(name = "scopeId", required = false) Long scopeId,
            @RequestParam(name = "limit", required = false) Integer limit,
            @RequestParam(name = "cursor", required = false) String cursor) {
        return map(reviews.list(scopeType, scopeId, limit, cursor));
    }

    /**
     * 审阅一条替换/撤销建议（§4.4）：decision 与两个 expectedVersion 都从请求体进来，
     * 采纳/并存必须带上用户看到的"当前值"版本；409 只回元信息，正文不随错误返回。
     */
    @PostMapping("/proposals/{proposalId}/review")
    public ResponseEntity<Map<String, Object>> reviewProposal(@PathVariable String proposalId,
            @RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> payload = body == null ? Map.of() : body;
        return map(reviews.review(proposalId, new MemoryReviewService.ReviewInput(intOf(payload, "expectedVersion"),
                intOf(payload, "expectedTargetVersion"), string(payload, "decision"),
                string(payload, "operationId"))));
    }

    /** 清空全部 AI 记忆（§11.3）：confirm 与 expectedVaultEpoch 都在请求体里；没删完回 202，不假 204。 */
    @PostMapping("/clear")
    public ResponseEntity<Map<String, Object>> clear(@RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> payload = body == null ? Map.of() : body;
        return map(clears.clear(Boolean.TRUE.equals(boolOf(payload, "confirm")), longObject(payload, "expectedVaultEpoch"),
                string(payload, "operationId")));
    }

    /** 管理页列表与本地检索：query 放请求体，不进 URL（防访问日志留原话）。 */
    @PostMapping("/search")
    public ResponseEntity<Map<String, Object>> search(@RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> payload = body == null ? Map.of() : body;
        return map(items.list(string(payload, "scopeType"), longObject(payload, "scopeId"),
                string(payload, "status"), string(payload, "query"), intOf(payload, "limit"),
                string(payload, "cursor")));
    }

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> status() {
        return map(items.status());
    }

    // ---- 映射 ----

    private static ResponseEntity<Map<String, Object>> map(MemoryApiResult result) {
        if (result.status() == 204) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.status(result.status()).body(result.body());
    }

    private static List<SessionUnlock> sessionUnlocks(Object raw) {
        List<SessionUnlock> unlocks = new ArrayList<>();
        if (!(raw instanceof List<?> entries)) {
            return unlocks;
        }
        for (Object entry : entries) {
            if (entry instanceof Map<?, ?> map) {
                Object id = map.get("sessionId");
                Object password = map.get("password");
                if (id instanceof Number number && password != null) {
                    unlocks.add(new SessionUnlock(number.longValue(), String.valueOf(password)));
                }
            }
        }
        return unlocks;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapOf(Object raw) {
        return raw instanceof Map<?, ?> ? (Map<String, Object>) raw : null;
    }

    private static String string(Map<String, Object> body, String key) {
        Object value = body.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private static Boolean boolOf(Map<String, Object> body, String key) {
        Object value = body.get(key);
        if (value == null) {
            return null;
        }
        return value instanceof Boolean flag ? flag : Boolean.parseBoolean(String.valueOf(value));
    }

    private static Integer intOf(Map<String, Object> body, String key) {
        Long value = longObject(body, key);
        return value == null ? null : value.intValue();
    }

    private static Long longObject(Map<String, Object> body, String key) {
        Object value = body.get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value).trim());
        } catch (NumberFormatException error) {
            return null;
        }
    }
}
