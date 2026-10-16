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
