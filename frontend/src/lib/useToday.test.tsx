import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { act, renderHook } from '@testing-library/react';
import { useToday } from './useToday';

describe('useToday', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date(2026, 8, 3, 23, 59, 0));
  });
  afterEach(() => {
    vi.useRealTimers();
  });

  it('follows the real date when the poll crosses midnight', () => {
    const { result } = renderHook(() => useToday());
    expect(result.current).toBe('2026-09-03');
    act(() => {
      vi.setSystemTime(new Date(2026, 8, 4, 0, 0, 30));
      vi.advanceTimersByTime(60_000);
    });
    expect(result.current).toBe('2026-09-04');
  });

  it('recalibrates on window focus without waiting for the next tick', () => {
    const { result } = renderHook(() => useToday());
    act(() => {
      vi.setSystemTime(new Date(2026, 8, 4, 0, 0, 5));
    });
    expect(result.current).toBe('2026-09-03');
    act(() => {
      window.dispatchEvent(new Event('focus'));
    });
    expect(result.current).toBe('2026-09-04');
  });
});
