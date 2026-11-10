import { useQuery } from '@tanstack/react-query';
import { get } from '../../lib/api';
import type { ConnectionModel, ConnectionProvider, ModelPreset, ReasoningControl } from './types';

export const EFFORTS = ['none', 'minimal', 'low', 'medium', 'high', 'xhigh', 'max'];
export const supportedEfforts = (format: string) => format === 'anthropic' ? EFFORTS.filter(value => !['none', 'minimal'].includes(value)) : EFFORTS;
export function effortLabel(levels: string[]): string {
  const ordered = EFFORTS.filter(value => levels.includes(value));
  if (!ordered.length) return '未设置';
  const parts: string[] = [];
  let start = 0;
  for (let i = 1; i <= ordered.length; i++) {
    if (i < ordered.length && EFFORTS.indexOf(ordered[i]) === EFFORTS.indexOf(ordered[i - 1]) + 1) continue;
    const run = ordered.slice(start, i);
    parts.push(...(run.length >= 3 ? [`${run[0]} → ${run[run.length - 1]}`] : run)); start = i;
  }
  return parts.join(', ');
}
export function useModelPresets(provider: ConnectionProvider) {
  const result = useQuery({ queryKey: ['ai-model-presets', provider.id, provider.endpoint, provider.apiFormat],
    queryFn: () => get<ModelPreset[]>(`/api/ai/connections/providers/${provider.id}/presets`), staleTime: Infinity, retry: false });
  return { ...result, data: Array.isArray(result.data) ? result.data : [] };
}
export function matchPreset(presets: ModelPreset[], id: string) {
  const matches = presets.filter(preset => preset.id === id.trim() || preset.aliases.includes(id.trim()));
  return matches.length === 1 ? matches[0] : undefined;
}
export interface ModelDraft {
  modelId: string; name: string; group: string; streaming: boolean; tools: boolean;
  contextWindow: string; maxOutputTokens: string;
  reasoningControl: ReasoningControl; reasoningEfforts: string[]; defaultReasoningEffort: string; thinkingBudgetTokens: string;
}
export function reasoningDraft(model?: ConnectionModel): Pick<ModelDraft, 'reasoningControl' | 'reasoningEfforts' | 'defaultReasoningEffort' | 'thinkingBudgetTokens'> {
  return { reasoningControl: model?.reasoningControl ?? 'effort', reasoningEfforts: model?.reasoningEfforts ?? [],
    defaultReasoningEffort: model?.defaultReasoningEffort ?? '', thinkingBudgetTokens: String(model?.thinkingBudgetTokens ?? '') };
}
export function presetFields(preset?: ModelPreset): Partial<ModelDraft> {
  return { name: preset?.name ?? '', contextWindow: String(preset?.contextWindow ?? ''), maxOutputTokens: String(preset?.maxOutputTokens ?? ''),
    reasoningControl: preset?.reasoningControl ?? 'effort', reasoningEfforts: preset?.reasoningEfforts ?? [], thinkingBudgetTokens: '' };
}
