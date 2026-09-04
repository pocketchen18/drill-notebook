import { Breadcrumb, Button, Popconfirm, Tag, Tooltip, Message } from '@arco-design/web-react';
import { ArrowLeft, ArrowRight, Edit3, Maximize2, Plus, RotateCcw, Sparkles, Trash2 } from 'lucide-react';
import { useState, useEffect } from 'react';
import { MarkdownContent } from '../../components/markdown/MarkdownRenderer';
import { buildFullMarkdown, ROOT_ID } from '../../lib/knowledgeTree';
import type { KnowledgeTree, KnowledgeTreeNode } from '../../lib/knowledgeTree';
import type { KnowledgePoint, Question } from '../../lib/types';
import { restoreOriginal, restoreSummary, summarizePoint, restoreOriginalBank, restoreSummaryBank, summarizeBank } from '../../lib/knowledgeApi';
import { friendlyMessage } from '../../lib/errors';

export interface KnowledgeCardWorkspaceProps {
  tree: KnowledgeTree;
  node: KnowledgeTreeNode;
  questions: Question[];
  bankId?: number;
  onNavigate: (id: number) => void;
  onEdit: (point: KnowledgePoint) => void;
  onAddChild?: (parentPoint: KnowledgePoint) => void;
  onDelete: (id: number) => void;
  onTagClick: (tag: string) => void;
  onFullscreen: () => void;
  onModified?: () => void;
}

export function KnowledgeCardWorkspace({ tree, node, questions, bankId, onNavigate, onEdit, onAddChild, onDelete, onTagClick, onFullscreen, onModified }: KnowledgeCardWorkspaceProps): JSX.Element {
  const isRoot = node.id === ROOT_ID;
  const rootHasSummary = tree.flatList.some((n) => n.hasOriginal);
  const index = tree.flatList.findIndex((n) => n.id === node.id);
  const prev = index > 0 ? tree.flatList[index - 1] : undefined;
  const next = index >= 0 && index < tree.flatList.length - 1 ? tree.flatList[index + 1] : undefined;

  // 收集该节点及其所有子节点的叶子/点
  const collectPoints = (n: KnowledgeTreeNode): KnowledgeTreeNode[] => {
    const list: KnowledgeTreeNode[] = [];
    const walk = (item: KnowledgeTreeNode) => {
      if (item.id !== ROOT_ID) list.push(item);
      item.children.forEach(walk);
    };
    walk(n);
    return list;
  };
  const subPoints = collectPoints(node);
  const nodeHasSummary = isRoot
    ? rootHasSummary
    : (node.children.length === 0 ? Boolean(node.hasOriginal) : subPoints.some((p) => p.hasOriginal));

  const [view, setView] = useState<'original' | 'summary'>(nodeHasSummary ? 'summary' : 'original');
  const [loading, setLoading] = useState(false);

  useEffect(() => {
    // 切换节点时根据该节点（或其子树）是否已总结重置视图状态
    setView(nodeHasSummary ? 'summary' : 'original');
  }, [node.id, nodeHasSummary]);

  const full = buildFullMarkdown(node);
  const crumbs = isRoot ? [node.title] : [...node.headingPath, node.title];

  const handleToggleSummary = async (): Promise<void> => {
    setLoading(true);
    try {
      if (isRoot) {
        if (!bankId) return;
        if (view === 'summary') {
          await restoreOriginalBank(bankId);
          setView('original');
          onModified?.();
        } else if (rootHasSummary) {
          await restoreSummaryBank(bankId);
          setView('summary');
          onModified?.();
        } else {
          const res = await summarizeBank(bankId);
          if (res.summarized > 0) {
            await restoreSummaryBank(bankId);
            setView('summary');
            onModified?.();
          } else {
            Message.error(`总结失败${res.errors?.[0] ? `：${res.errors[0]}` : ''}`);
          }
        }
      } else if (node.children.length === 0) {
        // 单个叶子节点
        if (view === 'summary') {
          await restoreOriginal(node.id);
          setView('original');
          onModified?.();
        } else if (node.hasOriginal) {
          await restoreSummary(node.id);
          setView('summary');
          onModified?.();
        } else {
          const res = await summarizePoint(node.id);
          if (res.summarized > 0) {
            await restoreSummary(node.id);
            setView('summary');
            onModified?.();
          } else {
            Message.error(`总结失败${res.errors?.[0] ? `：${res.errors[0]}` : ''}`);
          }
        }
      } else {
        // 中间章节/目录节点：批量操作子树节点
        if (view === 'summary') {
          for (const p of subPoints) {
            if (p.hasOriginal) {
              try { await restoreOriginal(p.id); } catch { /* ignore */ }
            }
          }
          setView('original');
          onModified?.();
        } else if (nodeHasSummary) {
          for (const p of subPoints) {
            if (p.hasOriginal) {
              try { await restoreSummary(p.id); } catch { /* ignore */ }
            }
          }
          setView('summary');
          onModified?.();
        } else {
          for (const p of subPoints) {
            if (!p.hasOriginal) {
              try {
                const res = await summarizePoint(p.id);
                if (res.summarized > 0) await restoreSummary(p.id);
              } catch { /* ignore */ }
            }
          }
          setView('summary');
          onModified?.();
        }
      }
    } catch (error) {
      Message.error(friendlyMessage(error, '切换总结显示失败'));
    } finally {
      setLoading(false);
    }
  };

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
            <Button size="small" icon={<Plus size={14} />} onClick={() => onAddChild?.(nodeAsPoint(node))}>
              {isRoot ? '新建根知识点' : '添加子知识点'}
            </Button>
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

        {/* 分类、标签栏：位于标题下方、正文上方，最右侧显示「显示原文 / 显示总结」切换按钮 */}
        <div className="kp-card-meta-row" style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 16, paddingBottom: 12, borderBottom: '1px solid var(--line)', gap: 12 }}>
          <div className="kp-card-meta-tags" style={{ display: 'flex', flexWrap: 'wrap', alignItems: 'center', gap: 8 }}>
            {node.category && <Tag color="arcoblue">{node.category}</Tag>}
            {node.tags.map((tag) => (
              <Tag key={tag} className="kp-card-tag" onClick={() => onTagClick(tag)}>{tag}</Tag>
            ))}
          </div>
          <div className="kp-card-meta-actions">
            <Button
              size="small"
              type="secondary"
              loading={loading}
              onClick={() => void handleToggleSummary()}
            >
              {view === 'summary' ? '显示原文' : '显示总结'}
            </Button>
          </div>
        </div>

        <div className="kp-card-content">
          <MarkdownContent value={full} />
        </div>

        {node.questionIds.length > 0 && (
          <div className="kp-card-questions">
            <strong>关联题目</strong>
            {node.questionIds.map((id) => (
              <div key={id}><MarkdownContent inline value={questions.find((q) => q.id === id)?.stem ?? `题目 #${id}`} /></div>
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
