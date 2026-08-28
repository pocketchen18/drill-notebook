import { useEffect, useRef, useState } from 'react';
import { Button, Message, Popconfirm, Tooltip } from '@arco-design/web-react';
import { ArrowLeft, Edit3, PanelLeftClose, PanelLeftOpen, Plus, RotateCcw, Trash2, X } from 'lucide-react';
import { MarkdownContent } from '../../components/markdown/MarkdownRenderer';
import { buildFullMarkdown, ROOT_ID } from '../../lib/knowledgeTree';
import type { KnowledgeTree, KnowledgeTreeNode } from '../../lib/knowledgeTree';
import {
  summarizePoint,
  resummarizePoint,
  restoreOriginal,
  restoreSummary,
  summarizeBank,
  resummarizeBank,
  restoreOriginalBank,
  restoreSummaryBank,
} from '../../lib/knowledgeApi';
import { friendlyMessage } from '../../lib/errors';
import type { KnowledgePoint, Question } from '../../lib/types';
import { KnowledgeTreeNav } from './KnowledgeTreeNav';

export interface KnowledgeFullCardViewProps {
  tree: KnowledgeTree;
  node: KnowledgeTreeNode;
  questions: Question[];
  bankId?: number;
  onNavigate: (id: number) => void;
  onClose: () => void;
  onDeleted: () => void;
  onModified: () => void;
  onEdit: (point: KnowledgePoint) => void;
  onAddChild?: (parentPoint: KnowledgePoint) => void;
}

const MIN_SIDEBAR_WIDTH = 180;
const DEFAULT_SIDEBAR_WIDTH = 280;
const MAX_SIDEBAR_WIDTH = 600;
const COLLAPSE_THRESHOLD = 120; // 拖拽宽度小于 120px 时直接折叠大纲（类似 VSCode 体验）

