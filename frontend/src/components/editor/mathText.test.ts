import { afterEach, describe, expect, it } from 'vitest';
import { Editor } from '@tiptap/core';
import { notebookExtensions } from './editorExtensions';

const mixed = '<p>A<span data-math-inline="" latex="x^2"></span>B<span data-math-inline="" latex="y^3"></span>C</p><div data-math-block="" latex="E=mc^2"></div>';
const editors: Editor[] = [];

function mount(content: string): Editor {
  const editor = new Editor({ extensions: notebookExtensions(), content });
  editors.push(editor);
  return editor;
}

/** 走 PM 真正的复制路径：serializeForClipboard 调 clipboardTextSerializer（TipTap core 已注册，读 toText）。 */
function plainTextOf(editor: Editor): string {
  editor.commands.selectAll();
  const slice = editor.state.selection.content();
  return editor.view.someProp('clipboardTextSerializer', (serialize) => serialize(slice, editor.view)) ?? '';
}

afterEach(() => editors.splice(0).forEach((editor) => editor.destroy()));

describe('公式的纯文本序列化', () => {
  it('复制混合段落时行内公式输出 $…$，前后文字不动', () => {
    expect(plainTextOf(mount(mixed))).toContain('A$x^2$B$y^3$C');
  });

  it('软换行照旧输出换行，不被公式序列化吞掉', () => {
    const editor = mount('<p>A<br>B<span data-math-inline="" latex="x^2"></span>C</p>');
    editor.commands.selectAll();
    const slice = editor.state.selection.content();
    expect(editor.view.someProp('clipboardTextSerializer', (serialize) => serialize(slice, editor.view))).toBe('A\nB$x^2$C');
  });

  it('独立公式块输出 $$…$$', () => {
    expect(plainTextOf(mount(mixed))).toContain('$$E=mc^2$$');
  });

  it('只选一个公式时纯文本就是它的 LaTeX', () => {
    const editor = mount('<p><span data-math-inline="" latex="a_1"></span></p>');
    editor.commands.setTextSelection({ from: 1, to: 2 });
    const slice = editor.state.selection.content();
    expect(editor.view.someProp('clipboardTextSerializer', (serialize) => serialize(slice, editor.view))).toBe('$a_1$');
  });

  it('富文本序列化仍是公式节点，编辑器内粘贴不降级成文字', () => {
    const html = mount(mixed).getHTML();
    expect(html).toContain('data-math-inline');
    expect(html).toContain('latex="x^2"');
  });

  it('getText 与剪贴板口径一致', () => {
    expect(mount(mixed).getText()).toContain('$x^2$');
  });
});
