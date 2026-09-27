import { useCallback, useEffect, useLayoutEffect, useRef, useState, type CSSProperties, type KeyboardEventHandler, type MouseEventHandler, type MutableRefObject, type ReactNode } from 'react';
import { createPortal } from 'react-dom';

export interface AnchorRect {
  readonly top: number;
  readonly bottom: number;
  readonly left: number;
  readonly right: number;
}

export type FloatingPlacement = 'top' | 'bottom';
export type FloatingAlign = 'start' | 'center' | 'end';

export interface FloatingPosition {
  readonly top: number;
  readonly left: number;
  readonly placement: FloatingPlacement;
}

const VIEWPORT_MARGIN = 8;

export function isEmptyRect(rect: AnchorRect): boolean {
  return rect.top === 0 && rect.bottom === 0 && rect.left === 0 && rect.right === 0;
}

/**
 * 把浮层放在锚点矩形旁：优先请求的一侧，另一侧空间更大时翻转，最后夹紧到视口内。
 */
export function computeFloatingPosition(
  anchor: AnchorRect,
  size: { width: number; height: number },
  viewport: { width: number; height: number },
  placement: FloatingPlacement = 'bottom',
  align: FloatingAlign = 'start',
  offset = 8,
  boundaryTop = 0
): FloatingPosition {
  const minTop = boundaryTop + VIEWPORT_MARGIN;
  const spaceAbove = anchor.top - minTop - offset;
  const spaceBelow = viewport.height - anchor.bottom - VIEWPORT_MARGIN - offset;
  let side = placement;
  if (side === 'top' && spaceAbove < size.height && spaceBelow > spaceAbove) side = 'bottom';
  else if (side === 'bottom' && spaceBelow < size.height && spaceAbove > spaceBelow) side = 'top';

  const rawTop = side === 'top' ? anchor.top - offset - size.height : anchor.bottom + offset;
  const top = Math.max(minTop, Math.min(rawTop, Math.max(minTop, viewport.height - VIEWPORT_MARGIN - size.height)));
  const width = anchor.right - anchor.left;
  const rawLeft = align === 'center' ? anchor.left + width / 2 - size.width / 2 : align === 'end' ? anchor.right - size.width : anchor.left;
  const left = Math.max(VIEWPORT_MARGIN, Math.min(rawLeft, Math.max(VIEWPORT_MARGIN, viewport.width - VIEWPORT_MARGIN - size.width)));
  return { top, left, placement: side };
}

export interface FloatingLayerProps {
  open: boolean;
  getAnchor: () => AnchorRect | null;
  children: ReactNode;
  placement?: FloatingPlacement;
  align?: FloatingAlign;
  offset?: number;
  /** 锚点没有布局或已离开视口时隐藏（用于跟随选区的浮层）。 */
  hideWhenDetached?: boolean;
  /** 可见区域的上边界（例如吸顶工具栏的底边）；锚点被它盖住视为离开视口。 */
  getBoundaryTop?: () => number;
  className?: string;
  role?: string;
  ariaLabel?: string;
  id?: string;
  layerRef?: MutableRefObject<HTMLDivElement | null>;
  onMouseDown?: MouseEventHandler<HTMLDivElement>;
  onKeyDown?: KeyboardEventHandler<HTMLDivElement>;
}

/**
 * 编辑器浮层统一渲染到 `document.body`：编辑画布是尺寸容器，会成为 fixed 后代的包含块，
 * 编辑框也会裁剪溢出内容。
 */
export function FloatingLayer({
  open,
  getAnchor,
  children,
  placement = 'bottom',
  align = 'start',
  offset = 8,
  hideWhenDetached = false,
  getBoundaryTop,
  className = '',
  role,
  ariaLabel,
  id,
  layerRef,
  onMouseDown,
  onKeyDown
}: FloatingLayerProps): JSX.Element | null {
  const elementRef = useRef<HTMLDivElement | null>(null);
  const getAnchorRef = useRef(getAnchor);
  getAnchorRef.current = getAnchor;
  const getBoundaryRef = useRef(getBoundaryTop);
  getBoundaryRef.current = getBoundaryTop;
  const [position, setPosition] = useState<FloatingPosition | null>(null);

  const measure = useCallback(() => {
    const element = elementRef.current;
    const anchor = getAnchorRef.current();
    const viewport = { width: window.innerWidth, height: window.innerHeight };
    const boundaryTop = getBoundaryRef.current?.() ?? 0;
    const hidden = !element || !anchor || (hideWhenDetached && (isEmptyRect(anchor) || anchor.bottom < boundaryTop || anchor.top > viewport.height));
    if (hidden) {
      setPosition((previous) => (previous === null ? previous : null));
      return;
    }
    const next = computeFloatingPosition(anchor, { width: element.offsetWidth, height: element.offsetHeight }, viewport, placement, align, offset, boundaryTop);
    setPosition((previous) => (previous && previous.top === next.top && previous.left === next.left && previous.placement === next.placement ? previous : next));
  }, [align, hideWhenDetached, offset, placement]);

  // 每次渲染后重新测量：锚点通常随编辑器状态移动。
  useLayoutEffect(() => {
    if (open) measure();
  });

  useEffect(() => {
    if (!open) return undefined;
    let frame = 0;
    const schedule = (): void => {
      if (frame) return;
      frame = window.requestAnimationFrame(() => {
        frame = 0;
        measure();
      });
    };
    window.addEventListener('scroll', schedule, true);
    window.addEventListener('resize', schedule);
    return () => {
      window.removeEventListener('scroll', schedule, true);
      window.removeEventListener('resize', schedule);
      if (frame) window.cancelAnimationFrame(frame);
    };
  }, [measure, open]);

  if (!open || typeof document === 'undefined') return null;

  const style: CSSProperties = position
    ? { position: 'fixed', top: position.top, left: position.left }
    : { position: 'fixed', top: 0, left: 0, visibility: 'hidden' };

  return createPortal(
    <div
      ref={(element) => {
        elementRef.current = element;
        if (layerRef) layerRef.current = element;
      }}
      id={id}
      className={`editor-float ${className}`.trim()}
      data-placement={position?.placement}
      style={style}
      role={role}
      aria-label={ariaLabel}
      onMouseDown={onMouseDown}
      onKeyDown={onKeyDown}
    >
      {children}
    </div>,
    document.body
  );
}

export function rectOf(element: Element | null | undefined): AnchorRect | null {
  return element ? element.getBoundingClientRect() : null;
}

/** 编辑器吸顶工具栏的底边：它下方才是正文的可见区域。 */
export function editorChromeBottom(dom: Element | null | undefined): number {
  const dock = dom?.closest('.editor-canvas')?.querySelector('.editor-toolbar-dock');
  return dock ? Math.max(0, dock.getBoundingClientRect().bottom) : 0;
}
