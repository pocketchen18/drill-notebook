import { beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { SessionNavigation } from './SessionNavigation';
import type { AiChatSession } from '../../lib/types';

const sessions: AiChatSession[] = [
  { id: 1, title: '当前讨论', titleSource: 'ai', titleStatus: 'ready', titleRevision: 2, canGenerateTitle: true, messageCount: 4, sessionLink: 'drill-notebook://session/aaa' },
  { id: 2, title: '积分换元', titleSource: 'manual', titleStatus: 'idle', titleRevision: 3, canGenerateTitle: true, messageCount: 2, sessionLink: 'drill-notebook://session/bbb' }
];
const setup = (overrides = {}) => {
  const props = { visible: true, sessions, currentId: 1, loading: false, error: false, creating: false, exporting: false,
    onRetry: vi.fn(), onCreate: vi.fn(), onSelect: vi.fn(), onRename: vi.fn().mockResolvedValue(undefined),
    onGenerate: vi.fn().mockResolvedValue(undefined), onDelete: vi.fn().mockResolvedValue(undefined), onExport: vi.fn().mockResolvedValue(undefined), ...overrides };
  const view = render(<SessionNavigation {...props} />);
  return { ...view, props };
};
const openTarget = async () => {
  fireEvent.click(screen.getByRole('button', { name: '切换会话' }));
  fireEvent.contextMenu(screen.getByRole('button', { name: /积分换元 2 条消息/ }), { clientX: 700, clientY: 150 });
  return screen.findByRole('menu', { name: '会话操作：积分换元' });
};

describe('会话导航', () => {
  it('多会话状态独立，忙碌目标可停止但不可删除，标题生成不冒充聊天运行', async () => {
    const onStop = vi.fn().mockResolvedValue(undefined);
    const view = setup({ onStop, runtimes: [{ sessionId: 2, requestId: 'b', status: 'RUNNING', phase: 'tools', queueCount: 2 }],
      sessions: [{ ...sessions[0], titleStatus: 'generating' }, sessions[1]] });
    expect(screen.queryByLabelText('正在生成标题')).toBeNull();
    await openTarget();
    expect(screen.getByText('1 个进行中')).toBeVisible();
    expect(screen.getByLabelText('正在调用工具')).toBeVisible();
    expect(screen.getByRole('menuitem', { name: '删除会话' })).toBeDisabled();
    fireEvent.click(screen.getByRole('menuitem', { name: '停止回复' }));
    await waitFor(() => expect(onStop).toHaveBeenCalledWith(2));
    expect(view.props.onSelect).not.toHaveBeenCalled();
  });

  beforeEach(() => {
    Object.defineProperty(navigator, 'clipboard', { value: { writeText: vi.fn().mockResolvedValue(undefined) }, configurable: true });
    Object.defineProperty(window, 'innerWidth', { value: 1024, configurable: true });
  });

  it('非当前行的重命名保持目标，后台标题刷新不覆盖草稿，中文组合输入不提交', async () => {
    const view = setup(); await openTarget();
    fireEvent.click(screen.getByRole('menuitem', { name: /重命名/ }));
    const input = await screen.findByRole('textbox', { name: '重命名会话' });
    expect(document.activeElement).toBe(input);
    fireEvent.change(input, { target: { value: '我尚未保存的草稿' } });
    view.rerender(<SessionNavigation {...view.props} sessions={[sessions[0], { ...sessions[1], title: '后台回填' }]} />);
    expect(input).toHaveValue('我尚未保存的草稿');
    fireEvent.keyDown(input, { key: 'Enter', isComposing: true, keyCode: 229 });
    expect(view.props.onRename).not.toHaveBeenCalled();
    fireEvent.keyDown(input, { key: 'Enter' });
    await waitFor(() => expect(screen.queryByLabelText('重命名会话')).toBeNull());
    expect(view.props.onRename).toHaveBeenCalledWith(2, '我尚未保存的草稿');
    expect(view.props.onSelect).not.toHaveBeenCalled();
    expect(screen.getByRole('button', { name: '切换会话' })).toHaveTextContent('当前讨论');
  });

  it('保存失败保留草稿，空白报错，Esc 取消而不发请求', async () => {
    const view = setup({ onRename: vi.fn().mockRejectedValue(new Error('磁盘忙')) }); await openTarget();
    fireEvent.click(screen.getByRole('menuitem', { name: /重命名/ }));
    const input = await screen.findByLabelText('重命名会话');
    fireEvent.change(input, { target: { value: '   ' } }); fireEvent.blur(input);
    expect(await screen.findByRole('alert')).toHaveTextContent('标题不能为空');
    expect(view.props.onRename).not.toHaveBeenCalled();
    fireEvent.change(input, { target: { value: '草稿' } }); fireEvent.keyDown(input, { key: 'Enter' });
    expect(await screen.findByText('磁盘忙')).toBeVisible();
    expect(input).toHaveValue('草稿');
    fireEvent.keyDown(input, { key: 'Escape' });
    expect(screen.queryByLabelText('重命名会话')).toBeNull();
    expect(view.props.onRename).toHaveBeenCalledTimes(1);
  });

  it('复制与导出子菜单绑定非当前目标，穿越父子菜单保持展开', async () => {
    const view = setup(); await openTarget();
    fireEvent.mouseEnter(screen.getByRole('menuitem', { name: '复制' }));
    fireEvent.mouseLeave(screen.getByRole('menuitem', { name: '复制' }));
    fireEvent.click(screen.getByRole('menuitem', { name: '复制会话链接' }));
    await waitFor(() => expect(navigator.clipboard.writeText).toHaveBeenCalledWith(sessions[1].sessionLink));
    fireEvent.click(screen.getByRole('button', { name: '更多操作：积分换元' }));
    await screen.findByRole('menu', { name: '会话操作：积分换元' });
    fireEvent.click(screen.getByRole('menuitem', { name: '导出' }));
    fireEvent.click(screen.getByRole('menuitem', { name: 'Markdown' }));
    expect(view.props.onExport).toHaveBeenCalledWith(2, 'md');
    expect(view.props.onSelect).not.toHaveBeenCalled();
  });

  it('剪贴板拒绝时提供真实可选文本', async () => {
    vi.mocked(navigator.clipboard.writeText).mockRejectedValue(new Error('denied'));
    setup(); await openTarget();
    fireEvent.click(screen.getByRole('menuitem', { name: '复制' }));
    fireEvent.click(screen.getByRole('menuitem', { name: '复制会话标题' }));
    expect(await screen.findByLabelText('待复制内容')).toHaveValue('积分换元');
  });

  it('删除非当前项先确认；失败保留弹窗，重试成功仍不切换会话', async () => {
    const onDelete = vi.fn().mockRejectedValueOnce(new Error('会话有正在生成的回答，请先停止')).mockResolvedValue(undefined);
    const view = setup({ onDelete }); await openTarget();
    fireEvent.click(screen.getByRole('menuitem', { name: '删除会话' }));
    const modal = await screen.findByRole('dialog', { name: '删除会话？' });
    expect(modal).toHaveTextContent('长期记忆会保留');
    expect(onDelete).not.toHaveBeenCalled();
    fireEvent.click(within(modal).getByRole('button', { name: '删除会话' }));
    expect(await within(modal).findByRole('alert')).toHaveTextContent('请先停止');
    fireEvent.click(within(modal).getByRole('button', { name: '删除会话' }));
    await waitFor(() => expect(onDelete).toHaveBeenCalledTimes(2));
    expect(onDelete).toHaveBeenLastCalledWith(2);
    expect(view.props.onSelect).not.toHaveBeenCalled();
  });

  it('覆盖人工标题需要确认，并携带原版本；旧空会话不能生成但最后一项可删除', async () => {
    const view = setup(); await openTarget();
    fireEvent.click(screen.getByRole('menuitem', { name: '重新生成标题' }));
    const modal = await screen.findByRole('dialog', { name: '替换手动设置的标题？' });
    expect(view.props.onGenerate).not.toHaveBeenCalled();
    fireEvent.click(within(modal).getByRole('button', { name: '生成标题' }));
    await waitFor(() => expect(view.props.onGenerate).toHaveBeenCalledWith(sessions[1], true));
    view.unmount();
    setup({ sessions: [{ id: 1, title: '新会话', canGenerateTitle: false }] });
    fireEvent.click(screen.getByRole('button', { name: '会话更多操作' }));
    expect(await screen.findByRole('menuitem', { name: '生成会话标题' })).toBeDisabled();
    expect(screen.getByRole('menuitem', { name: '删除会话' })).toBeEnabled();
  });

  it('未保存的新会话页没有当前会话菜单，但仍可浏览已有会话', () => {
    setup({ currentId: undefined });
    expect(screen.getByRole('button', { name: '切换会话' })).toHaveTextContent('新会话');
    expect(screen.getByRole('button', { name: '会话更多操作' })).toBeDisabled();
    fireEvent.click(screen.getByRole('button', { name: '切换会话' }));
    expect(screen.getByRole('button', { name: /积分换元 2 条消息/ })).toBeVisible();
    expect(document.querySelector('[aria-current="true"]')).toBeNull();
  });

  it('360px 使用返回式二级菜单；Esc 逐级退出，键盘菜单不切换会话', async () => {
    Object.defineProperty(window, 'innerWidth', { value: 360, configurable: true });
    const view = setup();
    fireEvent.click(screen.getByRole('button', { name: '切换会话' }));
    const row = screen.getByRole('button', { name: /积分换元 2 条消息/ });
    row.focus(); fireEvent.keyDown(row, { key: 'F10', shiftKey: true });
    await screen.findByRole('menu', { name: '会话操作：积分换元' });
    const copy = screen.getByRole('menuitem', { name: '复制' }); copy.focus();
    fireEvent.mouseEnter(copy);
    expect(screen.queryByRole('menuitem', { name: '复制会话标题' })).toBeNull();
    fireEvent.keyDown(copy, { key: 'ArrowRight' });
    expect(screen.getByRole('menuitem', { name: '返回' })).toBeVisible();
    expect(screen.queryByRole('menuitem', { name: /重命名/ })).toBeNull();
    fireEvent.keyDown(document, { key: 'Escape' });
    expect(await screen.findByRole('menuitem', { name: /重命名/ })).toBeVisible();
    fireEvent.keyDown(document, { key: 'Escape' });
    expect(screen.queryByRole('menu')).toBeNull();
    expect(document.activeElement).toBe(row);
    fireEvent.keyDown(document, { key: 'Escape' });
    expect(screen.queryByRole('dialog', { name: '会话列表' })).toBeNull();
    expect(document.activeElement).toBe(screen.getByRole('button', { name: '切换会话' }));
    expect(view.props.onSelect).not.toHaveBeenCalled();
  });

  it('会话列表错误可重试，加载和空列表都有状态', () => {
    const view = setup({ error: true, sessions: [] });
    fireEvent.click(screen.getByRole('button', { name: '切换会话' }));
    fireEvent.click(screen.getByRole('button', { name: '重试' }));
    expect(view.props.onRetry).toHaveBeenCalled();
    view.rerender(<SessionNavigation {...view.props} error={false} loading />);
    expect(within(screen.getByRole('dialog', { name: '会话列表' })).getByText('正在加载会话…')).toBeVisible();
    view.rerender(<SessionNavigation {...view.props} error={false} />);
    expect(screen.getByText('暂无会话')).toBeVisible();
  });
});
