/**
 * 草稿闭环：真实编辑器 → onChange 更新父组件 state → 作为 content 回传。
 * 笔记页就是这样接编辑器的；只用 vi.fn() 接 onChange 的测试覆盖不到回传竞态。
 */
import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest';
import { useState } from 'react';
import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import type { Editor } from '@tiptap/core';
import { closeHistory } from '@tiptap/pm/history';
import { NotebookEditor } from './NotebookEditor';

const emptyDoc = { type: 'doc', content: [{ type: 'paragraph' }] };

function LoopHost({ initial = emptyDoc }: { initial?: Record<string, unknown> }): JSX.Element {
  const [content, setContent] = useState<Record<string, unknown>>(initial);
  return <NotebookEditor content={content} onChange={setContent} pageId={1} />;
}

async function mountedEditor(): Promise<Editor> {
  let editor: Editor | undefined;
  await waitFor(() => {
    editor = (document.querySelector('.ProseMirror') as (HTMLElement & { editor?: Editor }) | null)?.editor;
    expect(editor).toBeDefined();
  });
  return editor!;
}

function topLevelTypes(editor: Editor): string[] {
  return (editor.getJSON().content ?? []).map((node) => String(node.type));
}

afterEach(() => { cleanup(); vi.restoreAllMocks(); });
// 撤销会滚动到选区，ProseMirror 需要 jsdom 未实现的布局 API；这里只要不抛错。
beforeAll(() => {
  const emptyRects = (): DOMRectList => Object.assign([], { item: () => null }) as unknown as DOMRectList;
  Range.prototype.getClientRects ??= emptyRects;
  Range.prototype.getBoundingClientRect ??= () => new DOMRect();
  Element.prototype.getClientRects ??= emptyRects;
  window.scrollBy = () => {};
});

describe('斜杠命令 + 草稿回传闭环', () => {
  const cases = [
    { id: 'markdownBlock', node: 'markdownBlock', dom: '.markdown-block' },
    { id: 'mermaidBlock', node: 'mermaidBlock', dom: '.mermaid-block' },
    { id: 'mathBlock', node: 'mathBlock', dom: '.math-block' },
    { id: 'codeBlock', node: 'codeBlock', dom: 'pre' },
    { id: 'mathInline', node: 'mathInline', dom: '.math-inline' }
  ] as const;

  it.each(cases)('插入 $id：文档、DOM 一致，“/” 被删除，撤销一步复原', async ({ id, node, dom }) => {
    render(<LoopHost />);
    const editor = await mountedEditor();
    act(() => {
      editor.view.focus();
      editor.commands.insertContent('/');
      // 与后续命令分开成两个撤销分组，才能验证命令本身只占一步。
      editor.view.dispatch(closeHistory(editor.state.tr));
    });
    const item = await waitFor(() => {
      const element = document.getElementById(`editor-slash-${id}`);
      expect(element).not.toBeNull();
      return element!;
    });
    act(() => { fireEvent.click(item); });

    const json = JSON.stringify(editor.getJSON());
    expect(json).toContain(`"type":"${node}"`);
    expect(editor.state.doc.textContent).not.toContain('/');
    expect(document.querySelectorAll(`.ProseMirror ${dom}`).length).toBe(1);

    // 删除 “/” 与插入同一事务：一次撤销回到只有 “/” 的状态。
    act(() => { editor.commands.undo(); });
    expect(editor.state.doc.textContent).toBe('/');
    expect(JSON.stringify(editor.getJSON())).not.toContain(`"type":"${node}"`);
  });
});

