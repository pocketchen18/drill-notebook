/**
 * 草稿闭环：真实编辑器 → onChange 更新父组件 state → 作为 content 回传。
 * 笔记页就是这样接编辑器的；只用 vi.fn() 接 onChange 的测试覆盖不到回传竞态。
 */
import { afterEach, beforeAll, describe, expect, it } from 'vitest';
import { useState } from 'react';
import { act, cleanup, fireEvent, render, waitFor } from '@testing-library/react';
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

afterEach(() => cleanup());
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
