/**
 * 快捷键编辑器（REC-*）：多绑定 chip 展示与移除、录制流程、取消 / 清空、
 * 作用域规则拒绝提示、录制期间吞掉按键并置 recording 标志。
 */
import { afterEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import { useState } from 'react';
import { Message } from '@arco-design/web-react';
import { ShortcutRecorder } from './ShortcutRecorder';
import { GLOBAL_RULE, isShortcutRecording, type ShortcutRule } from '../lib/shortcuts';

const PLAIN: ShortcutRule = { plainKeys: true };

function Harness({ initial, rule = GLOBAL_RULE }: { initial: string[]; rule?: ShortcutRule }): JSX.Element {
  const [values, setValues] = useState<string[]>(initial);
  const [recording, setRecording] = useState(false);
  return (
    <ShortcutRecorder
      values={values}
      rule={rule}
      recording={recording}
      onStart={() => setRecording(true)}
      onStop={() => setRecording(false)}
      onAdd={(accelerator) => setValues((current) => [...current, accelerator])}
      onRemove={(accelerator) => setValues((current) => current.filter((entry) => entry !== accelerator))}
      onClear={() => setValues([])}
    />
  );
}

function chips(): Array<string | null> {
  return Array.from(document.querySelectorAll('.shortcut-key')).map((el) => el.textContent);
}

function press(key: string, init?: KeyboardEventInit): void {
  fireEvent.keyDown(window, { key, ...init });
}

function startRecording(): void {
  fireEvent.click(screen.getByRole('button', { name: /录入/ }));
}

afterEach(() => {
  expect(isShortcutRecording()).toBe(false);
  vi.restoreAllMocks();
});

describe('ShortcutRecorder', () => {
  it('REC-1: shows key chips for every binding with display labels', () => {
    render(<Harness initial={['Ctrl+Shift+L', 'ArrowLeft', 'F6']} />);
    expect(chips()).toEqual(['Ctrl', 'Shift', 'L', '←', 'F6']);
  });

  it('REC-2: shows 未设置 when unbound', () => {
    render(<Harness initial={[]} />);
    expect(screen.getByText('未设置')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '录入按键' })).toBeInTheDocument();
  });

  it('REC-3: records a combo, appends it and exits recording state', () => {
    render(<Harness initial={['Ctrl+J']} />);
    startRecording();
    expect(screen.getByText('按下按键…')).toBeInTheDocument();
    expect(isShortcutRecording()).toBe(true);
    press('k', { ctrlKey: true });
    expect(chips()).toEqual(['Ctrl', 'J', 'Ctrl', 'K']);
    expect(isShortcutRecording()).toBe(false);
    expect(screen.queryByText('按下按键…')).toBeNull();
  });

  it('REC-4: Escape cancels without changing the bindings', () => {
    render(<Harness initial={['Ctrl+J']} />);
    startRecording();
    press('Escape');
    expect(chips()).toEqual(['Ctrl', 'J']);
    expect(screen.queryByText('按下按键…')).toBeNull();
  });

  it('REC-5: Backspace clears every binding', () => {
    render(<Harness initial={['Ctrl+J', 'F6']} />);
    startRecording();
    press('Backspace');
    expect(screen.getByText('未设置')).toBeInTheDocument();
  });

  it('REC-6: lone modifier presses keep waiting for the combo', () => {
    render(<Harness initial={[]} />);
    startRecording();
    press('Control');
    press('Shift');
    expect(screen.getByText('按下按键…')).toBeInTheDocument();
    expect(isShortcutRecording()).toBe(true);
    press('l', { ctrlKey: true, shiftKey: true });
    expect(chips()).toEqual(['Ctrl', 'Shift', 'L']);
  });

  it('REC-7: global rule rejects a plain key with a hint and exits recording', () => {
    const warn = vi.spyOn(Message, 'warning');
    render(<Harness initial={['Ctrl+J']} />);
    startRecording();
    press('a');
    expect(screen.queryByText('按下按键…')).toBeNull();
    expect(chips()).toEqual(['Ctrl', 'J']);
    expect(warn).toHaveBeenCalledWith(expect.stringContaining('Ctrl 或 Alt'));
  });

  it('REC-8: page rule accepts plain and named keys', () => {
    render(<Harness initial={[]} rule={PLAIN} />);
    startRecording();
    press('Enter');
    expect(chips()).toEqual(['Enter']);
    startRecording();
    press('ArrowRight');
    expect(chips()).toEqual(['Enter', '→']);
  });

  it('REC-9: mainKeys rule only accepts the listed key, with or without modifiers', () => {
    const warn = vi.spyOn(Message, 'warning');
    render(<Harness initial={[]} rule={{ plainKeys: true, mainKeys: ['Enter'] }} />);
    startRecording();
    press('a');
    expect(warn).toHaveBeenCalledWith(expect.stringContaining('Enter'));
    expect(screen.getByText('未设置')).toBeInTheDocument();
    startRecording();
    press('Enter', { ctrlKey: true });
    expect(chips()).toEqual(['Ctrl', 'Enter']);
  });

  it('REC-10: the × button removes a single binding', () => {
    render(<Harness initial={['Ctrl+J', 'F6']} />);
    fireEvent.click(screen.getByRole('button', { name: '移除 F6' }));
    expect(chips()).toEqual(['Ctrl', 'J']);
  });

  it('REC-11: swallows keydown while recording so global handlers stay quiet', () => {
    const spy = vi.fn();
    window.addEventListener('keydown', spy);
    render(<Harness initial={[]} />);
    startRecording();
    press('j', { ctrlKey: true });
    // capture 阶段 stopImmediatePropagation：全局（bubble）监听不应收到
    expect(spy).not.toHaveBeenCalled();
    window.removeEventListener('keydown', spy);
  });
});
