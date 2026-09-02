import { useEffect, useRef, useState } from 'react';
import { Tooltip } from '@arco-design/web-react';
import { BookOpen, ChevronDown, ChevronRight, FileText } from 'lucide-react';
import { ROOT_ID } from '../../lib/knowledgeTree';
import type { KnowledgeTree, KnowledgeTreeNode } from '../../lib/knowledgeTree';

export interface KnowledgeTreeNavProps {
  tree: KnowledgeTree;
  activeId: number | null;
  search: string;
  activeTag: string | null;
  onSelect: (id: number) => void;
  /** 折叠状态记忆：挂载时恢复；不传则保持组件内部状态（旧调用方不受影响）。 */
  initialCollapsedKeys?: string[];
  /** 折叠作用域（如题库 id）：变化时重新套用 initialCollapsedKeys。 */
  collapsedScope?: string | number;
  onCollapsedKeysChange?: (keys: string[]) => void;
}

interface TreeNodeViewProps {
  node: KnowledgeTreeNode;
  depth: number;
  activeId: number | null;
  search: string;
  activeTag: string | null;
  collapsed: Set<string>;
  onToggle: (id: number) => void;
  onSelect: (id: number) => void;
}

function subtreeMatches(node: KnowledgeTreeNode, search: string): boolean {
  if (!search) return true;
  if (node.title.toLowerCase().includes(search.toLowerCase())) return true;
  return node.children.some((c) => subtreeMatches(c, search));
}

function TreeNodeView({ node, depth, activeId, search, activeTag, collapsed, onToggle, onSelect }: TreeNodeViewProps): JSX.Element | null {
  const hasChildren = node.children.length > 0;
  const isCollapsed = collapsed.has(String(node.id));
  if (search && !subtreeMatches(node, search)) return null;
  const dimmed = activeTag ? !node.tags.includes(activeTag) : false;

  return (
    <div className="kp-tree-node">
      <div
        className={`kp-tree-row${activeId === node.id ? ' active' : ''}${dimmed ? ' dimmed' : ''}`}
        style={{ paddingLeft: depth * INDENT_PER_LEVEL }}
        data-node-id={node.id}
      >
        <button
          type="button"
          className="kp-tree-toggle"
          onClick={() => { if (hasChildren) onToggle(node.id); }}
          aria-label={hasChildren ? (isCollapsed ? '展开' : '折叠') : '节点'}
        >
          {hasChildren ? (isCollapsed ? <ChevronRight size={14} /> : <ChevronDown size={14} />) : <FileText size={14} />}
        </button>
        <Tooltip content={node.title} position="tl">
          <button type="button" className="kp-tree-title" onClick={() => onSelect(node.id)}>
            {node.title}
          </button>
        </Tooltip>
      </div>
      {hasChildren && (!isCollapsed || !!search) && (
        <div className="kp-tree-children">
          {node.children.map((c) => (
            <TreeNodeView key={c.id} node={c} depth={depth + 1} activeId={activeId} search={search} activeTag={activeTag} collapsed={collapsed} onToggle={onToggle} onSelect={onSelect} />
          ))}
        </div>
      )}
    </div>
  );
}

const INDENT_PER_LEVEL = 14;

export function KnowledgeTreeNav({
  tree,
  activeId,
  search,
  activeTag,
  onSelect,
  initialCollapsedKeys,
  collapsedScope,
  onCollapsedKeysChange
}: KnowledgeTreeNavProps): JSX.Element {
  const [collapsed, setCollapsed] = useState<Set<string>>(() => new Set(initialCollapsedKeys ?? []));
  const prevScopeRef = useRef(collapsedScope);
  const initialKeysRef = useRef(initialCollapsedKeys);
  initialKeysRef.current = initialCollapsedKeys;

  // 切库时套用该库自己的折叠状态（挂载时已用初始值，无需再动）。
  useEffect(() => {
    if (prevScopeRef.current === collapsedScope) return;
    prevScopeRef.current = collapsedScope;
    setCollapsed(new Set(initialKeysRef.current ?? []));
  }, [collapsedScope]);

  useEffect(() => {
    onCollapsedKeysChange?.(Array.from(collapsed).sort((a, b) => Number(a) - Number(b)));
  }, [collapsed]); // eslint-disable-line react-hooks/exhaustive-deps -- 回调稳定即可，避免自触发
  const prevActiveIdRef = useRef<number | null>(null);

  useEffect(() => {
    const prev = prevActiveIdRef.current;
    prevActiveIdRef.current = activeId;
    // Only do work when activeId actually changes (not when the tree object
    // identity changes with the same activeId).
    if (activeId == null || activeId === prev) return;
    const ancestors: string[] = [];
    let cur = tree.parentById.get(activeId);
    while (cur != null) {
      ancestors.push(String(cur));
      cur = tree.parentById.get(cur);
    }
    setCollapsed((prevSet) => {
      const toRemove = ancestors.filter((a) => prevSet.has(a));
      if (toRemove.length === 0) return prevSet;
      const next = new Set(prevSet);
      toRemove.forEach((a) => next.delete(a));
      return next;
    });
    requestAnimationFrame(() => {
      document.querySelector(`[data-node-id="${activeId}"]`)?.scrollIntoView({ block: 'center' });
    });
  }, [activeId, tree]);

  const onToggle = (id: number): void => {
    setCollapsed((prev) => {
      const next = new Set(prev);
      const key = String(id);
      if (next.has(key)) next.delete(key);
      else next.add(key);
      return next;
    });
  };

  const isRootActive = activeId === ROOT_ID;
  const rootNode = tree.rootNode;
  const hasRoots = tree.roots.length > 0;
  const isRootCollapsed = collapsed.has(String(ROOT_ID));

  return (
    <aside className="kp-tree-panel">
      <div className="kp-tree-panel-header">大纲</div>
      <div className="kp-tree-panel-body">
        {hasRoots && (
          <div className="kp-tree-node kp-tree-root-entry">
            <div className={`kp-tree-row${isRootActive ? ' active' : ''}`} data-node-id={ROOT_ID}>
              <button
                type="button"
                className="kp-tree-toggle"
                onClick={() => onToggle(ROOT_ID)}
                aria-label={isRootCollapsed ? '展开全篇' : '折叠全篇'}
              >
                {isRootCollapsed ? <ChevronRight size={14} /> : <ChevronDown size={14} />}
              </button>
              <Tooltip content={rootNode.title} position="tl">
                <button type="button" className="kp-tree-title kp-tree-root-title" onClick={() => onSelect(ROOT_ID)}>
                  <BookOpen size={14} style={{ marginRight: 4, verticalAlign: -2 }} />
                  {rootNode.title}
                </button>
              </Tooltip>
            </div>
            {(!isRootCollapsed || !!search) && (
              <div className="kp-tree-children">
                {tree.roots.map((root) => (
                  <TreeNodeView
                    key={root.id}
                    node={root}
                    depth={1}
                    activeId={activeId}
                    search={search}
                    activeTag={activeTag}
                    collapsed={collapsed}
                    onToggle={onToggle}
                    onSelect={onSelect}
                  />
                ))}
              </div>
            )}
          </div>
        )}
      </div>
    </aside>
  );
}
