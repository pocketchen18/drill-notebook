import type { Editor } from '@tiptap/core';
import { TextSelection } from '@tiptap/pm/state';
import type { Slice } from '@tiptap/pm/model';
import { dropPoint } from '@tiptap/pm/transform';
import type { EditorView } from '@tiptap/pm/view';
import type { Transaction } from '@tiptap/pm/state';

export interface HeadingMoveSource {
  readonly position: number;
  readonly level: number;
}

/**
 * Capture only complete heading text selections during an in-editor move.
 * Manual deletion and copied/external drops intentionally return no source.
 */
export function captureHeadingMoveSources(view: EditorView, moved: boolean): HeadingMoveSource[] {
  if (!moved) return [];
  const { selection, doc } = view.state;
  if (!(selection instanceof TextSelection) || selection.empty) return [];

  const sources: HeadingMoveSource[] = [];
  doc.nodesBetween(selection.from, selection.to, (node, position) => {
    if (node.type.name !== 'heading' || node.content.size === 0) return true;
    const contentStart = position + 1;
    const contentEnd = contentStart + node.content.size;
    if (selection.from <= contentStart && selection.to >= contentEnd) {
      const level = typeof node.attrs.level === 'number' ? node.attrs.level : Number(node.attrs.level) || 1;
      sources.push({ position, level });
    }
    return true;
  });
  return sources;
}

/**
 * Handle a complete heading move in one transaction. Returning false lets
 * ProseMirror keep its normal copy/drop behavior for partial or external
 * drops, while a handled move preserves a single undo step.
 */
export function handleHeadingDrop(view: EditorView, event: DragEvent, slice: Slice, moved: boolean): boolean {
  const sources = captureHeadingMoveSources(view, moved);
  if (sources.length === 0) return false;

  try {
    const eventPosition = view.posAtCoords({ left: event.clientX, top: event.clientY });
    if (!eventPosition) return false;
    const mousePosition = view.state.doc.resolve(eventPosition.pos);
    const insertPosition = dropPoint(view.state.doc, mousePosition.pos, slice) ?? mousePosition.pos;
    const transaction = view.state.tr.deleteSelection();
    const mappedInsertPosition = transaction.mapping.map(insertPosition);
    const isSingleNode = slice.openStart === 0 && slice.openEnd === 0 && slice.content.childCount === 1;
    if (isSingleNode) {
      transaction.replaceRangeWith(mappedInsertPosition, mappedInsertPosition, slice.content.firstChild!);
    } else {
      transaction.replaceRange(mappedInsertPosition, mappedInsertPosition, slice);
    }

    const paragraph = view.state.schema.nodes.paragraph;
    if (!paragraph) return false;
    let normalized = false;
    for (const source of sources) {
      const position = transaction.mapping.map(source.position, 1);
      const node = transaction.doc.nodeAt(position);
      if (!node || node.type.name !== 'heading' || node.content.size !== 0) continue;
      const level = typeof node.attrs.level === 'number' ? node.attrs.level : Number(node.attrs.level) || 1;
      if (level !== source.level) continue;
      transaction.setNodeMarkup(position, paragraph, null, node.marks);
      normalized = true;
    }
    if (!normalized) return false;

    const selectionPosition = Math.min(mappedInsertPosition, transaction.doc.content.size);
    transaction.setSelection(TextSelection.near(transaction.doc.resolve(selectionPosition), 1));
    transaction.setMeta('uiEvent', 'drop');
    transaction.scrollIntoView();
    event.preventDefault();
    view.focus();
    view.dispatch(transaction);
    return true;
  } catch {
    // Let ProseMirror's default drop path handle an incompatible target/slice.
    return false;
  }
}

/**
 * Fallback cleanup for a move that was already handled by ProseMirror. The
 * primary path handles complete heading moves in one transaction; this keeps
 * older/coordinate-less drop paths from leaving a visible empty heading.
 */
export function cleanupMovedHeadingSources(editor: Editor, dropTransaction: Transaction, sources: readonly HeadingMoveSource[]): boolean {
  if (editor.isDestroyed || sources.length === 0) return false;
  const paragraph = editor.state.schema.nodes.paragraph;
  if (!paragraph) return false;

  const cleanup = editor.state.tr;
  let changed = false;
  for (const source of sources) {
    const position = dropTransaction.mapping.map(source.position, 1);
    const node = cleanup.doc.nodeAt(position);
    if (!node || node.type.name !== 'heading' || node.content.size !== 0) continue;
    const level = typeof node.attrs.level === 'number' ? node.attrs.level : Number(node.attrs.level) || 1;
    if (level !== source.level) continue;
    cleanup.setNodeMarkup(position, paragraph, null, node.marks);
    changed = true;
  }

  if (!changed) return false;

  // A drop leaves a range or node selection active. Move the caret to the
  // nearest text position so the moved block can be edited immediately.
  const selectionPosition = Math.min(editor.state.selection.to, cleanup.doc.content.size);
  try {
    cleanup.setSelection(TextSelection.near(cleanup.doc.resolve(selectionPosition), 1));
  } catch {
    return false;
  }
  cleanup.setMeta('addToHistory', false);
  editor.view.dispatch(cleanup);
  return true;
}

/** Clear the node/range selection left by a native in-editor move. */
export function collapseMovedSelection(editor: Editor): boolean {
  if (editor.isDestroyed || editor.state.selection.empty) return false;
  const position = Math.min(editor.state.selection.to, editor.state.doc.content.size);
  const cleanup = editor.state.tr;
  try {
    cleanup.setSelection(TextSelection.near(cleanup.doc.resolve(position), 1));
  } catch {
    return false;
  }
  cleanup.setMeta('addToHistory', false);
  editor.view.dispatch(cleanup);
  return true;
}
