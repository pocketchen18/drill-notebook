import { get } from './api';

export interface RoundPreview {
  roundNumber: number;
  totalRounds: number;
  messageId?: number;
  question: string;
  answer: string;
  createdAt?: string;
  status?: string | null;
}
export const getRoundPreview = (sessionId: number, roundNumber: number) =>
  get<RoundPreview>(`/api/ai/sessions/${sessionId}/navigation/${roundNumber}`);

export function previewText(value: string): string {
  return value.replace(/!\[[^\]]*\]\([^)]*\)/g, '[图片]').replace(/\[([^\]]+)\]\([^)]*\)/g, '$1')
    .replace(/```[^\n]*\n?/g, '').replace(/^\s{0,3}[#>]+\s*/gm, '').replace(/[*_`]/g, '').trim();
}
