import { afterEach, describe, expect, it } from 'vitest';
import { Editor } from '@tiptap/core';
import StarterKit from '@tiptap/starter-kit';
import { SlashCommand, getSlashState } from './SlashCommand';
import { filterCommands, slashCommands } from './commandCatalog';

const editors: Editor[] = [];

function createEditor(text = ''): Editor {
  const paragraph = text ? { type: 'paragraph', content: [{ type: 'text', text }] } : { type: 'paragraph' };
  const editor = new Editor({ extensions: [StarterKit, SlashCommand], content: { type: 'doc', content: [paragraph] } });
  editors.push(editor);
  // 光标放在段落末尾，模拟接着已有文字继续输入。
  editor.commands.setTextSelection(1 + text.length);
  return editor;
}

function press(editor: Editor, key: string): boolean {
  let handled = false;
  editor.view.someProp('handleKeyDown', (handler) => {
    handled = Boolean(handler(editor.view, new KeyboardEvent('keydown', { key })));
    return handled;
  });
  return handled;
}

afterEach(() => {
  editors.splice(0).forEach((editor) => editor.destroy());
});

describe('“/” 触发规则', () => {
  it('块首输入 “/” 打开菜单，之后的输入作为查询词', () => {
    const editor = createEditor();
    editor.commands.insertContent('/');
    expect(getSlashState(editor.state)).toMatchObject({ active: true, from: 1, query: '' });
    editor.commands.insertContent('bt');
    expect(getSlashState(editor.state)).toMatchObject({ active: true, query: 'bt' });
  });

  it('词中的 “/” 不触发（例如 a/b、网址）', () => {
    const editor = createEditor('and');
    editor.commands.insertContent('/');
    expect(getSlashState(editor.state).active).toBe(false);
  });

  it('空白后的全角 “／” 触发', () => {
    const editor = createEditor('说明 ');
    editor.commands.insertContent('／');
    expect(getSlashState(editor.state).active).toBe(true);
  });

  it('中文输入法下的 “、” 只在块首触发，不影响顿号', () => {
    const atStart = createEditor();
    atStart.commands.insertContent('、');
    expect(getSlashState(atStart.state).active).toBe(true);
    const afterWord = createEditor('苹果');
    afterWord.commands.insertContent('、');
    expect(getSlashState(afterWord.state).active).toBe(false);
  });

  it('代码块内与粘贴进来的 “/” 都不触发', () => {
    const editor = createEditor();
    editor.commands.setCodeBlock();
    editor.commands.insertContent('/');
    expect(getSlashState(editor.state).active).toBe(false);

    const pasted = createEditor();
    pasted.view.dispatch(pasted.state.tr.insertText('/').setMeta('paste', true));
    expect(getSlashState(pasted.state).active).toBe(false);
  });

  it('输入空格或光标离开触发位置时关闭', () => {
    const editor = createEditor();
    editor.commands.insertContent('/');
    editor.commands.insertContent(' ');
    expect(getSlashState(editor.state).active).toBe(false);

    const moved = createEditor('前文 ');
    moved.commands.insertContent('/');
    moved.commands.setTextSelection(1);
    expect(getSlashState(moved.state).active).toBe(false);
    // 光标移回旧的 “/” 之后也不会重新弹出。
    moved.commands.setTextSelection(5);
    expect(getSlashState(moved.state).active).toBe(false);
  });

  it('Esc 关闭后继续输入不会重新打开', () => {
    const editor = createEditor();
    editor.commands.insertContent('/');
    expect(press(editor, 'Escape')).toBe(true);
    expect(getSlashState(editor.state).active).toBe(false);
    editor.commands.insertContent('bt');
    expect(getSlashState(editor.state).active).toBe(false);
  });

  it('菜单未打开时 Enter 交给编辑器正常处理', () => {
    const editor = createEditor('正文');
    expect(getSlashState(editor.state).active).toBe(false);
    press(editor, 'Enter');
    expect(editor.state.doc.childCount).toBe(2);
  });
});

describe('命令筛选', () => {
  it('中文、英文、拼音全拼与首字母都能命中，前缀命中排在前面', () => {
    const commands = slashCommands();
    expect(filterCommands(commands, 'bg')[0]?.label).toBe('表格');
    expect(filterCommands(commands, 'daiban')[0]?.label).toBe('待办清单');
    expect(filterCommands(commands, 'h2')[0]?.label).toBe('标题 2');
    expect(filterCommands(commands, '公式')[0]?.label).toBe('公式块');
    expect(filterCommands(commands, 'mermaid')[0]?.label).toBe('Mermaid 图表');
    expect(filterCommands(commands, 'zzz')).toEqual([]);
  });

  it('空查询保持目录顺序：基础块在前，插入项在后', () => {
    const labels = filterCommands(slashCommands(), '').map((command) => command.label);
    expect(labels.slice(0, 2)).toEqual(['正文', '标题 1']);
    expect(labels.indexOf('表格')).toBeGreaterThan(labels.indexOf('代码块'));
  });
});
