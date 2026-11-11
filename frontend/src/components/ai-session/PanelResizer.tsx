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
    try { handle.setPointerCapture(event.pointerId); } catch { /* 没有捕获也能靠元素自身的事件完成短距离拖动 */ }
    handle.classList.add('is-dragging');
    const move = (next: PointerEvent): void => {
      const delta = next.clientX - startX;
      latest = clamp(handle, edge === 'right' ? start + delta : start - delta);
      onChange(latest);
    };
    const finish = (): void => {
      handle.removeEventListener('pointermove', move);
      handle.removeEventListener('pointerup', finish);
      handle.removeEventListener('pointercancel', finish);
      handle.classList.remove('is-dragging');
      onCommit(latest);
    };
    handle.addEventListener('pointermove', move);
    handle.addEventListener('pointerup', finish);
    handle.addEventListener('pointercancel', finish);
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
