import { forwardRef, useEffect, useState, type CSSProperties, type DragEventHandler, type KeyboardEvent as ReactKeyboardEvent, type MouseEventHandler } from 'react';
import type { Editor } from '@tiptap/react';
import { NodeSelection, Selection, TextSelection } from '@tiptap/pm/state';
import type { EditorView } from '@tiptap/pm/view';
import type { Node as ProseMirrorNode } from '@tiptap/pm/model';
import { GripVertical, X } from 'lucide-react';
import { describeAccelerators } from '../../lib/shortcuts';
import { useUiStore, type OutlineSide } from '../../stores/uiStore';

export { EditorBubbleMenu } from './EditorBubbleMenu';

export interface BlockDragHandleProps {
  label?: string;
  className?: string;
  style?: CSSProperties;
  active?: boolean;
  onClick?: MouseEventHandler<HTMLSpanElement>;
  onDragStart?: DragEventHandler<HTMLSpanElement>;
  onDragEnd?: DragEventHandler<HTMLSpanElement>;
}

/**
 * 块手柄。全编辑器只有一个实例跟随指针（见 BlockHandle）；每个块操作都有键盘快捷键，
 * 因此手柄只服务指针操作，不增加 Tab 停靠点。
 */
export const BlockDragHandle = forwardRef<HTMLSpanElement, BlockDragHandleProps>(function BlockDragHandle(
  { label = '拖动块', className = '', style, active = false, onClick, onDragStart, onDragEnd },
  ref
) {
  return (
    <span
      ref={ref}
      className={`editor-block-drag-handle${active ? ' is-active' : ''} ${className}`.trim()}
      style={style}
      draggable="true"
      aria-hidden="true"
      tabIndex={-1}
      title={label}
      onClick={(event) => {
        event.stopPropagation();
        onClick?.(event);
      }}
      onDragStart={onDragStart}
      onDragEnd={onDragEnd}
    >
      <GripVertical size={15} aria-hidden="true" />
    </span>
  );
});

/** 自定义块进入自身编辑态前先退出节点选区。 */
export function exitNodeSelection(view: EditorView | undefined, getPos: (() => number) | undefined, node: ProseMirrorNode): void {
  if (!view || !getPos || !(view.state.selection instanceof NodeSelection)) return;
  try {
    const position = getPos();
    if (view.state.selection.from !== position) return;
    const afterNode = Math.min(position + node.nodeSize, view.state.doc.content.size);
    view.dispatch(view.state.tr.setSelection(Selection.near(view.state.doc.resolve(afterNode), 1)));
  } catch {
    // 按下与点击之间节点可能已被删除，此时交给 ProseMirror 处理选区。
  }
}

/**
 * 块进入编辑态后把焦点交给源码框。节点视图首次渲染时 DOM 还没挂进文档，同步 focus 无效；
 * 插入命令里的 `chain().focus()` 又会在下一帧把焦点拉回正文，所以放到下一帧、排在它之后再聚焦。
 * 只在正文持有焦点时接管（插入或点击块之后），打开页面时文档里原有的空块不抢焦点。
 */
export function focusFieldSoon(getField: () => HTMLElement | null): () => void {
  const frame = window.requestAnimationFrame(() => {
    const field = getField();
    const editorRoot = field?.closest('.ProseMirror');
    if (!field?.isConnected || !editorRoot?.contains(document.activeElement) || document.activeElement === field) return;
    field.focus({ preventScroll: true });
    field.scrollIntoView?.({ block: 'nearest' });
  });
  return () => window.cancelAnimationFrame(frame);
}

/** 源码编辑框里的 Tab 插入两个空格，而不是把焦点移走（移走会触发失焦提交）。 */
export function insertSoftTab(event: ReactKeyboardEvent<HTMLTextAreaElement>, setDraft: (value: string) => void): boolean {
  if (event.key !== 'Tab' || event.shiftKey || event.ctrlKey || event.altKey || event.metaKey) return false;
  event.preventDefault();
  const area = event.currentTarget;
  area.setRangeText('  ', area.selectionStart, area.selectionEnd, 'end');
  setDraft(area.value);
  return true;
}

/** 块编辑条上的「完成」按钮，同时提示当前绑定的快捷键。 */
export function FinishButton({ onFinish }: { onFinish: () => void }): JSX.Element {
  const keys = useUiStore((state) => state.shortcutConfig.editorFinishBlock);
  const hint = keys.length ? describeAccelerators(keys) : '';
  return (
    <button type="button" className="node-chip-btn" aria-label="完成" title={hint ? `完成（${hint}）` : '完成'} onMouseDown={(event) => event.preventDefault()} onClick={onFinish}>
      完成
      {hint ? <kbd className="node-chip-kbd">{hint}</kbd> : null}
    </button>
  );
}

interface OutlineHeading {
  readonly level: number;
  readonly text: string;
  readonly index: number;
  readonly position: number;
}

function collectHeadings(editor: Editor): OutlineHeading[] {
  const items: OutlineHeading[] = [];
  editor.state.doc.descendants((node, position) => {
    if (node.type.name === 'heading') {
      const level = typeof node.attrs.level === 'number' ? node.attrs.level : 1;
      const text = node.textContent.trim();
      items.push({ level, text: text || '（无标题）', index: items.length, position });
    }
    return true;
  });
  return items;
}

