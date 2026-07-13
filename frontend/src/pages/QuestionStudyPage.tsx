import { useEffect, useMemo, useRef, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Button, Empty, Message, Select, Space, Tag, Typography } from '@arco-design/web-react';
import { BookOpenCheck, CheckCircle2, ChevronLeft, ChevronRight, Eye, RotateCcw } from 'lucide-react';
import { get } from '../lib/api';
import type { Bank, Question } from '../lib/types';
import { AdvancedQuestionSelector } from '../components/AdvancedQuestionSelector';
import { MarkdownContent } from '../components/markdown/MarkdownRenderer';
import { questionTypeColor, questionTypeLabel } from '../lib/quiz';

export function QuestionStudyPage(): JSX.Element {
  const banksQuery = useQuery({ queryKey: ['banks'], queryFn: () => get<Bank[]>('/api/banks') });
  const [bankId, setBankId] = useState<number>();
  const questionsQuery = useQuery({ queryKey: ['study-questions', bankId], queryFn: () => get<Question[]>(`/api/banks/${bankId}/questions`), enabled: bankId !== undefined });
  const [selectedIds, setSelectedIds] = useState<number[]>([]);
  const [sessionIds, setSessionIds] = useState<number[]>();
  const [index, setIndex] = useState(0);
  const [revealed, setRevealed] = useState(false);
  const [remembered, setRemembered] = useState<number[]>([]);
  const [reviewAgain, setReviewAgain] = useState<number[]>([]);
  const selectionBank = useRef<number>();
  const selectionInitialized = useRef(false);

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
  const byId = useMemo(() => new Map((questionsQuery.data ?? []).map((question) => [question.id, question])), [questionsQuery.data]);
  const sessionQuestions = (sessionIds ?? []).map((id) => byId.get(id)).filter((question): question is Question => Boolean(question));
  const question = sessionQuestions[index];

  const jump = (nextIndex: number): void => { setIndex(nextIndex); setRevealed(false); };
  const mark = (known: boolean): void => {
    if (!question) return;
    setRemembered((ids) => known ? [...new Set([...ids, question.id])] : ids.filter((id) => id !== question.id));
    setReviewAgain((ids) => known ? ids.filter((id) => id !== question.id) : [...new Set([...ids, question.id])]);
    if (index < sessionQuestions.length - 1) jump(index + 1); else Message.success('本轮背题完成');
  };

  return <main className="page">
    <div className="page-heading"><div><h1>背题</h1><p>面向已知题库：先回忆题目与选项，再揭示答案和解析，不计入刷题成绩。</p></div>{sessionIds && <Button icon={<RotateCcw size={16} />} onClick={() => { setSessionIds(undefined); setIndex(0); setRevealed(false); }}>重新编排</Button>}</div>
    {!sessionIds ? <section className="panel study-setup-panel"><div className="panel-header"><h2>选择并编排要背的题目</h2></div><div className="panel-body">
      <Select value={bankId} placeholder="选择题库" onChange={(value) => { setBankId(Number(value)); setSessionIds(undefined); }} style={{ width: 320, marginBottom: 16 }}>{banksQuery.data?.map((bank) => <Select.Option key={bank.id} value={bank.id}>{bank.name}（{bank.questionCount ?? 0} 道）</Select.Option>)}</Select>
      {questionsQuery.data?.length ? <><AdvancedQuestionSelector questions={questionsQuery.data} selectedIds={selectedIds} onChange={setSelectedIds} /><div className="setup-actions"><Button type="primary" icon={<BookOpenCheck size={16} />} disabled={!selectedIds.length} onClick={() => { setSessionIds([...selectedIds]); setIndex(0); setRevealed(false); setRemembered([]); setReviewAgain([]); }}>开始背题（{selectedIds.length}）</Button></div></> : <Empty description="该题库暂无题目" />}
    </div></section> : question ? <div className="study-session-layout">
      <aside className="panel question-palette"><div className="panel-header"><h2>题号跳转</h2></div><div className="panel-body"><div className="palette-grid">{sessionQuestions.map((item, itemIndex) => <button type="button" key={item.id} className={`palette-item ${itemIndex === index ? 'current' : ''} ${remembered.includes(item.id) ? 'known' : ''} ${reviewAgain.includes(item.id) ? 'review' : ''}`} onClick={() => jump(itemIndex)}>{itemIndex + 1}</button>)}</div><Typography.Text type="secondary">绿色：已记住 · 橙色：需复习</Typography.Text></div></aside>
      <section className="quiz-card memory-card"><div className="quiz-progress"><span>第 {index + 1} / {sessionQuestions.length} 题</span><Tag color={questionTypeColor(question.type)}>{questionTypeLabel(question.type)}</Tag></div><div className="quiz-stem"><MarkdownContent value={question.stem} /></div>{(question.type === 'single' || question.type === 'multiple') && <div className="quiz-options">{question.options.map((option) => <div className={`quiz-option ${revealed && question.answer?.split(',').includes(option.key) ? 'correct' : ''}`} key={option.key}><span className="quiz-key">{option.key}</span><MarkdownContent inline value={option.text} />{revealed && question.answer?.split(',').includes(option.key) && <CheckCircle2 size={17} color="#2ba471" />}</div>)}</div>}
        {revealed ? <div className="feedback"><strong>{question.type === 'essay' ? '参考答案' : '答案'}</strong>{question.answer ? <MarkdownContent value={question.type === 'true_false' ? question.answer === 'true' ? '正确' : '错误' : question.answer} /> : <Typography.Text type="secondary">本题未提供参考答案。</Typography.Text>}<MarkdownContent value={question.analysis || '这道题暂无解析。'} /></div> : <div className="memory-cover"><Eye size={20} /><span>先在心里作答，再揭示{question.type === 'essay' ? '参考答案' : '答案'}</span></div>}
        <div className="quiz-actions"><Button icon={<ChevronLeft size={16} />} disabled={index === 0} onClick={() => jump(index - 1)}>上一题</Button>{!revealed ? <Button type="primary" icon={<Eye size={16} />} onClick={() => setRevealed(true)}>揭示答案</Button> : <><Button status="warning" onClick={() => mark(false)}>再看一次</Button><Button type="primary" onClick={() => mark(true)}>记住了</Button></>}<Button icon={<ChevronRight size={16} />} disabled={index === sessionQuestions.length - 1} onClick={() => jump(index + 1)}>下一题</Button></div>
      </section>
    </div> : <Empty description="没有可背诵的题目" />}
  </main>;
}
