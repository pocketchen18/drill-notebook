import { Button, Checkbox, Popconfirm, Space, Tag, Tooltip } from '@arco-design/web-react';
import { ArrowDown, ArrowUp, CalendarPlus, Edit3, Trash2 } from 'lucide-react';
import type { KnowledgePoint } from '../../lib/types';
import { MarkdownContent } from '../../components/markdown/MarkdownRenderer';

export interface KnowledgeItemCardProps {
  point: KnowledgePoint;
  selected: boolean;
  onToggleSelect: (checked: boolean) => void;
  onMove: (delta: number) => void;
  onAddToPlan: () => void;
  onEdit: () => void;
  onDelete: () => void;
  onClick: () => void;       // 点卡片本体进入全屏
}

export function KnowledgeItemCard({ point, selected, onToggleSelect, onMove, onAddToPlan, onEdit, onDelete, onClick }: KnowledgeItemCardProps) {
  const stopAnd = (fn: () => void) => (e: { stopPropagation: () => void }): void => { e.stopPropagation(); fn(); };
  return (
    <article className={`knowledge-item ${selected ? 'selected' : ''}`} onClick={onClick} style={{ cursor: 'pointer' }}>
      <div className="knowledge-item-top">
        <Checkbox checked={selected} onChange={onToggleSelect} />
        <div>
          <h3>{point.title}</h3>
          <Space wrap>
            {point.category && <Tag color="arcoblue">{point.category}</Tag>}
            {point.tags.map((tag) => <Tag key={tag}>{tag}</Tag>)}
          </Space>
        </div>
        <Space size={2}>
          <Tooltip content="上移一位"><Button type="text" size="mini" icon={<ArrowUp size={13} />} onClick={stopAnd(() => onMove(-1))} /></Tooltip>
          <Tooltip content="下移一位"><Button type="text" size="mini" icon={<ArrowDown size={13} />} onClick={stopAnd(() => onMove(1))} /></Tooltip>
          <Tooltip content="加入复习计划"><Button type="text" size="mini" icon={<CalendarPlus size={13} />} onClick={stopAnd(onAddToPlan)} /></Tooltip>
          <Tooltip content="编辑这张知识点"><Button type="text" size="mini" icon={<Edit3 size={14} />} onClick={stopAnd(onEdit)} /></Tooltip>
          <Tooltip content="从知识库永久删除"><Popconfirm title="删除这个知识点？" onOk={onDelete}><Button type="text" status="danger" size="mini" icon={<Trash2 size={14} />} /></Popconfirm></Tooltip>
        </Space>
      </div>
      <MarkdownContent value={point.content} />
    </article>
  );
}
