import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import {
  captureIdSet,
  captureKeySet,
  clearViewState,
  flushViewState,
  LS_VIEW_STATE,
  MAX_IDS,
  MAX_SCOPES,
  normalizeViewState,
  persistViewState,
  putScoped,
  readIdSet,
  readKeySet,
  readLastRoute,
  readPageSlice,
  readViewState,
  recordRoute
} from './viewState';
import { LS_REMEMBER_VIEW_STATE, writeBoolPref } from './sessionPrefs';

const seed = (value: unknown): void => {
  localStorage.setItem(LS_VIEW_STATE, typeof value === 'string' ? value : JSON.stringify(value));
};

describe('viewState', () => {
  beforeEach(() => {
    localStorage.clear();
    clearViewState();
  });
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('returns defaults when nothing is cached', () => {
    expect(readViewState()).toEqual({ version: 1, pages: {} });
    expect(readLastRoute()).toBeUndefined();
    expect(readPageSlice('banks')).toEqual({});
  });

  it('survives corrupt payloads and unknown fields', () => {
    seed('{not json');
    expect(readViewState().pages).toEqual({});
    seed({ version: 1, lastRoute: '/nope', pages: { bogus: { x: 1 }, banks: { selectedId: -3, nope: 'x' } } });
    const state = readViewState();
    expect(state.lastRoute).toBeUndefined();
    expect(state.pages.banks).toBeUndefined();
    expect(readPageSlice('banks')).toEqual({});
    expect((state.pages as Record<string, unknown>).bogus).toBeUndefined();
  });

  it('normalizes ids, enums and truncates oversized arrays', () => {
    const normalized = normalizeViewState({
      lastRoute: '/practice',
      pages: {
        notebooks: { notebookId: 7, pageId: '8', focusMode: 'yes', selectedPageIds: [1, 'x', 2.5, 3] },
        practice: { tab: 'nope', memorizeTarget: 'knowledge', selectors: { byId: { '1': { types: ['single'] } } } },
        calendar: { viewYear: 2030, viewMonth: 11 }
      }
    });
    expect(normalized.lastRoute).toBe('/practice');
    expect(normalized.pages.notebooks).toEqual({ notebookId: 7, selectedPageIds: [1, 3] });
    expect(normalized.pages.practice.memorizeTarget).toBe('knowledge');
    expect(normalized.pages.practice.tab).toBeUndefined();
    expect(normalized.pages.calendar).toEqual({ viewYear: 2030, viewMonth: 11 });
    const big = normalizeViewState({ pages: { wrong: { selectedIds: Array.from({ length: MAX_IDS + 50 }, (_, i) => i + 1) } } });
    expect(big.pages.wrong.selectedIds).toHaveLength(MAX_IDS);
  });

  it('records only whitelisted pathnames and folds practice aliases', () => {
    recordRoute('/quiz?autoStart=1&dayQueue=1&questionIds=1,2');
    expect(readLastRoute()).toBe('/practice');
    recordRoute('/memorize');
    expect(readLastRoute()).toBe('/practice');
    recordRoute('/calendar?date=2026-01-05');
    expect(readLastRoute()).toBe('/calendar');
    recordRoute('/today');
    recordRoute('/definitely-not-a-page');
    expect(readLastRoute()).toBe('/calendar');
  });

  it('merges partial patches and persists them on flush', () => {
    persistViewState('practice', { tab: 'memorize' });
    persistViewState('practice', { memorizeTarget: 'knowledge' });
    expect(readPageSlice('practice')).toEqual({ tab: 'memorize', memorizeTarget: 'knowledge' });
    flushViewState();
    expect(JSON.parse(localStorage.getItem(LS_VIEW_STATE) ?? '{}')).toEqual({
      version: 1,
      pages: { practice: { tab: 'memorize', memorizeTarget: 'knowledge' } }
    });
  });

  it('skips redundant writes', () => {
    const spy = vi.spyOn(Storage.prototype, 'setItem');
    persistViewState('banks', { selectedId: 5 });
    flushViewState();
    const first = spy.mock.calls.length;
    persistViewState('banks', { selectedId: 5 });
    flushViewState();
    expect(spy.mock.calls.length).toBe(first);
  });

  it('reads and writes nothing when the setting is off', () => {
    persistViewState('banks', { selectedId: 5 });
    flushViewState();
    writeBoolPref(LS_REMEMBER_VIEW_STATE, false);
    expect(readLastRoute()).toBeUndefined();
    expect(readPageSlice('banks')).toEqual({});
    persistViewState('banks', { selectedId: 9 });
    flushViewState();
    writeBoolPref(LS_REMEMBER_VIEW_STATE, true);
    expect(readPageSlice('banks').selectedId).toBe(5);
  });

  it('clearViewState wipes storage and pending patches', () => {
    seed({ version: 1, lastRoute: '/knowledge', pages: { wrong: { selectedIds: [1] } } });
    persistViewState('banks', { selectedId: 3 });
    clearViewState();
    expect(localStorage.getItem(LS_VIEW_STATE)).toBeNull();
    expect(readLastRoute()).toBeUndefined();
    expect(readPageSlice('banks')).toEqual({});
    flushViewState();
    expect(localStorage.getItem(LS_VIEW_STATE)).toBeNull();
  });

  it('collapses whole-bank selections into an all sentinel', () => {
    expect(captureIdSet([1, 2, 3], 3)).toEqual({ all: true });
    expect(readIdSet({ byId: { '7': { all: true } } }, 7, [9, 10])).toEqual([9, 10]);
    expect(captureIdSet(Array.from({ length: MAX_IDS + 1 }, (_, i) => i + 1), 9999)).toEqual({});
    expect(readIdSet({ byId: { '7': {} } }, 7, [1])).toBeUndefined();
    expect(readIdSet({ byId: { '7': { ids: [1, 2] } } }, 8, [1])).toBeUndefined();
  });

  it('keeps the 全选 vs 清空 distinction for tree checks', () => {
    expect(captureKeySet(undefined)).toEqual({ mode: 'all' });
    expect(captureKeySet([])).toEqual({ mode: 'some', keys: [] });
    const scope = { byId: { '1': captureKeySet(undefined), '2': captureKeySet([]) } };
    expect(readKeySet(scope, 1)).toBeUndefined();
    expect(readKeySet(scope, 2)).toEqual([]);
    expect(readKeySet(scope, 3)).toBeNull();
  });

  it('keeps other banks scoped and evicts the oldest beyond the limit', () => {
    let scope = putScoped(undefined, 1, { ids: [1] });
    scope = putScoped(scope, 2, { ids: [2] });
    expect(scope.lastId).toBe(2);
    expect(Object.keys(scope.byId)).toEqual(['1', '2']);
    for (let id = 3; id <= 3 + MAX_SCOPES; id += 1) scope = putScoped(scope, id, { ids: [id] });
    expect(Object.keys(scope.byId)).toHaveLength(MAX_SCOPES);
    expect(scope.byId['1']).toBeUndefined();
    expect(scope.byId[String(3 + MAX_SCOPES)]).toEqual({ ids: [3 + MAX_SCOPES] });
  });
});
