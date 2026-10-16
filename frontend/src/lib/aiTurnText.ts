/**
 * 上下文提示码与轮次终态的界面文案（contracts §1 NoticeCode、§4.5 终态）。
 *
 * <p>服务端只给码，界面只翻文案：这里不参与任何判定，也不反过来改授权。</p>
 */
import type { ChatResultView } from './aiStream';

interface TurnStatusLike {
  status?: string;
  active?: boolean;
}

const NOTICE_TEXT: Record<string, string> = {
  MEMORY_DISABLED: '自动记忆未开启，本轮没有使用记忆',
  MEMORY_LOCKED: '记忆库已锁定，本轮没有使用记忆',
  MEMORY_INDEX_BUILDING: '记忆索引正在重建，本轮可能没有带上记忆',
  MEMORY_LOOKUP_TIMEOUT: '记忆查询超时，本轮没有带上记忆',
  MEMORY_CONSENT_REQUIRED: '需要先确认记忆授权，本轮没有使用记忆',
  MEMORY_CONFLICT: '有相互冲突的记忆，本轮只带上没有冲突的部分',
  MEMORY_BUDGET_PAUSED: '记忆整理预算已用尽，暂时不会新增记忆',
  CONTEXT_TRIMMED: '上下文超出模型窗口，已裁掉较早的内容',
  SUMMARY_UNAVAILABLE: '会话摘要暂不可用，本轮改用较短的历史',
  PERSISTENCE_FAILED: '这一轮没有正常落库，历史记录可能不完整',
  USAGE_UNKNOWN: '本轮用量未知，未计入成本',
  HISTORY_STORAGE_WARNING: '会话存储接近上限，可清理历史',
  SOURCE_DELETED: '有记忆的来源已经被删除',
  SOURCE_TOO_LARGE: '有来源超过处理上限，未用于记忆',
  SOURCE_CHANGED: '来源内容已变更，稍后会重新整理',
  SUMMARY_GAPS: '会话摘要有未覆盖的段落',
  NO_COMPRESSION_GAIN: '整理后没有压缩收益，保留了原始历史',
  OUTPUT_CAPTURE_LIMIT: '模型输出被长度上限截断',
  HISTORY_ATTACHMENT_OMITTED: '历史里的附件没有再次发送',
  CONTEXT_AUDIT_TRUNCATED: '上下文账本过长，只保留了前面部分',
  RETRIEVAL_UNAVAILABLE: '笔记检索暂时不可用，本次回答未使用笔记内容',
  WINDOW_UNSET: '未设置模型窗口，本轮按保守长度组装上下文',
  IMAGE_COST_UNKNOWN: '图片开销未知，上下文长度按估算处理',
  ATTACHMENT_UNAVAILABLE: '有附件本轮不可用，未发送给模型'
};

/** 同一个 `RETRIEVAL_UNAVAILABLE` 码在两屏语义下的另一句说法（有引用=只降了级）。 */
const RETRIEVAL_DEGRADED_WITH_CITATIONS = '笔记检索降级：本次只用了关键词召回结果';

const STATUS_TEXT: Record<string, string> = {
  RUNNING: '仍在进行',
  COMPLETED: '已完成',
  ABORTED: '已中断',
  TRUNCATED: '被截断',
  FAILED: '失败',
  INTERRUPTED: '已中断（进程退出）'
};

export interface TurnNotice {
  code: string;
  message: string;
}

function firstNotice(codes: unknown): TurnNotice | undefined {
  if (!Array.isArray(codes)) return undefined;
  for (const raw of codes) {
    const code = String(raw);
    const text = NOTICE_TEXT[code];
    if (text) return { code, message: text };
  }
  return undefined;
}

export function turnStatusText(status: unknown): string {
  const key = typeof status === 'string' ? status : '';
  return STATUS_TEXT[key] ?? '状态未知';
}

