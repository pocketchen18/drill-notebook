package com.drillnotebook.app.service.memory;

import com.drillnotebook.app.repository.MemoryRepository;
import java.sql.SQLException;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * 笔记本删除时清掉它自己的 NOTEBOOK scope 记忆。
 *
 * <p>没有记忆库时是 no-op；记忆库锁定时也不需要钥匙——这里只删密文行，不解密。</p>
 */
@Service
public class MemoryNotebookCleanupService {
    private final MemoryRepository memory;

    public MemoryNotebookCleanupService(MemoryRepository memory) {
        this.memory = memory;
    }

    public int purge(long notebookId) throws SQLException {
        Optional<MemoryRepository.VaultEnvelope> envelope = memory.readEnvelope();
        return envelope.isEmpty() ? 0 : memory.purgeNotebookScope(envelope.get().vaultId(), notebookId);
    }
}
