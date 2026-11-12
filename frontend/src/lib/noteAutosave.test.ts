import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { NoteAutosave, saveAllNotesBeforeRestart } from './noteAutosave';

const doc = (text: string): Record<string, unknown> => ({ type: 'doc', content: [{ type: 'paragraph', content: [{ type: 'text', text }] }] });

describe('NoteAutosave', () => {
  beforeEach(() => { vi.useFakeTimers(); });
  afterEach(() => { vi.useRealTimers(); });

  it('debounces consecutive changes into one send bound to the page', async () => {
    const send = vi.fn().mockResolvedValue({});
    const autosave = new NoteAutosave(send);
    autosave.change(11, doc('a'));
    autosave.change(11, doc('ab'));
    expect(send).not.toHaveBeenCalled();
    await vi.advanceTimersByTimeAsync(400);
    expect(send).toHaveBeenCalledTimes(1);
    expect(send).toHaveBeenCalledWith({ pageId: 11, content: doc('ab') });
  });

  it('switching pages keeps both drafts bound to their own pages', async () => {
    const send = vi.fn().mockResolvedValue({});
    const autosave = new NoteAutosave(send);
    autosave.noteServer(11, doc('原-11'));
    autosave.noteServer(37, doc('原-37'));
    autosave.change(11, doc('A'));
    autosave.change(37, doc('B'));
    await vi.advanceTimersByTimeAsync(400);
    expect(send).toHaveBeenCalledTimes(2);
    const pages = send.mock.calls.map((call) => (call[0] as { pageId: number }).pageId).sort();
    expect(pages).toEqual([11, 37]);
    const to37 = send.mock.calls.find((call) => (call[0] as { pageId: number }).pageId === 37)?.[0] as { content: Record<string, unknown> };
    expect(JSON.stringify(to37.content)).toContain('B');
  });

  it('echo of the server baseline cancels the pending save', async () => {
    const send = vi.fn().mockResolvedValue({});
    const autosave = new NoteAutosave(send);
    autosave.noteServer(11, doc('原-11'));
    autosave.change(11, doc('A'));
    expect(autosave.hasPendingFor(11)).toBe(true);
    // 编辑器 setContent 重置回声：内容与服务端基线相同 → 不脏不存
    autosave.change(11, doc('原-11'));
    expect(autosave.hasPendingFor(11)).toBe(false);
    vi.advanceTimersByTime(800);
    expect(send).not.toHaveBeenCalled();
  });

  it('take() hands pending drafts to the keepalive flush and clears the timer', async () => {
    const send = vi.fn().mockResolvedValue({});
    const autosave = new NoteAutosave(send);
    autosave.change(11, doc('A'));
    const taken = autosave.take();
    expect(taken).toEqual([{ pageId: 11, content: doc('A') }]);
    vi.advanceTimersByTime(800);
    expect(send).not.toHaveBeenCalled();
    expect(autosave.take()).toEqual([]);
  });

  it('discard() drops drafts of deleted pages', async () => {
    const send = vi.fn().mockResolvedValue({});
    const autosave = new NoteAutosave(send);
    autosave.change(11, doc('A'));
    autosave.change(37, doc('B'));
    autosave.discard(11);
    await vi.advanceTimersByTimeAsync(400);
    expect(send).toHaveBeenCalledTimes(1);
    expect(send).toHaveBeenCalledWith({ pageId: 37, content: doc('B') });
  });

  it('删除后迟到的保存失败和编辑器回声不能恢复旧草稿', async () => {
    let reject!: (reason: Error) => void;
    const send = vi.fn(() => new Promise((_resolve, fail) => { reject = fail; }));
    const autosave = new NoteAutosave(send);
    autosave.change(11, doc('已发出的内容'));
    autosave.flush();
    autosave.discard(11);
    reject(new Error('page deleted'));
    await vi.advanceTimersByTimeAsync(0);
    autosave.change(11, doc('迟到的编辑器回声'));
    autosave.flush();
    expect(send).toHaveBeenCalledTimes(1);
    expect(autosave.hasPendingFor(11)).toBe(false);
    expect(autosave.take()).toEqual([]);
  });

  it('successful send refreshes the server baseline so later echoes stay silent', async () => {
    const send = vi.fn().mockResolvedValue({});
    const autosave = new NoteAutosave(send);
    autosave.change(11, doc('A'));
    await vi.advanceTimersByTimeAsync(400);
    await Promise.resolve();
    await Promise.resolve();
    autosave.change(11, doc('A'));
    vi.advanceTimersByTime(800);
    expect(send).toHaveBeenCalledTimes(1);
  });
  it('protects drafts while saving, after failure, and until a later save succeeds', async () => {
    let rejectSave!: (reason: Error) => void;
    const send = vi.fn().mockImplementationOnce(() => new Promise((_resolve, reject) => { rejectSave = reject; })).mockResolvedValue({});
    const autosave = new NoteAutosave(send);
    autosave.change(11, doc('本地草稿'));
    await vi.advanceTimersByTimeAsync(400);
    expect(autosave.hasPendingFor(11)).toBe(true);
    rejectSave(new Error('offline'));
    await vi.advanceTimersByTimeAsync(0);
    expect(autosave.hasPendingFor(11)).toBe(true);
    autosave.change(11, doc('稍后重试'));
    await vi.advanceTimersByTimeAsync(400);
    expect(autosave.hasPendingFor(11)).toBe(false);
    expect(send).toHaveBeenCalledTimes(2);
  });

  it('keepalive flush keeps the same protection while hidden, including failure', async () => {
    let rejectSave!: (reason: Error) => void;
    const regular = vi.fn().mockResolvedValue({});
    const keepalive = vi.fn(() => new Promise((_resolve, reject) => { rejectSave = reject; }));
    const autosave = new NoteAutosave(regular);
    autosave.change(11, doc('后台草稿'));
    autosave.flush(keepalive);
    expect(autosave.hasPendingFor(11)).toBe(true);
    await vi.advanceTimersByTimeAsync(400);
    expect(regular).not.toHaveBeenCalled();
    expect(keepalive).toHaveBeenCalledTimes(1);
    rejectSave(new Error('offline'));
    await vi.advanceTimersByTimeAsync(0);
    expect(autosave.hasPendingFor(11)).toBe(true);
    autosave.discard(11);
    expect(autosave.hasPendingFor(11)).toBe(false);
  });
  it('restart waits for a confirmed save and drains the newest draft after a pending request', async () => {
    let finish!: () => void;
    const send = vi.fn().mockImplementationOnce(() => new Promise<void>(resolve => { finish = resolve; })).mockResolvedValue({});
    const autosave = new NoteAutosave(send);
    autosave.change(12, doc('第一份')); autosave.flush();
    autosave.change(12, doc('最新草稿'));
    let ready = false;
    const waiting = autosave.flushAndWait().then(() => { ready = true; });
    expect(send).toHaveBeenCalledTimes(1); expect(ready).toBe(false);
    finish(); await waiting;
    expect(send).toHaveBeenCalledTimes(2); expect(send).toHaveBeenLastCalledWith({ pageId: 12, content: doc('最新草稿') });
    expect(autosave.hasPendingFor(12)).toBe(false);
  });
  it('undo during an in-flight save is queued so the older save cannot overwrite the undo', async () => {
    let finish!: () => void;
    const send = vi.fn().mockImplementationOnce(() => new Promise<void>(resolve => { finish = resolve; })).mockResolvedValue({});
    const autosave = new NoteAutosave(send);
    autosave.noteServer(13, doc('原文')); autosave.change(13, doc('修改')); autosave.flush();
    autosave.change(13, doc('原文'));
    const waiting = autosave.flushAndWait(); finish(); await waiting;
    expect(send).toHaveBeenLastCalledWith({ pageId: 13, content: doc('原文') });
  });
  it('failed save blocks restart, retains the draft, and a successful retry releases the guard', async () => {
    const send = vi.fn().mockRejectedValueOnce(new Error('offline')).mockResolvedValue({});
    const autosave = new NoteAutosave(send); autosave.change(14, doc('不能丢失'));
    await expect(autosave.flushAndWait()).rejects.toThrow('草稿已保留');
    expect(autosave.hasPendingFor(14)).toBe(true);
    await autosave.flushAndWait(); expect(autosave.hasPendingFor(14)).toBe(false);
  });
  it('restart includes drafts whose page has unmounted', async () => {
    const send = vi.fn().mockResolvedValue({});
    const autosave = new NoteAutosave(send); autosave.change(15, doc('切走的页面'));
    await saveAllNotesBeforeRestart(); expect(send).toHaveBeenCalledWith({ pageId: 15, content: doc('切走的页面') });
  });
});
