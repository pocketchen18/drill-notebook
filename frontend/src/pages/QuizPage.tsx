import { useEffect, useMemo, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Button, Empty, Input, Message, Modal, Select, Space, Spin, Tag, Typography } from '@arco-design/web-react';
import { Check, ChevronLeft, ChevronRight, FilePlus2, Play, RotateCcw, Sparkles, X } from 'lucide-react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { get, post } from '../lib/api';
import type { Bank, NotePage, Notebook, Question, QuizSession, SubmitResult } from '../lib/types';
import { useSessionStore } from '../stores/sessionStore';
import { useUiStore } from '../stores/uiStore';
import { MarkdownContent } from '../components/markdown/MarkdownRenderer';
import { questionsToMarkdown } from '../lib/aiContext';
import { useRegisterPageContext } from '../hooks/useRegisterPageContext';
import { AdvancedQuestionSelector } from '../components/AdvancedQuestionSelector';
import { questionTypeColor, questionTypeLabel } from '../lib/quiz';

const { Text } = Typography;

interface AnsweredQuestionState {
  selected: string[];
  textAnswer: string;
  result: SubmitResult;
}

function AddToNoteModal({ question, visible, onClose }: { question?: Question; visible: boolean; onClose: () => void }): JSX.Element {
  const [notebookId, setNotebookId] = useState<number>();
  const [pageId, setPageId] = useState<number>();
  const notebooksQuery = useQuery({ queryKey: ['notebooks'], queryFn: () => get<Notebook[]>('/api/notebooks'), enabled: visible });
  const pagesQuery = useQuery({ queryKey: ['note-pages', notebookId], queryFn: () => get<NotePage[]>(`/api/notebooks/${notebookId}/pages`), enabled: visible && notebookId !== undefined });

  useEffect(() => {
    if (notebookId === undefined && notebooksQuery.data?.length) setNotebookId(notebooksQuery.data[0].id);
  }, [notebookId, notebooksQuery.data]);
  useEffect(() => {
    if (pageId === undefined && pagesQuery.data?.length) setPageId(pagesQuery.data[0].id);
  }, [pageId, pagesQuery.data]);

  const add = async (): Promise<void> => {
    if (!question || !pageId) return;
    try {
      await post(`/api/notes/pages/${pageId}/questions/${question.id}`, {});
      Message.success('题目已添加到笔记');
      onClose();
    } catch (error) {
      Message.error(error instanceof Error ? error.message : '添加失败');
    }
  };

  return <Modal title="添加到笔记" visible={visible} onCancel={onClose} onOk={() => void add()} okText="添加" autoFocus={false}>
    {notebooksQuery.isLoading ? <Spin /> : notebooksQuery.data?.length ? <Space direction="vertical" style={{ width: '100%' }}>
      <Select placeholder="选择笔记本" value={notebookId} onChange={(value) => { setNotebookId(Number(value)); setPageId(undefined); }}>
        {notebooksQuery.data.map((notebook) => <Select.Option key={notebook.id} value={notebook.id}>{notebook.title}</Select.Option>)}
      </Select>
      <Select placeholder="选择页面" value={pageId} onChange={(value) => setPageId(Number(value))}>
        {pagesQuery.data?.map((page) => <Select.Option key={page.id} value={page.id}>{page.title}</Select.Option>)}
      </Select>
      {!pagesQuery.isLoading && !pagesQuery.data?.length && <Text type="secondary">该笔记本还没有页面，请先在笔记本页创建页面。</Text>}
    </Space> : <Empty description="还没有笔记本" />}
  </Modal>;
}

