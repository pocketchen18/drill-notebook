import { useEffect, useRef, useState, type DragEvent as ReactDragEvent, type RefObject } from 'react';
import type { Editor } from '@tiptap/react';
import { NodeSelection, TextSelection, type Transaction } from '@tiptap/pm/state';
import type { EditorView } from '@tiptap/pm/view';
import { liftListItem, sinkListItem } from '@tiptap/pm/schema-list';
import { ArrowDown, ArrowUp, Copy, IndentDecrease, IndentIncrease, Plus, Trash2 } from 'lucide-react';
import { BlockDragHandle } from './EditorChrome';
import { MenuPopover, type MenuSection } from './MenuPopover';
import { BLOCK_KIND_OPTIONS } from './commandCatalog';
import {
  blockAtCoords,
  blockAtSelection,
  canMoveBlock,
  canTurnInto,
  currentTarget,
  deleteBlock,
  duplicateBlock,
  insertBlockBelow,
  isListItem,
  moveBlock,
  setBlockMenuTarget,
  turnInto,
  type BlockKind,
  type BlockTarget
} from './blockCommands';

const HANDLE_SIZE = 24;
const HANDLE_GAP = 4;
/** 拖动自动滚动的热区高度与每次 dragover 的最大滚动距离（dragover 约每 50ms 一次）。 */
const AUTOSCROLL_ZONE = 72;
const AUTOSCROLL_MAX_STEP = 28;

function scrollContainerOf(element: HTMLElement): HTMLElement {
  for (let node = element.parentElement; node; node = node.parentElement) {
    const { overflowY } = window.getComputedStyle(node);
    if ((overflowY === 'auto' || overflowY === 'scroll') && node.scrollHeight > node.clientHeight) return node;
  }
  return (document.scrollingElement as HTMLElement | null) ?? document.documentElement;
}

interface HandlePlacement {
  readonly top: number;
  readonly left: number;
}

function kindOfTarget(editor: Editor, target: BlockTarget): BlockKind | null {
  const { node } = target;
  if (node.type.name === 'paragraph') return 'paragraph';
  if (node.type.name === 'heading') return ([1, 2, 3] as const).includes(node.attrs.level) ? `heading${node.attrs.level as 1 | 2 | 3}` : null;
  if (node.type.name === 'codeBlock') return 'codeBlock';
  if (node.type.name === 'blockquote') return 'blockquote';
  if (node.type.name === 'taskItem') return 'taskList';
  if (node.type.name === 'listItem') {
    const parent = editor.state.doc.resolve(target.pos).parent.type.name;
    return parent === 'orderedList' ? 'orderedList' : 'bulletList';
  }
  return null;
}

/** 手柄与块的首行对齐，位于列表符号左侧。 */
function placementFor(editor: Editor, container: HTMLElement, target: BlockTarget): HandlePlacement | null {
  const dom = editor.view.nodeDOM(target.pos);
  if (!(dom instanceof HTMLElement)) return null;
  const rect = dom.getBoundingClientRect();
  const containerRect = container.getBoundingClientRect();
  const reference = isListItem(target.node) && dom.parentElement ? dom.parentElement.getBoundingClientRect().left : rect.left;
  const style = window.getComputedStyle(dom);
  const lineHeight = Number.parseFloat(style.lineHeight) || 28;
  const paddingTop = Number.parseFloat(style.paddingTop) || 0;
  const offset = rect.height < HANDLE_SIZE
    ? (rect.height - HANDLE_SIZE) / 2
    : Math.min(paddingTop + (lineHeight - HANDLE_SIZE) / 2, rect.height - HANDLE_SIZE);
  return {
    top: rect.top - containerRect.top + offset,
    left: reference - containerRect.left - HANDLE_SIZE - HANDLE_GAP
  };
}

function withSelectionInside(editor: Editor, target: BlockTarget): ReturnType<Editor['state']['apply']> {
  const { state } = editor;
  return state.apply(state.tr.setSelection(TextSelection.near(state.doc.resolve(Math.min(target.pos + 2, state.doc.content.size)))));
}

