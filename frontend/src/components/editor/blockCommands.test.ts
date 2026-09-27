import { afterEach, describe, expect, it } from 'vitest';
import { Editor } from '@tiptap/core';
import StarterKit from '@tiptap/starter-kit';
import TaskList from '@tiptap/extension-task-list';
import TaskItem from '@tiptap/extension-task-item';
import { TextSelection } from '@tiptap/pm/state';
import { blockAtSelection, canMoveBlock, deleteBlock, duplicateBlock, insertBlockBelow, moveBlock, turnInto } from './blockCommands';

const editors: Editor[] = [];

function createEditor(content: string): Editor {
  const editor = new Editor({ extensions: [StarterKit, TaskList, TaskItem.configure({ nested: true })], content });
  editors.push(editor);
  return editor;
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
