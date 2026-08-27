import { useEffect, useRef, useState } from 'react';
import { Button, Message, Popconfirm, Tooltip } from '@arco-design/web-react';
import { ArrowLeft, Edit3, PanelLeftClose, PanelLeftOpen, RotateCcw, Trash2, X } from 'lucide-react';
import { MarkdownContent } from '../../components/markdown/MarkdownRenderer';
import { buildFullMarkdown, ROOT_ID } from '../../lib/knowledgeTree';
import type { KnowledgeTree, KnowledgeTreeNode } from '../../lib/knowledgeTree';
import { summarizePoint, resummarizePoint, restoreOriginal, restoreSummary } from '../../lib/knowledgeApi';
import { friendlyMessage } from '../../lib/errors';
import type { KnowledgePoint, Question } from '../../lib/types';
import { KnowledgeTreeNav } from './KnowledgeTreeNav';

export interface KnowledgeFullCardViewProps {
  tree: KnowledgeTree;
  node: KnowledgeTreeNode;
  questions: Question[];
  onNavigate: (id: number) => void;
  onClose: () => void;
  onDeleted: () => void;
  onModified: () => void;
  onEdit: (point: KnowledgePoint) => void;
}

export function KnowledgeFullCardView({ tree, node, questions, onNavigate, onClose, onDeleted, onModified, onEdit }: KnowledgeFullCardViewProps): JSX.Element {
  const isRoot = node.id === ROOT_ID;
  const [sidebarOpen, setSidebarOpen] = useState(true);
  const [view, setView] = useState<'original' | 'summary'>('original');
  const [loading, setLoading] = useState(false);
  const [current, setCurrent] = useState<KnowledgeTreeNode>(node);
  const hasOriginal = Boolean(current.hasOriginal);
  const isLeaf = !isRoot && current.children.length === 0;
  const index = tree.flatList.findIndex((n) => n.id === current.id);
  const total = tree.flatList.length;
  const full = buildFullMarkdown(current);

  const prevNodeIdRef = useRef<number | null>(null);
  useEffect(() => {
    const idChanged = prevNodeIdRef.current !== node.id;
    prevNodeIdRef.current = node.id;
    setCurrent(node);
    if (idChanged) setView('original');
  }, [node]);

  useEffect(() => {
    const onKey = (e: KeyboardEvent): void => {
      if (e.key === 'Escape') { onClose(); }
      else if (e.key === 'ArrowLeft') { const p = tree.flatList[index - 1]; if (p) onNavigate(p.id); }
      else if (e.key === 'ArrowRight') { const n = tree.flatList[index + 1]; if (n) onNavigate(n.id); }
      else if (e.key === 't' || e.key === 'T') setSidebarOpen((v) => !v);
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [onClose, onNavigate, tree, index]);

  const nodeAsPoint = (n: KnowledgeTreeNode): KnowledgePoint => ({
    id: n.id, title: n.title, content: n.content, category: n.category,
    tags: n.tags, questionIds: n.questionIds, headingPath: n.headingPath, hasOriginal: n.hasOriginal,
  });

  const handleToggle = async (): Promise<void> => {
    setLoading(true);
    try {
      if (view === 'summary') {
        const result = await restoreOriginal(current.id);
        setCurrent((p) => ({ ...p, content: result.content }));
        setView('original');
      } else if (hasOriginal) {
        const result = await restoreSummary(current.id);
        setCurrent((p) => ({ ...p, content: result.content }));
        setView('summary');
      } else {
        const result = await summarizePoint(current.id);
        if (result.summarized > 0) {
          const restored = await restoreSummary(current.id);
          setCurrent((p) => ({ ...p, content: restored.content, hasOriginal: true }));
          setView('summary');
          onModified();
        }
      }
    } catch (error) {
      Message.error(friendlyMessage(error, '切换总结显示失败'));
    } finally {
      setLoading(false);
    }
  };

  const handleResummarize = async (): Promise<void> => {
    if (!hasOriginal) return;
    setLoading(true);
    try {
      const result = await resummarizePoint(current.id);
      if (result.summarized > 0) {
        const restored = await restoreSummary(current.id);
        setCurrent((p) => ({ ...p, content: restored.content }));
        setView('summary');
        onModified();
      }
    } catch (error) {
      Message.error(friendlyMessage(error, '重新总结失败'));
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="knowledge-full-card" role="dialog" aria-modal="true" aria-label={current.title}>
      <div className="knowledge-full-card-toolbar">
        <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
          <Button type="text" icon={<ArrowLeft size={18} />} onClick={onClose}>返回</Button>
          <Tooltip content={sidebarOpen ? '折叠大纲 (T)' : '展开大纲 (T)'}>
            <Button
              type="text"
              icon={sidebarOpen ? <PanelLeftClose size={18} /> : <PanelLeftOpen size={18} />}
              onClick={() => setSidebarOpen((v) => !v)}
              aria-label={sidebarOpen ? '折叠大纲' : '展开大纲'}
            >
              {sidebarOpen ? '折叠大纲' : '大纲'}
            </Button>
          </Tooltip>
        </div>
        <span className="knowledge-full-card-progress">
          {isRoot ? '总览全文' : `第 ${index + 1} / ${total} 个知识点`}
        </span>
        <div className="knowledge-full-card-actions">
          {isLeaf && (
            <>
              <Button loading={loading} onClick={() => void handleToggle()}>
                {view === 'original' ? '总结' : '还原'}
              </Button>
              <Tooltip content={hasOriginal ? '' : '当前知识卡片还未总结，请先点击"总结"'}>
                <Button loading={loading} icon={<RotateCcw size={14} />} disabled={!hasOriginal} onClick={() => void handleResummarize()}>重新总结</Button>
              </Tooltip>
            </>
          )}
          {!isRoot && (
            <>
              <Button icon={<Edit3 size={14} />} onClick={() => onEdit(nodeAsPoint(current))}>修改</Button>
              <Popconfirm title="删除这个知识点？" onOk={onDeleted}>
                <Button status="danger" icon={<Trash2 size={14} />}>删除</Button>
              </Popconfirm>
            </>
          )}
          <Button type="text" icon={<X size={18} />} onClick={onClose} aria-label="关闭" />
        </div>
      </div>

      <div className={`knowledge-full-card-main ${sidebarOpen ? 'with-sidebar' : 'without-sidebar'}`}>
        {sidebarOpen && (
          <div className="knowledge-full-card-sidebar">
            <KnowledgeTreeNav
              tree={tree}
              activeId={current.id}
              search=""
              activeTag={null}
              onSelect={(id) => onNavigate(id)}
            />
          </div>
        )}
        <div className="knowledge-full-card-body">
          <h2 className="knowledge-full-card-title">{current.title}</h2>
          <div className="knowledge-full-card-content">
            <MarkdownContent value={full} />
          </div>
          {current.questionIds.length > 0 && (
            <div className="knowledge-full-card-links">
              <strong>关联题目</strong>
              {current.questionIds.map((id) => (
                <div key={id}>{questions.find((q) => q.id === id)?.stem ?? `题目 #${id}`}</div>
              ))}
            </div>
          )}
        </div>
      </div>
    </div>
  );
}
