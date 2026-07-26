import { useMemo } from 'react';
import { Button, Empty, Select, Space, Tooltip, Typography } from '@arco-design/web-react';
import { BookOpenCheck, FileText, Shuffle } from 'lucide-react';
import type { Bank, KnowledgePoint } from '../../lib/types';
import { KnowledgeItemCard } from './KnowledgeItemCard';

export interface KnowledgeLibraryViewProps {
  banks: Bank[];
  bankId: number | undefined;
  onBankChange: (id: number) => void;
  points: KnowledgePoint[];
  selectedIds: number[];
  categories: string[];
  tags: string[];
  groupLevel: number;
  onCategoriesChange: (values: string[]) => void;
  onTagsChange: (values: string[]) => void;
  onGroupLevelChange: (level: number) => void;
  onToggleSelect: (id: number, checked: boolean) => void;
  onMove: (id: number, delta: number) => void;
  onShuffle: () => void;
  onSelectAllFiltered: () => void;
  onAddToPlan: (points: KnowledgePoint[]) => void;
  onEdit: (point?: KnowledgePoint) => void;
  onDelete: (id: number) => void;
  onClickCard: (point: KnowledgePoint) => void;
  onStartSession: () => void;
  bankHasSummary: boolean;                                // 该 bank 是否至少一张卡已总结
  viewMode: 'summary' | 'original';                      // 当前列表展示的是总结态还是原文态
  onToggleViewMode: () => void;                          // 点「显示原文/显示总结」切换
  viewModeLoading: boolean;                              // 切换中（批量请求未完成）
}

export function KnowledgeLibraryView(props: KnowledgeLibraryViewProps) {
  const {
    banks, bankId, onBankChange, points, selectedIds, categories, tags, groupLevel,
    onCategoriesChange, onTagsChange, onGroupLevelChange,
    onToggleSelect, onMove, onShuffle, onSelectAllFiltered, onAddToPlan,
    onEdit, onDelete, onClickCard,
    onStartSession,
    bankHasSummary, viewMode, onToggleViewMode, viewModeLoading,
  } = props;

  const categoryOptions = useMemo(() => [...new Set(points.map((p) => p.category).filter(Boolean) as string[])], [points]);
  const tagOptions = useMemo(() => [...new Set(points.flatMap((p) => p.tags))], [points]);
  const matchingPoints = points.filter((p) => (!categories.length || (p.category && categories.includes(p.category))) && (!tags.length || p.tags.some((t) => tags.includes(t))));
  const filtered = [...selectedIds.map((id) => matchingPoints.find((p) => p.id === id)).filter((p): p is KnowledgePoint => Boolean(p)), ...matchingPoints.filter((p) => !selectedIds.includes(p.id))];

  // 标题分级切换覆盖：按 groupLevel 分组后直接渲染，无折叠态
  const grouped = useMemo(() => {
    if (groupLevel === 0) return null;
    const groups = new Map<string, KnowledgePoint[]>();
    const orphansKey = '（未分类 / 无标题链）';
    for (const point of filtered) {
      const key = point.headingPath?.[groupLevel - 1] ?? orphansKey;
      const arr = groups.get(key) ?? [];
      arr.push(point);
      groups.set(key, arr);
    }
    const entries = [...groups.entries()].sort(([a], [b]) => {
      if (a === orphansKey) return 1;
      if (b === orphansKey) return -1;
      return a.localeCompare(b, 'zh');
    });
    return entries;
  }, [filtered, groupLevel]);

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
          <Select value={groupLevel} onChange={(v) => onGroupLevelChange(Number(v))} style={{ width: 180 }} aria-label="按级分组查看">
            <Select.Option key={0} value={0}>不分组</Select.Option>
            {[1, 2, 3, 4, 5, 6].map((level) => <Select.Option key={level} value={level}>{level} 级标题分组</Select.Option>)}
          </Select>
          {bankHasSummary && (
            <Tooltip content={viewMode === 'summary' ? '把所有已总结卡还原为原文显示' : '把所有已总结卡切回 AI 总结显示'}>
              <Button size="small" icon={<FileText size={14} />} loading={viewModeLoading} onClick={onToggleViewMode}>
                {viewMode === 'summary' ? '显示原文' : '显示总结'}
              </Button>
            </Tooltip>
          )}
        </div>
        <div className="selection-toolbar">
          <Space>
            <Button size="small" onClick={onSelectAllFiltered}>全选筛选结果（{filtered.length}）</Button>
            <Button size="small" icon={<Shuffle size={14} />} onClick={onShuffle}>随机重排</Button>
          </Space>
          <Typography.Text type="secondary">已选 {selectedIds.length}</Typography.Text>
        </div>
        {grouped ? (
          <div className="knowledge-groups">
            {grouped.map(([groupKey, items]) => (
              <section className="knowledge-group" key={groupKey}>
                <header className="knowledge-group-header"><h3>{groupKey}</h3><Typography.Text type="secondary">{items.length} 个</Typography.Text></header>
                <div className="knowledge-grid">{items.map((point) => <KnowledgeItemCard key={point.id} point={point} selected={selectedIds.includes(point.id)} onToggleSelect={(c) => onToggleSelect(point.id, c)} onMove={(d) => onMove(point.id, d)} onAddToPlan={() => onAddToPlan([point])} onEdit={() => onEdit(point)} onDelete={() => onDelete(point.id)} onClick={() => onClickCard(point)} />)}</div>
              </section>
            ))}
          </div>
        ) : (
          <div className="knowledge-grid">{filtered.map((point) => <KnowledgeItemCard key={point.id} point={point} selected={selectedIds.includes(point.id)} onToggleSelect={(c) => onToggleSelect(point.id, c)} onMove={(d) => onMove(point.id, d)} onAddToPlan={() => onAddToPlan([point])} onEdit={() => onEdit(point)} onDelete={() => onDelete(point.id)} onClick={() => onClickCard(point)} />)}</div>
        )}
        {!filtered.length && <Empty description="暂无知识点；可新建或导入 Markdown" />}
        <div className="setup-actions"><Button type="primary" icon={<BookOpenCheck size={16} />} disabled={!selectedIds.length} onClick={onStartSession}>开始背知识点（{selectedIds.length}）</Button></div>
      </div>
    </section>
  );
}
