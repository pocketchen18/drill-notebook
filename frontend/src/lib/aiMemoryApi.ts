import { del, delJson, get, patch, post, put } from './api';
import type {
  ConsentPurpose,
  MemoryItem,
  MemoryItemEdit,
  MemoryItemInput,
  MemoryListPage,
  MemoryPatch,
  MemorySettings,
  MemoryStatus,
  MemoryStatusBody,
  MemoryTarget,
  MemoryVaultStatus,
  VaultMode
} from './aiMemoryTypes';

/**
 * 记忆管理 REST（contracts §3）。判定都在后端，这一层只负责：路径、请求体键名、返回类型。
 *
 * <p>三条纪律：主密码与会话密码只能走请求体（旧 `/messages` 的 query 密码不复用）；
 * 列表与搜索的关键词也走请求体，URL 里不留用户原话；编辑与删除必须带 expectedVersion，
 * 服务端从不相信前端禁用按钮。</p>
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
