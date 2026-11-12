import { useEffect } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { createHashRouter, RouterProvider } from 'react-router-dom';
import { useState } from 'react';
import { AiAssistant } from './components/AiAssistant';
import { resolveSystemTheme, setAssistantLauncher, useUiStore } from './stores/uiStore';
import { hideAssistantWindow, publish, subscribe } from './lib/windowBus';
import { isShortcutRecording, LS_SHORTCUTS, matchesAny, readShortcutConfig } from './lib/shortcuts';

/** 这些设置在主窗口修改后，助手窗口要立即看到。 */
const SHARED_QUERY_KEYS = ['ai-config', 'ai-connections', 'ai-tool-settings', 'ai-runtime-settings', 'ai-memory-settings',
  'ai-memory-status', 'notebooks'];

/**
 * 助手窗口的根：没有业务页面，只承载对话。主题、快捷键与「当前页面」都来自主窗口，
 * 这里不注册页面上下文，也不改动主窗口的任何偏好。
 */
function AssistantShell(): JSX.Element {
  const queryClient = useQueryClient();
  const theme = useUiStore((state) => state.theme);

  useEffect(() => {
    useUiStore.setState({ aiOpen: true });
    // 助手窗口里「关闭助手」只是收起自己；状态保持打开，任务与查询继续。
    setAssistantLauncher((request) => { if (request !== 'open') hideAssistantWindow(); return true; });
    return () => setAssistantLauncher(null);
  }, []);

  useEffect(() => {
    document.title = 'AI 助手';
    document.documentElement.dataset.theme = theme;
    if (theme === 'dark') document.body.setAttribute('arco-theme', 'dark');
    else document.body.removeAttribute('arco-theme');
  }, [theme]);

  // 主题与快捷键偏好保存在两个窗口共用的本地存储里，主窗口一改这里就跟上。
  useEffect(() => {
    const onStorage = (event: StorageEvent): void => {
      if (event.key === 'ui.themeMode' || event.key === 'drill-notebook-theme') {
        const mode = localStorage.getItem('ui.themeMode');
        const saved = localStorage.getItem('drill-notebook-theme');
        const next = mode === 'system' ? resolveSystemTheme() : mode === 'dark' || mode === 'light' ? mode : saved;
        if (next === 'dark' || next === 'light') useUiStore.getState().setTheme(next);
      } else if (event.key === LS_SHORTCUTS) {
        useUiStore.setState({ shortcutConfig: readShortcutConfig() });
      }
    };
    const media = window.matchMedia?.('(prefers-color-scheme: dark)');
    const onSystem = (): void => { if (localStorage.getItem('ui.themeMode') === 'system') useUiStore.getState().setTheme(resolveSystemTheme()); };
    window.addEventListener('storage', onStorage);
    media?.addEventListener?.('change', onSystem);
    return () => { window.removeEventListener('storage', onStorage); media?.removeEventListener?.('change', onSystem); };
  }, []);

  // 「当前页面」始终是主窗口的业务页面：打开时要一份，之后跟随主窗口的变化。
  useEffect(() => {
    const off = subscribe((message) => {
      if (message.type === 'page-context') useUiStore.getState().setPageContext(message.context);
      else if (message.type === 'invalidate') for (const queryKey of message.keys) void queryClient.invalidateQueries({ queryKey });
    });
    publish({ type: 'context-request' });
    return off;
  }, [queryClient]);

  // 窗口重新可见或获得焦点时，补上收起期间主窗口改过的设置。
  useEffect(() => {
    const refresh = (): void => {
      if (document.visibilityState !== 'visible') return;
      for (const key of SHARED_QUERY_KEYS) void queryClient.invalidateQueries({ queryKey: [key] });
      publish({ type: 'context-request' });
    };
    window.addEventListener('focus', refresh);
    document.addEventListener('visibilitychange', refresh);
    return () => { window.removeEventListener('focus', refresh); document.removeEventListener('visibilitychange', refresh); };
  }, [queryClient]);

  // 打开 / 关闭助手的全局快捷键在这个窗口里表示收起。
  useEffect(() => {
    const onKey = (event: KeyboardEvent): void => {
      if (isShortcutRecording() || !matchesAny(event, useUiStore.getState().shortcutConfig.toggleAi)) return;
      event.preventDefault();
      hideAssistantWindow();
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, []);

  return <AiAssistant />;
}

export function AssistantApp(): JSX.Element {
  const [router] = useState(() => createHashRouter([{ path: '*', element: <AssistantShell /> }]));
  return <RouterProvider router={router} />;
}
