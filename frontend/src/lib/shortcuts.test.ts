/**
 * 快捷键纯逻辑（SHORTCUT-*）：组合键解析 / 归一化（全局规则与页面规则）、事件匹配、
 * 动作解析、展示标签、配置存取与冲突检测（作用域 / 阶段）、录制中标志。
 */
import { afterEach, describe, expect, it } from 'vitest';
import {
  LS_SHORTCUTS,
  SHORTCUT_ACTIONS,
  acceleratorFromEvent,
  defaultShortcutConfig,
  describeAccelerator,
  describeAccelerators,
  findShortcutConflicts,
  isActionDefault,
  isDefaultShortcutConfig,
  isModifierKey,
  isShortcutRecording,
  matchesAny,
  matchesShortcut,
  normalizeAccelerator,
  normalizeShortcutConfig,
  readShortcutConfig,
  resolveShortcutAction,
  ruleForAction,
  setShortcutRecording,
  writeShortcutConfig,
  type ShortcutEvent,
  type ShortcutRule
} from './shortcuts';

const PLAIN: ShortcutRule = { plainKeys: true };
const ENTER_ONLY: ShortcutRule = { plainKeys: true, mainKeys: ['Enter'] };

function keyEvent(partial: Partial<ShortcutEvent>): ShortcutEvent {
  return { key: '', ctrlKey: false, metaKey: false, altKey: false, shiftKey: false, ...partial };
}

describe('normalizeAccelerator · 全局规则（须含 Ctrl / Alt，F1–F12 例外）', () => {
  it('SHORTCUT-1: keeps canonical accelerators and normalizes case / order / aliases', () => {
    expect(normalizeAccelerator('Ctrl+J')).toBe('Ctrl+J');
    expect(normalizeAccelerator('ctrl+shift+l')).toBe('Ctrl+Shift+L');
    expect(normalizeAccelerator('Shift+Ctrl+K')).toBe('Ctrl+Shift+K');
    expect(normalizeAccelerator('Alt+F4')).toBe('Alt+F4');
    expect(normalizeAccelerator('Ctrl+Space')).toBe('Ctrl+Space');
    expect(normalizeAccelerator('Ctrl+Enter')).toBe('Ctrl+Enter');
    expect(normalizeAccelerator('Alt+left')).toBe('Alt+ArrowLeft');
    expect(normalizeAccelerator('Ctrl+Esc')).toBe('Ctrl+Escape');
    expect(normalizeAccelerator('F6')).toBe('F6');
  });

  it('SHORTCUT-2: treats Cmd / meta as Ctrl', () => {
    expect(normalizeAccelerator('Cmd+K')).toBe('Ctrl+K');
    expect(normalizeAccelerator('⌘+K')).toBe('Ctrl+K');
  });

  it('SHORTCUT-3: rejects keys without Ctrl / Alt, including Shift-only combos', () => {
    expect(normalizeAccelerator('A')).toBeNull();
    expect(normalizeAccelerator('7')).toBeNull();
    expect(normalizeAccelerator('Space')).toBeNull();
    expect(normalizeAccelerator('Enter')).toBeNull();
    expect(normalizeAccelerator('ArrowLeft')).toBeNull();
    // 仅 Shift 会与输入框里打大写字母冲突
    expect(normalizeAccelerator('Shift+A')).toBeNull();
    expect(normalizeAccelerator('Shift+1')).toBeNull();
  });

  it('SHORTCUT-4: rejects malformed or unsupported keys', () => {
    expect(normalizeAccelerator('Ctrl+')).toBeNull();
    expect(normalizeAccelerator('Ctrl')).toBeNull();
    expect(normalizeAccelerator('Ctrl+A+B')).toBeNull();
    expect(normalizeAccelerator('Ctrl+Tab')).toBeNull();
    expect(normalizeAccelerator('Ctrl+Backspace')).toBeNull();
    expect(normalizeAccelerator('Ctrl+F13')).toBeNull();
    expect(normalizeAccelerator('')).toBeNull();
  });

  it('SHORTCUT-5: allows function keys without modifiers only within F1-F12', () => {
    expect(normalizeAccelerator('F1')).toBe('F1');
    expect(normalizeAccelerator('F12')).toBe('F12');
    expect(normalizeAccelerator('Shift+F6')).toBe('Shift+F6');
    expect(normalizeAccelerator('F0')).toBeNull();
    expect(normalizeAccelerator('F13')).toBeNull();
  });
});