/** 界面该说哪一句：落库没成 > 非 COMPLETED 终态 > 上下文降级码（§4.2 统一结果、§7.2 第 6 条）。 */
export function outcomeNotice(view: ChatResultView): TurnNotice | undefined {
  if (view.stateDurable === false) {
    return { code: 'PERSISTENCE_FAILED', message: NOTICE_TEXT.PERSISTENCE_FAILED };
  }
  if (view.status && view.status !== 'COMPLETED') {
    return { code: view.status, message: `这一轮${turnStatusText(view.status)}，显示的内容可能不完整` };
  }
  const notice = firstNotice(view.contextUsage?.notices);
  // 服务端把"降级但仍有关键词命中"与"检索异常无命中"折叠成同一个码（AiService:538）：
  // 同一屏已经列出笔记引用时再说"未使用笔记内容"就是自相矛盾，只能按有无引用分句。
  if (notice?.code === 'RETRIEVAL_UNAVAILABLE' && view.citations?.length) {
    return { code: notice.code, message: RETRIEVAL_DEGRADED_WITH_CITATIONS };
  }
  return notice;
}

/** 查状态查出来的终态也要有话可说。 */
export function statusNotice(status: TurnStatusLike): TurnNotice {
  const text = turnStatusText(status.status);
  if (status.status === 'RUNNING') {
    return status.active === false
      ? { code: 'RUNNING_WITHOUT_HANDLE', message: `这一轮已经中断，没有收到结果（${text}）` }
      : { code: 'RUNNING', message: '这一轮仍在后端收尾，结果稍后出现在历史里' };
  }
  return { code: status.status ?? 'UNKNOWN', message: `这一轮${text}，显示的内容可能不完整` };
}

/**
 * 检查器的四张码表（contracts §4.5、执行方案 §9.4）：读不出账本、发送态、来源占位、省略原因。
 * 「本轮未保存上下文记录」是契约规定的缺省句，四种不可用形态都以它开头，界面不许合并成一句空话。
 */
const AUDIT_REASON_TEXT: Record<string, string> = {
  NO_AUDIT_SAVED: '本轮未保存上下文记录',
  LEGACY_TURN_WITHOUT_CONTEXT_PLAN: '本轮未保存上下文记录（这一轮早于上下文账本）',
  AUDIT_UNREADABLE: '本轮未保存上下文记录（密文读不出来，可能需要解锁）',
  REPLAYED_WITHOUT_AUDIT: '本轮未保存上下文记录（重放没有带出新计划）'
};

/** §9.4：只能说"提供给模型什么"，不能替模型声称它确实读了。 */
const DISPATCH_TEXT: Record<string, string> = {
  sent: '已发送给模型',
  not_sent: '尚未发送给模型',
  unknown: '发送状态未知'
};

const PREVIEW_REASON_TEXT: Record<string, string> = {
  LOCKED: '记忆库或会话未解锁，无法对照来源',
  SOURCE_DELETED: '已删除',
  SOURCE_CHANGED: '来源已改版',
  OUTSIDE_SCOPE: '已不在当前笔记本作用域内',
  CHECKPOINT_REPLACED: '摘要已被新版本替换，旧版本不提供预览',
  WRONG_GENERATION: '这一轮属于切换作用域之前的历史，已隔离'
};

const OMISSION_TEXT: Record<string, string> = {
  BUDGET: '超出预算被裁掉',
  COVERED_BY_SUMMARY: '已由续接摘要覆盖',
  OUTSIDE_SCAN_WINDOW: '在扫描窗口之外',
  WRONG_GENERATION: '作用域切换后被隔离',
  NON_COMPLETED: '该轮次不是已完成状态',
  SOURCE_DELETED: '来源已删除',
  SOURCE_CHANGED: '来源已改版',
  LOCKED: '未解锁，未提供',
  NOT_AUTHORIZED: '未获得授权，未提供',
  DUPLICATE: '重复内容',
  ATTACHMENT_POLICY: '按附件策略未发送',
  ITEM_LIMIT: '明细超出账本上限，只保留了计数'
};

