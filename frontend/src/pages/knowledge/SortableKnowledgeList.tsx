import { useMemo, useState } from 'react';
import { DndContext, DragOverlay, PointerSensor, closestCenter, useSensor, useSensors, type DragEndEvent, type DragStartEvent } from '@dnd-kit/core';
import { SortableContext, verticalListSortingStrategy } from '@dnd-kit/sortable';
import { restrictToParentElement, restrictToVerticalAxis } from '@dnd-kit/modifiers';
import { Empty, Tag } from '@arco-design/web-react';
import { GripVertical } from 'lucide-react';
import type { KnowledgePoint } from '../../lib/types';
import { alignOrder, reorderIds } from '../../lib/sortOrder';
import { useKnowledgeSortStore } from '../../stores/knowledgeSortStore';
import { KnowledgeListRow, SortableKnowledgeItem } from './SortableKnowledgeItem';

export interface SortableKnowledgeListProps {
  /** 显示的列表（可能已被分类/标签筛选）。 */
  points: KnowledgePoint[];
  /** 当前题库全部知识点：全量重排的依据（忽略筛选）。 */
  allPoints: KnowledgePoint[];
  selectedIds: number[];
  onReorder: (sortedIds: number[]) => void;
  onClickCard: (point: KnowledgePoint) => void;
  onToggleSelect: (id: number, checked: boolean) => void;
  onEdit: (point: KnowledgePoint) => void;
  onDelete: (id: number) => void;
  onAddToPlan: (point: KnowledgePoint) => void;
}

export function SortableKnowledgeList(props: SortableKnowledgeListProps): JSX.Element {
  const { points, allPoints, selectedIds, onReorder, onClickCard, onToggleSelect, onEdit, onDelete, onAddToPlan } = props;
  const orderedIds = useKnowledgeSortStore((state) => state.orderedIds);
  const setOrderedIds = useKnowledgeSortStore((state) => state.setOrderedIds);
  const [activeId, setActiveId] = useState<number | null>(null);

  // 拖拽需移动 4px 才激活，避免与「点行进全屏 / 勾选 / 按钮」冲突
  const sensors = useSensors(useSensor(PointerSensor, { activationConstraint: { distance: 4 } }));

  const pointById = useMemo(() => new Map(points.map((point) => [point.id, point])), [points]);

  // 可见列表 = 全库顺序在当前筛选结果上的投影（拖拽时按可见项让位）
  const items = useMemo(() => alignOrder(orderedIds, points)
    .map((id) => pointById.get(id))
    .filter((point): point is KnowledgePoint => Boolean(point)), [points, orderedIds, pointById]);

  // 全库 id 顺序：全量更新持久化用（忽略筛选，隐藏项保持相对位置）
  const allIds = useMemo(() => alignOrder(orderedIds, allPoints), [orderedIds, allPoints]);

  const activePoint = activeId == null ? undefined : pointById.get(activeId);

  const handleDragStart = (event: DragStartEvent): void => setActiveId(Number(event.active.id));

  const handleDragEnd = (event: DragEndEvent): void => {
    setActiveId(null);
    const { active, over } = event;
    if (!over || active.id === over.id) return;
    if (!allIds.includes(Number(active.id)) || !allIds.includes(Number(over.id))) return;
    // 方案 A（全量更新）：在「全库 id 顺序」上用 arrayMove 整段重排
    const nextIds = reorderIds(allIds, Number(active.id), Number(over.id));
    setOrderedIds(nextIds); // Zustand set：本地状态写回完整新数组
    onReorder(nextIds);     // React Query mutation：全量持久化
  };

  if (!items.length) {
    return <Empty description="暂无知识点；可新建或导入 Markdown" />;
  }

  return (
    <DndContext
      sensors={sensors}
      collisionDetection={closestCenter}
      modifiers={[restrictToVerticalAxis, restrictToParentElement]}
      onDragStart={handleDragStart}
      onDragEnd={handleDragEnd}
      onDragCancel={() => setActiveId(null)}
    >
      <SortableContext items={items.map((point) => point.id)} strategy={verticalListSortingStrategy}>
        <div className="sortable-kp-list">
          {items.map((point) => (
            <SortableKnowledgeItem
              key={point.id}
              point={point}
              selected={selectedIds.includes(point.id)}
              onToggleSelect={(checked) => onToggleSelect(point.id, checked)}
              onEdit={() => onEdit(point)}
              onDelete={() => onDelete(point.id)}
              onAddToPlan={() => onAddToPlan(point)}
              onClick={() => onClickCard(point)}
            />
          ))}
        </div>
      </SortableContext>
      <DragOverlay>
        {activePoint ? (
          <div className="sortable-kp is-overlay">
            <span className="sortable-kp-handle"><GripVertical size={18} /></span>
            <div className="sortable-kp-main">
              <div className="sortable-kp-title-row">
                <div className="sortable-kp-title">{activePoint.title}</div>
              </div>
              <div className="sortable-kp-meta">
                {activePoint.category ? <Tag color="arcoblue">{activePoint.category}</Tag> : null}
                {activePoint.tags.slice(0, 3).map((tag) => <Tag key={tag}>{tag}</Tag>)}
              </div>
              {activePoint.content && activePoint.content.trim() ? <div className="sortable-kp-preview">{activePoint.content.replace(/[#>*_~`|$-]/g, ' ').replace(/\s+/g, ' ').trim().slice(0, 110)}</div> : null}
            </div>
          </div>
        ) : null}
      </DragOverlay>
    </DndContext>
  );
}
