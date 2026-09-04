/**
 * 快捷键：全局动作与各页面局部动作统一在「设置 → 常规 → 快捷键」自定义。
 *
 * 约定：
 * - 每个动作可绑定多个组合键（string[]，[] = 未绑定），以规范串存储：
 *   `Ctrl+J`、`Ctrl+Shift+L`、`F2`、`ArrowRight`、`Ctrl+Enter`…；Ctrl 兼容 Mac ⌘（metaKey）；
 * - 作用域决定单键规则：全局 / 编辑器内（在 textarea 里触发）必须带 Ctrl 或 Alt（F1–F12 例外），
 *   否则打字会误触发；页面内动作（刷题、知识卡片全屏、题库列表）允许单键，它们在输入框聚焦时本就不生效；
 * - 单一 localStorage key（`ui.shortcuts.v1`），读取时逐项归一化：非法条目丢弃，整项全非法或缺失回落默认；
 * - 冲突：同一组合键不能绑定会在同一时机触发的两个动作——全局动作与任何动作互斥，
 *   同一作用域内除显式声明为不同阶段（phase，如刷题「作答中 / 已提交」）外互斥，不同页面作用域互不影响；
 * - ShortcutRecorder 录制期间置 recording 标志，全局监听忽略按键，
 *   避免改绑「打开 AI 助手」时旧绑定把侧栏一起唤出。
 */

export type ShortcutScope = 'global' | 'quiz' | 'knowledgeCard' | 'editor' | 'bank' | 'ai';

export interface ShortcutScopeMeta {
  id: ShortcutScope;
  label: string;
  /** 一句话说明生效范围 */
  description: string;
  /** 允许无 Ctrl / Alt 的单键（仅在输入框未聚焦时触发的页面动作才可以） */
  plainKeys: boolean;
}

export const SHORTCUT_SCOPES: ShortcutScopeMeta[] = [
  { id: 'global', label: '全局', description: '任意页面生效。', plainKeys: false },
  { id: 'quiz', label: '刷题', description: '答题时生效；数字键选选项固定。', plainKeys: true },
  { id: 'knowledgeCard', label: '知识卡片全屏', description: '知识点全屏阅读时生效。', plainKeys: true },
  { id: 'editor', label: '笔记编辑器', description: '编辑公式 / 图表 / Markdown 块时生效。', plainKeys: false },
  { id: 'bank', label: '题库', description: '题库列表项选中时生效。', plainKeys: true },
  { id: 'ai', label: 'AI 助手', description: '侧栏输入框内生效。', plainKeys: true }
];

export type ShortcutAction =
  | 'toggleAi' | 'toggleTheme' | 'openSettings'
  | 'quizSubmit' | 'quizNext' | 'quizPrev'
  | 'kcSearch' | 'kcToggleOutline' | 'kcPrev' | 'kcNext' | 'kcExit'
  | 'editorFinishBlock'
  | 'bankRename'
  | 'aiSend';

export interface ShortcutActionMeta {
  id: ShortcutAction;
  scope: ShortcutScope;
  /** 设置页展示的动作名。 */
  label: string;
  /** 一句话说明，可为空。 */
  description: string;
  /** 默认组合键；[] = 默认不绑定。 */
  defaults: string[];
  /** 同一作用域内不同阶段的动作可共用按键（刷题：作答中 Enter 提交、已提交 Enter 下一题）。 */
  phase?: string;
  /** 限定主键（AI 发送只能用 Enter 组合），录入其它键会被拒绝。 */
  mainKeys?: string[];
}

