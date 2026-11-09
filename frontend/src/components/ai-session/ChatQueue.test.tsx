import { describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { ChatQueue } from './ChatQueue';
import type { ChatQueueView, QueueItem } from '../../lib/chatQueue';
import { splitReply } from '../../lib/chatQueue';
const item: QueueItem = { requestId: 'second', revision: 2, state: 'PENDING', source: 'human', userText: '下一条消息', sequence: 2 };
const view: ChatQueueView = { sessionId: 1, items: [item, { ...item, requestId: 'third', userText: '第三条' }], steering: [], paused: false, editingId: null };
function props() { return { sessionId: 1, view, runningId: 'first', retry: vi.fn(), action: vi.fn().mockResolvedValue({ ...view, itemRevision: 3 }), pause: vi.fn().mockResolvedValue(undefined) }; }
describe('贴边队列', () => {
  it('默认只展示第一条，菜单有编辑与移除且移除可撤销', async () => {
    const p = props(); render(<ChatQueue {...p} />);
    expect(screen.queryByText('第三条')).not.toBeInTheDocument();
    fireEvent.click(screen.getByText('展开其余 1 条')); expect(screen.getByText('第三条')).toBeVisible();
    fireEvent.click(screen.getAllByRole('button', { name: '待发消息操作' })[0]);
    expect(screen.getByRole('menuitem', { name: '编辑消息' })).toBeVisible();
    fireEvent.click(screen.getByRole('menuitem', { name: '移除消息' }));
    await screen.findByText('已移除');
    fireEvent.click(screen.getByText('撤销'));
    await waitFor(() => expect(p.action).toHaveBeenLastCalledWith(expect.objectContaining({ requestId: 'second', revision: 3 }), 'undo'));
  });
  it('编辑草稿跨会话保留，插话不可发送正在编辑的旧内容，Escape取消', async () => {
    const p = props(); const editView = { ...view, editingId: item.requestId, paused: true };
    const { rerender } = render(<ChatQueue {...p} view={editView} />);
    fireEvent.change(screen.getByRole('textbox', { name: '编辑待发消息' }), { target: { value: '未保存的修改' } });
    expect(screen.getByRole('button', { name: '插话' })).toBeDisabled();
    expect(screen.getByRole('button', { name: '继续发送' })).toBeDisabled();
    rerender(<ChatQueue {...p} sessionId={2} view={{ ...view, sessionId: 2, items: [] }} />);
    rerender(<ChatQueue {...p} view={editView} />);
    expect(screen.getByRole('textbox')).toHaveValue('未保存的修改');
    fireEvent.keyDown(screen.getByRole('textbox'), { key: 'Escape' });
    await waitFor(() => expect(p.action).toHaveBeenCalledWith(item, 'cancelEdit'));
  });
  it('转为插话绑定正在运行的身份，接收未知不提供自动重发', async () => {
    const p = props(); const { rerender } = render(<ChatQueue {...p} />);
    expect(screen.getByRole('button', { name: '插话' })).toHaveAttribute('title', '提交，但不中断模型运行');
    fireEvent.click(screen.getByRole('button', { name: '插话' }));
    await waitFor(() => expect(p.action).toHaveBeenCalledWith(item, 'steer', { targetRequestId: 'first' }));
    rerender(<ChatQueue {...p} view={{ ...view, items: [], steering: [{ ...item, state: 'UNKNOWN' }] }} />);
    expect(screen.getByText('接收未知')).toBeVisible();
    expect(screen.queryByRole('button', { name: '插话' })).not.toBeInTheDocument();
    expect(screen.queryByText('重新发送')).not.toBeInTheDocument();
  });
  it('只把已接收插话按偏移嵌入回复，原段落和附件标记保留', () => {
    expect(splitReply('原答后答', [
      { requestId: 's', userText: '补充', replyOffset: 2, state: 'RECEIVED', attachments: ['例题.md'] },
      { requestId: 'u', userText: '未知', replyOffset: 4, state: 'UNKNOWN' }
    ]).map((p) => [p.role, p.text])).toEqual([['assistant', '原答'], ['user', '补充'], ['assistant', '后答']]);
  });
});
