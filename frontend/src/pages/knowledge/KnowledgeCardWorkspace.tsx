import { Breadcrumb, Button, Popconfirm, Tag, Tooltip } from '@arco-design/web-react';
import { ArrowLeft, ArrowRight, Edit3, Maximize2, Trash2 } from 'lucide-react';
import { MarkdownContent } from '../../components/markdown/MarkdownRenderer';
import { buildFullMarkdown, ROOT_ID } from '../../lib/knowledgeTree';
import type { KnowledgeTree, KnowledgeTreeNode } from '../../lib/knowledgeTree';
import type { KnowledgePoint, Question } from '../../lib/types';

export interface KnowledgeCardWorkspaceProps {
  tree: KnowledgeTree;
  node: KnowledgeTreeNode;
  questions: Question[];
  onNavigate: (id: number) => void;
  onEdit: (point: KnowledgePoint) => void;
  onDelete: (id: number) => void;
  onTagClick: (tag: string) => void;
  onFullscreen: () => void;
}

export function KnowledgeCardWorkspace({ tree, node, questions, onNavigate, onEdit, onDelete, onTagClick, onFullscreen }: KnowledgeCardWorkspaceProps): JSX.Element {
  const isRoot = node.id === ROOT_ID;
  const index = tree.flatList.findIndex((n) => n.id === node.id);
  const prev = index > 0 ? tree.flatList[index - 1] : undefined;
  const next = index >= 0 && index < tree.flatList.length - 1 ? tree.flatList[index + 1] : undefined;
  const full = buildFullMarkdown(node);
  const crumbs = isRoot ? [node.title] : [...node.headingPath, node.title];

  const nodeAsPoint = (n: KnowledgeTreeNode): KnowledgePoint => ({
    id: n.id,
    title: n.title,
    content: n.content,
    category: n.category,
    tags: n.tags,
    questionIds: n.questionIds,
    headingPath: n.headingPath,
    hasOriginal: n.hasOriginal,
  });

  return (
    <section className="kp-card-workspace">
      <div className="kp-card-breadcrumb">
        <Breadcrumb>
          {crumbs.length > 2 ? <Breadcrumb.Item>…</Breadcrumb.Item> : null}
          {crumbs.slice(crumbs.length > 2 ? crumbs.length - 1 : 0).map((c) => (
            <Breadcrumb.Item key={`${c}-${crumbs.indexOf(c)}`}>{c}</Breadcrumb.Item>
          ))}
        </Breadcrumb>
        <Tooltip content={crumbs.join(' > ')}>
          <span className="kp-card-breadcrumb-path">完整路径</span>
        </Tooltip>
      </div>

      <article className="kp-card">
        <div className="kp-card-toolbar">
          <h2 className="kp-card-title">{node.title}</h2>
          <div className="kp-card-actions">
            {!isRoot && (
              <>
                <Button size="small" icon={<Edit3 size={14} />} onClick={() => onEdit(nodeAsPoint(node))}>编辑</Button>
                <Popconfirm title="删除这个知识点？" onOk={() => onDelete(node.id)}>
                  <Button size="small" status="danger" icon={<Trash2 size={14} />}>删除</Button>
                </Popconfirm>
              </>
            )}
            <Button size="small" icon={<Maximize2 size={14} />} onClick={onFullscreen}>全屏</Button>
          </div>
        </div>
        <div className="kp-card-content">
          <MarkdownContent value={full} />
        </div>
        {node.tags.length > 0 && (
          <div className="kp-card-tags">
            {node.tags.map((tag) => (
              <Tag key={tag} className="kp-card-tag" onClick={() => onTagClick(tag)}>{tag}</Tag>
            ))}
          </div>
        )}
        {node.questionIds.length > 0 && (
          <div className="kp-card-questions">
            <strong>关联题目</strong>
            {node.questionIds.map((id) => (
              <div key={id}>{questions.find((q) => q.id === id)?.stem ?? `题目 #${id}`}</div>
            ))}
          </div>
        )}
      </article>

      {!isRoot && (
        <div className="kp-card-pager">
          <Button disabled={!prev} icon={<ArrowLeft size={14} />} onClick={() => { if (prev) onNavigate(prev.id); }}>上一张</Button>
          <span className="kp-card-pager-count">{index + 1} / {tree.flatList.length}</span>
          <Button disabled={!next} icon={<ArrowRight size={14} />} onClick={() => { if (next) onNavigate(next.id); }}>下一张</Button>
        </div>
      )}
    </section>
  );
}
