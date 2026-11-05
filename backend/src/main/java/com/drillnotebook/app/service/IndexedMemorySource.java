package com.drillnotebook.app.service;

import com.drillnotebook.app.repository.MemoryRepository;
import com.drillnotebook.app.service.memory.MemoryIndexService;
import com.drillnotebook.app.service.memory.MemorySearchIndex;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * §9.1 的读路径实现：只查 {@link MemoryIndexService} 缓存的不可变内存快照，绝不在这里解密、也绝不建任何
 * 明文索引表。替换掉 P4 的 {@code ManualMemorySource}（执行方案 P4 交接 A 项），{@link ContextMemorySource}
 * 接口与 100ms 降级语义保持不变，组装器与预算都不动。
 *
 * <p>一次 {@code select} 做的四件事，按 §9.1 第 5 条的顺序：</p>
 * <ol>
 *   <li>取与当前库版本一致的快照；没有就提交后台重建并按 {@code BUILDING} 交白卷；</li>
 *   <li>把 {@code FAMILY} 抑制键交给索引，让被遗忘的同族事实<b>在打分前</b>就出局（否则它会白占 3 个名额）；</li>
 *   <li>索引内完成作用域/有效期过滤、BM25 打分、相关性下限、分层排序、家族去重与 3+2 上限；</li>
 *   <li>剩余时间不够就整轮降级为"本轮不使用记忆"（§9.1-7）。</li>
 * </ol>
 */
@Component
public class IndexedMemorySource implements ContextMemorySource {

    private static final Logger log = LoggerFactory.getLogger(IndexedMemorySource.class);

    private final MemoryIndexService indexes;
    private final MemoryRepository memory;

    public IndexedMemorySource(MemoryIndexService indexes, MemoryRepository memory) {
        this.indexes = indexes;
        this.memory = memory;
    }

    @Override
    public Selection select(String vaultId, String scopeType, long scopeId, String queryText, long nowMillis,
                           int deadlineMillis) {
        if (vaultId == null) {
            return Selection.unavailable(ContextMemorySource.Availability.LOCKED);
        }
        long deadlineNanos = System.nanoTime() + Math.max(1, deadlineMillis) * 1_000_000L;
        Optional<MemoryRepository.VaultEnvelope> envelope;
        try {
            envelope = memory.readEnvelope();
        } catch (SQLException error) {
            // 记忆读失败只让本轮不带记忆，不能把用户的聊天打掉（§9.1-7）
            log.warn("记忆库版本读取失败，本轮降级为无记忆: {}", error.toString());
            return Selection.unavailable(ContextMemorySource.Availability.LOCKED);
        }
        if (envelope.isEmpty() || !vaultId.equals(envelope.get().vaultId())) {
            return Selection.unavailable(ContextMemorySource.Availability.LOCKED);
        }
        Optional<MemoryIndexService.Snapshot> snapshot = indexes.ready(vaultId, envelope.get().epoch(),
                envelope.get().revision());
        if (snapshot.isEmpty()) {
            // 没钥匙就是"锁定"，有钥匙才是"快照还在建"：两条文案与后续动作完全不同
            return Selection.unavailable(indexes.locked() ? ContextMemorySource.Availability.LOCKED
                    : ContextMemorySource.Availability.BUILDING);
        }
        Predicate<MemorySearchIndex.Doc> eligible;
        try {
            eligible = suppressedFilter(vaultId, scopeType, scopeId);
        } catch (SQLException error) {
            log.warn("记忆抑制键读取失败，本轮降级为无记忆: {}", error.toString());
            return Selection.unavailable(ContextMemorySource.Availability.LOCKED);
        }
        long remainingMillis = (deadlineNanos - System.nanoTime()) / 1_000_000L;
        if (remainingMillis <= 0) {
            return Selection.unavailable(ContextMemorySource.Availability.TIMEOUT);
        }
        MemorySearchIndex.Result result = snapshot.get().index().search(queryText, scopeType, scopeId, nowMillis,
                MemorySearchIndex.TOPICAL_LIMIT, remainingMillis, eligible);
        if (result.timedOut()) {
            return Selection.unavailable(ContextMemorySource.Availability.TIMEOUT);
        }
        if (result.candidates() == 0) {
            // §9.1-6：候选少/无相关要如实记录，但只记数量与作用域，不记正文
            log.info("记忆检索没有相关候选: scopeType={}, scopeId={}, scanned={}", scopeType, scopeId,
                    result.scanned());
        }
        return new Selection(facts(result.topical()), facts(result.style()), result.candidates(), false,
                ContextMemorySource.Availability.READY);
    }

    /**
     * 冲突抑制过滤（§9.1-5）。只认 {@code FAMILY} 档：{@code PREDICATE} 档是"这一类别再自动记"的写入闸门
     * （P7），拿它过滤注入会连用户没删的同谓词事实一起屏蔽——两个并存学习目标当场少一个（M13）。
     */
    private Predicate<MemorySearchIndex.Doc> suppressedFilter(String vaultId, String scopeType, long scopeId)
            throws SQLException {
        Set<String> globalFamilies = indexes.suppressedFamilies(vaultId, "GLOBAL", 0L);
        Set<String> notebookFamilies = "NOTEBOOK".equals(scopeType) && scopeId > 0
                ? indexes.suppressedFamilies(vaultId, "NOTEBOOK", scopeId) : Set.of();
        if (globalFamilies.isEmpty() && notebookFamilies.isEmpty()) {
            return doc -> true;
        }
        return doc -> "GLOBAL".equals(doc.scopeType())
                ? !globalFamilies.contains(doc.familyKeyHmac())
                : !notebookFamilies.contains(doc.familyKeyHmac());
    }

    private static List<ContextMemorySource.Fact> facts(List<MemorySearchIndex.Hit> hits) {
        List<ContextMemorySource.Fact> facts = new ArrayList<>();
        for (MemorySearchIndex.Hit hit : hits) {
            MemorySearchIndex.Doc doc = hit.doc();
            facts.add(new ContextMemorySource.Fact(doc.memoryId(), doc.version(), doc.kind(), doc.predicate(),
                    doc.value(), doc.scopeType(), doc.scopeId()));
        }
        return facts;
    }
}
