import { render, screen, fireEvent } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { KnowledgeFullCardView } from './KnowledgeFullCardView';
import { buildKnowledgeTree, ROOT_ID } from '../../lib/knowledgeTree';
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

    // 根节点隐藏单卡删除与修改按钮
    expect(screen.queryByRole('button', { name: '删除' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '修改' })).not.toBeInTheDocument();
  });
});
