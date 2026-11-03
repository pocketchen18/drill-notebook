import { get, post } from './api';

/**
 * 会话存储容量的只读出口与历史清理（contracts §2.7、§3 的 `/history/storage`、
 * `/history/cleanup/*`）。
 *
 * <p>这些数一律是**逻辑落库字节**：UTF-8 落库长度，不是 Token，也不是 `.db` 的物理大小；
 * 它同样独立于后台记忆的 20 次/30K 维护预算。三者各说各话，界面不许混称。</p>
 */
export interface ChatStorageSnapshot {
  /** 已落库的历史字节（全部消息五列 + 全部轮次四列密文元数据）。 */
  historyBytes: number;
  /** 仍在收尾的轮次为回复预留的字节。 */
  reservedHistoryBytes: number;
  /** 账本口径的占用 = 存量 + 预留；预警判定也按这个数。 */
  usedHistoryBytes: number;
  historyLimitBytes: number;
  attachmentBytes: number;
  attachmentLimitBytes: number;
  historyWarning: boolean;
  attachmentWarning: boolean;
  /** 改额度时的 CAS 基线；本片只做只读展示。 */
  revision: number;
}

/**
 * 清理预览：服务端钉死的一份范围。界面只展示，不自己算任何数——
 * `messageIds` 就是执行时真正会删的那一批（含 legacy 展示行），
 * `incompleteLegacyMessageIds` 是其中凑不成一问一答的那几条。
 */
export interface ChatCleanupPreview {
  previewId: string;
  sessionId: number;
  beforeOrAtMessageId: number;
  maxTurns: number;
  turnCount: number;
  messageCount: number;
  legacyCount: number;
  incompleteLegacyCount: number;
  excludedMessageCount: number;
  olderTurnCount: number;
  messageIds: number[];
  turnRequestIds: string[];
  incompleteLegacyMessageIds: number[];
  /** 受影响记忆：会被删的原话证据、挂在证据上的建议（不分状态）、失去唯一来源的自动事实、只断来源的人工保护事实。 */
  affectedEvidence: number;
  affectedProposals: number;
  autoFactsRemoved: number;
  protectedFactsKept: number;
  historyBytes: number;
  attachmentBytes: number;
  /** 提交清理时必须原样带回的 CAS 基线。 */
  historyRevision: number;
  expiresAt: string;
}

/** 执行结果：实删数与预览报的数同源，界面只拿它写一句结果。 */
export interface ChatCleanupResult {
  sessionId: number;
  cleanedTurns: number;
  cleanedLegacyRows: number;
  messageCount: number;
  releasedHistoryBytes: number;
  releasedAttachmentBytes: number;
  evidenceDeleted: number;
  proposalsDeleted: number;
  autoFactsRemoved: number;
  protectedFactsKept: number;
  checkpointsInvalidated: number;
  jobsSuperseded: number;
  historyRevision: number;
  storage: ChatStorageSnapshot;
  /** 同一个 operationId 的第二次提交：服务端回放当初那份结果。 */
  replayed?: boolean;
}

export const storageQueryKey = ['ai-history-storage'] as const;

/** 读一次账本；服务端刻意不因读取而重建计数。 */
export const readStorageSnapshot = (): Promise<ChatStorageSnapshot> =>
  get<ChatStorageSnapshot>('/api/ai/history/storage');

/** 预览：只读。返回的头里带 `previewId` 与写死的范围，执行时不再按日期重新扩大。 */
export const previewCleanup = (body: {
  sessionId: number;
  beforeOrAtMessageId: number;
  maxTurns: number;
}): Promise<ChatCleanupPreview> => post<ChatCleanupPreview>('/api/ai/history/cleanup/preview', body);

/** 执行：必须原样带上预览给的 revision，并显式 confirm。 */
export const executeCleanup = (body: {
  previewId: string;
  expectedHistoryRevision: number;
  operationId: string;
  confirm: true;
}): Promise<ChatCleanupResult> => post<ChatCleanupResult>('/api/ai/history/cleanup', body);
