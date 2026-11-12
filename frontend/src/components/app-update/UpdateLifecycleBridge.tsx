import { useEffect, useRef, useState } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { prepareForRestart } from '../../lib/updatePreparation';

/** Shared by both renderers; it subscribes to the one main-process transaction and owns no update timer. */
export function UpdateLifecycleBridge(): JSX.Element | null {
  const client = useQueryClient();
  const [locked, setLocked] = useState(false);
  const active = useRef<string>();
  const shield = useRef<HTMLDivElement>(null);
  useEffect(() => {
    const api = window.api?.update;
    if (!api) return;
    const stop = api.onPrepare(request => {
      active.current = request.id;
      document.documentElement.dataset.updatePreparing = 'true';
      setLocked(true);
      void prepareForRestart(() => client.isMutating()).then(async () => {
        if (active.current === request.id) await api.prepared({ id: request.id, ok: true });
      }, async error => {
        if (active.current === request.id) await api.prepared({ id: request.id, ok: false,
          reason: error instanceof Error ? error.message : '保存未完成，请先完成当前操作。' });
      }).catch(() => undefined);
    });
    const release = api.onPreparationEnd(() => {
      active.current = undefined;
      delete document.documentElement.dataset.updatePreparing;
      setLocked(false);
    });
    const keyboard = (event: KeyboardEvent): void => {
      if (!active.current || (event.target instanceof Element && event.target.closest('[data-app-update], .app-update-dialog'))) return;
      event.preventDefault(); event.stopImmediatePropagation();
    };
    window.addEventListener('keydown', keyboard, true);
    void api.rendererReady().catch(() => undefined);
    return () => { stop(); release(); window.removeEventListener('keydown', keyboard, true); delete document.documentElement.dataset.updatePreparing; };
  }, [client]);
  useEffect(() => { if (locked && !document.querySelector('[data-app-update]')) shield.current?.focus(); }, [locked]);
  return locked ? <div ref={shield} tabIndex={-1} className="update-save-shield" role="status" aria-live="polite">
    <span>正在保存当前工作，完成后将自动更新并重启…</span>
  </div> : null;
}
