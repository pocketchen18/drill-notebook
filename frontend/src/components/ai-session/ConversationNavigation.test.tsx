import { afterEach, describe, it, expect, vi } from 'vitest';
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { ConversationNavigation } from './ConversationNavigation';
import type { RoundPreview } from '../../lib/conversationNavigation';

const preview = (round: number): RoundPreview => ({ roundNumber: round, totalRounds: 1000, messageId: round * 2 - 1,
  question: `提问 ${round}`, answer: `回答 ${round}`, status: 'COMPLETED', createdAt: '2026-11-13T02:24:00Z' });
afterEach(() => { vi.useRealTimers(); vi.restoreAllMocks(); vi.unstubAllGlobals(); });
const bounds = (height: number, width = 44): DOMRect => ({
  width, height, x: 0, y: 0, top: 0, bottom: height, left: 0, right: width, toJSON: () => ({})
});
function controlledFrames() {
  let identity = 0;
  const pending = new Map<number, FrameRequestCallback>();
  const schedule = vi.spyOn(window, 'requestAnimationFrame').mockImplementation(callback => {
    const frame = ++identity; pending.set(frame, callback); return frame;
  });
  const cancel = vi.spyOn(window, 'cancelAnimationFrame').mockImplementation(frame => { pending.delete(frame); });
  const flush = () => act(async () => {
    const callbacks = [...pending.values()]; pending.clear(); callbacks.forEach(callback => callback(0));
  });
  return { pending, schedule, cancel, flush };
}
describe('会话右侧定位', () => {
  it.each([
    '2026-11-12 21:54:00',
    '2026-11-12T21:54:00Z',
    '2026-11-13T05:54:00+08:00',
    '2026-11-12T16:54:00-05:00'
  ])('数据库 UTC 时间跨日后显示真实的本地提问时间：%s', async createdAt => {
    const value = { ...preview(2), createdAt };
    render(<ConversationNavigation total={2} reading={2} getPreview={async () => value} onJump={vi.fn()} />);
    fireEvent.focus(screen.getByRole('slider'));
    await screen.findByText('提问 2');
    const card = screen.getByRole('button', { name: /第 2 .*对话预览/ });
    const expected = new Date('2026-11-12T21:54:00Z');
    expect(card.querySelector('time')).toHaveTextContent(expected.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }));
    expect(card.querySelector('time')).toHaveAttribute('datetime', expected.toISOString());
  });
  it.each([undefined, '', 'not-a-timestamp'])('没有有效提问时间时不显示伪造时间：%s', async createdAt => {
    const value = { ...preview(2), createdAt };
    render(<ConversationNavigation total={2} reading={2} getPreview={async () => value} onJump={vi.fn()} />);
    fireEvent.focus(screen.getByRole('slider'));
    await screen.findByText('提问 2');
    expect(screen.getByRole('button', { name: /第 2 .*对话预览/ }).querySelector('time')).toBeNull();
  });
  it('少量轮次固定间距、按数量缩短导航；鼠标直接定位且不锁定焦点', async () => {
    const getPreview = vi.fn(async round => preview(round)); const jump = vi.fn();
    const { container } = render(<ConversationNavigation total={13} reading={13} getPreview={getPreview} onJump={jump} />);
    const range = screen.getByRole('slider');
    vi.spyOn(range, 'getBoundingClientRect').mockReturnValue(bounds(136));
    expect(container.querySelector('.ai-conversation-rail')).toHaveStyle({ height: '136px' });
    const ticks = [...container.querySelectorAll<HTMLElement>('.ai-conversation-tick')];
    expect(ticks).toHaveLength(13);
    expect(ticks.map(tick => tick.style.top)).toEqual(Array.from({ length: 13 }, (_, index) => `${index * 10}px`));
    fireEvent.pointerDown(range, { clientY: 108, pointerType: 'mouse' });
    await waitFor(() => expect(jump).toHaveBeenCalledWith(preview(11)));
    expect(getPreview).toHaveBeenCalledOnce();
    expect(getPreview).toHaveBeenCalledWith(11);
    expect(range).not.toHaveFocus();
    expect(screen.queryByRole('button', { name: /对话预览/ })).toBeNull();
  });
  it('首次移入后连续移动不延长等待；打开后按帧合并，离开取消待执行更新', async () => {
    vi.useFakeTimers();
    const frames = controlledFrames();
    const getPreview = vi.fn(async round => preview(round));
    const { unmount } = render(<ConversationNavigation total={13} reading={13} getPreview={getPreview} onJump={vi.fn()} />);
    const range = screen.getByRole('slider');
    vi.spyOn(range, 'getBoundingClientRect').mockReturnValue(bounds(136));
    fireEvent.pointerMove(range, { clientY: 8 });
    await act(async () => { await vi.advanceTimersByTimeAsync(100); });
    fireEvent.pointerMove(range, { clientY: 48 });
    await act(async () => { await vi.advanceTimersByTimeAsync(49); });
    expect(getPreview).not.toHaveBeenCalled();
    await act(async () => { await vi.advanceTimersByTimeAsync(1); });
    expect(getPreview).toHaveBeenCalledExactlyOnceWith(5);
    expect(screen.getByText('提问 5')).toBeVisible();
    fireEvent.pointerMove(range, { clientY: 58 });
    fireEvent.pointerMove(range, { clientY: 68 });
    fireEvent.pointerMove(range, { clientY: 88 });
    expect(frames.schedule).toHaveBeenCalledOnce();
    expect(getPreview).toHaveBeenCalledOnce();
    await frames.flush();
    expect(getPreview.mock.calls.map(([round]) => round)).toEqual([5, 9]);
    expect(screen.getByText('提问 9')).toBeVisible();
    fireEvent.pointerMove(range, { clientY: 88 });
    expect(frames.pending.size).toBe(0);
    fireEvent.pointerMove(range, { clientY: 98 });
    fireEvent.pointerLeave(range);
    expect(frames.pending.size).toBe(0);
    await frames.flush();
    expect(getPreview).toHaveBeenCalledTimes(2);
    await act(async () => { await vi.advanceTimersByTimeAsync(180); });
    expect(screen.queryByRole('button', { name: /对话预览/ })).toBeNull();
    fireEvent.pointerMove(range, { clientY: 8 });
    unmount();
    await act(async () => { await vi.advanceTimersByTimeAsync(150); });
    expect(getPreview).toHaveBeenCalledTimes(2);
  });
  it('移动到正在加载的同轮不重复请求，加载中点击仍定位新的目标轮次', async () => {
    const frames = controlledFrames();
    let resolveNew!: (value: RoundPreview) => void;
    const getPreview = vi.fn(round => round === 2
      ? new Promise<RoundPreview>(resolve => { resolveNew = resolve; }) : Promise.resolve(preview(round)));
    const jump = vi.fn();
    render(<ConversationNavigation total={13} reading={1} getPreview={getPreview} onJump={jump} />);
    const range = screen.getByRole('slider');
    vi.spyOn(range, 'getBoundingClientRect').mockReturnValue(bounds(136));
    fireEvent.focus(range); await screen.findByText('提问 1');
    fireEvent.pointerMove(range, { clientY: 18 }); await frames.flush();
    expect(screen.getByText('提问 1')).not.toBeVisible();
    expect(screen.getByRole('status')).toHaveTextContent('正在读取预览');
    fireEvent.pointerMove(range, { clientY: 18 });
    expect(frames.pending.size).toBe(0);
    expect(getPreview.mock.calls.map(([round]) => round)).toEqual([1, 2]);
    fireEvent.click(screen.getByRole('button', { name: /第 2 .*对话预览/ }));
    await act(async () => { resolveNew(preview(2)); });
    expect(jump).toHaveBeenCalledExactlyOnceWith(preview(2));
  });
  it('键盘逐轮预览，Enter 才定位；密集会话只有一个定位控件', async () => {
    const getPreview = vi.fn(async round => preview(round)); const jump = vi.fn();
    const { container } = render(<ConversationNavigation total={1000} reading={600} getPreview={getPreview} onJump={jump} />);
    const range = screen.getByRole('slider', { name: '会话内对话跳转' });
    expect(container.querySelector('[data-round="600"]')).toHaveStyle({ width: '8px' });
    expect(container.querySelector('[data-round="599"]')).toHaveStyle({ width: '6px' });
    fireEvent.focus(range);
    expect(await screen.findByText('提问 600')).toBeVisible();
    expect(container.querySelector('[data-round="600"]')).toHaveStyle({ width: '24px' });
    expect(container.querySelector('[data-round="599"]')).toHaveStyle({ width: '20px' });
    fireEvent.keyDown(range, { key: 'Home' });
    expect(await screen.findByText('提问 1')).toBeVisible();
    fireEvent.keyDown(range, { key: 'ArrowDown' });
    expect(await screen.findByText('提问 2')).toBeVisible();
    expect(jump).not.toHaveBeenCalled();
    expect(screen.getAllByRole('slider')).toHaveLength(1);
    expect(container.querySelectorAll('.ai-conversation-tick').length).toBeLessThanOrEqual(64);
    fireEvent.keyDown(range, { key: 'Enter' });
    await waitFor(() => expect(jump).toHaveBeenCalledWith(preview(2)));
    expect(screen.queryByText('提问 2')).toBeNull();
  });
  it('预览卡片整体点击定位，没有重复跳转按钮；Escape 不连带关闭其他界面', async () => {
    const jump = vi.fn();
    render(<ConversationNavigation total={1000} reading={18} getPreview={async round => preview(round)} onJump={jump} />);
    const range = screen.getByRole('slider'); fireEvent.focus(range);
    const card = await screen.findByRole('button', { name: /第 18 .*对话预览/ });
    await screen.findByText('回答 18');
    expect(screen.queryByText('跳到这轮')).toBeNull();
    expect(screen.queryByText(/^提问$/)).toBeNull();
    expect(screen.queryByText(/^回答$/)).toBeNull();
    expect(screen.getByText('第 18 / 1000 轮')).toBeVisible();
    expect(card.querySelector('time')).toHaveAttribute('datetime', '2026-11-13T02:24:00.000Z');
    expect(screen.queryByText('已完成')).toBeNull();
    fireEvent.click(card); await waitFor(() => expect(jump).toHaveBeenCalledOnce());
    fireEvent.focus(range); const next = await screen.findByRole('button', { name: /对话预览/ });
    fireEvent.focus(next); fireEvent.keyDown(next, { key: 'Escape' });
    await waitFor(() => expect(screen.queryByRole('button', { name: /对话预览/ })).toBeNull());
    expect(jump).toHaveBeenCalledOnce();
  });
  it('迟到的预览不覆盖新选择，窄窗覆盖与空单轮隐藏导航', async () => {
    let resolveOld!: (value: RoundPreview) => void;
    const getPreview = vi.fn(round => round === 600 ? new Promise<RoundPreview>(resolve => { resolveOld = resolve; }) : Promise.resolve(preview(round)));
    const props = { total: 1000, reading: 600, getPreview, onJump: vi.fn() };
    const { rerender } = render(<ConversationNavigation {...props} />);
    const range = screen.getByRole('slider'); fireEvent.focus(range); fireEvent.keyDown(range, { key: 'Home' });
    await screen.findByText('提问 1'); resolveOld(preview(600));
    await waitFor(() => expect(screen.getByText('提问 1')).toBeVisible());
    expect(screen.queryByText('提问 600')).toBeNull();
    rerender(<ConversationNavigation {...props} hidden />); expect(screen.queryByRole('slider')).toBeNull();
    rerender(<ConversationNavigation {...props} total={1} />); expect(screen.queryByRole('slider')).toBeNull();
  });
  it('最多显示 64 个逐轮刻度，滚轮翻阅隐藏轮次且不带动正文或自动跳转', async () => {
    const parentWheel = vi.fn(); const jump = vi.fn();
    const { container } = render(<div onWheel={parentWheel}><ConversationNavigation total={1000} reading={1000}
      getPreview={async round => preview(round)} onJump={jump} /></div>);
    const range = screen.getByRole('slider'); fireEvent.focus(range);
    await screen.findByText('提问 1000');
    const rail = container.querySelector('.ai-conversation-rail')!;
    expect(rail).toHaveAttribute('data-first-round', '937');
    expect(container.querySelectorAll('.ai-conversation-tick')).toHaveLength(64);
    expect(fireEvent.wheel(range, { deltaY: -96, cancelable: true })).toBe(false);
    await screen.findByText('提问 997');
    expect(rail).toHaveAttribute('data-first-round', '934');
    expect(rail).toHaveAttribute('data-last-round', '997');
    expect(container.querySelectorAll('.ai-conversation-tick')).toHaveLength(64);
    expect(parentWheel).not.toHaveBeenCalled(); expect(jump).not.toHaveBeenCalled();
    fireEvent.wheel(screen.getByRole('button', { name: /对话预览/ }), { deltaY: 96, cancelable: true });
    await screen.findByText('提问 1000');
    expect(rail).toHaveAttribute('data-first-round', '937');
    fireEvent.wheel(range, { deltaY: 96, cancelable: true });
    expect(rail).toHaveAttribute('data-first-round', '937');
    fireEvent.keyDown(range, { key: 'Home' }); await screen.findByText('提问 1');
    expect(rail).toHaveAttribute('data-first-round', '1');
    fireEvent.keyDown(range, { key: 'End' }); await screen.findByText('提问 1000');
    expect(rail).toHaveAttribute('data-last-round', '1000');
  });
  it('窗口缩小时保留已打开的预览及所选刻度，扩展回来仍不自动定位正文', async () => {
    let height = 800;
    let resize = () => {};
    vi.spyOn(HTMLElement.prototype, 'clientHeight', 'get').mockImplementation(function (this: HTMLElement) {
      return this.classList.contains('ai-window-stream-wrap') ? height : 400;
    });
    vi.stubGlobal('ResizeObserver', class {
      constructor(private callback: ResizeObserverCallback) {}
      observe(target: Element) {
        if (target.classList.contains('ai-window-stream-wrap')) {
          resize = () => this.callback([{ target } as ResizeObserverEntry], this as unknown as ResizeObserver);
        }
      }
      disconnect() {}
    });
    const jump = vi.fn();
    const { container } = render(<div className="ai-window-stream-wrap"><ConversationNavigation total={1000} reading={1000}
      getPreview={async round => preview(round)} onJump={jump} /></div>);
    fireEvent.focus(screen.getByRole('slider'));
    await screen.findByText('提问 1000');
    const rail = container.querySelector('.ai-conversation-rail')!;
    expect(container.querySelectorAll('.ai-conversation-tick')).toHaveLength(64);
    expect(rail).toHaveStyle({ height: '646px' });
    height = 200;
    act(() => resize());
    await waitFor(() => expect(container.querySelectorAll('.ai-conversation-tick')).toHaveLength(13));
    expect(rail).toHaveAttribute('data-first-round', '988');
    expect(rail).toHaveStyle({ height: '136px' });
    expect(container.querySelector('[data-round="1000"]')).toHaveStyle({ width: '24px' });
    expect(screen.getByText('提问 1000')).toBeVisible();
    height = 800;
    act(() => resize());
    await waitFor(() => expect(container.querySelectorAll('.ai-conversation-tick')).toHaveLength(64));
    expect(rail).toHaveAttribute('data-first-round', '937');
    expect(screen.getByText('提问 1000')).toBeVisible();
    expect(jump).not.toHaveBeenCalled();
  });
  it('提问两行、回答三行，排版超限时明确添加三个点并保留完整跳转身份', async () => {
    vi.spyOn(HTMLElement.prototype, 'getBoundingClientRect').mockImplementation(() => ({
      width: 300, height: 336, x: 0, y: 0, top: 0, bottom: 336, left: 0, right: 300, toJSON: () => ({})
    }));
    const measured: string[] = [];
    vi.spyOn(HTMLElement.prototype, 'scrollHeight', 'get').mockImplementation(function (this: HTMLElement) {
      measured.push(this.dataset.text ?? '');
      return Math.ceil(Array.from(this.dataset.text ?? '').length / 20) * 20;
    });
    const value = { ...preview(18), question: '这一轮的完整提问'.repeat(200), answer: '回答包含😀与长段正文。'.repeat(300) };
    const jump = vi.fn();
    const { container } = render(<ConversationNavigation total={1000} reading={18}
      getPreview={async round => round === 18 ? value : preview(round)} onJump={jump} />);
    fireEvent.focus(screen.getByRole('slider'));
    const card = await screen.findByRole('button', { name: /第 18 .*对话预览/ });
    await waitFor(() => {
      const excerpts = container.querySelectorAll('.ai-conversation-excerpt-content');
      expect(excerpts).toHaveLength(2);
      expect(excerpts[0].textContent).toMatch(/\.\.\.$/);
      expect(excerpts[1].textContent).toMatch(/\.\.\.$/);
      expect(Array.from(excerpts[0].textContent!)).toHaveLength(40);
      expect(Array.from(excerpts[1].textContent!)).toHaveLength(60);
    });
    expect(measured.length).toBeGreaterThan(0);
    expect(Math.max(...measured.map(text => Array.from(text).length))).toBeLessThanOrEqual(131);
    fireEvent.keyDown(screen.getByRole('slider'), { key: 'ArrowDown' });
    await screen.findByText('提问 19');
    const measurementsBeforeReturn = measured.length;
    fireEvent.keyDown(screen.getByRole('slider'), { key: 'ArrowUp' });
    await waitFor(() => expect(container.querySelector('.ai-conversation-excerpt-content')?.textContent).toMatch(/\.\.\.$/));
    expect(measured).toHaveLength(measurementsBeforeReturn);
    fireEvent.click(card); await waitFor(() => expect(jump).toHaveBeenCalledWith(value));
  });
});
