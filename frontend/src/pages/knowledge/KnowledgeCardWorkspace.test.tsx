import { render, screen, fireEvent } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { KnowledgeCardWorkspace } from './KnowledgeCardWorkspace';
import { buildKnowledgeTree, ROOT_ID } from '../../lib/knowledgeTree';
import type { KnowledgePoint } from '../../lib/types';

function point(id: number, title: string, content: string, headingPath: string[], tags: string[] = []): KnowledgePoint {
  return { id, title, content, headingPath, tags, questionIds: [], hasOriginal: false };
}

describe('KnowledgeCardWorkspace', () => {
  const points: KnowledgePoint[] = [
    point(1, '第一章 操作系统', '操作系统是系统软件。', []),
    point(2, '1.1 进程管理', '进程是资源分配单位。', ['第一章 操作系统'], ['核心概念']),
  ];
  const tree = buildKnowledgeTree(points, '计算机考研');

  it('renders single knowledge point node with toolbar and content', () => {
    const node = tree.byId.get(2)!;
    const onNavigate = vi.fn();
    const onEdit = vi.fn();
    const onDelete = vi.fn();
    const onTagClick = vi.fn();
    const onFullscreen = vi.fn();

    render(
      <KnowledgeCardWorkspace
        tree={tree}
        node={node}
        questions={[]}
        onNavigate={onNavigate}
        onEdit={onEdit}
        onDelete={onDelete}
        onTagClick={onTagClick}
        onFullscreen={onFullscreen}
      />
    );

    expect(screen.getByRole('heading', { name: '1.1 进程管理' })).toBeInTheDocument();
    expect(screen.getByText('进程是资源分配单位。')).toBeInTheDocument();
    expect(screen.getByText('核心概念')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '编辑' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '删除' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '全屏' })).toBeInTheDocument();
  });

  it('renders root overview node without edit/delete buttons', () => {
    const rootNode = tree.rootNode;
    const onNavigate = vi.fn();
    const onEdit = vi.fn();
    const onDelete = vi.fn();
    const onTagClick = vi.fn();
    const onFullscreen = vi.fn();

    render(
      <KnowledgeCardWorkspace
        tree={tree}
        node={rootNode}
        questions={[]}
        onNavigate={onNavigate}
        onEdit={onEdit}
        onDelete={onDelete}
        onTagClick={onTagClick}
        onFullscreen={onFullscreen}
      />
    );

    expect(screen.getByRole('heading', { name: '计算机考研' })).toBeInTheDocument();
    // 递归拼接了子章节正文
    expect(screen.getByText('操作系统是系统软件。')).toBeInTheDocument();
    expect(screen.getByText('进程是资源分配单位。')).toBeInTheDocument();

    // 根节点不应显示单卡专用的编辑/删除按钮
    expect(screen.queryByRole('button', { name: '编辑' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '删除' })).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: '全屏' })).toBeInTheDocument();
  });

  it('triggers onFullscreen and onTagClick callbacks', () => {
    const node = tree.byId.get(2)!;
    const onFullscreen = vi.fn();
    const onTagClick = vi.fn();

    render(
      <KnowledgeCardWorkspace
        tree={tree}
        node={node}
        questions={[]}
        onNavigate={vi.fn()}
        onEdit={vi.fn()}
        onDelete={vi.fn()}
        onTagClick={onTagClick}
        onFullscreen={onFullscreen}
      />
    );

    fireEvent.click(screen.getByRole('button', { name: '全屏' }));
    expect(onFullscreen).toHaveBeenCalledTimes(1);

    fireEvent.click(screen.getByText('核心概念'));
    expect(onTagClick).toHaveBeenCalledWith('核心概念');
  });

  it('renders toggle summary button in tags row with correct state', () => {
    const p1: KnowledgePoint = { id: 10, title: '卡片已总结', content: '总结正文', headingPath: [], tags: ['标签1'], questionIds: [], hasOriginal: true };
    const p2: KnowledgePoint = { id: 11, title: '卡片未总结', content: '未总结原文', headingPath: [], tags: [], questionIds: [], hasOriginal: false };
    const testTree = buildKnowledgeTree([p1, p2], '测试库');

    const node1 = testTree.byId.get(10)!;
    const node2 = testTree.byId.get(11)!;

    const { rerender } = render(
      <KnowledgeCardWorkspace
        tree={testTree}
        node={node1}
        questions={[]}
        onNavigate={vi.fn()}
        onEdit={vi.fn()}
        onDelete={vi.fn()}
        onTagClick={vi.fn()}
        onFullscreen={vi.fn()}
      />
    );

    // 已总结卡片初始显示「显示原文」
    expect(screen.getByRole('button', { name: '显示原文' })).toBeInTheDocument();

    // 切换到未总结卡片
    rerender(
      <KnowledgeCardWorkspace
        tree={testTree}
        node={node2}
        questions={[]}
        onNavigate={vi.fn()}
        onEdit={vi.fn()}
        onDelete={vi.fn()}
        onTagClick={vi.fn()}
        onFullscreen={vi.fn()}
      />
    );

    // 未总结卡片初始显示「显示总结」
    expect(screen.getByRole('button', { name: '显示总结' })).toBeInTheDocument();
  });
});
