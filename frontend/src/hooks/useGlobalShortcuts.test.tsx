/**
 * 全局快捷键监听（GS-*）：默认绑定触发、自定义改绑即时生效、多绑定并存、
 * 未绑定动作不触发、页面内动作不由全局监听处理、录制期间让路、openSettings 跳转。
 */
import { beforeEach, describe, expect, it } from 'vitest';
import { fireEvent, render } from '@testing-library/react';
import { MemoryRouter, useLocation } from 'react-router-dom';
import { useGlobalShortcuts } from './useGlobalShortcuts';
import { defaultShortcutConfig, setShortcutRecording } from '../lib/shortcuts';
import { useUiStore } from '../stores/uiStore';

let currentPath = '';

function Probe(): null {
  useGlobalShortcuts();
  currentPath = useLocation().pathname;
  return null;
}

function renderWithRouter(): void {
  currentPath = '';
  render(
    <MemoryRouter initialEntries={['/notebooks']}>
      <Probe />
    </MemoryRouter>
  );
}

function press(key: string, init?: KeyboardEventInit): void {
  fireEvent.keyDown(window, { key, ...init });
}

// store 是模块级单例：上一条用例翻转过的 aiOpen / 主题 / 绑定会残留，
// 每条用例前显式复位，保证断言只反映本用例的按键。
beforeEach(() => {
  useUiStore.setState({ aiOpen: false, shortcutConfig: defaultShortcutConfig() });
  useUiStore.getState().setThemeMode('light');
  setShortcutRecording(false);
});

describe('useGlobalShortcuts', () => {
  it('GS-1: default Ctrl+J toggles the AI drawer', () => {
    renderWithRouter();
    expect(useUiStore.getState().aiOpen).toBe(false);
    press('j', { ctrlKey: true });
    expect(useUiStore.getState().aiOpen).toBe(true);
    press('j', { ctrlKey: true });
    expect(useUiStore.getState().aiOpen).toBe(false);
  });

  it('GS-2: picks up a rebinding immediately without remount', () => {
    renderWithRouter();
    useUiStore.getState().setShortcutConfig({ ...defaultShortcutConfig(), toggleAi: ['Alt+K'] });
    press('j', { ctrlKey: true });
    expect(useUiStore.getState().aiOpen).toBe(false);
    press('k', { altKey: true });
    expect(useUiStore.getState().aiOpen).toBe(true);
  });

  it('GS-3: every binding of an action works', () => {
    renderWithRouter();
    useUiStore.getState().setShortcutConfig({ ...defaultShortcutConfig(), toggleAi: ['Ctrl+J', 'F9'] });
    press('F9');
    expect(useUiStore.getState().aiOpen).toBe(true);
    press('j', { ctrlKey: true });
    expect(useUiStore.getState().aiOpen).toBe(false);
  });

  it('GS-4: unbound actions and unrelated keys do not trigger anything', () => {
    renderWithRouter();
    useUiStore.getState().setShortcutConfig({ ...defaultShortcutConfig(), toggleAi: [] });
    const themeBefore = useUiStore.getState().theme;
    press('t', { altKey: true });
    press('d', { ctrlKey: true, shiftKey: true });
    press('j', { ctrlKey: true });
    expect(useUiStore.getState().theme).toBe(themeBefore);
    expect(useUiStore.getState().aiOpen).toBe(false);
  });

  it('GS-5: page-scoped bindings are not handled by the global listener', () => {
    renderWithRouter();
    // 默认的刷题 / 知识卡片键：Enter、方向键、T、Ctrl+F 都不该改变全局状态
    press('Enter');
    press('ArrowLeft');
    press('t');
    press('f', { ctrlKey: true });
    expect(useUiStore.getState().aiOpen).toBe(false);
    expect(useUiStore.getState().theme).toBe('light');
    expect(currentPath).toBe('/notebooks');
  });

  it('GS-6: toggleTheme binding flips the theme', () => {
    renderWithRouter();
    useUiStore.getState().setShortcutConfig({ ...defaultShortcutConfig(), toggleTheme: ['Ctrl+Shift+L'] });
    press('l', { ctrlKey: true, shiftKey: true });
    expect(useUiStore.getState().theme).toBe('dark');
    expect(useUiStore.getState().themeMode).toBe('dark');
  });

  it('GS-7: openSettings binding navigates to the settings page', () => {
    renderWithRouter();
    useUiStore.getState().setShortcutConfig({ ...defaultShortcutConfig(), openSettings: ['F6'] });
    press('F6');
    expect(currentPath).toBe('/settings');
  });

  it('GS-8: stays quiet while a shortcut is being recorded', () => {
    renderWithRouter();
    setShortcutRecording(true);
    press('j', { ctrlKey: true });
    expect(useUiStore.getState().aiOpen).toBe(false);
    setShortcutRecording(false);
    press('j', { ctrlKey: true });
    expect(useUiStore.getState().aiOpen).toBe(true);
  });
});