describe('normalizeAccelerator · 页面规则（允许单键）', () => {
  it('SHORTCUT-6: accepts plain letters, named keys and their aliases', () => {
    expect(normalizeAccelerator('n', PLAIN)).toBe('N');
    expect(normalizeAccelerator('Enter', PLAIN)).toBe('Enter');
    expect(normalizeAccelerator('esc', PLAIN)).toBe('Escape');
    expect(normalizeAccelerator('left', PLAIN)).toBe('ArrowLeft');
    expect(normalizeAccelerator('←', PLAIN)).toBe('ArrowLeft');
    expect(normalizeAccelerator('PgDn', PLAIN)).toBe('PageDown');
    expect(normalizeAccelerator('Space', PLAIN)).toBe('Space');
    expect(normalizeAccelerator('Shift+T', PLAIN)).toBe('Shift+T');
    expect(normalizeAccelerator('Home', PLAIN)).toBe('Home');
  });

  it('SHORTCUT-7: still rejects unsupported keys and double main keys', () => {
    expect(normalizeAccelerator('Tab', PLAIN)).toBeNull();
    expect(normalizeAccelerator('Backspace', PLAIN)).toBeNull();
    expect(normalizeAccelerator('Delete', PLAIN)).toBeNull();
    expect(normalizeAccelerator('Enter+Space', PLAIN)).toBeNull();
  });

  it('SHORTCUT-8: mainKeys restricts the main key but allows modifiers', () => {
    expect(normalizeAccelerator('Enter', ENTER_ONLY)).toBe('Enter');
    expect(normalizeAccelerator('Ctrl+Enter', ENTER_ONLY)).toBe('Ctrl+Enter');
    expect(normalizeAccelerator('Shift+Enter', ENTER_ONLY)).toBe('Shift+Enter');
    expect(normalizeAccelerator('A', ENTER_ONLY)).toBeNull();
    expect(normalizeAccelerator('Ctrl+A', ENTER_ONLY)).toBeNull();
  });
});

describe('acceleratorFromEvent 从键盘事件生成组合键', () => {
  it('SHORTCUT-9: builds combos under the global rule', () => {
    expect(acceleratorFromEvent(keyEvent({ key: 'j', ctrlKey: true }))).toBe('Ctrl+J');
    expect(acceleratorFromEvent(keyEvent({ key: 'J', metaKey: true }))).toBe('Ctrl+J');
    expect(acceleratorFromEvent(keyEvent({ key: 'l', ctrlKey: true, shiftKey: true }))).toBe('Ctrl+Shift+L');
    expect(acceleratorFromEvent(keyEvent({ key: 't', altKey: true }))).toBe('Alt+T');
    expect(acceleratorFromEvent(keyEvent({ key: 'F6' }))).toBe('F6');
    expect(acceleratorFromEvent(keyEvent({ key: 'F6', shiftKey: true }))).toBe('Shift+F6');
    expect(acceleratorFromEvent(keyEvent({ key: 'Enter', ctrlKey: true }))).toBe('Ctrl+Enter');
  });

  it('SHORTCUT-10: falls back to physical code when Shift changes the key', () => {
    // Shift+1 在多数布局下 key 为 '!'，应回退 code=Digit1 记成 Ctrl+Shift+1
    expect(acceleratorFromEvent(keyEvent({ key: '!', code: 'Digit1', ctrlKey: true, shiftKey: true }))).toBe('Ctrl+Shift+1');
  });

  it('SHORTCUT-11: global rule yields null for lone modifiers and plain keys', () => {
    expect(acceleratorFromEvent(keyEvent({ key: 'Control' }))).toBeNull();
    expect(acceleratorFromEvent(keyEvent({ key: 'a' }))).toBeNull();
    expect(acceleratorFromEvent(keyEvent({ key: 'A', shiftKey: true }))).toBeNull();
    expect(acceleratorFromEvent(keyEvent({ key: 'Enter' }))).toBeNull();
    expect(acceleratorFromEvent(keyEvent({ key: 'ArrowLeft' }))).toBeNull();
    expect(acceleratorFromEvent(keyEvent({ key: 'Tab', ctrlKey: true }))).toBeNull();
  });

  it('SHORTCUT-12: page rule accepts plain and named keys', () => {
    expect(acceleratorFromEvent(keyEvent({ key: 'Enter' }), PLAIN)).toBe('Enter');
    expect(acceleratorFromEvent(keyEvent({ key: 'ArrowRight' }), PLAIN)).toBe('ArrowRight');
    expect(acceleratorFromEvent(keyEvent({ key: ' ' }), PLAIN)).toBe('Space');
    expect(acceleratorFromEvent(keyEvent({ key: 'Escape' }), PLAIN)).toBe('Escape');
    expect(acceleratorFromEvent(keyEvent({ key: 't' }), PLAIN)).toBe('T');
    expect(acceleratorFromEvent(keyEvent({ key: 'PageDown' }), PLAIN)).toBe('PageDown');
    expect(acceleratorFromEvent(keyEvent({ key: 'a' }), ENTER_ONLY)).toBeNull();
  });
});

