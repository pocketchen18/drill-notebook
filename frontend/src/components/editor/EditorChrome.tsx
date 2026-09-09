import { useEffect, useState } from 'react';
import type { Editor } from '@tiptap/react';
import { NodeSelection, Selection, TextSelection } from '@tiptap/pm/state';
import type { EditorView } from '@tiptap/pm/view';
import type { Node as ProseMirrorNode } from '@tiptap/pm/model';
import { Bold, Code, GripVertical, Heading2, Italic, RemoveFormatting, Trash2, X } from 'lucide-react';
import type { OutlineSide } from '../../stores/uiStore';

/**
 * Native TipTap drag handle for atom blocks.  Keeping the handle in the
 * editor chrome gives every custom block the same interaction and makes the
 * affordance discoverable without adding another command menu.
 */
export function BlockDragHandle({ label = '拖动块' }: { label?: string }): JSX.Element {
  return (
    <span
      className="editor-block-drag-handle"
      data-drag-handle="true"
      draggable="true"
      aria-hidden="true"
      tabIndex={-1}
      aria-label={label}
      title={label}
      onClick={(event) => event.stopPropagation()}
    >
      <GripVertical size={15} aria-hidden="true" />
    </span>
  );
}

/** Clear a node selection before a custom block enters its own editor. */
export function exitNodeSelection(view: EditorView | undefined, getPos: (() => number) | undefined, node: ProseMirrorNode): void {
  if (!view || !getPos || !(view.state.selection instanceof NodeSelection)) return;
  try {
    const position = getPos();
    if (view.state.selection.from !== position) return;
    const afterNode = Math.min(position + node.nodeSize, view.state.doc.content.size);
    view.dispatch(view.state.tr.setSelection(Selection.near(view.state.doc.resolve(afterNode), 1)));
  } catch {
    // A node can be removed between pointer down and click; leave selection handling to ProseMirror.
  }
}

interface BubbleState {
  readonly visible: boolean;
  readonly top: number;
  readonly left: number;
  readonly nodeSelection: boolean;
}

/**
 * 选中文字时浮出的格式工具栏（Obsidian 式）。
 * 不用 TipTap 自带 BubbleMenu（其依赖 tippy.js，需真实布局、jsdom 无法运行且增加体积），
 * 改为按选区矩形做 fixed 定位的零依赖浮层：选区为空或取不到矩形时隐藏。
 */
export function EditorBubbleMenu({ editor }: { editor: Editor }): JSX.Element | null {
  const [bubble, setBubble] = useState<BubbleState>({ visible: false, top: 0, left: 0, nodeSelection: false });

  useEffect(() => {
    const hide = (): void => setBubble((prev) => (prev.visible ? { ...prev, visible: false } : prev));
    const update = (): void => {
      const { selection } = editor.state;
      if (selection.empty || !editor.isFocused) { hide(); return; }
      const nodeSelection = selection instanceof NodeSelection;
      const selectedNode = nodeSelection ? editor.view.nodeDOM(selection.from) : null;
      const sel = typeof window !== 'undefined' ? window.getSelection() : null;
      const range = selectedNode instanceof HTMLElement
        ? selectedNode.getBoundingClientRect()
        : (sel && sel.rangeCount > 0 ? sel.getRangeAt(0).getBoundingClientRect?.() : null);
      // jsdom 无布局，矩形恒为 0；生产环境据此定位浮层。
      if (!range || (range.width === 0 && range.height === 0 && range.top === 0)) { hide(); return; }
      const center = range.left + range.width / 2;
      const left = typeof window !== 'undefined'
        ? (() => {
            const edge = Math.min(112, Math.max(8, window.innerWidth / 2 - 8));
            return Math.max(edge, Math.min(window.innerWidth - edge, center));
          })()
        : center;
      setBubble({ visible: true, top: range.top, left, nodeSelection });
    };
    editor.on('selectionUpdate', update);
    editor.on('transaction', update);
    editor.on('focus', update);
    editor.on('blur', hide);
    window.addEventListener('scroll', hide, true);
    window.addEventListener('resize', hide);
    return () => {
      editor.off('selectionUpdate', update);
      editor.off('transaction', update);
      editor.off('focus', update);
      editor.off('blur', hide);
      window.removeEventListener('scroll', hide, true);
      window.removeEventListener('resize', hide);
    };
  }, [editor]);

  if (!bubble.visible) return null;

  return (
    <div
      className="editor-bubble"
      role="toolbar"
      aria-label={bubble.nodeSelection ? '选中块操作' : '选中文本格式'}
      // 阻止按钮点击让编辑器失焦而清空选区
      onMouseDown={(event) => event.preventDefault()}
      style={{ position: 'fixed', top: Math.max(bubble.top - 44, 4), left: bubble.left, transform: 'translateX(-50%)' }}
    >
      {!bubble.nodeSelection && <>
      <button type="button" className={editor.isActive('bold') ? 'is-active' : ''} onClick={() => editor.chain().focus().toggleBold().run()} aria-label="加粗" aria-pressed={editor.isActive('bold')} title="加粗"><Bold size={15} /></button>
      <button type="button" className={editor.isActive('italic') ? 'is-active' : ''} onClick={() => editor.chain().focus().toggleItalic().run()} aria-label="斜体" aria-pressed={editor.isActive('italic')} title="斜体"><Italic size={15} /></button>
      <button type="button" className={editor.isActive('code') ? 'is-active' : ''} onClick={() => editor.chain().focus().toggleCode().run()} aria-label="行内代码" aria-pressed={editor.isActive('code')} title="行内代码"><Code size={15} /></button>
      <button type="button" className={editor.isActive('heading', { level: 2 }) ? 'is-active' : ''} onClick={() => editor.chain().focus().toggleHeading({ level: 2 }).run()} aria-label="二级标题" aria-pressed={editor.isActive('heading', { level: 2 })} title="二级标题"><Heading2 size={15} /></button>
      <button type="button" onClick={() => editor.chain().focus().unsetAllMarks().clearNodes().run()} aria-label="清除格式" title="清除格式"><RemoveFormatting size={15} /></button>
      </>}
      <button type="button" className="is-danger" onClick={() => editor.chain().focus().deleteSelection().run()} aria-label="删除" title={bubble.nodeSelection ? '删除选中块' : '删除选中内容'}><Trash2 size={15} /></button>
    </div>
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

/**
 * 文档大纲：普通模式限于编辑器内，专注模式固定在窗口侧边，画布按同侧预留空间。
 * 点击条目按标题在文档中的顺序滚动定位。
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
    if (!open) return undefined;
    const onKeyDown = (event: KeyboardEvent): void => {
      if (event.key === 'Escape') onClose();
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
      // A concurrently updated document may invalidate the old heading position.
    }
    if (dom instanceof HTMLElement) dom.scrollIntoView({ behavior: 'smooth', block: 'start' });
  };

  return (
    <div className={`editor-outline editor-outline--${side}${focusMode ? ' editor-outline--focus' : ''}`} role="dialog" aria-label="文档大纲">
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
        <div className="editor-outline__empty">还没有标题。用工具栏「二级标题」或输入 ## 加空格创建。</div>
      )}
    </div>
  );
}
