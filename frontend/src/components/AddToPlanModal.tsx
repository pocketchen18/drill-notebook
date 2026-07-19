import { useEffect, useMemo, useState } from 'react';
import {
  Button,
  Form,
  Input,
  Message,
  Modal,
  Space,
  Spin,
  Switch,
  Tag,
  Typography
} from '@arco-design/web-react';
import { useNavigate } from 'react-router-dom';
import { post } from '../lib/api';
import { friendlyMessage } from '../lib/errors';
import {
  formatPlanWindowLabel,
  distributeItemsAcrossWindow,
  resolvePlanWindow,
  todayYmd,
  tomorrowYmd,
  type PlanWindow
} from '../lib/studyPlan';
import type { PlanResourceType, PlanSource, StudyPlanGroup, StudyPlanItem } from '../lib/types';

const { Text, Title } = Typography;

export interface AddToPlanItem {
  resourceId: number;
  title: string;
  /** Defaults to modal-level resourceType when omitted. */
  resourceType?: PlanResourceType;
}

export interface AddToPlanModalProps {
  visible: boolean;
  onClose: () => void;
  /** Fallback type when an item does not specify resourceType. */
  resourceType?: PlanResourceType;
  items: AddToPlanItem[];
  defaultDate?: string;
  defaultTitle?: string;
  defaultNote?: string;
  source?: PlanSource;
  sessionType?: 'quiz' | 'memorize' | 'knowledge' | string;
  /** @deprecated 已改为「让 AI 排计划」；保留兼容旧调用。 */
  enableAiNote?: boolean;
  onCreated?: (planDate: string) => void;
}

interface CreateGroupResponse {
  group: StudyPlanGroup;
  items: StudyPlanItem[];
  skipped?: Array<{ resourceType: string; resourceId: number; reason?: string }>;
  failed?: Array<{ resourceType: string; resourceId: number; reason?: string }>;
}

interface ScheduleGroup {
  planDate: string;
  title: string;
  note?: string;
  items: Array<{
    resourceType: PlanResourceType;
    resourceId: number;
    title: string;
  }>;
}

interface AiScheduleResponse {
  mode: 'ai' | 'rule_fallback' | 'empty';
  startDate: string;
  endDate?: string;
  spanDays?: number;
  defaultTitle?: string;
  groups: ScheduleGroup[];
  message?: string;
  window?: PlanWindow;
}

interface ApplyScheduleResponse {
  createdGroups: number;
  createdItems: number;
  failed?: Array<{ planDate?: string; title?: string; reason?: string }>;
}

/**
 * 「加入计划」：默认手动写入一天；可选开启「让 AI 排计划」——
 * 先填用户提示词，再结合题难度/错题次数/标签等上下文生成多日方案。
 */
