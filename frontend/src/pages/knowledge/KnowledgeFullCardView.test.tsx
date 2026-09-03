import { render, screen, fireEvent } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { KnowledgeFullCardView } from './KnowledgeFullCardView';
import { buildKnowledgeTree, ROOT_ID } from '../../lib/knowledgeTree';
import { defaultShortcutConfig } from '../../lib/shortcuts';
import { useUiStore } from '../../stores/uiStore';
import type { KnowledgePoint } from '../../lib/types';

function point(id: number, title: string, content: string, headingPath: string[], tags: string[] = []): KnowledgePoint {
  return { id, title, content, headingPath, tags, questionIds: [], hasOriginal: false };
}

describe('KnowledgeFullCardView', () => {
  const points: KnowledgePoint[] = [
    point(1, '第一章 操作系统', '操作系统概论正文。', []),
    point(2, '1.1 进程与线程', '进程模型正文。', ['第一章 操作系统']),
  ];
  const tree = buildKnowledgeTree(points, '计算机考研');

  // 快捷键绑定来自模块级 uiStore：逐条用例复位，避免改绑用例影响其它断言
  beforeEach(() => {
    useUiStore.setState({ shortcutConfig: defaultShortcutConfig() });
  });

  it('renders full screen layout with sidebar navigation and main content', () => {
    const node = tree.byId.get(2)!;
    const onClose = vi.fn();
    const onNavigate = vi.fn();

    render(
      <KnowledgeFullCardView
        tree={tree}
        node={node}
        questions={[]}
        onNavigate={onNavigate}
        onClose={onClose}
        onDeleted={vi.fn()}
        onModified={vi.fn()}
        onEdit={vi.fn()}
      />
    );

    // 验证全屏下左侧大纲存在
    expect(screen.getByText('大纲')).toBeInTheDocument();
    expect(screen.getByText('折叠大纲')).toBeInTheDocument();

    // 验证右侧正文区域
    expect(screen.getByRole('heading', { name: '1.1 进程与线程' })).toBeInTheDocument();
    expect(screen.getByText('进程模型正文。')).toBeInTheDocument();
  });

  it('toggles sidebar collapse/expand via button or T shortcut', () => {
    const node = tree.byId.get(2)!;

    render(
      <KnowledgeFullCardView
        tree={tree}
        node={node}
        questions={[]}
        onNavigate={vi.fn()}
        onClose={vi.fn()}
        onDeleted={vi.fn()}
        onModified={vi.fn()}
        onEdit={vi.fn()}
      />
    );

    const toggleBtn = screen.getByRole('button', { name: '折叠大纲' });
    fireEvent.click(toggleBtn);

    // 折叠后变为展开大纲按钮
    expect(screen.getByRole('button', { name: '展开大纲' })).toBeInTheDocument();

    // 按键盘 T 键再次展开
    fireEvent.keyDown(window, { key: 't' });
    expect(screen.getByRole('button', { name: '折叠大纲' })).toBeInTheDocument();
  });

  it('honours an outline shortcut rebound in settings (T → O)', () => {
    useUiStore.getState().setShortcutConfig({ ...defaultShortcutConfig(), kcToggleOutline: ['O'] });
    const node = tree.byId.get(2)!;

    render(
      <KnowledgeFullCardView
        tree={tree}
        node={node}
        questions={[]}
        onNavigate={vi.fn()}
        onClose={vi.fn()}
        onDeleted={vi.fn()}
        onModified={vi.fn()}
        onEdit={vi.fn()}
      />
    );

    fireEvent.click(screen.getByRole('button', { name: '折叠大纲' }));
    expect(screen.getByRole('button', { name: '展开大纲' })).toBeInTheDocument();

    // 旧键 T 不再生效
    fireEvent.keyDown(window, { key: 't' });
    expect(screen.getByRole('button', { name: '展开大纲' })).toBeInTheDocument();

    // 新键 O 生效
    fireEvent.keyDown(window, { key: 'o' });
    expect(screen.getByRole('button', { name: '折叠大纲' })).toBeInTheDocument();
  });

  it('renders root overview correctly with progress indicator "总览全文"', () => {
    const rootNode = tree.rootNode;

    render(
      <KnowledgeFullCardView
        tree={tree}
        node={rootNode}
        questions={[]}
        onNavigate={vi.fn()}
        onClose={vi.fn()}
        onDeleted={vi.fn()}
        onModified={vi.fn()}
        onEdit={vi.fn()}
      />
    );

    expect(screen.getByText('总览全文')).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: '计算机考研' })).toBeInTheDocument();
    expect(screen.getByText('操作系统概论正文。')).toBeInTheDocument();
    expect(screen.getByText('进程模型正文。')).toBeInTheDocument();

    // 根节点展示总结全文按钮
    expect(screen.getByRole('button', { name: '总结全文' })).toBeInTheDocument();

    // 根节点隐藏单卡删除与修改按钮
    expect(screen.queryByRole('button', { name: '删除' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '修改' })).not.toBeInTheDocument();
  });

  it('supports resizing outline width and collapses when dragged below threshold', () => {
    const node = tree.byId.get(2)!;

    render(
      <KnowledgeFullCardView
        tree={tree}
        node={node}
        questions={[]}
        onNavigate={vi.fn()}
        onClose={vi.fn()}
        onDeleted={vi.fn()}
        onModified={vi.fn()}
        onEdit={vi.fn()}
      />
    );

    const resizer = screen.getByRole('separator', { name: '拖拽调节大纲宽度' });
    expect(resizer).toBeInTheDocument();

    // 模拟拖拽调整宽度
    fireEvent.mouseDown(resizer, { clientX: 280 });
    fireEvent.mouseMove(window, { clientX: 350 });
    fireEvent.mouseUp(window);

    // 仍在展开状态
    expect(screen.getByRole('button', { name: '折叠大纲' })).toBeInTheDocument();

    // 再次从新位置拖拽小于阈值（350 + (100 - 350) = 100px < 120px），自动折叠
    fireEvent.mouseDown(resizer, { clientX: 350 });
    fireEvent.mouseMove(window, { clientX: 100 });
    fireEvent.mouseUp(window);

    // 验证大纲已被折叠
    expect(screen.getByRole('button', { name: '展开大纲' })).toBeInTheDocument();
    expect(screen.queryByRole('separator', { name: '拖拽调节大纲宽度' })).not.toBeInTheDocument();
  });

  it('updates toggle button text correctly between summarized and unsummarized nodes', () => {
    const p1: KnowledgePoint = { id: 10, title: '卡片已总结', content: '总结正文', headingPath: [], tags: [], questionIds: [], hasOriginal: true };
    const p2: KnowledgePoint = { id: 11, title: '卡片未总结', content: '未总结原文', headingPath: [], tags: [], questionIds: [], hasOriginal: false };
    const testTree = buildKnowledgeTree([p1, p2], '测试库');

    const node1 = testTree.byId.get(10)!;
    const node2 = testTree.byId.get(11)!;

    const { rerender } = render(
      <KnowledgeFullCardView
        tree={testTree}
        node={node1}
        questions={[]}
        onNavigate={vi.fn()}
        onClose={vi.fn()}
        onDeleted={vi.fn()}
        onModified={vi.fn()}
        onEdit={vi.fn()}
      />
    );

    // node1 已总结，初始显示「还原」与可用「重新总结」
    expect(screen.getByRole('button', { name: '还原' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '重新总结' })).not.toBeDisabled();

    // 切换到 node2（未总结）
    rerender(
      <KnowledgeFullCardView
        tree={testTree}
        node={node2}
        questions={[]}
        onNavigate={vi.fn()}
        onClose={vi.fn()}
        onDeleted={vi.fn()}
        onModified={vi.fn()}
        onEdit={vi.fn()}
      />
    );

    // node2 未总结，应显示「总结」且「重新总结」禁用
    expect(screen.getByRole('button', { name: '总结' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '重新总结' })).toBeDisabled();
  });

  it('renders summary buttons on intermediate section branch nodes', () => {
    const p1: KnowledgePoint = { id: 20, title: '第一章 架构设计', content: '第一章概述', headingPath: [], tags: [], questionIds: [], hasOriginal: false };
    const p2: KnowledgePoint = { id: 21, title: '1.1 模块划分', content: '模块划分正文', headingPath: ['第一章 架构设计'], tags: [], questionIds: [], hasOriginal: false };
    const testTree = buildKnowledgeTree([p1, p2], '系统库');

    // 目录/父节点（有子节点）
    const sectionNode = testTree.byId.get(20)!;

    render(
      <KnowledgeFullCardView
        tree={testTree}
        node={sectionNode}
        questions={[]}
        onNavigate={vi.fn()}
        onClose={vi.fn()}
        onDeleted={vi.fn()}
        onModified={vi.fn()}
        onEdit={vi.fn()}
      />
    );

    // 中间章节目录节点应展示总结按钮与重新总结按钮（未总结时为总结）
    expect(screen.getByRole('button', { name: '总结' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '重新总结' })).toBeInTheDocument();
  });

  it('triggers Ctrl+F shortcut to show search bar and filters outline & content', () => {
    const node = tree.byId.get(2)!;

    render(
      <KnowledgeFullCardView
        tree={tree}
        node={node}
        questions={[]}
        onNavigate={vi.fn()}
        onClose={vi.fn()}
        onDeleted={vi.fn()}
        onModified={vi.fn()}
        onEdit={vi.fn()}
      />
    );

    // 初始状态没有搜索栏
    expect(screen.queryByPlaceholderText(/搜索大纲与正文/)).not.toBeInTheDocument();

    // 模拟触发 Ctrl+F 快捷键
    fireEvent.keyDown(window, { key: 'f', ctrlKey: true });

    // 搜索栏应该弹出
    const searchInput = screen.getByPlaceholderText(/搜索大纲与正文/);
    expect(searchInput).toBeInTheDocument();

    // 输入搜索词“模型”进行搜索（匹配正文中的“进程模型正文。”）
    fireEvent.change(searchInput, { target: { value: '模型' } });

    // 验证匹配结果统计
    expect(screen.getByText('正文 1 / 1')).toBeInTheDocument();

    // 按 Escape 键关闭搜索栏
    fireEvent.keyDown(window, { key: 'Escape' });
    expect(screen.queryByPlaceholderText(/搜索大纲与正文/)).not.toBeInTheDocument();
  });
});
