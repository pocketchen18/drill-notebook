package com.drillnotebook.app.controller;

import com.drillnotebook.app.service.memory.MemoryApiResult;
import com.drillnotebook.app.service.memory.MemoryItemService;
import com.drillnotebook.app.service.memory.MemoryPolicyService;
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
import org.springframework.web.bind.annotation.RestController;

/**
 * 记忆管理 REST（contracts §3 的 memory 路由，全部挂在 {@code /api/ai} 下）。
 *
 * <p>控制器只拆请求、映射状态码，判定一律在服务层：响应形状是 {@code {code,message,retryable?,details?}}
 * 或成功体，由 {@link MemoryApiResult} 决定，不走全局异常处理器，因此错误里不会出现堆栈、SQL 或正文。</p>
 *
 * <p>两条硬规矩：主密码与会话密码只能走请求体（不进 querystring、不进日志）；
 * 编辑与删除必须带 {@code expectedVersion}，服务端不靠前端禁用按钮。</p>
 */
@RestController
@RequestMapping("/api/ai/memory")
public class MemoryController {

    private final MemoryVaultService vault;
    private final MemoryPolicyService policy;
    private final MemoryItemService items;

    public MemoryController(MemoryVaultService vault, MemoryPolicyService policy, MemoryItemService items) {
        this.vault = vault;
        this.policy = policy;
        this.items = items;
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