function activeHeadingIndex(editor: Editor): number {
  const selectionPosition = editor.state.selection.from;
  let headingIndex = -1;
  let currentIndex = 0;
  editor.state.doc.descendants((node, position) => {
    if (node.type.name === 'heading') {
      if (position <= selectionPosition) headingIndex = currentIndex;
      currentIndex += 1;
    }
    return true;
  });
  return headingIndex;
}

// 越过这条线（吸顶工具栏加少许留白）的标题视为已读到。
const SCROLL_SPY_OFFSET = 96;

/** 顶部已越过阅读线的最后一个标题；没有则为 -1。 */
function headingInView(editor: Editor, headings: readonly OutlineHeading[]): number {
  let found = -1;
  for (const heading of headings) {
    const dom = editor.view.nodeDOM(heading.position);
    if (!(dom instanceof HTMLElement)) continue;
    const { top, height } = dom.getBoundingClientRect();
    if (height === 0 && top === 0) return -2; // 没有布局（测试环境）：保持按光标计算的结果
    if (top <= SCROLL_SPY_OFFSET) found = heading.index;
    else break;
  }
  return found;
}

/**
 * 文档大纲：普通模式限于编辑器内并随页面吸顶，专注模式固定在窗口侧边，画布按同侧预留空间。
 * 当前章节跟随光标，也跟随滚动位置。
 */
export function EditorOutline({ editor, open, onClose, focusMode = false, side = 'left' }: { editor: Editor; open: boolean; onClose: () => void; focusMode?: boolean; side?: OutlineSide }): JSX.Element | null {
  const [headings, setHeadings] = useState<OutlineHeading[]>(() => collectHeadings(editor));
  const [activeIndex, setActiveIndex] = useState(() => activeHeadingIndex(editor));

  useEffect(() => {
    if (!open) return undefined;
    const recompute = (): void => {
      setHeadings(collectHeadings(editor));
      setActiveIndex(activeHeadingIndex(editor));
    };
    const updateActive = (): void => setActiveIndex(activeHeadingIndex(editor));
    recompute();
    editor.on('update', recompute);
    editor.on('selectionUpdate', updateActive);
    editor.on('focus', updateActive);
    return () => {
      editor.off('update', recompute);
      editor.off('selectionUpdate', updateActive);
      editor.off('focus', updateActive);
    };
  }, [editor, open]);

  useEffect(() => {
    if (!open || headings.length === 0) return undefined;
    let frame = 0;
    const onScroll = (): void => {
      if (frame) return;
      frame = window.requestAnimationFrame(() => {
        frame = 0;
        const index = headingInView(editor, headings);
        if (index !== -2) setActiveIndex(index);
      });
    };
    window.addEventListener('scroll', onScroll, true);
    return () => {
      window.removeEventListener('scroll', onScroll, true);
      if (frame) window.cancelAnimationFrame(frame);
    };
  }, [editor, headings, open]);

  useEffect(() => {
    if (!open) return undefined;
    const onKeyDown = (event: KeyboardEvent): void => {
      // 已被 “/” 菜单、链接输入框等处理过的 Esc 不再关闭大纲。
      if (event.key === 'Escape' && !event.defaultPrevented) onClose();
    };
    window.addEventListener('keydown', onKeyDown);
    return () => window.removeEventListener('keydown', onKeyDown);
  }, [onClose, open]);

  if (!open) return null;

  const jumpTo = (heading: OutlineHeading): void => {
    setActiveIndex(heading.index);
    const dom = editor.view.nodeDOM(heading.position);
    try {
      const selection = TextSelection.near(editor.state.doc.resolve(heading.position + 1), 1);
      editor.view.dispatch(editor.state.tr.setSelection(selection));
      editor.view.focus();
    } catch {
      // 文档同时被更新时，旧的标题位置可能已失效。
    }
    if (dom instanceof HTMLElement) dom.scrollIntoView({ behavior: 'smooth', block: 'start' });
  };

  return (
    <div className={`editor-outline editor-outline--${side}${focusMode ? ' editor-outline--focus' : ''}`} role="dialog" aria-label="文档大纲">
      <div className="editor-outline__inner">
        <div className="editor-outline__head">
          <span>大纲</span>
          <button type="button" className="editor-outline__close" onClick={onClose} aria-label="关闭大纲"><X size={14} /></button>
        </div>
        {headings.length > 0 ? (
          <ul className="editor-outline__list" aria-label="文档标题">
            {headings.map((heading) => (
              <li key={heading.index}>
                <button
                  type="button"
                  className={`editor-outline__item editor-outline__item--h${heading.level}${activeIndex === heading.index ? ' is-active' : ''}`}
                  onClick={() => jumpTo(heading)}
                  aria-current={activeIndex === heading.index ? 'location' : undefined}
                  title={heading.text}
                >
                  {heading.text}
                </button>
              </li>
            ))}
          </ul>
        ) : (
          <div className="editor-outline__empty">还没有标题。输入 “/” 选择标题，或输入 ## 加空格创建。</div>
        )}
      </div>
    </div>
  );
}
