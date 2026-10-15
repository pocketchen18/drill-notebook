/**
 * 记忆 REST 的响应形状（contracts §3）。键名逐一对齐后端实序列化：全部 camelCase，
 * 后端会显式 put(null)（如 validUntil、quote），所以这些字段是 `T | null`；
 * 而解密失败的行只是<b>没有</b> predicate/value 两个键并给出 corrupt=true，所以它们标成可选。
 * 界面判"这行读不出来"要看 corrupt，不能靠键是否存在猜。
 */

export type MemoryKind = 'PREFERENCE' | 'GOAL' | 'BACKGROUND' | 'TASK';
export type MemoryScopeType = 'GLOBAL' | 'NOTEBOOK';
/** 列表可过滤的状态；ACTIVE 是默认值（后端 blank 时按 ACTIVE 处理）。 */
export type MemoryStatus = 'ACTIVE' | 'PENDING' | 'DISABLED' | 'SUPERSEDED' | 'EXPIRED';
export type MemoryOrigin = 'AUTO' | 'MANUAL';
export type VaultMode = 'fingerprint' | 'password';
export type ConsentPurpose = 'MEMORY_USE' | 'MEMORY_MAINTENANCE';
export type ConsentState = 'GRANTED' | 'MISSING' | 'STALE' | 'TARGET_MISSING';
export type MaintenanceGate =
  | 'ALLOWED'
  | 'MEMORY_DISABLED'
  | 'AUTO_EXTRACT_DISABLED'
  | 'COMPRESS_DISABLED'
  | 'VAULT_MISSING'
  | 'LOCKED'
  | 'CONSENT_MISSING'
  | 'CONSENT_STALE'
  | 'TARGET_MISSING'
  | 'SCOPE_INVALID'
  | 'QUOTA_ACTIVE_FULL'
  | 'QUOTA_PENDING_FULL';

/** 服务端解析出的实际外发目标；授权比对的就是它的 fingerprint。 */
export interface MemoryTarget {
  purpose: string;
  slot: string;
  provider?: string;
  host?: string;
  model?: string;
  apiFormat?: string;
  configured: boolean;
  keyPresent: boolean;
  fingerprint?: string;
}

export interface MemoryConsentStatus {
  purpose: string;
  state: ConsentState;
  granted: boolean;
  target: MemoryTarget;
  storedFingerprint?: string;
}

/** 记忆库脱敏状态：不含包装层、DEK 与主材料。 */
export interface MemoryVaultStatus {
  initialized: boolean;
  unlocked: boolean;
  sessionUnlockedCount: number;
  vaultId?: string;
  mode?: VaultMode;
  epoch?: number;
  /** 库自己的重包装版本；与设置行的 revision 不是一回事，设置快照里另给 vaultRevision。 */
  revision?: number;
}

export interface MemorySettings extends MemoryVaultStatus {
  /** 设置行版本：PUT /settings 的 CAS 前提。 */
  revision: number;
  enabled: boolean;
  autoExtract: boolean;
  compressSession: boolean;
  autoApplyLowRisk: boolean;
  updatedAt?: string;
  consentedAt?: string;
  policyVersion: string;
  maintenanceModelMode: 'chat' | 'dedicated';
  dailyCallLimit: number;
  dailyTokenLimit: number;
  budgetTimezone: string;
  memoryHistoryFloor: number;
  consents: Partial<Record<ConsentPurpose, MemoryConsentStatus>>;
  limits: { activeFacts: number; pendingFacts: number };
  vaultRevision?: number;
}

export interface MemorySource {
  evidenceId: string;
  sessionId: number;
  turnId: number;
  messageId: number;
  sourceBlockId?: string;
  sourceCreatedAt?: string;
  /** 来源原话；该行密文不可用时为 null 且 corrupt=true。 */
  quote?: string | null;
  corrupt?: boolean;
}

export interface MemoryItem {
  memoryId: string;
  version: number;
  kind: MemoryKind;
  scopeType: MemoryScopeType;
  scopeId: number;
  status: MemoryStatus;
  validUntil: string | null;
  recordedAt: string;
  origin: MemoryOrigin;
  /** 人工改过的保护标记：自动提取不得覆盖它。 */
  manualProtected: boolean;
  pinned: boolean;
  validFrom: string | null;
  observedAt: string | null;
  lastConfirmedAt: string | null;
  predicate?: string;
  value?: string;
  corrupt: boolean;
  corruptCode?: string;
  sources?: MemorySource[];
  /** 待确认候选能不能「分别保留」：判定表只在服务端有一份。 */
  multiValued?: boolean;
  /** 为什么这条要人工确认：code + 服务端给的中文说法。 */
  reviewReason?: string;
  reviewReasonText?: string;
  /** 候选是被哪一条顶下来的：替换选择器据此预选旧目标。 */
  proposedAgainstMemoryId?: string;
  proposedAgainstVersion?: number;
}

