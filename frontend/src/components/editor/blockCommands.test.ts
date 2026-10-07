import { afterEach, describe, expect, it } from 'vitest';
import { Editor } from '@tiptap/core';
import StarterKit from '@tiptap/starter-kit';
import TaskList from '@tiptap/extension-task-list';
import TaskItem from '@tiptap/extension-task-item';
import { TextSelection } from '@tiptap/pm/state';
import { BlockKeymap, blockAtSelection, canMoveBlock, deleteBlock, duplicateBlock, insertBlockBelow, moveBlock, turnInto } from './blockCommands';
import { NotebookCodeBlock } from './CodeBlockView';
import { MathInline } from './extensions';

const editors: Editor[] = [];

function createEditor(content: string | Record<string, unknown>): Editor {
  const editor = new Editor({
    extensions: [StarterKit.configure({ codeBlock: false }), TaskList, TaskItem.configure({ nested: true }), MathInline, NotebookCodeBlock, BlockKeymap],
    content
  });
  editors.push(editor);
  return editor;
}

function mathLatex(editor: Editor): string[] {
  const list: string[] = [];
  editor.state.doc.descendants((node) => {
    if (node.type.name === 'mathInline') list.push(String(node.attrs.latex));
  });
  return list;
}

/** 把光标放到第一个包含该文字的文本块里。 */
function placeCaret(editor: Editor, text: string): void {
  let target = -1;
  editor.state.doc.descendants((node, position) => {
    if (target < 0 && node.isText && node.text?.includes(text)) target = position + 1;
    return target < 0;
  });
  editor.view.dispatch(editor.state.tr.setSelection(TextSelection.create(editor.state.doc, target)));
}

afterEach(() => {
  editors.splice(0).forEach((editor) => editor.destroy());
});

