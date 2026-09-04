/**
 * 设置页「常规」分区（SET-*）：主题三选一、AI 悬浮球开关、快捷键按作用域分组展示、
 * 多绑定录入 / 移除 / 清空、冲突拒绝（全局 vs 全局、全局 vs 页面、同页面不同阶段放行）、
 * 单项与全部恢复默认、单键规则按作用域区分。其余分区的重组件全部替身。
 */
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, within } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { Message } from '@arco-design/web-react';
import { useUiStore } from '../stores/uiStore';
import { LS_SHORTCUTS, defaultShortcutConfig, readShortcutConfig } from '../lib/shortcuts';
import { LS_SHOW_AI_FAB } from '../lib/sessionPrefs';

vi.mock('../lib/review', () => ({
  listConfigs: vi.fn(() => Promise.resolve([])),
  createConfig: vi.fn(),
  updateConfig: vi.fn(),
  deleteConfig: vi.fn()
}));
vi.mock('../components/AiModelSlotCard', () => ({ AiModelSlotCard: () => <div>stub:ai-slot</div> }));
vi.mock('../components/EmbeddingSettingsCard', () => ({ EmbeddingSettingsCard: () => <div>stub:embedding</div> }));
vi.mock('../components/DataManagementPanel', () => ({ DataManagementPanel: () => <div>stub:data</div> }));

// Import after vi.mock so the stubs are bound.
import { SettingsPage } from './SettingsPage';

function renderPage(): void {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={['/settings']}>
        <SettingsPage />
      </MemoryRouter>
    </QueryClientProvider>
  );
}

function shortcutRow(label: string): HTMLElement {
  const row = screen.getByText(label).closest('.settings-row');
  if (!row) throw new Error(`settings-row not found for ${label}`);
  return row as HTMLElement;
}

function chipsIn(row: HTMLElement): Array<string | null> {
  return Array.from(row.querySelectorAll('.shortcut-key')).map((el) => el.textContent);
}

function addButton(row: HTMLElement): HTMLElement {
  return within(row).getByRole('button', { name: /录入/ });
}

function press(key: string, init?: KeyboardEventInit): void {
  fireEvent.keyDown(window, { key, ...init });
}

beforeEach(() => {
  // store 为模块级单例，逐条用例复位到出厂状态
  useUiStore.setState({ aiOpen: false, aiFabVisible: true, shortcutConfig: defaultShortcutConfig() });
  useUiStore.getState().setThemeMode('light');
  window.location.hash = '';
  vi.restoreAllMocks();
});

