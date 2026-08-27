import type { KnowledgePoint } from './types';

export interface KnowledgeTreeNode {
  id: number;
  title: string;
  content: string;
  category?: string;
  tags: string[];
  questionIds: number[];
  hasOriginal: boolean;
  headingPath: string[];
  depth: number;
  children: KnowledgeTreeNode[];
}

export interface KnowledgeTree {
  rootNode: KnowledgeTreeNode;
  roots: KnowledgeTreeNode[];
  flatList: KnowledgeTreeNode[];
  byId: Map<number, KnowledgeTreeNode>;
  parentById: Map<number, number | null>;
}

export const ROOT_ID = 0;

const PATH_SEP = '\u0000';

function pathKey(path: string[]): string {
  return path.join(PATH_SEP);
}

export function buildKnowledgeTree(points: KnowledgePoint[], rootTitle: string = '全部知识点'): KnowledgeTree {
  const byId = new Map<number, KnowledgeTreeNode>();
  const parentById = new Map<number, number | null>();
  const roots: KnowledgeTreeNode[] = [];
  const nodeByPath = new Map<string, KnowledgeTreeNode>();

  // 父节点先于子节点（按 headingPath 深度），同级按 id（=文档顺序）
  const sorted = [...points].sort((a, b) => {
    const da = a.headingPath?.length ?? 0;
    const db = b.headingPath?.length ?? 0;
    if (da !== db) return da - db;
    return a.id - b.id;
  });

  for (const p of sorted) {
    const path = p.headingPath ?? [];
    const node: KnowledgeTreeNode = {
      id: p.id,
      title: p.title,
      content: p.content ?? '',
      category: p.category,
      tags: p.tags ?? [],
      questionIds: p.questionIds ?? [],
      hasOriginal: p.hasOriginal ?? false,
      headingPath: path,
      depth: path.length + 1,
      children: [],
    };
    const fullPathKey = pathKey([...path, p.title]);
    const pathCollision = nodeByPath.has(fullPathKey);
    if (pathCollision) {
      // 同名兄弟标题冲突：不静默覆盖已有路径注册，降级为根节点，防止后代误挂到最后一个实例
      roots.push(node);
      parentById.set(p.id, ROOT_ID);
    } else if (path.length === 0) {
      roots.push(node);
      parentById.set(p.id, ROOT_ID);
    } else {
      const parent = nodeByPath.get(pathKey(path));
      if (parent) {
        parent.children.push(node);
        parentById.set(p.id, parent.id);
      } else {
        roots.push(node); // 异常路径降级为根节点
        parentById.set(p.id, ROOT_ID);
      }
    }
    if (!pathCollision) nodeByPath.set(fullPathKey, node);
    byId.set(p.id, node);
  }

  const rootNode: KnowledgeTreeNode = {
    id: ROOT_ID,
    title: rootTitle,
    content: '',
    category: undefined,
    tags: [],
    questionIds: [],
    hasOriginal: false,
    headingPath: [],
    depth: 0,
    children: roots,
  };
  byId.set(ROOT_ID, rootNode);
  parentById.set(ROOT_ID, null);

  const flatList: KnowledgeTreeNode[] = [];
  const walk = (n: KnowledgeTreeNode): void => {
    flatList.push(n);
    n.children.forEach(walk);
  };
  roots.forEach(walk);

  return { rootNode, roots, flatList, byId, parentById };
}

export function buildFullMarkdown(node: KnowledgeTreeNode): string {
  const parts: string[] = [];
  if (node.content && node.content.trim()) parts.push(node.content);
  for (const child of node.children) {
    const headingLevel = Math.max(1, child.depth);
    const heading = '#'.repeat(headingLevel) + ' ' + child.title;
    const childMd = buildFullMarkdown(child);
    if (childMd) parts.push(`${heading}\n${childMd}`);
    else parts.push(heading);
  }
  return parts.join('\n\n');
}
