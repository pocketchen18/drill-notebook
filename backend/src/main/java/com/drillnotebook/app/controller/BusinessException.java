package com.drillnotebook.app.controller;

/**
 * 业务异常：对外只暴露稳定的 error code 与用户可读的简要 message，
 * 内部细节（堆栈、原始 cause）由 ApiExceptionHandler 记录到日志。
 */
public class BusinessException extends RuntimeException {

    private final String code;

    public BusinessException(String code, String message) {
        super(message);
        this.code = code;
    }

    public BusinessException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
