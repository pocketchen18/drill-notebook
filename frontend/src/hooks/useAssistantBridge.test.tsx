import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, render, screen } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, useLocation } from 'react-router-dom';
import { useAssistantBridge } from './useAssistantBridge';
import { useUiStore } from '../stores/uiStore';

type Status = { visible: boolean; running: number; pending: number };
let busListener: ((message: unknown) => void) | undefined;
let stateListener: ((state: Status) => void) | undefined;
const published: Array<{ type: string; [key: string]: unknown }> = [];
const assistant = {
  open: vi.fn(async () => undefined), hide: vi.fn(async () => undefined),
  getState: vi.fn(async (): Promise<Status> => ({ visible: false, running: 0, pending: 0 })),
  toggle: vi.fn(async () => undefined), attention: vi.fn(async () => undefined), focusMain: vi.fn(async () => undefined),
  onState: vi.fn((listener: (state: Status) => void) => { stateListener = listener; return () => { stateListener = undefined; }; }),
  onActivated: vi.fn(() => () => undefined)
};

function Probe(): JSX.Element {
  const status = useAssistantBridge();
  const location = useLocation();
  return <div><span data-testid="loc">{location.pathname + location.search}</span>
    <span data-testid="status">{`${status.visible}:${status.running}:${status.pending}`}</span></div>;
}
function mount(): QueryClient {
  const client = new QueryClient();
  render(<QueryClientProvider client={client}><MemoryRouter initialEntries={['/banks']}><Probe /></MemoryRouter></QueryClientProvider>);
  return client;
}

beforeEach(() => {
  published.length = 0; vi.clearAllMocks();
  (window as unknown as { api: unknown }).api = { assistant, bus: {
    publish: async (message: { type: string }) => { published.push(message); },
    subscribe: (listener: (message: unknown) => void) => { busListener = listener; return () => { busListener = undefined; }; } } };
  useUiStore.setState({ aiOpen: false, pageContext: { kind: 'note', title: '导数的定义', markdown: '正文', route: '/notebooks', notePageId: 3 } });
});
afterEach(() => { delete (window as unknown as { api?: unknown }).api; });

describe('主窗口的助手桥接', () => {
  it('打开与关闭转成对独立窗口的操作，入口状态以窗口真实可见性为准', async () => {
    mount();
    act(() => useUiStore.getState().setAiOpen(true));
    expect(assistant.open).toHaveBeenCalledTimes(1);
    expect(useUiStore.getState().aiOpen).toBe(false);
    act(() => stateListener?.({ visible: true, running: 0, pending: 0 }));
    expect(useUiStore.getState().aiOpen).toBe(true);
    // 入口与快捷键是切换：收起还是唤到前台由主进程按窗口真实状态决定。
    act(() => useUiStore.getState().toggleAi());
    expect(assistant.toggle).toHaveBeenCalledTimes(1);
    expect(assistant.hide).not.toHaveBeenCalled();
    expect(useUiStore.getState().aiOpen).toBe(true);
    act(() => useUiStore.getState().setAiOpen(false));
    expect(assistant.hide).toHaveBeenCalledTimes(1);
    act(() => stateListener?.({ visible: false, running: 2, pending: 1 }));
    expect(screen.getByTestId('status').textContent).toBe('false:2:1');
    expect(useUiStore.getState().aiOpen).toBe(false);
  });

  it('把当前业务页面交给助手，并在切页或被请求时再次发送', async () => {
    mount();
    expect(published.at(-1)).toMatchObject({ type: 'page-context', context: { title: '导数的定义', notePageId: 3 } });
    act(() => useUiStore.getState().setPageContext({ kind: 'bank', title: '数学题库', markdown: '', route: '/banks', bankId: 7 }));
    expect(published.at(-1)).toMatchObject({ type: 'page-context', context: { title: '数学题库', bankId: 7 } });
    const count = published.length;
    act(() => busListener?.({ type: 'context-request' }));
    expect(published).toHaveLength(count + 1);
  });

  it('只按助手里的点击定位资料，拒绝非站内路径；确认的工具写入刷新本窗口缓存', async () => {
    const client = mount();
    const invalidate = vi.spyOn(client, 'invalidateQueries');
    act(() => busListener?.({ type: 'navigate', route: '/notebooks?pageId=9' }));
    expect(screen.getByTestId('loc').textContent).toBe('/notebooks?pageId=9');
    for (const route of ['https://example.com', '//evil.example', 'notebooks', 42])
      act(() => busListener?.({ type: 'navigate', route }));
    expect(screen.getByTestId('loc').textContent).toBe('/notebooks?pageId=9');

    act(() => busListener?.({ type: 'tool-mutation', row: { sequence: 1, toolName: 'create_material', state: 'FINISHED', outcome: 'CREATED',
      sourceLink: 'drill-notebook://material/note/9' } }));
    expect(invalidate).toHaveBeenCalledWith({ queryKey: ['note-pages'] });
    invalidate.mockClear();
    act(() => busListener?.({ type: 'tool-mutation', row: { sequence: 1, toolName: 'create_material', state: 'RUNNING', outcome: 'UNKNOWN' } }));
    act(() => busListener?.({ type: 'tool-mutation', row: 'not-a-row' }));
    expect(invalidate).not.toHaveBeenCalled();
    act(() => busListener?.({ type: 'invalidate', keys: [['note-page', 9], 'bad'] }));
    expect(invalidate).toHaveBeenCalledTimes(1);
    expect(invalidate).toHaveBeenCalledWith({ queryKey: ['note-page', 9] });
  });
});
