import { useEffect, useMemo, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  Button,
  Checkbox,
  Dropdown,
  Empty,
  Form,
  Input,
  Menu,
  Message,
  Modal,
  Popconfirm,
  Select,
  Space,
  Spin,
  Tag,
  Typography
} from '@arco-design/web-react';
import {
  Calendar as CalendarIcon,
  ChevronLeft,
  ChevronRight,
  Pencil,
  Play,
  Plus,
  Trash2
} from 'lucide-react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { del, get, put } from '../lib/api';
import { friendlyMessage } from '../lib/errors';
import { completeStudy } from '../lib/study';
import { TodayQueuePanel } from '../components/TodayQueuePanel';
import {
  collectTodoKnowledgePointIds,
  collectTodoKnowledgePointIdsFromGroups,
  collectTodoNotePageIds,
  collectTodoNotePageIdsFromGroups,
  collectTodoQuestionIds,
  collectTodoQuestionIdsFromGroups,
  formatYmd,
  monthRange,
  planKnowledgePath,
  planNotePath,
  planQuizPath,
  planStudyPath,
  todayYmd
} from '../lib/studyPlan';
import type { PlanStatus, StudyPlanGroup, StudyPlanItem, StudyPlanRangeResponse } from '../lib/types';

const { Text } = Typography;

const WEEKDAYS = ['一', '二', '三', '四', '五', '六', '日'];

const YMD_RE = /^\d{4}-\d{2}-\d{2}$/;

function parseYmdParts(ymd: string): { year: number; month: number; day: number } | null {
  if (!YMD_RE.test(ymd)) return null;
  const [year, month, day] = ymd.split('-').map(Number);
  const d = new Date(year, month - 1, day);
  if (d.getFullYear() !== year || d.getMonth() !== month - 1 || d.getDate() !== day) return null;
  return { year, month: month - 1, day };
}

function sourceLabel(source: string): string {
  return source === 'session_recommend' ? '会话推荐' : '手动';
}

function sourceColor(source: string): string {
  return source === 'session_recommend' ? 'arcoblue' : 'gray';
}

function resourceTypeLabel(type: string): string {
  if (type === 'question') return '题目';
  if (type === 'knowledge_point') return '知识点';
  if (type === 'note_page') return '笔记';
  return type;
}

function todoCountForDay(groups: StudyPlanGroup[]): number {
  return groups.reduce(
    (sum, group) => sum + group.items.filter((item) => item.status === 'todo').length,
    0
  );
}

function totalCountForDay(groups: StudyPlanGroup[]): number {
  return groups.reduce((sum, group) => sum + group.items.length, 0);
}

type BatchStudyTarget = {
  questionIds: number[];
  knowledgePointIds: number[];
  notePageIds: number[];
  planGroupId?: number;
  planDate: string;
};

function BatchStudyButton({
  target,
  size = 'small',
  type = 'primary',
  label = '去学习'
}: {
  target: BatchStudyTarget;
  size?: 'mini' | 'small' | 'default' | 'large';
  type?: 'primary' | 'outline' | 'text' | 'secondary' | 'dashed';
  label?: string;
}): JSX.Element {
  const navigate = useNavigate();
  const q = target.questionIds.length;
  const k = target.knowledgePointIds.length;
  const n = target.notePageIds.length;
  const total = q + k + n;
  const disabled = total === 0;
  const kindCount = (q > 0 ? 1 : 0) + (k > 0 ? 1 : 0) + (n > 0 ? 1 : 0);

  const goQuiz = (): void => {
    try {
      navigate(
        planQuizPath(target.questionIds, {
          planGroupId: target.planGroupId,
          planDate: target.planDate
        })
      );
    } catch (error) {
      Message.warning(friendlyMessage(error, '无法开始刷题'));
    }
  };

  const goKnowledge = (): void => {
    try {
      navigate(
        planKnowledgePath(target.knowledgePointIds, {
          planGroupId: target.planGroupId,
          planDate: target.planDate
        })
      );
    } catch (error) {
      Message.warning(friendlyMessage(error, '无法开始背知识点'));
    }
  };

  const goNotes = (): void => {
    try {
      navigate(
        planNotePath(target.notePageIds, {
          planGroupId: target.planGroupId,
          planDate: target.planDate
        })
      );
      if (target.notePageIds.length > 1) {
        Message.info(`已打开第 1 页笔记，其余 ${target.notePageIds.length - 1} 页可在日历中逐条「去学习」`);
      }
    } catch (error) {
      Message.warning(friendlyMessage(error, '无法打开笔记'));
    }
  };

  const goDefault = (): void => {
    if (q > 0 && k === 0 && n === 0) {
      goQuiz();
      return;
    }
    if (k > 0 && q === 0 && n === 0) {
      goKnowledge();
      return;
    }
    if (n > 0 && q === 0 && k === 0) {
      goNotes();
      return;
    }
    if (total === 0) {
      Message.info('当前没有待学习的题目、知识点或笔记（仅含待完成且有效的条目）');
    }
  };

  const countSuffix = total > 0 ? `（${total}）` : '';

  if (kindCount >= 2) {
    const droplist = (
      <Menu
        onClickMenuItem={(key) => {
          if (key === 'quiz') goQuiz();
          if (key === 'knowledge') goKnowledge();
          if (key === 'notes') goNotes();
        }}
      >
        {q > 0 ? <Menu.Item key="quiz">刷题目（{q}）</Menu.Item> : null}
        {k > 0 ? <Menu.Item key="knowledge">背知识点（{k}）</Menu.Item> : null}
        {n > 0 ? <Menu.Item key="notes">复习笔记（{n}）</Menu.Item> : null}
      </Menu>
    );
    return (
      <Dropdown droplist={droplist} trigger="click" position="br">
        <Button type={type} size={size} icon={<Play size={14} />}>
          {label}
          {countSuffix}
        </Button>
      </Dropdown>
    );
  }

  return (
    <Button type={type} size={size} icon={<Play size={14} />} disabled={disabled} onClick={goDefault}>
      {label}
      {countSuffix}
    </Button>
  );
}