/** 建议审阅的三个按钮；待确认候选多一个 replace（contracts §3）。 */
export type ProposalDecision = 'accept' | 'reject' | 'keep_both';
export type CandidateDecision = ProposalDecision | 'replace';

/** 建议指向的那条记忆的当前生效版：新值与它并排显示，三按钮才按得下去。 */
export interface MemoryCurrentFact {
  memoryId: string;
  version: number;
  kind: MemoryKind;
  scopeType: MemoryScopeType;
  scopeId: number;
  status: MemoryStatus;
  validUntil: string | null;
  recordedAt: string;
  value?: string | null;
  corrupt?: boolean;
}

export type ProposalOperation = 'REPLACE_PROPOSAL' | 'RETRACT_PROPOSAL';
export type ProposalState = 'PENDING' | 'ACCEPTED' | 'REJECTED' | 'STALE';

export interface MemoryProposal {
  proposalId: string;
  version: number;
  status: ProposalState;
  operation: ProposalOperation;
  targetMemoryId: string | null;
  targetVersion: number;
  sessionId: number;
  sourceTurnId: number | null;
  createdAt: string;
  expiresAt: string;
  /** 载荷解不开的行只带上面这些元信息，正文一律缺席。 */
  corrupt: boolean;
  corruptCode?: string;
  scopeType?: MemoryScopeType;
  scopeId?: number;
  predicate?: string;
  newValue?: string | null;
  reviewReason?: string;
  reviewReasonText?: string;
  multiValued?: boolean;
  quote?: string | null;
  target?: MemoryCurrentFact | null;
}

export interface MemoryProposalPage {
  proposals: MemoryProposal[];
  limit: number;
  hasMore: boolean;
  nextCursor?: string;
  vaultEpoch: number;
  vaultRevision: number;
}

/** 审阅两条路由共用的响应形状（正文一律不回来，要看内容走列表或详情）。 */
export interface MemoryReviewResult {
  memoryId: string;
  version: number;
  status: string;
  decision?: string;
  changed: boolean;
  replayed?: boolean;
  proposalId?: string;
  pendingMemoryId?: string;
}

/** 清空全部 AI 记忆：没删完时 cleared=false，界面拿返回的 epoch 继续清。 */
export interface MemoryClearResult {
  cleared: boolean;
  remaining?: boolean;
  epoch: number;
  revision: number;
  memoryEnabled: boolean;
  autoExtract: boolean;
  scanWatermarksAdvanced: number;
  purged: {
    facts: number;
    evidence: number;
    proposals: number;
    suppressions: number;
    jobs: number;
    contexts: number;
  };
  message?: string;
  replayed?: boolean;
}

/** 409 冲突时服务端给的"最新 metadata"（不含正文，要看正文得再取一次详情）。 */
export interface MemoryFactKeys {
  memoryId: string;
  version: number;
  kind: MemoryKind;
  scopeType: MemoryScopeType;
  scopeId: number;
  status: MemoryStatus;
  validUntil: string | null;
  recordedAt: string;
}

export interface MemoryListPage {
  items: MemoryItem[];
  limit: number;
  status: MemoryStatus;
  hasMore: boolean;
  nextCursor?: string;
  vaultRevision: number;
}

export interface MemoryStatusBody extends MemoryVaultStatus {
  limits: { activeFacts: number; pendingFacts: number };
  activeFacts?: number;
  pendingFacts?: number;
  pendingProposals?: number;
  suppressions?: number;
  queuedJobs: number;
  retryJobs: number;
  extraction: { gate: MaintenanceGate; message?: string };
}

export interface MemoryItemInput {
  kind: MemoryKind;
  scopeType: MemoryScopeType;
  scopeId?: number;
  predicate?: string;
  family?: string;
  value: string;
  validUntil?: string;
  /** 内容被判为凭据类时必须显式为 true，否则 409。 */
  sensitiveConfirmed?: boolean;
  operationId?: string;
}

export interface MemoryItemEdit {
  expectedVersion: number;
  predicate?: string;
  family?: string;
  value?: string;
  validUntil?: string;
  status?: MemoryStatus;
}

export interface MemoryPatch {
  enabled?: boolean;
  autoExtract?: boolean;
  compressSession?: boolean;
  autoApplyLowRisk?: boolean;
  policy?: {
    maintenanceModelMode?: 'chat' | 'dedicated';
    dailyCallLimit?: number;
    dailyTokenLimit?: number;
    budgetTimezone?: string;
  };
}
