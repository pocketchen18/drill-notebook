import { useSortable } from '@dnd-kit/sortable';
import { CSS } from '@dnd-kit/utilities';
import { Button, Checkbox, Popconfirm, Tag, Tooltip } from '@arco-design/web-react';
import { CalendarPlus, Edit3, GripVertical, Trash2 } from 'lucide-react';
import type { KnowledgePoint } from '../../lib/types';

const stop = (fn: () => void) => (event: React.SyntheticEvent | Event): void => {
  event.stopPropagation();
  fn();
};

/** 把 Markdown 正文压成一段可读的纯文本摘要：去掉代码块/图片/链接语法与常用标记符，折叠空白并截断。 */
function contentPreview(markdown: string, maxLength = 110): string {
  if (!markdown || !markdown.trim()) return '';
  const text = markdown
    .replace(/```[\s\S]*?```/g, ' ')   // 代码块
    .replace(/!\[[^\]]*\]\([^)]*\)/g, ' ') // 图片
    .replace(/\[([^\]]*)\]\([^)]*\)/g, '$1') // 链接 → 保留链接文字
    .replace(/[#>*_~`|$-]/g, ' ')      // 常用 Markdown 标记符
    .replace(/\s+/g, ' ')
    .trim();
  if (!text) return '';
  return text.length > maxLength ? `${text.slice(0, maxLength)}…` : text;
}

export interface KnowledgeListRowProps {
  point: KnowledgePoint;
  selected: boolean;
  /** 拖拽手柄元素（排序场景由 useSortable 提供；分组查看等非排序场景不传）。 */
  handle?: React.ReactNode;
  /** 是否正在拖拽（占位符样式）。 */
  dragging?: boolean;
  /** 拖拽浮层样式（DragOverlay 使用）。 */
  overlay?: boolean;
  onToggleSelect: (checked: boolean) => void;
  onEdit: () => void;
  onDelete: () => void;
  onAddToPlan: () => void;
  onClick: () => void;
}

/** 知识点列表行（纯展示，不含 dnd），供排序列表与分组查看复用。 */
export function KnowledgeListRow({
  point,
  selected,
  handle,
  dragging = false,
  overlay = false,
  onToggleSelect,
  onEdit,
  onDelete,
  onAddToPlan,
  onClick
}: KnowledgeListRowProps): JSX.Element {
  const stateClass = dragging ? ' is-dragging' : overlay ? ' is-overlay' : '';
  const preview = contentPreview(point.content);
  return (
    <div className={`sortable-kp${stateClass}`}>
      {handle}
      <div
        className="sortable-kp-main"
        role="button"
        tabIndex={0}
        onClick={onClick}
        onKeyDown={(event) => { if (event.key === 'Enter') onClick(); }}
      >
        <div className="sortable-kp-title-row">
          <span className="sortable-kp-check" onClick={(event) => event.stopPropagation()}>
            <Checkbox checked={selected} onChange={onToggleSelect} aria-label={`选择：${point.title}`} />
          </span>
          <div className="sortable-kp-title">{point.title}</div>
        </div>
        <div className="sortable-kp-meta">
          {point.category ? <Tag color="arcoblue">{point.category}</Tag> : null}
          {point.tags.slice(0, 3).map((tag) => <Tag key={tag}>{tag}</Tag>)}
        </div>
        {preview ? <div className="sortable-kp-preview">{preview}</div> : null}
        <div className="sortable-kp-actions">
          <Tooltip content="加入复习计划">
            <Button type="text" size="mini" icon={<CalendarPlus size={13} />} onClick={stop(onAddToPlan)} />
          </Tooltip>
          <Tooltip content="编辑">
            <Button type="text" size="mini" icon={<Edit3 size={14} />} onClick={stop(onEdit)} />
          </Tooltip>
          <Popconfirm title="删除这个知识点？" onOk={stop(onDelete)}>
            <Button type="text" status="danger" size="mini" icon={<Trash2 size={14} />} onClick={stop(() => {})} />
          </Popconfirm>
        </div>
      </div>
    </div>
  );
}

export interface SortableKnowledgeItemProps {
  point: KnowledgePoint;
  selected: boolean;
  onToggleSelect: (checked: boolean) => void;
  onEdit: () => void;
  onDelete: () => void;
  onAddToPlan: () => void;
  onClick: () => void;
}

export function SortableKnowledgeItem({ point, selected, onToggleSelect, onEdit, onDelete, onAddToPlan, onClick }: SortableKnowledgeItemProps): JSX.Element {
  const { attributes, listeners, setNodeRef, transform, transition, isDragging } = useSortable({ id: point.id });

  // 拖拽中的项：留在原位作「占位符」（虚线 + 半透明），真正跟随鼠标的卡片由 DragOverlay 呈现
  const style: React.CSSProperties = {
    transform: isDragging ? undefined : CSS.Transform.toString(transform),
    transition
  };

  const handle = (
    <button
      type="button"
      className="sortable-kp-handle"
      {...attributes}
      {...listeners}
      aria-label={`拖拽排序：${point.title}`}
      title="拖拽调整顺序"
    >
      <GripVertical size={18} />
    </button>
  );

  return (
    <div ref={setNodeRef} style={style}>
      <KnowledgeListRow
        point={point}
        selected={selected}
        handle={handle}
        dragging={isDragging}
        onToggleSelect={onToggleSelect}
        onEdit={onEdit}
        onDelete={onDelete}
        onAddToPlan={onAddToPlan}
        onClick={onClick}
      />
    </div>
  );
}
