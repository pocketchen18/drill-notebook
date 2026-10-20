import { get } from './api';

/**
 * 会话存储容量的只读出口（contracts §2.7、§3 的 `GET /history/storage`）。
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

export const storageQueryKey = ['ai-history-storage'] as const;

/** 读一次账本；服务端刻意不因读取而重建计数。 */
export const readStorageSnapshot = (): Promise<ChatStorageSnapshot> =>
  get<ChatStorageSnapshot>('/api/ai/history/storage');
