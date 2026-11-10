export interface MaterialDeletion { type: 'note' | 'notebook'; id: number }
const listeners = new Set<(event: MaterialDeletion) => void>();
/** 同步先丢弃草稿，再更新查询缓存，避免重渲染触发已删除页面的自动保存。 */
export function notifyMaterialDeleted(event: MaterialDeletion): void {
  for (const listener of listeners) listener(event);
}
export function onMaterialDeleted(listener: (event: MaterialDeletion) => void): () => void {
  listeners.add(listener);
  return () => { listeners.delete(listener); };
}
