import { useEffect, useMemo, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Button, Empty, Message, Select, Space, Switch, Tag, Typography } from '@arco-design/web-react';
import { BookOpenCheck, CheckCircle2, ChevronLeft, ChevronRight, Eye, RotateCcw } from 'lucide-react';
import { get } from '../lib/api';
import type { Bank, Question } from '../lib/types';
import { AdvancedQuestionSelector } from '../components/AdvancedQuestionSelector';
import { MarkdownContent } from '../components/markdown/MarkdownRenderer';
import { questionTypeColor, questionTypeLabel } from '../lib/quiz';
import { enrollItems, submitReview, getScheduleDetail, listConfigs, getDueItems } from '../lib/review';

export function QuestionStudyPage(): JSX.Element {
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
  const [submittedReviewIds, setSubmittedReviewIds] = useState<Set<number>>(new Set());
  const [reviewConfigId, setReviewConfigId] = useState<number>();
  const selectionBank = useRef<number>();

  // 复习方案
  const configsQuery = useQuery({ queryKey: ['review-configs'], queryFn: () => listConfigs() });
  useEffect(() => {
    if (configsQuery.data?.length && reviewConfigId === undefined) {
      const def = configsQuery.data.find((c) => c.isDefault) ?? configsQuery.data[0];
      setReviewConfigId(def.id);
    }
  }, [configsQuery.data, reviewConfigId]);
  const selectionInitialized = useRef(false);

  // 待复习过滤
  const [dueOnly, setDueOnly] = useState(false);
  const dueQuery = useQuery({
    queryKey: ['review-due', reviewConfigId, 'question'],
    queryFn: () => getDueItems({ type: 'question', configId: reviewConfigId }),
    enabled: reviewConfigId !== undefined,
  });

  // 所有活跃（未掌握）的题目ID
  const activeQuestionIds = useMemo(
    () => new Set((dueQuery.data ?? []).map((d) => d.itemId)),
    [dueQuery.data],
  );

  // 到期题：next_review 已过期且非 new
  const dueQuestionIds = useMemo(() => {
    const ids = new Set<number>();
    for (const d of (dueQuery.data ?? [])) {
      if (d.status === 'new') continue;
      if (d.nextReview && new Date(d.nextReview.replace(' ', 'T')).getTime() <= Date.now()) {
        ids.add(d.itemId);
      }
    }
    return ids;
  }, [dueQuery.data]);
  const newQuestionIds = useMemo(
    () => new Set((dueQuery.data ?? []).filter((d) => d.status === 'new').map((d) => d.itemId)),
    [dueQuery.data],
  );

  // 根据过滤展示题目
  const displayQuestions = useMemo(() => {
    const all = questionsQuery.data ?? [];
    if (!dueOnly) return all;
    // 没有任何活跃条目时，显示全部（首次使用）
    if (activeQuestionIds.size === 0) return all;
    // 仅待复习：到期 + 新学
    return all.filter((q) => dueQuestionIds.has(q.id) || newQuestionIds.has(q.id));
  }, [questionsQuery.data, dueOnly, dueQuestionIds, newQuestionIds, activeQuestionIds]);
  const dueCount = dueQuestionIds.size;
  const newCount = newQuestionIds.size;
  const learningCount = activeQuestionIds.size - dueCount - newCount;

  useEffect(() => { if (bankId === undefined && banksQuery.data?.length) setBankId(banksQuery.data[0].id); }, [bankId, banksQuery.data]);
  useEffect(() => {
    if (!questionsQuery.data) return;
    const available = displayQuestions.map((question) => question.id);
    if (!selectionInitialized.current || selectionBank.current !== bankId || dueOnly) {
      selectionInitialized.current = true;
      selectionBank.current = bankId;
      setSelectedIds(available);
      return;
    }
    const availableIds = new Set(available);
    setSelectedIds((ids) => ids.filter((id) => availableIds.has(id)));
  }, [bankId, questionsQuery.data, dueOnly, displayQuestions]);
  const byId = useMemo(() => new Map((questionsQuery.data ?? []).map((question) => [question.id, question])), [questionsQuery.data]);
  const sessionQuestions = (sessionIds ?? []).map((id) => byId.get(id)).filter((question): question is Question => Boolean(question));
  const question = sessionQuestions[index];

  const jump = (nextIndex: number): void => { setIndex(nextIndex); setRevealed(false); setSelectedOption(null); };

  // 将背题评分提交到 SM-2 系统（fire-and-forget）
  const submitReviewForQuestion = async (questionId: number, quality: number): Promise<void> => {
    try {
      const detail = await getScheduleDetail('question', questionId);
      if (!detail.enrolled) {
        const result = await enrollItems('question', [questionId], reviewConfigId);
        const enrolled = result.find((r) => r.itemId === questionId);
        if (enrolled?.scheduleId) {
          await submitReview(enrolled.scheduleId, quality, undefined, 'memorize');
        }
      } else if (detail.id) {
        await submitReview(detail.id, quality, undefined, 'memorize');
      }
    } catch {
      // 后端不可用时静默失败，不打断背题流程
    }
  };

  const reveal = (): void => { setRevealed(true); };

  const mark = (known: boolean): void => {
    if (!question) return;

    // 1. 本地状态立即更新（同步，不阻塞）
    setRemembered((ids) => known ? [...new Set([...ids, question.id])] : ids.filter((id) => id !== question.id));
    setReviewAgain((ids) => known ? ids.filter((id) => id !== question.id) : [...new Set([...ids, question.id])]);

    // 2. 异步提交 SM-2 复习结果（fire-and-forget，同一题仅提交一次）
    if (!submittedReviewIds.has(question.id)) {
      setSubmittedReviewIds((prev) => new Set(prev).add(question.id));
      const quality = known ? 4 : 0;
      void submitReviewForQuestion(question.id, quality);
    }

    // 3. "记住了"才跳到下一题；"再看一次"留在当前题
    if (known) {
      if (index < sessionQuestions.length - 1) jump(index + 1);
      else Message.success('本轮背题完成');
    }
  };

  return <main className="page">
    <div className="page-heading"><div><h1>背题</h1><p>面向已知题库：先回忆题目与选项，再揭示答案和解析，不计入刷题成绩。</p></div>{sessionIds && <Button icon={<RotateCcw size={16} />} onClick={() => { setSessionIds(undefined); setIndex(0); setRevealed(false); setSelectedOption(null); }}>重新编排</Button>}</div>
    {!sessionIds ? <section className="panel study-setup-panel"><div className="panel-header"><h2>选择并编排要背的题目</h2></div><div className="panel-body">
      <Select value={bankId} placeholder="选择题库" onChange={(value) => { setBankId(Number(value)); setSessionIds(undefined); }} style={{ width: 320, marginBottom: 16 }}>{banksQuery.data?.map((bank) => <Select.Option key={bank.id} value={bank.id}>{bank.name}（{bank.questionCount ?? 0} 道）</Select.Option>)}</Select>
      <Select value={reviewConfigId} placeholder="记忆曲线方案" onChange={(v) => setReviewConfigId(v)} style={{ width: 200, marginBottom: 16, marginLeft: 12 }}>
        {configsQuery.data?.map((c) => <Select.Option key={c.id} value={c.id}>{c.name}{c.isDefault ? '（默认）' : ''}</Select.Option>)}
      </Select>
      <div style={{ display: 'flex', alignItems: 'center', gap: 12, marginBottom: 16 }}>
        <Switch checked={dueOnly} onChange={setDueOnly} />
        <Typography.Text>仅待复习</Typography.Text>
        {dueOnly && (activeQuestionIds.size > 0
          ? <Tag color="arcoblue">到期 {dueCount} + 新学 {newCount}{learningCount > 0 ? ` | 学习中 ${learningCount}` : ''}</Tag>
          : <Tag color="gray">暂无复习记录，显示全部</Tag>
        )}
      </div>
      {questionsQuery.data?.length ? <><AdvancedQuestionSelector questions={displayQuestions} selectedIds={selectedIds} onChange={setSelectedIds} /><div className="setup-actions"><Button type="primary" icon={<BookOpenCheck size={16} />} disabled={!selectedIds.length} onClick={() => { setSessionIds([...selectedIds]); setIndex(0); setRevealed(false); setSelectedOption(null); setRemembered([]); setReviewAgain([]); setSubmittedReviewIds(new Set()); }}>开始背题（{selectedIds.length}）</Button></div></> : <Empty description="该题库暂无题目" />}
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
        <div className="quiz-actions"><Button icon={<ChevronLeft size={16} />} disabled={index === 0} onClick={() => jump(index - 1)}>上一题</Button>{!revealed ? <Button type="primary" icon={<Eye size={16} />} onClick={reveal}>揭示答案</Button> : <><Button status="warning" onClick={() => mark(false)}>再看一次</Button><Button type="primary" onClick={() => mark(true)}>记住了</Button></>}<Button icon={<ChevronRight size={16} />} disabled={index === sessionQuestions.length - 1} onClick={() => jump(index + 1)}>下一题</Button></div>
      </section>
    </div> : <Empty description="没有可背诵的题目" />}
  </main>;
}