describe('matchesShortcut / matchesAny 事件匹配', () => {
  it('SHORTCUT-13: matches ctrl and meta equivalently', () => {
    expect(matchesShortcut(keyEvent({ key: 'j', ctrlKey: true }), 'Ctrl+J')).toBe(true);
    expect(matchesShortcut(keyEvent({ key: 'j', metaKey: true }), 'Ctrl+J')).toBe(true);
  });

  it('SHORTCUT-14: rejects modifier mismatches and plain keys', () => {
    expect(matchesShortcut(keyEvent({ key: 'j' }), 'Ctrl+J')).toBe(false);
    expect(matchesShortcut(keyEvent({ key: 'j', ctrlKey: true, shiftKey: true }), 'Ctrl+J')).toBe(false);
    expect(matchesShortcut(keyEvent({ key: 'k', ctrlKey: true }), 'Ctrl+J')).toBe(false);
    expect(matchesShortcut(keyEvent({ key: 'F6' }), 'Ctrl+J')).toBe(false);
    expect(matchesShortcut(keyEvent({ key: 'Enter', ctrlKey: true }), 'Enter')).toBe(false);
  });

  it('SHORTCUT-15: matches named keys, function keys and shift-digit bindings', () => {
    expect(matchesShortcut(keyEvent({ key: 'F6' }), 'F6')).toBe(true);
    expect(matchesShortcut(keyEvent({ key: 'Enter' }), 'Enter')).toBe(true);
    expect(matchesShortcut(keyEvent({ key: 'Enter', ctrlKey: true }), 'Ctrl+Enter')).toBe(true);
    expect(matchesShortcut(keyEvent({ key: 'ArrowLeft' }), 'ArrowLeft')).toBe(true);
    expect(matchesShortcut(keyEvent({ key: 'Escape' }), 'Escape')).toBe(true);
    expect(matchesShortcut(keyEvent({ key: 'Esc' }), 'Escape')).toBe(true);
    expect(matchesShortcut(keyEvent({ key: '!', code: 'Digit1', ctrlKey: true, shiftKey: true }), 'Ctrl+Shift+1')).toBe(true);
    expect(matchesShortcut(keyEvent({ key: ' ', ctrlKey: true }), 'Ctrl+Space')).toBe(true);
  });

  it('SHORTCUT-16: matchesAny checks every binding and is false for an empty list', () => {
    const prev = ['ArrowLeft', 'PageUp', 'P'];
    expect(matchesAny(keyEvent({ key: 'p' }), prev)).toBe(true);
    expect(matchesAny(keyEvent({ key: 'PageUp' }), prev)).toBe(true);
    expect(matchesAny(keyEvent({ key: 'x' }), prev)).toBe(false);
    expect(matchesAny(keyEvent({ key: 'p' }), [])).toBe(false);
  });
});

