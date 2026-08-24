import { create } from 'zustand';

/**
 * 知识点排序的本地状态。
 * 真正的列表数据仍以 React Query 缓存为准，这里只保存当前的 id 顺序覆盖层
 * （排序列表已成为主视图，无“模式开关”，始终按此顺序展示与拖拽）。
 */
interface KnowledgeSortState {
  /** 当前题库按新顺序排列的完整 id 数组。 */
  orderedIds: number[];
  setOrderedIds: (ids: number[]) => void;
}

export const useKnowledgeSortStore = create<KnowledgeSortState>((set) => ({
  orderedIds: [],
  setOrderedIds: (orderedIds) => set({ orderedIds })
}));