describe('turnInto', () => {
  it('把列表项直接转成标题，而不是嵌套在列表里', () => {
    const editor = createEditor('<ul><li><p>一</p></li><li><p>二</p></li></ul>');
    placeCaret(editor, '二');
    turnInto(editor, 'heading2');
    expect(editor.getHTML()).toBe('<ul><li><p>一</p></li></ul><h2>二</h2>');
  });

  it('转代码块前先把行内公式摊成 $…$ 文字，前后文字与多公式都不丢，一步撤销可还原节点', () => {
    const editor = createEditor('<p>A<span data-math-inline="" latex="x^2"></span>B<span data-math-inline="" latex="y^3"></span>C</p>');
    placeCaret(editor, 'A');
    turnInto(editor, 'codeBlock');
    expect(editor.getHTML()).toContain('A$x^2$B$y^3$C');
    editor.commands.undo();
    expect(editor.getHTML()).toContain('latex="x^2"');
    expect(editor.getHTML()).toContain('latex="y^3"');
  });

  it('正文 → 代码块 → 正文 往返后公式节点原样回来', () => {
    const editor = createEditor('<p>A<span data-math-inline="" latex="x^2"></span>B<span data-math-inline="" latex="y^3"></span>C</p>');
    placeCaret(editor, 'A');
    turnInto(editor, 'codeBlock');
    expect(editor.getHTML()).toContain('A$x^2$B$y^3$C');
    turnInto(editor, 'paragraph');
    expect(mathLatex(editor)).toEqual(['x^2', 'y^3']);
    expect(editor.state.doc.textContent).toBe('ABC');
  });

  it('往返只恢复有来源的片段，手打的字面 $…$ 保持原样', () => {
    const editor = createEditor('<p>价格 $5 与 $x^2$ 共存<span data-math-inline="" latex="a_1"></span></p>');
    placeCaret(editor, '价格');
    turnInto(editor, 'codeBlock');
    turnInto(editor, 'paragraph');
    expect(mathLatex(editor)).toEqual(['a_1']);
    expect(editor.state.doc.textContent).toBe('价格 $5 与 $x^2$ 共存');
    expect(editor.getText()).toBe('价格 $5 与 $x^2$ 共存$a_1$');
  });

  it('代码块被编辑过后转回正文，不猜公式边界', () => {
    const editor = createEditor('<p>A<span data-math-inline="" latex="x^2"></span>B</p>');
    placeCaret(editor, 'A');
    turnInto(editor, 'codeBlock');
    editor.commands.setTextSelection(1);
    editor.commands.insertContent('#');
    turnInto(editor, 'paragraph');
    expect(mathLatex(editor)).toEqual([]);
    expect(editor.state.doc.textContent).toBe('#A$x^2$B');
  });

  it('跨多块选区转代码块同样不丢公式文字，只是不承诺恢复', () => {
    const editor = createEditor(
      '<p>P1<span data-math-inline="" latex="a_1"></span></p><p>P2<span data-math-inline="" latex="b_2"></span></p>'
    );
    editor.commands.setTextSelection({ from: 1, to: editor.state.doc.content.size - 1 });
    turnInto(editor, 'codeBlock');
    expect(editor.state.doc.textContent).toBe('P1$a_1$P2$b_2$');
    expect(editor.state.doc.children.map((child) => child.type.name)).toEqual(['codeBlock', 'codeBlock']);
    expect(JSON.stringify(editor.getJSON())).not.toContain('"mathSpans":[');
    editor.commands.setTextSelection({ from: 1, to: editor.state.doc.content.size - 1 });
    turnInto(editor, 'paragraph');
    expect(mathLatex(editor)).toEqual([]);
    expect(editor.state.doc.textContent).toBe('P1$a_1$P2$b_2$');
  });

  it('来源随文档 JSON 一起保存，重开笔记后仍能恢复', () => {
    const first = createEditor('<p>A<span data-math-inline="" latex="x^2"></span>B</p>');
    placeCaret(first, 'A');
    turnInto(first, 'codeBlock');
    const saved = JSON.parse(JSON.stringify(first.getJSON()));
    expect(JSON.stringify(saved)).toContain('mathSpans');

    const reopened = createEditor(saved);
    placeCaret(reopened, 'A$x^2$B');
    turnInto(reopened, 'paragraph');
    expect(mathLatex(reopened)).toEqual(['x^2']);
  });

  it.each([false, true])('部分跨段选区保留首尾未选中的公式，反向选择=%s', (backward) => {
    const editor = createEditor('<p><span data-math-inline="" latex="x^2"></span>AA</p><p>BB<span data-math-inline="" latex="y^3"></span></p>');
    const before = editor.getJSON();
    editor.commands.setTextSelection(backward ? { from: 7, to: 2 } : { from: 2, to: 7 });
    turnInto(editor, 'codeBlock');
    expect(editor.state.doc.children.map((node) => [node.type.name, node.textContent])).toEqual([
      ['codeBlock', '$x^2$AA'], ['codeBlock', 'BB$y^3$']
    ]);
    expect(JSON.stringify(editor.getJSON())).not.toContain('"mathSpans":[');
    editor.commands.undo();
    expect(editor.getJSON()).toEqual(before);
  });

  it.each(['before', 'after', 'both'])('单段 Ctrl+A 全选仍可往返恢复：%s', (when) => {
    const editor = createEditor('<p>A<span data-math-inline="" latex="x^2"></span>B<span data-math-inline="" latex="y^3"></span>C</p>');
    if (when !== 'after') editor.commands.selectAll();
    else editor.commands.setTextSelection(1);
    turnInto(editor, 'codeBlock');
    expect(editor.state.doc.firstChild?.attrs.mathSpans).toHaveLength(2);
    if (when !== 'before') editor.commands.selectAll();
    else editor.commands.setTextSelection(1);
    turnInto(editor, 'paragraph');
    expect(mathLatex(editor)).toEqual(['x^2', 'y^3']);
    expect(editor.state.doc.textContent).toBe('ABC');
  });

  it('NodeSelection 选中整个文本块也能记录和恢复公式', () => {
    const editor = createEditor('<p>A<span data-math-inline="" latex="x^2"></span>B</p>');
    editor.commands.setNodeSelection(0);
    turnInto(editor, 'codeBlock');
    expect(editor.state.doc.firstChild?.attrs.mathSpans).toHaveLength(1);
    editor.commands.setNodeSelection(0);
    turnInto(editor, 'paragraph');
    expect(mathLatex(editor)).toEqual(['x^2']);
  });

  it('全选多段仍只保留公式文字，不错误套用单块来源', () => {
    const editor = createEditor('<p>A<span data-math-inline="" latex="x"></span></p><p>B<span data-math-inline="" latex="y"></span></p>');
    editor.commands.selectAll();
    turnInto(editor, 'codeBlock');
    expect(editor.state.doc.children.map((node) => node.textContent)).toEqual(['A$x$', 'B$y$']);
    expect(JSON.stringify(editor.getJSON())).not.toContain('"mathSpans":[');
    turnInto(editor, 'paragraph');
    expect(mathLatex(editor)).toEqual([]);
    expect(editor.state.doc.textContent).toBe('A$x$B$y$');
  });

  it('列表项先解包再摊平时不重复映射选区，也不误改邻块', () => {
    const editor = createEditor('<ul><li><p>前</p></li><li><p>A<span data-math-inline="" latex="x^2"></span>B</p></li><li><p>后</p></li></ul>');
    placeCaret(editor, 'A');
    turnInto(editor, 'codeBlock');
    expect(editor.state.doc.children.map((node) => node.type.name)).toEqual(['bulletList', 'codeBlock', 'bulletList']);
    expect(editor.state.selection.$from.parent.type.name).toBe('codeBlock');
    turnInto(editor, 'paragraph');
    expect(mathLatex(editor)).toEqual(['x^2']);
    expect(editor.state.doc.textContent).toBe('前AB后');
  });

  it('链内先删除斜杠查询，再转换公式，只提交一次并保持正确选区', () => {
    const editor = createEditor('<p>/code A<span data-math-inline="" latex="x^2"></span>B</p>');
    editor.commands.setTextSelection(7);
    const before = editor.getJSON();
    turnInto(editor, 'codeBlock', null, editor.chain().deleteRange({ from: 1, to: 7 }));
    expect(editor.state.doc.firstChild?.textContent).toBe('A$x^2$B');
    expect(editor.state.doc.firstChild?.attrs.mathSpans).toHaveLength(1);
    editor.commands.undo();
    expect(editor.getJSON()).toEqual(before);
  });

  it('公式前有软换行时，代码块来源仍能按正确偏移恢复', () => {
    const editor = createEditor('<p>A<br>B<span data-math-inline="" latex="x^2"></span>C</p>');
    editor.commands.setTextSelection(1);
    turnInto(editor, 'codeBlock');
    expect(editor.state.doc.firstChild?.textContent).toBe('A\nB$x^2$C');
    expect(editor.state.doc.firstChild?.attrs.mathSpans).toHaveLength(1);
    turnInto(editor, 'paragraph');
    expect(mathLatex(editor)).toEqual(['x^2']);
    expect(editor.getText()).toBe('A\nB$x^2$C');
  });

  it.each(['c', '0'])('代码块快捷键保留公式，Ctrl+Alt+%s 返回正文也恢复节点', (exitKey) => {
    const editor = createEditor('<p>A<span data-math-inline="" latex="x^2"></span>B</p>');
    editor.commands.setTextSelection(1);
    const press = (key: string): void => {
      const event = new KeyboardEvent('keydown', { key, ctrlKey: true, altKey: true, bubbles: true, cancelable: true });
      editor.view.dom.dispatchEvent(event);
      expect(event.defaultPrevented).toBe(true);
    };
    press('c');
    expect(editor.state.doc.firstChild?.type.name).toBe('codeBlock');
    expect(editor.state.doc.firstChild?.textContent).toBe('A$x^2$B');
    expect(editor.state.doc.firstChild?.attrs.mathSpans).toHaveLength(1);
    press(exitKey);
    expect(mathLatex(editor)).toEqual(['x^2']);
    expect(editor.state.doc.firstChild?.type.name).toBe('paragraph');
    expect(editor.state.doc.textContent).toBe('AB');
  });

  it('快捷键在普通段落仍能开关代码块，代码块 Enter 换行行为保留', () => {
    const editor = createEditor('<p>plain</p>');
    editor.commands.setTextSelection(6);
    editor.view.dom.dispatchEvent(new KeyboardEvent('keydown', { key: 'c', ctrlKey: true, altKey: true, bubbles: true }));
    editor.view.dom.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true }));
    expect(editor.state.doc.firstChild?.type.name).toBe('codeBlock');
    expect(editor.state.doc.firstChild?.textContent).toBe('plain\n');
  });

  it.each(['invalid', [null], [{ start: -1, end: 6, latex: 'x^2' }]])('无效来源属性安全降级为文字：%j', (mathSpans) => {
    const editor = createEditor({ type: 'doc', content: [{ type: 'codeBlock', attrs: { mathSpans }, content: [{ type: 'text', text: 'A$x^2$B' }] }] });
    editor.commands.selectAll();
    expect(() => turnInto(editor, 'paragraph')).not.toThrow();
    expect(editor.state.doc.textContent).toBe('A$x^2$B');
    expect(mathLatex(editor)).toEqual([]);
  });

  it('按手柄目标转换整个块，并可一步撤销', () => {
    const editor = createEditor('<p>甲</p><p>乙</p>');
    placeCaret(editor, '甲');
    const target = { pos: editor.state.doc.child(0).nodeSize, node: editor.state.doc.child(1) };
    turnInto(editor, 'taskList', target);
    expect(editor.getHTML()).toContain('data-type="taskList"');
    expect(editor.getHTML()).toContain('<p>乙</p></div></li></ul>');
    editor.commands.undo();
    expect(editor.getHTML()).toBe('<p>甲</p><p>乙</p>');
  });

  it('当前已是目标类型时不做任何事', () => {
    const editor = createEditor('<p>正文</p>');
    placeCaret(editor, '正文');
    expect(turnInto(editor, 'paragraph')).toBe(false);
  });
});

