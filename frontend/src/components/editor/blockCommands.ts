import { Extension, type ChainedCommands, type Editor } from '@tiptap/core';
import type { Node as ProseMirrorNode, ResolvedPos } from '@tiptap/pm/model';
import { NodeSelection, Plugin, PluginKey, Selection, TextSelection, type EditorState, type Transaction } from '@tiptap/pm/state';
import { Decoration, DecorationSet, type EditorView } from '@tiptap/pm/view';

/** 可移动的单位：顶层块，或列表中的列表项 / 待办项。 */
export interface BlockTarget {
  readonly pos: number;
  readonly node: ProseMirrorNode;
}

export type BlockKind = 'paragraph' | 'heading1' | 'heading2' | 'heading3' | 'bulletList' | 'orderedList' | 'taskList' | 'blockquote' | 'codeBlock';

const LIST_ITEM_TYPES = new Set(['listItem', 'taskItem']);

export function isListItem(node: ProseMirrorNode): boolean {
  return LIST_ITEM_TYPES.has(node.type.name);
}

function blockFromResolved($pos: ResolvedPos): BlockTarget | null {
  for (let depth = $pos.depth; depth >= 1; depth -= 1) {
    const node = $pos.node(depth);
    if (isListItem(node)) return { pos: $pos.before(depth), node };
  }
  if ($pos.depth >= 1) return { pos: $pos.before(1), node: $pos.node(1) };
  const after = $pos.nodeAfter;
  return after ? { pos: $pos.pos, node: after } : null;
}

export function blockAtSelection(state: EditorState): BlockTarget | null {
  const { selection, doc } = state;
  if (selection instanceof NodeSelection && selection.node.isBlock) {
    const $pos = doc.resolve(selection.from);
    if ($pos.depth === 0 || isListItem(selection.node)) return { pos: selection.from, node: selection.node };
    return blockFromResolved($pos);
  }
  return blockFromResolved(selection.$from);
}

/** 解析指针下方的块；`left` 应已落在正文栏内。 */
export function blockAtCoords(view: EditorView, coords: { left: number; top: number }): BlockTarget | null {
  const hit = view.posAtCoords(coords);
  if (!hit) return null;
  const { doc } = view.state;
  const anchor = Math.max(0, Math.min(hit.inside >= 0 ? hit.inside : hit.pos, doc.content.size));
  return blockFromResolved(doc.resolve(anchor));
}

/** 文档可能已变化，重新校验之前记录的目标块。 */
export function currentTarget(state: EditorState, target: BlockTarget | null): BlockTarget | null {
  if (!target || target.pos < 0 || target.pos >= state.doc.content.size) return null;
  const node = state.doc.nodeAt(target.pos);
  return node && node.type === target.node.type ? { pos: target.pos, node } : null;
}

export function canTurnInto(target: BlockTarget | null): boolean {
  if (!target) return false;
  const { node } = target;
  return node.isTextblock || isListItem(node) || node.type.name === 'blockquote';
}

export function currentBlockKind(editor: Editor): BlockKind {
  if (editor.isActive('codeBlock')) return 'codeBlock';
  for (const level of [1, 2, 3] as const) {
    if (editor.isActive('heading', { level })) return `heading${level}`;
  }
  if (editor.isActive('taskList')) return 'taskList';
  if (editor.isActive('bulletList')) return 'bulletList';
  if (editor.isActive('orderedList')) return 'orderedList';
  if (editor.isActive('blockquote')) return 'blockquote';
  return 'paragraph';
}

const TURN_INTO: Record<BlockKind, (chain: ChainedCommands) => ChainedCommands> = {
  paragraph: (chain) => chain.clearNodes().setParagraph(),
  heading1: (chain) => chain.clearNodes().setHeading({ level: 1 }),
  heading2: (chain) => chain.clearNodes().setHeading({ level: 2 }),
  heading3: (chain) => chain.clearNodes().setHeading({ level: 3 }),
  bulletList: (chain) => chain.clearNodes().toggleBulletList(),
  orderedList: (chain) => chain.clearNodes().toggleOrderedList(),
  taskList: (chain) => chain.clearNodes().toggleTaskList(),
  blockquote: (chain) => chain.clearNodes().toggleBlockquote(),
  codeBlock: (chain) => chain.clearNodes().setCodeBlock()
};

