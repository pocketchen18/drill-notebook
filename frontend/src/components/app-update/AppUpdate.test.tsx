import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { beforeEach, afterEach, describe, expect, it, vi } from 'vitest';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { AppUpdateProvider } from './AppUpdateProvider';
import { AboutPanel } from './AboutPanel';
import { UpdateEntry } from './UpdateEntry';
import { ReleaseNotesDialog, UpdateProgressDialog } from './UpdateDialogs';
import { UpdateLifecycleBridge } from './UpdateLifecycleBridge';
import { registerUpdateParticipant } from '../../lib/updatePreparation';
import type { AppUpdateState, AppUpdaterApi, NotesRange, UpdateRelease, UpdatePreparationRequest } from '../../../../electron/update-types';

const release = (version: string): UpdateRelease => ({ version, tag: `v${version}`, name: `v${version}`, body: `## v${version} 的说明\n\n- 保留完整正文\n\n[项目链接](https://github.com/qilin-software/drill-notebook)`,
  publishedAt: '2026-11-12T00:00:00Z', url: `https://github.com/qilin-software/drill-notebook/releases/tag/v${version}`, prerelease: true,
  asset: { name: `Drill-Notebook-${version}-win-x64-portable.zip`, size: 1024, url: 'https://github.com/qilin-software/drill-notebook/releases/download/x', digest: 'a'.repeat(64) } });
const base = (): AppUpdateState => ({ currentVersion: '0.7.3', development: false, installSupported: true,
  settings: { automaticUpdate: false, automaticCheck: true, intervalHours: 24, startupReminder: true }, checking: false, checkStatus: 'available',
  target: release('0.8.0'), updateVisible: true, startupBusy: false, progress: { phase: 'idle', received: 0, bytesPerSecond: 0 } });
let state: AppUpdateState;
let api: AppUpdaterApi;
let stateListener: (state: AppUpdateState) => void;
let prepareListener: (request: UpdatePreparationRequest) => void;
let endListener: () => void;
const openExternal = vi.fn().mockResolvedValue(undefined);
const emit = (): void => act(() => stateListener?.(structuredClone(state)));
beforeEach(() => {
  state = base(); openExternal.mockClear();
  api = {
    getState: vi.fn(async () => structuredClone(state)),
    setSettings: vi.fn(async patch => { state.settings = { ...state.settings, ...patch }; emit(); return state; }),
    check: vi.fn(async () => state), openAvailable: vi.fn(async () => {}), openHistory: vi.fn(async () => {}),
    closePresentation: vi.fn(async () => {}), skip: vi.fn(async () => {}), restoreReminder: vi.fn(async () => {}),
    install: vi.fn(async () => {}), cancelDownload: vi.fn(async () => {}), resumeInstall: vi.fn(async () => {}),
    notes: vi.fn(async range => ({ range, releases: [release('0.8.0'), release('0.7.3'), release('0.7.2')], cached: false })),
    onState: listener => { stateListener = listener; return () => { stateListener = undefined!; }; },
    onPrepare: listener => { prepareListener = listener; return () => { prepareListener = undefined!; }; },
    onPreparationEnd: listener => { endListener = listener; return () => { endListener = undefined!; }; },
    rendererReady: vi.fn(async () => {}), prepared: vi.fn(async () => {}), readAssistantDrafts: vi.fn(async () => null), saveAssistantDrafts: vi.fn(async () => {}),
  };
  Object.defineProperty(window, 'api', { value: { update: api, shell: { openExternal } }, configurable: true, writable: true });
});
afterEach(() => { Object.defineProperty(window, 'api', { value: undefined, configurable: true, writable: true }); });
const mountAbout = (): ReturnType<typeof render> => render(<AppUpdateProvider><AboutPanel /><UpdateEntry /></AppUpdateProvider>);

