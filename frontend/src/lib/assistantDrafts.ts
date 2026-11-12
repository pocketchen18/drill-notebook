export interface DraftAttachment {
  id: string; name: string; kind: 'text' | 'image'; value: string; mime: string; bytes: number; codepoints?: number;
}
export interface AssistantDraftDocument {
  format: 1;
  selectedSessionId: number | null;
  choice: { modelConfigId: string | null; reasoningEffort: string | null };
  titleDraft: string | null;
  drafts: Array<{ sessionId: number | null; message: string; attachments: DraftAttachment[]; scroll: number }>;
}
const sessionId = (value: unknown): number | null => {
  if (value === null || typeof value === 'number' && Number.isSafeInteger(value) && value > 0) return value;
  throw new Error('助手草稿会话标识无效，原文件已保留。');
};

export function parseAssistantDrafts(raw: unknown): AssistantDraftDocument | null {
  if (raw === null || raw === undefined) return null;
  if (typeof raw !== 'object' || (raw as { format?: number }).format !== 1 || !Array.isArray((raw as { drafts?: unknown }).drafts)) throw new Error('助手草稿格式无效，原文件已保留。');
  const data = raw as AssistantDraftDocument;
  if (data.drafts.length > 500) throw new Error('助手草稿数量超过安全限制，原文件已保留。');
  const ids = new Set<number | null>();
  const drafts = data.drafts.map(draft => {
    if (!draft || typeof draft.message !== 'string' || !Array.isArray(draft.attachments)) throw new Error('助手草稿内容无效，原文件已保留。');
    const id = sessionId(draft.sessionId);
    if (ids.has(id)) throw new Error('助手草稿会话标识重复，原文件已保留。');
    ids.add(id);
    const attachmentIds = new Set<string>();
    const attachments = draft.attachments.map(item => {
      if (!item || typeof item.id !== 'string' || typeof item.name !== 'string' || !['text', 'image'].includes(item.kind)
        || !item.id || attachmentIds.has(item.id) || typeof item.value !== 'string' || typeof item.mime !== 'string' || !Number.isSafeInteger(item.bytes) || item.bytes < 0
        || (item.codepoints !== undefined && (!Number.isSafeInteger(item.codepoints) || item.codepoints < 0))
        || (item.kind === 'image' && !/^data:image\/(?:png|jpeg|gif|webp|bmp);base64,/i.test(item.value))) throw new Error('助手草稿附件无效，原文件已保留。');
      attachmentIds.add(item.id);
      return { ...item };
    });
    return { sessionId: id, message: draft.message, attachments,
      scroll: typeof draft.scroll === 'number' && Number.isFinite(draft.scroll) ? Math.max(0, draft.scroll) : 0 };
  });
  const choice = data.choice;
  return { format: 1, selectedSessionId: sessionId(data.selectedSessionId),
    choice: { modelConfigId: typeof choice?.modelConfigId === 'string' ? choice.modelConfigId : null,
      reasoningEffort: typeof choice?.reasoningEffort === 'string' ? choice.reasoningEffort : null },
    titleDraft: typeof data.titleDraft === 'string' ? data.titleDraft : null, drafts };
}
