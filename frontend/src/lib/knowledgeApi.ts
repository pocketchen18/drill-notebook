import { post, put } from './api';

export interface SummaryResult {
  summarized: number;
  failed: number;
  errors: string[];
}

export interface SummarizeImportResult {
  imported: number;
  failed: number;
  errors: string[];
  strategy: string;
}

// 后端 @RequestParam → 走 query string
export async function summarizeBank(bankId: number): Promise<SummaryResult> {
  return post<SummaryResult>(`/api/knowledge-points/summarize?bankId=${bankId}`, {});
}

export async function resummarizeBank(bankId: number): Promise<SummaryResult> {
  return post<SummaryResult>(`/api/knowledge-points/resummarize?bankId=${bankId}`, {});
}

// 后端 @RequestBody → 走 body
export async function summarizeImport(bankId: number, content: string): Promise<SummarizeImportResult> {
  return post<SummarizeImportResult>('/api/knowledge-points/summarize-import', { bankId, content });
}

export async function summarizePoint(pointId: number): Promise<SummaryResult> {
  return post<SummaryResult>(`/api/knowledge-points/${pointId}/summarize`, {});
}

export async function resummarizePoint(pointId: number): Promise<SummaryResult> {
  return post<SummaryResult>(`/api/knowledge-points/${pointId}/resummarize`, {});
}

export async function restoreOriginal(pointId: number): Promise<{ content: string }> {
  return post<{ content: string }>(`/api/knowledge-points/${pointId}/restore-original`, {});
}

export async function restoreSummary(pointId: number): Promise<{ content: string }> {
  return post<{ content: string }>(`/api/knowledge-points/${pointId}/restore-summary`, {});
}

// 批量删除知识点：后端单事务级联删除，返回实际删除数
export async function deleteKnowledgePoints(ids: number[]): Promise<{ deleted: number }> {
  return post<{ deleted: number }>('/api/knowledge-points/batch-delete', { ids });
}

/**
 * 全量更新某题库知识点的顺序：sortedIds 按新顺序包含该题库全部知识点 id。
 * 后端从 0 开始递增写入 sort_index。
 */
export async function sortKnowledgePoints(bankId: number, sortedIds: number[]): Promise<void> {
  return put<void>('/api/knowledge-points/sort-all', { bankId, sortedIds });
}
