import { describe, it, expect, vi } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import { ToolActivityGroup } from './ToolActivityGroup';
import { mergeToolActivities, readToolActivities, settleToolActivities, toolResultText } from '../../lib/toolActivity';
import type { ToolActivity } from '../../lib/toolActivity';

const started: ToolActivity = { sequence: 1, toolName: 'search_memories', state: 'RUNNING', outcome: 'UNKNOWN',
  startedAt: '2026-11-08T01:00:00.000Z', elapsedMs: null, count: 0, availability: null, incomplete: false };
const ended: ToolActivity = { ...started, state: 'FINISHED', outcome: 'OK', count: 2, elapsedMs: 620 };

describe('工具活动折叠和真实结果', () => {
  it('修改资料展示独立结果、版本冲突和未保存草稿，安全来源不包含修改正文', () => {
    const open = vi.fn();
    const saved = readToolActivities([{ ...ended, toolName: 'update_material', outcome: 'UPDATED',
      sourceLink: 'drill-notebook://material/question/12', changes: { answer: '不展示答案' } }]);
    const view = render(<ToolActivityGroup activities={saved} onOpenMaterial={open} />);
    fireEvent.click(screen.getByRole('button', { name: /已更新学习资料/ }));
    fireEvent.click(screen.getByRole('button', { name: /修改学习资料 已更新/ }));
    expect(screen.getByText('当前本地学习资料')).toBeVisible();
    expect(screen.queryByText('不展示答案')).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: '查看资料 ↗' }));
    expect(open).toHaveBeenCalledWith('drill-notebook://material/question/12');
    const blocked = readToolActivities([{ ...ended, toolName: 'update_material', outcome: 'DRAFT_UNSAVED', count: 0, incomplete: true }]);
    view.rerender(<ToolActivityGroup activities={blocked} onOpenMaterial={open} />);
    expect(screen.getByRole('button', { name: /修改学习资料 请先保存当前笔记草稿/ })).toBeVisible();
    expect(screen.queryByRole('button', { name: '查看资料 ↗' })).not.toBeInTheDocument();
    expect(toolResultText({ ...blocked[0], outcome: 'CONFLICT' })).toContain('重新读取');
    expect(toolResultText({ ...blocked[0], outcome: 'DUPLICATE' })).toBe('已处理，未重复修改');
    view.rerender(<ToolActivityGroup activities={[{ ...blocked[0], state: 'UNKNOWN', outcome: 'UNKNOWN' }]} />);
    expect(screen.getByText(/部分资料是否已保存尚未确认/)).toBeVisible();
  });

  it('资料保存结果保留安全来源，只有点击才导航，未知写入不能宣称失败', () => {
    const link = 'drill-notebook://material/note/12';
    const open = vi.fn();
    const saved = readToolActivities([{ ...ended, toolName: 'create_material', outcome: 'CREATED', sourceLink: link, arguments: '不展示的正文' }]);
    const view = render(<ToolActivityGroup activities={saved} onOpenMaterial={open} />);
    fireEvent.click(screen.getByRole('button', { name: /已保存学习资料/ }));
    fireEvent.click(screen.getByRole('button', { name: /新增学习资料 已保存/ }));
    expect(screen.getByText('当前本地学习资料')).toBeVisible();
    expect(screen.queryByText('不展示的正文')).not.toBeInTheDocument();
    expect(open).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole('button', { name: '查看资料 ↗' }));
    expect(open).toHaveBeenCalledWith(link);
    expect(readToolActivities([{ ...saved[0], sourceLink: 'https://example.com/private' }])[0].sourceLink).toBeUndefined();
    view.rerender(<ToolActivityGroup activities={[...saved, { ...started, sequence: 2, toolName: 'create_material', state: 'UNKNOWN' }]} onOpenMaterial={open} />);
    expect(screen.getByText(/部分资料是否已保存尚未确认/)).toBeVisible();
    expect(screen.getByRole('button', { name: '查看资料 ↗' })).toBeVisible();
  });

  it('派发成功只表示受理，等待中的消息不冒充已读回复，点击才打开目标', () => {
    const link = `drill-notebook://session/${'a'.repeat(32)}`;
    const openSession = vi.fn();
    const rows = readToolActivities([
      { ...ended, toolName: 'create_session', outcome: 'CREATED', count: 1, sessionLink: link },
      { ...ended, sequence: 2, toolName: 'read_session', outcome: 'QUEUED', count: 0, incomplete: true }
    ]);
    render(<ToolActivityGroup activities={rows} onOpenSession={openSession} />);
    const group = screen.getByRole('button', { name: /已查询会话状态 · 已新建会话/ });
    expect(group).not.toHaveAccessibleName(/已读取会话/);
    expect(openSession).not.toHaveBeenCalled();
    fireEvent.click(group);
    expect(screen.getByRole('button', { name: /读取会话 目标消息仍在排队，尚无回复/ })).toBeVisible();
    fireEvent.click(screen.getByRole('button', { name: /新建会话 会话已创建，首条消息已提交/ }));
    fireEvent.click(screen.getByRole('button', { name: '打开会话 ↗' }));
    expect(openSession).toHaveBeenCalledWith(link);
    expect(readToolActivities([{ ...ended, sessionLink: 'https://example.com/unsafe', requestId: 'not-a-request' }])[0].sessionLink).toBeUndefined();
  });

  it('会话工具展示真实进度和分页，不沿用记忆结果文案', () => {
    const view = render(<ToolActivityGroup activities={[{ ...started, toolName: 'list_sessions' }]} active phase="tools" />);
    expect(screen.getByRole('button', { name: /正在查找历史会话/ })).toBeVisible();
    const listed = { ...ended, toolName: 'list_sessions' as const, incomplete: true };
    const reading = { ...started, sequence: 2, toolName: 'read_session' as const };
    view.rerender(<ToolActivityGroup activities={[listed, reading]} active phase="tools" />);
    expect(screen.getByRole('button', { name: /正在读取会话/ })).toBeVisible();
    expect(screen.getAllByText('本次找到 2 个会话（分批结果）')[0]).toBeVisible();
    fireEvent.click(screen.getByRole('button', { name: /查找历史会话 本次找到/ }));
    expect(screen.getAllByText('当前本地数据中的会话')[0]).toBeVisible();
    expect(toolResultText({ ...ended, toolName: 'read_session', incomplete: true })).toBe('本次读取 2 段消息（部分记录）');
    expect(toolResultText({ ...ended, toolName: 'read_session', outcome: 'AMBIGUOUS' })).toBe('找到多个会话，待明确目标');
    expect(toolResultText({ ...ended, toolName: 'read_session', outcome: 'NOT_FOUND' })).toBe('会话不存在或已被删除');
    expect(toolResultText({ ...ended, toolName: 'read_session', outcome: 'STALE' })).toBe('会话历史已变化，需要重新读取');
    expect(toolResultText({ ...ended, toolName: 'read_session', outcome: 'UNAVAILABLE', availability: 'LOCKED' })).toBe('会话需要解锁后读取');
    expect(readToolActivities([listed, reading])[1].toolName).toBe('read_session');
  });
  it('百次工具记录完整保留，读取 20 条不被显示成 15 条', () => {
    const rows = Array.from({ length: 100 }, (_, i) => ({ ...ended, sequence: i + 1, count: 20 }));
    expect(readToolActivities(rows)).toHaveLength(100);
    expect(mergeToolActivities([], rows)).toHaveLength(100);
    expect(toolResultText(readToolActivities(rows)[99])).toBe('已返回 20 条');
  });
  it('执行时展开，正常完成自动收起；历史默认收起', () => {
    const view = render(<ToolActivityGroup activities={[started]} active phase="tools" />);
    expect(screen.getByRole('button', { name: /正在检索记忆/ })).toHaveAttribute('aria-expanded', 'true');
    view.rerender(<ToolActivityGroup activities={[ended]} active phase="answer" />);
    expect(screen.getByText('正在生成回答…')).toBeVisible();
    expect(document.querySelector('.is-running')).toBeNull();
    view.rerender(<ToolActivityGroup activities={[ended]} />);
    expect(screen.getByRole('button', { name: /已检索记忆/ })).toHaveAttribute('aria-expanded', 'false');
  });
  it('手动收起优先于后续错误，展开详情优先于自动收起', () => {
    const onDisclosure = vi.fn();
    const view = render(<ToolActivityGroup activities={[started]} active onDisclosure={onDisclosure} />);
    fireEvent.click(screen.getByRole('button', { name: /正在检索记忆/ }));
    view.rerender(<ToolActivityGroup activities={settleToolActivities([started])} onDisclosure={onDisclosure} />);
    const group = screen.getByRole('button', { name: /部分执行结果未确认/ });
    expect(group).toHaveAttribute('aria-expanded', 'false');
    fireEvent.click(group);
    fireEvent.click(screen.getByRole('button', { name: /检索记忆 执行结果未确认/ }));
    view.rerender(<ToolActivityGroup activities={[ended]} onDisclosure={onDisclosure} />);
    expect(screen.getByRole('button', { name: /已检索记忆/ })).toHaveAttribute('aria-expanded', 'true');
    expect(screen.getByText('search_memories')).toBeVisible();
    expect(onDisclosure).toHaveBeenCalledTimes(3);
  });
  it('中断仅将未确认行收口，已写入数量保留，空结果不显示警告', () => {
    const write = { ...ended, toolName: 'create_memory' as const, outcome: 'CREATED', count: 1 };
    const pending = { ...started, sequence: 2, toolName: 'update_memory' as const };
    const view = render(<ToolActivityGroup activities={[write, pending]} active />);
    view.rerender(<ToolActivityGroup activities={settleToolActivities([write, pending])} />);
    expect(screen.getByRole('button', { name: /新增记忆 已新增 1 条/ })).toBeVisible();
    expect(screen.getByText(/部分写入结果未确认，可到记忆库核对/)).toBeVisible();
    expect(document.querySelector('.is-running')).toBeNull();
    expect(toolResultText({ ...ended, outcome: 'NO_MATCH' })).toBe('未找到相关记忆');
    expect(toolResultText({ ...write, outcome: 'DUPLICATE' })).toBe('已存在，未重复添加');
  });
  it('重复/迟到事件不新增调用或覆盖已确认结果；未知状态和字段安全降级', () => {
    expect(mergeToolActivities([ended], [started, started])).toEqual([ended]);
    expect(mergeToolActivities(settleToolActivities([started]), [ended])).toEqual([ended]);
    const read = readToolActivities([{ ...ended, toolName: 'private-name', outcome: 'private-error', arguments: 'private-body' }]);
    expect(read[0]).toMatchObject({ toolName: 'unknown_tool', outcome: 'UNKNOWN' });
    expect(JSON.stringify(read)).not.toContain('private');
    expect(readToolActivities(null)).toEqual([]);
  });
});
