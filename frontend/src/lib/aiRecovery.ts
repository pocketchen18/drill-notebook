export interface AiRecovery {
  kind: 'request' | 'stream' | 'network';
  state: 'waiting' | 'connecting' | 'failed' | 'stopped';
  attempt: number;
  limit: number | null;
  error: string;
  nextRetryAt?: number | null;
}

export function readRecovery(value: unknown): AiRecovery | null | undefined {
  if (value === null) return null;
  if (!value || typeof value !== 'object') return undefined;
  const row = value as Record<string, unknown>;
  if (!['request', 'stream', 'network'].includes(String(row.kind)) || !['waiting', 'connecting', 'failed', 'stopped'].includes(String(row.state))
    || !Number.isInteger(row.attempt) || (row.attempt as number) < 0 || typeof row.error !== 'string'
    || !(row.limit === null || Number.isInteger(row.limit) && (row.limit as number) >= 0 && (row.limit as number) <= 100)) return undefined;
  return { kind: row.kind as AiRecovery['kind'], state: row.state as AiRecovery['state'], attempt: row.attempt as number,
    limit: row.limit as number | null, error: row.error.slice(0, 4001),
    nextRetryAt: Number.isFinite(row.nextRetryAt) ? row.nextRetryAt as number : null };
}

export function recoveryLabel(value: AiRecovery): string {
  const count = value.limit === null ? `已重试 ${value.attempt} 次` : `${value.attempt}/${value.limit}`;
  if (value.state === 'stopped') return `已停止重新连接 · 已重试 ${value.attempt} 次`;
  if (value.state === 'failed') {
    if (value.attempt === 0 && value.limit !== 0) return '无法继续生成';
    return value.limit === 0 ? '生成失败 · 自动重试已关闭' : `重新连接失败 · ${count}`;
  }
  return value.kind === 'network' ? `等待网络恢复${value.limit === null ? ' · ' : ' '}${count}`
    : `${value.kind === 'request' ? '请求重试' : '正在重新连接'} ${count}`;
}
