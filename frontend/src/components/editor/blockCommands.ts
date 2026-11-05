import { Extension, type ChainedCommands, type Command, type Editor } from '@tiptap/core';
import type { Node as ProseMirrorNode, ResolvedPos } from '@tiptap/pm/model';
import { NodeSelection, Plugin, PluginKey, Selection, TextSelection, type EditorState, type Transaction } from '@tiptap/pm/state';
import { Decoration, DecorationSet, type EditorView } from '@tiptap/pm/view';

/** 可移动的单位：顶层块，或列表中的列表项 / 待办项。 */
export interface BlockTarget {
  readonly pos: number;
  readonly node: ProseMirrorNode;
}

export type BlockKind = 'paragraph' | 'heading1' | 'heading2' | 'heading3' | 'bulletList' | 'orderedList' | 'taskList' | 'blockquote' | 'codeBlock';

/** 浮窗请求节点视图打开公式源码；仅传事务元数据，不修改文档或撤销历史。 */
export const EDIT_MATH_BLOCK_META = 'notebookEditMathBlock';

export function editSelectedMathBlock(editor: Editor): boolean {
  const { selection } = editor.state;
  if (!editor.isEditable || !(selection instanceof NodeSelection) || selection.node.type.name !== 'mathBlock') return false;
  editor.view.dispatch(editor.state.tr.setMeta(EDIT_MATH_BLOCK_META, selection.from));
  return true;
}

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

/** 代码块内 `$…$` 片段的位置（相对块内文本），转回正文时据此精确重建公式节点。 */
export interface MathSpan {
  readonly start: number;
  readonly end: number;
  readonly latex: string;
}

const FLATTEN_META = 'notebookMathFlatten';
const RESTORE_META = 'notebookMathRestore';

interface SelectedTextblock {
  readonly start: number;
  readonly end: number;
  readonly node: ProseMirrorNode;
}

/** 块转换处理完整文本块，不能只扫描文字选区；同时兼容 AllSelection / NodeSelection。 */
function selectedTextblocks(tr: Transaction): SelectedTextblock[] {
  const blocks = new Map<number, SelectedTextblock>();
  for (const { $from, $to } of tr.selection.ranges) {
    tr.doc.nodesBetween($from.pos, $to.pos, (node, pos) => {
      if (!node.isTextblock) return;
      blocks.set(pos, { start: pos + 1, end: pos + 1 + node.content.size, node });
      return false;
    });
  }
  return [...blocks.values()];
}

/** 是否只涉及一个文本块取决于实际内容，而不是选区端点的深度。 */
function singleTextblock(tr: Transaction): SelectedTextblock | null {
  const blocks = selectedTextblocks(tr);
  return blocks.length === 1 ? blocks[0] : null;
}

/**
 * 代码块只收纯文本，而行内公式的源码存在 attrs.latex 里：不先摊成 `$…$` 文字，
 * 转换时公式节点会被 schema 直接丢弃。片段位置随事务交给下一步写进 codeBlock 属性。
 * 这些命令一律返回 true，否则 TipTap 链条会在没有公式的段落上断掉。
 */
const flattenMathToText: Command = ({ tr, dispatch }) => {
  const mathType = tr.doc.type.schema.nodes.mathInline;
  if (!mathType || !dispatch) return true;
  const blocks = selectedTextblocks(tr);
  const block = blocks.length === 1 ? blocks[0] : null;
  const hits: { from: number; to: number; text: string }[] = [];
  for (const target of blocks) {
    target.node.forEach((node, offset) => {
      if (node.type !== mathType) return;
      const pos = target.start + offset;
      hits.push({ from: pos, to: pos + node.nodeSize, text: `$${String(node.attrs.latex ?? '')}$` });
    });
  }
  if (!hits.length) return true;
  // 只使用本阶段的映射：clearNodes / 斜杠删除已经改变过位置，不能重复映射。
  const bookmark = tr.selection.getBookmark();
  const mapFrom = tr.mapping.maps.length;
  for (const hit of hits.reverse()) tr.insertText(hit.text, hit.from, hit.to);
  tr.setSelection(bookmark.map(tr.mapping.slice(mapFrom)).resolve(tr.doc));
  // 只有整段落在同一个文本块里才记来源：跨块转换会拆成多个代码块，位置对不上，宁可不恢复也不猜。
  if (!block) return true;
  const spans: MathSpan[] = [];
  let text = '';
  block.node.content.forEach((child) => {
    if (child.type === mathType) {
      const latex = String(child.attrs.latex ?? '');
      const piece = `$${latex}$`;
      spans.push({ start: text.length, end: text.length + piece.length, latex });
      text += piece;
    } else if (child.isText) {
      text += child.text ?? '';
    } else if (child.type.name === 'hardBreak') {
      text += '\n';
    }
  });
  if (spans.length) tr.setMeta(FLATTEN_META, { spans, text });
  return true;
};

