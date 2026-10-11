package com.drillnotebook.app.repository;

import com.drillnotebook.app.config.ImmediateTransaction;
import com.drillnotebook.app.config.ProcessIdentity;
import com.drillnotebook.app.service.ActiveTurnRegistry;
import com.drillnotebook.app.service.ChatMessageCipher;
import com.drillnotebook.app.service.ChatTurnService;
import com.drillnotebook.app.service.ApiKeyEncryptor;
import com.fasterxml.jackson.databind.ObjectMapper;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 测试装配：聊天写路径的仓储必须与账本、会话元数据、记忆、轮次仓储同源，
 * 它们的写方法都跑在同一个 {@link ImmediateTransaction} 上才能同事务记账与重算证据。
 */
public final class ChatLegacyWiring {
    private ChatLegacyWiring() {
    }

    /** 一整套同源依赖，供 AiService/ChatTurnService 的手工装配复用。 */
    public record Wiring(ImmediateTransaction immediate, ChatStorageLedger ledger,
                         ChatSessionMetadataRepository metadata, MemoryRepository memory,
                         AiChatSessionRepository sessions, ChatTurnRepository turns,
                         ChatMessageCipher cipher, ActiveTurnRegistry handles, ChatTurnService turnService) {
    }

    public static Wiring wire(DataSource dataSource, JdbcTemplate jdbc) {
        ObjectMapper mapper = new ObjectMapper();
        ImmediateTransaction immediate = new ImmediateTransaction(dataSource);
        ChatStorageLedger ledger = new ChatStorageLedger(dataSource, immediate);
        ChatSessionMetadataRepository metadata = new ChatSessionMetadataRepository(dataSource, immediate);
        MemoryRepository memory = new MemoryRepository(dataSource, immediate);
        ChatTurnRepository turns = new ChatTurnRepository(dataSource, immediate, ledger, metadata, memory);
        ChatMessageCipher cipher = new ChatMessageCipher(new ApiKeyEncryptor(), mapper);
        ActiveTurnRegistry handles = new ActiveTurnRegistry();
        ChatTurnService turnService = new ChatTurnService(turns, metadata, cipher, handles, new ProcessIdentity(),
                mapper);
        AiChatSessionRepository sessions = new AiChatSessionRepository(jdbc, immediate, ledger, metadata, memory);
        return new Wiring(immediate, ledger, metadata, memory, sessions, turns, cipher, handles, turnService);
    }

    public static AiChatSessionRepository sessions(DataSource dataSource, JdbcTemplate jdbc) {
        return wire(dataSource, jdbc).sessions();
    }

    public static ChatTurnService turnService(DataSource dataSource, JdbcTemplate jdbc) {
        return wire(dataSource, jdbc).turnService();
    }

    public static ChatMessageCipher cipher() {
        return new ChatMessageCipher(new ApiKeyEncryptor(), new ObjectMapper());
    }
}
