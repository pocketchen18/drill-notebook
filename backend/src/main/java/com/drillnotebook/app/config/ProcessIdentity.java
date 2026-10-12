package com.drillnotebook.app.config;

import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * 本进程身份，写入 {@code ai_chat_turn.runtime_generation}。
 *
 * <p>pid 单独不足以判定孤儿轮次（进程重启后 pid 会复用），所以拼一段随机后缀。
 * 孤儿恢复仍以「本进程有没有活跃句柄」为准，这里只作事后排查线索。</p>
 */
@Component
public class ProcessIdentity {
    private final String generation = ProcessHandle.current().pid() + "-" + UUID.randomUUID();

    public String generation() {
        return generation;
    }
}
