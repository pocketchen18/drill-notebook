package com.drillnotebook.app.service.web;

/** Only these codes and an HTTP status may reach activities; upstream bodies are never user-facing errors. */
public final class WebFailure extends RuntimeException {
    public enum Code {
        AUTH_REQUIRED, RATE_LIMITED, QUOTA_EXHAUSTED, TIME_LIMIT, CANCELLED, UNSAFE_URL,
        NETWORK_ERROR, INVALID_ARGUMENT, EMPTY_CONTENT, LOGIN_REQUIRED, CONTENT_TOO_LARGE,
        UNSUPPORTED_CONTENT, UPSTREAM_ERROR, COOLDOWN, KEY_UNAVAILABLE, LIMIT_REACHED, NATIVE_UNSUPPORTED, DISABLED
    }
    private final Code code;
    private final Integer httpStatus;
    private final Long retryAt;

    public WebFailure(Code code) { this(code, null, null); }
    public WebFailure(Code code, Integer httpStatus, Long retryAt) {
        super(code.name());
        this.code = code;
        this.httpStatus = httpStatus;
        this.retryAt = retryAt;
    }
    public Code code() { return code; }
    public Integer httpStatus() { return httpStatus; }
    public Long retryAt() { return retryAt; }
}
