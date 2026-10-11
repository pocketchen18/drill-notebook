package com.drillnotebook.app.repository;

import com.drillnotebook.app.config.ImmediateTransaction;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 测试装配：旧聊天写路径的会话仓储必须与账本、会话元数据、记忆仓储同源，
 * 它们的写方法都跑在同一个 {@link ImmediateTransaction} 上才能同事务记账与重算证据。
 */
public final class ChatLegacyWiring {
    private ChatLegacyWiring() {
    }

    public static AiChatSessionRepository sessions(DataSource dataSource, JdbcTemplate jdbc) {
        ImmediateTransaction immediate = new ImmediateTransaction(dataSource);
        ChatStorageLedger ledger = new ChatStorageLedger(dataSource, immediate);
        ChatSessionMetadataRepository metadata = new ChatSessionMetadataRepository(dataSource, immediate);
        MemoryRepository memory = new MemoryRepository(dataSource, immediate);
        return new AiChatSessionRepository(jdbc, immediate, ledger, metadata, memory);
    }
}
