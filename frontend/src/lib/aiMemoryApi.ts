import { del, delJson, get, patch, post, put } from './api';
import type {
  CandidateDecision,
  ConsentPurpose,
  MemoryClearResult,
  MemoryItem,
  MemoryItemEdit,
  MemoryItemInput,
  MemoryListPage,
  MemoryPatch,
  MemoryProposalPage,
  MemoryReviewResult,
  MemoryScopeType,
  MemorySettings,
  MemoryStatus,
  MemoryStatusBody,
  MemoryTarget,
  MemoryVaultStatus,
  ProposalDecision,
  VaultMode
} from './aiMemoryTypes';

/**
 * 记忆管理 REST（contracts §3）。判定都在后端，这一层只负责：路径、请求体键名、返回类型。
 *
 * <p>三条纪律：主密码与会话密码只能走请求体（旧 `/messages` 的 query 密码不复用）；
 * 列表与搜索的关键词也走请求体，URL 里只允许 scope/limit/cursor 这类非内容筛选条件；
 * 编辑与删除必须带 expectedVersion，服务端从不相信前端禁用按钮。</p>
 */

const BASE = '/api/ai/memory';

export interface MemorySearchParams {
  scopeType?: 'GLOBAL' | 'NOTEBOOK';
  scopeId?: number;
  status?: MemoryStatus;
  query?: string;
  limit?: number;
  cursor?: string;
}

/** PATCH 改状态只回三键，改正文回整条视图，界面按这个联合类型分支。 */
export interface MemoryStatusEditResult {
  memoryId: string;
  version: number;
  status: MemoryStatus;
  changed?: boolean;
}

export function isMemoryItem(view: MemoryItem | MemoryStatusEditResult): view is MemoryItem {
  return 'recordedAt' in view;
}

export const getMemorySettings = () => get<MemorySettings>(`${BASE}/settings`);

export const updateMemorySettings = (revision: number, changes: MemoryPatch) =>
  put<{ revision: number }>(`${BASE}/settings`, { revision, ...changes });

export const grantMemoryConsent = (purpose: ConsentPurpose, targetFingerprint: string, policyVersion: string) =>
  post<{ revision: number; purpose: string; consented: boolean; target: MemoryTarget }>(`${BASE}/consents`, {
    purpose,
    targetFingerprint,
    policyVersion
  });

/** 撤销授权返回 204，控制器按规则把正文整份丢掉，所以这里没有可用返回体。 */
export const revokeMemoryConsent = (purpose: ConsentPurpose) =>
  del<void>(`${BASE}/consents/${encodeURIComponent(purpose)}`);

export const createMemoryVault = (mode: VaultMode, password?: string, operationId?: string) =>
  post<MemoryVaultStatus>(`${BASE}/vault`, { mode, password, operationId });

export const unlockMemoryVault = (password?: string, sessionUnlocks?: { sessionId: number; password: string }[]) =>
  post<MemoryVaultStatus>(`${BASE}/vault/unlock`, { password, sessionUnlocks });

export const lockMemoryVault = () => post<void>(`${BASE}/vault/lock`, {});

export const changeMemoryVaultPassword = (oldPassword: string, password: string) =>
  put<MemoryVaultStatus>(`${BASE}/vault/password`, { oldPassword, password });

/** 重置不可解锁的记忆库：必须显式 confirm=true，且不清学习/使用开关。 */
export const resetMemoryVault = (mode: VaultMode, password: string | undefined, operationId?: string) =>
  post<MemoryVaultStatus>(`${BASE}/vault/reset`, { confirm: true, mode, password, operationId });

export const addMemoryItem = (input: MemoryItemInput) => post<MemoryItem>(`${BASE}/items`, input);

export const getMemoryItem = (memoryId: string) =>
  get<MemoryItem>(`${BASE}/items/${encodeURIComponent(memoryId)}`);

export const editMemoryItem = (memoryId: string, edit: MemoryItemEdit) =>
  patch<MemoryItem | MemoryStatusEditResult>(`${BASE}/items/${encodeURIComponent(memoryId)}`, edit);

/** forget=true 会同时推进全局遗忘边界，属于不可逆动作，由界面确认后显式传入。 */
export const deleteMemoryItem = (memoryId: string, expectedVersion: number, forget: boolean, operationId?: string) =>
  delJson<void>(`${BASE}/items/${encodeURIComponent(memoryId)}`, { expectedVersion, forget, operationId });

export const searchMemoryItems = (params: MemorySearchParams) =>
  post<MemoryListPage>(`${BASE}/search`, params);

export const getMemoryStatus = () => get<MemoryStatusBody>(`${BASE}/status`);

/** 建议列表的筛选条件：只有这三类非内容参数可以进 URL（后端有用例白名单钉着）。 */
export interface MemoryProposalParams {
  scopeType?: MemoryScopeType;
  scopeId?: number;
  limit?: number;
  cursor?: string;
}

export function listMemoryProposals(params: MemoryProposalParams = {}): Promise<MemoryProposalPage> {
  const query = new URLSearchParams();
  if (params.scopeType) query.set('scopeType', params.scopeType);
  // 不带 scopeType 就不写 scopeId：后端只在接受筛选时才看它，URL 里也不留多余的数字
  if (params.scopeType && params.scopeId != null) query.set('scopeId', String(params.scopeId));
  if (params.limit != null) query.set('limit', String(params.limit));
  if (params.cursor) query.set('cursor', params.cursor);
  const suffix = query.toString();
  return get<MemoryProposalPage>(`${BASE}/proposals${suffix ? `?${suffix}` : ''}`);
}

export interface ProposalReview {
  expectedVersion: number;
  /** 采纳与并存必须带上用户看到的「当前值」版本；拒绝不带。 */
  expectedTargetVersion?: number;
  decision: ProposalDecision;
  operationId?: string;
}

export interface CandidateReview {
  expectedVersion: number;
  decision: CandidateDecision;
  /** 只有 replace 需要点名旧目标（§3：人工选择才允许替换）。 */
  targetMemoryId?: string;
  expectedTargetVersion?: number;
  operationId?: string;
}

export const reviewMemoryProposal = (proposalId: string, review: ProposalReview) =>
  post<MemoryReviewResult>(`${BASE}/proposals/${encodeURIComponent(proposalId)}/review`, review);

export const reviewMemoryCandidate = (memoryId: string, review: CandidateReview) =>
  post<MemoryReviewResult>(`${BASE}/items/${encodeURIComponent(memoryId)}/review`, review);

/**
 * 清空全部 AI 记忆。202 也是成功响应（还有剩余行），调用方拿返回的 epoch 继续清，
 * 所以这里不能把非 200 当失败——`apiRequest` 只在 4xx/5xx 抛错。
 */
export const clearAllMemories = (expectedVaultEpoch: number, operationId?: string) =>
  post<MemoryClearResult>(`${BASE}/clear`, { confirm: true, expectedVaultEpoch, operationId });
