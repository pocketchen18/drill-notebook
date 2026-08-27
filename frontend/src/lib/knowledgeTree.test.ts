import { describe, expect, it } from 'vitest';
import { buildFullMarkdown, buildKnowledgeTree, ROOT_ID } from './knowledgeTree';
import type { KnowledgePoint } from './types';

function point(id: number, title: string, content: string, headingPath: string[], tags: string[] = []): KnowledgePoint {
  return { id, title, content, headingPath, tags, questionIds: [], hasOriginal: false };
}

describe('buildKnowledgeTree', () => {
  it('builds nested tree from headingPath', () => {
    const tree = buildKnowledgeTree([
      point(1, '计算机网络', '概述', []),
      point(2, '传输层', '端到端', ['计算机网络']),
      point(3, 'TCP', '三次握手', ['计算机网络', '传输层']),
      point(4, 'UDP', '无连接', ['计算机网络', '传输层']),
    ]);
    expect(tree.roots).toHaveLength(1);
    expect(tree.roots[0].title).toBe('计算机网络');
    expect(tree.roots[0].children.map((c) => c.title)).toEqual(['传输层']);
    expect(tree.roots[0].children[0].children.map((c) => c.title)).toEqual(['TCP', 'UDP']);
    expect(tree.flatList.map((n) => n.id)).toEqual([1, 2, 3, 4]);
    expect(tree.parentById.get(3)).toBe(2);
    expect(tree.parentById.get(1)).toBe(ROOT_ID);
    expect(tree.rootNode.id).toBe(ROOT_ID);
    expect(tree.rootNode.children).toEqual(tree.roots);
    expect(tree.byId.get(ROOT_ID)).toBe(tree.rootNode);
  });

  it('orders siblings by id ascending (document order)', () => {
    const tree = buildKnowledgeTree([
      point(10, 'UDP', '无连接', ['网络']),
      point(9, 'TCP', '三次握手', ['网络']),
      point(1, '网络', '导语', []),
    ]);
    expect(tree.roots[0].children.map((c) => c.id)).toEqual([9, 10]);
  });

  it('treats points without headingPath as roots', () => {
    const tree = buildKnowledgeTree([point(1, '手记', '内容', [])]);
    expect(tree.roots).toHaveLength(1);
    expect(tree.roots[0].depth).toBe(1);
  });

  it('degrades orphaned headingPath to root node', () => {
    const tree = buildKnowledgeTree([point(1, '孤卡', '内容', ['不存在的父'])]);
    expect(tree.roots).toHaveLength(1);
    expect(tree.roots[0].title).toBe('孤卡');
  });

  it('degrades duplicate sibling titles to root instead of overwriting', () => {
    const tree = buildKnowledgeTree([
      point(0, '章节', '根', []),
      point(1, '实现', '第一个', ['章节']),
      point(2, '实现', '第二个', ['章节']),
      point(3, '方案', '子内容', ['章节', '实现']),
    ]);
    // 第一个「实现」挂到「章节」下；第二个同名「实现」降级为根，其子节点不挂到第一个
    const chapter = tree.roots.find((n) => n.title === '章节');
    expect(chapter?.children.filter((c) => c.title === '实现')).toHaveLength(1);
    expect(tree.roots.some((n) => n.title === '实现')).toBe(true);
  });

  it('builds full markdown for root node showing entire document', () => {
    const tree = buildKnowledgeTree([
      point(1, '第一章', '第一章导言', []),
      point(2, '1.1 概念', '概念详解', ['第一章']),
      point(3, '第二章', '第二章导言', []),
    ]);
    const rootFull = buildFullMarkdown(tree.rootNode);
    expect(rootFull).toBe('# 第一章\n第一章导言\n\n## 1.1 概念\n概念详解\n\n# 第二章\n第二章导言');
  });
});

describe('buildFullMarkdown', () => {
  it('returns own content for leaf', () => {
    const tree = buildKnowledgeTree([point(1, '叶子', '正文', [])]);
    expect(buildFullMarkdown(tree.roots[0])).toBe('正文');
  });

  it('joins parent content with children recursively using relative depth', () => {
    const tree = buildKnowledgeTree([
      point(1, '父', '导语', []),
      point(2, '子', '子正文', ['父']),
    ]);
    expect(buildFullMarkdown(tree.roots[0])).toBe('导语\n\n## 子\n子正文');
  });

  it('returns empty string for empty node', () => {
    const tree = buildKnowledgeTree([point(1, '空', '', [])]);
    expect(buildFullMarkdown(tree.roots[0])).toBe('');
  });
});