describe('关于与更新入口', () => {
  it('展示实际安装版、GitHub 图标、唯一更新内容入口与 Star 按钮', async () => {
    mountAbout(); await screen.findByText('v0.7.3');
    const home = screen.getByRole('button', { name: 'GitHub 主页' }); expect(home.querySelector('svg.lucide-github')).toBeInTheDocument();
    expect(screen.getAllByRole('button', { name: '更新内容' })).toHaveLength(1);
    fireEvent.click(home); expect(openExternal).toHaveBeenCalledWith('https://github.com/qilin-software/drill-notebook');
    fireEvent.click(screen.getByRole('button', { name: '去 GitHub 点 Star' })); expect(openExternal).toHaveBeenCalledTimes(2);
    fireEvent.click(screen.getByRole('button', { name: '检查更新' })); expect(api.check).toHaveBeenCalledTimes(1);
    fireEvent.click(screen.getByRole('button', { name: '更新内容' })); expect(api.openHistory).toHaveBeenCalledTimes(1);
  });
  it('自动更新只保存偏好并禁用启动说明，关闭自动检查保留间隔和提示偏好', async () => {
    mountAbout(); await screen.findByText('v0.7.3');
    fireEvent.click(screen.getByRole('switch', { name: '自动更新' }));
    await waitFor(() => expect(api.setSettings).toHaveBeenCalledWith({ automaticUpdate: true }));
    expect(screen.getByRole('switch', { name: '启动时更新提醒' })).toBeDisabled(); expect(api.install).not.toHaveBeenCalled();
    await waitFor(() => expect(screen.getByRole('switch', { name: '自动检查更新' })).not.toBeDisabled());
    fireEvent.click(screen.getByRole('switch', { name: '自动检查更新' }));
    await waitFor(() => expect(state.settings.automaticCheck).toBe(false));
    expect(state.settings.startupReminder).toBe(true); expect(state.settings.intervalHours).toBe(24);
    expect(screen.getByRole('button', { name: '检查更新' })).not.toBeDisabled();
  });
  it('自定义间隔拒绝小数和越界，不悄悄夹值', async () => {
    state.settings.intervalHours = 7; mountAbout();
    const input = await screen.findByRole('textbox', { name: '自定义间隔小时' });
    for (const value of ['0', '169', '1.5', '']) {
      fireEvent.change(input, { target: { value } }); fireEvent.blur(input);
      expect(screen.getByRole('alert')).toHaveTextContent('1–168'); expect(api.setSettings).not.toHaveBeenCalled();
    }
    fireEvent.change(input, { target: { value: '8' } }); fireEvent.keyDown(input, { key: 'Enter' });
    await waitFor(() => expect(api.setSettings).toHaveBeenCalledWith({ intervalHours: 8 }));
  });
  it('顶部入口跟随同一状态，跳过隐藏，下载中可以重新打开进度', async () => {
    mountAbout(); const entry = await screen.findByRole('button', { name: '发现新版本 v0.8.0，查看更新内容' });
    fireEvent.click(entry); expect(api.openAvailable).toHaveBeenCalledTimes(1);
    state.updateVisible = false; state.skippedVersion = '0.8.0'; emit();
    expect(screen.queryByRole('button', { name: /发现新版本.*查看更新内容/ })).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: '恢复提醒' })); expect(api.restoreReminder).toHaveBeenCalledTimes(1);
    state.progress = { phase: 'downloading', version: '0.8.0', received: 20, total: 100, bytesPerSecond: 10 }; emit();
    fireEvent.click(screen.getByRole('button', { name: '正在更新 v0.8.0，查看进度' })); expect(api.openAvailable).toHaveBeenCalledTimes(2);
  });
});

