import { useState } from 'react';
import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest';
import { act, cleanup, fireEvent, render, waitFor } from '@testing-library/react';
import type { Editor } from '@tiptap/core';
import { closeHistory } from '@tiptap/pm/history';
import type { NoteAttachment } from '../../lib/types';
import { NotebookEditor } from './NotebookEditor';

const { uploadAttachment } = vi.hoisted(() => ({ uploadAttachment: vi.fn() }));
vi.mock('../../lib/attachments', () => ({
  uploadAttachment,
  attachmentContentUrl: () => Promise.resolve('about:blank')
}));

const initial = { type: 'doc', content: ['TOP', 'BOTTOM'].map((text) => ({ type: 'paragraph', content: [{ type: 'text', text }] })) };

function Host(): JSX.Element {
  const [content, setContent] = useState<Record<string, unknown>>(initial);
  return <NotebookEditor content={content} onChange={setContent} pageId={11} />;
}

function attachment(id = 1): NoteAttachment {
  return { id, pageId: 11, fileName: `${id}.png`, mimeType: 'image/png', fileSize: 1, storagePath: 'fake', sha256: null, createdAt: '' };
}

function deferredUpload(): (value: NoteAttachment) => void {
  let resolve!: (value: NoteAttachment) => void;
  uploadAttachment.mockReturnValueOnce(new Promise<NoteAttachment>((done) => { resolve = done; }));
  return resolve;
}

async function mount(): Promise<Editor> {
  render(<Host />);
  return waitFor(() => {
    const editor = (document.querySelector('.ProseMirror') as HTMLElement & { editor?: Editor })?.editor;
    expect(editor).toBeDefined();
    return editor!;
  });
}

function drop(editor: Editor, files = [new File(['x'], '1.png', { type: 'image/png' })]): void {
  // 只模拟几何：原文字光标在 TOP，鼠标落在 BOTTOM 末尾（位置 12）。其余走真实 DOM drop 路径。
  vi.spyOn(editor.view, 'posAtCoords').mockReturnValue({ pos: 12, inside: 5 });
  act(() => { editor.view.focus(); editor.commands.setTextSelection(1); });
  fireEvent.drop(editor.view.dom, {
    clientX: 350, clientY: 500,
    dataTransfer: { files, items: [], types: ['Files'], getData: () => '' }
  });
}

function blocks(editor: Editor): string[] {
  const result: string[] = [];
  editor.state.doc.forEach((node) => result.push(node.type.name === 'fileBlock' ? `file:${node.attrs.attachmentId}` : node.textContent));
  return result;
}

function blockTransfer(withFile: boolean): DataTransfer {
  const strings = new Map<string, string>();
  // 浏览器图片拖动可能携带文件项；clearData 只清文本格式，不代表文件项也清掉。
  const files = withFile ? [new File(['image'], '1.png', { type: 'image/png' })] : [];
  return {
    files, items: files.map((file) => ({ kind: 'file', getAsFile: () => file })),
    get types() { return [...strings.keys(), ...(files.length ? ['Files'] : [])]; },
    clearData: () => strings.clear(),
    setData: (type: string, value: string) => { strings.set(type, value); },
    getData: (type: string) => strings.get(type) ?? '',
    setDragImage: () => {},
    effectAllowed: 'uninitialized'
  } as unknown as DataTransfer;
}

async function startImageHandleDrag(editor: Editor, transfer: DataTransfer): Promise<void> {
  let pos = -1;
  editor.state.doc.forEach((node, offset) => { if (node.type.name === 'fileBlock') pos = offset; });
  expect(pos).toBeGreaterThanOrEqual(0);
  vi.mocked(editor.view.posAtCoords).mockReturnValue({ pos, inside: pos });
  vi.spyOn(editor.view.dom, 'getBoundingClientRect').mockReturnValue(new DOMRect(0, 0, 800, 700));
  // jsdom 没有布局，dropcursor 需要一个真实存在的定位容器。
  vi.spyOn(editor.view.dom, 'offsetParent', 'get').mockReturnValue(document.querySelector('.editor-content'));
  fireEvent.mouseMove(document.querySelector('.editor-content')!, { clientX: 100, clientY: 100 });
  const handle = await waitFor(() => {
    const element = document.querySelector('.editor-block-drag-handle');
    expect(element).not.toBeNull();
    return element!;
  });
  fireEvent.dragStart(handle, { dataTransfer: transfer });
  expect(editor.view.dragging).not.toBeNull();
}

