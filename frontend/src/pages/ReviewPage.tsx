import { useEffect, useMemo, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Button, Empty, Message, Select, Space, Tag, Typography, Progress, Card, Statistic, Divider } from '@arco-design/web-react';
import { BarChart3, BrainCircuit, CheckCircle2, ChevronLeft, ChevronRight, Eye, Layers3, Target } from 'lucide-react';
import { get, post } from '../lib/api';
import type { Bank, Question } from '../lib/types';
import {
  listConfigs, getDueItems, enrollItems, getReviewStats,
  QUALITY_LABELS, dueStatusColor,
} from '../lib/review';
import type { ReviewStats } from '../lib/review';

function QuestionCard({ question, revealed, setRevealed, onRate }: {
  question: Question; revealed: boolean; setRevealed: (v: boolean) => void;
  onRate: (quality: number) => void;
}) {
  const isChoice = question.type === 'single' || question.type === 'multiple';

  return (
    <div className="quiz-card memory-card">
      <div className="quiz-progress">
        <Tag color={question.type === 'single' ? 'blue' : question.type === 'multiple' ? 'purple' : question.type === 'fill' ? 'green' : question.type === 'true_false' ? 'orange' : 'red'}>
          {question.type === 'single' ? '单选' : question.type === 'multiple' ? '多选' : question.type === 'fill' ? '填空' : question.type === 'true_false' ? '判断' : '解答'}
        </Tag>
        {question.chapter && <Tag>{question.chapter}</Tag>}
      </div>
      <h2 className="knowledge-study-title" dangerouslySetInnerHTML={{ __html: question.stem }} />
      {revealed ? (
        <div className="knowledge-study-content">
          {isChoice && question.options && question.options.length > 0 && (
            <ul className="option-list">
              {question.options.map((opt) => (
                <li key={opt.key} className={`option-item ${opt.key === question.answer ? 'correct' : ''}`}>
                  <span className="option-key">{opt.key}</span>
                  <span className="option-text" dangerouslySetInnerHTML={{ __html: opt.text }} />
                  {opt.key === question.answer && <CheckCircle2 size={16} className="option-check" />}
                </li>
              ))}
            </ul>
          )}
          {!isChoice && question.answer && (
            <div className="answer-reveal">
              <strong>正确答案：</strong>
              <span dangerouslySetInnerHTML={{ __html: question.answer }} />
            </div>
          )}
          {question.analysis && (
            <div className="analysis-block">
              <strong>解析：</strong>
              <div dangerouslySetInnerHTML={{ __html: question.analysis }} />
            </div>
          )}
          <Divider />
          <div className="quality-rating">
            <Typography.Text style={{ marginBottom: 8, display: 'block' }}>你对这道题的掌握程度如何？</Typography.Text>
            <div className="quality-buttons">
              {[0, 2, 3, 4, 5].map((q) => (
                <button key={q} className={`quality-btn quality-${q}`} onClick={() => onRate(q)}>
                  <span className="quality-label">{QUALITY_LABELS[q]}</span>
                  <span className="quality-score">{q}</span>
                </button>
              ))}
            </div>
          </div>
        </div>
      ) : (
        <div className="memory-cover">
          <Eye size={22} />
          <span>先回忆答案和解析，再揭示内容</span>
        </div>
      )}
      {!revealed && (
        <div className="quiz-actions">
          <Button type="primary" icon={<Eye size={16} />} onClick={() => setRevealed(true)}>
            揭示答案
          </Button>
        </div>
      )}
    </div>
  );
}

function KnowledgePointCard({ pointTitle, pointContent, revealed, setRevealed, onRate }: {
  pointTitle: string; pointContent?: string; revealed: boolean; setRevealed: (v: boolean) => void;
  onRate: (quality: number) => void;
}) {
  return (
    <div className="quiz-card memory-card">
      <div className="quiz-progress">
        <Tag color="arcoblue">知识点</Tag>
      </div>
      <h2 className="knowledge-study-title">{pointTitle}</h2>
      {revealed ? (
        <div className="knowledge-study-content">
          {pointContent && <div dangerouslySetInnerHTML={{ __html: pointContent }} />}
          <Divider />
          <div className="quality-rating">
            <Typography.Text style={{ marginBottom: 8, display: 'block' }}>你对这个知识点的掌握程度如何？</Typography.Text>
            <div className="quality-buttons">
              {[0, 2, 3, 4, 5].map((q) => (
                <button key={q} className={`quality-btn quality-${q}`} onClick={() => onRate(q)}>
                  <span className="quality-label">{QUALITY_LABELS[q]}</span>
                  <span className="quality-score">{q}</span>
                </button>
              ))}
            </div>
          </div>
        </div>
      ) : (
        <div className="memory-cover">
          <Eye size={22} />
          <span>先回忆定义、原理和例子，再揭示知识点</span>
        </div>
      )}
      {!revealed && (
        <div className="quiz-actions">
          <Button type="primary" icon={<Eye size={16} />} onClick={() => setRevealed(true)}>
            揭示内容
          </Button>
        </div>
      )}
    </div>
  );
}