describe('SettingsPage 常规分区', () => {
  it('SET-1: renders theme choices, scope groups and default bindings', () => {
    renderPage();
    expect(screen.getByText('浅色')).toBeInTheDocument();
    expect(screen.getByText('深色')).toBeInTheDocument();
    expect(screen.getByText('跟随系统')).toBeInTheDocument();
    expect(document.querySelectorAll('[data-scope]')).toHaveLength(7);
    expect(screen.getByText('全局 · 任意页面生效。')).toBeInTheDocument();
    expect(screen.getByText('知识卡片全屏 · 知识点全屏阅读时生效。')).toBeInTheDocument();
    expect(screen.getByText('笔记本 · 笔记页列表项选中时生效。')).toBeInTheDocument();
    expect(chipsIn(shortcutRow('打开 / 关闭 AI 助手'))).toEqual(['Ctrl', 'J']);
    expect(chipsIn(shortcutRow('切换深浅主题'))).toEqual(['未设置']);
    expect(chipsIn(shortcutRow('提交答案'))).toEqual(['Enter', 'Ctrl', 'S']);
    expect(chipsIn(shortcutRow('上一题'))).toEqual(['←', 'PgUp', 'P']);
    expect(chipsIn(shortcutRow('退出全屏'))).toEqual(['Esc']);
    expect(chipsIn(shortcutRow('重命名题库'))).toEqual(['F2']);
    // 题库与笔记本是不同作用域，F2 可以并存不冲突
    expect(chipsIn(shortcutRow('重命名笔记页'))).toEqual(['F2']);
    expect(screen.getByRole('button', { name: '全部恢复默认' })).toBeDisabled();
    expect(screen.queryByRole('button', { name: /^恢复默认：/ })).toBeNull();
  });

  it('SET-2: choosing 跟随系统 switches theme mode and persists it', () => {
    renderPage();
    fireEvent.click(screen.getByRole('radio', { name: '跟随系统' }));
    expect(useUiStore.getState().themeMode).toBe('system');
    expect(localStorage.getItem('ui.themeMode')).toBe('system');
    fireEvent.click(screen.getByRole('radio', { name: '深色' }));
    expect(useUiStore.getState().themeMode).toBe('dark');
    expect(useUiStore.getState().theme).toBe('dark');
  });

  it('SET-3: hiding the AI fab persists the preference', () => {
    renderPage();
    const toggle = within(shortcutRow('显示 AI 悬浮球')).getByRole('switch');
    expect(toggle).toHaveAttribute('aria-checked', 'true');
    fireEvent.click(toggle);
    expect(useUiStore.getState().aiFabVisible).toBe(false);
    expect(localStorage.getItem(LS_SHOW_AI_FAB)).toBe('false');
  });

  it('SET-4: recording appends bindings, persists them and enables reset controls', () => {
    renderPage();
    const row = shortcutRow('切换深浅主题');
    fireEvent.click(addButton(row));
    expect(within(row).getByText('按下按键…')).toBeInTheDocument();
    press('l', { ctrlKey: true, shiftKey: true });
    expect(chipsIn(row)).toEqual(['Ctrl', 'Shift', 'L']);
    expect(useUiStore.getState().shortcutConfig.toggleTheme).toEqual(['Ctrl+Shift+L']);
    expect(readShortcutConfig().toggleTheme).toEqual(['Ctrl+Shift+L']);
    expect(localStorage.getItem(LS_SHORTCUTS)).toContain('Ctrl+Shift+L');
    expect(screen.getByRole('button', { name: '全部恢复默认' })).toBeEnabled();
    expect(within(row).getByRole('button', { name: '恢复默认：切换深浅主题' })).toBeInTheDocument();
    // 再录一个：追加而不是替换
    fireEvent.click(addButton(row));
    press('F9');
    expect(chipsIn(row)).toEqual(['Ctrl', 'Shift', 'L', 'F9']);
    expect(useUiStore.getState().shortcutConfig.toggleTheme).toEqual(['Ctrl+Shift+L', 'F9']);
  });

  it('SET-5: a combo already used by another global action is rejected', () => {
    // 只记录调用，保留真实实现：不依赖 Arco Message 的返回类型
    const warn = vi.spyOn(Message, 'warning');
    renderPage();
    const row = shortcutRow('切换深浅主题');
    fireEvent.click(addButton(row));
    press('j', { ctrlKey: true });
    // 绑定保持不变，并给出冲突提示
    expect(chipsIn(row)).toEqual(['未设置']);
    expect(useUiStore.getState().shortcutConfig.toggleTheme).toEqual([]);
    expect(useUiStore.getState().shortcutConfig.toggleAi).toEqual(['Ctrl+J']);
    expect(warn).toHaveBeenCalledWith(expect.stringContaining('已用于「打开 / 关闭 AI 助手」'));
  });

  it('SET-6: global bindings are off-limits for page actions, but different phases of one page may share a key', () => {
    const warn = vi.spyOn(Message, 'warning');
    renderPage();
    const searchRow = shortcutRow('搜索大纲与正文');
    fireEvent.click(addButton(searchRow));
    press('j', { ctrlKey: true });
    expect(warn).toHaveBeenCalledWith(expect.stringContaining('已用于「打开 / 关闭 AI 助手」'));
    expect(chipsIn(searchRow)).toEqual(['Ctrl', 'F']);
    // 「提交答案」(作答中) 已有 Ctrl+S，「下一题」(已提交) 仍可绑定同一键
    const nextRow = shortcutRow('下一题');
    fireEvent.click(addButton(nextRow));
    press('s', { ctrlKey: true });
    expect(chipsIn(nextRow)).toEqual(['Enter', '→', 'PgDn', 'N', 'Ctrl', 'S']);
    expect(useUiStore.getState().shortcutConfig.quizNext).toContain('Ctrl+S');
  });

  it('SET-7: × removes one binding and the per-row reset restores defaults', () => {
    renderPage();
    const row = shortcutRow('上一题');
    fireEvent.click(within(row).getByRole('button', { name: '移除 P' }));
    expect(chipsIn(row)).toEqual(['←', 'PgUp']);
    expect(useUiStore.getState().shortcutConfig.quizPrev).toEqual(['ArrowLeft', 'PageUp']);
    fireEvent.click(within(row).getByRole('button', { name: '恢复默认：上一题' }));
    expect(chipsIn(row)).toEqual(['←', 'PgUp', 'P']);
    expect(within(row).queryByRole('button', { name: /^恢复默认：/ })).toBeNull();
    expect(readShortcutConfig()).toEqual(defaultShortcutConfig());
  });

  it('SET-8: Backspace clears a row and 全部恢复默认 restores everything', () => {
    renderPage();
    const aiRow = shortcutRow('打开 / 关闭 AI 助手');
    fireEvent.click(addButton(aiRow));
    press('Backspace');
    expect(chipsIn(aiRow)).toEqual(['未设置']);
    expect(useUiStore.getState().shortcutConfig.toggleAi).toEqual([]);
    fireEvent.click(screen.getByRole('button', { name: '全部恢复默认' }));
    expect(chipsIn(aiRow)).toEqual(['Ctrl', 'J']);
    expect(readShortcutConfig()).toEqual(defaultShortcutConfig());
  });

  it('SET-9: only one recorder is active at a time', () => {
    renderPage();
    const themeRow = shortcutRow('切换深浅主题');
    const settingsRow = shortcutRow('打开设置');
    fireEvent.click(addButton(themeRow));
    expect(within(themeRow).getByText('按下按键…')).toBeInTheDocument();
    // 另一个录制器点击后接管录制态，前一个退出
    fireEvent.click(addButton(settingsRow));
    expect(within(settingsRow).getByText('按下按键…')).toBeInTheDocument();
    expect(within(themeRow).queryByText('按下按键…')).toBeNull();
    press('F6');
    expect(useUiStore.getState().shortcutConfig.openSettings).toEqual(['F6']);
    expect(useUiStore.getState().shortcutConfig.toggleTheme).toEqual([]);
  });

  it('SET-10: single keys are rejected for global actions but accepted for page actions', () => {
    const warn = vi.spyOn(Message, 'warning');
    renderPage();
    const settingsRow = shortcutRow('打开设置');
    fireEvent.click(addButton(settingsRow));
    press('a');
    expect(warn).toHaveBeenCalledWith(expect.stringContaining('Ctrl 或 Alt'));
    expect(chipsIn(settingsRow)).toEqual(['未设置']);
    const outlineRow = shortcutRow('折叠 / 展开大纲');
    fireEvent.click(addButton(outlineRow));
    press('o');
    expect(chipsIn(outlineRow)).toEqual(['T', 'O']);
    expect(useUiStore.getState().shortcutConfig.kcToggleOutline).toEqual(['T', 'O']);
  });

  it('SET-11: AI 发送 only accepts Enter combos', () => {
    const warn = vi.spyOn(Message, 'warning');
    renderPage();
    const sendRow = shortcutRow('发送消息');
    fireEvent.click(addButton(sendRow));
    press('k', { ctrlKey: true });
    expect(warn).toHaveBeenCalledWith(expect.stringContaining('Enter'));
    expect(chipsIn(sendRow)).toEqual(['Enter']);
    fireEvent.click(addButton(sendRow));
    press('Enter', { ctrlKey: true });
    expect(chipsIn(sendRow)).toEqual(['Enter', 'Ctrl', 'Enter']);
    expect(useUiStore.getState().shortcutConfig.aiSend).toEqual(['Enter', 'Ctrl+Enter']);
  });
});
