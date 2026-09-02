/**
 * 界面状态记忆：启动回到上次停留的页面，并恢复各页的选中项、切换项与筛选条件。
 *
 * 单一 localStorage key（`ui.viewState.v1`）存一份结构化视图状态：
 * - 读：`readViewState` / `readPageSlice` / `readLastRoute`（含未落盘的待写入补丁）
 * - 写：`persistViewState(page, partial)` / `recordRoute(path)`，浅合并 + 去重 + 尾部防抖
 * - 落盘：`flushViewState()`（pagehide/beforeunload 与测试用）
 *
 * 只存「视图选择」，绝不存密钥、脏缓冲与进行中的会话进度；任何异常都退化成
 * 「该字段无缓存 → 页面默认值」，不写入半对的错误选择。
 */
import { LS_REMEMBER_VIEW_STATE, readBoolPref } from './sessionPrefs';

export const LS_VIEW_STATE = 'ui.viewState.v1';

/** 侧栏可达页面（与 App.tsx 的 navItems 白名单一致）。 */
export type PagePath =
  | '/notebooks'
  | '/banks'
  | '/wrong'
  | '/knowledge'
  | '/practice'
  | '/calendar'
  | '/settings';

export type PageKey = PagePath extends `/${infer P}` ? P : never;

export type PracticeTab = 'quiz' | 'memorize';
export type MemorizeTarget = 'questions' | 'knowledge';
export type SettingsTab = 'general' | 'ai' | 'embedding' | 'study' | 'data';

/** id 勾选集合：`all` 是「整库全选」哨兵，避免把上万个 id 写进 localStorage。 */
export interface IdSet {
  all?: true;
  ids?: number[];
}

/** 树勾选集合：`all` 对应现网 `checkedKeys === undefined`（= 全选当前库）。 */
export interface KeySet {
  mode: 'all' | 'some';
  keys?: string[];
}

/** 按题库作用域保存的选择，只留最近 MAX_SCOPES 个库。 */
export interface ScopedById<T> {
  lastId?: number;
  byId: Record<string, T>;
}

export interface SelectorFilters {
  search?: string;
  types?: string[];
  chapters?: string[];
  tags?: string[];
}

export interface PageStates {
  notebooks: {
    notebookId?: number;
    pageId?: number;
    selectedPageIds?: number[];
    focusMode?: boolean;
  };
  banks: {
    selectedId?: number;
    selection?: ScopedById<IdSet>;
  };
  wrong: {
    selectedIds?: number[];
  };
  knowledge: {
    bankId?: number;
    search?: string;
    activeId?: number | null;
    activeTag?: string | null;
    treeCollapsed?: ScopedById<string[]>;
    fullCardWidth?: number;
  };
  practice: {
    tab?: PracticeTab;
    memorizeTarget?: MemorizeTarget;
    quizBankId?: number;
    quizSelection?: ScopedById<IdSet>;
    studyBankId?: number;
    studySelection?: ScopedById<IdSet>;
    knowledgeBankId?: number;
    knowledgeChecked?: ScopedById<KeySet>;
    selectors?: ScopedById<SelectorFilters>;
  };
  calendar: {
    viewYear?: number;
    viewMonth?: number;
  };
  settings: {
    tab?: SettingsTab;
  };
}

export interface ViewState {
  version: 1;
  lastRoute?: PagePath;
  pages: PageStates;
}

export const DEFAULT_VIEW_STATE: ViewState = { version: 1, pages: {} };

const PAGE_KEYS: PageKey[] = ['notebooks', 'banks', 'wrong', 'knowledge', 'practice', 'calendar', 'settings'];
const PAGE_PATHS: PagePath[] = PAGE_KEYS.map((key) => `/${key}` as PagePath);
const PRACTICE_TABS = ['quiz', 'memorize'];
const MEMORIZE_TARGETS = ['questions', 'knowledge'];
const SETTINGS_TABS = ['general', 'ai', 'embedding', 'study', 'data'];
const ROUTE_ALIASES: Record<string, PagePath> = { '/quiz': '/practice', '/memorize': '/practice' };

export const MAX_IDS = 500;
export const MAX_KEYS = 200;
export const MAX_SCOPES = 4;
const MAX_BYTES = 96 * 1024;

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function num(value: unknown): number | undefined {
  return typeof value === 'number' && Number.isInteger(value) && value > 0 && value < 1e9 ? value : undefined;
}