export function QuizPage(): JSX.Element {
  const [searchParams] = useSearchParams();
  const { setSessionId } = useSessionStore();
  const setAiOpen = useUiStore((state) => state.setAiOpen);
  const banksQuery = useQuery({ queryKey: ['banks'], queryFn: () => get<Bank[]>('/api/banks') });
  const initialBank = Number(searchParams.get('bankId')) || undefined;
  const questionIds = useMemo(() => searchParams.get('questionIds')?.split(',').map(Number).filter(Boolean), [searchParams]);
  const [bankId, setBankId] = useState<number | undefined>(initialBank);
  const questionsQuery = useQuery({ queryKey: ['quiz-questions', bankId], queryFn: () => get<Question[]>(`/api/banks/${bankId}/questions`), enabled: bankId !== undefined && !questionIds?.length });
  const [selectedQuestionIds, setSelectedQuestionIds] = useState<number[]>(questionIds ?? []);
  const [session, setSession] = useState<QuizSession>();
  const [index, setIndex] = useState(0);
  const [selected, setSelected] = useState<string[]>([]);
  const [textAnswer, setTextAnswer] = useState('');
  const [result, setResult] = useState<SubmitResult>();
  const [submitting, setSubmitting] = useState(false);
  const [masterPassword, setMasterPassword] = useState('');
  const [noteQuestion, setNoteQuestion] = useState<Question>();
  const [noteVisible, setNoteVisible] = useState(false);
  const [answeredIds, setAnsweredIds] = useState<number[]>([]);
  const [answerStates, setAnswerStates] = useState<Record<number, AnsweredQuestionState>>({});

  useEffect(() => {
    if (!bankId && banksQuery.data?.length) setBankId(banksQuery.data[0].id);
  }, [bankId, banksQuery.data]);
  useEffect(() => {
    if (questionIds?.length) setSelectedQuestionIds(questionIds);
    else if (questionsQuery.data) setSelectedQuestionIds(questionsQuery.data.map((item) => item.id));
  }, [bankId, questionIds, questionsQuery.data]);

  const question = session?.questions[index];

  const pageContext = useMemo(() => {
    if (!question) {
      return { kind: 'quiz' as const, title: '刷题（未开始）', markdown: '', route: '/quiz' };
    }
    const body = questionsToMarkdown([{
      ...question,
      answer: result?.correctAnswer ?? question.answer,
      analysis: result?.analysis ?? question.analysis
    }]);
    return {
      kind: 'quiz' as const,
      title: `刷题 · 第 ${index + 1} 题`,
      markdown: body,
      route: '/quiz',
      questionId: question.id
    };
  }, [index, question, result]);

  useRegisterPageContext(pageContext);

  useEffect(() => {
    const onKey = (event: KeyboardEvent): void => {
      if (!session || (event.target instanceof HTMLElement && ['INPUT', 'TEXTAREA', 'SELECT', 'BUTTON'].includes(event.target.tagName))) return;
      const option = session.questions[index]?.options[Number(event.key) - 1];
      if (option && !result) {
        event.preventDefault();
        choose(option.key);
        return;
      }
      const key = event.key.toLowerCase();
      if (!result && (event.key === 'Enter' || (event.ctrlKey && key === 's'))) {
        event.preventDefault();
        void submit();
        return;
      }
      if (result && (event.key === 'Enter' || event.key === 'ArrowRight' || event.key === 'PageDown' || key === 'n')) {
        event.preventDefault();
        next();
        return;
      }
      if (event.key === 'ArrowLeft' || event.key === 'PageUp' || key === 'p') {
        event.preventDefault();
        previous();
      }
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  });

  const start = async (): Promise<void> => {
    if (!bankId && !questionIds?.length) {
      Message.warning('请先选择题库');
      return;
    }
    try {
      const plannedIds = questionIds?.length ? questionIds : selectedQuestionIds;
      if (!plannedIds.length) { Message.warning('请至少选择一道题'); return; }
      const newSession = await post<QuizSession>('/api/quiz/sessions', { bankId, questionIds: plannedIds, shuffle: false, limit: plannedIds.length });
      setSession(newSession);
      setSessionId(newSession.sessionId);
      setIndex(0);
      setSelected([]);
      setTextAnswer('');
      setResult(undefined);
      setAnsweredIds([]);
      setAnswerStates({});
      setMasterPassword('');
    } catch (error) {
      Message.error(error instanceof Error ? error.message : '无法开始练习');
    }
  };

  const choose = (key: string): void => {
    if (!question || result) return;
    setSelected((current) => question.type === 'multiple' ? current.includes(key) ? current.filter((value) => value !== key) : [...current, key].sort() : [key]);
  };

  const submit = async (): Promise<void> => {
    if (submitting || result) return;
    const userAnswer = question && (question.type === 'single' || question.type === 'multiple' || question.type === 'true_false') ? selected.join(',') : textAnswer.trim();
    if (!session || !question || !userAnswer) {
      Message.warning('请填写或选择答案');
      return;
    }
    setSubmitting(true);
    try {
      const submission = await post<SubmitResult>(`/api/quiz/sessions/${session.sessionId}/submit`, { questionId: question.id, userAnswer, timeSpent: 0, useAiGrading: question.type === 'essay', masterPassword: question.type === 'essay' && masterPassword ? masterPassword : undefined });
      setResult(submission);
      setAnswerStates((states) => ({ ...states, [question.id]: { selected: [...selected], textAnswer, result: submission } }));
      setAnsweredIds((ids) => [...new Set([...ids, question.id])]);
    } catch (error) {
      Message.error(error instanceof Error ? error.message : '提交失败');
    } finally { setSubmitting(false); }
  };

  const showQuestion = (nextIndex: number): void => {
    if (!session) return;
    const nextQuestion = session.questions[nextIndex];
    if (!nextQuestion) return;
    const saved = answerStates[nextQuestion.id];
    setIndex(nextIndex);
    setSelected(saved?.selected ?? []);
    setTextAnswer(saved?.textAnswer ?? '');
    setResult(saved?.result);
  };

  const next = (): void => {
    if (!session) return;
    if (index >= session.questions.length - 1) {
      Message.success('本轮练习完成');
      return;
    }
    showQuestion(index + 1);
  };

  const previous = (): void => {
    if (!session || index === 0) return;
    showQuestion(index - 1);
  };

  const jump = (nextIndex: number): void => { showQuestion(nextIndex); };

  return <main className="page">
    <div className="page-heading">
      <div><h1>刷题</h1><p>按筛选与编排顺序练习，提交后查看答案和解析。可用右下角 AI 助手讲解当前题。</p></div>
      <Space>
        <Button icon={<Sparkles size={16} />} onClick={() => setAiOpen(true)}>问 AI</Button>
        <Button icon={<RotateCcw size={16} />} onClick={() => { setSession(undefined); setResult(undefined); setMasterPassword(''); setAnswerStates({}); setAnsweredIds([]); }}>重新选择</Button>
        <Button type="primary" icon={<Play size={16} />} onClick={() => void start()}>开始练习</Button>
      </Space>
    </div>
    {!session ? <section className="panel study-setup-panel">
      <div className="panel-header"><h2>练习设置</h2></div>
      <div className="panel-body">
        <Space direction="vertical" style={{ width: '100%' }} size="large">
          <div><Text type="secondary">题库</Text><Select style={{ width: '100%', marginTop: 8 }} value={bankId} onChange={(value) => setBankId(Number(value))} placeholder="选择题库">
            {banksQuery.data?.map((bank) => <Select.Option key={bank.id} value={bank.id}>{bank.name}（{bank.questionCount ?? 0} 道题）</Select.Option>)}
          </Select></div>
          {questionIds?.length ? <Tag color="green">错题再练：{questionIds.length} 题</Tag> : null}
          {!questionIds?.length && questionsQuery.data?.length ? <AdvancedQuestionSelector questions={questionsQuery.data} selectedIds={selectedQuestionIds} onChange={setSelectedQuestionIds} /> : null}
          {!banksQuery.isLoading && !banksQuery.data?.length && <Empty description="请先在题库页创建并导入题目" />}
        </Space>
      </div>
    </section> : question ? <div className="study-session-layout">
      <aside className="panel question-palette"><div className="panel-header"><h2>题号跳转</h2></div><div className="panel-body"><div className="palette-grid">{session.questions.map((item, itemIndex) => <button type="button" key={item.id} className={`palette-item ${itemIndex === index ? 'current' : ''} ${answeredIds.includes(item.id) ? 'answered' : ''}`} onClick={() => jump(itemIndex)}>{itemIndex + 1}</button>)}</div><Text type="secondary">蓝色：当前 · 绿色：已作答</Text></div></aside>
      <div className="quiz-layout"><div className="quiz-card">
        <div className="quiz-progress"><span>第 {index + 1} / {session.questions.length} 题</span><Tag color={questionTypeColor(question.type)}>{questionTypeLabel(question.type)}</Tag></div>
        <div className="quiz-stem"><MarkdownContent value={question.stem} /></div>
        {(question.type === 'single' || question.type === 'multiple') && <div className="quiz-options">
          {question.options.map((option) => {
            const isCorrect = result?.correctAnswer.split(',').includes(option.key);
            const isWrong = result && selected.includes(option.key) && !isCorrect;
            return <button key={option.key} type="button" disabled={Boolean(result)} className={`quiz-option ${selected.includes(option.key) ? 'selected' : ''} ${result && isCorrect ? 'correct' : ''} ${isWrong ? 'wrong' : ''}`} onClick={() => choose(option.key)}>
              <span className="quiz-key">{option.key}</span><MarkdownContent inline value={option.text} />
              {result && isCorrect ? <Check size={17} color="#2ba471" /> : null}
              {isWrong ? <X size={17} color="#f53f3f" /> : null}
            </button>;
          })}
        </div>}
        {question.type === 'true_false' && <div className="true-false-options"><Button size="large" type={selected.includes('true') ? 'primary' : 'outline'} disabled={Boolean(result)} onClick={() => choose('true')}>正确</Button><Button size="large" type={selected.includes('false') ? 'primary' : 'outline'} disabled={Boolean(result)} onClick={() => choose('false')}>错误</Button></div>}
        {question.type === 'fill' && <Input value={textAnswer} onChange={setTextAnswer} disabled={Boolean(result)} size="large" placeholder="填写答案" allowClear />}
        {question.type === 'essay' && <><Input.TextArea value={textAnswer} onChange={setTextAnswer} disabled={Boolean(result)} placeholder="写下你的解答；提交后将尝试使用已配置的 AI 模型结合参考答案给出辅助建议" autoSize={{ minRows: 7, maxRows: 16 }} />{!result && <Space direction="vertical" size={4} style={{ width: '100%', marginTop: 12 }}><Input.Password value={masterPassword} onChange={setMasterPassword} placeholder="可选：AI 配置使用主密码时在此输入" /><Text type="secondary">主密码仅保留在当前练习页面，不会写入答案记录。</Text></Space>}</>}
        {result && question.type !== 'essay' && <div className="feedback"><strong>{result.isCorrect ? '回答正确' : `回答错误，正确答案：${question.type === 'true_false' ? result.correctAnswer === 'true' ? '正确' : '错误' : result.correctAnswer}`}</strong><MarkdownContent value={result.analysis || '这道题暂无解析。'} /></div>}
        {result && question.type === 'essay' && <div className={`feedback essay-feedback ${result.gradingStatus === 'ai_suggested' ? 'ai-suggested' : 'ungraded'}`}>
          {result.gradingStatus === 'ai_suggested' && result.grading ? <><strong>AI 辅助建议：{result.grading.suggestedCorrect ? '回答基本符合要点' : '回答仍需完善'}</strong><div className="grading-metrics"><Tag color="arcoblue">建议得分 {Math.round(result.grading.score ?? 0)}</Tag><Tag>置信度 {Math.round((result.grading.confidence ?? 0) * 100)}%</Tag></div><MarkdownContent value={result.grading.explanation || '模型没有提供说明。'} /></> : <strong>{result.grading?.message || '答案已保存，AI 辅助判题不可用。'}</strong>}
          {result.correctAnswer ? <div className="reference-answer"><strong>参考答案</strong><MarkdownContent value={result.correctAnswer} /></div> : <Text type="secondary">本题未提供参考答案，AI 建议仅供复核。</Text>}
          {result.analysis && <MarkdownContent value={result.analysis} />}
        </div>}
        <div className="quiz-actions">
          <Button icon={<FilePlus2 size={16} />} onClick={() => { setNoteQuestion(question); setNoteVisible(true); }}>添加到笔记</Button>
          <Button icon={<Sparkles size={16} />} onClick={() => setAiOpen(true)}>AI 讲解</Button>
          {!result ? <Button type="primary" loading={submitting} onClick={() => void submit()}>{question.type === 'essay' ? '提交并请求 AI 辅助判题' : '提交答案'}</Button> : <Button type="primary" icon={<ChevronRight size={16} />} onClick={next}>{index === session.questions.length - 1 ? '完成' : '下一题'}</Button>}
        </div>
      </div>
      <div style={{ display: 'flex', justifyContent: 'space-between', marginTop: 14 }}><Button type="text" icon={<ChevronLeft size={16} />} disabled={index === 0} onClick={previous}>上一题</Button><Text type="secondary">数字 1-4 选择，Enter 提交，←/→ 或 P/N 切题 · Ctrl+J AI</Text></div>
    </div></div> : <Empty description="题库中没有可练习的题目" />}
    <AddToNoteModal question={noteQuestion} visible={noteVisible} onClose={() => setNoteVisible(false)} />
  </main>;
}
