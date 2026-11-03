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

const INTERNAL_PREDICATE = /^[a-z][a-z0-9_]*(\.[a-z0-9_]+)+$/i;

export function memoryKindLabel(kind: string): string {
  return KIND_LABELS[kind as MemoryKind] ?? kind;
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

/**
 * 列表标题：用户起过名字就用名字；自动提取留下的内部谓词（如 study.background）
 * 对用户没有意义，退回正文前 30 个字。
 */
export function memoryDisplayTitle(item: { predicate?: string; value?: string; corrupt?: boolean }): string {
  if (item.corrupt) return '密文不可用的记忆';
  const predicate = item.predicate?.trim() ?? '';
  if (predicate && !INTERNAL_PREDICATE.test(predicate)) return predicate;
  const value = item.value?.trim() ?? '';
  if (!value) return '未命名记忆';
  const chars = Array.from(value);
  return chars.slice(0, 30).join('') + (chars.length > 30 ? '…' : '');
}

export function memoryValidUntilLabel(value?: string | null): string | null {
  return value ? value.slice(0, 10) : null;
}