function text(value: unknown, max = 200): string | undefined {
  return typeof value === 'string' && value.length <= max ? value : undefined;
}

function oneOf<T extends string>(value: unknown, allowed: readonly T[]): T | undefined {
  return typeof value === 'string' && (allowed as readonly string[]).includes(value) ? (value as T) : undefined;
}

function numArray(value: unknown, max: number): number[] | undefined {
  if (!Array.isArray(value)) return undefined;
  const out = value.map(num).filter((v): v is number => v !== undefined);
  return out.length > 0 ? out.slice(0, max) : [];
}

function strArray(value: unknown, max: number): string[] | undefined {
  if (!Array.isArray(value)) return undefined;
  const out = value.filter((v): v is string => typeof v === 'string' && v.length <= 200);
  return out.length > 0 ? out.slice(0, max) : [];
}

function idSet(value: unknown): IdSet | undefined {
  if (!isRecord(value)) return undefined;
  const out: IdSet = {};
  if (value.all === true) out.all = true;
  const ids = numArray(value.ids, MAX_IDS);
  if (ids) out.ids = ids;
  return out.all || out.ids ? out : undefined;
}

function keySet(value: unknown): KeySet | undefined {
  if (!isRecord(value)) return undefined;
  const mode = oneOf(value.mode, ['all', 'some'] as const);
  if (!mode) return undefined;
  if (mode === 'all') return { mode };
  const keys = strArray(value.keys, MAX_KEYS);
  return { mode, keys: keys ?? [] };
}

function scoped<T>(value: unknown, parse: (entry: unknown) => T | undefined): ScopedById<T> | undefined {
  if (!isRecord(value) || !isRecord(value.byId)) return undefined;
  const byId: Record<string, T> = {};
  for (const [key, entry] of Object.entries(value.byId)) {
    if (Object.keys(byId).length >= MAX_SCOPES) break;
    const parsed = parse(entry);
    if (parsed) byId[key] = parsed;
  }
  const out: ScopedById<T> = { byId };
  const lastId = num(value.lastId);
  if (lastId !== undefined) out.lastId = lastId;
  return Object.keys(byId).length > 0 ? out : undefined;
}

function selectors(value: unknown): SelectorFilters | undefined {
  if (!isRecord(value)) return undefined;
  const out: SelectorFilters = {};
  const search = text(value.search, 120);
  if (search) out.search = search;
  const types = strArray(value.types, 20);
  if (types?.length) out.types = types;
  const chapters = strArray(value.chapters, 20);
  if (chapters?.length) out.chapters = chapters;
  const tags = strArray(value.tags, 20);
  if (tags?.length) out.tags = tags;
  return Object.keys(out).length > 0 ? out : undefined;
}

function normalizePage(page: PageKey, value: unknown): Record<string, unknown> {
  if (!isRecord(value)) return {};
  const out: Record<string, unknown> = {};
  const pick = (key: string, parsed: unknown): void => {
    if (parsed !== undefined) out[key] = parsed;
  };
  switch (page) {
    case 'notebooks':
      pick('notebookId', num(value.notebookId));
      pick('pageId', num(value.pageId));
      pick('selectedPageIds', numArray(value.selectedPageIds, MAX_IDS));
      pick('focusMode', typeof value.focusMode === 'boolean' ? value.focusMode : undefined);
      break;
    case 'banks':
      pick('selectedId', num(value.selectedId));
      pick('selection', scoped(value.selection, idSet));
      break;
    case 'wrong':
      pick('selectedIds', numArray(value.selectedIds, MAX_IDS));
      break;
    case 'knowledge':
      pick('bankId', num(value.bankId));
      pick('search', text(value.search, 120));
      pick('activeId', value.activeId === null ? null : num(value.activeId));
      pick('activeTag', text(value.activeTag));
      pick('treeCollapsed', scoped(value.treeCollapsed, (entry) => strArray(entry, MAX_KEYS)));
      pick('fullCardWidth', num(value.fullCardWidth));
      break;
    case 'practice':
      pick('tab', oneOf(value.tab, PRACTICE_TABS));
      pick('memorizeTarget', oneOf(value.memorizeTarget, MEMORIZE_TARGETS));
      pick('quizBankId', num(value.quizBankId));
      pick('quizSelection', scoped(value.quizSelection, idSet));
      pick('studyBankId', num(value.studyBankId));
      pick('studySelection', scoped(value.studySelection, idSet));
      pick('knowledgeBankId', num(value.knowledgeBankId));
      pick('knowledgeChecked', scoped(value.knowledgeChecked, keySet));
      pick('selectors', scoped(value.selectors, selectors));
      break;
    case 'calendar':
      pick('viewYear', num(value.viewYear));
      pick('viewMonth', typeof value.viewMonth === 'number' && value.viewMonth >= 0 && value.viewMonth <= 11 ? value.viewMonth : undefined);
      break;
    case 'settings':
      pick('tab', oneOf(value.tab, SETTINGS_TABS));
      break;
  }
  return out;
}

