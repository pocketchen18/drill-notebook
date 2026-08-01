import { post } from './api';

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
