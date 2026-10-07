import { afterEach, describe, expect, it } from 'vitest';
import { act, cleanup, render, screen } from '@testing-library/react';
import { Editor } from '@tiptap/react';
import { notebookExtensions } from './editorExtensions';
import { EditorStatusBar } from './EditorStatusBar';

const editors: Editor[] = [];

function mount(content: string): Editor {
  const editor = new Editor({ extensions: notebookExtensions(), content });
  editors.push(editor);
  render(<EditorStatusBar editor={editor} />);
  return editor;
}

afterEach(() => {
  cleanup();
  editors.splice(0).forEach((editor) => editor.destroy());
});

describe('字符统计口径', () => {
  it('全选公式混排段落时，已选与总数都按可复制的源码计算', () => {
    const editor = mount('<p>A<span data-math-inline="" latex="x^2"></span>B<span data-math-inline="" latex="y^3"></span>C</p>');
    act(() => { editor.commands.selectAll(); });
    expect(screen.getByRole('status')).toHaveTextContent('13 字符');
    expect(screen.getByRole('status')).toHaveTextContent('已选 13 字');
    act(() => { editor.commands.setTextSelection({ from: 2, to: 3 }); });
    expect(screen.getByRole('status')).toHaveTextContent('已选 5 字');
  });

  it('块级公式、软换行、空白与 Unicode 使用同一统计规则，复制仍保留换行', () => {
    const editor = mount('<p>A<br>B 😀</p><div data-math-block="" latex="x^2"></div>');
    act(() => { editor.commands.selectAll(); });
    const expected = Array.from(editor.getText().replace(/\s/g, '')).length;
    expect(expected).toBe(10);
    expect(screen.getByRole('status')).toHaveTextContent(`${expected} 字符`);
    expect(screen.getByRole('status')).toHaveTextContent(`已选 ${expected} 字`);
    const slice = editor.state.selection.content();
    expect(editor.view.someProp('clipboardTextSerializer', (serialize) => serialize(slice, editor.view))).toContain('A\nB 😀');
  });

  it('只选普通文字按选区长度统计，光标折叠后不显示已选', () => {
    const editor = mount('<p>A<span data-math-inline="" latex="x^2"></span>BC</p>');
    act(() => { editor.commands.setTextSelection({ from: 3, to: 4 }); });
    expect(screen.getByRole('status')).toHaveTextContent('已选 1 字');
    act(() => { editor.commands.setTextSelection(4); });
    expect(screen.getByRole('status')).not.toHaveTextContent('已选');
  });
});
