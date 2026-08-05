import { useEffect, useMemo, useRef, useState } from 'react';
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
  onDrop: (sourceId: number, targetId: number, position: 'before' | 'after') => void;  // 拖拽插入：position 表示插到 targetId 的前面还是后面
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

const MASONRY_COLUMNS = 2;
const MASONRY_GAP = 12;

/** 把瀑布流分配结果转成"每列的卡片列表 + 每列的原始下标列表" */
function splitByColumn<T>(items: T[], columns: number[]): { colCards: T[][]; colIdx: number[][] } {
  const colCards: T[][] = Array.from({ length: MASONRY_COLUMNS }, () => []);
  const colIdx: number[][] = Array.from({ length: MASONRY_COLUMNS }, () => []);
  items.forEach((item, i) => {
    const col = columns[i] ?? i % MASONRY_COLUMNS;
    colCards[col].push(item);
    colIdx[col].push(i);
  });
  return { colCards, colIdx };
}

/**
 * 瀑布流分列：按卡片数组顺序（S 形"先左右后上下"）逐张分配到当前累积高度更矮的那一列。
 * 用 ResizeObserver 监听卡片高度变化，触发重排。
 * items 带 key 字段：key 变化（跨分组边界）时重置两列高度，保证每个分组内部独立平衡。
 * 返回每张卡应归属的列索引（0 = 左，1 = 右）。
 */
function useMasonry<T extends { key: string }>(items: T[], refs: React.MutableRefObject<Array<HTMLElement | null>>): number[] {
  const [columns, setColumns] = useState<number[]>(() => items.map((_, i) => i % MASONRY_COLUMNS));

  useEffect(() => {
    if (!items.length) return;
    const measure = (): void => {
      const colHeights = new Array<number>(MASONRY_COLUMNS).fill(0);
      const next: number[] = [];
      // 第一张放左列，后续按"当前更矮的列"分配，保留 S 形填充顺序
      for (let i = 0; i < items.length; i += 1) {
        if (i > 0 && items[i].key !== items[i - 1].key) colHeights.fill(0); // 进入新分组，两列高度清零
        const targetCol = i === 0 || colHeights[0] === 0 && colHeights[1] === 0
          ? 0
          : (colHeights[0] <= colHeights[1] ? 0 : 1);
        next.push(targetCol);
        const h = refs.current[i]?.offsetHeight ?? 0;
        colHeights[targetCol] += h + MASONRY_GAP;
      }
      setColumns((prev) => (prev.length === next.length && prev.every((c, idx) => c === next[idx]) ? prev : next));
    };
    measure();
    const ro = new ResizeObserver(() => measure());
    refs.current.forEach((el) => { if (el) ro.observe(el); });
    return () => ro.disconnect();
  }, [items, refs]);

  return columns;
}

export function KnowledgeLibraryView(props: KnowledgeLibraryViewProps) {
  const {
    banks, bankId, onBankChange, points, selectedIds, categories, tags, groupLevel,
    onCategoriesChange, onTagsChange, onGroupLevelChange,
    onToggleSelect, onDrop, onShuffle, onSelectAllFiltered, onAddToPlan,
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

  // 瀑布流分列：不分组用 flat 列表；分组模式按"组键+卡片"拍平，组内独立流动，组间互不影响
  const masonryItems = useMemo(
    () => (grouped ? grouped.flatMap(([key, items]) => items.map((point) => ({ key, point }))) : filtered.map((point) => ({ key: '', point }))),
    [grouped, filtered]
  );
  const refs = useRef<Array<HTMLElement | null>>([]);
  const columns = useMasonry(masonryItems, refs);
  const { colCards, colIdx } = splitByColumn(masonryItems, columns);

  const renderCard = (point: KnowledgePoint, groupLabel: string | undefined, groupIds: number[] | undefined, index: number): React.ReactNode => {
    const handleDrop = groupIds
      ? (sourceId: number, targetId: number, position: 'before' | 'after'): void => {
          if (groupIds.includes(sourceId) && groupIds.includes(targetId)) onDrop(sourceId, targetId, position);
        }
      : onDrop;
    return (
      <KnowledgeItemCard
        key={point.id}
        point={point}
        selected={selectedIds.includes(point.id)}
        groupLabel={groupLabel}
        onToggleSelect={(c) => onToggleSelect(point.id, c)}
        onDrop={handleDrop}
        onAddToPlan={() => onAddToPlan([point])}
        onEdit={() => onEdit(point)}
        onDelete={() => onDelete(point.id)}
        onClick={() => onClickCard(point)}
      />
    );
  };

  // 不分组：用 columns 把卡片分到两列容器，两列独立流动
  if (!grouped) {
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
              <Button size="small" onClick={onSelectAllFiltered}>全选筛选结果（{filtered.length}）</Button>
              <Button size="small" icon={<Shuffle size={14} />} onClick={onShuffle}>随机重排</Button>
            </Space>
            <Typography.Text type="secondary">已选 {selectedIds.length}</Typography.Text>
          </div>
          <div className="knowledge-masonry">
            {colCards.map((cards, col) => (
              <div key={col} className="knowledge-grid-col">
                {cards.map((item, k) => (
                  <div key={item.point.id} ref={(el) => { refs.current[colIdx[col][k]] = el; }}>
                    {renderCard(item.point, undefined, undefined, colIdx[col][k])}
                  </div>
                ))}
              </div>
            ))}
          </div>
          {!filtered.length && <Empty description="暂无知识点；可新建或导入 Markdown" />}
          <div className="setup-actions"><Button type="primary" icon={<BookOpenCheck size={16} />} disabled={!selectedIds.length} onClick={onStartSession}>开始背知识点（{selectedIds.length}）</Button></div>
        </div>
      </section>
    );
  }

  // 分组模式：每个分组内部同样做瀑布流
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
            <Button size="small" onClick={onSelectAllFiltered}>全选筛选结果（{filtered.length}）</Button>
            <Button size="small" icon={<Shuffle size={14} />} onClick={onShuffle}>随机重排</Button>
          </Space>
          <Typography.Text type="secondary">已选 {selectedIds.length}</Typography.Text>
        </div>
        {grouped.map(([groupKey]) => {
          const groupItems = masonryItems.filter((item) => item.key === groupKey);
          const groupIds = groupItems.map((item) => item.point.id);
          return (
            <div key={groupKey} className="knowledge-group">
              <h3 className="knowledge-group-title">{groupKey}</h3>
              <div className="knowledge-masonry">
                {colCards.map((cards, col) => {
                  const inGroup = cards
                    .map((item, k) => ({ item, flatIdx: colIdx[col][k] }))
                    .filter(({ flatIdx }) => masonryItems[flatIdx]?.key === groupKey);
                  if (!inGroup.length) return null;
                  return (
                    <div key={col} className="knowledge-grid-col">
                      {inGroup.map(({ item, flatIdx }) => (
                        <div key={item.point.id} ref={(el) => { refs.current[flatIdx] = el; }}>
                          {renderCard(item.point, groupKey, groupIds, flatIdx)}
                        </div>
                      ))}
                    </div>
                  );
                })}
              </div>
            </div>
          );
        })}
        {!filtered.length && <Empty description="暂无知识点；可新建或导入 Markdown" />}
        <div className="setup-actions"><Button type="primary" icon={<BookOpenCheck size={16} />} disabled={!selectedIds.length} onClick={onStartSession}>开始背知识点（{selectedIds.length}）</Button></div>
      </div>
    </section>
  );
}
