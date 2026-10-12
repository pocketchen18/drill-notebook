package com.drillnotebook.app.memory.prototype;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.drillnotebook.app.memory.prototype.HistoryProjectionPrototype.ContextPlan;
import com.drillnotebook.app.memory.prototype.HistoryProjectionPrototype.Counters;
import com.drillnotebook.app.memory.prototype.HistoryProjectionPrototype.Message;
import com.drillnotebook.app.memory.prototype.HistoryProjectionPrototype.ScanResult;
import com.drillnotebook.app.memory.prototype.HistoryProjectionPrototype.SummaryCheckpoint;
import com.drillnotebook.app.memory.prototype.HistoryProjectionPrototype.Turn;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * P0a 第⑤组：600 条历史在分页与压缩后仍完整，模型侧读取按轮次有界，请求与检查器共用同一投影
 * （验收 H01/H02/H03/H11 的最小 fixture）。生产实现属于 P1/P3/P4。
 */
class HistoryProjectionPrototypeTest {

    private static final int TURN_COUNT = 300;
    private static final int CIPHER_CHARS = 200;
    private static final int MESSAGE_COUNT = TURN_COUNT * HistoryProjectionPrototype.MESSAGES_PER_TURN;

    private Path workdir;

    private DataSource dataSource;
    private long sessionId;
    private long turnBytes;

    @BeforeEach
    void seedHistory() throws Exception {
        workdir = StorageLedgerPrototype.scratchDirectory("p0a-history-");
        dataSource = StorageLedgerPrototype.createDatabase(workdir.resolve("study.db"));
        sessionId = createSession();
        assertEquals(MESSAGE_COUNT,
                HistoryProjectionPrototype.seedTurns(dataSource, sessionId, TURN_COUNT, CIPHER_CHARS));
        turnBytes = StorageLedgerPrototype.storedBytes(
                "z" + String.format("%06d", 1) + "A".repeat(CIPHER_CHARS - 7), "{\"salt\":\"s\",\"iv\":\"i\"}")
                + StorageLedgerPrototype.storedBytes(
                        "z" + String.format("%06d", 1) + "A".repeat(CIPHER_CHARS - 7), "{\"salt\":\"s\",\"iv\":\"i\"}");
    }

    @AfterEach
    void removeScratch() throws Exception {
        StorageLedgerPrototype.deleteRecursively(workdir);
    }

    @Test
    void historyPagingKeepsEveryOriginalMessage() throws Exception {
        Map<Long, String> before = HistoryProjectionPrototype.snapshotCiphers(dataSource, sessionId);
        Counters counters = new Counters();
        List<Long> seen = new ArrayList<>();
        long cursor = Long.MAX_VALUE;
        int pages = 0;
        while (true) {
            List<Message> page = HistoryProjectionPrototype.pageMessages(dataSource, sessionId, cursor,
                    HistoryProjectionPrototype.HISTORY_PAGE_SIZE, counters);
            if (page.isEmpty()) {
                break;
            }
            assertTrue(page.size() <= HistoryProjectionPrototype.HISTORY_PAGE_SIZE, "每页最多 50 条");
            for (Message message : page) {
                seen.add(message.id());
            }
            cursor = page.get(page.size() - 1).id();
            pages++;
            assertTrue(pages < 100, "分页必须收敛");
        }
        assertEquals(MESSAGE_COUNT, seen.size(), "翻到底必须覆盖全部历史");
        assertEquals(new LinkedHashSet<>(seen).size(), seen.size(), "分页不得重复或漏条");
        assertEquals(12, pages, "600 条按每页 50 条取满 12 页");
        assertEquals(13, counters.statements, "第 13 次查询返回空页，用来结束翻页");
        assertFalse(seen.stream().anyMatch(id -> id <= 0));

        // 翻页不删数据、不触发新的远程调用（这里体现为消息表行数不变）
        assertEquals(MESSAGE_COUNT, HistoryProjectionPrototype.countMessages(dataSource, sessionId));
        assertEquals(before, HistoryProjectionPrototype.snapshotCiphers(dataSource, sessionId));
    }

    @Test
    void modelScanReadsAtMostThirtyTwoTurns() throws Exception {
        Counters counters = new Counters();
        ScanResult scan = HistoryProjectionPrototype.boundedModelScan(dataSource, sessionId, counters);

        assertEquals(HistoryProjectionPrototype.MODEL_MAX_TURNS, scan.turns().size());
        assertEquals(HistoryProjectionPrototype.MODEL_MAX_TURNS, scan.decryptCount(), "只解密真正纳入的轮次");
        // 32 轮 + 每批多读的一轮，绝不退回"全量 messages 再裁剪"
        assertEquals(68L, scan.rowsRead(), "读到的行数必须远小于 600");
        assertTrue(scan.rowsRead() < MESSAGE_COUNT / 8);
        assertEquals(2, counters.statements, "两批 16 轮");
        assertEquals(TURN_COUNT, scan.turns().get(0).turnId(), "从最新一轮开始");
        assertEquals(TURN_COUNT - HistoryProjectionPrototype.MODEL_MAX_TURNS + 1,
                scan.turns().get(scan.turns().size() - 1).turnId(), "更早的 268 轮不进入模型扫描");
        for (Turn turn : scan.turns()) {
            assertEquals(HistoryProjectionPrototype.MESSAGES_PER_TURN, turn.messages().size(), "不纳入半轮");
        }
    }

