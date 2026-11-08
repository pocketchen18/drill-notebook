/** 工具记录只接收安全元数据；参数、正文和任意错误文本不进入展示层。 */
export const TOOL_NAMES = {
  search_memories: '检索记忆', list_memories: '读取记忆',
  create_memory: '新增记忆', update_memory: '更新记忆', unknown_tool: '未知工具'
} as const;
export type ToolName = keyof typeof TOOL_NAMES;
export interface ToolActivity {
  sequence: number;
  toolName: ToolName;
  state: 'RUNNING' | 'FINISHED' | 'UNKNOWN';
  outcome: string;
  startedAt: string | null;
  elapsedMs: number | null;
  count: number;
  availability: string | null;
  incomplete: boolean;
}
export type ToolPhase = 'tools' | 'answer';
const OUTCOMES = new Set(['OK', 'NO_MATCH', 'CREATED', 'UPDATED', 'DUPLICATE', 'NO_CHANGE',
  'UNAVAILABLE', 'STALE', 'LIMIT_REACHED', 'INVALID_ARGUMENT', 'NOT_FOUND', 'AMBIGUOUS', 'SUPPRESSED', 'REJECTED',
  'RESULT_TOO_LARGE', 'CONTEXT_LIMIT', 'REPEATED_CALL', 'TIME_LIMIT']);
export const MAX_TOOL_ACTIVITIES = 1200;
const AVAILABILITIES = new Set(['READY', 'BUILDING', 'LOCKED', 'TIMEOUT', 'NOT_REQUESTED', 'DENIED']);
const NORMAL = new Set(['OK', 'NO_MATCH', 'CREATED', 'UPDATED', 'DUPLICATE', 'NO_CHANGE', 'SUPPRESSED']);

export function readToolActivities(value: unknown): ToolActivity[] {
  if (!Array.isArray(value)) return [];
  const rows = new Map<number, ToolActivity>();
  for (const row of value.slice(0, MAX_TOOL_ACTIVITIES)) {
    if (!row || typeof row !== 'object' || !Number.isInteger(row.sequence) || row.sequence < 1 || row.sequence > MAX_TOOL_ACTIVITIES) continue;
    rows.set(row.sequence, {
      sequence: row.sequence,
      toolName: Object.hasOwn(TOOL_NAMES, row.toolName) ? row.toolName : 'unknown_tool',
      state: row.state === 'RUNNING' || row.state === 'FINISHED' ? row.state : 'UNKNOWN',
      outcome: OUTCOMES.has(row.outcome) ? row.outcome : 'UNKNOWN',
      startedAt: typeof row.startedAt === 'string' && Number.isFinite(Date.parse(row.startedAt))
        ? new Date(row.startedAt).toISOString() : null,
      elapsedMs: typeof row.elapsedMs === 'number' && Number.isFinite(row.elapsedMs) ? Math.max(0, row.elapsedMs) : null,
      count: Number.isInteger(row.count) ? Math.max(0, Math.min(1000, row.count)) : 0,
      availability: AVAILABILITIES.has(row.availability) ? row.availability : null,
      incomplete: row.incomplete === true
    });
  }
  return [...rows.values()].sort((a, b) => a.sequence - b.sequence);
}

/** 同序号原位更新；迟到的开始事件不能覆盖已经确认的结果。 */
export function mergeToolActivities(previous: ToolActivity[] = [], incoming: unknown = []): ToolActivity[] {
  const rows = new Map(previous.map((row) => [row.sequence, row]));
  for (const row of readToolActivities(incoming)) {
    const before = rows.get(row.sequence);
    if (before?.state === 'FINISHED' && row.state !== 'FINISHED') continue;
    if (before?.state === 'UNKNOWN' && row.state === 'RUNNING') continue;
    rows.set(row.sequence, row);
  }
  return [...rows.values()].sort((a, b) => a.sequence - b.sequence).slice(0, MAX_TOOL_ACTIVITIES);
}

export function settleToolActivities(rows: ToolActivity[] = []): ToolActivity[] {
  return rows.map((row) => row.state === 'RUNNING'
    ? { ...row, state: 'UNKNOWN', outcome: 'UNKNOWN', elapsedMs: null, count: 0 } : row);
}