/**
 * 把选区所在的块（或手柄指向的块）转换为另一种类型。
 * 先清除外层包裹，列表项或引用会直接变成新类型而不是被嵌套，与 Notion 的“转换为”一致。
 */
export function turnInto(editor: Editor, kind: BlockKind, target?: BlockTarget | null, start?: ChainedCommands): boolean {
  if (target) {
    const live = currentTarget(editor.state, target);
    if (!live || !canTurnInto(live)) return false;
    const { doc } = editor.state;
    const selection = TextSelection.between(doc.resolve(live.pos + 1), doc.resolve(live.pos + live.node.nodeSize - 1));
    editor.view.dispatch(editor.state.tr.setSelection(selection));
  } else if (currentBlockKind(editor) === kind) {
    return false;
  }
  const chain = TURN_INTO[kind](start ?? editor.chain().focus());
  if (target) {
    chain.command(({ tr }) => {
      tr.setSelection(TextSelection.near(tr.doc.resolve(tr.selection.to), -1));
      return true;
    });
  }
  return chain.run();
}

function selectionOffset(state: EditorState, target: BlockTarget): number | null {
  const { from } = state.selection;
  return from >= target.pos && from <= target.pos + target.node.nodeSize ? from - target.pos : null;
}

function placeSelection(tr: Transaction, position: number, node: ProseMirrorNode, offset: number | null, preferNode: boolean): void {
  if ((preferNode || node.isAtom) && NodeSelection.isSelectable(node)) {
    tr.setSelection(NodeSelection.create(tr.doc, position));
    return;
  }
  const inside = Math.min(position + (offset ?? 1), tr.doc.content.size);
  tr.setSelection(Selection.near(tr.doc.resolve(inside), 1));
}

export function canMoveBlock(state: EditorState, direction: -1 | 1, target: BlockTarget | null): boolean {
  const live = currentTarget(state, target);
  if (!live) return false;
  const $pos = state.doc.resolve(live.pos);
  const sibling = $pos.index() + direction;
  return sibling >= 0 && sibling < $pos.parent.childCount;
}

/** 与同一父节点内的上一个 / 下一个兄弟块交换位置。 */
export function moveBlock(editor: Editor, direction: -1 | 1, target: BlockTarget | null = blockAtSelection(editor.state)): boolean {
  const { state } = editor;
  const live = currentTarget(state, target);
  if (!live || !canMoveBlock(state, direction, live)) return false;
  const $pos = state.doc.resolve(live.pos);
  const sibling = $pos.parent.child($pos.index() + direction);
  const { node } = live;
  const offset = selectionOffset(state, live);
  const tr = state.tr;
  let destination: number;
  if (direction < 0) {
    destination = live.pos - sibling.nodeSize;
    tr.delete(live.pos, live.pos + node.nodeSize).insert(destination, node);
  } else {
    tr.delete(live.pos, live.pos + node.nodeSize);
    destination = live.pos + sibling.nodeSize;
    tr.insert(destination, node);
  }
  placeSelection(tr, destination, node, offset, state.selection instanceof NodeSelection);
  editor.view.dispatch(tr.scrollIntoView());
  editor.view.focus();
  return true;
}

export function duplicateBlock(editor: Editor, target: BlockTarget | null = blockAtSelection(editor.state)): boolean {
  const { state } = editor;
  const live = currentTarget(state, target);
  if (!live) return false;
  const destination = live.pos + live.node.nodeSize;
  const tr = state.tr.insert(destination, live.node);
  placeSelection(tr, destination, live.node, selectionOffset(state, live), state.selection instanceof NodeSelection);
  editor.view.dispatch(tr.scrollIntoView());
  editor.view.focus();
  return true;
}

