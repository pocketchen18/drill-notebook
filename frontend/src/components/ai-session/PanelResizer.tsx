import type { KeyboardEvent, PointerEvent as ReactPointerEvent } from 'react';

interface Props {
  label: string;
  /** 分隔条在面板的哪一侧：会话栏的分隔条在右边，详情栏的在左边。 */
  edge: 'right' | 'left';
  value: number;
  min: number;
  max: number;
  /** 面板最多占助手窗口宽度的比例，窗口变窄时不会把对话区挤没。 */
  maxRatio: number;
  defaultValue: number;
  onChange: (width: number) => void;
  /** 拖动结束、键盘调整或双击重置后保存。 */
  onCommit: (width: number) => void;
}

const KEY_STEP = 16;

/** 面板分隔条：拖动调整宽度，方向键微调，双击恢复默认。挂在面板内部，贴着面板边缘。 */
export function PanelResizer({ label, edge, value, min, max, maxRatio, defaultValue, onChange, onCommit }: Props): JSX.Element {
  const limit = (handle: HTMLElement): number => {
    const container = handle.closest<HTMLElement>('.ai-window');
    return Math.max(min, Math.min(max, Math.floor((container?.clientWidth || window.innerWidth) * maxRatio)));
  };
  const clamp = (handle: HTMLElement, width: number): number => Math.round(Math.max(min, Math.min(limit(handle), width)));

  const onPointerDown = (event: ReactPointerEvent<HTMLDivElement>): void => {
    if (event.button !== 0) return;
    event.preventDefault();
    const handle = event.currentTarget;
    // 以面板当前的实际宽度为起点：窗口变窄后实际宽度可能小于记住的值。
    const start = handle.parentElement?.getBoundingClientRect().width || value;
    const startX = event.clientX;
    let latest = clamp(handle, start);
    handle.classList.add('is-dragging');
    const move = (next: PointerEvent): void => {
      const delta = next.clientX - startX;
      latest = clamp(handle, edge === 'right' ? start + delta : start - delta);
      onChange(latest);
    };
    const settle = (next: PointerEvent, usePosition: boolean): void => {
      // 快速拖动时最后一次 pointermove 可能被合并掉：抬手的位置才算最终宽度，界面也要跟上。
      if (usePosition) {
        const delta = next.clientX - startX;
        latest = clamp(handle, edge === 'right' ? start + delta : start - delta);
        onChange(latest);
      }
      window.removeEventListener('pointermove', move);
      window.removeEventListener('pointerup', up);
      window.removeEventListener('pointercancel', cancel);
      handle.classList.remove('is-dragging');
      onCommit(latest);
    };
    const up = (next: PointerEvent): void => settle(next, true);
    const cancel = (next: PointerEvent): void => settle(next, false);
    // 挂在 window 上：指针离开这条 6px 宽的分隔条（拖动过程中必然会离开）也能继续跟手。
    window.addEventListener('pointermove', move);
    window.addEventListener('pointerup', up);
    window.addEventListener('pointercancel', cancel);
  };

  const onKeyDown = (event: KeyboardEvent<HTMLDivElement>): void => {
    if (event.key !== 'ArrowLeft' && event.key !== 'ArrowRight') return;
    event.preventDefault();
    const grow = (event.key === 'ArrowRight') === (edge === 'right');
    const next = clamp(event.currentTarget, value + (grow ? KEY_STEP : -KEY_STEP));
    onChange(next); onCommit(next);
  };

  return <div className={`ai-window-resizer is-${edge}`} role="separator" aria-orientation="vertical" tabIndex={0}
    aria-label={label} aria-valuemin={min} aria-valuemax={max} aria-valuenow={Math.round(value)}
    title="拖动调整宽度，双击恢复默认" onPointerDown={onPointerDown} onKeyDown={onKeyDown}
    onDoubleClick={() => { onChange(defaultValue); onCommit(defaultValue); }} />;
}
