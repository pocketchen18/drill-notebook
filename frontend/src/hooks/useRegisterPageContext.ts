import { useEffect, useMemo, useRef } from 'react';
import { useUiStore, type AiPageContext } from '../stores/uiStore';

function contextKey(context: AiPageContext): string {
  return [
    context.kind,
    context.title,
    context.markdown,
    context.route ?? '',
    context.notePageId ?? '',
    context.notebookId ?? '',
    context.questionId ?? ''
  ].join('\u0001');
}

/**
 * Register AI page context without infinite re-render loops.
 * Only calls the store when the serialized context actually changes.
 */
export function useRegisterPageContext(context: AiPageContext): void {
  const lastKey = useRef<string>('');
  const key = useMemo(() => contextKey(context), [
    context.kind,
    context.title,
    context.markdown,
    context.route,
    context.notePageId,
    context.notebookId,
    context.questionId
  ]);

  useEffect(() => {
    if (lastKey.current === key) return;
    lastKey.current = key;
    useUiStore.getState().setPageContext(context);
  }, [context, key]);
}
