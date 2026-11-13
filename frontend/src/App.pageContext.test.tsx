import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { transferableAbortController } from 'node:util';
import type { ReactNode } from 'react';
import { App } from './App';
import { useUiStore } from './stores/uiStore';

const { routers } = vi.hoisted(() => ({ routers: [] as Array<{ dispose: () => void }> }));
vi.mock('react-router-dom', async importOriginal => {
  const actual = await importOriginal<typeof import('react-router-dom')>();
  return { ...actual, createHashRouter: (...args: Parameters<typeof actual.createHashRouter>) => {
    const router = actual.createHashRouter(...args);
    routers.push(router);
    return router;
  } };
});

vi.mock('./pages/NotebookPage', () => ({ NotebookPage: () => <div>notebooks</div> }));
vi.mock('./pages/BankPage', () => ({ BankPage: () => <div>banks</div> }));
vi.mock('./pages/WrongPage', () => ({ WrongPage: () => <div>wrong</div> }));
vi.mock('./pages/KnowledgePointPage', () => ({ KnowledgePointPage: () => <div>knowledge</div> }));
vi.mock('./pages/PracticePage', () => ({ PracticePage: () => <div>practice</div> }));
vi.mock('./pages/SettingsPage', async () => {
  const { useRegisterPageContext } = await import('./hooks/useRegisterPageContext');
  return { SettingsPage: () => {
    useRegisterPageContext({ kind: 'settings', title: '设置 · 关于', route: '/settings?tab=about', markdown: '当前版本 v0.8.1' });
    return <main>settings content</main>;
  } };
});
vi.mock('./pages/CalendarPage', async () => {
  const { useRegisterPageContext } = await import('./hooks/useRegisterPageContext');
  return { CalendarPage: () => {
    useRegisterPageContext({ kind: 'calendar', title: '日历 · 2026-11-14', route: '/calendar?date=2026-11-14', markdown: '周末学习计划' });
    return <main>calendar content</main>;
  } };
});
vi.mock('./hooks/useAssistantBridge', () => ({ useAssistantBridge: () => ({ running: 0, pending: 0 }) }));
vi.mock('./hooks/useGlobalShortcuts', () => ({ useGlobalShortcuts: () => {} }));
vi.mock('./components/app-update/AppUpdateProvider', () => ({
  AppUpdateProvider: ({ children }: { children: ReactNode }) => <>{children}</>, useAppUpdate: () => ({})
}));
vi.mock('./components/app-update/UpdateEntry', () => ({ UpdateEntry: () => null, UpdateStartupView: () => null }));

beforeEach(() => {
  // 数据路由使用 Node 的 Request；让取消信号来自同一实现，避免 jsdom 的跨域类型不匹配。
  vi.stubGlobal('AbortController', class {
    private readonly delegate = transferableAbortController();
    get signal() { return this.delegate.signal; }
    abort(reason?: unknown) { this.delegate.abort(reason); }
  });
  useUiStore.getState().clearPageContext();
});
afterEach(() => {
  cleanup();
  for (const router of routers.splice(0)) router.dispose();
  vi.unstubAllGlobals();
});

describe('主窗口页面登记', () => {
  it.each(['/settings', '/calendar'])('%s 首次打开时，外壳不会清空已登记的页面', async route => {
    window.location.hash = '#' + route;
    render(<App />);
    await waitFor(() => expect(useUiStore.getState().pageContext.kind).toBe(route.slice(1)));
    expect(useUiStore.getState().pageContext.markdown).not.toBe('');
  });

  it('设置与日历连续切换时始终保留新页面快照', async () => {
    window.location.hash = '#/settings';
    render(<App />);
    await waitFor(() => expect(useUiStore.getState().pageContext.kind).toBe('settings'));
    fireEvent.click(screen.getByRole('menuitem', { name: '日历' }));
    await waitFor(() => expect(useUiStore.getState().pageContext.markdown).toBe('周末学习计划'));
    fireEvent.click(screen.getByRole('menuitem', { name: '设置' }));
    await waitFor(() => expect(useUiStore.getState().pageContext.markdown).toBe('当前版本 v0.8.1'));
  });
});