    @Test
    void summaryReplacesOnlyActuallyCoveredTurns() throws Exception {
        Map<Long, String> before = HistoryProjectionPrototype.snapshotCiphers(dataSource, sessionId);
        ScanResult scan = HistoryProjectionPrototype.boundedModelScan(dataSource, sessionId, new Counters());

        // 覆盖 284..269，但中间 270 是缺口（T02：有缺口时不能按 through 整段排除）
        Set<Long> covered = new LinkedHashSet<>(HistoryProjectionPrototype.turnIdsBetween(269, 284));
        covered.remove(270L);
        SummaryCheckpoint checkpoint = new SummaryCheckpoint(284L, covered, "y" + "S".repeat(512));

        ContextPlan plan = HistoryProjectionPrototype.project("plan-coverage", scan.turns(), checkpoint,
                HistoryProjectionPrototype.RECENT_TURNS_BUDGET_BYTES);

        assertTrue(plan.includedTurnIds().contains(270L), "缺口轮次必须仍作为原文纳入");
        assertFalse(plan.replacedBySummaryTurnIds().contains(270L));
        for (Long turnId : plan.replacedBySummaryTurnIds()) {
            assertTrue(covered.contains(turnId), "只能替代实际覆盖的轮次");
        }
        assertEquals(scan.turns().size(),
                plan.includedTurnIds().size() + plan.replacedBySummaryTurnIds().size() + plan.trimmedTurnIds().size(),
                "扫描到的每一轮都要有明确去向，不得静默丢失");
        assertTrue(plan.usesSummary());

        // 压缩是替代呈现，不是删除：原始消息逐字节不变（H02）
        assertEquals(MESSAGE_COUNT, HistoryProjectionPrototype.countMessages(dataSource, sessionId));
        assertEquals(before, HistoryProjectionPrototype.snapshotCiphers(dataSource, sessionId));
    }

    @Test
    void budgetTrimDropsOldestTurnsAndKeepsTwoMostRecent() throws Exception {
        ScanResult scan = HistoryProjectionPrototype.boundedModelScan(dataSource, sessionId, new Counters());
        List<Turn> desc = scan.turns();

        ContextPlan threeTurns = HistoryProjectionPrototype.project("plan-budget", desc, null, turnBytes * 3);
        assertEquals(3, threeTurns.includedTurnIds().size(), "预算刚好装下三轮");
        assertEquals(TURN_COUNT - 2, threeTurns.includedTurnIds().get(0).intValue(), "投影按时间顺序恢复后应是最初三轮");
        assertEquals(TURN_COUNT - 1, threeTurns.includedTurnIds().get(1).intValue());
        assertEquals(TURN_COUNT, threeTurns.includedTurnIds().get(2).intValue());
        assertEquals(HistoryProjectionPrototype.turnIdsBetween(
                TURN_COUNT - HistoryProjectionPrototype.MODEL_MAX_TURNS + 1, TURN_COUNT - 3),
                new LinkedHashSet<>(threeTurns.trimmedTurnIds()), "被裁的必须是从最旧开始的那批");

        // 预算几乎为零时仍保留最近两轮（§9.2），且当前问题不在这条路径上被截断
        ContextPlan starved = HistoryProjectionPrototype.project("plan-starved", desc, null, 1L);
        assertEquals(2, starved.includedTurnIds().size());
        assertEquals(TURN_COUNT - 1, starved.includedTurnIds().get(0).intValue());
        assertEquals(TURN_COUNT, starved.includedTurnIds().get(1).intValue());
        assertEquals(desc.size() - HistoryProjectionPrototype.MIN_RETAINED_TURNS, starved.trimmedTurnIds().size());
    }

    @Test
    void requestAndInspectorShareOnePlan() throws Exception {
        ScanResult scan = HistoryProjectionPrototype.boundedModelScan(dataSource, sessionId, new Counters());
        Set<Long> covered = new LinkedHashSet<>(HistoryProjectionPrototype.turnIdsBetween(269, 290));
        ContextPlan plan = HistoryProjectionPrototype.project("plan-single", scan.turns(),
                new SummaryCheckpoint(290L, covered, "y" + "S".repeat(256)),
                HistoryProjectionPrototype.RECENT_TURNS_BUDGET_BYTES);

        // 请求来源与检查器来源来自同一个不可变 plan：内容、顺序、planId 全一致（H11）
        assertEquals(plan.requestSourceIds(), plan.auditSourceIds());
        assertEquals(plan.includedTurnIds(), plan.requestSourceIds());
        assertTrue(plan.includedTurnIds().stream().anyMatch(id -> !covered.contains(id)));
        assertEquals(plan.estimatedBytes(),
                StorageLedgerPrototype.storedBytes("y" + "S".repeat(256)) + plan.includedTurnIds().stream()
                        .map(id -> scan.turns().stream().filter(turn -> turn.turnId() == id).findFirst().orElseThrow()
                                .bytes())
                        .reduce(0L, Long::sum).longValue());
        // 重复派生不改变结果，也不重新读库
        assertEquals(plan.requestSourceIds(), plan.auditSourceIds());
        assertEquals(plan.scannedTurnCount(), scan.turns().size());
    }

    private long createSession() throws Exception {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement("INSERT INTO ai_chat_session(title) VALUES (?)",
                     java.sql.Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, "600 条历史");
            statement.executeUpdate();
            try (var keys = statement.getGeneratedKeys()) {
                keys.next();
                return keys.getLong(1);
            }
        }
    }
}