const REGION_TEXT: Record<string, string> = {
  SYSTEM: '系统说明',
  MEMORY: '记忆',
  SESSION_STATE: '会话状态',
  RECENT_TURNS: '最近对话',
  PAGE: '当前页面',
  NOTEBOOK_RAG: '笔记检索片段',
  USER_TEXT: '本轮提问',
  TEXT_ATTACHMENT: '文本附件',
  IMAGE: '图片'
};

const KIND_TEXT: Record<string, string> = {
  MEMORY: '记忆',
  SUMMARY: '续接摘要',
  TURN: '对话轮次',
  LEGACY_MESSAGE: '旧消息',
  TEXT_ATTACHMENT: '文本附件',
  IMAGE: '图片',
  NOTEBOOK_RAG: '笔记片段',
  PAGE: '页面'
};

/** 账本不可用时的那一句；未知原因也如实说"读不出来"，不假装本轮没发生过。 */
export function auditReasonText(reason: unknown): string {
  const key = typeof reason === 'string' ? reason : '';
  return AUDIT_REASON_TEXT[key] ?? '本轮未保存上下文记录（原因未知）';
}

export function dispatchStateText(state: unknown): string {
  const key = typeof state === 'string' ? state : '';
  return DISPATCH_TEXT[key] ?? '发送状态未知';
}

/** 预览占位原因：`IMAGE_WITHOUT_TEXT_PREVIEW` 是可用的（图片本来就没有文字预览），不算失败。 */
export function previewPlaceholder(preview: { reason?: string; note?: string }): string {
  if (preview.note === 'IMAGE_WITHOUT_TEXT_PREVIEW') return '图片不提供文字预览';
  const key = preview.reason ?? '';
  return PREVIEW_REASON_TEXT[key] ?? '暂时读不到这条来源';
}

export type PreviewTone = 'quote' | 'note' | 'gone';

/**
 * 一行预览的显示口径：可用且有文字才是引文；图片与空正文各自成句；
 * 不可用时只给占位原因，绝不把上一次的正文留在原地。
 */
export function previewLine(preview: { available: boolean; text?: string | null; truncated?: boolean; note?: string; reason?: string }):
  { tone: PreviewTone; text: string } {
  if (preview.note) return { tone: 'note', text: previewPlaceholder(preview) };
  if (!preview.available) return { tone: 'gone', text: previewPlaceholder(preview) };
  const body = preview.text ?? '';
  if (!body.trim()) return { tone: 'note', text: '这条来源没有文字内容' };
  return { tone: 'quote', text: `${body}${preview.truncated ? '…（已截断）' : ''}` };
}

export function omissionReasonText(reason: unknown): string {
  const key = typeof reason === 'string' ? reason : '';
  return OMISSION_TEXT[key] ?? '未提供（原因未记录）';
}

export function regionLabel(kind: unknown): string {
  const key = typeof kind === 'string' ? kind : '';
  return REGION_TEXT[key] ?? key ?? '未知区域';
}

export function sourceKindLabel(kind: unknown): string {
  const key = typeof kind === 'string' ? kind : '';
  return KIND_TEXT[key] ?? key ?? '来源';
}

const HISTORY_TEXT = {
  CONTENT_DELETED: '这一轮的原文已按你的要求清理，只剩时间与状态',
  UNLOCK_REQUIRED: '这段历史用主密码加密，解锁本会话后才能显示原文'
};

/** 历史行自带的如实标注：清理过的原文与失败/中断终态各说各的，不许伪装成一次正常回答（§7.4、§4.5）。 */
export function historyNotice(row: { turnStatus?: string | null; contentDeleted?: boolean }): TurnNotice | undefined {
  if (row.contentDeleted) return { code: 'CONTENT_DELETED', message: HISTORY_TEXT.CONTENT_DELETED };
  if (row.turnStatus && row.turnStatus !== 'COMPLETED') {
    const notice = statusNotice({ status: row.turnStatus });
    return { code: notice.code, message: notice.message };
  }
  return undefined;
}

/** 需要主密码才能显示的历史，说的是"去解锁"，不是说"没有消息"。 */
export function historyUnlockText(): string {
  return HISTORY_TEXT.UNLOCK_REQUIRED;
}
