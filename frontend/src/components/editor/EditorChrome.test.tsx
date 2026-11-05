import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { Editor, EditorContent } from '@tiptap/react';
import StarterKit from '@tiptap/starter-kit';
import { BlockDragHandle, EditorBubbleMenu, EditorOutline, focusFieldSoon } from './EditorChrome';
import { MathBlock, MathInline } from './extensions';

const selectionRect = { x: 200, y: 160, top: 160, left: 200, right: 320, bottom: 180, width: 120, height: 20, toJSON: () => ({}) } as DOMRect;
const editors: Editor[] = [];

// jsdom 有选区但没有布局；保留真实的 TipTap 命令与撤销历史。
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

describe('源码框初始光标', () => {
  it.each(['input', 'textarea'] as const)('%s 自动接管焦点时可定位到源码末尾', async (tag) => {
    render(<div className="ProseMirror" tabIndex={0}>{tag === 'input' ? <input aria-label="源码" defaultValue="x^2" /> : <textarea aria-label="源码" defaultValue={'x^2\n+y^3'} />}</div>);
    const field = screen.getByRole('textbox', { name: '源码' }) as HTMLInputElement | HTMLTextAreaElement;
    field.setSelectionRange(0, 0);
    field.parentElement!.focus();
    focusFieldSoon(() => field, { caretAtEnd: true });
    await waitFor(() => expect(field).toHaveFocus());
    expect([field.selectionStart, field.selectionEnd]).toEqual([field.value.length, field.value.length]);
  });

  it('未指定末尾定位的其它源码块维持原光标', async () => {
    render(<div className="ProseMirror" tabIndex={0}><textarea aria-label="源码" defaultValue="abcdef" /></div>);
    const field = screen.getByRole('textbox', { name: '源码' }) as HTMLTextAreaElement;
    field.setSelectionRange(1, 3);
    field.parentElement!.focus();
    focusFieldSoon(() => field);
    await waitFor(() => expect(field).toHaveFocus());
    expect([field.selectionStart, field.selectionEnd]).toEqual([1, 3]);
  });

  it.each(['manual', 'outside', 'cancelled'])('延迟聚焦不覆盖用户已有操作：%s', async (action) => {
    render(<><div className="ProseMirror" tabIndex={0}><textarea aria-label="源码" defaultValue="abcdef" /></div><input aria-label="外部" /></>);
    const field = screen.getByRole('textbox', { name: '源码' }) as HTMLTextAreaElement;
    field.parentElement!.focus();
    field.setSelectionRange(1, 3);
    const cancel = focusFieldSoon(() => field, { caretAtEnd: true });
    if (action === 'manual') field.focus();
    if (action === 'outside') screen.getByRole('textbox', { name: '外部' }).focus();
    if (action === 'cancelled') cancel();
    const focused = document.activeElement;
    await act(async () => { await new Promise((resolve) => window.requestAnimationFrame(() => resolve(null))); });
    expect(document.activeElement).toBe(focused);
    expect([field.selectionStart, field.selectionEnd]).toEqual([1, 3]);
  });
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

  it('follows the selection while the page scrolls and hides when focus leaves the editor', async () => {
    vi.spyOn(Range.prototype, 'getBoundingClientRect').mockReturnValue(selectionRect);
    const editor = mountEditor('<p>selected text</p>');
    act(() => { editor.commands.setTextSelection({ from: 1, to: 5 }); });
    await screen.findByRole('toolbar', { name: '选中文本格式' });
    fireEvent.scroll(document);
    await act(async () => { await new Promise((resolve) => window.requestAnimationFrame(() => resolve(null))); });
    expect(screen.getByRole('toolbar', { name: '选中文本格式' })).toBeInTheDocument();
    act(() => { editor.commands.blur(); });
    await waitFor(() => expect(screen.queryByRole('toolbar')).toBeNull());
  });

  it('hides once the selection has scrolled out of the viewport', async () => {
    const rect = vi.spyOn(Range.prototype, 'getBoundingClientRect').mockReturnValue(selectionRect);
    const editor = mountEditor('<p>selected text</p>');
    act(() => { editor.commands.setTextSelection({ from: 1, to: 5 }); });
    await screen.findByRole('toolbar', { name: '选中文本格式' });
    rect.mockReturnValue({ ...selectionRect, top: -200, bottom: -180 } as DOMRect);
    fireEvent.scroll(document);
    await waitFor(() => expect(screen.queryByRole('toolbar', { name: '选中文本格式' })).toBeNull());
  });
});