describe('resolveShortcutAction 按阶段解析动作', () => {
  const config = defaultShortcutConfig();

  it('SHORTCUT-17: Enter submits while answering and advances while reviewing', () => {
    expect(resolveShortcutAction(keyEvent({ key: 'Enter' }), config, ['quizSubmit', 'quizPrev'])).toBe('quizSubmit');
    expect(resolveShortcutAction(keyEvent({ key: 'Enter' }), config, ['quizNext', 'quizPrev'])).toBe('quizNext');
    expect(resolveShortcutAction(keyEvent({ key: 'ArrowLeft' }), config, ['quizSubmit', 'quizPrev'])).toBe('quizPrev');
    expect(resolveShortcutAction(keyEvent({ key: 'ArrowRight' }), config, ['quizSubmit', 'quizPrev'])).toBeNull();
    expect(resolveShortcutAction(keyEvent({ key: 'x' }), config, ['quizNext', 'quizPrev'])).toBeNull();
  });
});

describe('展示标签', () => {
  it('SHORTCUT-18: maps named keys to short labels and keeps the rest', () => {
    expect(describeAccelerator('Ctrl+J')).toBe('Ctrl+J');
    expect(describeAccelerator('Ctrl+ArrowLeft')).toBe('Ctrl+←');
    expect(describeAccelerator('Escape')).toBe('Esc');
    expect(describeAccelerator('PageDown')).toBe('PgDn');
    expect(describeAccelerators(['ArrowLeft', 'PageUp', 'P'], '/')).toBe('←/PgUp/P');
    expect(describeAccelerators(['Enter', 'Ctrl+S'])).toBe('Enter / Ctrl+S');
  });
});

describe('shortcut config 存取与归一化', () => {
  it('SHORTCUT-19: defaults when nothing is stored, and defaults never conflict', () => {
    expect(readShortcutConfig()).toEqual(defaultShortcutConfig());
    expect(findShortcutConflicts(defaultShortcutConfig())).toEqual({});
    expect(isDefaultShortcutConfig(defaultShortcutConfig())).toBe(true);
  });

  it('SHORTCUT-20: round-trips a custom config and normalizes each binding by its scope rule', () => {
    writeShortcutConfig({ ...defaultShortcutConfig(), toggleAi: ['ctrl+k'], toggleTheme: ['Alt+T'], openSettings: [], quizPrev: ['left', 'pgup'] });
    const config = readShortcutConfig();
    expect(config.toggleAi).toEqual(['Ctrl+K']);
    expect(config.toggleTheme).toEqual(['Alt+T']);
    expect(config.openSettings).toEqual([]);
    expect(config.quizPrev).toEqual(['ArrowLeft', 'PageUp']);
    expect(config.quizNext).toEqual(['Enter', 'ArrowRight', 'PageDown', 'N']);
  });

  it('SHORTCUT-21: accepts the early single-string / null shape', () => {
    localStorage.setItem(LS_SHORTCUTS, JSON.stringify({ toggleAi: 'ctrl+k', toggleTheme: null }));
    const config = readShortcutConfig();
    expect(config.toggleAi).toEqual(['Ctrl+K']);
    expect(config.toggleTheme).toEqual([]);
  });

  it('SHORTCUT-22: drops invalid entries, falls back to defaults when nothing survives, dedupes and caps', () => {
    localStorage.setItem(LS_SHORTCUTS, JSON.stringify({
      toggleAi: ['banana'],
      openSettings: 3,
      quizSubmit: ['Tab'],
      kcToggleOutline: ['Shift+A', 'T', 'x', 't'],
      toggleTheme: ['A', 'Ctrl+K', 'ctrl+k'],
      quizNext: ['1', '2', '3', '4', '5', '6', '7', '8']
    }));
    const config = readShortcutConfig();
    expect(config.toggleAi).toEqual(['Ctrl+J']);
    expect(config.openSettings).toEqual([]);
    expect(config.quizSubmit).toEqual(['Enter', 'Ctrl+S']);
    expect(config.kcToggleOutline).toEqual(['Shift+A', 'T', 'X']);
    expect(config.toggleTheme).toEqual(['Ctrl+K']);
    expect(config.quizNext).toHaveLength(6);
  });

  it('SHORTCUT-23: corrupt JSON and non-object payloads fall back to defaults', () => {
    localStorage.setItem(LS_SHORTCUTS, '{oops');
    expect(readShortcutConfig()).toEqual(defaultShortcutConfig());
    expect(normalizeShortcutConfig('nope')).toEqual(defaultShortcutConfig());
    expect(normalizeShortcutConfig([1, 2])).toEqual(defaultShortcutConfig());
  });

  it('SHORTCUT-24: ruleForAction reflects scope and per-action main keys', () => {
    expect(ruleForAction('toggleAi')).toEqual({ plainKeys: false });
    expect(ruleForAction('editorFinishBlock')).toEqual({ plainKeys: false });
    expect(ruleForAction('quizPrev')).toEqual({ plainKeys: true });
    expect(ruleForAction('aiSend')).toEqual({ plainKeys: true, mainKeys: ['Enter'] });
    // 每个默认值都必须通过自身规则，否则设置页无法「恢复默认」
    for (const meta of SHORTCUT_ACTIONS) {
      for (const accelerator of meta.defaults) expect(normalizeAccelerator(accelerator, ruleForAction(meta.id))).toBe(accelerator);
    }
  });
});