export const SHORTCUT_ACTIONS: ShortcutActionMeta[] = [
  { id: 'toggleAi', scope: 'global', label: '打开 / 关闭 AI 助手', description: '任意页面唤出或收起侧栏。', defaults: ['Ctrl+J'] },
  { id: 'toggleTheme', scope: 'global', label: '切换深浅主题', description: '明暗主题一键互换。', defaults: [] },
  { id: 'openSettings', scope: 'global', label: '打开设置', description: '直达设置页。', defaults: [] },
  { id: 'quizSubmit', scope: 'quiz', phase: 'answering', label: '提交答案', description: '作答中生效。', defaults: ['Enter', 'Ctrl+S'] },
  { id: 'quizNext', scope: 'quiz', phase: 'reviewing', label: '下一题', description: '看完解析后生效。', defaults: ['Enter', 'ArrowRight', 'PageDown', 'N'] },
  { id: 'quizPrev', scope: 'quiz', label: '上一题', description: '', defaults: ['ArrowLeft', 'PageUp', 'P'] },
  { id: 'kcSearch', scope: 'knowledgeCard', label: '搜索大纲与正文', description: '', defaults: ['Ctrl+F'] },
  { id: 'kcToggleOutline', scope: 'knowledgeCard', label: '折叠 / 展开大纲', description: '搜索框打开时不生效。', defaults: ['T'] },
  { id: 'kcPrev', scope: 'knowledgeCard', label: '上一张卡片', description: '', defaults: ['ArrowLeft'] },
  { id: 'kcNext', scope: 'knowledgeCard', label: '下一张卡片', description: '', defaults: ['ArrowRight'] },
  { id: 'kcExit', scope: 'knowledgeCard', label: '退出全屏', description: '搜索框打开时先关闭搜索。', defaults: ['Escape'] },
  { id: 'editorFinishBlock', scope: 'editor', label: '完成块编辑', description: '公式 / 图表 / Markdown 块。', defaults: ['Ctrl+Enter'] },
  { id: 'bankRename', scope: 'bank', label: '重命名题库', description: '', defaults: ['F2'] },
  { id: 'aiSend', scope: 'ai', label: '发送消息', description: '其余 Enter 组合用于换行。', defaults: ['Enter'], mainKeys: ['Enter'] }
];

const ACTION_BY_ID = new Map(SHORTCUT_ACTIONS.map((meta) => [meta.id, meta] as const));
const SCOPE_BY_ID = new Map(SHORTCUT_SCOPES.map((scope) => [scope.id, scope] as const));

export function shortcutActionMeta(action: ShortcutAction): ShortcutActionMeta {
  return ACTION_BY_ID.get(action) as ShortcutActionMeta;
}

/* ---------------------------------------------------------------- 解析 */

/** 组合键解析结果；ctrl 同时代表 Ctrl 与 ⌘。 */
export interface ParsedShortcut {
  ctrl: boolean;
  alt: boolean;
  shift: boolean;
  /** 归一化主键：'A'-'Z' | '0'-'9' | 'F1'-'F12' | 命名键（Enter / Escape / Space / Arrow* / PageUp / PageDown / Home / End） */
  key: string;
}

/** 单键规则：plainKeys=false 时非功能键必须带 Ctrl 或 Alt；mainKeys 限定可用主键。 */
export interface ShortcutRule {
  plainKeys: boolean;
  mainKeys?: string[];
}

export const GLOBAL_RULE: ShortcutRule = { plainKeys: false };
/** 匹配已存储的组合键时不再校验规则（存储前已按各自规则归一化）。 */
const MATCH_RULE: ShortcutRule = { plainKeys: true };