export function toolTone(row: ToolActivity): 'running' | 'success' | 'neutral' | 'warning' {
  if (row.state === 'RUNNING') return 'running';
  if (row.state === 'UNKNOWN' || !NORMAL.has(row.outcome)) return 'warning';
  return ['CREATED', 'UPDATED'].includes(row.outcome) || (row.outcome === 'OK' && row.count > 0) ? 'success' : 'neutral';
}

export function toolResultText(row: ToolActivity): string {
  if (row.state === 'RUNNING') return '执行中';
  if (row.state === 'UNKNOWN' || row.outcome === 'UNKNOWN') return '执行结果未确认';
  switch (row.outcome) {
    case 'OK': return row.count > 0 ? `${row.toolName === 'list_memories' || row.incomplete ? '本次' : '已'}返回 ${row.count} 条`
      : row.toolName === 'list_memories' ? '当前范围没有记忆' : '未找到相关记忆';
    case 'NO_MATCH': return row.toolName === 'list_memories' ? '当前范围没有记忆' : '未找到相关记忆';
    case 'CREATED': return '已新增 1 条';
    case 'UPDATED': return '已更新 1 条';
    case 'DUPLICATE': return '已存在，未重复添加';
    case 'NO_CHANGE': return '内容相同，无需更新';
    case 'STALE': return '记忆已变化，未更新';
    case 'NOT_FOUND': return '未找到待更新的记忆';
    case 'AMBIGUOUS': return '需明确要更新的记忆';
    case 'LIMIT_REACHED': return '已达本轮调用上限';
    case 'RESULT_TOO_LARGE': return '工具结果过大，未发送给模型';
    case 'CONTEXT_LIMIT': return '上下文空间不足，结果未发送';
    case 'REPEATED_CALL': return '重复调用无新结果，已停止';
    case 'TIME_LIMIT': return '已达本轮时间上限';
    case 'INVALID_ARGUMENT': return '工具请求无效';
    case 'SUPPRESSED': return '未保存此内容';
    case 'REJECTED': return '本次内容未写入';
    case 'UNAVAILABLE':
      return ({ BUILDING: '记忆索引准备中', LOCKED: '记忆暂不可用', TIMEOUT: '读取超时',
        DENIED: '无权执行此操作', NOT_REQUESTED: '本轮未启用记忆' } as Record<string, string>)[row.availability ?? ''] ?? '记忆暂不可用';
    default: return '执行结果未确认';
  }
}

export function toolGroupTitle(rows: ToolActivity[]): string {
  const running = rows.find((row) => row.state === 'RUNNING');
  if (running) return `正在${TOOL_NAMES[running.toolName]}`;
  const warnings = rows.filter((row) => toolTone(row) === 'warning');
  if (warnings.length) return warnings.some((row) => row.state === 'UNKNOWN' || row.outcome === 'UNKNOWN')
    ? '部分执行结果未确认' : warnings.length < rows.length ? '部分工具未完成'
      : warnings.every((row) => row.outcome === 'UNAVAILABLE') ? '工具暂不可用' : '工具未完成';
  const parts: string[] = [];
  if (rows.some((row) => row.toolName === 'search_memories')) parts.push('已检索记忆');
  if (rows.some((row) => row.toolName === 'list_memories')) parts.push('已读取记忆');
  const created = rows.filter((row) => row.outcome === 'CREATED').length;
  const updated = rows.filter((row) => row.outcome === 'UPDATED').length;
  if (created) parts.push(`已新增 ${created} 条`);
  if (updated) parts.push(`已更新 ${updated} 条`);
  if (!created && !updated && rows.some((row) => ['create_memory', 'update_memory'].includes(row.toolName))) parts.push('未产生变更');
  return parts.join(' · ') || '工具调用已结束';
}

export const toolDuration = (ms: number | null): string => ms === null ? '耗时未记录' : `${(ms / 1000).toFixed(1)} 秒`;

export function toolStartedAt(value: string | null): string {
  if (!value) return '时间未记录';
  const date = new Date(value);
  const time = date.toLocaleTimeString('zh-CN', { hour12: false });
  return date.toDateString() === new Date().toDateString() ? time : `${date.toLocaleDateString('zh-CN')} ${time}`;
}