describe('moveBlock', () => {
  it('顶层块与相邻块交换，光标跟随被移动的块', () => {
    const editor = createEditor('<p>A</p><p>B</p><p>C</p>');
    placeCaret(editor, 'C');
    expect(moveBlock(editor, -1)).toBe(true);
    expect(editor.getHTML()).toBe('<p>A</p><p>C</p><p>B</p>');
    expect(blockAtSelection(editor.state)?.node.textContent).toBe('C');
    expect(moveBlock(editor, 1)).toBe(true);
    expect(editor.getHTML()).toBe('<p>A</p><p>B</p><p>C</p>');
  });

  it('列表项只在所在列表内移动，到边界时不可移动', () => {
    const editor = createEditor('<ul><li><p>一</p></li><li><p>二</p></li></ul>');
    placeCaret(editor, '二');
    const target = blockAtSelection(editor.state);
    expect(target?.node.type.name).toBe('listItem');
    expect(canMoveBlock(editor.state, 1, target)).toBe(false);
    expect(moveBlock(editor, -1)).toBe(true);
    expect(editor.getHTML()).toBe('<ul><li><p>二</p></li><li><p>一</p></li></ul>');
  });

  it('移动是一步撤销', () => {
    const editor = createEditor('<p>A</p><p>B</p>');
    placeCaret(editor, 'B');
    moveBlock(editor, -1);
    editor.commands.undo();
    expect(editor.getHTML()).toBe('<p>A</p><p>B</p>');
  });
});