export function ReviewPage(): JSX.Element {
  const [activeMode, setActiveMode] = useState<'due' | 'enroll' | 'stats'>('due');

  return (
    <main className="page">
      <div className="page-heading">
        <div>
          <h1>记忆曲线复习</h1>
          <p>基于 SM-2 间隔重复算法，科学安排复习节奏，对抗遗忘曲线。</p>
        </div>
        <Space>
          <Button type="outline" onClick={() => window.location.hash = '#/review/config'} size="small">配置方案</Button>
          <Button type={activeMode === 'due' ? 'primary' : 'default'} icon={<Target size={16} />} onClick={() => setActiveMode('due')}>今日复习</Button>
          <Button type={activeMode === 'enroll' ? 'primary' : 'default'} icon={<Layers3 size={16} />} onClick={() => setActiveMode('enroll')}>加入计划</Button>
          <Button type={activeMode === 'stats' ? 'primary' : 'default'} icon={<BarChart3 size={16} />} onClick={() => setActiveMode('stats')}>复习统计</Button>
        </Space>
      </div>

      {activeMode === 'due' && <ReviewDueView />}
      {activeMode === 'enroll' && <ReviewEnrollView />}
      {activeMode === 'stats' && <ReviewStatsView />}
    </main>
  );
}

function ReviewDueView(): JSX.Element {
  const configsQuery = useQuery({ queryKey: ['review-configs'], queryFn: () => listConfigs() });
  const [configId, setConfigId] = useState<number | undefined>();
  const config = configsQuery.data?.find((c) => c.id === configId) ?? configsQuery.data?.[0];

  const dueQuery = useQuery({
    queryKey: ['review-due', configId],
    queryFn: () => getDueItems({ configId }),
    enabled: !!config,
  });

  const items = useMemo(() => dueQuery.data ?? [], [dueQuery.data]);

  const [index, setIndex] = useState(0);
  const [revealed, setRevealed] = useState(false);
  const [completedCount, setCompletedCount] = useState(0);
  const [answeredSet, setAnsweredSet] = useState<Set<number>>(new Set());

  useEffect(() => { setIndex(0); setRevealed(false); }, [items]);

  if (dueQuery.isLoading) return <div className="panel"><div className="panel-body"><Empty description="加载中..." /></div></div>;
  if (items.length === 0) return <div className="panel"><div className="panel-body"><Empty description="今日没有待复习的内容" /></div></div>;

  const current = items[index];
  if (!current) return <div className="panel"><div className="panel-body"><Empty description="复习完成！" /></div></div>;

  const handleRate = async (quality: number): Promise<void> => {
    try {
      const source = current.itemType === 'question' ? 'review_question' : 'review_knowledge_point';
      await post('/api/review/submit', { scheduleId: current.id, quality, source });
      const newAnswered = new Set(answeredSet);
      newAnswered.add(current.id);
      setAnsweredSet(newAnswered);
      setCompletedCount((c) => c + 1);
      if (index < items.length - 1) {
        setIndex(index + 1);
        setRevealed(false);
      }
    } catch (e) {
      Message.error(e instanceof Error ? e.message : '提交失败');
    }
  };

  const progress = items.length > 0 ? completedCount / items.length : 0;

  return (
    <div className="study-session-layout">
      <aside className="panel question-palette">
        <div className="panel-header">
          <h2>复习列表</h2>
          <Select value={configId} onChange={(v) => setConfigId(v)} size="small" style={{ width: 140 }}>
            {configsQuery.data?.map((c) => <Select.Option key={c.id} value={c.id}>{c.name}</Select.Option>)}
          </Select>
        </div>
        <div className="panel-body">
          <Progress percent={progress * 100} style={{ marginBottom: 12 }} />
          <Typography.Text type="secondary" style={{ marginBottom: 12, display: 'block', fontSize: 13 }}>
            已完成 {completedCount}/{items.length}
          </Typography.Text>
          <div className="palette-grid">
            {items.map((item, i) => {
              const isAnswered = answeredSet.has(item.id);
              const color = isAnswered ? 'green' : dueStatusColor(item);
              return (
                <button
                  type="button"
                  className={`palette-item ${i === index ? 'current' : ''} palette-${color}`}
                  key={item.id}
                  onClick={() => { setIndex(i); setRevealed(false); }}
                >
                  {i + 1}
                </button>
              );
            })}
          </div>
        </div>
      </aside>

      <section>
        {current.itemType === 'question' && current.question ? (
          <QuestionCard question={current.question} revealed={revealed} setRevealed={setRevealed} onRate={handleRate} />
        ) : current.itemType === 'knowledge_point' ? (
          <KnowledgePointCard
            pointTitle={`知识点 #${current.itemId}`}
            revealed={revealed} setRevealed={setRevealed} onRate={handleRate}
          />
        ) : (
          <div className="quiz-card memory-card">
            <Empty description="内容加载失败" />
          </div>
        )}

        <div className="quiz-actions" style={{ marginTop: 12 }}>
          <Button icon={<ChevronLeft size={16} />} disabled={index === 0} onClick={() => { setIndex(index - 1); setRevealed(false); }}>
            上一个
          </Button>
          <Button icon={<ChevronRight size={16} />} disabled={index === items.length - 1} onClick={() => { setIndex(index + 1); setRevealed(false); }}>
            下一个
          </Button>
        </div>
      </section>
    </div>
  );
}