export function normalizeViewState(raw: unknown): ViewState {
  if (!isRecord(raw)) return { version: 1, pages: {} };
  const pages: Partial<PageStates> = {};
  if (isRecord(raw.pages)) {
    for (const page of PAGE_KEYS) {
      const slice = normalizePage(page, raw.pages[page]);
      if (Object.keys(slice).length > 0) pages[page] = slice as unknown as PageStates[typeof page];
    }
  }
  const out: ViewState = { version: 1, pages: pages as PageStates };
  const route = oneOf(raw.lastRoute, PAGE_PATHS);
  if (route) out.lastRoute = route;
  return out;
}

/* ------------------------------------------------------------------ 读写 */

let cached: ViewState = DEFAULT_VIEW_STATE;
let cachedRaw: string | null = null;
let pendingPages: Partial<Record<PageKey, object>> = {};
let pendingRoute: PagePath | undefined;
let timer: ReturnType<typeof setTimeout> | undefined;

export function isRememberViewStateEnabled(): boolean {
  return readBoolPref(LS_REMEMBER_VIEW_STATE, true);
}

function syncFromStorage(): void {
  let raw: string | null = null;
  try {
    raw = localStorage.getItem(LS_VIEW_STATE);
  } catch {
    raw = null;
  }
  if (raw === cachedRaw) return;
  cachedRaw = raw;
  if (!raw) {
    cached = DEFAULT_VIEW_STATE;
    return;
  }
  try {
    cached = normalizeViewState(JSON.parse(raw));
  } catch {
    cached = DEFAULT_VIEW_STATE;
  }
}

export function readViewState(): ViewState {
  if (!isRememberViewStateEnabled()) return DEFAULT_VIEW_STATE;
  syncFromStorage();
  if (Object.keys(pendingPages).length === 0 && !pendingRoute) return cached;
  const pages = { ...cached.pages };
  for (const [page, patch] of Object.entries(pendingPages)) {
    pages[page as PageKey] = { ...pages[page as PageKey], ...patch } as never;
  }
  return { ...cached, pages, ...(pendingRoute ? { lastRoute: pendingRoute } : {}) };
}

export function readPageSlice<K extends PageKey>(page: K): PageStates[K] {
  return readViewState().pages[page] ?? ({} as PageStates[K]);
}

export function readLastRoute(): PagePath | undefined {
  return readViewState().lastRoute;
}

let hooksInstalled = false;

function scheduleFlush(): void {
  if (!hooksInstalled && typeof window !== 'undefined') {
    hooksInstalled = true;
    window.addEventListener('pagehide', flushViewState);
    window.addEventListener('beforeunload', flushViewState);
  }
  if (timer) clearTimeout(timer);
  timer = setTimeout(flushViewState, 300);
}

export function persistViewState(page: PageKey, values: object): void {
  if (!isRememberViewStateEnabled()) return;
  const patch = { ...pendingPages[page], ...values };
  pendingPages[page] = patch;
  scheduleFlush();
}

