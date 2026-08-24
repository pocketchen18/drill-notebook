import { arrayMove } from '@dnd-kit/sortable';

/**
 * 用 @dnd-kit 的 arrayMove 对 id 数组做极简整段重排。
 * activeId/overId 任一不存在或相等时原样返回（纯函数，StrictMode 安全）。
 */
export function reorderIds(ids: number[], activeId: number, overId: number): number[] {
  const oldIndex = ids.indexOf(activeId);
  const newIndex = ids.indexOf(overId);
  if (oldIndex < 0 || newIndex < 0 || oldIndex === newIndex) return ids;
  return arrayMove(ids, oldIndex, newIndex);
}

/**
 * 让本地排序顺序与当前数据源对齐：剔除已不存在的 id，末尾补齐漏掉的 id。
 * 保证拖拽列表与知识点数据一一对应（避免切换题库后残留旧顺序）。
 */
export function alignOrder<T extends { id: number }>(orderedIds: number[], items: T[]): number[] {
  const known = new Set(items.map((item) => item.id));
  const aligned = orderedIds.filter((id) => known.has(id));
  const missing = items.filter((item) => !aligned.includes(item.id)).map((item) => item.id);
  return [...aligned, ...missing];
}