function ReviewEnrollView(): JSX.Element {
  const banksQuery = useQuery({ queryKey: ['banks'], queryFn: () => get<Bank[]>('/api/banks') });
  const configsQuery = useQuery({ queryKey: ['review-configs'], queryFn: () => listConfigs() });

  const [bankId, setBankId] = useState<number>();
  const [configId, setConfigId] = useState<number>();

  useEffect(() => {
    if (banksQuery.data?.length && !bankId) setBankId(banksQuery.data[0].id);
  }, [banksQuery.data, bankId]);

  useEffect(() => {
    const defaultConfig = configsQuery.data?.find((c) => c.isDefault);
    if (defaultConfig && !configId) setConfigId(defaultConfig.id);
  }, [configsQuery.data, configId]);

  const questionsQuery = useQuery({
    queryKey: ['bank-questions', bankId],
    queryFn: () => get<Question[]>(`/api/banks/${bankId}/questions`),
    enabled: !!bankId,
  });

  const [selectedQuestionIds, setSelectedQuestionIds] = useState<number[]>([]);
  const [enrolling, setEnrolling] = useState(false);

  const questions = questionsQuery.data ?? [];

  const handleEnroll = async (): Promise<void> => {
    if (!selectedQuestionIds.length) { Message.warning('请选择题目'); return; }
    setEnrolling(true);
    try {
      const result = await enrollItems('question', selectedQuestionIds, configId);
      Message.success(`已加入 ${result.filter((r) => r.status === 'enrolled').length} 道题目`);
    } catch (e) {
      Message.error(e instanceof Error ? e.message : '加入失败');
    } finally {
      setEnrolling(false);
    }
  };

  return (
    <section className="panel">
      <div className="panel-header">
        <h2>选择题目加入复习计划</h2>
        <Space>
          <Select value={bankId} onChange={(v) => setBankId(v)} placeholder="题库" style={{ width: 200 }}>
            {banksQuery.data?.map((b) => <Select.Option key={b.id} value={b.id}>{b.name}</Select.Option>)}
          </Select>
          <Select value={configId} onChange={(v) => setConfigId(v)} placeholder="复习方案" style={{ width: 160 }}>
            {configsQuery.data?.map((c) => <Select.Option key={c.id} value={c.id}>{c.name}</Select.Option>)}
          </Select>
        </Space>
      </div>
      <div className="panel-body">
        <div className="selection-toolbar">
          <Space>
            <Button size="small" onClick={() => setSelectedQuestionIds(questions.map((q) => q.id))}>全选（{questions.length}）</Button>
            <Button size="small" onClick={() => setSelectedQuestionIds([])}>取消全选</Button>
          </Space>
          <Typography.Text type="secondary">已选 {selectedQuestionIds.length}</Typography.Text>
        </div>
        {questions.length === 0 ? (
          <Empty description="暂无题目" />
        ) : (
          <div style={{ maxHeight: 480, overflow: 'auto' }}>
            {questions.map((q) => (
              <div
                key={q.id}
                className={`knowledge-item ${selectedQuestionIds.includes(q.id) ? 'selected' : ''}`}
                onClick={() => setSelectedQuestionIds((ids) => ids.includes(q.id) ? ids.filter((id) => id !== q.id) : [...ids, q.id])}
              >
                <div className="knowledge-item-top">
                  <Tag>{q.type === 'single' ? '单选' : q.type === 'multiple' ? '多选' : q.type === 'fill' ? '填空' : q.type === 'true_false' ? '判断' : '解答'}</Tag>
                  <span style={{ flex: 1, cursor: 'pointer' }}>{q.stem}</span>
                  {q.chapter && <Tag>{q.chapter}</Tag>}
                </div>
              </div>
            ))}
          </div>
        )}
        <div className="setup-actions">
          <Button type="primary" icon={<BrainCircuit size={16} />} disabled={!selectedQuestionIds.length} loading={enrolling} onClick={handleEnroll}>
            加入复习计划（{selectedQuestionIds.length}）
          </Button>
        </div>
      </div>
    </section>
  );
}

