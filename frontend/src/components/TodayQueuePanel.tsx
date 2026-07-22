import { useMemo, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Button, Empty, Message, Modal, Radio, Space, Spin, Tag, Typography } from '@arco-design/web-react';
import { BookOpenText, BrainCircuit, CalendarDays, CheckCircle2, ListTodo, Play } from 'lucide-react';
import { useNavigate } from 'react-router-dom';
import { friendlyMessage } from '../lib/errors';
import { beginDayQueueSession } from '../lib/dayQueueSession';
import { completeStudy, fetchToday, type TodayQueueItem } from '../lib/study';
import {
  planKnowledgePath,
  planMemorizePath,
  planNotePath,
  planQuizPath,
  planStudyPath,
  todayYmd
} from '../lib/studyPlan';
import { LS_FORCE_ADVANCE, readBoolPref } from '../lib/sessionPrefs';

const { Text } = Typography;

const EMPTY_ITEMS: TodayQueueItem[] = [];

type SourceFilter = 'all' | 'curve' | 'plan';

/** Origin of a queue row for calendar UX. */
export type QueueOrigin = 'curve' | 'plan' | 'both';

const QUALITY_OPTIONS: Array<{ quality: number; label: string; className: string }> = [
  { quality: 0, label: '不会', className: 'quality-0' },
  { quality: 2, label: '困难', className: 'quality-2' },
  { quality: 3, label: '一般', className: 'quality-3' },
  { quality: 4, label: '熟悉', className: 'quality-4' },
  { quality: 5, label: '掌握', className: 'quality-5' }
];

function resourceTypeLabel(type: string): string {
  if (type === 'question') return '题目';
  if (type === 'knowledge_point') return '知识点';
  if (type === 'note_page') return '笔记';
  return type;
}

/** curve = SRS only; plan = calendar only; both = enrolled due + calendar todo. */
export function queueOrigin(item: TodayQueueItem): QueueOrigin {
  const fromPlan = item.kind === 'plan' || item.kind === 'plan_and_due' || item.planItemId != null;
  const fromCurve =
    item.kind === 'due' ||
    item.kind === 'plan_and_due' ||
    item.due === true ||
    item.overdue === true ||
    item.scheduleId != null ||
    item.srsStatus === 'new' ||
    item.isNew === true;
  if (fromPlan && fromCurve) return 'both';
  if (fromCurve) return 'curve';
  return 'plan';
}

function originMeta(origin: QueueOrigin, item?: TodayQueueItem): {
  label: string;
  color: string;
  rowClass: string;
  hint: string;
} {
  if (origin === 'curve') {
    if (item?.isNew || item?.srsStatus === 'new') {
      return {
        label: '新学',
        color: 'purple',
        rowClass: 'origin-curve',
        hint: '刚加入记忆曲线，今日可先学'
      };
    }
    return {
      label: '记忆曲线',
      color: 'purple',
      rowClass: 'origin-curve',
      hint: '算法到期，未钉在日历上'
    };
  }
  if (origin === 'both') {
    return {
      label: '曲线+计划',
      color: 'magenta',
      rowClass: 'origin-both',
      hint: '既已安排到日历，也到了复习日'
    };
  }
  return {
    label: '自主安排',
    color: 'arcoblue',
    rowClass: 'origin-plan',
    hint: '你或 AI 钉在日历上的任务'
  };
}

/**
 * "去学习" from the today queue:
 * - Memory-curve questions → quiz with fromQueue=1 (right/wrong drives SRS: correct=mastered signal).
 * - Pure plan questions → quiz without force curve flags.
 * - Knowledge points / notes → their study pages.
 */
