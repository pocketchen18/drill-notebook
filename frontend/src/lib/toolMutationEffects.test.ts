import { expect, it, vi } from 'vitest';
import { QueryClient } from '@tanstack/react-query';
import { refreshToolMutation } from './toolMutationEffects';
import { onMaterialDeleted } from './materialEvents';
import type { ToolActivity } from './toolActivity';

const deleted: ToolActivity = { sequence: 1, toolName: 'delete_material', state: 'FINISHED', outcome: 'DELETED',
  startedAt: null, elapsedMs: 1, count: 1, availability: null, incomplete: false, sourceLink: 'drill-notebook://material/note/11' };
it('删除笔记先通知丢弃草稿，再移除目标缓存；其他页保留', () => {
  const client = new QueryClient();
  const pages = [{ id: 11, notebookId: 1 }, { id: 37, notebookId: 1 }];
  client.setQueryData(['note-pages', 1], pages);
  client.setQueryData(['note-page', 11], pages[0]);
  client.setQueryData(['note-page-deep-link', 11], pages[0]);
  client.setQueryData(['note-page', 37], pages[1]);
  const receive = vi.fn(() => expect(client.getQueryData(['note-page', 11])).toBeDefined());
  const unsubscribe = onMaterialDeleted(receive);
  refreshToolMutation(client, deleted);
  unsubscribe();
  expect(receive).toHaveBeenCalledWith({ type: 'note', id: 11 });
  expect(client.getQueryData(['note-page', 11])).toBeUndefined();
  expect(client.getQueryData(['note-page-deep-link', 11])).toBeUndefined();
  expect(client.getQueryData(['note-pages', 1])).toEqual([pages[1]]);
  expect(client.getQueryData(['note-page', 37])).toEqual(pages[1]);
});
it('删除笔记本清理整本缓存，移出错题不会删除原题缓存', () => {
  const client = new QueryClient();
  client.setQueryData(['notebooks'], [{ id: 1 }, { id: 2 }]);
  client.setQueryData(['note-pages', 1], [{ id: 11, notebookId: 1 }]);
  client.setQueryData(['note-page', 11], { id: 11, notebookId: 1 });
  refreshToolMutation(client, { ...deleted, sourceLink: 'drill-notebook://material/notebook/1' });
  expect(client.getQueryData(['notebooks'])).toEqual([{ id: 2 }]);
  expect(client.getQueryData(['note-page', 11])).toBeUndefined();
  client.setQueryData(['questions', 1], [{ id: 11 }]);
  client.setQueryData(['wrong'], [{ questionId: 11 }]);
  refreshToolMutation(client, { ...deleted, toolName: 'remove_wrong_question', outcome: 'REMOVED', sourceLink: 'drill-notebook://material/question/11' });
  expect(client.getQueryData(['questions', 1])).toEqual([{ id: 11 }]);
  expect(client.getQueryState(['wrong'])?.isInvalidated).toBe(true);
  const invalidate = vi.spyOn(client, 'invalidateQueries');
  refreshToolMutation(client, { ...deleted, sourceLink: 'drill-notebook://material/bank/1' });
  expect(invalidate).toHaveBeenCalledWith({ queryKey: ['study-questions'] });
  expect(invalidate).toHaveBeenCalledWith({ queryKey: ['review-calendar-stats'] });
});
it('失败或未确认不移除缓存，记忆和会话成功后刷新各自列表', () => {
  const client = new QueryClient();
  const invalidate = vi.spyOn(client, 'invalidateQueries');
  client.setQueryData(['note-page', 11], { id: 11 });
  refreshToolMutation(client, { ...deleted, outcome: 'UNAVAILABLE' });
  expect(client.getQueryData(['note-page', 11])).toEqual({ id: 11 });
  expect(invalidate).not.toHaveBeenCalled();
  refreshToolMutation(client, { ...deleted, toolName: 'delete_memory', sourceLink: undefined });
  expect(invalidate).toHaveBeenCalledWith({ queryKey: ['ai-memory-list'] });
  refreshToolMutation(client, { ...deleted, toolName: 'delete_session', sourceLink: undefined });
  expect(invalidate).toHaveBeenCalledWith({ queryKey: ['ai-sessions'] });
  expect(invalidate).toHaveBeenCalledWith({ queryKey: ['ai-history-storage'] });
});
