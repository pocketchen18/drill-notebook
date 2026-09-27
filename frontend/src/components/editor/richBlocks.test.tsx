import { afterEach, describe, expect, it, vi } from 'vitest';
import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { Editor, EditorContent } from '@tiptap/react';
import { notebookExtensions } from './editorExtensions';
import { TableMenu } from './TableMenu';

const editors: Editor[] = [];

function createEditor(content: string): Editor {
  const editor = new Editor({ extensions: notebookExtensions(), content });
  editors.push(editor);
  return editor;
}

afterEach(() => {
  cleanup();
  editors.splice(0).forEach((editor) => editor.destroy());
  vi.restoreAllMocks();
});

describe('代码块节点视图', () => {
  it('显示当前语言，未知别名原样保留；切换语言写回文档，选“纯文本”存为 null', async () => {
    const editor = createEditor('<pre><code class="language-js">let a = 1;</code></pre>');
    render(<EditorContent editor={editor} />);
    const select = await screen.findByRole('combobox', { name: '代码语言' }) as HTMLSelectElement;
    expect(select.value).toBe('js');
    fireEvent.change(select, { target: { value: 'python' } });
    expect(editor.getJSON().content?.[0]?.attrs?.language).toBe('python');
    fireEvent.change(select, { target: { value: '' } });
    expect(editor.getJSON().content?.[0]?.attrs?.language).toBeNull();
  });

  it('复制按钮复制代码文本并短暂显示“已复制”', async () => {
    const writeText = vi.fn().mockResolvedValue(undefined);
    Object.defineProperty(navigator, 'clipboard', { value: { writeText }, configurable: true });
    const editor = createEditor('<pre><code>print(1)</code></pre>');
    render(<EditorContent editor={editor} />);
    fireEvent.click(await screen.findByRole('button', { name: '复制代码' }));
    await waitFor(() => expect(writeText).toHaveBeenCalledWith('print(1)'));
    expect(await screen.findByRole('button', { name: '已复制' })).toBeInTheDocument();
  });

  it('新建的代码块不写入语言，旧文档里的 null 语言保持不变', () => {
    const editor = createEditor('<p>x</p>');
    editor.commands.setCodeBlock();
    expect(editor.getJSON().content?.[0]).toMatchObject({ type: 'codeBlock', attrs: { language: null } });
  });
});

describe('表格操作条', () => {
  it('光标进入表格且编辑器聚焦时出现，可插入行，并反映表头状态', async () => {
    const editor = createEditor('<table><tr><th><p>a</p></th><th><p>b</p></th></tr><tr><td><p>1</p></td><td><p>2</p></td></tr></table>');
    render(<><EditorContent editor={editor} /><TableMenu editor={editor} /></>);
    act(() => {
      // commands.focus() 会延迟到下一帧才聚焦，这里直接聚焦视图。
      editor.view.focus();
      editor.commands.setTextSelection(4);
    });
    // jsdom 没有布局，表格锚点尺寸为 0，操作条按设计保持 visibility: hidden；
    // 隐藏元素的无障碍名称为空，所以这里按 aria-label 查询。
    await waitFor(() => expect(document.querySelector('.editor-table-menu')).not.toBeNull());
    const toolbar = screen.getByLabelText('表格操作');
    expect(within(toolbar).getByLabelText('表头行')).toHaveAttribute('aria-pressed', 'true');
    fireEvent.click(within(toolbar).getByLabelText('下方插入行'));
    expect(editor.state.doc.firstChild?.childCount).toBe(3);
  });

  it('光标不在表格里时不出现', () => {
    const editor = createEditor('<p>正文</p>');
    render(<><EditorContent editor={editor} /><TableMenu editor={editor} /></>);
    act(() => { editor.view.focus(); });
    expect(document.querySelector('.editor-table-menu')).toBeNull();
  });
});