export function CalendarPage(): JSX.Element {
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const [searchParams, setSearchParams] = useSearchParams();
  const today = todayYmd();

  const initialDate = (() => {
    const fromQuery = searchParams.get('date');
    if (fromQuery && parseYmdParts(fromQuery)) return fromQuery;
    return today;
  })();
  const initialParts = parseYmdParts(initialDate) ?? parseYmdParts(today)!;

  const [viewYear, setViewYear] = useState(initialParts.year);
  const [viewMonth, setViewMonth] = useState(initialParts.month);
  const [selectedDate, setSelectedDate] = useState(initialDate);
  const [editGroup, setEditGroup] = useState<StudyPlanGroup | null>(null);
  const [editTitle, setEditTitle] = useState('');
  const [editNote, setEditNote] = useState('');
  const [editPlanDate, setEditPlanDate] = useState('');
  const [editSaving, setEditSaving] = useState(false);

  // Honor external ?date= changes (e.g. deep link after create).
  useEffect(() => {
    const fromQuery = searchParams.get('date');
    if (!fromQuery || !parseYmdParts(fromQuery) || fromQuery === selectedDate) return;
    const parts = parseYmdParts(fromQuery)!;
    setSelectedDate(fromQuery);
    setViewYear(parts.year);
    setViewMonth(parts.month);
  }, [searchParams]); // eslint-disable-line react-hooks/exhaustive-deps -- only react to URL

  const range = useMemo(() => monthRange(viewYear, viewMonth), [viewYear, viewMonth]);

  const monthQuery = useQuery({
    queryKey: ['study-plans', range.from, range.to],
    queryFn: () => get<StudyPlanRangeResponse>(`/api/study-plans?from=${range.from}&to=${range.to}`)
  });

  // SRS due + overdue counts per day, overlaid on the calendar grid.
  // Due = memory-curve items whose next_review falls on that day.
  // Overdue = next_review < real today, still not mastered — rendered as a red dot.
  type CalendarStatsRow = { due_date?: string; due_count?: number; overdue_count?: number };
  type CalendarStatsResponse = {
    from: string;
    to: string;
    realToday: string;
    due: CalendarStatsRow[];
    overdue: CalendarStatsRow[];
  };
  const srsStatsQuery = useQuery({
    queryKey: ['review-calendar-stats', range.from, range.to],
    queryFn: () => get<CalendarStatsResponse>(`/api/review/calendar-stats?from=${range.from}&to=${range.to}`),
    // The plan range query already covers the month; SRS stats are independent.
    // Stale data is fine since invalidation keys differ.
    staleTime: 0
  });

  const dayGroupsFromCache = useMemo(() => {
    const days = monthQuery.data?.days ?? [];
    const hit = days.find((d) => d.date === selectedDate);
    return hit?.groups ?? [];
  }, [monthQuery.data, selectedDate]);

  const selectedInViewMonth = useMemo(() => {
    const parts = parseYmdParts(selectedDate);
    return !!parts && parts.year === viewYear && parts.month === viewMonth;
  }, [selectedDate, viewYear, viewMonth]);

  const dayQuery = useQuery({
    queryKey: ['study-plans-day', selectedDate],
    queryFn: () => get<StudyPlanRangeResponse>(`/api/study-plans/day?date=${selectedDate}`),
    // Range query already covers the viewed month; only fetch day when selected date is outside it.
    enabled: !selectedInViewMonth
  });

  const groups: StudyPlanGroup[] = selectedInViewMonth
    ? dayGroupsFromCache
    : (dayQuery.data?.days?.[0]?.groups ?? []);

  const dayLoading = selectedInViewMonth ? monthQuery.isLoading : dayQuery.isLoading;

  const todoByDate = useMemo(() => {
    const map = new Map<string, { todos: number; total: number; srsDue: number; srsOverdue: number }>();
    for (const day of monthQuery.data?.days ?? []) {
      map.set(day.date, {
        todos: todoCountForDay(day.groups),
        total: totalCountForDay(day.groups),
        srsDue: 0,
        srsOverdue: 0
      });
    }
    // Overlay SRS due counts (memory-curve items whose next_review falls on that day).
    for (const row of srsStatsQuery.data?.due ?? []) {
      const date = row.due_date;
      if (!date) continue;
      const entry = map.get(date) ?? { todos: 0, total: 0, srsDue: 0, srsOverdue: 0 };
      entry.srsDue = row.due_count ?? 0;
      map.set(date, entry);
    }
    // Overlay overdue counts (next_review < real today, still not mastered) — red dot.
    for (const row of srsStatsQuery.data?.overdue ?? []) {
      const date = row.due_date;
      if (!date) continue;
      const entry = map.get(date) ?? { todos: 0, total: 0, srsDue: 0, srsOverdue: 0 };
      entry.srsOverdue = row.overdue_count ?? 0;
      map.set(date, entry);
    }
    return map;
  }, [monthQuery.data, srsStatsQuery.data]);

  const selectDate = (ymd: string): void => {
    setSelectedDate(ymd);
    const parts = parseYmdParts(ymd);
    if (parts) {
      setViewYear(parts.year);
      setViewMonth(parts.month);
    }
    setSearchParams(ymd === today ? {} : { date: ymd }, { replace: true });
  };

  const goToday = (): void => {
    const t = todayYmd();
    const parts = parseYmdParts(t)!;
    setViewYear(parts.year);
    setViewMonth(parts.month);
    selectDate(t);
  };

  const shiftMonth = (delta: number): void => {
    const d = new Date(viewYear, viewMonth + delta, 1);
    setViewYear(d.getFullYear());
    setViewMonth(d.getMonth());
  };

  const yearOptions = useMemo(() => {
    const nowY = new Date().getFullYear();
    // 覆盖历史计划与未来排期；并保证当前视图年一定在列表中
    const minY = Math.min(nowY - 15, viewYear - 5);
    const maxY = Math.max(nowY + 10, viewYear + 5);
    const years: number[] = [];
    for (let y = minY; y <= maxY; y++) years.push(y);
    return years;
  }, [viewYear]);

  const monthOptions = useMemo(
    () =>
      Array.from({ length: 12 }, (_, i) => ({
        value: i,
        label: `${i + 1}月`
      })),
    []
  );

  const onYearChange = (year: number): void => {
    setViewYear(year);
  };

  const onMonthSelect = (monthIndex0: number): void => {
    setViewMonth(monthIndex0);
  };

  const invalidatePlans = async (): Promise<void> => {
    await queryClient.invalidateQueries({ queryKey: ['study-plans'] });
    await queryClient.invalidateQueries({ queryKey: ['study-plans-day'] });
    await queryClient.invalidateQueries({ queryKey: ['study-today'] });
  };

  const toggleItem = useMutation({
    mutationFn: async ({
      item,
      status
    }: {
      item: StudyPlanItem;
      status: PlanStatus;
    }) => {
      if (status === 'done') {
        // Plan-only complete through fused engine (no quality → SRS skipped).
        await completeStudy({
          resourceType: item.resourceType,
          resourceId: item.resourceId,
          planItemId: item.id,
          planDate: item.planDate || selectedDate,
          source: 'calendar'
        });
        return;
      }
      await put<StudyPlanItem>(`/api/study-plans/items/${item.id}`, { status: 'todo' });
    },
    onSuccess: async () => {
      await invalidatePlans();
    },
    onError: (error) => Message.error(friendlyMessage(error, '更新计划状态失败'))
  });

  const deleteItem = useMutation({
    mutationFn: (id: number) => del<void>(`/api/study-plans/items/${id}`),
    onSuccess: async () => {
      Message.success('已删除条目');
      await invalidatePlans();
    },
    onError: (error) => Message.error(friendlyMessage(error, '删除条目失败'))
  });

  const deleteGroup = useMutation({
    mutationFn: (id: number) => del<void>(`/api/study-plans/groups/${id}`),
    onSuccess: async () => {
      Message.success('已删除计划组');
      await invalidatePlans();
    },
    onError: (error) => Message.error(friendlyMessage(error, '删除计划组失败'))
  });

  const openEdit = (group: StudyPlanGroup): void => {
    setEditGroup(group);
    setEditTitle(group.title ?? '');
    setEditNote(group.note ?? '');
    setEditPlanDate(group.planDate);
    setEditSaving(false);
  };

  const saveEdit = async (): Promise<void> => {
    if (!editGroup) return;
    if (!editPlanDate || !YMD_RE.test(editPlanDate)) {
      Message.warning('请选择有效的计划日期');
      return;
    }
    setEditSaving(true);
    try {
      await put<StudyPlanGroup>(`/api/study-plans/groups/${editGroup.id}`, {
        title: editTitle.trim(),
        note: editNote,
        planDate: editPlanDate
      });
      Message.success('计划组已更新');
      setEditGroup(null);
      await invalidatePlans();
      if (editPlanDate !== selectedDate) {
        selectDate(editPlanDate);
      }
    } catch (error) {
      Message.error(friendlyMessage(error, '更新计划组失败'));
    } finally {
      setEditSaving(false);
    }
  };

  const onAddPlan = (): void => {
    Message.warning('请从刷题、背题、知识点、错题或笔记页选择内容加入计划');
  };

  const onStudy = (item: StudyPlanItem, groupId: number): void => {
    if (item.resourceMissing) {
      Message.warning('资源已不存在');
      return;
    }
    navigate(planStudyPath(item, groupId, selectedDate));
  };

  const dayStudyTarget = useMemo<BatchStudyTarget>(
    () => ({
      questionIds: collectTodoQuestionIdsFromGroups(groups),
      knowledgePointIds: collectTodoKnowledgePointIdsFromGroups(groups),
      notePageIds: collectTodoNotePageIdsFromGroups(groups),
      planDate: selectedDate
    }),
    [groups, selectedDate]
  );

  const monthLabel = `${viewYear}年${viewMonth + 1}月`;

  const gridCells = useMemo(() => {
    const first = new Date(viewYear, viewMonth, 1);
    // Monday-first: JS getDay() Sun=0 … Sat=6 → Mon=0 … Sun=6
    const startOffset = (first.getDay() + 6) % 7;
    const daysInMonth = new Date(viewYear, viewMonth + 1, 0).getDate();
    const cells: Array<{ key: string; ymd?: string; dayNum?: number; outside: boolean }> = [];
    for (let i = 0; i < startOffset; i++) {
      cells.push({ key: `pad-start-${i}`, outside: true });
    }
    for (let day = 1; day <= daysInMonth; day++) {
      const ymd = formatYmd(new Date(viewYear, viewMonth, day));
      cells.push({ key: ymd, ymd, dayNum: day, outside: false });
    }
    while (cells.length % 7 !== 0) {
      cells.push({ key: `pad-end-${cells.length}`, outside: true });
    }
    return cells;
  }, [viewYear, viewMonth]);

  return (
    <main className="page">
      <div className="page-heading">
        <div>
          <h1>日历</h1>
          <p>月历规划学习；选中日期（默认今天）下方为合并队列：计划待办 + 到期复习。</p>
        </div>
        <Space>
          <Button onClick={goToday}>今天</Button>
          <Button type="primary" icon={<Plus size={16} />} onClick={onAddPlan}>
            添加计划
          </Button>
        </Space>
      </div>

      <div className="calendar-layout">
        <section className="panel">
          <div className="panel-header calendar-month-header">
            <h2 className="calendar-month-title">{monthLabel}</h2>
            <Space size={6} wrap>
              <Select
                size="small"
                value={viewYear}
                onChange={(value) => onYearChange(Number(value))}
                style={{ width: 96 }}
                aria-label="选择年份"
              >
                {yearOptions.map((year) => (
                  <Select.Option key={year} value={year}>
                    {year}年
                  </Select.Option>
                ))}
              </Select>
              <Select
                size="small"
                value={viewMonth}
                onChange={(value) => onMonthSelect(Number(value))}
                style={{ width: 80 }}
                aria-label="选择月份"
              >
                {monthOptions.map((m) => (
                  <Select.Option key={m.value} value={m.value}>
                    {m.label}
                  </Select.Option>
                ))}
              </Select>
              <Button
                type="text"
                icon={<ChevronLeft size={16} />}
                onClick={() => shiftMonth(-1)}
                aria-label="上一月"
              />
              <Button
                type="text"
                icon={<ChevronRight size={16} />}
                onClick={() => shiftMonth(1)}
                aria-label="下一月"
              />
            </Space>
          </div>
          <div className="panel-body">
            {monthQuery.isLoading ? (
              <Spin />
            ) : monthQuery.isError ? (
              <Empty
                description={
                  <span>
                    加载失败
                    <Button type="text" size="mini" onClick={() => void monthQuery.refetch()}>
                      重试
                    </Button>
                  </span>
                }
              />
            ) : (
              <>
                <div className="calendar-weekday-row">
                  {WEEKDAYS.map((label) => (
                    <div key={label} className="calendar-weekday">
                      {label}
                    </div>
                  ))}
                </div>
                <div className="calendar-month-grid">
                  {gridCells.map((cell) => {
                    if (cell.outside || !cell.ymd) {
                      return <div key={cell.key} className="calendar-day-cell outside" />;
                    }
                    const stats = todoByDate.get(cell.ymd);
                    const isSelected = cell.ymd === selectedDate;
                    const isToday = cell.ymd === today;
                    const hasOverdue = (stats?.srsOverdue ?? 0) > 0;
                    const className = [
                      'calendar-day-cell',
                      isSelected ? 'selected' : '',
                      isToday ? 'today' : '',
                      hasOverdue ? 'has-overdue' : ''
                    ]
                      .filter(Boolean)
                      .join(' ');
                    return (
                      <button
                        key={cell.key}
                        type="button"
                        className={className}
                        onClick={() => selectDate(cell.ymd!)}
                        aria-label={cell.ymd}
                        aria-pressed={isSelected}
                      >
                        <span className="calendar-day-num">{cell.dayNum}</span>
                        {stats && stats.total > 0 ? (
                          <span className="calendar-day-marks">
                            <span
                              className={`calendar-dot${stats.todos === 0 ? ' done' : ''}`}
                              title={
                                stats.todos > 0
                                  ? `${stats.todos} 项待完成`
                                  : `${stats.total} 项已完成`
                              }
                            />
                            {stats.todos > 0 ? (
                              <span className="calendar-todo-count">{stats.todos}</span>
                            ) : null}
                          </span>
                        ) : null}
                        {stats && stats.srsDue > 0 ? (
                          <span
                            className="calendar-srs-due"
                            title={`记忆曲线到期 ${stats.srsDue} 项`}
                          >
                            <span className="calendar-srs-due-dot" />
                            <span className="calendar-srs-due-count">{stats.srsDue}</span>
                          </span>
                        ) : null}
                        {stats && stats.srsOverdue > 0 ? (
                          <span
                            className="calendar-srs-overdue"
                            title={`逾期未复习 ${stats.srsOverdue} 项`}
                          >
                            <span className="calendar-srs-overdue-dot" />
                          </span>
                        ) : null}
                      </button>
                    );
                  })}
                </div>
              </>
            )}
          </div>
        </section>

        <div className="calendar-day-column">
          <TodayQueuePanel date={selectedDate} />

          <section className="panel">
          <div className="panel-header">
            <h2>自主安排 · {selectedDate}</h2>
            <Space size={8} wrap>
              <Tag color="arcoblue" size="small">日历计划</Tag>
              <Tag color={groups.length ? 'arcoblue' : 'gray'}>
                {groups.reduce((n, g) => n + g.totalCount, 0)} 项
              </Tag>
              {groups.length > 0 ? (
                <BatchStudyButton target={dayStudyTarget} size="small" type="primary" label="批量去学习" />
              ) : null}
            </Space>
          </div>
          <div className="panel-body">
            {dayLoading ? (
              <Spin />
            ) : groups.length === 0 ? (
              <Empty
                icon={<CalendarIcon size={34} />}
                description="这一天还没有自主安排的计划分组。记忆曲线到期项请看上方队列（紫色）；也可从刷题/知识点等加入计划。"
              />
            ) : (
              groups.map((group) => {
                const groupStudyTarget: BatchStudyTarget = {
                  questionIds: collectTodoQuestionIds(group.items),
                  knowledgePointIds: collectTodoKnowledgePointIds(group.items),
                  notePageIds: collectTodoNotePageIds(group.items),
                  planGroupId: group.id,
                  planDate: selectedDate
                };
                return (
                <div key={group.id} className="plan-group">
                  <div className="plan-group-header">
                    <div className="plan-group-meta">
                      <div className="plan-group-title-row">
                        <strong>{group.title || '未命名计划'}</strong>
                        <Tag size="small" color={sourceColor(group.source)}>
                          {sourceLabel(group.source)}
                        </Tag>
                        <Tag size="small">
                          {group.doneCount}/{group.totalCount}
                        </Tag>
                      </div>
                      {group.note ? (
                        <Text type="secondary" className="plan-group-note">
                          {group.note}
                        </Text>
                      ) : null}
                    </div>
                    <Space size={4} wrap>
                      <BatchStudyButton
                        target={groupStudyTarget}
                        size="mini"
                        type="outline"
                        label="去学习"
                      />
                      <Button
                        type="text"
                        size="mini"
                        icon={<Pencil size={14} />}
                        onClick={() => openEdit(group)}
                        aria-label="编辑计划组"
                      />
                      <Popconfirm
                        title="删除该计划组及全部条目？"
                        onOk={() => deleteGroup.mutateAsync(group.id)}
                      >
                        <Button
                          type="text"
                          status="danger"
                          size="mini"
                          icon={<Trash2 size={14} />}
                          aria-label="删除计划组"
                        />
                      </Popconfirm>
                    </Space>
                  </div>
                  {group.items.map((item) => {
                    const rowClass = [
                      'plan-item-row',
                      item.status === 'done' ? 'done' : '',
                      item.resourceMissing ? 'missing' : ''
                    ]
                      .filter(Boolean)
                      .join(' ');
                    return (
                      <div key={item.id} className={rowClass}>
                        <Checkbox
                          checked={item.status === 'done'}
                          disabled={toggleItem.isPending}
                          onChange={(checked) =>
                            toggleItem.mutate({
                              item,
                              status: checked ? 'done' : 'todo'
                            })
                          }
                        />
                        <div className="plan-item-body">
                          <div className="plan-item-title">{item.title || `#${item.resourceId}`}</div>
                          <div className="plan-item-sub">
                            <Tag size="small">{resourceTypeLabel(item.resourceType)}</Tag>
                            {item.resourceMissing ? (
                              <Tag size="small" color="orangered">
                                资源已失效
                              </Tag>
                            ) : (
                              <Tag size="small" color={item.status === 'done' ? 'green' : 'blue'}>
                                {item.status === 'done' ? '已完成' : '待完成'}
                              </Tag>
                            )}
                          </div>
                        </div>
                        <Space size={4}>
                          <Button
                            type="text"
                            size="mini"
                            disabled={!!item.resourceMissing}
                            onClick={() => onStudy(item, group.id)}
                          >
                            去学习
                          </Button>
                          <Popconfirm
                            title="删除该条目？"
                            onOk={() => deleteItem.mutateAsync(item.id)}
                          >
                            <Button
                              type="text"
                              status="danger"
                              size="mini"
                              icon={<Trash2 size={14} />}
                              aria-label="删除条目"
                            />
                          </Popconfirm>
                        </Space>
                      </div>
                    );
                  })}
                </div>
                );
              })
            )}
          </div>
        </section>
        </div>
      </div>

      <Modal
        title="编辑计划组"
        visible={!!editGroup}
        onCancel={() => setEditGroup(null)}
        onOk={() => void saveEdit()}
        okText="保存"
        confirmLoading={editSaving}
        autoFocus={false}
      >
        <Form layout="vertical">
          <Form.Item label="计划日期" required>
            <Input type="date" value={editPlanDate} onChange={setEditPlanDate} style={{ width: '100%' }} />
          </Form.Item>
          <Form.Item label="分组标题">
            <Input value={editTitle} onChange={setEditTitle} maxLength={80} placeholder="计划标题" />
          </Form.Item>
          <Form.Item label="备注">
            <Input.TextArea
              value={editNote}
              onChange={setEditNote}
              autoSize={{ minRows: 2, maxRows: 4 }}
              placeholder="可选"
            />
          </Form.Item>
        </Form>
      </Modal>
    </main>
  );
}
