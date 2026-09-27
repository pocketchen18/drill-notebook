import { afterEach, describe, expect, it, vi } from 'vitest';

afterEach(() => { vi.restoreAllMocks(); });

describe('outline-side preference', () => {
  it.each([null, 'invalid', 'left', 'right'])('restores %s safely on a fresh store', async (saved) => {
    if (saved !== null) localStorage.setItem('ui.outlineSide', saved);
    vi.resetModules();
    const { useUiStore } = await import('./uiStore');
    expect(useUiStore.getState().outlineSide).toBe(saved === 'right' ? 'right' : 'left');
    expect(useUiStore.getState().notebookPanelsSwapped).toBe(false);
  });

  it.each([null, 'false', 'true', '1', 'invalid'])('restores notebook panel swap %s safely on a fresh store', async (saved) => {
    if (saved !== null) localStorage.setItem('ui.notebookPanelsSwapped', saved);
    vi.resetModules();
    const { useUiStore } = await import('./uiStore');
    expect(useUiStore.getState().notebookPanelsSwapped).toBe(saved === 'true' || saved === '1');
  });

  it('keeps working when preference storage is unavailable', async () => {
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => { throw new Error('storage disabled'); });
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new Error('storage disabled'); });
    vi.resetModules();
    const { useUiStore } = await import('./uiStore');
    expect(useUiStore.getState().outlineSide).toBe('left');
    expect(() => useUiStore.getState().setOutlineSide('right')).not.toThrow();
    expect(useUiStore.getState().outlineSide).toBe('right');
    expect(useUiStore.getState().notebookPanelsSwapped).toBe(false);
    expect(() => useUiStore.getState().setNotebookPanelsSwapped(true)).not.toThrow();
    expect(useUiStore.getState().notebookPanelsSwapped).toBe(true);
  });

  it('persists the notebook panel swap independently from the focus outline side', async () => {
    vi.resetModules();
    const { useUiStore } = await import('./uiStore');
    useUiStore.getState().setOutlineSide('right');
    useUiStore.getState().setNotebookPanelsSwapped(true);
    expect(localStorage.getItem('ui.outlineSide')).toBe('right');
    expect(localStorage.getItem('ui.notebookPanelsSwapped')).toBe('true');
    expect(useUiStore.getState().outlineSide).toBe('right');
    expect(useUiStore.getState().notebookPanelsSwapped).toBe(true);
  });
});
