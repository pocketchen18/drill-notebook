import type { QueryClient } from '@tanstack/react-query';
import type { NotePage, Notebook } from './types';
import type { ToolActivity } from './toolActivity';
import { materialLinkTarget } from './pageSnapshot';
import { notifyMaterialDeleted } from './materialEvents';

export function isConfirmedToolMutation(row: ToolActivity): boolean {
  return row.state === 'FINISHED'
    && ['create_material', 'update_material', 'delete_material', 'remove_wrong_question', 'delete_memory', 'delete_session'].includes(row.toolName)
    && ['CREATED', 'UPDATED', 'DELETED', 'REMOVED', 'DUPLICATE', 'NO_CHANGE'].includes(row.outcome);
}

/** 只处理本次运行的确认事件；加载旧聊天记录不会再次丢弃当前草稿。 */
export function refreshToolMutation(client: QueryClient, row: ToolActivity): void {
  if (!isConfirmedToolMutation(row)) return;
  const invalidate = (keys: string[]) => {
    for (const key of keys) void client.invalidateQueries({ queryKey: [key] });
  };
  if (row.toolName === 'delete_memory') { invalidate(['ai-memory-list', 'ai-memory-status', 'ai-memory-item']); return; }
  if (row.toolName === 'delete_session') { invalidate(['ai-sessions', 'ai-memory-list', 'ai-memory-item', 'ai-history-storage']); return; }
  const target = row.sourceLink ? materialLinkTarget(row.sourceLink) : undefined;
  if (!target) return;
  if (row.toolName === 'delete_material' && (target.type === 'note' || target.type === 'notebook')) {
    notifyMaterialDeleted({ type: target.type, id: target.id });
    const matches = (page: NotePage) => target.type === 'note' ? page.id === target.id : page.notebookId === target.id;
    const pageIds = new Set<number>(target.type === 'note' ? [target.id] : []);
    for (const [, pages] of client.getQueriesData<NotePage[]>({ queryKey: ['note-pages'] }))
      for (const page of pages ?? []) if (matches(page)) pageIds.add(page.id);
    for (const [, page] of client.getQueriesData<NotePage>({ queryKey: ['note-page'] }))
      if (page && matches(page)) pageIds.add(page.id);
    for (const id of pageIds) for (const key of ['note-page', 'note-page-deep-link']) {
      void client.cancelQueries({ queryKey: [key, id], exact: true });
      client.removeQueries({ queryKey: [key, id], exact: true });
    }
    client.setQueriesData<NotePage[]>({ queryKey: ['note-pages'] }, pages => pages?.filter(page => !matches(page)));
    if (target.type === 'notebook') {
      client.setQueryData<Notebook[]>(['notebooks'], books => books?.filter(book => book.id !== target.id));
      invalidate(['ai-memory-list', 'ai-memory-status', 'ai-memory-item']);
    }
  }
  const keys = target.type === 'notebook' || target.type === 'note' ? ['notebooks', 'note-pages', 'ai-drawer-pages']
    : target.type === 'knowledge' ? ['knowledge-points', 'knowledge-questions']
      : target.type === 'bank' ? ['banks', 'questions', 'study-questions', 'quiz-questions', 'knowledge-points', 'knowledge-questions', 'wrong']
        : ['banks', 'questions', 'study-questions', 'quiz-questions', 'knowledge-questions', 'wrong'];
  invalidate(keys);
  if (row.toolName === 'delete_material' || row.toolName === 'remove_wrong_question')
    invalidate(['study-today', 'study-plans', 'study-plans-day', 'review-calendar-stats', 'review-due', 'review-stats']);
  if (row.toolName === 'update_material' && target.type === 'note') {
    void client.invalidateQueries({ queryKey: ['note-page', target.id] });
    void client.invalidateQueries({ queryKey: ['note-page-deep-link', target.id] });
  }
}