describe('EditorBubbleMenu 行内公式', () => {
  function mountMathDoc(html: string): Editor {
    const editor = new Editor({ extensions: [StarterKit, MathInline], content: html });
    editors.push(editor);
    render(<><EditorContent editor={editor} /><EditorBubbleMenu editor={editor} /></>);
    act(() => { editor.view.focus(); });
    return editor;
  }

  async function mathButton(): Promise<HTMLElement> {
    const toolbar = await screen.findByRole('toolbar', { name: '选中文本格式' });
    return within(toolbar).getByRole('button', { name: '转为行内公式' });
  }

  it('converts the selection to a formula and back without losing text', async () => {
    vi.spyOn(Range.prototype, 'getBoundingClientRect').mockReturnValue(selectionRect);
    vi.spyOn(HTMLElement.prototype, 'getBoundingClientRect').mockReturnValue(selectionRect);
    const editor = mountMathDoc('<p>质能方程 rest</p>');
    act(() => { editor.commands.setTextSelection({ from: 1, to: 5 }); });
    fireEvent.click(await mathButton());
    expect(JSON.stringify(editor.getJSON())).toContain('"latex":"质能方程"');

    // 选中公式后再点同一个按钮 = 取消公式；旧实现会把 LaTeX 写成空串，内容直接消失
    act(() => { editor.commands.setTextSelection({ from: 1, to: 2 }); });
    const toggle = await mathButton();
    expect(toggle).toHaveAttribute('aria-pressed', 'true');
    fireEvent.click(toggle);
    expect(editor.getText()).toBe('质能方程 rest');
    expect(JSON.stringify(editor.getJSON())).not.toContain('mathInline');
  });

  it('a single click hands the formula to the selection so the same bubble bar toggles it back', async () => {
    vi.spyOn(HTMLElement.prototype, 'getBoundingClientRect').mockReturnValue(selectionRect);
    const editor = mountMathDoc('<p>质能方程 rest</p>');
    act(() => { editor.commands.setTextSelection({ from: 1, to: 5 }); });
    fireEvent.click(await mathButton());
    expect(JSON.stringify(editor.getJSON())).toContain('"latex":"质能方程"');

    // 拖选跨不进 contenteditable=false 的岛，所以单击直接把公式交给选区，取消复用同一条浮窗。
    fireEvent.click(document.querySelector('.math-inline.is-preview')!);
    expect([editor.state.selection.from, editor.state.selection.to]).toEqual([1, 2]);
    const toggle = await mathButton();
    expect(toggle).toHaveAttribute('aria-pressed', 'true');
    fireEvent.click(toggle);
    expect(editor.getText()).toBe('质能方程 rest');
    expect(JSON.stringify(editor.getJSON())).not.toContain('mathInline');
  });

  it('double-click still opens the LaTeX editor', async () => {
    mountMathDoc('<p><span data-math-inline="" latex="x^2"></span></p>');
    const preview = await screen.findByRole('button', { name: '编辑行内公式' });
    fireEvent.doubleClick(preview);
    const input = await screen.findByRole('textbox', { name: '编辑行内 LaTeX' }) as HTMLInputElement;
    await waitFor(() => expect(input).toHaveFocus());
    expect(input).toHaveValue('x^2');
    expect([input.selectionStart, input.selectionEnd]).toEqual([3, 3]);
  });

  it('双击进编辑态会折掉覆盖公式的选区，编辑框不再叠着选中轮廓', async () => {
    vi.spyOn(HTMLElement.prototype, 'getBoundingClientRect').mockReturnValue(selectionRect);
    const editor = mountMathDoc('<p><span data-math-inline="" latex="x^2"></span>rest</p>');
    const preview = await screen.findByRole('button', { name: '编辑行内公式' });
    fireEvent.click(preview);
    expect([editor.state.selection.from, editor.state.selection.to]).toEqual([1, 2]);
    expect(document.querySelector('.math-inline')).toHaveClass('is-selected');

    fireEvent.doubleClick(preview);
    await screen.findByRole('textbox', { name: '编辑行内 LaTeX' });
    expect(editor.state.selection.empty).toBe(true);
    expect(document.querySelector('.math-inline.is-editing')).not.toHaveClass('is-selected');
  });

  it('Enter 提交后光标停在公式之后，焦点交回正文以便继续输入', async () => {
    const editor = mountMathDoc('<p><span data-math-inline="" latex="x^2"></span>rest</p>');
    fireEvent.doubleClick(await screen.findByRole('button', { name: '编辑行内公式' }));
    const input = await screen.findByRole('textbox', { name: '编辑行内 LaTeX' });
    fireEvent.change(input, { target: { value: 'x^3' } });
    fireEvent.keyDown(input, { key: 'Enter' });
    await waitFor(() => expect(JSON.stringify(editor.getJSON())).toContain('x^3'));
    expect(editor.state.selection.empty).toBe(true);
    expect(editor.state.selection.from).toBe(2);
    expect(document.activeElement).toBe(editor.view.dom);
  });

  it('点别处失焦只负责保存，不把焦点抢回编辑器', async () => {
    const editor = mountMathDoc('<p><span data-math-inline="" latex="x^2"></span>rest</p>');
    fireEvent.doubleClick(await screen.findByRole('button', { name: '编辑行内公式' }));
    const input = await screen.findByRole('textbox', { name: '编辑行内 LaTeX' });
    const other = document.createElement('input');
    document.body.appendChild(other);
    act(() => { other.focus(); });
    fireEvent.change(input, { target: { value: 'x^3' } });
    fireEvent.blur(input);
    expect(document.activeElement).toBe(other);
    await waitFor(() => expect(JSON.stringify(editor.getJSON())).toContain('x^3'));
    other.remove();
  });

  it('行内 Esc 丢弃草稿并交回公式后的光标，随后失焦不能提交草稿', async () => {
    const editor = mountMathDoc('<p>A<span data-math-inline="" latex="x^2"></span>B</p>');
    const before = editor.getJSON();
    fireEvent.doubleClick(await screen.findByRole('button', { name: '编辑行内公式' }));
    const input = await screen.findByRole('textbox', { name: '编辑行内 LaTeX' });
    await waitFor(() => expect(input).toHaveFocus());
    fireEvent.change(input, { target: { value: 'discarded' } });
    fireEvent.keyDown(input, { key: 'Escape' });
    await waitFor(() => expect(editor.view.dom).toHaveFocus());
    expect(editor.getJSON()).toEqual(before);
    expect(editor.can().undo()).toBe(false);
    expect(editor.state.selection.empty).toBe(true);
    expect(editor.state.selection.from).toBe(3);
    act(() => { editor.commands.insertContent('z'); });
    expect(editor.getText()).toBe('A$x^2$zB');

    // 取消之后还能再次编辑提交，防重入标记不能永久挡住保存。
    fireEvent.doubleClick(screen.getByRole('button', { name: '编辑行内公式' }));
    const reopened = await screen.findByRole('textbox', { name: '编辑行内 LaTeX' });
    await waitFor(() => expect(reopened).toHaveFocus());
    expect(reopened).toHaveValue('x^2');
    expect([(reopened as HTMLInputElement).selectionStart, (reopened as HTMLInputElement).selectionEnd]).toEqual([3, 3]);
    fireEvent.change(reopened, { target: { value: 'x^3' } });
    fireEvent.keyDown(reopened, { key: 'Enter' });
    expect(editor.getText()).toBe('A$x^3$zB');
  });

  it('ignores a textless selection instead of overwriting the formula with an empty one', async () => {
    vi.spyOn(Range.prototype, 'getBoundingClientRect').mockReturnValue(selectionRect);
    const editor = mountMathDoc('<p>质能方程 rest</p>');
    act(() => { editor.commands.setTextSelection({ from: 1, to: 5 }); });
    fireEvent.click(await mathButton());
    const before = JSON.stringify(editor.getJSON());

    // 选区比公式大一点：既没命中“恰好一个公式”，也取不到文字 → 保持原样
    act(() => { editor.commands.setTextSelection({ from: 1, to: 3 }); });
    fireEvent.click(await mathButton());
    expect(JSON.stringify(editor.getJSON())).toBe(before);
  });

  it.each([
    '<p>A<span data-math-inline="" latex="x^2"></span>B</p>',
    '<p><span data-math-inline="" latex="x"></span> + <span data-math-inline="" latex="y"></span></p>'
  ])('disables mixed formula conversion without changing the selection content: %s', async (html) => {
    vi.spyOn(Range.prototype, 'getBoundingClientRect').mockReturnValue(selectionRect);
    vi.spyOn(HTMLElement.prototype, 'getBoundingClientRect').mockReturnValue(selectionRect);
    const editor = mountMathDoc(html);
    const before = editor.getJSON();
    act(() => { editor.commands.setTextSelection({ from: 1, to: editor.state.doc.content.size - 1 }); });
    const button = await mathButton();
    expect(button).toBeDisabled();
    fireEvent.click(button);
    expect(editor.getJSON()).toEqual(before);
    expect(editor.can().undo()).toBe(false);
  });
});

