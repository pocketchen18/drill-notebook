import { useEffect, useState } from 'react';
import { Button, Message, Popconfirm, Tooltip } from '@arco-design/web-react';
import { ArrowLeft, Edit3, RotateCcw, Trash2, X } from 'lucide-react';
import type { KnowledgePoint, Question } from '../../lib/types';
import { MarkdownContent } from '../../components/markdown/MarkdownRenderer';
import {
  summarizePoint,
  resummarizePoint,
  restoreOriginal,
  restoreSummary,
} from '../../lib/knowledgeApi';
import { friendlyMessage } from '../../lib/errors';

export interface KnowledgeFullCardViewProps {
  point: KnowledgePoint;
  total: number;
  index: number;
  questions: Question[];
  onClose: () => void;
  onDeleted: () => void;
  onModified: () => void;
  onEdit: (point: KnowledgePoint) => void;
}

export function KnowledgeFullCardView({ point, total, index, questions, onClose, onDeleted, onModified, onEdit }: KnowledgeFullCardViewProps) {
  const [currentPoint, setCurrentPoint] = useState<KnowledgePoint>(point);
  const [view, setView] = useState<'original' | 'summary'>('original');
  const [loading, setLoading] = useState(false);
  const hasOriginal = Boolean(currentPoint.hasOriginal);

  useEffect(() => {
    const onKey = (e: KeyboardEvent): void => { if (e.key === 'Escape') onClose(); };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [onClose]);

  const handleToggle = async (): Promise<void> => {
    setLoading(true);
    try {
      if (view === 'summary') {
        const result = await restoreOriginal(currentPoint.id);
        setCurrentPoint((p) => ({ ...p, content: result.content }));
        setView('original');
      } else if (hasOriginal) {
        const result = await restoreSummary(currentPoint.id);
        setCurrentPoint((p) => ({ ...p, content: result.content }));
        setView('summary');
      } else {
        const result = await summarizePoint(currentPoint.id);
        if (result.summarized > 0) {
          const restored = await restoreSummary(currentPoint.id);
          setCurrentPoint((p) => ({ ...p, content: restored.content, hasOriginal: true }));
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
      const result = await resummarizePoint(currentPoint.id);
      if (result.summarized > 0) {
        const restored = await restoreSummary(currentPoint.id);
        setCurrentPoint((p) => ({ ...p, content: restored.content }));
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
    <div className="knowledge-full-card" role="dialog" aria-modal="true" aria-label={currentPoint.title}>
      <div className="knowledge-full-card-toolbar">
        <Button type="text" icon={<ArrowLeft size={18} />} onClick={onClose}>返回</Button>
        <span className="knowledge-full-card-progress">第 {index + 1} / {total} 个知识点</span>
        <div className="knowledge-full-card-actions">
          <Button loading={loading} onClick={() => void handleToggle()}>
            {view === 'original' ? '总结' : '还原'}
          </Button>
          <Tooltip content={hasOriginal ? '' : '当前知识卡片还未总结，请先点击"总结"'}>
            <Button loading={loading} icon={<RotateCcw size={14} />} disabled={!hasOriginal} onClick={() => void handleResummarize()}>
              重新总结
            </Button>
          </Tooltip>
          <Button icon={<Edit3 size={14} />} onClick={() => onEdit(currentPoint)}>修改</Button>
          <Popconfirm title="删除这个知识点？" onOk={onDeleted}>
            <Button status="danger" icon={<Trash2 size={14} />}>删除</Button>
          </Popconfirm>
          <Button type="text" icon={<X size={18} />} onClick={onClose} aria-label="关闭" />
        </div>
      </div>

      <div className="knowledge-full-card-body">
        <h2 className="knowledge-full-card-title">{currentPoint.title}</h2>
        <div className="knowledge-full-card-content">
          <MarkdownContent value={currentPoint.content} />
        </div>
        {currentPoint.questionIds.length > 0 && (
          <div className="knowledge-full-card-links">
            <strong>关联题目</strong>
            {currentPoint.questionIds.map((id) => (
              <div key={id}>{questions.find((q) => q.id === id)?.stem ?? `题目 #${id}`}</div>
            ))}
          </div>
        )}
      </div>
    </div>
  );
}
