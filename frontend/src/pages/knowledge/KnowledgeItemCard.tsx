import { useState } from 'react';
import { Button, Checkbox, Popconfirm, Space, Tag, Tooltip, Typography } from '@arco-design/web-react';
import { CalendarPlus, Edit3, Trash2 } from 'lucide-react';
import type { KnowledgePoint } from '../../lib/types';
import { MarkdownContent } from '../../components/markdown/MarkdownRenderer';

export interface KnowledgeItemCardProps {
  point: KnowledgePoint;
  selected: boolean;
  groupLabel?: string;          // 分级标题（按 headingPath 分组时传入）
  onToggleSelect: (checked: boolean) => void;
  onDrop: (sourceId: number, targetId: number, position: 'before' | 'after') => void;  // 拖拽插入：position 表示插到 targetId 的前面还是后面
  onAddToPlan: () => void;
  onEdit: () => void;
  onDelete: () => void;
  onClick: () => void;       // 点卡片本体进入全屏
}

export function KnowledgeItemCard({ point, selected, groupLabel, onToggleSelect, onDrop, onAddToPlan, onEdit, onDelete, onClick }: KnowledgeItemCardProps) {
  // 'before' = 鼠标在卡的上半部，将插到这张卡前面；'after' = 下半部，插后面
  const [dropPos, setDropPos] = useState<'before' | 'after' | null>(null);
  const stopAnd = (fn: () => void) => (e: { stopPropagation: () => void }): void => { e.stopPropagation(); fn(); };
  // 只有点击卡片本体才进全屏；点按钮/复选框/Popconfirm 弹层等交互控件时忽略。
  const handleArticleClick = (e: React.MouseEvent<HTMLElement>): void => {
    const target = e.target as HTMLElement;
    if (target.closest('button, .arco-checkbox, .arco-popconfirm, .arco-tag, a, input')) return;
    onClick();
  };
  const handleDragOver = (e: React.DragEvent<HTMLElement>): void => {
    e.preventDefault();
    e.dataTransfer.dropEffect = 'move';
    const rect = e.currentTarget.getBoundingClientRect();
    const isUpper = e.clientY < rect.top + rect.height / 2;
    setDropPos(isUpper ? 'before' : 'after');
  };
  return (
    <article
      className={`knowledge-item ${selected ? 'selected' : ''}${dropPos ? ` drop-target-${dropPos}` : ''}`}
      draggable
      onDragStart={(e) => { e.dataTransfer.setData('text/plain', String(point.id)); e.dataTransfer.effectAllowed = 'move'; }}
      onDragOver={handleDragOver}
      onDragLeave={() => setDropPos(null)}
      onDrop={(e) => {
        e.preventDefault();
        const pos = dropPos;
        setDropPos(null);
        const sourceId = Number(e.dataTransfer.getData('text/plain'));
        if (sourceId && sourceId !== point.id && pos) onDrop(sourceId, point.id, pos);
      }}
      onClick={handleArticleClick}
      style={{ cursor: 'pointer' }}
    >
      <div className="knowledge-item-top">
        <Checkbox checked={selected} onChange={onToggleSelect} />
        <div>
          {groupLabel && <Typography.Text type="secondary" style={{ fontSize: 12, display: 'block', marginBottom: 2 }}>{groupLabel}</Typography.Text>}
          <h3>{point.title}</h3>
          <Space wrap>
            {point.category && <Tag color="arcoblue">{point.category}</Tag>}
            {point.tags.map((tag) => <Tag key={tag}>{tag}</Tag>)}
          </Space>
        </div>
        <Space size={2}>
          <Tooltip content="加入复习计划"><Button type="text" size="mini" icon={<CalendarPlus size={13} />} onClick={stopAnd(onAddToPlan)} /></Tooltip>
          <Tooltip content="编辑这张知识点"><Button type="text" size="mini" icon={<Edit3 size={14} />} onClick={stopAnd(onEdit)} /></Tooltip>
          <Tooltip content="从知识库永久删除"><Popconfirm title="删除这个知识点？" onOk={onDelete}><Button type="text" status="danger" size="mini" icon={<Trash2 size={14} />} onClick={stopAnd(() => {})} /></Popconfirm></Tooltip>
        </Space>
      </div>
      <MarkdownContent value={point.content} />
    </article>
  );
}
