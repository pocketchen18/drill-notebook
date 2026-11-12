import { createContext, useContext, useEffect, useState, type ReactNode } from 'react';
import { Message } from '@arco-design/web-react';
import type { AppUpdateState, AppUpdaterApi } from '../../../../electron/update-types';
import { ReleaseNotesDialog, UpdateProgressDialog } from './UpdateDialogs';
import './app-update.css';

interface UpdateContextValue { api?: AppUpdaterApi; state?: AppUpdateState; error?: string; }
const UpdateContext = createContext<UpdateContextValue>({});
export const useAppUpdate = (): UpdateContextValue => useContext(UpdateContext);
export function updateAction(task: Promise<unknown> | undefined): void {
  void task?.catch(error => Message.error(error instanceof Error ? error.message : '更新操作未完成，请重试。'));
}

export function AppUpdateProvider({ children }: { children: ReactNode }): JSX.Element {
  const api = window.api?.update;
  const [state, setState] = useState<AppUpdateState>();
  const [error, setError] = useState<string>();
  useEffect(() => {
    if (!api) return;
    let live = true;
    let received = false;
    const stop = api.onState(next => { received = true; if (live) { setState(next); setError(undefined); } });
    void api.getState().then(next => { if (live && !received) setState(next); }, error => {
      if (live) setError(error instanceof Error ? error.message : '暂时无法读取更新状态。');
    });
    return () => { live = false; stop(); };
  }, [api]);
  const presentation = state?.presentation;
  return <UpdateContext.Provider value={{ api, state, error }}>
    {children}
    {api && state && presentation && (presentation.kind === 'progress'
      ? <UpdateProgressDialog key={presentation.id} state={state} api={api} presentation={presentation} />
      : <ReleaseNotesDialog key={presentation.id} state={state} api={api} presentation={presentation} />)}
  </UpdateContext.Provider>;
}
