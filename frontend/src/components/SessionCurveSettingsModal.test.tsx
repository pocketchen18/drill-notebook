import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { act } from 'react';
import { SessionCurveSettingsModal } from './SessionCurveSettingsModal';
import { DEFAULT_SESSION_CURVE_CONFIG, readSessionCurveConfig, writeSessionCurveConfig } from '../lib/sessionCurve';

const LS_KEY = 'session.curveConfig';

describe('SessionCurveSettingsModal', () => {
  beforeEach(() => {
    localStorage.removeItem(LS_KEY);
    writeSessionCurveConfig({ ...DEFAULT_SESSION_CURVE_CONFIG });
  });
  afterEach(() => {
    localStorage.removeItem(LS_KEY);
  });

  it('renders curve options and the default preset as active', () => {
    render(<SessionCurveSettingsModal visible onClose={vi.fn()} />);
    expect(screen.getByText('背诵记忆曲线设置')).toBeInTheDocument();
    expect(screen.getByText('循环轮数')).toBeInTheDocument();
    expect(screen.getByText('错题重复策略')).toBeInTheDocument();
    expect(screen.getByText('组末复习')).toBeInTheDocument();
    expect(screen.getByText('推荐 · 三轮循环')).toBeInTheDocument();
  });

  it('applies a preset and saves it to localStorage on 保存', async () => {
    const onSaved = vi.fn();
    const onClose = vi.fn();
    render(<SessionCurveSettingsModal visible onClose={onClose} onSaved={onSaved} />);
    fireEvent.click(screen.getByText('强化 · 五轮循环'));
    fireEvent.click(screen.getByText('保存'));
    await waitFor(() => expect(onSaved).toHaveBeenCalled());
    expect(onClose).toHaveBeenCalled();
    expect(readSessionCurveConfig().loops).toBe(5);
    expect(readSessionCurveConfig().nextRoundOrder).toBe('wrongFirst');
  });

  it('reloads latest config from localStorage each time it opens', async () => {
    writeSessionCurveConfig({ ...DEFAULT_SESSION_CURVE_CONFIG, loops: 7 });
    let utils: ReturnType<typeof render> | undefined;
    act(() => {
      utils = render(<SessionCurveSettingsModal visible={false} onClose={vi.fn()} />);
    });
    writeSessionCurveConfig({ ...DEFAULT_SESSION_CURVE_CONFIG, loops: 2 });
    act(() => {
      utils!.rerender(<SessionCurveSettingsModal visible onClose={vi.fn()} />);
    });
    await waitFor(() => {
      const numberInputs = Array.from(document.querySelectorAll('input.arco-input')) as HTMLInputElement[];
      expect(numberInputs.some((input) => input.value === '2')).toBe(true);
    });
  });
});