/** `setCodeBlock` 之后把来源写进属性；块内文本与预期不一致就说明转换结果变了，宁可不记。 */
const applyMathSpansToCodeBlock: Command = ({ tr, dispatch }) => {
  const recorded = tr.getMeta(FLATTEN_META) as { spans: MathSpan[]; text: string } | undefined;
  const codeType = tr.doc.type.schema.nodes.codeBlock;
  if (!recorded || !codeType) return true;
  const block = singleTextblock(tr);
  if (block?.node.type === codeType && block.node.textContent === recorded.text && dispatch) {
    tr.setNodeMarkup(block.start - 1, undefined, { ...block.node.attrs, mathSpans: recorded.spans });
  }
  return true;
};

/** 转回正文前先取出来源：`clearNodes` 会把 codeBlock 的属性一起清掉。 */
const captureCodeBlockMath: Command = ({ tr }) => {
  const block = singleTextblock(tr);
  if (block?.node.type.name === 'codeBlock') tr.setMeta(RESTORE_META, block.node.attrs.mathSpans);
  return true;
};

/**
 * 保守恢复：每个片段的位置和字面 `$latex$` 必须原样还在，改过就不猜边界、也不用旧内容覆盖新内容。
 * 用户手打的 `$x^2$` 没有来源记录，因此往返后仍是普通文字。
 */
const restoreMathSpans: Command = ({ tr, dispatch }) => {
  const spans: unknown = tr.getMeta(RESTORE_META);
  const mathType = tr.doc.type.schema.nodes.mathInline;
  const block = singleTextblock(tr);
  if (!Array.isArray(spans) || !spans.length || !mathType || !block) return true;
  const text = block.node.textContent;
  // 来源会从 JSON 读回；形状、顺序和边界异常时只保留当前文字，不猜测或抛错。
  let previousEnd = 0;
  for (const span of spans) {
    if (!span || typeof span.latex !== 'string' || !Number.isInteger(span.start) || !Number.isInteger(span.end)
      || span.start < previousEnd || span.end <= span.start || span.end > text.length
      || text.slice(span.start, span.end) !== `$${span.latex}$`) return true;
    previousEnd = span.end;
  }
  const pieces: ProseMirrorNode[] = [];
  let cursor = 0;
  for (const span of spans) {
    if (span.start > cursor) pieces.push(tr.doc.type.schema.text(text.slice(cursor, span.start))!);
    pieces.push(mathType.create({ latex: span.latex }));
    cursor = span.end;
  }
  if (cursor < text.length) pieces.push(tr.doc.type.schema.text(text.slice(cursor))!);
  if (dispatch) {
    const bookmark = tr.selection.getBookmark();
    const mapFrom = tr.mapping.maps.length;
    tr.replaceWith(block.start, block.end, pieces);
    tr.setSelection(bookmark.map(tr.mapping.slice(mapFrom)).resolve(tr.doc));
  }
  return true;
};

const TURN_INTO: Record<BlockKind, (chain: ChainedCommands) => ChainedCommands> = {
  paragraph: (chain) => chain.command(captureCodeBlockMath).clearNodes().command(({ tr, commands }) => {
    // clearNodes 通常已转成正文；再次 setParagraph 会返回 false，让快捷键误以为未处理。
    return selectedTextblocks(tr).every((block) => block.node.type.name === 'paragraph') || commands.setParagraph();
  }).command(restoreMathSpans),
  heading1: (chain) => chain.clearNodes().setHeading({ level: 1 }),
  heading2: (chain) => chain.clearNodes().setHeading({ level: 2 }),
  heading3: (chain) => chain.clearNodes().setHeading({ level: 3 }),
  bulletList: (chain) => chain.clearNodes().toggleBulletList(),
  orderedList: (chain) => chain.clearNodes().toggleOrderedList(),
  taskList: (chain) => chain.clearNodes().toggleTaskList(),
  blockquote: (chain) => chain.clearNodes().toggleBlockquote(),
  codeBlock: (chain) => chain.clearNodes().command(flattenMathToText).setCodeBlock().command(applyMathSpansToCodeBlock)
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
  // 优先于 Paragraph 自带的快捷键，返回正文时才能恢复公式来源。
  priority: 1100,
  addStorage() {
    return { openLinkEditor: null };
  },
  addKeyboardShortcuts() {
    return {
      // 复制副本 / 键盘选中后焦点可能在正文根节点，仍与公式预览的 Enter 使用同一入口。
      Enter: () => editSelectedMathBlock(this.editor),
      'Mod-Alt-0': () => turnInto(this.editor, 'paragraph'),
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
