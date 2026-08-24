import { useMemo } from 'react';
import { Button, Empty, Popconfirm, Select, Space, Tooltip, Typography } from '@arco-design/web-react';
import { BookOpenCheck, FileText, Shuffle, Trash2 } from 'lucide-react';
import type { Bank, KnowledgePoint } from '../../lib/types';
import { SortableKnowledgeList } from './SortableKnowledgeList';

export interface KnowledgeLibraryViewProps {
  banks: Bank[];
  bankId: number | undefined;
  onBankChange: (id: number) => void;
  points: KnowledgePoint[];
  selectedIds: number[];
  categories: string[];
  tags: string[];
  onCategoriesChange: (values: string[]) => void;
  onTagsChange: (values: string[]) => void;
  onToggleSelect: (id: number, checked: boolean) => void;
  onShuffle: () => void;
  onSelectAllFiltered: () => void;
  allFilteredSelected: boolean;                         // 筛选结果是否已全部选中，决定全选按钮是勾选还是取消勾选
  onDeleteSelected: () => void;                         // 一键删除所有已选知识卡
  deletingSelected: boolean;                            // 批量删除请求进行中
  onAddToPlan: (points: KnowledgePoint[]) => void;
  onEdit: (point?: KnowledgePoint) => void;
  onDelete: (id: number) => void;
  onClickCard: (point: KnowledgePoint) => void;
  onStartSession: () => void;
  onReorder: (sortedIds: number[]) => void;               // 松手后全量持久化：按新顺序的完整 id 数组
  bankHasSummary: boolean;                                // 该 bank 是否至少一张卡已总结
  viewMode: 'summary' | 'original';                      // 当前列表展示的是总结态还是原文态
  onToggleViewMode: () => void;                          // 点「显示原文/显示总结」切换
  viewModeLoading: boolean;                              // 切换中（批量请求未完成）
}

export function KnowledgeLibraryView(props: KnowledgeLibraryViewProps): JSX.Element {
  const {
    banks, bankId, onBankChange, points, selectedIds, categories, tags,
    onCategoriesChange, onTagsChange,
    onToggleSelect, onShuffle, onSelectAllFiltered, allFilteredSelected,
    onDeleteSelected, deletingSelected, onAddToPlan,
    onEdit, onDelete, onClickCard, onStartSession, onReorder,
    bankHasSummary, viewMode, onToggleViewMode, viewModeLoading,
  } = props;

  const categoryOptions = useMemo(() => [...new Set(points.map((p) => p.category).filter(Boolean) as string[])], [points]);
  const tagOptions = useMemo(() => [...new Set(points.flatMap((p) => p.tags))], [points]);
  const matchingPoints = points.filter((p) => (!categories.length || (p.category && categories.includes(p.category))) && (!tags.length || p.tags.some((t) => tags.includes(t))));
  const filtered = [...selectedIds.map((id) => matchingPoints.find((p) => p.id === id)).filter((p): p is KnowledgePoint => Boolean(p)), ...matchingPoints.filter((p) => !selectedIds.includes(p.id))];

  return (
    <section className="panel">
      <div className="panel-header">
        <h2>知识卡编排</h2>
        <Select value={bankId} onChange={(v) => onBankChange(Number(v))} placeholder="选择题库" style={{ width: 280 }}>
          {banks.map((bank) => <Select.Option key={bank.id} value={bank.id}>{bank.name}</Select.Option>)}
        </Select>
      </div>
      <div className="panel-body">
        <div className="advanced-filters advanced-filters-compact">
          <Select mode="multiple" allowClear placeholder="分类" value={categories} onChange={(v) => onCategoriesChange(v.map(String))} style={{ width: 120 }}>
            {categoryOptions.map((item) => <Select.Option key={item} value={item}>{item}</Select.Option>)}
          </Select>
          <Select mode="multiple" allowClear placeholder="标签" value={tags} onChange={(v) => onTagsChange(v.map(String))} style={{ width: 140 }}>
            {tagOptions.map((item) => <Select.Option key={item} value={item}>{item}</Select.Option>)}
          </Select>
          {bankHasSummary && (
            <span style={{ marginLeft: 'auto' }}>
              <Tooltip content={viewMode === 'summary' ? '把所有已总结卡还原为原文显示' : '把所有已总结卡切回 AI 总结显示'}>
                <Button size="small" icon={<FileText size={14} />} loading={viewModeLoading} onClick={onToggleViewMode}>
                  {viewMode === 'summary' ? '显示原文' : '显示总结'}
                </Button>
              </Tooltip>
            </span>
          )}
        </div>
        <div className="selection-toolbar">
          <Space>
            <Button size="small" onClick={onSelectAllFiltered}>{allFilteredSelected ? `取消全选筛选结果（${filtered.length}）` : `全选筛选结果（${filtered.length}）`}</Button>
            <Button size="small" icon={<Shuffle size={14} />} onClick={onShuffle}>随机重排</Button>
            <Popconfirm title={`删除选中的 ${selectedIds.length} 个知识点？`} content="删除后不可恢复，关联的复习计划与记忆曲线记录会一并清除。" disabled={!selectedIds.length} onOk={onDeleteSelected}>
              <Button size="small" status="danger" icon={<Trash2 size={14} />} disabled={!selectedIds.length} loading={deletingSelected}>删除已选（{selectedIds.length}）</Button>
            </Popconfirm>
          </Space>
          <Typography.Text type="secondary">已选 {selectedIds.length}</Typography.Text>
        </div>
        <SortableKnowledgeList
          points={filtered}
          allPoints={points}
          selectedIds={selectedIds}
          onReorder={onReorder}
          onClickCard={onClickCard}
          onToggleSelect={onToggleSelect}
          onEdit={(point) => onEdit(point)}
          onDelete={(id) => onDelete(id)}
          onAddToPlan={(point) => onAddToPlan([point])}
        />
        {!filtered.length && <Empty description="暂无知识点；可新建或导入 Markdown" />}
        <div className="setup-actions"><Button type="primary" icon={<BookOpenCheck size={16} />} disabled={!selectedIds.length} onClick={onStartSession}>开始背知识点（{selectedIds.length}）</Button></div>
      </div>
    </section>
  );
}
