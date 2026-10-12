package com.drillnotebook.app.service;

import com.drillnotebook.app.repository.AiConfigRepository;
import com.drillnotebook.app.repository.ChatHistoryRepository;
import com.drillnotebook.app.repository.ChatLegacyWiring;
import com.drillnotebook.app.repository.MemoryJobRepository;
import com.drillnotebook.app.repository.MemoryOperationRepository;
import com.drillnotebook.app.repository.MemorySettingsRepository;
import com.drillnotebook.app.repository.NotebookRepository;
import com.drillnotebook.app.repository.RetrievalIndexRepository;
import com.drillnotebook.app.repository.SessionContextRepository;
import com.drillnotebook.app.service.memory.MemoryCryptoService;
import com.drillnotebook.app.service.memory.MemoryPolicyService;
import com.drillnotebook.app.service.memory.MemoryVaultService;
import com.fasterxml.jackson.databind.ObjectMapper;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 测试装配：P4 的上下文栈（投影 → 组装 → 预算 → 计划）必须与写路径同源，
 * 否则轮次、附件、记忆库版本在两个栈里不是同一份，检查点与审计就对不上。
 */
public final class ChatContextWiring {
    private ChatContextWiring() {
    }

    /** 一套可直接喂给 {@code new AiService(...)} 的上下文服务与只读入口。 */
    public record Stack(ChatContextService contexts, ChatHistoryService history, ContextInspector inspector,
                        TokenEstimator estimator, ConversationProjection projection,
                        ChatContextAssembler assembler, ContextBudgeter budgeter, ManualMemorySource memories,
                        MemoryCryptoService crypto, MemoryVaultService vault, MemoryPolicyService policy,
                        ChatHistoryRepository historyRepo, SessionContextRepository checkpoints,
                        ChatAttachmentReader attachmentReader) {
    }

    public static Stack wire(DataSource dataSource, JdbcTemplate jdbc, ChatLegacyWiring.Wiring wiring) {
        ObjectMapper mapper = new ObjectMapper();
        TokenEstimator estimator = new TokenEstimator(mapper);
        MemoryCryptoService crypto = new MemoryCryptoService(new ApiKeyEncryptor());
        MemoryVaultService vault = new MemoryVaultService(crypto, wiring.memory(),
                new MemoryOperationRepository(dataSource, wiring.immediate()), wiring.metadata());
        MemoryPolicyService policy = new MemoryPolicyService(
                new MemorySettingsRepository(dataSource, wiring.immediate()), wiring.memory(),
                new MemoryJobRepository(dataSource, wiring.immediate()), new AiConfigRepository(jdbc),
                new NotebookRepository(jdbc, mapper), vault);
        ChatHistoryRepository historyRepo = new ChatHistoryRepository(dataSource);
        SessionContextRepository checkpoints = new SessionContextRepository(dataSource);
        ChatAttachmentReader attachmentReader = new ChatAttachmentReader(wiring.turns(), wiring.cipher(),
                wiring.attachmentCipher(), mapper);
        ConversationProjection projection = new ConversationProjection(historyRepo, checkpoints, wiring.metadata(),
                wiring.cipher(), wiring.memory(), crypto, vault, policy, estimator, mapper);
        ChatContextAssembler assembler = new ChatContextAssembler(estimator, attachmentReader);
        ContextBudgeter budgeter = new ContextBudgeter(estimator, mapper);
        ManualMemorySource memories = new ManualMemorySource(wiring.memory(), vault, crypto, mapper);
        ChatContextService contexts = new ChatContextService(projection, assembler, budgeter, memories,
                wiring.metadata(), checkpoints, wiring.memory(), policy, historyRepo);
        ChatHistoryService history = new ChatHistoryService(historyRepo, wiring.metadata(), wiring.cipher(), vault);
        ContextInspector inspector = new ContextInspector(wiring.turns(), historyRepo, wiring.metadata(),
                wiring.cipher(), attachmentReader, wiring.memory(), crypto, vault, checkpoints,
                new RetrievalIndexRepository(jdbc), mapper);
        return new Stack(contexts, history, inspector, estimator, projection, assembler, budgeter, memories,
                crypto, vault, policy, historyRepo, checkpoints, attachmentReader);
    }
}
