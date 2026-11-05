package com.drillnotebook.app.service;

/**
 * 聊天加密失败：写路径必须停下来（fail-closed），不能把正文降级成明文。
 */
public class ChatCryptoException extends RuntimeException {
    public ChatCryptoException(String message) {
        super(message);
    }

    public ChatCryptoException(String message, Throwable cause) {
        super(message, cause);
    }
}
