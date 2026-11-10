/** 工具记录只接收安全元数据；参数、正文和任意错误文本不进入展示层。 */
export const TOOL_NAMES = {
  search_memories: '检索记忆', list_memories: '读取记忆',
  list_sessions: '查找历史会话', read_session: '读取会话',
  create_session: '新建会话', send_message: '发送消息',
  read_current_page: '读取当前页面', search_materials: '查找学习资料', read_notebook: '读取笔记',
  read_knowledge: '读取知识点', read_question_bank: '读取题库', read_wrong_questions: '读取错题',
  create_material: '新增学习资料', update_material: '修改学习资料', create_memory: '新增记忆', update_memory: '更新记忆',
  delete_memory: '删除记忆', delete_session: '删除会话', delete_material: '删除学习资料', remove_wrong_question: '移出错题本', unknown_tool: '未知工具'
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
  sessionLink?: string;
  requestId?: string;
  sourceLink?: string;
}
export type ToolPhase = 'tools' | 'answer';
const OUTCOMES = new Set(['OK', 'NO_MATCH', 'CREATED', 'UPDATED', 'DUPLICATE', 'NO_CHANGE',
  'DELETED', 'REMOVED', 'NOT_EMPTY', 'IN_USE', 'DISABLED',
  'UNAVAILABLE', 'STALE', 'LIMIT_REACHED', 'INVALID_ARGUMENT', 'NOT_FOUND', 'AMBIGUOUS', 'SUPPRESSED', 'REJECTED', 'CONFLICT', 'DRAFT_UNSAVED',
  'RESULT_TOO_LARGE', 'CONTEXT_LIMIT', 'REPEATED_CALL', 'TIME_LIMIT', 'SENT', 'QUEUED', 'RUNNING', 'CANCELLED', 'CYCLE_BLOCKED', 'ARCHIVED', 'LOCKED']);
export const MAX_TOOL_ACTIVITIES = 1200;
const AVAILABILITIES = new Set(['READY', 'BUILDING', 'LOCKED', 'UNREADABLE', 'TIMEOUT', 'NOT_REQUESTED', 'DENIED']);
const NORMAL = new Set(['OK', 'NO_MATCH', 'CREATED', 'UPDATED', 'DELETED', 'REMOVED', 'DUPLICATE', 'NO_CHANGE', 'SUPPRESSED', 'SENT', 'QUEUED', 'RUNNING']);
export const isDeletionTool = (name: ToolName): boolean => ['delete_memory', 'delete_session', 'delete_material', 'remove_wrong_question'].includes(name);

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
      incomplete: row.incomplete === true,
      ...(typeof row.sourceLink === 'string' && /^drill-notebook:\/\/material\/(notebook|note|knowledge|bank|question|wrong)\/[1-9][0-9]{0,15}$/.test(row.sourceLink) ? { sourceLink: row.sourceLink } : {}),
      ...(typeof row.sessionLink === 'string' && /^drill-notebook:\/\/session\/[a-f0-9]{32}$/.test(row.sessionLink) ? { sessionLink: row.sessionLink } : {}),
      ...(typeof row.requestId === 'string' && /^[a-f0-9]{8}-[a-f0-9]{4}-7[a-f0-9]{3}-[89ab][a-f0-9]{3}-[a-f0-9]{12}$/.test(row.requestId) ? { requestId: row.requestId } : {})
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
  return ['CREATED', 'UPDATED', 'DELETED', 'REMOVED'].includes(row.outcome) || (row.outcome === 'OK' && row.count > 0) ? 'success' : 'neutral';
}

