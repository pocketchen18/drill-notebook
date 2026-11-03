import type { MemoryKind, MemoryScopeType, MemoryStatus } from './aiMemoryTypes';

const KIND_LABELS: Record<MemoryKind, string> = {
  PREFERENCE: '偏好',
  GOAL: '目标',
  BACKGROUND: '背景',
  TASK: '任务'
};

const STATUS_LABELS: Record<MemoryStatus, string> = {
  ACTIVE: '启用',
  DISABLED: '停用',
  SUPERSEDED: '已替换',
  EXPIRED: '已过期'
};

const INTERNAL_PREDICATE =
  /^(?:other|general|misc|偏好|目标|背景|任务|[a-z][a-z0-9_]*(\.[a-z0-9_]+)+)$/i;
const TITLE_MAX_CHARS = 30;

const LEADING_TEMPORAL = /^(?:之后|以后|今后|从今以后)\s*/;
const LEADING_INTENT =
  /^(?:请|帮我|帮忙|麻烦|让你|请你)?(?:记住|记一下|记下来|加入记忆|存到记忆|别忘了|不要忘记)[：:，,\s]*/;
const TRAILING_INTENT =
  /[，,。.!！?？；;\s]*(?:请记住|帮我记住|让你记住|记住|记一下|别忘了|不要忘记)[。.!！?？\s]*$/;
const LEADING_SUBJECT = /^(?:用户|我|你|我们|本人)(?:的|是|叫)?\s*/;
const TRAILING_PUNCT = /[。.!！?？；;，,\s]+$/;

function contentTitle(value: string): string {
  let text = value.replace(/\s+/g, ' ').trim();
  text = text.replace(LEADING_TEMPORAL, '');
  text = text.replace(LEADING_INTENT, '');
  text = text.replace(TRAILING_INTENT, '');
  text = text.replace(LEADING_SUBJECT, '');
  text = text.replace(TRAILING_PUNCT, '').trim();
  if (!text) return '未命名记忆';

  const firstLine = text.split(/[\n。！？!?；;]/).map((part) => part.trim()).find(Boolean) ?? text;
  const chars = Array.from(firstLine);
  if (chars.length <= TITLE_MAX_CHARS) return firstLine;
  return `${chars.slice(0, TITLE_MAX_CHARS).join('')}…`;
}

/**
 * 列表标题：用户起过名字就用名字；自动提取留下的内部谓词（other、study.background 等）
 * 和“偏好/目标”这类分类词对用户没有意义，退回精简后的正文。
 */
export function memoryDisplayTitle(item: { predicate?: string; value?: string; corrupt?: boolean }): string {
  if (item.corrupt) return '密文不可用的记忆';
  const predicate = item.predicate?.trim() ?? '';
  if (predicate && !INTERNAL_PREDICATE.test(predicate)) return predicate;
  const value = item.value?.trim() ?? '';
  if (!value) return '未命名记忆';
  return contentTitle(value);
}

export function memoryStatusLabel(status: string): string {
  return STATUS_LABELS[status as MemoryStatus] ?? status;
}

export function memoryStatusTone(status: string): 'active' | 'disabled' | 'expired' | 'muted' {
  if (status === 'ACTIVE') return 'active';
  if (status === 'DISABLED') return 'disabled';
  if (status === 'EXPIRED') return 'expired';
  return 'muted';
}

export function memoryOriginLabel(origin: string): string {
  return origin === 'MANUAL' ? '人工记录' : '自动整理';
}

export function memoryScopeLabel(
  scopeType: MemoryScopeType,
  scopeId: number,
  notebooks: Array<{ value: number; label: string }> = []
): string {
  if (scopeType === 'GLOBAL') return '全局';
  const notebook = notebooks.find((entry) => entry.value === scopeId);
  return notebook ? `笔记本 · ${notebook.label}` : '笔记本';
}

export function memoryKindLabel(kind: string): string {
  return KIND_LABELS[kind as MemoryKind] ?? kind;
}

export function memoryValidUntilLabel(value?: string | null): string | null {
  return value ? value.slice(0, 10) : null;
}