export function KnowledgeFullCardView({ tree, node, questions, bankId, onNavigate, onClose, onDeleted, onModified, onEdit, onAddChild }: KnowledgeFullCardViewProps): JSX.Element {
  const isRoot = node.id === ROOT_ID;
  const rootHasSummary = tree.flatList.some((n) => n.hasOriginal);

  // 收集节点及其子孙节点中的有效知识点
  const collectSubtreePoints = (targetNode: KnowledgeTreeNode): KnowledgeTreeNode[] => {
    const list: KnowledgeTreeNode[] = [];
    const walk = (n: KnowledgeTreeNode) => {
      if (n.id !== ROOT_ID) list.push(n);
      n.children.forEach(walk);
    };
    walk(targetNode);
    return list;
  };

  const [sidebarOpen, setSidebarOpen] = useState(true);
  const [sidebarWidth, setSidebarWidth] = useState(DEFAULT_SIDEBAR_WIDTH);
  const [isResizing, setIsResizing] = useState(false);
  const [loading, setLoading] = useState(false);
  const [current, setCurrent] = useState<KnowledgeTreeNode>(node);

  const subPoints = collectSubtreePoints(current);
  const nodeHasSummary = isRoot
    ? rootHasSummary
    : (current.children.length === 0 ? Boolean(current.hasOriginal) : subPoints.some((p) => p.hasOriginal));

  const [view, setView] = useState<'original' | 'summary'>(nodeHasSummary ? 'summary' : 'original');
  const hasOriginal = nodeHasSummary;
  const isLeaf = !isRoot && current.children.length === 0;
  const index = tree.flatList.findIndex((n) => n.id === current.id);
  const total = tree.flatList.length;
  const full = buildFullMarkdown(current);

  const prevNodeIdRef = useRef<number | null>(null);
  const containerRef = useRef<HTMLDivElement>(null);
  const isResizingRef = useRef(false);
  const startXRef = useRef(0);
  const startWidthRef = useRef(DEFAULT_SIDEBAR_WIDTH);

  const sidebarWidthRef = useRef(sidebarWidth);
  sidebarWidthRef.current = sidebarWidth;

  const startResizing = (e: React.MouseEvent) => {
    e.preventDefault();
    isResizingRef.current = true;
    startXRef.current = e.clientX;
    startWidthRef.current = sidebarWidthRef.current;
    setIsResizing(true);
  };

  useEffect(() => {
    const handleMouseMove = (e: MouseEvent) => {
      if (!isResizingRef.current) return;
      const deltaX = e.clientX - startXRef.current;
      const nextWidth = startWidthRef.current + deltaX;

      if (nextWidth < COLLAPSE_THRESHOLD) {
        // 小于阈值直接折叠
        setSidebarOpen(false);
        setSidebarWidth(DEFAULT_SIDEBAR_WIDTH);
        isResizingRef.current = false;
        setIsResizing(false);
      } else {
        // 限制在 [MIN_SIDEBAR_WIDTH, MAX_SIDEBAR_WIDTH] 之间
        const clamped = Math.min(Math.max(nextWidth, MIN_SIDEBAR_WIDTH), MAX_SIDEBAR_WIDTH);
        setSidebarWidth(clamped);
      }
    };

    const handleMouseUp = () => {
      if (isResizingRef.current) {
        isResizingRef.current = false;
        setIsResizing(false);
      }
    };

    window.addEventListener('mousemove', handleMouseMove);
    window.addEventListener('mouseup', handleMouseUp);
    return () => {
      window.removeEventListener('mousemove', handleMouseMove);
      window.removeEventListener('mouseup', handleMouseUp);
    };
  }, []);

  useEffect(() => {
    const idChanged = prevNodeIdRef.current !== node.id;
    prevNodeIdRef.current = node.id;
    setCurrent(node);
    if (idChanged) {
      const isTargetRoot = node.id === ROOT_ID;
      const targetSub = collectSubtreePoints(node);
      const targetHasSummary = isTargetRoot
        ? tree.flatList.some((n) => n.hasOriginal)
        : (node.children.length === 0 ? Boolean(node.hasOriginal) : targetSub.some((p) => p.hasOriginal));
      setView(targetHasSummary ? 'summary' : 'original');
    }
  }, [node, tree]);

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
      if (isRoot) {
        if (!bankId) return;
        if (view === 'summary') {
          await restoreOriginalBank(bankId);
          setView('original');
          onModified();
        } else if (hasOriginal) {
          await restoreSummaryBank(bankId);
          setView('summary');
          onModified();
        } else {
          const result = await summarizeBank(bankId);
          if (result.summarized > 0) {
            await restoreSummaryBank(bankId);
            setView('summary');
            onModified();
          } else {
            const msg = result.errors?.[0] ? `：${result.errors[0]}` : '';
            Message.error(`总结全文失败${msg}`);
          }
        }
      } else if (current.children.length === 0) {
        if (view === 'summary') {
          const result = await restoreOriginal(current.id);
          setCurrent((p) => ({ ...p, content: result.content }));
          setView('original');
          onModified();
        } else if (hasOriginal) {
          try {
            const result = await restoreSummary(current.id);
            setCurrent((p) => ({ ...p, content: result.content }));
            setView('summary');
            onModified();
          } catch {
            // 如果数据库缺失 summary 记录，自愈自动发起即时总结
            const result = await summarizePoint(current.id);
            if (result.summarized > 0) {
              const restored = await restoreSummary(current.id);
              setCurrent((p) => ({ ...p, content: restored.content, hasOriginal: true }));
              setView('summary');
              onModified();
            } else {
              const msg = result.errors?.[0] ? `：${result.errors[0]}` : '';
              Message.error(`总结失败${msg}`);
            }
          }
        } else {
          const result = await summarizePoint(current.id);
          if (result.summarized > 0) {
            const restored = await restoreSummary(current.id);
            setCurrent((p) => ({ ...p, content: restored.content, hasOriginal: true }));
            setView('summary');
            onModified();
          } else {
            const msg = result.errors?.[0] ? `：${result.errors[0]}` : '';
            Message.error(`总结失败${msg}`);
          }
        }
      } else {
        // 中间章节/目录分支节点：批量总结或还原该分支下的所有子知识点
        if (view === 'summary') {
          for (const p of subPoints) {
            if (p.hasOriginal) {
              try { await restoreOriginal(p.id); } catch { /* ignore */ }
            }
          }
          setView('original');
          onModified();
        } else if (hasOriginal) {
          for (const p of subPoints) {
            if (p.hasOriginal) {
              try { await restoreSummary(p.id); } catch { /* ignore */ }
            }
          }
          setView('summary');
          onModified();
        } else {
          for (const p of subPoints) {
            if (!p.hasOriginal) {
              try {
                const result = await summarizePoint(p.id);
                if (result.summarized > 0) await restoreSummary(p.id);
              } catch { /* ignore */ }
            }
          }
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
      if (isRoot) {
        if (!bankId) return;
        const result = await resummarizeBank(bankId);
        if (result.summarized > 0) {
          await restoreSummaryBank(bankId);
          setView('summary');
          onModified();
        } else {
          const msg = result.errors?.[0] ? `：${result.errors[0]}` : '';
          Message.error(`重新总结全文失败${msg}`);
        }
      } else if (current.children.length === 0) {
        const result = await resummarizePoint(current.id);
        if (result.summarized > 0) {
          const restored = await restoreSummary(current.id);
          setCurrent((p) => ({ ...p, content: restored.content }));
          setView('summary');
          onModified();
        }
      } else {
        for (const p of subPoints) {
          if (p.hasOriginal) {
            try {
              const res = await resummarizePoint(p.id);
              if (res.summarized > 0) await restoreSummary(p.id);
            } catch { /* ignore */ }
          }
        }
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
    <div className={`knowledge-full-card ${isResizing ? 'is-resizing' : ''}`} role="dialog" aria-modal="true" aria-label={current.title} ref={containerRef}>
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
          <>
            <Button loading={loading} onClick={() => void handleToggle()}>
              {isRoot ? (view === 'original' ? '总结全文' : '还原全文') : (view === 'original' ? '总结' : '还原')}
            </Button>
            <Tooltip content={hasOriginal ? '' : (isRoot ? '当前知识库还未总结，请先点击"总结全文"' : '当前知识卡片还未总结，请先点击"总结"')}>
              <Button loading={loading} icon={<RotateCcw size={14} />} disabled={!hasOriginal} onClick={() => void handleResummarize()}>
                {isRoot ? '重新总结全文' : '重新总结'}
              </Button>
            </Tooltip>
          </>
          <Button icon={<Plus size={14} />} onClick={() => onAddChild?.(nodeAsPoint(current))}>
            {isRoot ? '新建根知识点' : '添加子知识点'}
          </Button>
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
          <div
            className="knowledge-full-card-sidebar-wrapper"
            style={{ width: sidebarWidth, flex: `0 0 ${sidebarWidth}px` }}
          >
            <div className="knowledge-full-card-sidebar">
              <KnowledgeTreeNav
                tree={tree}
                activeId={current.id}
                search=""
                activeTag={null}
                onSelect={(id) => onNavigate(id)}
              />
            </div>
            <div
              className="knowledge-full-card-resizer"
              onMouseDown={startResizing}
              role="separator"
              aria-orientation="vertical"
              aria-label="拖拽调节大纲宽度"
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