const MODIFIER_KEYS = new Set(['Control', 'Shift', 'Alt', 'Meta']);
const FUNCTION_KEY = /^F([1-9]|1[0-2])$/i;
const KEY_ALIASES: Record<string, string> = {
  enter: 'Enter', return: 'Enter',
  escape: 'Escape', esc: 'Escape',
  space: 'Space', spacebar: 'Space',
  arrowleft: 'ArrowLeft', left: 'ArrowLeft', '←': 'ArrowLeft',
  arrowright: 'ArrowRight', right: 'ArrowRight', '→': 'ArrowRight',
  arrowup: 'ArrowUp', up: 'ArrowUp', '↑': 'ArrowUp',
  arrowdown: 'ArrowDown', down: 'ArrowDown', '↓': 'ArrowDown',
  pageup: 'PageUp', pgup: 'PageUp',
  pagedown: 'PageDown', pgdn: 'PageDown',
  home: 'Home',
  end: 'End'
};
/** 展示用短标签；未列出的键原样显示。 */
const KEY_LABELS: Record<string, string> = {
  ArrowLeft: '←', ArrowRight: '→', ArrowUp: '↑', ArrowDown: '↓',
  Escape: 'Esc', PageUp: 'PgUp', PageDown: 'PgDn'
};

/** 是否为单独按下的修饰键（此时组合键尚未完成）。 */
export function isModifierKey(key: string): boolean {
  return MODIFIER_KEYS.has(key);
}

function normalizeKeyToken(token: string): string | null {
  if (/^[a-z]$/i.test(token)) return token.toUpperCase();
  if (/^[0-9]$/.test(token)) return token;
  if (FUNCTION_KEY.test(token)) return token.toUpperCase();
  return KEY_ALIASES[token.toLowerCase()] ?? null;
}

/**
 * 归一化事件里的主键。Shift 会改变 key（Shift+1 → '!'），此时回退到
 * 物理 code（Digit1/KeyJ）还原，保证记录与匹配看到的是同一个键。
 */
function normalizeEventKey(key: string, code?: string): string | null {
  if (key === ' ') return 'Space';
  const direct = normalizeKeyToken(key);
  if (direct) return direct;
  if (code) {
    const letter = /^Key([A-Z])$/.exec(code);
    if (letter) return letter[1];
    const digit = /^Digit([0-9])$/.exec(code);
    if (digit) return digit[1];
  }
  return null;
}

function passesRule(parsed: ParsedShortcut, rule: ShortcutRule): boolean {
  if (rule.mainKeys && !rule.mainKeys.includes(parsed.key)) return false;
  if (!rule.plainKeys && !parsed.ctrl && !parsed.alt && !FUNCTION_KEY.test(parsed.key)) return false;
  return true;
}

export function parseAccelerator(raw: string, rule: ShortcutRule = GLOBAL_RULE): ParsedShortcut | null {
  if (typeof raw !== 'string' || raw.length === 0 || raw.length > 40) return null;
  const tokens = raw.split('+').map((token) => token.trim()).filter(Boolean);
  if (tokens.length === 0) return null;
  let ctrl = false;
  let alt = false;
  let shift = false;
  let key: string | null = null;
  for (const token of tokens) {
    const lower = token.toLowerCase();
    if (lower === 'ctrl' || lower === 'control' || lower === 'cmd' || lower === 'meta' || token === '⌘') ctrl = true;
    else if (lower === 'alt' || lower === 'option') alt = true;
    else if (lower === 'shift') shift = true;
    else if (key !== null) return null; // 出现第二个主键
    else {
      key = normalizeKeyToken(token);
      if (key === null) return null;
    }
  }
  if (key === null) return null;
  const parsed = { ctrl, alt, shift, key };
  return passesRule(parsed, rule) ? parsed : null;
}

/** 修饰键固定按 Ctrl → Alt → Shift 排序，输出规范串。 */
export function formatAccelerator(parsed: ParsedShortcut): string {
  const parts: string[] = [];
  if (parsed.ctrl) parts.push('Ctrl');
  if (parsed.alt) parts.push('Alt');
  if (parsed.shift) parts.push('Shift');
  parts.push(parsed.key);
  return parts.join('+');
}

/** 解析并重建规范串；非法或不符合规则返回 null。 */
export function normalizeAccelerator(raw: string, rule: ShortcutRule = GLOBAL_RULE): string | null {
  const parsed = parseAccelerator(raw, rule);
  return parsed ? formatAccelerator(parsed) : null;
}

