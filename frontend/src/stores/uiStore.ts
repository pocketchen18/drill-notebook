import { create } from 'zustand';
import { normalizeShortcutConfig, readShortcutConfig, writeShortcutConfig, type ShortcutConfig } from '../lib/shortcuts';
import { readBoolPref, writeBoolPref } from '../lib/sessionPrefs';

type Theme = 'light' | 'dark';
/** 主题偏好：system = 跟随系统深浅色，其余为显式指定。 */
export type ThemeMode = 'light' | 'dark' | 'system';
export type OutlineSide = 'left' | 'right';

const LS_THEME_MODE = 'ui.themeMode';
const LS_OUTLINE_SIDE = 'ui.outlineSide';
export const LS_NOTEBOOK_PANELS_SWAPPED = 'ui.notebookPanelsSwapped';

export type AiContextKind = 'none' | 'quiz' | 'wrong' | 'note' | 'knowledge' | 'bank' | 'manual' | 'settings' | 'calendar';

export interface AiPageContext {
  kind: AiContextKind;
  title: string;
  markdown: string;
  route?: string;
  notePageId?: number;
  notebookId?: number;
  questionId?: number;
  bankId?: number;
  pointId?: number;
  selectedIds?: number[];
  selection?: string;
  isDraft?: boolean;
  truncated?: boolean;
}

interface UiState {
  theme: Theme;
  themeMode: ThemeMode;
  setTheme: (theme: Theme) => void;
  setThemeMode: (mode: ThemeMode) => void;
  toggleTheme: () => void;
  shortcutConfig: ShortcutConfig;
  setShortcutConfig: (config: ShortcutConfig) => void;
  aiOpen: boolean;
  setAiOpen: (open: boolean) => void;
  toggleAi: () => void;
  pageContext: AiPageContext;
  setPageContext: (context: AiPageContext) => void;
  clearPageContext: () => void;
  notebookFocusMode: boolean;
  setNotebookFocusMode: (focus: boolean) => void;
  outlineSide: OutlineSide;
  setOutlineSide: (side: OutlineSide) => void;
  notebookPanelsSwapped: boolean;
  setNotebookPanelsSwapped: (swapped: boolean) => void;
}

/**
 * 助手是独立窗口：主窗口里「打开 / 关闭」要转成对那个窗口的操作，助手窗口里「关闭」只是收起自己。
 * 由各窗口的根组件登记；未登记（单元测试）时 aiOpen 只是普通状态。返回值是应写入的 aiOpen。
 */
export type AssistantRequest = 'open' | 'close' | 'toggle';
let assistantLauncher: ((request: AssistantRequest, current: boolean) => boolean) | null = null;
export function setAssistantLauncher(launcher: ((request: AssistantRequest, current: boolean) => boolean) | null): void {
  assistantLauncher = launcher;
}
const launch = (request: AssistantRequest, current: boolean): boolean => assistantLauncher
  ? assistantLauncher(request, current) : request === 'toggle' ? !current : request === 'open';

const emptyContext: AiPageContext = { kind: 'none', title: '无页面上下文', markdown: '' };

/** 系统当前偏好深色时返回 dark（jsdom / 无 matchMedia 环境回落 light）。 */
export function resolveSystemTheme(): Theme {
  try {
    return window.matchMedia?.('(prefers-color-scheme: dark)').matches ? 'dark' : 'light';
  } catch {
    return 'light';
  }
}

function readThemeMode(): ThemeMode {
  try {
    const raw = localStorage.getItem(LS_THEME_MODE);
    if (raw === 'light' || raw === 'dark' || raw === 'system') return raw;
    // 旧版本只存生效主题（drill-notebook-theme），迁移为显式模式，避免单选与实际明暗不一致
    const legacy = localStorage.getItem('drill-notebook-theme');
    if (legacy === 'light' || legacy === 'dark') return legacy;
  } catch {
    /* ignore */
  }
  return 'light';
}

function writeThemeMode(mode: ThemeMode): void {
  try {
    localStorage.setItem(LS_THEME_MODE, mode);
  } catch {
    /* ignore */
  }
}

function resolveTheme(mode: ThemeMode): Theme {
  return mode === 'system' ? resolveSystemTheme() : mode;
}

function readOutlineSide(): OutlineSide {
  try {
    return localStorage.getItem(LS_OUTLINE_SIDE) === 'right' ? 'right' : 'left';
  } catch {
    return 'left';
  }
}

function writeOutlineSide(side: OutlineSide): void {
  try {
    localStorage.setItem(LS_OUTLINE_SIDE, side);
  } catch {
    /* ignore */
  }
}

const initialThemeMode = readThemeMode();

export const useUiStore = create<UiState>((set) => ({
  theme: resolveTheme(initialThemeMode),
  themeMode: initialThemeMode,
  // 仅设置生效主题（跟随系统模式下系统偏好变化时使用），不改变偏好本身
  setTheme: (theme) => set({ theme }),
  setThemeMode: (mode) => {
    writeThemeMode(mode);
    set({ themeMode: mode, theme: resolveTheme(mode) });
  },
  // 手动切换 = 显式选择反色主题，覆盖「跟随系统」
  toggleTheme: () => set((state) => {
    const theme = state.theme === 'light' ? 'dark' : 'light';
    writeThemeMode(theme);
    return { theme, themeMode: theme };
  }),
  shortcutConfig: readShortcutConfig(),
  setShortcutConfig: (config) => {
    const normalized = normalizeShortcutConfig(config);
    writeShortcutConfig(normalized);
    set({ shortcutConfig: normalized });
  },
  aiOpen: false,
  setAiOpen: (aiOpen) => set((state) => ({ aiOpen: launch(aiOpen ? 'open' : 'close', state.aiOpen) })),
  toggleAi: () => set((state) => ({ aiOpen: launch('toggle', state.aiOpen) })),
  pageContext: emptyContext,
  setPageContext: (pageContext) => set((state) => {
    const prev = state.pageContext;
    if (
      prev.kind === pageContext.kind
      && prev.title === pageContext.title
      && prev.markdown === pageContext.markdown
      && prev.route === pageContext.route
      && prev.notePageId === pageContext.notePageId
      && prev.notebookId === pageContext.notebookId
      && prev.questionId === pageContext.questionId
      && prev.bankId === pageContext.bankId
      && prev.pointId === pageContext.pointId
      && prev.selection === pageContext.selection
      && prev.isDraft === pageContext.isDraft
      && prev.truncated === pageContext.truncated
      && JSON.stringify(prev.selectedIds) === JSON.stringify(pageContext.selectedIds)
    ) {
      return state;
    }
    return { pageContext };
  }),
  clearPageContext: () => set((state) => (state.pageContext.kind === 'none' ? state : { pageContext: emptyContext })),
  notebookFocusMode: false,
  setNotebookFocusMode: (focus) => set({ notebookFocusMode: focus }),
  outlineSide: readOutlineSide(),
  setOutlineSide: (side) => {
    writeOutlineSide(side);
    set({ outlineSide: side });
  },
  notebookPanelsSwapped: readBoolPref(LS_NOTEBOOK_PANELS_SWAPPED, false),
  setNotebookPanelsSwapped: (swapped) => {
    writeBoolPref(LS_NOTEBOOK_PANELS_SWAPPED, swapped);
    set({ notebookPanelsSwapped: swapped });
  }
}));
