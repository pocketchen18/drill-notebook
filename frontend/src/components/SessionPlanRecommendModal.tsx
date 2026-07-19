import { useEffect, useMemo, useState } from 'react';
import {
  Button,
  Checkbox,
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
  distributeItemsAcrossWindow,
  formatPlanWindowLabel,
  resolvePlanWindow,
  tomorrowYmd,
  type PlanWindow
} from '../lib/studyPlan';
import type { PlanCandidate, PlanResourceType } from '../lib/types';

const { Text, Title } = Typography;

export interface SessionPlanRecommendModalProps {
  visible: boolean;
  onClose: () => void;
  sessionType: 'quiz' | 'memorize' | 'knowledge';
  payload: {
    wrongQuestionIds?: number[];
    reviewAgainIds?: number[];
    answered?: Array<{ questionId: number; isCorrect: boolean | null }>;
    pointIds?: number[];
  };
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

interface RecommendResponse {
  title?: string;
  candidates?: PlanCandidate[];
}

interface AiScheduleResponse {
  mode: 'ai' | 'rule_fallback' | 'empty';
  startDate: string;
  endDate?: string;
  spanDays?: number;
  defaultTitle?: string;
  candidates?: PlanCandidate[];
  groups: ScheduleGroup[];
  message?: string;
  window?: PlanWindow;
}

interface ApplyScheduleResponse {
  createdGroups: number;
  createdItems: number;
  failed?: Array<{ planDate?: string; title?: string; reason?: string }>;
}

function candidateKey(item: PlanCandidate): string {
  return `${item.resourceType}:${item.resourceId}`;
}

/**
 * 会话结束：规则候选勾选 → 可选「让 AI 排计划」→ 写入日历。
 * 关闭 AI 时：用户自选起始/终止日，条目在窗口内均分（无终止则全部写到起始日）。
 */
export function SessionPlanRecommendModal({
  visible,
  onClose,
  sessionType,
  payload
}: SessionPlanRecommendModalProps): JSX.Element {
  const navigate = useNavigate();
  const [loadingCandidates, setLoadingCandidates] = useState(false);
  const [candidates, setCandidates] = useState<PlanCandidate[]>([]);
  const [selectedKeys, setSelectedKeys] = useState<string[]>([]);
  const [suggestedTitle, setSuggestedTitle] = useState('复习计划');
  const [useAiSchedule, setUseAiSchedule] = useState(false);
  const [userPrompt, setUserPrompt] = useState('');
  const [startDate, setStartDate] = useState(tomorrowYmd());
  const [endDate, setEndDate] = useState('');
  const [groupTitle, setGroupTitle] = useState('');
  const [note, setNote] = useState('');
  const [aiLoading, setAiLoading] = useState(false);
  const [applying, setApplying] = useState(false);
  const [schedule, setSchedule] = useState<AiScheduleResponse | null>(null);

  const payloadKey = JSON.stringify(payload);

  const planWindowState = useMemo(() => {
    const start = startDate || tomorrowYmd();
    try {
      const window = resolvePlanWindow(start, endDate || null);
      return { window, error: null as string | null };
    } catch (error) {
      const message = error instanceof Error ? error.message : '日期窗口无效';
      return { window: null, error: message };
    }
  }, [startDate, endDate]);

  useEffect(() => {
    if (!visible) return;
    let cancelled = false;
    setLoadingCandidates(true);
    setCandidates([]);
    setSelectedKeys([]);
    setSuggestedTitle('复习计划');
    setGroupTitle('');
    setNote('');
    setUseAiSchedule(false);
    setUserPrompt('');
    setStartDate(tomorrowYmd());
    setEndDate('');
    setSchedule(null);
    setAiLoading(false);
    setApplying(false);

    void (async () => {
      try {
        const response = await post<RecommendResponse>('/api/study-plans/recommend', {
          sessionType,
          ...payload
        });
        if (cancelled) return;
        const list = response.candidates ?? [];
        setCandidates(list);
        setSelectedKeys(list.map(candidateKey));
        const title = response.title?.trim() || '复习计划';
        setSuggestedTitle(title);
        setGroupTitle(title);
      } catch (error) {
        if (cancelled) return;
        Message.error(friendlyMessage(error, '加载推荐内容失败，请稍后重试'));
        onClose();
      } finally {
        if (!cancelled) setLoadingCandidates(false);
      }
    })();

    return () => {
      cancelled = true;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps -- payload via payloadKey
  }, [visible, sessionType, payloadKey]);

  useEffect(() => {
    if (!useAiSchedule) {
      setSchedule(null);
      setAiLoading(false);
    }
  }, [useAiSchedule]);

  const selectedCandidates = useMemo(
    () => candidates.filter((item) => selectedKeys.includes(candidateKey(item))),
    [candidates, selectedKeys]
  );

  const allSelected = candidates.length > 0 && selectedKeys.length === candidates.length;

  const onStartDateChange = (value: string): void => {
    setStartDate(value);
    setSchedule(null);
  };

  const onEndDateChange = (value: string): void => {
    setEndDate(value);
    setSchedule(null);
  };

  const generateAi = async (): Promise<void> => {
    if (!selectedCandidates.length) {
      Message.warning('请至少选择一项内容');
      return;
    }
    if (planWindowState.error) {
      Message.warning(planWindowState.error);
      return;
    }
    setAiLoading(true);
    setSchedule(null);
    try {
      const response = await post<AiScheduleResponse>('/api/study-plans/recommend/ai-schedule', {
        sessionType,
        startDate: startDate || tomorrowYmd(),
        endDate: endDate.trim() || undefined,
        defaultTitle: groupTitle.trim() || suggestedTitle,
        userPrompt: userPrompt.trim() || undefined,
        candidates: selectedCandidates.map((item) => ({
          resourceType: item.resourceType,
          resourceId: item.resourceId,
          title: item.title
        }))
      });
      setSchedule(response);
      if (response.mode === 'rule_fallback') {
        Message.warning(response.message || 'AI 不可用，已使用规则降级方案');
      } else if (response.mode === 'empty') {
        Message.info(response.message || '没有可安排的内容');
      }
    } catch (error) {
      Message.error(friendlyMessage(error, '生成 AI 方案失败，请稍后重试或关闭 AI 后手动写入'));
    } finally {
      setAiLoading(false);
    }
  };

  const buildManualGroups = (): ScheduleGroup[] => {
    const start = startDate || tomorrowYmd();
    const items = selectedCandidates.map((item) => ({
      resourceType: item.resourceType as PlanResourceType,
      resourceId: item.resourceId,
      title: item.title
    }));
    const title = groupTitle.trim() || suggestedTitle || '复习计划';
    const end = endDate.trim();
    if (!end) {
      return [{ planDate: start, title, note: note.trim() || undefined, items }];
    }
    return distributeItemsAcrossWindow(items, start, end, {
      title,
      note: note.trim() || undefined
    }).map((group) => ({
      planDate: group.planDate,
      title: group.title,
      note: group.note,
      items: group.items.map((item) => ({
        resourceType: item.resourceType as PlanResourceType,
        resourceId: item.resourceId,
        title: item.title
      }))
    }));
  };

  const applyGroups = async (groups: ScheduleGroup[]): Promise<void> => {
    if (!groups.length) {
      Message.warning('没有可写入的计划');
      return;
    }
    setApplying(true);
    try {
      const result = await post<ApplyScheduleResponse>('/api/study-plans/recommend/apply-schedule', {
        groups: groups.map((g) => ({
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
      const failedCount = result.failed?.length ?? 0;
      const firstDate = groups[0]?.planDate;
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
      onClose();
    } catch (error) {
      Message.error(friendlyMessage(error, '写入计划失败，请稍后重试'));
    } finally {
      setApplying(false);
    }
  };

  const onSubmit = (): void => {
    if (!selectedCandidates.length) {
      Message.warning('请至少选择一项内容');
      return;
    }
    if (planWindowState.error) {
      Message.warning(planWindowState.error);
      return;
    }
    if (useAiSchedule) {
      if (!schedule?.groups?.length) {
        Message.warning('请先生成 AI 方案，或关闭「让 AI 排计划」后手动写入');
        return;
      }
      void applyGroups(schedule.groups);
      return;
    }
    void applyGroups(buildManualGroups());
  };

  const windowInvalid = Boolean(planWindowState.error);
  const scheduleWindowLabel = (() => {
    if (useAiSchedule && schedule?.window) {
      try {
        return formatPlanWindowLabel(schedule.window);
      } catch {
        /* fall through */
      }
    }
    if (planWindowState.window) {
      return formatPlanWindowLabel(planWindowState.window);
    }
    return null;
  })();

  const okDisabled =
    loadingCandidates ||
    applying ||
    aiLoading ||
    !selectedCandidates.length ||
    windowInvalid ||
    (useAiSchedule && !schedule?.groups?.length);

  const manualPreview = !useAiSchedule && selectedCandidates.length && !windowInvalid
    ? buildManualGroups()
    : [];

  return (
    <Modal
      title="本轮结束后的学习计划"
      visible={visible}
      onCancel={onClose}
      onOk={onSubmit}
      okText={useAiSchedule ? '一键添加到日历' : endDate.trim() ? '按日期范围写入' : '写入计划'}
      confirmLoading={applying || aiLoading}
      okButtonProps={{ disabled: okDisabled }}
      cancelText="跳过"
      autoFocus={false}
      style={{ width: 640 }}
    >
      {loadingCandidates ? (
        <div style={{ textAlign: 'center', padding: 24 }}>
          <Spin tip="正在加载本轮可安排内容…" />
        </div>
      ) : candidates.length === 0 ? (
        <Text type="secondary">本轮没有可安排的内容，可关闭后从错题/知识点/笔记页手动加入计划。</Text>
      ) : (
        <Space direction="vertical" style={{ width: '100%' }} size="medium">
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
            <Text>
              选择要写入计划的内容（{selectedCandidates.length}/{candidates.length}）
            </Text>
            <Checkbox
              checked={allSelected}
              indeterminate={selectedKeys.length > 0 && !allSelected}
              onChange={(checked) => {
                setSelectedKeys(checked ? candidates.map(candidateKey) : []);
                setSchedule(null);
              }}
            >
              全选
            </Checkbox>
          </div>
          <Checkbox.Group
            value={selectedKeys}
            onChange={(values) => {
              setSelectedKeys(values as string[]);
              setSchedule(null);
            }}
            direction="vertical"
            style={{ width: '100%', maxHeight: 200, overflow: 'auto' }}
          >
            {candidates.map((item) => (
              <Checkbox key={candidateKey(item)} value={candidateKey(item)} style={{ marginBottom: 8 }}>
                <Text>{item.title}</Text>
                <Text type="secondary" style={{ marginLeft: 8, fontSize: 12 }}>
                  {item.resourceType === 'knowledge_point'
                    ? '知识点'
                    : item.resourceType === 'note_page'
                      ? '笔记'
                      : '题目'}
                </Text>
              </Checkbox>
            ))}
          </Checkbox.Group>

          <Form layout="vertical">
            <Form.Item label="起始日" required>
              <Input type="date" value={startDate} onChange={onStartDateChange} style={{ width: '100%' }} />
            </Form.Item>
            <Form.Item label="终止日（可选）">
              <Input type="date" value={endDate} onChange={onEndDateChange} style={{ width: '100%' }} />
            </Form.Item>
            <Form.Item>
              {planWindowState.error ? (
                <Text type="error" style={{ display: 'block' }}>
                  {planWindowState.error}
                </Text>
              ) : (
                <>
                  <Text type="secondary" style={{ display: 'block' }}>
                    {scheduleWindowLabel}
                  </Text>
                  {!useAiSchedule ? (
                    <Text type="secondary" style={{ display: 'block', marginTop: 4 }}>
                      {endDate.trim()
                        ? '未开 AI：所选内容将在窗口内按天大致均分写入。'
                        : '未开 AI 且未设终止日：全部写入起始日。'}
                    </Text>
                  ) : null}
                  {useAiSchedule && planWindowState.window && planWindowState.window.spanDays >= 21 ? (
                    <Text type="warning" style={{ display: 'block', marginTop: 4 }}>
                      当前窗口较长，生成可能较慢，请耐心等待。
                    </Text>
                  ) : null}
                </>
              )}
            </Form.Item>

            <Form.Item label="让 AI 帮忙排计划">
              <Space align="start">
                <Switch checked={useAiSchedule} onChange={setUseAiSchedule} />
                <Text type="secondary">
                  默认关闭。开启后可根据难度、错题次数、标签和你的提示词生成多日方案；关闭则由你自选日期并均分写入。
                </Text>
              </Space>
            </Form.Item>

            <Form.Item label="分组标题">
              <Input
                value={groupTitle}
                onChange={(value) => {
                  setGroupTitle(value);
                  setSchedule(null);
                }}
                placeholder={suggestedTitle}
                maxLength={80}
              />
            </Form.Item>

            {!useAiSchedule ? (
              <Form.Item label="备注">
                <Input.TextArea
                  value={note}
                  onChange={setNote}
                  placeholder="可选"
                  autoSize={{ minRows: 2, maxRows: 4 }}
                />
              </Form.Item>
            ) : (
              <>
                <Form.Item label="你的需求 / 薄弱点（提示词）">
                  <Input.TextArea
                    value={userPrompt}
                    onChange={setUserPrompt}
                    placeholder="例如：优先巩固本轮错题；公式类多安排两天；每天控制在 30 分钟内……"
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
                    loading={aiLoading}
                    disabled={!selectedCandidates.length || windowInvalid}
                    onClick={() => void generateAi()}
                  >
                    {schedule ? '重新生成 AI 方案' : '生成 AI 方案'}
                  </Button>
                </Form.Item>
              </>
            )}
          </Form>

          {useAiSchedule ? (
            aiLoading ? (
              <div style={{ textAlign: 'center', padding: 16 }}>
                <Spin tip="AI 正在根据数据与你的提示词安排计划…" />
              </div>
            ) : schedule?.groups?.length ? (
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
                      : `窗口 ${schedule.startDate} 起共 ${schedule.spanDays ?? schedule.groups.length} 天`}
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
                        <li key={`${item.resourceType}-${item.resourceId}-${item.title}`}>
                          <Text>{item.title}</Text>
                        </li>
                      ))}
                    </ul>
                  </div>
                ))}
              </Space>
            ) : (
              <Text type="secondary">开启 AI 后请先点击「生成 AI 方案」，再写入日历。</Text>
            )
          ) : manualPreview.length > 0 ? (
            <Space direction="vertical" style={{ width: '100%' }} size="small">
              <Text type="secondary">手动预览（确认后写入）</Text>
              {manualPreview.map((group, idx) => (
                <div
                  key={`${group.planDate}-${idx}`}
                  style={{
                    border: '1px solid var(--color-border-2, #e5e6eb)',
                    borderRadius: 8,
                    padding: 10
                  }}
                >
                  <div style={{ display: 'flex', justifyContent: 'space-between', gap: 8 }}>
                    <Title heading={6} style={{ margin: 0 }}>
                      {group.planDate} · {group.title}
                    </Title>
                    <Tag size="small">{group.items.length} 项</Tag>
                  </div>
                </div>
              ))}
            </Space>
          ) : null}
        </Space>
      )}
    </Modal>
  );
}