describe('findShortcutConflicts 冲突检测', () => {
  it('SHORTCUT-25: flags two global actions sharing one accelerator', () => {
    const conflicts = findShortcutConflicts({ ...defaultShortcutConfig(), toggleAi: ['Ctrl+J'], toggleTheme: ['Ctrl+J'] });
    expect(conflicts.toggleAi).toBe('toggleTheme');
    expect(conflicts.toggleTheme).toBe('toggleAi');
    expect(conflicts.openSettings).toBeUndefined();
  });

  it('SHORTCUT-26: a global binding conflicts with any page binding', () => {
    const conflicts = findShortcutConflicts({ ...defaultShortcutConfig(), kcSearch: ['Ctrl+J'] });
    expect(conflicts.kcSearch).toBe('toggleAi');
    expect(conflicts.toggleAi).toBe('kcSearch');
  });

  it('SHORTCUT-27: same scope conflicts unless the actions are in different phases', () => {
    // 默认 Enter 同时用于「提交」(answering) 与「下一题」(reviewing)：不同阶段，不冲突
    expect(findShortcutConflicts(defaultShortcutConfig())).toEqual({});
    // 「上一题」无阶段限制，与任一阶段共用 Enter 都冲突
    const conflicts = findShortcutConflicts({ ...defaultShortcutConfig(), quizPrev: ['Enter'] });
    expect(conflicts.quizPrev).toBeDefined();
    expect(['quizSubmit', 'quizNext']).toContain(conflicts.quizPrev);
  });

  it('SHORTCUT-28: different page scopes never conflict', () => {
    // 刷题 Enter 提交 与 AI 输入框 Enter 发送 各在各的页面
    expect(findShortcutConflicts({ ...defaultShortcutConfig(), aiSend: ['Enter'], kcNext: ['ArrowRight'], quizNext: ['ArrowRight'] })).toEqual({});
  });

  it('SHORTCUT-29: unbound actions never conflict', () => {
    const empty = defaultShortcutConfig();
    for (const meta of SHORTCUT_ACTIONS) empty[meta.id] = [];
    expect(findShortcutConflicts(empty)).toEqual({});
  });
});

describe('isActionDefault', () => {
  it('SHORTCUT-30: compares bindings order-insensitively', () => {
    const config = defaultShortcutConfig();
    expect(isActionDefault('quizPrev', config)).toBe(true);
    config.quizPrev = ['P', 'PageUp', 'ArrowLeft'];
    expect(isActionDefault('quizPrev', config)).toBe(true);
    config.quizPrev = ['P'];
    expect(isActionDefault('quizPrev', config)).toBe(false);
    expect(isDefaultShortcutConfig(config)).toBe(false);
  });
});

describe('recording 标志', () => {
  afterEach(() => setShortcutRecording(false));

  it('SHORTCUT-31: toggles and resets', () => {
    expect(isShortcutRecording()).toBe(false);
    setShortcutRecording(true);
    expect(isShortcutRecording()).toBe(true);
    setShortcutRecording(false);
    expect(isShortcutRecording()).toBe(false);
  });

  it('SHORTCUT-32: isModifierKey identifies lone modifier presses', () => {
    expect(isModifierKey('Control')).toBe(true);
    expect(isModifierKey('Shift')).toBe(true);
    expect(isModifierKey('Alt')).toBe(true);
    expect(isModifierKey('Meta')).toBe(true);
    expect(isModifierKey('a')).toBe(false);
  });
});
