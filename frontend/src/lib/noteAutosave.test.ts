import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { NoteAutosave } from './noteAutosave';

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
    vi.advanceTimersByTime(400);
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
    vi.advanceTimersByTime(400);
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
    vi.advanceTimersByTime(400);
    expect(send).toHaveBeenCalledTimes(1);
    expect(send).toHaveBeenCalledWith({ pageId: 37, content: doc('B') });
  });

  it('successful send refreshes the server baseline so later echoes stay silent', async () => {
    const send = vi.fn().mockResolvedValue({});
    const autosave = new NoteAutosave(send);
    autosave.change(11, doc('A'));
    vi.advanceTimersByTime(400);
    await Promise.resolve();
    await Promise.resolve();
    autosave.change(11, doc('A'));
    vi.advanceTimersByTime(800);
    expect(send).toHaveBeenCalledTimes(1);
  });
});
