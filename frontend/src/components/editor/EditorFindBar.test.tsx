import { afterEach, describe, expect, it, vi } from 'vitest';
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react';
import { Editor, EditorContent } from '@tiptap/react';
import { notebookExtensions } from './editorExtensions';
import { EditorFindBar, type FindRequest } from './EditorFindBar';

const editors: Editor[] = [];
const openRequest = (replace = false): FindRequest => ({ open: true, replace, seed: '', token: 1 });

function mount(html: string, request: FindRequest = openRequest(), onClose = vi.fn()): Editor {
  const editor = new Editor({ extensions: notebookExtensions(), content: html });
  editors.push(editor);
  render(<><EditorContent editor={editor} /><EditorFindBar editor={editor} request={request} onClose={onClose} onToggleReplace={() => {}} /></>);
  return editor;
}

function search(value: string): HTMLElement {
  const input = screen.getByRole('textbox', { name: '查找' });
  fireEvent.change(input, { target: { value } });
  return input;
}

afterEach(() => {
  cleanup();
  editors.splice(0).forEach((editor) => editor.destroy());
});

describe('EditorFindBar', () => {
  it('匹配可以跨越加粗等格式，并显示当前序号与总数', async () => {
    mount('<p>alpha <strong>be</strong>ta and beta</p>');
    search('beta');
    expect(await screen.findByText('1 / 2')).toBeInTheDocument();
    expect(document.querySelectorAll('.ProseMirror-search-match, .ProseMirror-active-search-match').length).toBeGreaterThanOrEqual(2);
  });

  it('Enter / Shift+Enter 在匹配间循环，Esc 关闭', async () => {
    const onClose = vi.fn();
    mount('<p>one two one two one</p>', openRequest(), onClose);
    const input = search('one');
    expect(await screen.findByText('1 / 3')).toBeInTheDocument();
    fireEvent.keyDown(input, { key: 'Enter' });
    expect(await screen.findByText('2 / 3')).toBeInTheDocument();
    fireEvent.keyDown(input, { key: 'Enter', shiftKey: true });
    expect(await screen.findByText('1 / 3')).toBeInTheDocument();
    fireEvent.keyDown(input, { key: 'Enter', shiftKey: true });
    expect(await screen.findByText('3 / 3')).toBeInTheDocument();
    fireEvent.keyDown(input, { key: 'Escape' });
    expect(onClose).toHaveBeenCalledOnce();
  });

  it('默认不区分大小写，打开 Aa 后只匹配大小写一致的结果', async () => {
    mount('<p>Alpha alpha</p>');
    search('alpha');
    expect(await screen.findByText('1 / 2')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: '区分大小写' }));
    expect(await screen.findByText('1 / 1')).toBeInTheDocument();
  });

  it('没有匹配时提示“无匹配”', async () => {
    mount('<p>alpha</p>');
    search('omega');
    expect(await screen.findByText('无匹配')).toBeInTheDocument();
  });

  it('全部替换只占一步撤销，编辑后匹配数随之刷新', async () => {
    const editor = mount('<p>cat and cat</p>', openRequest(true));
    search('cat');
    expect(await screen.findByText('1 / 2')).toBeInTheDocument();
    fireEvent.change(screen.getByRole('textbox', { name: '替换为' }), { target: { value: 'dog' } });
    fireEvent.click(screen.getByRole('button', { name: '全部替换' }));
    expect(editor.getText()).toBe('dog and dog');
    expect(await screen.findByText('无匹配')).toBeInTheDocument();
    act(() => { editor.commands.undo(); });
    expect(editor.getText()).toBe('cat and cat');
    act(() => { editor.commands.insertContentAt(editor.state.doc.content.size - 1, ' cat'); });
    expect(await screen.findByText(/\/ 3$/)).toBeInTheDocument();
  });

  it('关闭后清除高亮', async () => {
    const editor = mount('<p>alpha alpha</p>');
    search('alpha');
    expect(await screen.findByText('1 / 2')).toBeInTheDocument();
    cleanup();
    render(<><EditorContent editor={editor} /><EditorFindBar editor={editor} request={{ ...openRequest(), open: false }} onClose={() => {}} onToggleReplace={() => {}} /></>);
    expect(document.querySelectorAll('.ProseMirror-search-match, .ProseMirror-active-search-match')).toHaveLength(0);
  });
});
