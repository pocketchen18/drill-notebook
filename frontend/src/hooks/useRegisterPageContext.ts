import { useEffect, useRef } from 'react';
import { useUiStore, type AiPageContext } from '../stores/uiStore';
import { businessSelection } from '../lib/pageSnapshot';

function contextKey(context: AiPageContext): string {
  return JSON.stringify(context);
}

/**
 * Register AI page context without infinite re-render loops.
 * Only calls the store when the serialized context actually changes.
 */
export function useRegisterPageContext(context: AiPageContext, enabled = true): void {
  const live = useRef(context);
  live.current = context;
  const key = contextKey(context);
  useEffect(() => {
    if (!enabled) return;
    let owned: AiPageContext = { ...live.current };
    useUiStore.getState().setPageContext(owned);
    owned = useUiStore.getState().pageContext;
    const onSelection = (): void => {
      // 助手在独立窗口里，它的选区不会出现在本文档；businessSelection 只认业务页面内的选区。
      if (useUiStore.getState().pageContext !== owned) return;
      const selection = businessSelection(live.current.markdown);
      useUiStore.getState().setPageContext({ ...live.current, ...(selection ? { selection } : {}) });
      owned = useUiStore.getState().pageContext;
    };
    document.addEventListener('selectionchange', onSelection);
    return () => {
      document.removeEventListener('selectionchange', onSelection);
      // 不能清掉下一页面已经注册的内容。
      if (useUiStore.getState().pageContext === owned) useUiStore.getState().clearPageContext();
    };
  }, [key, enabled]);
}