describe('内容同步边界', () => {
  it('公式完成、取消与后续输入在父组件回传后仍保持焦点和文档一致', async () => {
    const initial = { type: 'doc', content: [{ type: 'mathBlock', attrs: { latex: 'x^2' } }] };
    render(<LoopHost initial={initial} />);
    const editor = await mountedEditor();
    fireEvent.doubleClick(await screen.findByRole('button', { name: '编辑公式块' }));
    let input = await screen.findByRole('textbox', { name: '编辑 LaTeX 公式' });
    await waitFor(() => expect(input).toHaveFocus());
    fireEvent.change(input, { target: { value: 'x^3' } });
    fireEvent.keyDown(input, { key: 'Enter', ctrlKey: true });
    await waitFor(() => expect(editor.view.dom).toHaveFocus());
    expect(topLevelTypes(editor)).toEqual(['mathBlock', 'paragraph']);
    expect(editor.state.doc.firstChild?.attrs.latex).toBe('x^3');
    expect(editor.state.selection.from).toBe(2);
    act(() => { editor.commands.undo(); });
    expect(editor.getJSON()).toEqual(initial);
    act(() => { editor.commands.redo(); });

    fireEvent.doubleClick(screen.getByRole('button', { name: '编辑公式块' }));
    input = await screen.findByRole('textbox', { name: '编辑 LaTeX 公式' });
    await waitFor(() => expect(input).toHaveFocus());
    fireEvent.change(input, { target: { value: 'discarded' } });
    const beforeCancel = editor.getJSON();
    fireEvent.keyDown(input, { key: 'Escape' });
    const preview = await screen.findByRole('button', { name: '编辑公式块' });
    await waitFor(() => expect(preview).toHaveFocus());
    expect(editor.getJSON()).toEqual(beforeCancel);
    fireEvent.keyDown(document.activeElement!, { key: 'Enter' });
    input = await screen.findByRole('textbox', { name: '编辑 LaTeX 公式' });
    await waitFor(() => expect(input).toHaveFocus());
    expect(input).toHaveValue('x^3');
    fireEvent.click(screen.getByRole('button', { name: '完成' }));
    await waitFor(() => expect(editor.view.dom).toHaveFocus());
    act(() => { editor.commands.insertContent([
      { type: 'text', text: 'A' }, { type: 'mathInline', attrs: { latex: 'y^2' } }, { type: 'text', text: 'B' }
    ]); });
    fireEvent.doubleClick(screen.getByRole('button', { name: '编辑行内公式' }));
    const inlineInput = await screen.findByRole('textbox', { name: '编辑行内 LaTeX' });
    await waitFor(() => expect(inlineInput).toHaveFocus());
    fireEvent.change(inlineInput, { target: { value: 'discarded' } });
    fireEvent.keyDown(inlineInput, { key: 'Escape' });
    await waitFor(() => expect(editor.view.dom).toHaveFocus());
    act(() => { editor.commands.insertContent('z'); });
    expect(editor.getText()).toBe('$$x^3$$\n\nA$y^2$zB');
    expect(screen.getByRole('button', { name: '编辑行内公式' })).toBeVisible();
  });

  it('块公式浮窗的复制、编辑、删除与父组件回传保持一致，并可逐步撤销', async () => {
    vi.spyOn(HTMLElement.prototype, 'getBoundingClientRect').mockReturnValue(new DOMRect(200, 160, 160, 50));
    const initial = { type: 'doc', content: [
      { type: 'mathBlock', attrs: { latex: 'x^2' } },
      { type: 'mathBlock', attrs: { latex: 'y^3' } },
      { type: 'paragraph' }
    ] };
    render(<LoopHost initial={initial} />);
    const editor = await mountedEditor();
    fireEvent.click((await screen.findAllByRole('button', { name: '编辑公式块' }))[1]);
    const toolbar = await screen.findByRole('toolbar', { name: '选中块操作' });
    fireEvent.click(within(toolbar).getByRole('button', { name: '复制副本' }));
    expect(editor.state.doc.children.filter((node) => node.type.name === 'mathBlock').map((node) => node.attrs.latex)).toEqual(['x^2', 'y^3', 'y^3']);
    expect(screen.getAllByRole('button', { name: '编辑公式块' })).toHaveLength(3);

    // 复制后焦点在正文，Enter 也应编辑所选副本，而不是插入一个新段落。
    const duplicated = editor.getJSON();
    fireEvent.keyDown(editor.view.dom, { key: 'Enter' });
    const input = await screen.findByRole('textbox', { name: '编辑 LaTeX 公式' });
    await waitFor(() => expect(input).toHaveFocus());
    expect(input).toHaveValue('y^3');
    expect(editor.getJSON()).toEqual(duplicated);
    expect(screen.getAllByRole('button', { name: '编辑公式块' })).toHaveLength(2);
    fireEvent.keyDown(input, { key: 'Escape' });
    act(() => { editor.commands.undo(); });
    expect(editor.getJSON()).toEqual(initial);

    fireEvent.click(screen.getAllByRole('button', { name: '编辑公式块' })[0]);
    fireEvent.click(within(await screen.findByRole('toolbar', { name: '选中块操作' })).getByRole('button', { name: '删除' }));
    expect(editor.state.doc.firstChild?.attrs.latex).toBe('y^3');
    expect(screen.getAllByRole('button', { name: '编辑公式块' })).toHaveLength(1);
    act(() => { editor.commands.undo(); });
    expect(editor.getJSON()).toEqual(initial);
  });

  it.each(['c', '0'])('完整编辑器快捷键往返（返回键 %s）不被父组件回传覆盖', async (exitKey) => {
    const initial = { type: 'doc', content: [{ type: 'paragraph', content: [
      { type: 'text', text: 'A' }, { type: 'mathInline', attrs: { latex: 'x^2' } }, { type: 'text', text: 'B' }
    ] }] };
    render(<LoopHost initial={initial} />);
    const editor = await mountedEditor();
    act(() => {
      editor.view.focus();
      editor.commands.selectAll();
      fireEvent.keyDown(editor.view.dom, { key: 'c', ctrlKey: true, altKey: true });
    });
    expect(editor.state.doc.firstChild?.type.name).toBe('codeBlock');
    expect(editor.state.doc.firstChild?.attrs.mathSpans).toHaveLength(1);
    expect(editor.state.doc.textContent).toBe('A$x^2$B');
    act(() => {
      editor.view.dispatch(closeHistory(editor.state.tr));
      fireEvent.keyDown(editor.view.dom, { key: exitKey, ctrlKey: true, altKey: true });
    });
    expect(editor.getJSON()).toEqual(initial);
    expect(document.querySelectorAll('.math-inline')).toHaveLength(1);
    act(() => { editor.commands.undo(); });
    expect(editor.state.doc.firstChild?.type.name).toBe('codeBlock');
    expect(editor.state.doc.firstChild?.attrs.mathSpans).toHaveLength(1);
  });

  it('父组件回传编辑器自己发出的旧草稿（落后一笔事务）时不覆盖编辑器', async () => {
    const emitted: Record<string, unknown>[] = [];
    const { rerender } = render(<NotebookEditor content={emptyDoc} onChange={(value) => emitted.push(value)} pageId={1} />);
    const editor = await mountedEditor();
    act(() => { editor.commands.insertContent('甲'); });
    act(() => { editor.commands.insertContent('乙'); });
    expect(emitted).toHaveLength(2);

    rerender(<NotebookEditor content={emitted[0]} onChange={(value) => emitted.push(value)} pageId={1} />);
    expect(editor.state.doc.textContent).toBe('甲乙');
  });

  it('外部来的新内容（如服务端快照）仍整篇替换', async () => {
    const { rerender } = render(<NotebookEditor content={emptyDoc} pageId={1} />);
    const editor = await mountedEditor();
    const external = { type: 'doc', content: [{ type: 'heading', attrs: { level: 1 }, content: [{ type: 'text', text: '服务端版本' }] }] };
    rerender(<NotebookEditor content={external} pageId={1} />);
    expect(topLevelTypes(editor)).toEqual(['heading']);
    expect(editor.state.doc.textContent).toBe('服务端版本');
  });
});
