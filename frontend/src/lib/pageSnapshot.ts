import type { AiPageContext } from '../stores/uiStore';

export interface PageSnapshot {
  kind: AiPageContext['kind']; title: string; route?: string; capturedAt: string;
  markdown: string; selection?: string; isDraft: boolean; truncated: boolean;
  pageId?: number; notebookId?: number; bankId?: number; pointId?: number; questionId?: number; selectedIds?: number[];
}

const clip = (text: string, max: number): string => Array.from(text).slice(0, max).join('');
// 可选所属库在旧 API 中可能返回 null；快照只携带可用定位，不能把 null 传给严格 ID 校验。
const positiveId = (value: unknown): number | undefined => typeof value === 'number' && Number.isSafeInteger(value) && value > 0 ? value : undefined;

/** 只复制业务页面注册的内容，不扫描设置/聊天 DOM，不读取浏览器存储。 */
export function capturePageSnapshot(context: AiPageContext, now = new Date()): PageSnapshot {
  return {
    kind: context.kind, title: clip(context.title, 200), route: context.route,
    capturedAt: now.toISOString(), markdown: clip(context.markdown, 32000),
    ...(context.selection ? { selection: clip(context.selection, 2000) } : {}),
    isDraft: context.isDraft === true,
    truncated: context.truncated === true || Array.from(context.markdown).length > 32000
      || (context.selectedIds?.length ?? 0) > 20 || Array.from(context.selection ?? '').length > 2000,
    pageId: positiveId(context.notePageId), notebookId: positiveId(context.notebookId), bankId: positiveId(context.bankId),
    pointId: positiveId(context.pointId), questionId: positiveId(context.questionId),
    ...(context.selectedIds?.length ? { selectedIds: context.selectedIds.slice(0, 20) } : {})
  };
}

/** 主界面选区由 selectionchange 记录；只认业务页面内的选区，弹层和输入框里的选区不算。 */
export function businessSelection(markdown: string): string | undefined {
  const selected = window.getSelection();
  if (!selected || selected.isCollapsed) return undefined;
  const node = selected.anchorNode?.parentElement;
  if (!node?.closest('main') || node.closest('.ai-assistant, .arco-drawer, input, textarea')) return undefined;
  const text = selected.toString().trim();
  return text && markdown.includes(text) ? clip(text, 2000) : undefined;
}

export function materialLinkTarget(link: string): { type: string; id: number } | undefined {
  const match = /^drill-notebook:\/\/material\/(notebook|note|knowledge|bank|question|wrong)\/([1-9]\d*)$/.exec(link);
  if (!match || !Number.isSafeInteger(Number(match[2]))) return undefined;
  return { type: match[1], id: Number(match[2]) };
}