function goStudyPath(item: TodayQueueItem, date: string): string {
  const origin = queueOrigin(item);
  const fromCurve = origin === 'curve' || origin === 'both';

  if (item.resourceType === 'question') {
    // Always practice by answering; curve items pass scheduleId + fromQueue for SRS write-back.
    const base = planQuizPath([item.resourceId], { planDate: date });
    const params = new URLSearchParams(base.split('?')[1] ?? '');
    if (item.planItemId != null) params.set('planItemId', String(item.planItemId));
    if (item.groupId != null) params.set('planGroupId', String(item.groupId));
    if (fromCurve) {
      params.set('fromQueue', '1');
      params.set('autoStart', '1');
      if (item.scheduleId != null) params.set('scheduleId', String(item.scheduleId));
    }
    return `/quiz?${params.toString()}`;
  }

  if (item.planItemId != null) {
    return planStudyPath(
      {
        resourceType: item.resourceType as 'question' | 'knowledge_point' | 'note_page',
        resourceId: item.resourceId,
        id: item.planItemId
      },
      item.groupId,
      date
    );
  }
  if (item.resourceType === 'knowledge_point') {
    const base = planKnowledgePath([item.resourceId], { planDate: date });
    return fromCurve ? `${base}&fromQueue=1` : base;
  }
  return planNotePath([item.resourceId], { planDate: date });
}

export type UrgencyKind = 'overdue' | 'backlog' | 'due' | null;

/**
 * Urgency relative to the day being viewed:
 * - overdue (real today past next_review): 逾期
 * - due earlier than selected future day but not past real today: 提前
 * - otherwise due for the selected day (including when browsing a future day
 *   where next_review falls on that day): 到期
 *
 * When nextReview is missing on a future-day view, prefer 到期 over 提前 so we
 * do not imply the user missed work.
 *
 * Why "提前" (not "待补"): on a future-day view this state only means the card's
 * algorithmic review date falls before the day being browsed — it is optional
 * early work, not a deficit. "待补" implied the user had fallen behind, which
 * was misleading when the day in question had not yet arrived.
 */
export function resolveUrgencyKind(
  item: TodayQueueItem,
  selectedDate: string,
  realToday: string
): UrgencyKind {
  const origin = queueOrigin(item);
  if (origin !== 'curve' && origin !== 'both') return null;

  if (item.overdue) return 'overdue';

  const isCurveDue =
    item.due === true || item.kind === 'due' || item.kind === 'plan_and_due';
  if (!isCurveDue) return null;

  // Future calendar day: only label 提前 when next_review is strictly before that day.
  if (selectedDate > realToday) {
    const nextDay =
      item.nextReview && item.nextReview.length >= 10
        ? item.nextReview.slice(0, 10)
        : item.nextReview?.trim() || null;
    if (nextDay && nextDay < selectedDate) return 'backlog';
    return 'due';
  }

  return 'due';
}

function urgencyTag(
  item: TodayQueueItem,
  selectedDate: string,
  realToday: string
): JSX.Element | null {
  const kind = resolveUrgencyKind(item, selectedDate, realToday);
  if (kind === 'overdue') {
    return (
      <Tag key="overdue" color="red" size="small" title="复习日已早于今天，请尽快补做">
        逾期
      </Tag>
    );
  }
  return null;
}

/** Tags: origin first (curve vs plan), then type, then urgency. */
function kindTags(item: TodayQueueItem, selectedDate: string, realToday: string): JSX.Element[] {
  const origin = queueOrigin(item);
  const meta = originMeta(origin, item);
  const tags: JSX.Element[] = [
    <Tag key="origin" size="small" color={meta.color} title={meta.hint}>
      {meta.label}
    </Tag>,
    <Tag key="type" size="small">
      {resourceTypeLabel(item.resourceType)}
    </Tag>
  ];
  const urgency = urgencyTag(item, selectedDate, realToday);
  if (urgency) tags.push(urgency);
  if (item.groupTitle && (origin === 'plan' || origin === 'both')) {
    tags.push(
      <Tag key="group" size="small" color="gray">
        {item.groupTitle.length > 12 ? `${item.groupTitle.slice(0, 12)}…` : item.groupTitle}
      </Tag>
    );
  }
  return tags;
}