export function toolResultText(row: ToolActivity): string {
  if (row.state === 'RUNNING') return '执行中';
  if (row.state === 'UNKNOWN' || row.outcome === 'UNKNOWN') return isDeletionTool(row.toolName) ? '删除结果未确认，请核对' : '执行结果未确认';
  if (isDeletionTool(row.toolName)) {
    switch (row.outcome) {
      case 'DELETED': return row.toolName === 'delete_memory' ? '已删除 1 条' : row.toolName === 'delete_session' ? '会话已删除' : '已删除';
      case 'REMOVED': return '已移出错题本';
      case 'NO_CHANGE': return '已不在错题本中';
      case 'DUPLICATE': return '已处理，未重复删除';
      case 'NOT_EMPTY': return '其中仍有内容，未删除';
      case 'IN_USE': return row.toolName === 'delete_session' ? '目标会话正在回复或有待发送消息，未删除' : '当前会话绑定该笔记本，未删除';
      case 'CYCLE_BLOCKED': return '不能删除当前会话或其上游会话';
      case 'CONFLICT': return '资料已变化，请重新读取后核对';
      case 'AMBIGUOUS': return '需明确要删除的记忆';
      case 'NOT_FOUND': return row.toolName === 'delete_memory' ? '未找到待删除的记忆' : '目标不存在或已被删除';
      case 'DISABLED': return '工具已在设置中关闭，未执行';
      case 'STALE': return '状态已变化，未删除';
      case 'CANCELLED': return '删除已取消';
      case 'UNAVAILABLE': return '删除结果未确认，请核对';
      case 'REJECTED': return '本次删除未获执行';
    }
  }
  if (row.toolName === 'update_material') {
    switch (row.outcome) {
      case 'UPDATED': return '已更新';
      case 'DUPLICATE': return '已处理，未重复修改';
      case 'NO_CHANGE': return '内容未变化';
      case 'CONFLICT': return '资料已变化或替换目标不唯一，请重新读取后核对';
      case 'DRAFT_UNSAVED': return '请先保存当前笔记草稿，再重新提问';
      case 'NOT_FOUND': return '资料不存在或已不在错题本中';
      case 'STALE': return '会话状态已变化，本次未写入';
      case 'CANCELLED': return '修改已取消';
      case 'UNAVAILABLE': return '保存结果未确认，请核对资料';
    }
  }
  if (row.toolName === 'create_material') {
    switch (row.outcome) {
      case 'CREATED': return '已保存';
      case 'DUPLICATE': return '已存在，未重复添加';
      case 'NO_CHANGE': return '已在错题本中，进度未改变';
      case 'NOT_FOUND': return '资料或所属位置不存在，可能已被删除';
      case 'STALE': return '会话状态已变化，本次未写入';
      case 'CONFLICT': return '重复操作的内容不一致，未写入';
      case 'CANCELLED': return '新增已取消';
      case 'UNAVAILABLE': return '保存结果未确认，请核对资料';
    }
  }
  if (['read_current_page', 'search_materials', 'read_notebook', 'read_knowledge', 'read_question_bank', 'read_wrong_questions'].includes(row.toolName)) {
    switch (row.outcome) {
      case 'OK': return `本次${row.toolName === 'search_materials' ? '找到' : '读取'} ${row.count} 项${row.incomplete ? '（部分内容）' : ''}`;
      case 'NO_MATCH': return '当前范围没有匹配资料';
      case 'NOT_FOUND': return '资料或所选版本不存在，可能已被删除';
      case 'STALE': return '资料已变化，需要重新读取';
      case 'UNAVAILABLE': return '资料暂时无法读取';
    }
  }
  if (row.toolName === 'create_session' && row.outcome === 'CREATED') return '会话已创建，首条消息已提交';
  if (row.outcome === 'SENT') return '消息已提交，等待目标回复';
  if (row.outcome === 'QUEUED') return row.toolName === 'read_session' ? '目标消息仍在排队，尚无回复' : '已排队，等待当前回复结束';
  if (row.outcome === 'RUNNING') return '目标仍在回复';
  if (row.outcome === 'CANCELLED') return '待发送消息已取消';
  if (row.outcome === 'CYCLE_BLOCKED') return '无法向自身或上游会话派发';
  if (row.outcome === 'ARCHIVED') return '目标会话已归档';
  if (row.outcome === 'LOCKED') return '目标会话需要解锁';
  if (row.toolName === 'create_session' || row.toolName === 'send_message') {
    if (row.outcome === 'UNAVAILABLE') return '会话派发暂不可用';
    if (row.outcome === 'STALE') return '会话状态已变化，请重新确认';
    if (row.outcome === 'NOT_FOUND') return '会话不存在或已被删除';
  }
  if (row.toolName === 'list_sessions' || row.toolName === 'read_session') {
    switch (row.outcome) {
      case 'OK': return row.toolName === 'list_sessions'
        ? `本次找到 ${row.count} 个会话${row.incomplete ? '（分批结果）' : ''}`
        : `本次读取 ${row.count} 段消息${row.incomplete ? '（部分记录）' : ''}`;
      case 'NO_MATCH': return '未找到匹配会话';
      case 'NOT_FOUND': return '会话不存在或已被删除';
      case 'AMBIGUOUS': return '找到多个会话，待明确目标';
      case 'STALE': return '会话历史已变化，需要重新读取';
      case 'UNAVAILABLE': return row.availability === 'LOCKED' ? '会话需要解锁后读取'
        : row.availability === 'UNREADABLE' ? '会话内容无法解密' : '会话暂时无法读取';
    }
  }
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
  if (rows.some(row => row.toolName === 'delete_material' && row.outcome === 'DELETED')) parts.push('已删除学习资料');
  if (rows.some(row => row.toolName === 'delete_session' && row.outcome === 'DELETED')) parts.push('已删除会话');
  if (rows.some(row => row.toolName === 'delete_memory' && row.outcome === 'DELETED')) parts.push('已删除记忆');
  if (rows.some(row => row.toolName === 'remove_wrong_question' && row.outcome === 'REMOVED')) parts.push('已移出错题本');
  if (rows.some(row => isDeletionTool(row.toolName) && ['DUPLICATE', 'NO_CHANGE'].includes(row.outcome))) parts.push('删除操作已核对');
  if (rows.some((row) => row.toolName === 'create_material' && ['CREATED', 'DUPLICATE', 'NO_CHANGE'].includes(row.outcome))) parts.push('已保存学习资料');
  if (rows.some((row) => row.toolName === 'update_material' && row.outcome === 'UPDATED')) parts.push('已更新学习资料');
  else if (rows.some((row) => row.toolName === 'update_material' && ['DUPLICATE', 'NO_CHANGE'].includes(row.outcome))) parts.push('资料修改已核对');
  if (rows.some((row) => row.toolName === 'search_materials')) parts.push('已查找学习资料');
  if (rows.some((row) => ['read_current_page', 'read_notebook', 'read_knowledge', 'read_question_bank', 'read_wrong_questions'].includes(row.toolName))) parts.push('已读取学习资料');
  if (rows.some((row) => row.toolName === 'search_memories')) parts.push('已检索记忆');
  if (rows.some((row) => row.toolName === 'list_memories')) parts.push('已读取记忆');
  if (rows.some((row) => row.toolName === 'list_sessions')) parts.push('已查找历史会话');
  if (rows.some((row) => row.toolName === 'read_session' && row.outcome === 'OK' && row.count > 0)) parts.push('已读取会话');
  else if (rows.some((row) => row.toolName === 'read_session')) parts.push('已查询会话状态');
  if (rows.some((row) => row.toolName === 'create_session' && row.outcome === 'CREATED')) parts.push('已新建会话');
  if (rows.some((row) => row.toolName === 'send_message' && ['SENT', 'QUEUED'].includes(row.outcome))) parts.push('已提交消息');
  const created = rows.filter((row) => row.toolName === 'create_memory' && row.outcome === 'CREATED').length;
  const updated = rows.filter((row) => row.toolName === 'update_memory' && row.outcome === 'UPDATED').length;
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