/**
 * 所有块共用一个手柄，位于正文栏左侧的槽位。拖动时把 NodeSelection 切片交给 ProseMirror 原生的 drop 处理，
 * 移动沿用编辑器现有的落点清理，并且只占一步撤销。
 */
export function BlockHandle({ editor, containerRef }: { editor: Editor; containerRef: RefObject<HTMLElement> }): JSX.Element | null {
  const [target, setTarget] = useState<BlockTarget | null>(null);
  const [placement, setPlacement] = useState<HandlePlacement | null>(null);
  const [menuOpen, setMenuOpen] = useState(false);
  const [typing, setTyping] = useState(false);
  const handleRef = useRef<HTMLSpanElement>(null);
  const targetRef = useRef<BlockTarget | null>(null);
  const menuOpenRef = useRef(false);
  const draggingRef = useRef(false);
  const draggingSliceRef = useRef<EditorView['dragging']>(null);
  targetRef.current = target;
  menuOpenRef.current = menuOpen;

  const show = (next: BlockTarget | null): void => {
    const container = containerRef.current;
    const live = next ? currentTarget(editor.state, next) : null;
    const nextPlacement = live && container ? placementFor(editor, container, live) : null;
    setTarget(nextPlacement ? live : null);
    setPlacement(nextPlacement);
  };
  const showRef = useRef(show);
  showRef.current = show;

  useEffect(() => {
    const container = containerRef.current;
    if (!container) return undefined;
    let frame = 0;
    let last: MouseEvent | null = null;
    const locate = (): void => {
      frame = 0;
      if (!last || menuOpenRef.current || draggingRef.current || editor.isDestroyed) return;
      const view = editor.view;
      const rect = view.dom.getBoundingClientRect();
      const style = window.getComputedStyle(view.dom);
      const right = rect.right - (Number.parseFloat(style.paddingRight) || 0) - 4;
      if (last.clientY < rect.top || last.clientY > rect.bottom) {
        showRef.current(null);
        return;
      }
      // 在正文栏右缘取点：最内层的块在那里铺满整行，嵌套列表项也能正确命中。
      showRef.current(blockAtCoords(view, { left: right, top: last.clientY }));
    };
    const onMove = (event: MouseEvent): void => {
      last = event;
      setTyping(false);
      if (!frame) frame = window.requestAnimationFrame(locate);
    };
    const onLeave = (event: MouseEvent): void => {
      if (menuOpenRef.current || draggingRef.current) return;
      if (event.relatedTarget instanceof Node && handleRef.current?.contains(event.relatedTarget)) return;
      showRef.current(null);
    };
    container.addEventListener('mousemove', onMove);
    container.addEventListener('mouseleave', onLeave);
    return () => {
      container.removeEventListener('mousemove', onMove);
      container.removeEventListener('mouseleave', onLeave);
      if (frame) window.cancelAnimationFrame(frame);
    };
  }, [containerRef, editor]);

  useEffect(() => {
    const coarse = typeof window.matchMedia === 'function' && window.matchMedia('(pointer: coarse)').matches;
    const onKeyDown = (): void => setTyping(true);
    // 文档变化时让手柄继续贴着原来的块；触屏没有悬停，手柄改为跟随光标所在块。
    const onTransaction = ({ transaction }: { transaction: Transaction }): void => {
      if (coarse && !menuOpenRef.current) {
        showRef.current(blockAtSelection(editor.state));
        return;
      }
      const current = targetRef.current;
      if (!current) return;
      if (!transaction.docChanged) {
        showRef.current(current);
        return;
      }
      const mapped = transaction.mapping.mapResult(current.pos, 1);
      showRef.current(mapped.deleted ? null : { pos: mapped.pos, node: current.node });
    };
    editor.view.dom.addEventListener('keydown', onKeyDown);
    editor.on('transaction', onTransaction);
    return () => {
      editor.view.dom.removeEventListener('keydown', onKeyDown);
      editor.off('transaction', onTransaction);
    };
  }, [editor]);

  useEffect(() => () => {
    if (!editor.isDestroyed) setBlockMenuTarget(editor.view, null);
  }, [editor]);

  const closeMenu = (): void => {
    setMenuOpen(false);
    if (!editor.isDestroyed) setBlockMenuTarget(editor.view, null);
  };

  // 菜单对应的块可能被删除或撤销掉，此时一并关闭菜单。
  useEffect(() => {
    if (menuOpen && !target) closeMenu();
  });

  const openMenu = (): void => {
    const live = currentTarget(editor.state, targetRef.current);
    if (!live) return;
    if (menuOpen) {
      closeMenu();
      return;
    }
    setBlockMenuTarget(editor.view, live.pos);
    setMenuOpen(true);
  };

  const onDragStart = (event: ReactDragEvent<HTMLSpanElement>): void => {
    const live = currentTarget(editor.state, targetRef.current);
    if (!live || !event.dataTransfer) return;
    const { view } = editor;
    closeMenu();
    draggingRef.current = true;
    const selection = NodeSelection.create(view.state.doc, live.pos);
    view.dispatch(view.state.tr.setSelection(selection));
    const { dom, text, slice } = view.serializeForClipboard(selection.content());
    event.dataTransfer.clearData();
    event.dataTransfer.setData('text/html', dom.innerHTML);
    event.dataTransfer.setData('text/plain', text);
    event.dataTransfer.effectAllowed = 'copyMove';
    const blockDom = view.nodeDOM(live.pos);
    if (blockDom instanceof HTMLElement) event.dataTransfer.setDragImage(blockDom, 0, 0);
    // ProseMirror 在 drop 时读取 `dragging`；带上 `node`，即使期间选区变化，移动时也只删除被拖动的块。
    const dragging = { slice, move: true, node: selection };
    view.dragging = dragging;
    draggingSliceRef.current = dragging;
  };

  const onDragEnd = (): void => {
    if (!draggingRef.current) return;
    draggingRef.current = false;
    // drop 会清空 `view.dragging`；拖动被取消时则需手动清掉，否则下一次外部拖入会误用旧切片。延迟与 ProseMirror 一致。
    const dragging = draggingSliceRef.current;
    draggingSliceRef.current = null;
    window.setTimeout(() => {
      if (!editor.isDestroyed && editor.view.dragging === dragging) editor.view.dragging = null;
    }, 50);
    showRef.current(null);
  };
  const dragEndRef = useRef(onDragEnd);
  dragEndRef.current = onDragEnd;

  // 移动后原块被删除，手柄随之卸载，收不到自己的 dragend；在 document 上兜底收尾，否则 draggingRef 常驻 true、手柄不再出现。
  useEffect(() => {
    const finish = (): void => dragEndRef.current();
    document.addEventListener('drop', finish, true);
    document.addEventListener('dragend', finish, true);
    return () => {
      document.removeEventListener('drop', finish, true);
      document.removeEventListener('dragend', finish, true);
    };
  }, []);

  // 拖动块时的边缘自动滚动。浏览器自带的只认滚动容器的物理边缘：顶部被吸顶工具栏盖住完全触发不了，底部热区也很窄。
  // 这里以“工具栏下沿 / 视口下沿”为边界，指针进入热区后按深入程度加速滚动；dragover 在指针静止时也会持续触发。
  useEffect(() => {
    const onDragOver = (event: DragEvent): void => {
      if (!draggingRef.current || editor.isDestroyed) return;
      const scroller = scrollContainerOf(editor.view.dom);
      const rect = scroller === document.scrollingElement
        ? { top: 0, bottom: window.innerHeight }
        : scroller.getBoundingClientRect();
      const dock = editor.view.dom.closest('.editor-shell')?.querySelector('.editor-toolbar-dock');
      const top = Math.max(rect.top, dock ? dock.getBoundingClientRect().bottom : rect.top);
      const bottom = Math.min(rect.bottom, window.innerHeight);
      const depth = event.clientY < top + AUTOSCROLL_ZONE
        ? -Math.min(1, (top + AUTOSCROLL_ZONE - event.clientY) / AUTOSCROLL_ZONE)
        : event.clientY > bottom - AUTOSCROLL_ZONE
          ? Math.min(1, (event.clientY - (bottom - AUTOSCROLL_ZONE)) / AUTOSCROLL_ZONE)
          : 0;
      if (depth) scroller.scrollTop += Math.round(depth * AUTOSCROLL_MAX_STEP);
    };
    document.addEventListener('dragover', onDragOver);
    return () => document.removeEventListener('dragover', onDragOver);
  }, [editor]);

  if (!target || !placement) return null;

  const live = currentTarget(editor.state, target);
  const kind = live ? kindOfTarget(editor, live) : null;
  const listItem = live ? isListItem(live.node) : false;
  const listCommand = (command: typeof sinkListItem): boolean => {
    if (!live) return false;
    const inside = withSelectionInside(editor, live);
    return command(live.node.type)(inside);
  };
  const runListCommand = (command: typeof sinkListItem): void => {
    if (!live) return;
    const { view } = editor;
    view.dispatch(view.state.tr.setSelection(TextSelection.near(view.state.doc.resolve(Math.min(live.pos + 2, view.state.doc.content.size)))));
    command(live.node.type)(view.state, view.dispatch);
    view.focus();
  };

  const sections: MenuSection[] = live ? [
    {
      key: 'insert',
      items: [{
        key: 'below',
        label: '在下方插入',
        icon: <Plus size={16} />,
        onSelect: () => {
          if (insertBlockBelow(editor, live)) editor.commands.insertContent('/');
        }
      }]
    },
    ...(canTurnInto(live) ? [{
      key: 'turn',
      title: '转换为',
      layout: 'grid' as const,
      items: BLOCK_KIND_OPTIONS.map((option) => ({
        key: option.kind,
        label: option.label,
        icon: <option.icon size={16} />,
        shortcut: option.shortcut,
        active: option.kind === kind,
        onSelect: () => { turnInto(editor, option.kind, live); }
      }))
    }] : []),
    {
      key: 'actions',
      items: [
        { key: 'duplicate', label: '复制副本', icon: <Copy size={16} />, shortcut: 'Ctrl+D', onSelect: () => { duplicateBlock(editor, live); } },
        { key: 'up', label: '上移', icon: <ArrowUp size={16} />, shortcut: 'Ctrl+Shift+↑', disabled: !canMoveBlock(editor.state, -1, live), onSelect: () => { moveBlock(editor, -1, live); } },
        { key: 'down', label: '下移', icon: <ArrowDown size={16} />, shortcut: 'Ctrl+Shift+↓', disabled: !canMoveBlock(editor.state, 1, live), onSelect: () => { moveBlock(editor, 1, live); } },
        ...(listItem ? [
          { key: 'indent', label: '缩进', icon: <IndentIncrease size={16} />, shortcut: 'Tab', disabled: !listCommand(sinkListItem), onSelect: () => runListCommand(sinkListItem) },
          { key: 'outdent', label: '取消缩进', icon: <IndentDecrease size={16} />, shortcut: 'Shift+Tab', disabled: !listCommand(liftListItem), onSelect: () => runListCommand(liftListItem) }
        ] : [])
      ]
    },
    {
      key: 'danger',
      items: [{ key: 'delete', label: '删除', icon: <Trash2 size={16} />, shortcut: 'Del', danger: true, onSelect: () => { deleteBlock(editor, live); } }]
    }
  ] : [];

  return (
    <>
      <BlockDragHandle
        ref={handleRef}
        label="拖动移动，点击打开块菜单"
        className={typing && !menuOpen ? 'is-hidden' : ''}
        active={menuOpen}
        style={{ top: placement.top, left: placement.left }}
        onClick={openMenu}
        onDragStart={onDragStart}
        onDragEnd={onDragEnd}
      />
      <MenuPopover
        open={menuOpen}
        onClose={closeMenu}
        getAnchor={() => handleRef.current?.getBoundingClientRect() ?? null}
        sections={sections}
        ariaLabel="块菜单"
        placement="bottom"
        align="start"
        triggerRef={handleRef}
        returnFocus={() => editor.commands.focus()}
        className="editor-block-menu"
      />
    </>
  );
}
