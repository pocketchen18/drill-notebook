import { useEffect, useRef, type KeyboardEvent as ReactKeyboardEvent, type ReactNode, type RefObject } from 'react';
import { FloatingLayer, type AnchorRect, type FloatingAlign, type FloatingPlacement } from './floating';

export interface MenuItem {
  readonly key: string;
  readonly label: string;
  readonly icon?: ReactNode;
  readonly shortcut?: string;
  readonly active?: boolean;
  readonly disabled?: boolean;
  readonly danger?: boolean;
  readonly onSelect: () => void;
}

export interface MenuSection {
  readonly key: string;
  readonly title?: string;
  /** `grid` 渲染为图标格，标签仍作为无障碍名称保留。 */
  readonly layout?: 'list' | 'grid';
  readonly items: readonly MenuItem[];
}

export interface MenuPopoverProps {
  open: boolean;
  onClose: () => void;
  getAnchor: () => AnchorRect | null;
  sections: readonly MenuSection[];
  ariaLabel: string;
  placement?: FloatingPlacement;
  align?: FloatingAlign;
  /**
   * 从选区浮条打开的菜单不能抢焦点，否则编辑器失焦，浮条会在菜单下方关闭。
   */
  keepEditorFocus?: boolean;
  /** 触发按钮自己负责开关菜单，按在它上面不算“点击外部”。 */
  triggerRef?: RefObject<HTMLElement>;
  /** Esc 关闭后焦点的去向，默认回到触发按钮。 */
  returnFocus?: () => void;
  className?: string;
}

const ITEM_SELECTOR = '[role="menuitem"]:not(:disabled)';

export function MenuPopover({
  open,
  onClose,
  getAnchor,
  sections,
  ariaLabel,
  placement = 'bottom',
  align = 'start',
  keepEditorFocus = false,
  triggerRef,
  returnFocus,
  className = ''
}: MenuPopoverProps): JSX.Element | null {
  const layerRef = useRef<HTMLDivElement | null>(null);
  const onCloseRef = useRef(onClose);
  onCloseRef.current = onClose;

  useEffect(() => {
    if (!open || keepEditorFocus) return undefined;
    const frame = window.requestAnimationFrame(() => {
      const layer = layerRef.current;
      const target = layer?.querySelector<HTMLElement>(`${ITEM_SELECTOR}.is-active`) ?? layer?.querySelector<HTMLElement>(ITEM_SELECTOR);
      target?.focus();
    });
    return () => window.cancelAnimationFrame(frame);
  }, [keepEditorFocus, open]);

  useEffect(() => {
    if (!open) return undefined;
    const onPointerDown = (event: MouseEvent): void => {
      const target = event.target as Node | null;
      if (target && (layerRef.current?.contains(target) || triggerRef?.current?.contains(target))) return;
      onCloseRef.current();
    };
    document.addEventListener('mousedown', onPointerDown, true);
    return () => document.removeEventListener('mousedown', onPointerDown, true);
  }, [open, triggerRef]);

  const onKeyDown = (event: ReactKeyboardEvent<HTMLDivElement>): void => {
    const items = Array.from(layerRef.current?.querySelectorAll<HTMLElement>(ITEM_SELECTOR) ?? []);
    const index = items.indexOf(document.activeElement as HTMLElement);
    const focusAt = (next: number): void => {
      if (!items.length) return;
      items[(next + items.length) % items.length]?.focus();
    };
    switch (event.key) {
      case 'ArrowDown':
      case 'ArrowRight':
        event.preventDefault();
        focusAt(index + 1);
        break;
      case 'ArrowUp':
      case 'ArrowLeft':
        event.preventDefault();
        focusAt(index < 0 ? items.length - 1 : index - 1);
        break;
      case 'Home':
        event.preventDefault();
        focusAt(0);
        break;
      case 'End':
        event.preventDefault();
        focusAt(items.length - 1);
        break;
      case 'Escape':
        event.preventDefault();
        event.stopPropagation();
        onClose();
        if (returnFocus) returnFocus();
        else triggerRef?.current?.focus();
        break;
      case 'Tab':
        onClose();
        break;
      default:
        break;
    }
  };

  return (
    <FloatingLayer
      open={open}
      getAnchor={getAnchor}
      placement={placement}
      align={align}
      className={`editor-menu ${className}`.trim()}
      role="menu"
      ariaLabel={ariaLabel}
      layerRef={layerRef}
      onKeyDown={onKeyDown}
      onMouseDown={keepEditorFocus ? (event) => event.preventDefault() : undefined}
    >
      {sections.filter((section) => section.items.length > 0).map((section) => (
        <div key={section.key} className={`editor-menu__section${section.layout === 'grid' ? ' is-grid' : ''}`} role="group" aria-label={section.title}>
          {section.title ? <div className="editor-menu__title" aria-hidden="true">{section.title}</div> : null}
          <div className={section.layout === 'grid' ? 'editor-menu__grid' : 'editor-menu__list'}>
            {section.items.map((item) => (
              <button
                key={item.key}
                type="button"
                role="menuitem"
                tabIndex={-1}
                className={`${section.layout === 'grid' ? 'editor-menu__cell' : 'editor-menu__item'}${item.active ? ' is-active' : ''}${item.danger ? ' is-danger' : ''}`}
                disabled={item.disabled}
                aria-label={item.label}
                aria-current={item.active ? 'true' : undefined}
                title={section.layout === 'grid' ? (item.shortcut ? `${item.label}（${item.shortcut}）` : item.label) : undefined}
                onClick={() => {
                  onClose();
                  item.onSelect();
                }}
              >
                {item.icon ? <span className="editor-menu__icon" aria-hidden="true">{item.icon}</span> : null}
                {section.layout === 'grid' ? null : <span className="editor-menu__label">{item.label}</span>}
                {section.layout !== 'grid' && item.shortcut ? <kbd className="editor-menu__shortcut">{item.shortcut}</kbd> : null}
              </button>
            ))}
          </div>
        </div>
      ))}
    </FloatingLayer>
  );
}