describe('duplicateBlock / deleteBlock / insertBlockBelow', () => {
  it('复制副本插在原块之后', () => {
    const editor = createEditor('<h2>标题</h2><p>尾</p>');
    placeCaret(editor, '标题');
    duplicateBlock(editor);
    expect(editor.getHTML()).toBe('<h2>标题</h2><h2>标题</h2><p>尾</p>');
  });

  it('删除列表的最后一项时一并删除空列表', () => {
    const editor = createEditor('<p>前</p><ul><li><p>唯一</p></li></ul><p>后</p>');
    placeCaret(editor, '唯一');
    deleteBlock(editor);
    expect(editor.getHTML()).toBe('<p>前</p><p>后</p>');
  });

  it('删除文档里唯一的块后留下一个空段落', () => {
    const editor = createEditor('<h1>只有我</h1>');
    placeCaret(editor, '只有我');
    deleteBlock(editor);
    expect(editor.getHTML()).toBe('<p></p>');
  });

  it('在列表项下方插入同级的新项，待办项默认未勾选', () => {
    const editor = createEditor('<ul data-type="taskList"><li data-type="taskItem" data-checked="true"><p>做完</p></li></ul>');
    placeCaret(editor, '做完');
    expect(insertBlockBelow(editor, blockAtSelection(editor.state))).toBe(true);
    const list = editor.state.doc.child(0);
    expect(list.childCount).toBe(2);
    expect(list.child(1).attrs.checked).toBe(false);
    expect(editor.state.selection.$from.parent.type.name).toBe('paragraph');
    expect(editor.state.selection.$from.node(-1).type.name).toBe('taskItem');
  });
});