/** 匹配 / 录制所需的事件字段；code 在合成事件里可能缺失，仅作 Shift 变键时的回退。 */
export type ShortcutEvent = Pick<KeyboardEvent, 'key' | 'ctrlKey' | 'metaKey' | 'altKey' | 'shiftKey'> & { code?: string };

/** 从键盘事件生成组合键；单独的修饰键或不符合规则时返回 null。 */
export function acceleratorFromEvent(event: ShortcutEvent, rule: ShortcutRule = GLOBAL_RULE): string | null {
  if (isModifierKey(event.key)) return null;
  const key = normalizeEventKey(event.key, event.code);
  if (key === null) return null;
  const parsed = { ctrl: event.ctrlKey || event.metaKey, alt: event.altKey, shift: event.shiftKey, key };
  return passesRule(parsed, rule) ? formatAccelerator(parsed) : null;
}

export function matchesShortcut(event: ShortcutEvent, accelerator: string): boolean {
  const parsed = parseAccelerator(accelerator, MATCH_RULE);
  if (!parsed) return false;
  const key = normalizeEventKey(event.key, event.code);
  if (key !== parsed.key) return false;
  if (parsed.ctrl !== (event.ctrlKey || event.metaKey)) return false;
  if (parsed.alt !== event.altKey) return false;
  if (parsed.shift !== event.shiftKey) return false;
  return true;
}

export function matchesAny(event: ShortcutEvent, accelerators: readonly string[]): boolean {
  return accelerators.some((accelerator) => matchesShortcut(event, accelerator));
}

/** 按候选顺序返回第一个命中的动作；页面据当前阶段传入候选（如刷题作答中不含「下一题」）。 */
export function resolveShortcutAction(
  event: ShortcutEvent,
  config: ShortcutConfig,
  candidates: readonly ShortcutAction[]
): ShortcutAction | null {
  for (const action of candidates) {
    if (matchesAny(event, config[action] ?? [])) return action;
  }
  return null;
}

/* ---------------------------------------------------------------- 展示 */

export function keyLabel(key: string): string {
  return KEY_LABELS[key] ?? key;
}

/** 拆成展示用片段：'Ctrl+ArrowLeft' → ['Ctrl', '←']。 */
export function acceleratorParts(accelerator: string): string[] {
  return accelerator.split('+').map(keyLabel);
}

export function describeAccelerator(accelerator: string): string {
  return acceleratorParts(accelerator).join('+');
}

export function describeAccelerators(accelerators: readonly string[], separator = ' / '): string {
  return accelerators.map(describeAccelerator).join(separator);
}

/* ------------------------------------------------------------- 配置存取 */

export type ShortcutConfig = Record<ShortcutAction, string[]>;

export const LS_SHORTCUTS = 'ui.shortcuts.v1';
export const MAX_BINDINGS_PER_ACTION = 6;

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

export function ruleForAction(action: ShortcutAction): ShortcutRule {
  const meta = shortcutActionMeta(action);
  const scope = SCOPE_BY_ID.get(meta.scope) as ShortcutScopeMeta;
  return meta.mainKeys ? { plainKeys: scope.plainKeys, mainKeys: meta.mainKeys } : { plainKeys: scope.plainKeys };
}

export function defaultShortcutConfig(): ShortcutConfig {
  const out = {} as ShortcutConfig;
  for (const meta of SHORTCUT_ACTIONS) out[meta.id] = [...meta.defaults];
  return out;
}

/**
 * 容错解析：缺失 / 非法类型 / 全部条目非法 → 该项回落默认；显式空数组或 null → 未绑定；
 * 兼容早期单串形态（`"Ctrl+J"`）。逐条按作用域规则归一化并去重，超出上限截断。
 */
