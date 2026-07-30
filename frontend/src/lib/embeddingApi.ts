import { del, get, post, put } from './api';
import type { EmbeddingCatalog, EmbeddingSlot, RetrievalStatus } from './types';

/**
 * Embedding 设置与本地模型目录 API（Task 12/13）。
 * 所有状态机/幂等决策在后端，前端只做展示与调用。
 */

/** 本地模型目录（内置数据 + 安装状态 + 在线目录缓存）。 */
export const getEmbeddingCatalog = () => get<EmbeddingCatalog>('/api/ai/embeddings/catalog');

/** 在线刷新模型目录（从 HuggingFace 拉取最新可用模型列表）。 */
export const refreshEmbeddingCatalog = () =>
  get<EmbeddingCatalog>('/api/ai/embeddings/catalog?refresh=true');

/** 向量索引状态（scope=all 覆盖全部笔记本）。 */
export const getRetrievalStatus = (scope: 'all' | 'current' = 'all', notebookId?: number) =>
  get<RetrievalStatus>(
    `/api/ai/retrieval/status?scope=${scope}${notebookId != null ? `&notebookId=${notebookId}` : ''}`
  );

export interface EmbeddingConfigInput {
  provider: 'disabled' | 'local' | 'openai' | 'ollama';
  endpoint?: string;
  model?: string;
  dimensions?: number;
  apiKey?: string;
  /** 远程授权确认；仅在用户勾选后发送 true。 */
  remoteContentConsent?: boolean;
}

/** 保存 embedding 配置（purpose=embedding）。 */
export const saveEmbeddingConfig = (input: EmbeddingConfigInput) =>
  put<EmbeddingSlot>('/api/ai/config', { purpose: 'embedding', ...input });

/** 用固定探针文本测试已保存的远程配置。 */
export const testEmbeddingEndpoint = () =>
  post<{ ok?: boolean; dimensions?: number; latencyMs?: number; errorCode?: string; message?: string }>(
    '/api/ai/embeddings/test',
    {}
  );

/** 下载本地模型；activateAfterDownload 必须显式提供。 */
export const downloadEmbeddingModel = (id: string, activateAfterDownload: boolean) =>
  post<{ jobId: string; state: string }>(
    `/api/ai/embeddings/models/${encodeURIComponent(id)}/download`,
    { activateAfterDownload }
  );

/** 取消（暂停）下载，保留可续传的 partial。 */
export const cancelEmbeddingDownload = (jobId: string) =>
  del<{ jobId: string; state: string }>(`/api/ai/embeddings/downloads/${encodeURIComponent(jobId)}`);

/** 启用已 READY 的本地模型并触发后台重建。 */
export const activateEmbeddingModel = (id: string) =>
  post<{ embeddingSpaceId: string; reindexJobId: string; state: string }>(
    `/api/ai/embeddings/models/${encodeURIComponent(id)}/activate`,
    {}
  );

/** 停用本地模型（保留文件与向量）。 */
export const disableEmbeddingModel = (id: string) =>
  post<{ state: string }>(`/api/ai/embeddings/models/${encodeURIComponent(id)}/disable`, {});

/** 彻底卸载：删除模型文件与向量，保留 BM25 索引。必须 confirm=true。 */
export const uninstallEmbeddingModel = (id: string) =>
  del<{ jobId: string; state: string }>(
    `/api/ai/embeddings/models/${encodeURIComponent(id)}?confirm=true`
  );

/** 重建/回填向量索引。mode=missing 仅补缺，full 全量重建。 */
export const reindexEmbeddings = (mode: 'missing' | 'full', scope: 'all' | 'current' = 'all', notebookId?: number) =>
  post<{ jobId: string; state: string }>('/api/ai/retrieval/reindex', { scope, notebookId, mode });

/** 重试失败的 embedding 任务。 */
export const retryFailedEmbeddings = (scope: 'all' | 'current' = 'all', notebookId?: number) =>
  post<{ requeued: number }>('/api/ai/retrieval/retry-failed', { scope, notebookId });

/** 字节数 → MB 文本（与目录 sizeBytes 一致，1 MiB=1048576）。 */
export function formatModelSize(bytes: number): string {
  const mib = bytes / 1048576;
  return `${mib.toFixed(2)} MiB`;
}

/** 依据 downloadProgress 估算已完成字节与百分比。 */
export function downloadPercent(model: {
  inventorySizeBytes: number;
  downloadProgress?: { totalBytes?: number; files?: Record<string, number>; [key: string]: unknown } | null;
}): number {
  const progress = model.downloadProgress;
  const total = progress?.totalBytes ?? model.inventorySizeBytes;
  if (!progress || !total) return 0;
  let done = 0;
  if (progress.files) {
    done = Object.values(progress.files).reduce((sum, n) => sum + (typeof n === 'number' ? n : 0), 0);
  } else {
    for (const [key, value] of Object.entries(progress)) {
      if (key !== 'totalBytes' && key !== 'jobId' && typeof value === 'number') done += value;
    }
  }
  return Math.min(100, Math.round((done / total) * 100));
}