export function AddToPlanModal({
  visible,
  onClose,
  resourceType,
  items,
  defaultDate,
  defaultTitle,
  defaultNote = '',
  source = 'manual',
  sessionType,
  onCreated
}: AddToPlanModalProps): JSX.Element {
  const navigate = useNavigate();
  const [planDate, setPlanDate] = useState(defaultDate ?? todayYmd());
  const [endDate, setEndDate] = useState('');
  const [title, setTitle] = useState(defaultTitle ?? '');
  const [note, setNote] = useState(defaultNote);
  const [userPrompt, setUserPrompt] = useState('');
  const [useAiSchedule, setUseAiSchedule] = useState(false);
  const [saving, setSaving] = useState(false);
  const [scheduleLoading, setScheduleLoading] = useState(false);
  const [schedule, setSchedule] = useState<AiScheduleResponse | null>(null);

  const resolvedItems = useMemo(
    () =>
      items
        .map((item) => ({
          resourceType: item.resourceType ?? resourceType,
          resourceId: item.resourceId,
          title: item.title
        }))
        .filter(
          (item): item is { resourceType: PlanResourceType; resourceId: number; title: string } =>
            Boolean(item.resourceType)
        ),
    [items, resourceType]
  );

  const planWindowState = useMemo(() => {
    const start = planDate || tomorrowYmd();
    try {
      const window = resolvePlanWindow(start, endDate || null);
      return { window, error: null as string | null };
    } catch (error) {
      const message = error instanceof Error ? error.message : '日期窗口无效';
      return { window: null, error: message };
    }
  }, [planDate, endDate]);

  useEffect(() => {
    if (!visible) return;
    setPlanDate(defaultDate ?? todayYmd());
    setEndDate('');
    setTitle(defaultTitle ?? '');
    setNote(defaultNote);
    setUserPrompt('');
    setUseAiSchedule(false);
    setSaving(false);
    setScheduleLoading(false);
    setSchedule(null);
  }, [visible, defaultDate, defaultTitle, defaultNote]);

  // 关闭 AI 时清空方案
  useEffect(() => {
    if (!useAiSchedule) {
      setSchedule(null);
      setScheduleLoading(false);
    }
  }, [useAiSchedule]);

  const onPlanDateChange = (value: string): void => {
    setPlanDate(value);
    if (useAiSchedule) setSchedule(null);
  };

  const onEndDateChange = (value: string): void => {
    setEndDate(value);
    setSchedule(null);
  };

  const generateAiSchedule = async (): Promise<void> => {
    if (!resolvedItems.length) {
      Message.warning('没有可安排的条目');
      return;
    }
    if (planWindowState.error) {
      Message.warning(planWindowState.error);
      return;
    }
    setScheduleLoading(true);
    setSchedule(null);
    try {
      const response = await post<AiScheduleResponse>('/api/study-plans/recommend/ai-schedule', {
        sessionType: sessionType ?? '',
        startDate: planDate || tomorrowYmd(),
        endDate: endDate.trim() || undefined,
        defaultTitle: title.trim() || defaultTitle || '复习计划',
        userPrompt: userPrompt.trim() || undefined,
        candidates: resolvedItems.map((item) => ({
          resourceType: item.resourceType,
          resourceId: item.resourceId,
          title: item.title
        }))
      });
      setSchedule(response);
      if (response.mode === 'rule_fallback') {
        Message.warning(response.message || 'AI 不可用，已使用规则降级方案');
      }
    } catch (error) {
      Message.error(friendlyMessage(error, 'AI 排期失败，请稍后重试或关闭开关后手动写入'));
      setSchedule(null);
    } finally {
      setScheduleLoading(false);
    }
  };

  const submitManual = async (): Promise<void> => {
    if (!planDate) {
      Message.warning('请选择起始日期');
      return;
    }
    if (!resolvedItems.length) {
      Message.warning('没有可添加的条目');
      return;
    }
    if (planWindowState.error) {
      Message.warning(planWindowState.error);
      return;
    }

    const items = resolvedItems.map((item) => ({
      resourceType: item.resourceType,
      resourceId: item.resourceId,
      title: item.title
    }));
    const end = endDate.trim();
    const baseTitle = title.trim() || '手动添加';
    const baseNote = note.trim() || undefined;

    setSaving(true);
    try {
      // 未填终止日：全部写到起始日一天
      // 填了终止日：在窗口内按天均分条目（用户自己排这段时间）
      const groups = end
        ? distributeItemsAcrossWindow(items, planDate, end, { title: baseTitle, note: baseNote })
        : [
            {
              planDate,
              title: baseTitle,
              note: baseNote,
              items
            }
          ];

      if (groups.length === 1) {
        const response = await post<CreateGroupResponse>('/api/study-plans/groups', {
          planDate: groups[0].planDate,
          title: groups[0].title,
          note: groups[0].note,
          source,
          items: groups[0].items
        });
        const skippedCount = response.skipped?.length ?? 0;
        const createdCount = response.items?.length ?? 0;
        if (skippedCount > 0) {
          Message.success(`已添加 ${createdCount} 项到学习计划（另有 ${skippedCount} 项未写入）`);
        } else {
          Message.success(`已添加 ${createdCount} 项到学习计划`);
        }
      } else {
        const result = await post<ApplyScheduleResponse>('/api/study-plans/recommend/apply-schedule', {
          groups: groups.map((g) => ({
            planDate: g.planDate,
            title: g.title,
            note: g.note,
            items: g.items
          }))
        });
        const failedCount = result.failed?.length ?? 0;
        Message.success({
          content: (
            <span>
              已按 {groups.length} 天写入 {result.createdGroups} 组 / {result.createdItems} 项
              {failedCount ? `（${failedCount} 组失败）` : ''}
              <Button
                type="text"
                size="mini"
                style={{ marginLeft: 8 }}
                onClick={() => navigate(`/calendar?date=${planDate}`)}
              >
                查看日历
              </Button>
            </span>
          ),
          duration: 6000
        });
      }
      onCreated?.(planDate);
      onClose();
    } catch (error) {
      Message.error(friendlyMessage(error, '添加到学习计划失败，请稍后重试'));
    } finally {
      setSaving(false);
    }
  };

  const submitAiSchedule = async (): Promise<void> => {
    if (!schedule?.groups?.length) {
      Message.warning('请先生成 AI 方案');
      return;
    }
    setSaving(true);
    try {
      const result = await post<ApplyScheduleResponse>('/api/study-plans/recommend/apply-schedule', {
        groups: schedule.groups.map((g) => ({
          planDate: g.planDate,
          title: g.title,
          note: g.note,
          items: g.items.map((it) => ({
            resourceType: it.resourceType,
            resourceId: it.resourceId,
            title: it.title
          }))
        }))
      });
      const firstDate = schedule.groups[0]?.planDate;
      const failedCount = result.failed?.length ?? 0;
      Message.success({
        content: (
          <span>
            已写入 {result.createdGroups} 组 / {result.createdItems} 项
            {failedCount ? `（${failedCount} 组失败）` : ''}
            {firstDate ? (
              <Button
                type="text"
                size="mini"
                style={{ marginLeft: 8 }}
                onClick={() => navigate(`/calendar?date=${firstDate}`)}
              >
                查看日历
              </Button>
            ) : null}
          </span>
        ),
        duration: 6000
      });
      onCreated?.(firstDate ?? planDate);
      onClose();
    } catch (error) {
      Message.error(friendlyMessage(error, '写入 AI 计划失败，请稍后重试'));
    } finally {
      setSaving(false);
    }
  };

  const onSubmit = (): void => {
    if (useAiSchedule) {
      void submitAiSchedule();
    } else {
      void submitManual();
    }
  };

  const windowInvalid = Boolean(planWindowState.error);
  const okDisabled =
    !resolvedItems.length ||
    !planDate ||
    windowInvalid ||
    (useAiSchedule ? scheduleLoading || !schedule?.groups?.length : false);

  const scheduleWindowLabel = (() => {
    if (schedule?.window) {
      try {
        return formatPlanWindowLabel(schedule.window);
      } catch {
        // fall through to local
      }
    }
    if (planWindowState.window) {
      return formatPlanWindowLabel(planWindowState.window);
    }
    return null;
  })();

  return (
    <Modal
      title="加入计划"
      visible={visible}
      onCancel={onClose}
      onOk={onSubmit}
      okText={useAiSchedule ? '一键添加到日历' : endDate.trim() ? '按日期范围写入' : '写入计划'}
      confirmLoading={saving || scheduleLoading}
      autoFocus={false}
      okButtonProps={{ disabled: okDisabled }}
      style={{ width: 640 }}
    >
      <Form layout="vertical">
        <Form.Item label={`条目（${resolvedItems.length}）`}>
          {resolvedItems.length === 0 ? (
            <Text type="secondary">暂无条目</Text>
          ) : (
            <ul style={{ margin: 0, paddingLeft: 18, maxHeight: 140, overflow: 'auto' }}>
              {resolvedItems.map((item) => (
                <li key={`${item.resourceType}-${item.resourceId}`}>
                  <Text>{item.title || `#${item.resourceId}`}</Text>
                </li>
              ))}
            </ul>
          )}
        </Form.Item>

        <Form.Item label="起始日" required>
          <Input type="date" value={planDate} onChange={onPlanDateChange} style={{ width: '100%' }} />
        </Form.Item>
        <Form.Item label="终止日（可选）">
          <Input type="date" value={endDate} onChange={onEndDateChange} style={{ width: '100%' }} />
        </Form.Item>
        <Form.Item>
          {planWindowState.error ? (
            <Text type="error" style={{ display: 'block' }}>
              {planWindowState.error}
            </Text>
          ) : endDate.trim() ? (
            <>
              <Text type="secondary" style={{ display: 'block' }}>
                {scheduleWindowLabel}
              </Text>
              {!useAiSchedule ? (
                <Text type="secondary" style={{ display: 'block', marginTop: 4 }}>
                  未开 AI：条目将在该窗口内按天大致均分写入。
                </Text>
              ) : null}
            </>
          ) : (
            <Text type="secondary" style={{ display: 'block' }}>
              {useAiSchedule
                ? scheduleWindowLabel
                : '未设终止日：全部条目写入起始日。填写终止日可把内容自行排进这段时间。'}
            </Text>
          )}
          {useAiSchedule && planWindowState.window && planWindowState.window.spanDays >= 21 ? (
            <Text type="warning" style={{ display: 'block', marginTop: 4 }}>
              当前窗口较长，生成可能较慢，请耐心等待。
            </Text>
          ) : null}
        </Form.Item>

        <Form.Item label="让 AI 排计划">
          <Space align="start">
            <Switch checked={useAiSchedule} onChange={setUseAiSchedule} />
            <Text type="secondary">
              开启后由 AI 结合难度/错题/标签与提示词排期；关闭则按上方日期由你安排（有终止日则均分到各天）。
            </Text>
          </Space>
        </Form.Item>

        {!useAiSchedule ? (
          <>
            <Form.Item label="分组标题">
              <Input
                value={title}
                onChange={setTitle}
                placeholder="可选，默认为「手动添加」"
                maxLength={80}
              />
            </Form.Item>
            <Form.Item label="备注">
              <Input.TextArea
                value={note}
                onChange={setNote}
                placeholder="可选"
                autoSize={{ minRows: 2, maxRows: 4 }}
              />
            </Form.Item>
          </>
        ) : (
          <>
            <Form.Item label="你的需求 / 薄弱点（提示词）">
              <Input.TextArea
                value={userPrompt}
                onChange={setUserPrompt}
                placeholder="例如：近两天重点补第三章公式，选择题少安排，每天别超过 5 题……"
                autoSize={{ minRows: 3, maxRows: 6 }}
                maxLength={1000}
                showWordLimit
              />
              <Text type="secondary" style={{ display: 'block', marginTop: 6 }}>
                将与难度、错题次数、标签等一并发送给 AI；不可突破上方日期窗口。
              </Text>
            </Form.Item>
            <Form.Item>
              <Button
                type="primary"
                long
                loading={scheduleLoading}
                disabled={!resolvedItems.length || windowInvalid}
                onClick={() => void generateAiSchedule()}
              >
                {schedule ? '重新生成方案' : '生成 AI 方案'}
              </Button>
            </Form.Item>
            <Form.Item label="方案预览">
              {scheduleLoading ? (
                <div style={{ textAlign: 'center', padding: 16 }}>
                  <Spin tip="AI 正在根据数据与你的提示词安排计划…" />
                </div>
              ) : !schedule ? (
                <Text type="secondary">填写提示词（可选）后点击「生成 AI 方案」。</Text>
              ) : schedule.mode === 'empty' || !schedule.groups?.length ? (
                <Text type="secondary">{schedule.message || '暂无方案'}</Text>
              ) : (
                <Space direction="vertical" style={{ width: '100%' }} size="small">
                  <div style={{ display: 'flex', gap: 8, alignItems: 'center', flexWrap: 'wrap' }}>
                    {schedule.mode === 'ai' ? (
                      <Tag color="arcoblue">AI 安排</Tag>
                    ) : (
                      <Tag color="orangered">规则降级</Tag>
                    )}
                    <Text type="secondary">
                      {schedule.mode === 'rule_fallback' && schedule.message
                        ? schedule.message
                        : `从 ${schedule.startDate} 起共 ${schedule.spanDays ?? schedule.groups.length} 天窗口`}
                    </Text>
                  </div>
                  {schedule.groups.map((group, idx) => (
                    <div
                      key={`${group.planDate}-${idx}`}
                      style={{
                        border: '1px solid var(--color-border-2, #e5e6eb)',
                        borderRadius: 8,
                        padding: 10
                      }}
                    >
                      <div
                        style={{
                          display: 'flex',
                          justifyContent: 'space-between',
                          gap: 8,
                          marginBottom: 4
                        }}
                      >
                        <Title heading={6} style={{ margin: 0 }}>
                          {group.planDate} · {group.title}
                        </Title>
                        <Tag size="small">{group.items.length} 项</Tag>
                      </div>
                      {group.note ? (
                        <Text type="secondary" style={{ display: 'block', marginBottom: 6 }}>
                          {group.note}
                        </Text>
                      ) : null}
                      <ul style={{ margin: 0, paddingLeft: 18 }}>
                        {group.items.map((item) => (
                          <li key={`${item.resourceType}-${item.resourceId}`}>
                            <Text>{item.title}</Text>
                          </li>
                        ))}
                      </ul>
                    </div>
                  ))}
                </Space>
              )}
            </Form.Item>
          </>
        )}
      </Form>
    </Modal>
  );
}