export type TodayQueuePanelProps = {
  /** Day to load queue for (YYYY-MM-DD). */
  date: string;
  /** When true, wrap in a panel with heading; false = body-only fragment for embedding. */
  embedded?: boolean;
  /** Extra class on root. */
  className?: string;
};

/**
 * Merged day queue: plan todos + SRS due. Used inside Calendar (primary) and legacy redirects.
 */
export function TodayQueuePanel({
  date,
  embedded = true,
  className
}: TodayQueuePanelProps): JSX.Element {
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const [qualityItem, setQualityItem] = useState<TodayQueueItem | null>(null);
  const [sourceFilter, setSourceFilter] = useState<SourceFilter>('all');
  const realToday = todayYmd();
  const isToday = date === realToday;

  const query = useQuery({
    queryKey: ['study-today', date],
    queryFn: () => fetchToday(date)
  });

  const items = query.data?.items ?? EMPTY_ITEMS;
  const stats = query.data?.stats ?? {};
  const dueTruncated = Number(stats.dueTruncated ?? 0);
  const planTodo = Number(stats.planTodo ?? 0);
  const dueIncluded = Number(stats.dueIncluded ?? 0);

  const originCounts = useMemo(() => {
    let curve = 0;
    let plan = 0;
    let both = 0;
    for (const item of items) {
      const o = queueOrigin(item);
      if (o === 'curve') curve += 1;
      else if (o === 'plan') plan += 1;
      else both += 1;
    }
    return { curve, plan, both };
  }, [items]);

  const visibleItems = useMemo(() => {
    if (sourceFilter === 'all') return items;
    return items.filter((item) => {
      const o = queueOrigin(item);
      if (sourceFilter === 'curve') return o === 'curve' || o === 'both';
      return o === 'plan' || o === 'both';
    });
  }, [items, sourceFilter]);

  const completeMutation = useMutation({
    mutationFn: completeStudy,
    onSuccess: (result, variables) => {
      setQualityItem(null);
      void queryClient.invalidateQueries({ queryKey: ['study-today'] });
      void queryClient.invalidateQueries({ queryKey: ['today'] });
      void queryClient.invalidateQueries({ queryKey: ['study-plans'] });
      void queryClient.invalidateQueries({ queryKey: ['study-plans-day'] });

      if (result.skippedSrs === 'not_enrolled') {
        Message.success('已勾掉计划（未加入记忆曲线，未改复习日）');
        return;
      }
      if (result.skippedSrs === 'no_quality') {
        Message.success('已勾掉计划（无评分，未改记忆曲线）');
        return;
      }
      if (result.extraPractice) {
        Message.info('今日已正式复习过：本次记为额外练习，下次复习日未改。可在设置中开启「同日正确仍推进」。');
        return;
      }

      const next = result.srs?.nextReview;
      const nextDay = next && next.length >= 10 ? next.slice(0, 10) : null;
      // Completing while viewing a future day: if next_review still falls on/before that day,
      // the card will remain in that day's queue (可能仍显示 到期/提前).
      if (nextDay && variables.planDate && nextDay <= variables.planDate && variables.planDate > realToday) {
        Message.warning(
          `已记录，但下次复习约 ${nextDay}，仍不晚于查看日 ${variables.planDate}。请刷新后确认；若仍在，请在「今天」再完成一次。`
        );
        return;
      }
      if (
        nextDay &&
        variables.planDate &&
        variables.planDate > realToday &&
        nextDay > variables.planDate
      ) {
        Message.success(`已完成，已离开 ${variables.planDate} 的队列（下次约 ${nextDay}）`);
        return;
      }
      if (nextDay) {
        Message.success(`已完成，下次复习约 ${nextDay}`);
        return;
      }
      Message.success('已完成');
    },
    onError: (error) => {
      Message.error(friendlyMessage(error, '标记完成失败，请稍后重试'));
    }
  });

  const runComplete = (item: TodayQueueItem, quality: number): void => {
    const origin = queueOrigin(item);
    // Queue completion of curve items must advance SRS (补做/提前/逾期), not count as same-day extra.
    const forceForCurve =
      origin === 'curve' || origin === 'both' || readBoolPref(LS_FORCE_ADVANCE, false);
    completeMutation.mutate({
      resourceType: item.resourceType,
      resourceId: item.resourceId,
      quality,
      source: 'today_queue',
      // Plan checkbox uses the day being viewed; SRS same-day policy uses server LocalDate.now().
      planDate: date,
      planItemId: item.planItemId,
      scheduleId: item.scheduleId,
      forceAdvance: forceForCurve
    });
  };

  const onCompleteClick = (item: TodayQueueItem): void => {
    const origin = queueOrigin(item);
    // Curve / both always ask quality so SRS gets a real signal.
    const needsQuality =
      origin === 'curve' ||
      origin === 'both' ||
      item.due ||
      item.kind === 'due' ||
      item.kind === 'plan_and_due';
    if (needsQuality) {
      setQualityItem(item);
      return;
    }
    runComplete(item, 3);
  };

  const onGoStudy = (item: TodayQueueItem): void => {
    try {
      navigate(goStudyPath(item, date));
    } catch (error) {
      Message.warning(friendlyMessage(error, '无法开始学习'));
    }
  };

  const onStartAll = (): void => {
    const startItems = visibleItems.length ? visibleItems : items;
    if (!startItems.length) {
      Message.info(isToday ? '今天没有待办' : '该日没有待办');
      return;
    }
    try {
      const path = beginDayQueueSession(date, startItems);
      Message.success('开始今日任务：做完一段后自动进入下一段，可随时退出回日历');
      navigate(path);
    } catch (error) {
      Message.warning(friendlyMessage(error, '无法开始学习'));
    }
  };

  const headerTitle = isToday ? '今日队列' : '当日队列';
  const subtitle =
    items.length > 0
      ? `${date} · 共 ${items.length} 项（自主安排 ${planTodo} · 记忆曲线 ${dueIncluded}${originCounts.both ? ` · 重叠 ${originCounts.both}` : ''}）`
      : `${date} · 紫色=记忆曲线到期 · 蓝色=自主安排`;

  const body = (
    <>
      {items.length > 0 ? (
        <div className="queue-origin-legend">
          <span className="queue-origin-legend-item curve">
            <BrainCircuit size={13} /> 记忆曲线
            <Text type="secondary">算法到期</Text>
          </span>
          <span className="queue-origin-legend-item plan">
            <CalendarDays size={13} /> 自主安排
            <Text type="secondary">你/AI 钉日</Text>
          </span>
          <span className="queue-origin-legend-item both">
            <ListTodo size={13} /> 曲线+计划
            <Text type="secondary">两边都有</Text>
          </span>
        </div>
      ) : null}

      {items.length > 0 ? (
        <div className="queue-source-filter">
          <Radio.Group
            type="button"
            size="small"
            value={sourceFilter}
            onChange={(v) => setSourceFilter(v as SourceFilter)}
          >
            <Radio value="all">全部 {items.length}</Radio>
            <Radio value="curve">
              记忆曲线 {originCounts.curve + originCounts.both}
            </Radio>
            <Radio value="plan">
              自主安排 {originCounts.plan + originCounts.both}
            </Radio>
          </Radio.Group>
        </div>
      ) : null}

      {dueTruncated > 0 ? (
        <div className="today-stats-hint">
          记忆曲线队列已截断：另有 {dueTruncated} 项到期未列入（每日复习上限），自主安排不受影响。
        </div>
      ) : null}

      {query.isLoading ? (
        <Spin />
      ) : query.isError ? (
        <Empty description={friendlyMessage(query.error, '加载队列失败')} />
      ) : items.length === 0 ? (
        <div className="empty-state">
          <Empty
            icon={<ListTodo size={34} />}
            description={
              <div>
                <p style={{ margin: 0 }}>
                  {isToday
                    ? '今天没有自主安排或记忆曲线到期项。'
                    : '该日没有自主安排或记忆曲线到期项。'}
                </p>
                <Space style={{ marginTop: 12 }}>
                  <Button type="outline" icon={<BookOpenText size={16} />} onClick={() => navigate('/banks')}>
                    去题库
                  </Button>
                </Space>
              </div>
            }
          />
        </div>
      ) : visibleItems.length === 0 ? (
        <Empty description="当前筛选下没有条目，可切换为「全部」" />
      ) : (
        <div className="plan-group today-queue-group">
          {visibleItems.map((item) => {
            const origin = queueOrigin(item);
            const meta = originMeta(origin, item);
            const rowClass = [
              'plan-item-row',
              'queue-row',
              meta.rowClass,
              item.overdue ? 'overdue' : ''
            ]
              .filter(Boolean)
              .join(' ');
            return (
              <div key={item.id} className={rowClass} title={meta.hint}>
                <div className={`queue-origin-rail ${meta.rowClass}`} aria-hidden />
                <div className="plan-item-body">
                  <div className="plan-item-title">{item.title || `资源 #${item.resourceId}`}</div>
                  <div className="plan-item-sub">{kindTags(item, date, realToday)}</div>
                  {item.note ? (
                    <Text type="secondary" className="plan-group-note">
                      {item.note}
                    </Text>
                  ) : null}
                </div>
                <Space size={4}>
                  <Button
                    type="text"
                    size="mini"
                    icon={<Play size={14} />}
                    onClick={() => onGoStudy(item)}
                  >
                    去学习
                  </Button>
                  <Button
                    type="text"
                    status="success"
                    size="mini"
                    icon={<CheckCircle2 size={14} />}
                    loading={
                      completeMutation.isPending &&
                      completeMutation.variables?.resourceId === item.resourceId &&
                      completeMutation.variables?.resourceType === item.resourceType
                    }
                    onClick={() => onCompleteClick(item)}
                  >
                    完成
                  </Button>
                </Space>
              </div>
            );
          })}
        </div>
      )}

      <Modal
        title="复习评分"
        visible={qualityItem != null}
        onCancel={() => setQualityItem(null)}
        footer={null}
        unmountOnExit
      >
        {qualityItem ? (
          <div>
            <Text style={{ display: 'block', marginBottom: 12 }}>
              {qualityItem.title || `资源 #${qualityItem.resourceId}`}
            </Text>
            <div className="quality-buttons">
              {QUALITY_OPTIONS.map((opt) => (
                <button
                  key={opt.quality}
                  type="button"
                  className={`quality-btn ${opt.className}`}
                  disabled={completeMutation.isPending}
                  onClick={() => runComplete(qualityItem, opt.quality)}
                >
                  <span className="quality-score">{opt.quality}</span>
                  <span className="quality-label">{opt.label}</span>
                </button>
              ))}
            </div>
          </div>
        ) : null}
      </Modal>
    </>
  );

  if (!embedded) {
    return <div className={className}>{body}</div>;
  }

  return (
    <section className={`panel today-queue-panel${className ? ` ${className}` : ''}`}>
      <div className="panel-header">
        <div>
          <h2 style={{ margin: 0 }}>{headerTitle}</h2>
          <Text type="secondary" style={{ fontSize: 12 }}>
            {subtitle}
          </Text>
        </div>
        <Space size={8} wrap>
          <Tag color={items.length ? 'arcoblue' : 'gray'}>
            {sourceFilter === 'all' ? items.length : visibleItems.length}
            {sourceFilter !== 'all' ? ` / ${items.length}` : ''} 项
          </Tag>
          <Button
            type="primary"
            size="small"
            icon={<Play size={14} />}
            disabled={!visibleItems.length || query.isLoading}
            onClick={onStartAll}
          >
            开始学习
          </Button>
        </Space>
      </div>
      <div className="panel-body">{body}</div>
    </section>
  );
}