describe('块级公式选中与编辑', () => {
  beforeEach(() => {
    vi.spyOn(HTMLElement.prototype, 'getBoundingClientRect').mockReturnValue(selectionRect);
  });

  function mountBlock(html = '<p>前</p><div data-math-block="" latex="x^2"></div><p>后</p>'): Editor {
    const editor = new Editor({ extensions: [StarterKit, MathBlock], content: html });
    editors.push(editor);
    render(<><EditorContent editor={editor} /><EditorBubbleMenu editor={editor} /><input aria-label="编辑器外输入" /></>);
    act(() => { editor.view.focus(); });
    return editor;
  }

  it('单击预览显示公式块工具，失焦隐藏，再次单击仍能显示', async () => {
    const editor = mountBlock();
    const preview = await screen.findByRole('button', { name: '编辑公式块' });
    fireEvent.click(preview);
    expect(editor.isFocused).toBe(false);
    const toolbar = await screen.findByRole('toolbar', { name: '选中块操作' });
    expect(within(toolbar).getByRole('button', { name: '编辑公式' })).toBeVisible();
    expect(within(toolbar).getByRole('button', { name: '复制副本' })).toBeVisible();
    expect(within(toolbar).getByRole('button', { name: '删除' })).toBeVisible();
    expect(within(toolbar).queryByRole('button', { name: '加粗' })).toBeNull();
    act(() => { screen.getByRole('textbox', { name: '编辑器外输入' }).focus(); });
    await waitFor(() => expect(screen.queryByRole('toolbar', { name: '选中块操作' })).toBeNull());
    fireEvent.click(preview);
    expect(await screen.findByRole('toolbar', { name: '选中块操作' })).toBeVisible();
  });

  it.each([
    '<p>前</p><div data-math-block="" latex="x^2"></div><p>后</p>',
    '<div data-math-block="" latex="x^2"></div>'
  ])('浮窗编辑仅打开当前公式，焦点到输入框且浮窗收起：%s', async (html) => {
    const editor = mountBlock(html);
    const before = editor.getJSON();
    fireEvent.click(await screen.findByRole('button', { name: '编辑公式块' }));
    const toolbar = await screen.findByRole('toolbar', { name: '选中块操作' });
    const button = within(toolbar).getByRole('button', { name: '编辑公式' });
    // 键盘将焦点移到浮窗也不应立即关闭它。
    act(() => { button.focus(); });
    expect(button).toBeVisible();
    fireEvent.click(button);
    const input = await screen.findByRole('textbox', { name: '编辑 LaTeX 公式' });
    await waitFor(() => expect(input).toHaveFocus());
    await waitFor(() => expect(screen.queryByRole('toolbar', { name: '选中块操作' })).toBeNull());
    expect([(input as HTMLTextAreaElement).selectionStart, (input as HTMLTextAreaElement).selectionEnd]).toEqual([3, 3]);
    expect(editor.getJSON()).toEqual(before);
    expect(editor.can().undo()).toBe(false);
  });

  it('浮窗复制生成公式副本，不误改前后段落，一次撤销恢复', async () => {
    const editor = mountBlock();
    const before = editor.getJSON();
    fireEvent.click(await screen.findByRole('button', { name: '编辑公式块' }));
    const toolbar = await screen.findByRole('toolbar', { name: '选中块操作' });
    fireEvent.click(within(toolbar).getByRole('button', { name: '复制副本' }));
    expect(editor.state.doc.children.map((node) => node.type.name)).toEqual(['paragraph', 'mathBlock', 'mathBlock', 'paragraph']);
    expect(editor.state.doc.child(1).attrs.latex).toBe('x^2');
    expect(editor.state.doc.child(2).attrs.latex).toBe('x^2');
    act(() => { editor.commands.undo(); });
    expect(editor.getJSON()).toEqual(before);
  });

  it.each([
    ['<p>前</p><div data-math-block="" latex="x^2"></div><p>后</p>', '<p>前</p><p>后</p>'],
    ['<div data-math-block="" latex="x^2"></div>', '<p></p>']
  ])('浮窗删除只删除目标公式并支持一步撤销：%s', async (html, remaining) => {
    const editor = mountBlock(html);
    const before = editor.getJSON();
    fireEvent.click(await screen.findByRole('button', { name: '编辑公式块' }));
    const toolbar = await screen.findByRole('toolbar', { name: '选中块操作' });
    fireEvent.click(within(toolbar).getByRole('button', { name: '删除' }));
    expect(editor.getHTML()).toBe(remaining);
    expect(screen.queryByRole('toolbar', { name: '选中块操作' })).toBeNull();
    act(() => { editor.commands.undo(); });
    expect(editor.getJSON()).toEqual(before);
  });

  it('单击仅选中公式，双击进入编辑并清理选中轮廓', async () => {
    const editor = mountBlock();
    const preview = await screen.findByRole('button', { name: '编辑公式块' });
    fireEvent.click(preview);
    expect(editor.state.selection.from).toBe(3);
    expect(editor.state.selection.to).toBe(4);
    expect(preview).toHaveFocus();
    expect(preview).toHaveClass('is-selected');
    expect(screen.queryByRole('textbox', { name: '编辑 LaTeX 公式' })).toBeNull();
    await screen.findByRole('toolbar', { name: '选中块操作' });
    fireEvent.doubleClick(preview);
    const input = await screen.findByRole('textbox', { name: '编辑 LaTeX 公式' });
    await waitFor(() => expect(input).toHaveFocus());
    expect(editor.state.selection.empty).toBe(true);
    expect(document.querySelector('.math-block.is-editing')).not.toHaveClass('is-selected');
    expect([(input as HTMLTextAreaElement).selectionStart, (input as HTMLTextAreaElement).selectionEnd]).toEqual([3, 3]);
    expect(screen.queryByRole('toolbar', { name: '选中块操作' })).toBeNull();
  });

  it.each(['Enter', ' '])('单击选中后按 %s 可以编辑，不会在文档里另插段落', async (key) => {
    const editor = mountBlock();
    const before = editor.getJSON();
    const preview = await screen.findByRole('button', { name: '编辑公式块' });
    fireEvent.click(preview);
    const focusEditor = vi.spyOn(editor.view, 'focus');
    fireEvent.keyDown(document.activeElement!, { key });
    expect(focusEditor).toHaveBeenCalled();
    const input = await screen.findByRole('textbox', { name: '编辑 LaTeX 公式' });
    await waitFor(() => expect(input).toHaveFocus());
    expect(editor.getJSON()).toEqual(before);
    expect([(input as HTMLTextAreaElement).selectionStart, (input as HTMLTextAreaElement).selectionEnd]).toEqual([3, 3]);
  });

  it('新建空公式仍直接编辑，不需要再次双击', async () => {
    const editor = mountBlock('<p>前</p>');
    act(() => { editor.chain().focus().insertContent({ type: 'mathBlock', attrs: { latex: '' } }).run(); });
    const input = await screen.findByRole('textbox', { name: '编辑 LaTeX 公式' });
    await waitFor(() => expect(input).toHaveFocus());
    expect([(input as HTMLTextAreaElement).selectionStart, (input as HTMLTextAreaElement).selectionEnd]).toEqual([0, 0]);
  });

  it('多行公式双击后定位到最后一行末尾，手动移动光标和输入不再被重置', async () => {
    const editor = mountBlock('<div data-math-block="" latex="x^2&#10;+y^3"></div>');
    const before = editor.getJSON();
    fireEvent.doubleClick(await screen.findByRole('button', { name: '编辑公式块' }));
    const input = await screen.findByRole('textbox', { name: '编辑 LaTeX 公式' }) as HTMLTextAreaElement;
    await waitFor(() => expect(input).toHaveFocus());
    expect(input.value).toBe('x^2\n+y^3');
    expect([input.selectionStart, input.selectionEnd]).toEqual([8, 8]);
    expect(editor.getJSON()).toEqual(before);
    expect(editor.can().undo()).toBe(false);
    fireEvent.change(input, { target: { value: 'ax^2\n+y^3', selectionStart: 1, selectionEnd: 1 } });
    await act(async () => { await new Promise((resolve) => window.requestAnimationFrame(() => resolve(null))); });
    expect([input.selectionStart, input.selectionEnd]).toEqual([1, 1]);
  });

  it.each(['shortcut', 'button'])('块公式通过 %s 完成后能在后方正文续写，提交仅产生一笔文档事务', async (finish) => {
    const editor = mountBlock();
    const before = editor.getJSON();
    fireEvent.doubleClick(await screen.findByRole('button', { name: '编辑公式块' }));
    const input = await screen.findByRole('textbox', { name: '编辑 LaTeX 公式' });
    await waitFor(() => expect(input).toHaveFocus());
    fireEvent.change(input, { target: { value: 'x^3' } });
    const onUpdate = vi.fn();
    editor.on('update', onUpdate);
    if (finish === 'shortcut') fireEvent.keyDown(input, { key: 'Enter', ctrlKey: true });
    else {
      const button = screen.getByRole('button', { name: '完成' });
      fireEvent.mouseDown(button);
      fireEvent.click(button);
    }
    await waitFor(() => expect(editor.view.dom).toHaveFocus());
    expect(screen.queryByRole('textbox', { name: '编辑 LaTeX 公式' })).toBeNull();
    expect(editor.state.doc.child(1).attrs.latex).toBe('x^3');
    expect(editor.state.selection.empty).toBe(true);
    expect(editor.state.selection.from).toBe(5);
    expect(onUpdate).toHaveBeenCalledTimes(1);
    act(() => { editor.commands.undo(); });
    expect(editor.getJSON()).toEqual(before);
  });

  it.each([
    '<div data-math-block="" latex="x^2"></div>',
    '<div data-math-block="" latex="x^2"></div><hr/>',
    '<blockquote><div data-math-block="" latex="x^2"></div></blockquote>',
    '<div data-math-block="" latex=""></div>'
  ])('完成时补出可输入正文，公式与新段落一次撤销恢复：%s', async (html) => {
    const editor = mountBlock(html);
    const before = editor.getJSON();
    const preview = screen.queryByRole('button', { name: '编辑公式块' });
    if (preview) fireEvent.doubleClick(preview);
    const input = await screen.findByRole('textbox', { name: '编辑 LaTeX 公式' });
    await waitFor(() => expect(input).toHaveFocus());
    fireEvent.change(input, { target: { value: 'x^3' } });
    fireEvent.keyDown(input, { key: 'Enter', ctrlKey: true });
    await waitFor(() => expect(editor.view.dom).toHaveFocus());
    expect(editor.state.selection.$from.parent.type.name).toBe('paragraph');
    expect(editor.state.selection.$from.parent.textContent).toBe('');
    expect(editor.state.selection.empty).toBe(true);
    const after = editor.getJSON();
    act(() => { editor.commands.undo(); });
    expect(editor.getJSON()).toEqual(before);
    act(() => { editor.commands.redo(); });
    expect(editor.getJSON()).toEqual(after);
  });

  it.each([
    '<p>前</p><div data-math-block="" latex="x^2"></div><p>后</p>',
    '<div data-math-block="" latex="x^2"></div>'
  ])('块公式 Esc 不修改文档，焦点返回选中预览且 Enter 可重新编辑：%s', async (html) => {
    const editor = mountBlock(html);
    const before = editor.getJSON();
    fireEvent.doubleClick(await screen.findByRole('button', { name: '编辑公式块' }));
    const input = await screen.findByRole('textbox', { name: '编辑 LaTeX 公式' });
    await waitFor(() => expect(input).toHaveFocus());
    fireEvent.change(input, { target: { value: 'discarded' } });
    fireEvent.keyDown(input, { key: 'Escape' });
    const preview = await screen.findByRole('button', { name: '编辑公式块' });
    await waitFor(() => expect(preview).toHaveFocus());
    expect(preview).toHaveClass('is-selected');
    expect(editor.getJSON()).toEqual(before);
    expect(editor.can().undo()).toBe(false);
    fireEvent.keyDown(document.activeElement!, { key: 'Enter' });
    const reopened = await screen.findByRole('textbox', { name: '编辑 LaTeX 公式' });
    await waitFor(() => expect(reopened).toHaveFocus());
    expect(reopened).toHaveValue('x^2');
    fireEvent.change(reopened, { target: { value: 'x^4' } });
    fireEvent.keyDown(reopened, { key: 'Enter', ctrlKey: true });
    expect(editor.getText()).toContain('$$x^4$$');
  });

  it('块公式点击外部保存不抢焦点，也不补段落', async () => {
    const editor = mountBlock('<div data-math-block="" latex="x^2"></div>');
    fireEvent.doubleClick(await screen.findByRole('button', { name: '编辑公式块' }));
    const input = await screen.findByRole('textbox', { name: '编辑 LaTeX 公式' });
    await waitFor(() => expect(input).toHaveFocus());
    fireEvent.change(input, { target: { value: 'x^3' } });
    const outside = screen.getByRole('textbox', { name: '编辑器外输入' });
    act(() => { outside.focus(); });
    await waitFor(() => expect(screen.queryByRole('textbox', { name: '编辑 LaTeX 公式' })).toBeNull());
    expect(outside).toHaveFocus();
    expect(editor.state.doc.childCount).toBe(1);
    expect(editor.state.doc.firstChild?.attrs.latex).toBe('x^3');
  });
});

describe('BlockDragHandle', () => {
  it('is a pointer-only grip: draggable, hidden from assistive tech and outside the tab order', () => {
    const onClick = vi.fn();
    const { container } = render(<BlockDragHandle label="拖动测试块" active onClick={onClick} />);
    const handle = container.querySelector('.editor-block-drag-handle') as HTMLElement;
    expect(handle).toHaveAttribute('draggable', 'true');
    expect(handle).toHaveAttribute('aria-hidden', 'true');
    expect(handle).toHaveAttribute('title', '拖动测试块');
    expect(handle).toHaveAttribute('tabindex', '-1');
    expect(handle).toHaveClass('is-active');
    fireEvent.click(handle);
    expect(onClick).toHaveBeenCalledOnce();
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
