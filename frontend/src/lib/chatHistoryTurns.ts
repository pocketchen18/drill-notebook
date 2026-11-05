import type { ChatHistoryMessage } from '../hooks/useChatHistoryPages';

/** 边界计算要的只是"这一轮的完整范围到哪儿为止"，所以只要轮次身份与最后一条消息。 */
export interface LoadedTurn {
  turnId: number;
  /** 该轮已加载消息里最大的一条 ID；服务端按"轮次的最大 message id <= 边界"选范围。 */
  lastMessageId: number;
}

/**
 * 已加载的**完整**轮次（旧→新）。分页是从新往旧连续取的，所以最多只有最旧那一组可能是半轮：
 * 服务端还说有更早的，就把最旧一组丢掉——绝不拿半轮的边界去算"保留最近 N 轮"，
 * 那会让实际清理的范围比用户看到的小（或大）一轮。legacy 展示行没有轮次身份，不参与边界计算。
 */
export function completeLoadedTurns(messages: ChatHistoryMessage[], hasOlder: boolean): LoadedTurn[] {
  const turns: LoadedTurn[] = [];
  const byId = new Map<number, LoadedTurn>();
  for (const row of messages) {
    const turnId = typeof row.turnId === 'number' ? row.turnId : undefined;
    if (turnId === undefined) continue;
    const existing = byId.get(turnId);
    if (existing) {
      existing.lastMessageId = Math.max(existing.lastMessageId, row.id);
    } else {
      const turn: LoadedTurn = { turnId, lastMessageId: row.id };
      byId.set(turnId, turn);
      turns.push(turn);
    }
  }
  return hasOlder && turns.length > 0 ? turns.slice(1) : turns;
}

/**
 * "保留最近 N 轮"的边界：第 N+1 新的那一轮的最后一条消息 ID。
 * 完整轮次不足 N+1 时返回 undefined——界面据此禁用并说明，绝不猜一个边界去预览。
 *
 * <p>N 本身没有上限：留着 300 轮、清更早的没问题（"一次最多清 200 轮"是**执行批次**的上限，
 * 由后端 `maxTurns` 管，超出部分由预览的 `olderTurnCount` 如实报出来）。这里只拒绝非正整数。</p>
 */
export function keepBoundary(turns: LoadedTurn[], keep: number): number | undefined {
  if (!Number.isInteger(keep) || keep < 1) return undefined;
  if (turns.length < keep + 1) return undefined;
  return turns[turns.length - 1 - keep].lastMessageId;
}
