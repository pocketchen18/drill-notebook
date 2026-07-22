import { useEffect, useMemo, useRef, useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { Button, Empty, Message, Select, Space, Tag, Typography } from '@arco-design/web-react';
import { BookOpenCheck, CalendarPlus, CheckCircle2, ChevronLeft, ChevronRight, Eye } from 'lucide-react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { get } from '../lib/api';
import type { Bank, Question } from '../lib/types';
import { AdvancedQuestionSelector } from '../components/AdvancedQuestionSelector';
import { MarkdownContent } from '../components/markdown/MarkdownRenderer';
import { questionTypeColor, questionTypeLabel } from '../lib/quiz';
import { AddToPlanModal } from '../components/AddToPlanModal';
import { CompletePlanButton } from '../components/CompletePlanButton';
import { DayQueueSessionBar, finishDayQueueStep } from '../components/DayQueueSessionBar';
import { SessionPlanRecommendModal } from '../components/SessionPlanRecommendModal';
import { planScopeFromSearch } from '../lib/planProgress';
import { completeStudy } from '../lib/study';
import { truncateTitle } from '../lib/studyPlan';

export function QuestionStudyPage(): JSX.Element {
  const navigate = useNavigate();
  const [searchParams] = useSearchParams();
  const { planItemId, planDate } = planScopeFromSearch(searchParams);
  const queryQuestionIds = useMemo(
    () => searchParams.get('questionIds')?.split(',').map(Number).filter(Boolean) ?? [],
    [searchParams]
  );
  const dayQueueMode = searchParams.get('dayQueue') === '1';
  const planResourceId = queryQuestionIds.length === 1 ? queryQuestionIds[0] : undefined;
  const queryClient = useQueryClient();
  const banksQuery = useQuery({ queryKey: ['banks'], queryFn: () => get<Bank[]>('/api/banks') });
  const [bankId, setBankId] = useState<number>();
  const questionsQuery = useQuery({ queryKey: ['study-questions', bankId], queryFn: () => get<Question[]>(`/api/banks/${bankId}/questions`), enabled: bankId !== undefined });
  const [selectedIds, setSelectedIds] = useState<number[]>([]);
  const [sessionIds, setSessionIds] = useState<number[]>();
  const [index, setIndex] = useState(0);
  const [revealed, setRevealed] = useState(false);
  const [selectedOption, setSelectedOption] = useState<string | null>(null);
  const [remembered, setRemembered] = useState<number[]>([]);
  const [reviewAgain, setReviewAgain] = useState<number[]>([]);
  const [planVisible, setPlanVisible] = useState(false);
  const [planItems, setPlanItems] = useState<Array<{ resourceId: number; title: string }>>([]);
  const [recommendVisible, setRecommendVisible] = useState(false);
  const [recommendPayload, setRecommendPayload] = useState<{ reviewAgainIds?: number[] }>({});
  const recommendShownRef = useRef(false);
  const [submittedReviewIds, setSubmittedReviewIds] = useState<Set<number>>(new Set());
  const selectionBank = useRef<number>();
  const selectionInitialized = useRef(false);
  const deepLinkStarted = useRef(false);

  useEffect(() => { if (bankId === undefined && banksQuery.data?.length) setBankId(banksQuery.data[0].id); }, [bankId, banksQuery.data]);
  useEffect(() => {
    if (!questionsQuery.data) return;
    const available = questionsQuery.data.map((question) => question.id);
    if (!selectionInitialized.current || selectionBank.current !== bankId) {
      selectionInitialized.current = true;
      selectionBank.current = bankId;
      setSelectedIds(available);
      return;
    }
    const availableIds = new Set(available);
    setSelectedIds((ids) => ids.filter((id) => availableIds.has(id)));
  }, [bankId, questionsQuery.data]);

  // Day-queue / deep-link: start memorize session from questionIds once.
  useEffect(() => {
    if (!queryQuestionIds.length || deepLinkStarted.current) return;
    deepLinkStarted.current = true;
    setSelectedIds(queryQuestionIds);
    setSessionIds(queryQuestionIds);
    setIndex(0);
    setRevealed(false);
    setSelectedOption(null);
    setRemembered([]);
    setReviewAgain([]);
    setSubmittedReviewIds(new Set());
    recommendShownRef.current = false;
  }, [queryQuestionIds]);

  const byId = useMemo(() => new Map((questionsQuery.data ?? []).map((question) => [question.id, question])), [questionsQuery.data]);
  const sessionQuestions = (sessionIds ?? []).map((id) => byId.get(id)).filter((question): question is Question => Boolean(question));
  const question = sessionQuestions[index];

  const jump = (nextIndex: number): void => { setIndex(nextIndex); setRevealed(false); setSelectedOption(null); };

  const openSessionRecommend = (reviewAgainIds: number[]): void => {
    if (recommendShownRef.current) return;
    recommendShownRef.current = true;
    setRecommendPayload({ reviewAgainIds });
    setRecommendVisible(true);
  };

  const scheduleIdFromQuery = Number(searchParams.get('scheduleId')) || undefined;
  const fromQueue = searchParams.get('fromQueue') === '1' || dayQueueMode;

  const rateQuality = (quality: number): void => {
    if (!question) return;
    const known = quality >= 3;
    setRemembered((ids) =>
      known ? [...new Set([...ids, question.id])] : ids.filter((id) => id !== question.id)
    );
    const nextReviewAgain = known
      ? reviewAgain.filter((id) => id !== question.id)
      : [...new Set([...reviewAgain, question.id])];
    setReviewAgain(nextReviewAgain);

    if (!submittedReviewIds.has(question.id)) {
      setSubmittedReviewIds((prev) => new Set(prev).add(question.id));
      void completeStudy({
        resourceType: 'question',
        resourceId: question.id,
        quality,
        source: fromQueue ? 'today_queue' : 'memorize',
        planItemId: planItemId && planResourceId === question.id ? planItemId : undefined,
        planDate,
        scheduleId: scheduleIdFromQuery,
        // From calendar curve queue: always advance (补做), not same-day extra.
        forceAdvance: fromQueue
      })
        .then((result) => {
          const next = result.srs?.nextReview;
          if (next && next.length >= 10) {
            Message.success(`已记录掌握度，下次约 ${next.slice(0, 10)}`);
          }
        })
        .catch(() => {
          /* best-effort */
        })
        .finally(() => {
          void queryClient.invalidateQueries({ queryKey: ['review-due'] });
          void queryClient.invalidateQueries({ queryKey: ['study-today'] });
        });
    }

    if (index < sessionQuestions.length - 1) {
      jump(index + 1);
    } else if (dayQueueMode && finishDayQueueStep(navigate)) {
      return;
    } else {
      Message.success('本轮背题完成');
      openSessionRecommend(nextReviewAgain);
    }
  };

  /** Quick binary rating (legacy buttons). */
  const mark = (known: boolean): void => {
    rateQuality(known ? 4 : 0);
  };

  const openPlanForQuestions = (items: Question[]): void => {
    setPlanItems(items.map((item) => ({ resourceId: item.id, title: truncateTitle(item.stem || `题目 #${item.id}`) })));
    setPlanVisible(true);
  };

  return <main className="page">
    {dayQueueMode ? <DayQueueSessionBar /> : null}
    <div className="page-heading">
      <div><h1>背题</h1><p>先回忆再揭示答案，用记忆曲线巩固。</p></div>
      <Space>
        <CompletePlanButton
          planItemId={planItemId}
          resourceType={planResourceId ? 'question' : undefined}
          resourceId={planResourceId}
        />
      </Space>
    </div>

    {!sessionIds ? <section className="panel study-setup-panel"><div className="panel-header"><h2>选择并编排要背的题目</h2></div><div className="panel-body">
      <Select value={bankId} placeholder="选择题库" onChange={(value) => { setBankId(Number(value)); setSessionIds(undefined); }} style={{ width: 320, marginBottom: 16 }}>{banksQuery.data?.map((bank) => <Select.Option key={bank.id} value={bank.id}>{bank.name}（{bank.questionCount ?? 0} 道）</Select.Option>)}</Select>
      {questionsQuery.data?.length ? <><AdvancedQuestionSelector questions={questionsQuery.data} selectedIds={selectedIds} onChange={setSelectedIds} /><div className="setup-actions"><Button type="primary" icon={<BookOpenCheck size={16} />} disabled={!selectedIds.length} onClick={() => { setSessionIds([...selectedIds]); setIndex(0); setRevealed(false); setSelectedOption(null); setRemembered([]); setReviewAgain([]); setSubmittedReviewIds(new Set()); }}>开始背题（{selectedIds.length}）</Button></div></> : <Empty description="该题库暂无题目" />}
    </div></section> : question ? <div className="study-session-layout">
      <aside className="panel question-palette"><div className="panel-header"><h2>题号跳转</h2></div><div className="panel-body"><div className="palette-grid">{sessionQuestions.map((item, itemIndex) => <button type="button" key={item.id} className={`palette-item ${itemIndex === index ? 'current' : ''} ${remembered.includes(item.id) ? 'known' : ''} ${reviewAgain.includes(item.id) ? 'review' : ''}`} onClick={() => jump(itemIndex)}>{itemIndex + 1}</button>)}</div><Typography.Text type="secondary">绿色：已记住 · 橙色：需复习</Typography.Text></div></aside>
      <section className="quiz-card memory-card"><div className="quiz-progress"><span>第 {index + 1} / {sessionQuestions.length} 题</span><Tag color={questionTypeColor(question.type)}>{questionTypeLabel(question.type)}</Tag></div><div className="quiz-stem"><MarkdownContent value={question.stem} /></div>{(question.type === 'single' || question.type === 'multiple') && <div className="quiz-options">{question.options.map((option) => {
        const isSelected = selectedOption === option.key;
        const isCorrectAnswer = question.answer?.split(',').includes(option.key);
        let className = 'quiz-option';
        if (revealed) {
          if (isCorrectAnswer) className += ' correct';
          else if (isSelected) className += ' wrong';
        } else if (isSelected) {
          className += ' selected';
        }
        return (
          <div
            className={className}
            key={option.key}
            onClick={() => { if (!revealed) setSelectedOption(isSelected ? null : option.key); }}
          >
            <span className="quiz-key">{option.key}</span>
            <MarkdownContent inline value={option.text} />
            {revealed && isCorrectAnswer && <CheckCircle2 size={17} color="#2ba471" />}
          </div>
        );
      })}</div>}
        {revealed ? <div className="feedback"><strong>{question.type === 'essay' ? '参考答案' : '答案'}</strong>{question.answer ? <MarkdownContent value={question.type === 'true_false' ? question.answer === 'true' ? '正确' : '错误' : question.answer} /> : <Typography.Text type="secondary">本题未提供参考答案。</Typography.Text>}<MarkdownContent value={question.analysis || '这道题暂无解析。'} /></div> : <div className="memory-cover"><Eye size={20} /><span>先在心里作答，再揭示{question.type === 'essay' ? '参考答案' : '答案'}</span></div>}
        <div className="quiz-actions">
          <Button icon={<ChevronLeft size={16} />} disabled={index === 0} onClick={() => jump(index - 1)}>上一题</Button>
          {!revealed ? (
            <Button type="primary" icon={<Eye size={16} />} onClick={() => setRevealed(true)}>
              揭示答案
            </Button>
          ) : (
            <div className="memory-rating-block">
              <Typography.Text type="secondary" style={{ display: 'block', marginBottom: 8 }}>
                掌握程度（写入记忆曲线）
              </Typography.Text>
              <div className="quality-buttons">
                {[
                  { q: 0, label: '不会' },
                  { q: 2, label: '困难' },
                  { q: 3, label: '一般' },
                  { q: 4, label: '熟悉' },
                  { q: 5, label: '掌握' }
                ].map((opt) => (
                  <button
                    key={opt.q}
                    type="button"
                    className={`quality-btn quality-${opt.q}`}
                    disabled={submittedReviewIds.has(question.id)}
                    onClick={() => rateQuality(opt.q)}
                  >
                    <span className="quality-score">{opt.q}</span>
                    <span className="quality-label">{opt.label}</span>
                  </button>
                ))}
              </div>
              <Space style={{ marginTop: 10 }}>
                <Button size="small" status="warning" onClick={() => mark(false)}>
                  再看一次
                </Button>
                <Button size="small" type="primary" onClick={() => mark(true)}>
                  记住了
                </Button>
              </Space>
            </div>
          )}
          <Button icon={<CalendarPlus size={16} />} onClick={() => openPlanForQuestions([question])}>加入计划</Button>
          <Button icon={<ChevronRight size={16} />} disabled={index === sessionQuestions.length - 1} onClick={() => jump(index + 1)}>下一题</Button>
        </div>
      </section>
    </div> : <Empty description="没有可背诵的题目" />}
    <AddToPlanModal
      visible={planVisible}
      onClose={() => setPlanVisible(false)}
      resourceType="question"
      items={planItems}
      defaultTitle="背题计划"
    />
    <SessionPlanRecommendModal
      visible={recommendVisible}
      onClose={() => {
        recommendShownRef.current = false;
        setRecommendVisible(false);
      }}
      sessionType="memorize"
      payload={recommendPayload}
    />
  </main>;
}
