package com.drillnotebook.app.service.memory;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 凭据与敏感内容的粗筛（执行方案 §5.3）。
 *
 * <p>口径：命中<b>凭据格式</b>一律拒收，手动录入也不行；其它敏感事实只在用户明确确认后才允许人工写入，
 * 自动提取链在送模型前还要再过一遍。这些规则不是完美 DLP，界面文案不得承诺"自动识别全部敏感数据"。</p>
 */
public final class MemorySensitivity {
    public enum Level {
        ORDINARY, SENSITIVE, CREDENTIAL
    }

    private static final List<Pattern> CREDENTIALS = List.of(
            Pattern.compile("(?i)(api[ _-]?key|access[ _-]?token|secret[ _-]?key|password|passwd|token|密码|密钥|验证码)"
                    + "\\s*[:：=]\\s*\\S{6,}"),
            Pattern.compile("\\bsk-[A-Za-z0-9_-]{16,}"),
            Pattern.compile("\\b(?:ghp|gho|ghu|ghs|ghr|xoxb|xoxp)-[A-Za-z0-9-]{16,}"),
            Pattern.compile("\\bAKIA[0-9A-Z]{16}\\b"),
            Pattern.compile("\\beyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{4,}\\.[A-Za-z0-9_-]{4,}"),
            Pattern.compile("(?i)-----BEGIN [A-Z ]*PRIVATE KEY-----"),
            Pattern.compile("(?i)\\bbearer\\s+[A-Za-z0-9._~+/=-]{16,}"),
            Pattern.compile("(?i)(验证码|动态码|校验码|otp)\\s*[:：=]?\\s*\\d{4,8}"),
            Pattern.compile("\\b\\d{15,19}\\b"),
            Pattern.compile("\\b\\d{6}(19|20)\\d{2}(0[1-9]|1[0-2])(0[1-9]|[12]\\d|3[01])\\d{3}[\\dXx]\\b"));

    private static final List<Pattern> SENSITIVE = List.of(
            Pattern.compile("\\b1[3-9]\\d{9}\\b"),
            Pattern.compile("(?i)[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}"),
            Pattern.compile("(住址|家住|门牌|小区|宿舍|号楼).{0,12}\\d"),
            Pattern.compile("(路|街|巷|弄)\\d{1,5}号"),
            Pattern.compile("(确诊|病历|就诊|吃药|服药|抑郁|焦虑|失眠|糖尿病|高血压|肿瘤|哮喘|肝炎)"),
            Pattern.compile("(银行卡|储蓄卡|信用卡|账户余额|基金账号|股票账户)"));

    private MemorySensitivity() {
    }

    /** 先判凭据（一律拒），再判其它敏感内容，都不命中才算普通。 */
    public static Level classify(String text) {
        if (text == null || text.isBlank()) {
            return Level.ORDINARY;
        }
        for (Pattern pattern : CREDENTIALS) {
            if (pattern.matcher(text).find()) {
                return Level.CREDENTIAL;
            }
        }
        for (Pattern pattern : SENSITIVE) {
            if (pattern.matcher(text).find()) {
                return Level.SENSITIVE;
            }
        }
        return Level.ORDINARY;
    }
}