export function recordRoute(path: string): void {
  if (!isRememberViewStateEnabled()) return;
  const pathname = path.split(/[?#]/)[0];
  const mapped = ROUTE_ALIASES[pathname] ?? pathname;
  if (!oneOf(mapped, PAGE_PATHS)) return;
  if (readViewState().lastRoute === mapped) return;
  pendingRoute = mapped;
  scheduleFlush();
}

function shrinkScoped(value: unknown): unknown {
  if (!isRecord(value) || !isRecord(value.byId)) return value;
  const byId: Record<string, unknown> = {};
  for (const [key, entry] of Object.entries(value.byId)) {
    if (isRecord(entry) && Array.isArray(entry.ids)) byId[key] = { ...entry, ids: (entry.ids as unknown[]).slice(0, 50) };
    else if (isRecord(entry) && Array.isArray(entry.keys)) byId[key] = { ...entry, keys: (entry.keys as unknown[]).slice(0, 50) };
    else byId[key] = entry;
  }
  return { ...value, byId };
}

/** 超预算时的降级：截断长数组字段，宁可少记也不越界。 */
function shrink(state: ViewState): ViewState {
  const pages: Record<string, unknown> = { ...state.pages };
  for (const page of PAGE_KEYS) {
    const slice = pages[page];
    if (!isRecord(slice)) continue;
    const next: Record<string, unknown> = {};
    for (const [field, value] of Object.entries(slice)) {
      next[field] = Array.isArray(value) && value.length > 50 ? value.slice(0, 50) : shrinkScoped(value);
    }
    pages[page] = next;
  }
  return { ...state, pages: pages as unknown as PageStates };
}

export function flushViewState(): void {
  if (timer) {
    clearTimeout(timer);
    timer = undefined;
  }
  const hasPending = Object.keys(pendingPages).length > 0 || Boolean(pendingRoute);
  if (!hasPending || !isRememberViewStateEnabled()) {
    pendingPages = {};
    pendingRoute = undefined;
    return;
  }
  const next = normalizeViewState(readViewState());
  pendingPages = {};
  pendingRoute = undefined;
  let serialized = JSON.stringify(next);
  if (serialized.length > MAX_BYTES) {
    serialized = JSON.stringify(shrink(next));
    if (serialized.length > MAX_BYTES) return;
  }
  syncFromStorage();
  if (serialized === cachedRaw) return;
  try {
    localStorage.setItem(LS_VIEW_STATE, serialized);
    cachedRaw = serialized;
    cached = next;
  } catch {
    /* 配额或隐私模式：静默放弃本次记忆 */
  }
}

export function clearViewState(): void {
  pendingPages = {};
  pendingRoute = undefined;
  if (timer) {
    clearTimeout(timer);
    timer = undefined;
  }
  try {
    localStorage.removeItem(LS_VIEW_STATE);
  } catch {
    /* ignore */
  }
  cachedRaw = null;
  cached = DEFAULT_VIEW_STATE;
}

/* ------------------------------------------------------- 勾选集合小工具 */

function evictOldest<T>(scope: ScopedById<T>): ScopedById<T> {
  const keys = Object.keys(scope.byId);
  if (keys.length <= MAX_SCOPES) return scope;
  const byId = { ...scope.byId };
  for (const key of keys.slice(0, keys.length - MAX_SCOPES)) delete byId[key];
  return { ...scope, byId };
}

/** 写入某个题库作用域的选择；scope 里其它库的选择保持不变。 */
export function putScoped<T>(
  scope: ScopedById<T> | undefined,
  id: number | undefined,
  value: T
): ScopedById<T> {
  const next: ScopedById<T> = { ...(scope ?? { byId: {} }), byId: { ...(scope?.byId ?? {}) } };
  if (id !== undefined) next.lastId = id;
  if (value !== undefined) next.byId[String(id)] = value;
  return evictOldest(next);
}

/** 全选 → `{all:true}` 哨兵；零散勾选超过上限 → 放弃缓存（`{}`）。 */
export function captureIdSet(ids: number[], universeSize: number): IdSet {
  if (universeSize > 0 && ids.length >= universeSize) return { all: true };
  if (ids.length > MAX_IDS) return {};
  return { ids };
}

/** 读取某库的勾选；`all` 展开成完整 id 列表，无缓存返回 undefined。 */
export function readIdSet(
  scope: ScopedById<IdSet> | undefined,
  id: number | undefined,
  universe: number[]
): number[] | undefined {
  if (id === undefined) return undefined;
  const entry = scope?.byId[String(id)];
  if (!entry) return undefined;
  if (entry.all) return universe;
  return Array.isArray(entry.ids) ? entry.ids : undefined;
}

/** `checkedKeys === undefined`（全选当前库）→ `{mode:'all'}`。 */
export function captureKeySet(keys: string[] | undefined): KeySet {
  if (keys === undefined) return { mode: 'all' };
  return { mode: 'some', keys: keys.slice(0, MAX_KEYS) };
}

export function readKeySet(
  scope: ScopedById<KeySet> | undefined,
  id: number | undefined
): string[] | undefined | null {
  if (id === undefined) return null;
  const entry = scope?.byId[String(id)];
  if (!entry) return null;
  return entry.mode === 'all' ? undefined : entry.keys ?? [];
}
