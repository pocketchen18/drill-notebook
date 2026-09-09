import { afterEach, describe, expect, it, vi } from 'vitest';
import { Editor } from '@tiptap/core';
import StarterKit from '@tiptap/starter-kit';
import { NodeSelection, TextSelection } from '@tiptap/pm/state';
import { captureHeadingMoveSources, cleanupMovedHeadingSources, collapseMovedSelection, handleHeadingDrop } from './headingDrag';
import { exitNodeSelection } from './EditorChrome';

const editors: Editor[] = [];

afterEach(() => {
  editors.splice(0).forEach((editor) => editor.destroy());
});

function makeEditor(): Editor {
  const editor = new Editor({ content: '<h2>移动标题</h2><p>目标位置</p>', extensions: [StarterKit] });
  editors.push(editor);
  return editor;
}

describe('heading drag normalization', () => {
  it('captures only a complete moved heading text selection', () => {
    const editor = makeEditor();
    editor.view.dispatch(editor.state.tr.setSelection(TextSelection.create(editor.state.doc, 1, 5)));
    expect(captureHeadingMoveSources(editor.view, true)).toEqual([{ position: 0, level: 2 }]);
    expect(captureHeadingMoveSources(editor.view, false)).toEqual([]);

    editor.view.dispatch(editor.state.tr.setSelection(TextSelection.create(editor.state.doc, 2, 5)));
    expect(captureHeadingMoveSources(editor.view, true)).toEqual([]);
  });

  it('turns the source shell into a paragraph and collapses the post-drop selection', () => {
    const editor = makeEditor();
    const sourceFrom = 1;
    const sourceTo = 5;
    editor.view.dispatch(editor.state.tr.setSelection(TextSelection.create(editor.state.doc, sourceFrom, sourceTo)));
    const sources = captureHeadingMoveSources(editor.view, true);
    const dropTransaction = editor.state.tr.deleteSelection();
    dropTransaction.insert(dropTransaction.doc.content.size, editor.state.schema.text('移动标题'));
    dropTransaction.setMeta('uiEvent', 'drop');
    editor.view.dispatch(dropTransaction);

    expect(editor.state.doc.child(0).type.name).toBe('heading');
    expect(editor.state.doc.child(0).textContent).toBe('');
    expect(cleanupMovedHeadingSources(editor, dropTransaction, sources)).toBe(true);
    expect(editor.state.doc.child(0).type.name).toBe('paragraph');
    expect(editor.state.doc.child(0).textContent).toBe('');
    expect(editor.state.selection).toBeInstanceOf(TextSelection);
    expect(editor.state.selection.empty).toBe(true);
  });

  it('does not change a manually emptied heading when no move is reported', () => {
    const editor = makeEditor();
    editor.view.dispatch(editor.state.tr.setSelection(TextSelection.create(editor.state.doc, 1, 5)).deleteSelection());
    expect(captureHeadingMoveSources(editor.view, false)).toEqual([]);
    expect(editor.state.doc.child(0).type.name).toBe('heading');
    expect(editor.state.doc.child(0).textContent).toBe('');
  });

  it('is safe when cleanup is dispatched from the editor update callback', () => {
    let pending: ReturnType<typeof captureHeadingMoveSources> = [];
    const editor = new Editor({
      content: '<h2>移动标题</h2><p>目标位置</p>',
      extensions: [StarterKit],
      onUpdate: ({ editor: current, transaction }) => {
        if (transaction.getMeta('uiEvent') !== 'drop') return;
        const sources = pending;
        pending = [];
        cleanupMovedHeadingSources(current, transaction, sources);
      }
    });
    editors.push(editor);
    editor.view.dispatch(editor.state.tr.setSelection(TextSelection.create(editor.state.doc, 1, 5)));
    pending = captureHeadingMoveSources(editor.view, true);
    const dropTransaction = editor.state.tr.deleteSelection();
    dropTransaction.insert(dropTransaction.doc.content.size, editor.state.schema.text('移动标题'));
    dropTransaction.setMeta('uiEvent', 'drop');
    expect(() => editor.view.dispatch(dropTransaction)).not.toThrow();
    expect(editor.state.doc.child(0).type.name).toBe('paragraph');
    expect(editor.state.selection.empty).toBe(true);
  });

  it('collapses a moved atom node selection to a nearby text cursor', () => {
    const editor = new Editor({ content: '<hr/><p>after</p>', extensions: [StarterKit] });
    editors.push(editor);
    editor.view.dispatch(editor.state.tr.setSelection(NodeSelection.create(editor.state.doc, 0)));
    expect(editor.state.selection).toBeInstanceOf(NodeSelection);
    expect(collapseMovedSelection(editor)).toBe(true);
    expect(editor.state.selection).toBeInstanceOf(TextSelection);
    expect(editor.state.selection.empty).toBe(true);
  });

  it('maps and clears a source heading when it is moved upward', () => {
    const editor = new Editor({ content: '<p>目标位置</p><h2>移动标题</h2>', extensions: [StarterKit] });
    editors.push(editor);
    const sourcePosition = editor.state.doc.child(0).nodeSize;
    const sourceStart = sourcePosition + 1;
    editor.view.dispatch(editor.state.tr.setSelection(TextSelection.create(editor.state.doc, sourceStart, sourceStart + 4)));
    const sources = captureHeadingMoveSources(editor.view, true);
    const dropTransaction = editor.state.tr.deleteSelection();
    dropTransaction.insert(1, editor.state.schema.nodes.heading.create({ level: 2 }, editor.state.schema.text('移动标题')));
    dropTransaction.setMeta('uiEvent', 'drop');
    editor.view.dispatch(dropTransaction);
    expect(cleanupMovedHeadingSources(editor, dropTransaction, sources)).toBe(true);
    expect(editor.state.doc.nodesBetween(0, editor.state.doc.content.size, (node, position) => {
      if (node.type.name === 'heading') expect(node.textContent).not.toBe('');
      return true;
    })).toBeUndefined();
    expect(editor.state.selection.empty).toBe(true);
  });

  it('lets a selected custom block enter its editor without retaining NodeSelection', () => {
    const editor = new Editor({ content: '<hr/><p>after</p>', extensions: [StarterKit] });
    editors.push(editor);
    const node = editor.state.doc.nodeAt(0)!;
    editor.view.dispatch(editor.state.tr.setSelection(NodeSelection.create(editor.state.doc, 0)));
    exitNodeSelection(editor.view, () => 0, node);
    expect(editor.state.selection.empty).toBe(true);
    expect(editor.state.selection).toBeInstanceOf(TextSelection);
  });

  it('normalizes a complete heading move in one undoable transaction', () => {
    const editor = makeEditor();
    editor.view.dispatch(editor.state.tr.setSelection(TextSelection.create(editor.state.doc, 1, 5)));
    const slice = editor.state.doc.slice(1, 5);
    vi.spyOn(editor.view, 'posAtCoords').mockReturnValue({ pos: editor.state.doc.content.size, inside: -1 });
    const event = { clientX: 10, clientY: 10, preventDefault: vi.fn() } as unknown as DragEvent;

    expect(handleHeadingDrop(editor.view, event, slice, true)).toBe(true);
    expect(event.preventDefault).toHaveBeenCalledOnce();
    expect(editor.state.doc.child(0).type.name).toBe('paragraph');
    expect(editor.state.doc.textContent).toContain('移动标题');
    expect(editor.state.selection.empty).toBe(true);

    editor.commands.undo();
    expect(editor.state.doc.child(0).type.name).toBe('heading');
    expect(editor.state.doc.child(0).textContent).toBe('移动标题');
  });

  it('handles a complete heading move upward without leaving the source style', () => {
    const editor = new Editor({ content: '<p>目标位置</p><h2>移动标题</h2>', extensions: [StarterKit] });
    editors.push(editor);
    const sourcePosition = editor.state.doc.child(0).nodeSize;
    const sourceStart = sourcePosition + 1;
    editor.view.dispatch(editor.state.tr.setSelection(TextSelection.create(editor.state.doc, sourceStart, sourceStart + 4)));
    const slice = editor.state.doc.slice(sourceStart, sourceStart + 4);
    vi.spyOn(editor.view, 'posAtCoords').mockReturnValue({ pos: 1, inside: -1 });
    const event = { clientX: 10, clientY: 10, preventDefault: vi.fn() } as unknown as DragEvent;

    expect(handleHeadingDrop(editor.view, event, slice, true)).toBe(true);
    expect(editor.state.doc.childCount).toBe(2);
    expect(editor.state.doc.child(0).type.name).toBe('paragraph');
    expect(editor.state.doc.child(0).textContent).toContain('移动标题');
    expect(editor.state.doc.child(1).type.name).toBe('paragraph');
    expect(editor.state.doc.child(1).textContent).toBe('');
  });
});
