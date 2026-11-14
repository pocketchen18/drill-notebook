import { get, post } from './api';

/**
 * 本轮上下文检查器的只读出口（contracts §4.5）：读已保存的审计账本、按来源键取短片段。
 * 两者都不建计划、不触发检索/维护/重放；面板打开后只读取已有记录。
 */

const BASE = '/api/ai/turns';

/** 审计里的一条来源；`sourceKey` 是本轮计划内的选择键，不能自行构造去预览。 */
export interface ContextSource {
  sourceKey: string;
  kind: string;
  id: string | null;
  version: string | null;
  sourceTurnId: number | null;
}

export interface ContextRegion {
  kind: string;
  estimatedTokens: number;
  itemCount: number;
}

/** `count=null` 是契约要求的"更早的没扫描"，界面不能伪装成 0 或"都已处理"。 */
export interface ContextOmission {
  reason: string;
  kind: string | null;
  count: number | null;
}

export interface ContextScan {
  firstTurnId: number | null;
  lastTurnId: number | null;
  scannedTurns: number;
  hasOlderUnscanned: boolean;
}

export interface ContextCoverage {
  firstTurnId: number;
  lastTurnId: number;
}

export interface ContextSummary {
  revision: number;
  throughTurnId: number;
  hasGaps: boolean;
  coverageRanges: ContextCoverage[];
}

export interface ContextResumeState {
  requestId: string;
  status: string;
  finishedAt: string;
}

export interface TurnContextAudit {
  schemaVersion: number;
  planId: string;
  dispatchState: string;
  estimatedInputTokens: number;
  inputBudget: number;
  estimatorVersion: string;
  selected: ContextSource[];
  regions: ContextRegion[];
  omissions: ContextOmission[];
  scan: ContextScan;
  summary: ContextSummary | null;
  resumeState: ContextResumeState | null;
  auditTruncated: boolean;
}

export interface TurnContextRead {
  requestId: string;
  sessionId: number;
  status: string;
  available: boolean;
  reason?: string;
  planId?: string;
  dispatchState?: string;
  audit?: TurnContextAudit;
  requestContexts?: RequestContextPage;
  usage?: ContextTokenTotals;
}

export interface ContextTokenTotals {
  requests: number;
  inputTokens: number | null;
  outputTokens: number | null;
  cacheReadTokens: number | null;
  cacheMissTokens: number | null;
  totalTokens: number | null;
  inputComplete: boolean;
  outputComplete: boolean;
  cacheReadComplete: boolean;
  cacheMissComplete: boolean;
  totalComplete: boolean;
}

export interface RequestContextSnapshot {
  schemaVersion: number;
  estimatorVersion: string;
  model: string;
  kind: 'CHAT' | 'NATIVE_WEB';
  estimatedInputTokens: number;
  contextWindow: number;
  categories: { kind: string; estimatedTokens: number; itemCount: number;
    items: { key: string; name: string | null; estimatedTokens: number }[] }[];
  detailsTruncated: boolean;
}

export interface RequestContextEntry {
  attemptId: string;
  operationId: string;
  ordinal: number;
  createdAt: string;
  settledAt: string | null;
  state: 'SENT' | 'SETTLED' | 'UNKNOWN';
  usageSource: string;
  inputTokens: number | null;
  outputTokens: number | null;
  cacheReadTokens: number | null;
  cacheMissTokens: number | null;
  totalTokens: number | null;
  inputComplete: boolean;
  outputComplete: boolean;
  cacheReadComplete: boolean;
  cacheMissComplete: boolean;
  totalComplete: boolean;
  context: RequestContextSnapshot | null;
}

export interface RequestContextPage {
  items: RequestContextEntry[];
  totalRequests: number;
  hasMore: boolean;
  nextBeforeAttemptId: string | null;
  limit: number;
}

/** 预览结果：`available=false` 时只有 `reason` 占位，绝不带正文。 */
export interface SourcePreview {
  sourceKey: string;
  kind: string;
  id: string | null;
  version: string | null;
  available: boolean;
  text?: string | null;
  truncated?: boolean;
  note?: string;
  reason?: string;
}

/** 一次最多 5 个来源、每个 ≤160 码点（服务端常量 MAX_PREVIEW_KEYS / MAX_PREVIEW_CODEPOINTS）。 */
export const MAX_PREVIEW_KEYS = 5;

export const readTurnContext = (requestId: string, beforeAttemptId?: string) =>
  get<TurnContextRead>(`${BASE}/${encodeURIComponent(requestId)}/context${beforeAttemptId ? `?beforeAttemptId=${encodeURIComponent(beforeAttemptId)}` : ''}`);

export const previewTurnSources = (requestId: string, sourceKeys: string[]) =>
  post<SourcePreview[]>(`${BASE}/${encodeURIComponent(requestId)}/context/preview`, { sourceKeys });
