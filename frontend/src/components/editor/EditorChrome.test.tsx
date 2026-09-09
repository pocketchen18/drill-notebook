import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { Editor, EditorContent } from '@tiptap/react';
import StarterKit from '@tiptap/starter-kit';
import { BlockDragHandle, EditorBubbleMenu, EditorOutline } from './EditorChrome';

const selectionRect = { x: 200, y: 160, top: 160, left: 200, right: 320, bottom: 180, width: 120, height: 20, toJSON: () => ({}) } as DOMRect;
const editors: Editor[] = [];

// jsdom has selections but no layout. Keep real TipTap commands and history.
if (!Range.prototype.getBoundingClientRect) Range.prototype.getBoundingClientRect = () => selectionRect;
if (!Range.prototype.getClientRects) Range.prototype.getClientRects = () => [] as unknown as DOMRectList;

function mountEditor(content: string): Editor {
  const editor = new Editor({ extensions: [StarterKit], content });
  editors.push(editor);
  render(<><EditorContent editor={editor} /><EditorBubbleMenu editor={editor} /></>);
  act(() => { editor.view.focus(); });
  return editor;
}

beforeEach(() => {
  vi.spyOn(window, 'scrollBy').mockImplementation(() => {});
});

afterEach(() => {
  cleanup();
  editors.splice(0).forEach((editor) => editor.destroy());
  vi.restoreAllMocks();
});

describe('EditorBubbleMenu deletion', () => {
  it('deletes only the selected text and supports undo', async () => {
    vi.spyOn(Range.prototype, 'getBoundingClientRect').mockReturnValue(selectionRect);
    const editor = mountEditor('<p>delete keep</p>');
    act(() => { editor.commands.setTextSelection({ from: 1, to: 8 }); });
    const toolbar = await screen.findByRole('toolbar', { name: '选中文本格式' });
    const remove = within(toolbar).getByRole('button', { name: '删除' });
    fireEvent.mouseDown(remove);
    fireEvent.click(remove);
    expect(editor.getText()).toBe('keep');
    expect(screen.queryByRole('toolbar', { name: '选中文本格式' })).toBeNull();
    act(() => { editor.commands.undo(); });
    expect(editor.getText()).toBe('delete keep');
  });

  it('deletes a selected block without applying text formatting or removing adjacent text', async () => {
    vi.spyOn(HTMLElement.prototype, 'getBoundingClientRect').mockReturnValue(selectionRect);
    const editor = mountEditor('<p>before</p><hr/><p>after</p>');
    act(() => { editor.commands.setNodeSelection(8); });
    const toolbar = await screen.findByRole('toolbar', { name: '选中块操作' });
    expect(within(toolbar).queryByRole('button', { name: '加粗' })).toBeNull();
    fireEvent.click(within(toolbar).getByRole('button', { name: '删除' }));
    expect(editor.getHTML()).toBe('<p>before</p><p>after</p>');
    act(() => { editor.commands.undo(); });
    expect(editor.getHTML()).toContain('<hr>');
  });

  it('hides when the document scrolls or focus leaves the editor', async () => {
    vi.spyOn(Range.prototype, 'getBoundingClientRect').mockReturnValue(selectionRect);
    const editor = mountEditor('<p>selected text</p>');
    act(() => { editor.commands.setTextSelection({ from: 1, to: 5 }); });
    await screen.findByRole('toolbar', { name: '选中文本格式' });
    fireEvent.scroll(document);
    expect(screen.queryByRole('toolbar')).toBeNull();
    act(() => { editor.commands.setTextSelection({ from: 1, to: 6 }); });
    await screen.findByRole('toolbar', { name: '选中文本格式' });
    act(() => { editor.commands.blur(); });
    await waitFor(() => expect(screen.queryByRole('toolbar')).toBeNull());
  });
});

describe('BlockDragHandle', () => {
  it('exposes the native TipTap drag-handle contract without adding a tab stop', () => {
    const { container } = render(<BlockDragHandle label="拖动测试块" />);
    const handle = container.querySelector('.editor-block-drag-handle') as HTMLElement;
    expect(handle).toHaveAttribute('data-drag-handle', 'true');
    expect(handle).toHaveAttribute('draggable', 'true');
    expect(handle).toHaveAttribute('aria-hidden', 'true');
    expect(handle).toHaveAttribute('title', '拖动测试块');
    expect(handle).toHaveAttribute('tabindex', '-1');
  });
});

describe('EditorOutline', () => {
  it('updates heading entries and scrolls to the matching document heading', () => {
    const editor = new Editor({ extensions: [StarterKit], content: '<h1>章节</h1><h2>小节</h2><p>正文</p>' });
    editors.push(editor);
    const onClose = vi.fn();
    render(<><EditorContent editor={editor} /><EditorOutline editor={editor} open onClose={onClose} /></>);
    const heading = editor.view.dom.querySelector('h2')!;
    heading.scrollIntoView = vi.fn();
    fireEvent.click(screen.getByRole('button', { name: '小节' }));
    expect(heading.scrollIntoView).toHaveBeenCalledWith({ behavior: 'smooth', block: 'start' });
    expect(editor.state.selection.empty).toBe(true);
    act(() => { editor.commands.setContent('<h2>新小节</h2>', true); });
    expect(screen.getByRole('button', { name: '新小节' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '小节' })).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: '关闭大纲' }));
    expect(onClose).toHaveBeenCalledOnce();
  });

  it('marks the heading nearest the current selection', () => {
    const editor = new Editor({ extensions: [StarterKit], content: '<h1>章节</h1><h2>小节</h2><p>正文</p>' });
    editors.push(editor);
    render(<><EditorContent editor={editor} /><EditorOutline editor={editor} open onClose={() => {}} /></>);
    expect(screen.getByRole('button', { name: '章节' })).toHaveClass('is-active');
    act(() => { editor.commands.setTextSelection({ from: 5, to: 5 }); });
    expect(screen.getByRole('button', { name: '小节' })).toHaveClass('is-active');
    expect(screen.getByRole('button', { name: '章节' })).not.toHaveClass('is-active');
  });
});