function ReviewStatsView(): JSX.Element {
  const configsQuery = useQuery({ queryKey: ['review-configs'], queryFn: () => listConfigs() });
  const [configId, setConfigId] = useState<number>();

  useEffect(() => {
    const defaultConfig = configsQuery.data?.find((c) => c.isDefault);
    if (defaultConfig && !configId) setConfigId(defaultConfig.id);
  }, [configsQuery.data, configId]);

  const statsQuery = useQuery({
    queryKey: ['review-stats', configId],
    queryFn: () => getReviewStats(undefined, configId),
    enabled: !!configId,
  });

  const stats = statsQuery.data;

  if (statsQuery.isLoading) return <div className="panel"><div className="panel-body"><Empty description="加载中..." /></div></div>;
  if (!stats) return <div className="panel"><div className="panel-body"><Empty description="暂无复习数据" /></div></div>;

  return (
    <section className="panel">
      <div className="panel-header">
        <h2>复习统计</h2>
        <Select value={configId} onChange={(v) => setConfigId(v)} style={{ width: 160 }}>
          {configsQuery.data?.map((c) => <Select.Option key={c.id} value={c.id}>{c.name}</Select.Option>)}
        </Select>
      </div>
      <div className="panel-body">
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(4, 1fr)', gap: 16, marginBottom: 24 }}>
          <Card><Statistic title="总登记" value={stats.totalEnrolled} /></Card>
          <Card><Statistic title="新加入" value={stats.newCount} /></Card>
          <Card><Statistic title="学习中" value={stats.learningCount} /></Card>
          <Card><Statistic title="已掌握" value={stats.masteredCount} groupSeparator /></Card>
        </div>
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(3, 1fr)', gap: 16, marginBottom: 24 }}>
          <Card><Statistic title="今日到期" value={stats.dueToday} /></Card>
          <Card><Statistic title="待复习" value={stats.reviewCount} /></Card>
          <Card><Statistic title="今日新增" value={stats.newToday} /></Card>
        </div>
        {stats.dailyStats && stats.dailyStats.length > 0 && (
          <div>
            <Typography.Title heading={6}>近 30 天复习趋势</Typography.Title>
            <div className="stats-bar-chart">
              {stats.dailyStats.slice(-14).map((day: { review_date: string; total: number; passed: number }) => {
                const maxTotal = Math.max(...stats.dailyStats.map((d: { total: number }) => d.total), 1);
                const pct = (day.total / maxTotal) * 100;
                const passedPct = day.total > 0 ? (day.passed / day.total) * 100 : 0;
                return (
                  <div key={day.review_date} className="bar-item" style={{ height: Math.max(pct, 4) + '%' }}>
                    <div className="bar-fill" style={{ height: passedPct + '%', background: 'var(--color-success)' }} />
                    <div className="bar-label">{day.review_date.slice(5)}</div>
                    <div className="bar-value">{day.total}</div>
                  </div>
                );
              })}
            </div>
          </div>
        )}
      </div>
    </section>
  );
}