describe('逐版本更新内容', () => {
  it('关于默认范围只读、未安装标签正确、菜单支持键盘并重新打开恢复默认', async () => {
    const presentation = { id: 1, kind: 'history' as const };
    const view = render(<ReleaseNotesDialog state={state} api={api} presentation={presentation} />);
    await screen.findByText('v0.7.2 的说明');
    expect(screen.getAllByText('尚未安装')).toHaveLength(1); expect(screen.getByText('当前安装')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '立即更新' })).toBeNull(); expect(screen.queryByRole('button', { name: '跳过此版本' })).toBeNull();
    const anchor = screen.getByRole('button', { name: '显示的更新说明：上次更新以来' });
    fireEvent.keyDown(anchor, { key: 'ArrowDown' });
    const first = screen.getByRole('menuitemradio', { name: '上次更新以来' }); await waitFor(() => expect(first).toHaveFocus());
    fireEvent.keyDown(first, { key: 'ArrowDown' }); expect(screen.getByRole('menuitemradio', { name: '当前版本及之前 10 个版本' })).toHaveFocus();
    fireEvent.click(document.activeElement!); await waitFor(() => expect(api.notes).toHaveBeenLastCalledWith(10, undefined, false));
    expect(screen.queryByRole('menu')).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: '关闭' })); expect(api.closePresentation).toHaveBeenCalledWith(1);
    view.unmount(); render(<ReleaseNotesDialog state={state} api={api} presentation={{ id: 2, kind: 'history' }} />);
    await waitFor(() => expect(api.notes).toHaveBeenLastCalledWith('since', undefined, false));
  });
  it('新版说明无历史菜单、只展示服务返回的未安装版本，每版标签无重复，一次点击更新', async () => {
    api.notes = vi.fn(async (range: NotesRange) => ({ range, releases: [release('0.8.0'), release('0.7.4')], cached: false }));
    render(<ReleaseNotesDialog state={state} api={api} presentation={{ id: 3, kind: 'available', version: '0.8.0' }} />);
    await screen.findByText('v0.7.4 的说明');
    expect(screen.getAllByText('尚未安装')).toHaveLength(2); expect(screen.queryByRole('menu')).toBeNull();
    expect(screen.queryByRole('button', { name: /^显示的更新说明/ })).toBeNull(); expect(screen.queryByText('v0.7.3 的说明')).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: '立即更新' })); expect(api.install).toHaveBeenCalledWith('0.8.0');
    fireEvent.click(screen.getByRole('button', { name: '跳过此版本' })); expect(api.skip).toHaveBeenCalledWith('0.8.0');
    const links = screen.getAllByRole('link', { name: '项目链接' }); fireEvent.click(links[0]);
    expect(openExternal).toHaveBeenCalledExactlyOnceWith('https://github.com/qilin-software/drill-notebook');
  });
  it('加载失败保留同范围缓存并允许重试，空正文不虚构说明', async () => {
    api.notes = vi.fn(async range => ({ range, releases: [{ ...release('0.7.3'), body: '' }], cached: true, error: '网络不可用' }));
    render(<ReleaseNotesDialog state={state} api={api} presentation={{ id: 4, kind: 'history' }} />);
    await screen.findByText('此版本未提供更新说明。'); expect(screen.getByRole('alert')).toHaveTextContent('缓存');
    fireEvent.click(screen.getByRole('button', { name: '重试' })); await waitFor(() => expect(api.notes).toHaveBeenLastCalledWith('since', undefined, true));
  });
});

describe('进度和保存握手', () => {
  it('关闭仅隐藏进度、下载阶段才可取消，真实进度可访问', () => {
    state.progress = { phase: 'downloading', version: '0.8.0', received: 50, total: 100, bytesPerSecond: 10 };
    const view = render(<UpdateProgressDialog state={state} api={api} presentation={{ id: 5, kind: 'progress', version: '0.8.0' }} />);
    expect(screen.getByRole('progressbar')).toHaveAttribute('aria-valuenow', '50');
    fireEvent.click(screen.getByRole('button', { name: '取消下载' })); expect(api.cancelDownload).toHaveBeenCalledTimes(1);
    const close = document.querySelector<HTMLElement>('.arco-modal-close-icon')!; fireEvent.click(close); expect(api.closePresentation).toHaveBeenCalledWith(5);
    state.progress.phase = 'verifying'; view.rerender(<UpdateProgressDialog state={state} api={api} presentation={{ id: 5, kind: 'progress' }} />);
    expect(screen.queryByRole('button', { name: '取消下载' })).toBeNull(); expect(screen.queryByRole('button', { name: '重启并更新' })).toBeNull();
  });
  it('两窗口使用相同握手，只在保存成功后回复并在暂停时解锁', async () => {
    const save = vi.fn().mockResolvedValue(undefined); const stop = registerUpdateParticipant('test-bridge', save);
    const client = new QueryClient();
    const view = render(<QueryClientProvider client={client}><UpdateLifecycleBridge /></QueryClientProvider>);
    expect(api.rendererReady).toHaveBeenCalledTimes(1);
    act(() => prepareListener({ id: 'preparation-1' }));
    await waitFor(() => expect(api.prepared).toHaveBeenCalledWith({ id: 'preparation-1', ok: true }));
    expect(save).toHaveBeenCalledTimes(1); expect(document.documentElement).toHaveAttribute('data-update-preparing', 'true');
    act(() => endListener()); expect(document.documentElement).not.toHaveAttribute('data-update-preparing');
    view.unmount(); stop();
  });
});