export function normalizeShortcutConfig(raw: unknown): ShortcutConfig {
  const out = defaultShortcutConfig();
  if (!isRecord(raw)) return out;
  for (const meta of SHORTCUT_ACTIONS) {
    if (!(meta.id in raw)) continue;
    const value = raw[meta.id];
    const list = value === null ? [] : typeof value === 'string' ? [value] : Array.isArray(value) ? value : null;
    if (list === null) continue;
    const rule = ruleForAction(meta.id);
    const normalized: string[] = [];
    for (const entry of list) {
      if (typeof entry !== 'string') continue;
      const accelerator = normalizeAccelerator(entry, rule);
      if (accelerator && !normalized.includes(accelerator)) normalized.push(accelerator);
      if (normalized.length >= MAX_BINDINGS_PER_ACTION) break;
    }
    out[meta.id] = list.length > 0 && normalized.length === 0 ? [...meta.defaults] : normalized;
  }
  return out;
}

export function readShortcutConfig(): ShortcutConfig {
  try {
    const raw = localStorage.getItem(LS_SHORTCUTS);
    if (!raw) return defaultShortcutConfig();
    return normalizeShortcutConfig(JSON.parse(raw));
  } catch {
    return defaultShortcutConfig();
  }
}

export function writeShortcutConfig(config: ShortcutConfig): void {
  try {
    localStorage.setItem(LS_SHORTCUTS, JSON.stringify(normalizeShortcutConfig(config)));
  } catch {
    /* ignore quota / private mode */
  }
}

function sameBindings(a: readonly string[], b: readonly string[]): boolean {
  return a.length === b.length && [...a].sort().join(' ') === [...b].sort().join(' ');
}

export function isActionDefault(action: ShortcutAction, config: ShortcutConfig): boolean {
  return sameBindings(config[action] ?? [], shortcutActionMeta(action).defaults);
}

export function isDefaultShortcutConfig(config: ShortcutConfig): boolean {
  return SHORTCUT_ACTIONS.every((meta) => isActionDefault(meta.id, config));
}

/* ---------------------------------------------------------------- 冲突 */

/** 两个动作是否会在同一时机被同一按键触发。 */
function scopesOverlap(a: ShortcutActionMeta, b: ShortcutActionMeta): boolean {
  if (a.scope === 'global' || b.scope === 'global') return true;
  if (a.scope !== b.scope) return false;
  return !a.phase || !b.phase || a.phase === b.phase;
}

/** 返回 action → 与其共用组合键且会同时触发的另一个 action；无冲突的 action 不出现在结果里。 */
export function findShortcutConflicts(config: ShortcutConfig): Partial<Record<ShortcutAction, ShortcutAction>> {
  const conflicts: Partial<Record<ShortcutAction, ShortcutAction>> = {};
  const normalized = new Map<ShortcutAction, string[]>();
  for (const meta of SHORTCUT_ACTIONS) {
    normalized.set(meta.id, (config[meta.id] ?? []).map((entry) => normalizeAccelerator(entry, MATCH_RULE) ?? entry));
  }
  for (let i = 0; i < SHORTCUT_ACTIONS.length; i += 1) {
    for (let j = i + 1; j < SHORTCUT_ACTIONS.length; j += 1) {
      const a = SHORTCUT_ACTIONS[i];
      const b = SHORTCUT_ACTIONS[j];
      if (!scopesOverlap(a, b)) continue;
      const bindingsB = normalized.get(b.id) ?? [];
      if (!(normalized.get(a.id) ?? []).some((accelerator) => bindingsB.includes(accelerator))) continue;
      conflicts[a.id] ??= b.id;
      conflicts[b.id] ??= a.id;
    }
  }
  return conflicts;
}

/* ------------------------------------------------------------- 录制中标志 */

let recording = false;

/** ShortcutRecorder 进入 / 退出录制态时切换；全局快捷键监听据此让路。 */
export function setShortcutRecording(active: boolean): void {
  recording = active;
}

export function isShortcutRecording(): boolean {
  return recording;
}
