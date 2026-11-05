package com.drillnotebook.app.service.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.drillnotebook.app.service.memory.MemorySensitivity.Level;
import org.junit.jupiter.api.Test;

/**
 * 凭据与敏感内容粗筛（§5.3）：凭据格式一律拒收，其它敏感事实要用户明确确认，普通学习描述不能被误伤。
 * 这里同时钉住"规则不是完美 DLP"的边界——它只保证命中的形状被拦，不保证识别全部敏感数据。
 */
class MemorySensitivityTest {
    @Test
    void credentialShapesAreCaught() {
        assertEquals(Level.CREDENTIAL, MemorySensitivity.classify("我的 api key 是 sk-abcdefghijklmnopqrstuvwxyz"));
        assertEquals(Level.CREDENTIAL, MemorySensitivity.classify("password: hunter2hunter2"));
        assertEquals(Level.CREDENTIAL, MemorySensitivity.classify("密码：MyCat2026!"));
        assertEquals(Level.CREDENTIAL, MemorySensitivity.classify("验证码 123456 用于登录"));
        assertEquals(Level.CREDENTIAL, MemorySensitivity.classify("Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9"
                + ".eyJzdWIiOiIxMjM0NTY3ODkwIn0.dozjgNryP4J3jVmNHl0w5N_XgL0n3I9PlFUP0THsR8U"));
        assertEquals(Level.CREDENTIAL, MemorySensitivity.classify("-----BEGIN RSA PRIVATE KEY-----"));
        assertEquals(Level.CREDENTIAL, MemorySensitivity.classify("卡在 6222021234567890123 上"));
        assertEquals(Level.CREDENTIAL, MemorySensitivity.classify("身份证号 110101199003077777"));
        assertEquals(Level.CREDENTIAL, MemorySensitivity.classify("AKIAIOSFODNN7EXAMPLE"));
    }

    @Test
    void otherSensitiveFactsNeedConfirmation() {
        assertEquals(Level.SENSITIVE, MemorySensitivity.classify("我的邮箱是 me@example.com"));
        assertEquals(Level.SENSITIVE, MemorySensitivity.classify("手机号 13800138000"));
        assertEquals(Level.SENSITIVE, MemorySensitivity.classify("我的住址是文化路 12 号"));
        assertEquals(Level.SENSITIVE, MemorySensitivity.classify("确诊甲流后想放慢进度"));
        assertEquals(Level.SENSITIVE, MemorySensitivity.classify("信用卡账单快忘了"));
    }

    @Test
    void ordinaryStudyPreferencesAreNotBlocked() {
        assertEquals(Level.ORDINARY, MemorySensitivity.classify("以后解释算法先给例子"));
        assertEquals(Level.ORDINARY, MemorySensitivity.classify("我在准备雅思，目标 7 分"));
        assertEquals(Level.ORDINARY, MemorySensitivity.classify("每周两次课，每次 90 分钟"));
        assertEquals(Level.ORDINARY, MemorySensitivity.classify("题号 12345 讲得细一点"));
        assertEquals(Level.ORDINARY, MemorySensitivity.classify(null));
        assertEquals(Level.ORDINARY, MemorySensitivity.classify("   "));
    }
}
