import { render, screen, fireEvent } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { KnowledgeTreeNav } from './KnowledgeTreeNav';
import { buildKnowledgeTree, ROOT_ID } from '../../lib/knowledgeTree';
import type { KnowledgePoint } from '../../lib/types';

function point(id: number, title: string, content: string, headingPath: string[], tags: string[] = []): KnowledgePoint {
  return { id, title, content, headingPath, tags, questionIds: [], hasOriginal: false };
}

describe('KnowledgeTreeNav', () => {
  const points: KnowledgePoint[] = [
    point(1, '第一章 操作系统', '概论', []),
    point(2, '1.1 进程与线程', '进程模型', ['第一章 操作系统'], ['重点']),
    point(3, '1.2 调度算法', 'FCFS/RR', ['第一章 操作系统'], ['算法']),
  ];

  it('renders root document node and chapters', () => {
    const tree = buildKnowledgeTree(points, '计算机考研知识库');
    const onSelect = vi.fn();

    render(
      <KnowledgeTreeNav
        tree={tree}
        activeId={ROOT_ID}
        search=""
        activeTag={null}
        onSelect={onSelect}
      />
    );

    // 验证根节点显示
    expect(screen.getByText('计算机考研知识库')).toBeInTheDocument();
    expect(screen.getByText('第一章 操作系统')).toBeInTheDocument();
    expect(screen.getByText('1.1 进程与线程')).toBeInTheDocument();
    expect(screen.getByText('1.2 调度算法')).toBeInTheDocument();
  });

  it('triggers onSelect when clicking root or child nodes', () => {
    const tree = buildKnowledgeTree(points);
    const onSelect = vi.fn();

    render(
      <KnowledgeTreeNav
        tree={tree}
        activeId={1}
        search=""
        activeTag={null}
        onSelect={onSelect}
      />
    );

    fireEvent.click(screen.getByText('全部知识点'));
    expect(onSelect).toHaveBeenCalledWith(ROOT_ID);

    fireEvent.click(screen.getByText('1.1 进程与线程'));
    expect(onSelect).toHaveBeenCalledWith(2);
  });

  it('filters nodes by search keyword', () => {
    const tree = buildKnowledgeTree(points);
    const onSelect = vi.fn();

    render(
      <KnowledgeTreeNav
        tree={tree}
        activeId={null}
        search="调度"
        activeTag={null}
        onSelect={onSelect}
      />
    );

    expect(screen.getByText('1.2 调度算法')).toBeInTheDocument();
    expect(screen.queryByText('1.1 进程与线程')).not.toBeInTheDocument();
  });

  it('dims non-matching nodes when activeTag is applied', () => {
    const tree = buildKnowledgeTree(points);
    const onSelect = vi.fn();

    const { container } = render(
      <KnowledgeTreeNav
        tree={tree}
        activeId={null}
        search=""
        activeTag="重点"
        onSelect={onSelect}
      />
    );

    const row1 = container.querySelector('[data-node-id="2"]');
    const row2 = container.querySelector('[data-node-id="3"]');
    expect(row1).not.toHaveClass('dimmed');
    expect(row2).toHaveClass('dimmed');
  });

  it('toggles collapse and expand on node toggle button click', () => {
    const tree = buildKnowledgeTree(points);
    const onSelect = vi.fn();

    render(
      <KnowledgeTreeNav
        tree={tree}
        activeId={null}
        search=""
        activeTag={null}
        onSelect={onSelect}
      />
    );

    const toggleBtn = screen.getByLabelText('折叠全篇');
    fireEvent.click(toggleBtn);

    // 折叠后子节点被隐藏
    expect(screen.queryByText('第一章 操作系统')).not.toBeInTheDocument();
  });
});
