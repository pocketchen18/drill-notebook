/**
 * 视图状态记忆的 React 胶水：读缓存用 `useViewStateSlice`，写缓存用 `usePersistSlice`。
 *
 * `usePersistSlice` 只在值真正变化时下发补丁（JSON 去重），因此挂载时从缓存恢复
 * 不会产生写回；`useIdSwitchReset` 用来替换「切库即清空」这类 effect —— 它们在挂载
 * 时也会触发一次，会把刚恢复的勾选清掉。
 */
import { useEffect, useRef } from 'react';
import { persistViewState, readPageSlice } from '../lib/viewState';
import type { PageKey, PageStates } from '../lib/viewState';

export function useViewStateSlice<K extends PageKey>(page: K): PageStates[K] {
  return readPageSlice(page);
}

export function usePersistSlice(page: PageKey, values: Record<string, unknown>): void {
  const lastRef = useRef('');
  const serialized = JSON.stringify(values);
  useEffect(() => {
    if (serialized === lastRef.current) return;
    lastRef.current = serialized;
    persistViewState(page, values);
    // eslint-disable-next-line react-hooks/exhaustive-deps -- values 用序列化结果作依赖
  }, [page, serialized]);
}

/** 仅在 id 真正切换（而非首次挂载）时执行 onSwitch。 */
export function useIdSwitchReset<T>(currentId: T, onSwitch: () => void): void {
  const prevRef = useRef<T>(currentId);
  const callbackRef = useRef(onSwitch);
  callbackRef.current = onSwitch;
  useEffect(() => {
    if (prevRef.current === currentId) return;
    prevRef.current = currentId;
    callbackRef.current();
  }, [currentId]);
}