beforeAll(() => {
  Range.prototype.getClientRects ??= () => [] as unknown as DOMRectList;
  Range.prototype.getBoundingClientRect ??= () => new DOMRect();
});

afterEach(() => {
  cleanup();
  uploadAttachment.mockReset();
  vi.restoreAllMocks();
});

describe('文件拖放与草稿回传闭环', () => {
  it('按鼠标落点插入，不按原光标位置插入，且可撤销', async () => {
    const resolve = deferredUpload();
    const editor = await mount();
    vi.spyOn(window, 'scrollBy').mockImplementation(() => {});
    drop(editor);
    expect(uploadAttachment).toHaveBeenCalledWith(11, expect.any(File));
    await act(async () => { resolve(attachment()); });
    expect(blocks(editor)).toEqual(['TOP', 'BOTTOM', 'file:1', '']);
    act(() => { editor.commands.undo(); });
    expect(editor.getJSON()).toEqual(initial);
  });

  it('上传期间移动光标并在落点前打字，文件仍跟随原落点', async () => {
    const resolve = deferredUpload();
    const editor = await mount();
    vi.spyOn(window, 'scrollBy').mockImplementation(() => {});
    drop(editor);
    act(() => { editor.commands.setTextSelection(1); editor.commands.insertContent('NEW '); });
    await act(async () => { resolve(attachment()); });
    expect(blocks(editor)).toEqual(['NEW TOP', 'BOTTOM', 'file:1', '']);
  });

  it('一次拖入多个文件保持顺序，后续文件不跟随用户移走的光标', async () => {
    const first = deferredUpload();
    const second = deferredUpload();
    const editor = await mount();
    vi.spyOn(window, 'scrollBy').mockImplementation(() => {});
    drop(editor, [new File(['x'], '1.png', { type: 'image/png' }), new File(['y'], '2.png', { type: 'image/png' })]);
    await act(async () => { first(attachment(1)); });
    act(() => { editor.commands.setTextSelection(1); });
    await act(async () => { second(attachment(2)); });
    expect(blocks(editor)).toEqual(['TOP', 'BOTTOM', 'file:1', 'file:2', '']);
  });

  it('落点算不出时退回按光标插入，且不让浏览器接管这次拖放', async () => {
    const resolve = deferredUpload();
    const editor = await mount();
    vi.spyOn(window, 'scrollBy').mockImplementation(() => {});
    // 第一次是 ProseMirror 自己解析落点（必须给值才会进 handleDrop），第二次才是我们的锚点采集。
    vi.spyOn(editor.view, 'posAtCoords').mockReturnValueOnce({ pos: 12, inside: 5 }).mockReturnValueOnce(null);
    act(() => { editor.view.focus(); editor.commands.setTextSelection(4); });
    expect(fireEvent.drop(editor.view.dom, {
      clientX: 350, clientY: 500,
      dataTransfer: { files: [new File(['x'], '1.png', { type: 'image/png' })], items: [], types: ['Files'], getData: () => '' }
    })).toBe(false);
    await act(async () => { resolve(attachment()); });
    expect(blocks(editor)).toEqual(['TOP', 'file:1', '', 'BOTTOM']);
  });

  it('上传完成前卸载编辑器，不向已销毁的视图插入', async () => {
    const resolve = deferredUpload();
    const editor = await mount();
    drop(editor);
    cleanup();
    await act(async () => { await new Promise((done) => setTimeout(done, 20)); });
    expect(editor.isDestroyed).toBe(true);
    const chain = vi.spyOn(editor, 'chain');
    await act(async () => { resolve(attachment()); });
    expect(chain).not.toHaveBeenCalled();
  });

  it.each([false, true])('拖拽上传后再用真实手柄移动，文件载荷=%s：只移动、不重复上传，一步撤销', async (withFile) => {
    const resolve = deferredUpload();
    const editor = await mount();
    vi.spyOn(window, 'scrollBy').mockImplementation(() => {});
    drop(editor);
    await act(async () => { resolve(attachment()); });
    const beforeMove = editor.getJSON();
    act(() => { editor.view.dispatch(closeHistory(editor.state.tr)); });
    const transfer = blockTransfer(withFile);
    await startImageHandleDrag(editor, transfer);
    // 拖动过程中即使文字光标变化，也应删除手柄所代表的源块。
    act(() => { editor.commands.setTextSelection(1); });
    fireEvent.dragEnter(document.querySelector('.editor-canvas')!, { dataTransfer: transfer });
    fireEvent.dragOver(editor.view.dom, { clientX: 100, clientY: 0, dataTransfer: transfer });
    expect(document.querySelector('.editor-canvas')).not.toHaveClass('is-dragging-files');
    vi.mocked(editor.view.posAtCoords).mockReturnValue({ pos: 0, inside: -1 });
    await act(async () => { fireEvent.drop(editor.view.dom, { clientX: 100, clientY: 0, dataTransfer: transfer }); });
    expect(uploadAttachment).toHaveBeenCalledTimes(1);
    expect(blocks(editor)).toEqual(['file:1', 'TOP', 'BOTTOM', '']);
    expect(document.querySelectorAll('.file-block--image')).toHaveLength(1);
    await act(async () => { editor.commands.undo(); });
    expect(editor.getJSON()).toEqual(beforeMove);
    await act(async () => { editor.commands.redo(); });
    expect(blocks(editor)).toEqual(['file:1', 'TOP', 'BOTTOM', '']);
    expect(uploadAttachment).toHaveBeenCalledTimes(1);
  });

  it('手柄 Ctrl 拖动允许复制节点，但不能重新上传图片', async () => {
    const resolve = deferredUpload();
    const editor = await mount();
    vi.spyOn(window, 'scrollBy').mockImplementation(() => {});
    drop(editor);
    await act(async () => { resolve(attachment()); });
    const transfer = blockTransfer(true);
    await startImageHandleDrag(editor, transfer);
    vi.mocked(editor.view.posAtCoords).mockReturnValue({ pos: 0, inside: -1 });
    // jsdom 没有 DragEvent，fireEvent.drop 的 Event 回退会忽略 ctrlKey，必须显式提供鼠标事件字段。
    const event = new MouseEvent('drop', { bubbles: true, cancelable: true, clientX: 100, clientY: 0, ctrlKey: true });
    Object.defineProperty(event, 'dataTransfer', { value: transfer });
    await act(async () => { fireEvent(editor.view.dom, event); });
    expect(uploadAttachment).toHaveBeenCalledTimes(1);
    expect(blocks(editor)).toEqual(['file:1', 'TOP', 'BOTTOM', 'file:1', '']);
  });

  it.each(['.file-image-preview', '.file-block--image'])('从图片预览 %s 发起原生拖动会被阻止，不旁路外侧手柄', async (selector) => {
    const resolve = deferredUpload();
    const editor = await mount();
    drop(editor);
    await act(async () => { resolve(attachment()); });
    const image = document.querySelector(selector)!;
    expect(fireEvent.dragStart(image, { dataTransfer: blockTransfer(true) })).toBe(false);
    expect(editor.view.dragging).toBeNull();
    expect(uploadAttachment).toHaveBeenCalledTimes(1);
  });

  it('取消手柄拖动后仍可正常拖入外部文件', async () => {
    const first = deferredUpload();
    const editor = await mount();
    vi.spyOn(window, 'scrollBy').mockImplementation(() => {});
    drop(editor);
    await act(async () => { first(attachment(1)); });
    await startImageHandleDrag(editor, blockTransfer(true));
    fireEvent.dragEnd(document);
    await waitFor(() => { expect(editor.view.dragging).toBeNull(); });
    const second = deferredUpload();
    const transfer = blockTransfer(true);
    fireEvent.dragEnter(document.querySelector('.editor-canvas')!, { dataTransfer: transfer });
    expect(document.querySelector('.editor-canvas')).toHaveClass('is-dragging-files');
    drop(editor);
    await act(async () => { second(attachment(2)); });
    expect(uploadAttachment).toHaveBeenCalledTimes(2);
    expect(blocks(editor).filter((block) => block.startsWith('file:')).sort()).toEqual(['file:1', 'file:2']);
    expect(document.querySelector('.editor-canvas')).not.toHaveClass('is-dragging-files');
  });

  it('正常正文选区仍可原生拖动，不被块内防误拖拦截', async () => {
    const editor = await mount();
    vi.spyOn(editor.view, 'posAtCoords').mockReturnValue({ pos: 2, inside: 0 });
    act(() => { editor.commands.setTextSelection({ from: 1, to: 4 }); });
    expect(fireEvent.dragStart(editor.view.dom.querySelector('p')!, { dataTransfer: blockTransfer(false) })).toBe(true);
    expect(editor.view.dragging?.slice.content.textBetween(0, editor.view.dragging.slice.content.size)).toBe('TOP');
    expect(uploadAttachment).not.toHaveBeenCalled();
    fireEvent.dragEnd(editor.view.dom);
  });
});