export function deleteBlock(editor: Editor, target: BlockTarget | null = blockAtSelection(editor.state)): boolean {
  const { state } = editor;
  const live = currentTarget(state, target);
  if (!live) return false;
  const { pos, node } = live;
  const tr = state.tr;
  if (state.doc.childCount === 1 && pos === 0) {
    tr.replaceWith(0, node.nodeSize, state.schema.nodes.paragraph.create());
  } else if (isListItem(node)) {
    // 删除列表的最后一项时一并移除空列表。
    tr.deleteRange(pos, pos + node.nodeSize);
  } else {
    tr.delete(pos, pos + node.nodeSize);
  }
  tr.setSelection(Selection.near(tr.doc.resolve(Math.min(pos, tr.doc.content.size)), 1));
  editor.view.dispatch(tr.scrollIntoView());
  editor.view.focus();
  return true;
}

/**
 * 在块后插入一个空行（列表内插入同级项）并把光标放进去；schema 不允许时返回 false。
 */
export function insertBlockBelow(editor: Editor, target: BlockTarget | null): boolean {
  const { state } = editor;
  const live = currentTarget(state, target);
  if (!live) return false;
  const { schema } = state;
  const paragraph = schema.nodes.paragraph.create();
  const itemType = isListItem(live.node) ? live.node.type : null;
  const inserted = itemType
    ? itemType.create(itemType.name === 'taskItem' ? { checked: false } : null, paragraph)
    : paragraph;
  const destination = live.pos + live.node.nodeSize;
  try {
    const tr = state.tr.insert(destination, inserted);
    tr.setSelection(TextSelection.create(tr.doc, destination + (itemType ? 2 : 1)));
    editor.view.dispatch(tr.scrollIntoView());
    editor.view.focus();
    return true;
  } catch {
    return false;
  }
}

/* ------------------------------------------------------------ 块菜单目标 */

const blockTargetKey = new PluginKey<number | null>('blockTarget');

/** 在不移动选区的前提下，给块菜单作用的块加轮廓提示。 */
export function setBlockMenuTarget(view: EditorView, position: number | null): void {
  if (view.isDestroyed || (blockTargetKey.getState(view.state) ?? null) === position) return;
  view.dispatch(view.state.tr.setMeta(blockTargetKey, position));
}

export const BlockTargetHighlight = Extension.create({
  name: 'blockTargetHighlight',
  addProseMirrorPlugins() {
    return [
      new Plugin<number | null>({
        key: blockTargetKey,
        state: {
          init: () => null,
          apply(tr, value) {
            const meta = tr.getMeta(blockTargetKey) as number | null | undefined;
            if (meta !== undefined) return meta;
            if (value === null || !tr.docChanged) return value;
            const mapped = tr.mapping.mapResult(value, 1);
            return mapped.deleted ? null : mapped.pos;
          }
        },
        props: {
          decorations(state) {
            const position = blockTargetKey.getState(state);
            if (position === null || position === undefined) return null;
            const node = state.doc.nodeAt(position);
            if (!node) return null;
            return DecorationSet.create(state.doc, [Decoration.node(position, position + node.nodeSize, { class: 'is-block-targeted' })]);
          }
        }
      })
    ];
  }
});

/* ------------------------------------------------------------ 键盘 */

export interface NotebookKeymapStorage {
  /** 由选区浮条注册，Ctrl+K 借此打开链接编辑。 */
  openLinkEditor: (() => void) | null;
}

export const BlockKeymap = Extension.create<Record<string, never>, NotebookKeymapStorage>({
  name: 'notebookKeymap',
  addStorage() {
    return { openLinkEditor: null };
  },
  addKeyboardShortcuts() {
    return {
      'Mod-k': () => {
        const open = this.storage.openLinkEditor;
        if (!open) return false;
        open();
        return true;
      },
      'Mod-d': () => duplicateBlock(this.editor),
      'Mod-Shift-ArrowUp': () => moveBlock(this.editor, -1),
      'Mod-Shift-ArrowDown': () => moveBlock(this.editor, 1),
      'Mod-\\': () => this.editor.chain().unsetAllMarks().run(),
      // 代码块内 Tab 缩进：浏览器默认会把焦点移出编辑器。
      Tab: () => (this.editor.isActive('codeBlock') ? this.editor.commands.insertContent('  ') : false)
    };
  }
});
