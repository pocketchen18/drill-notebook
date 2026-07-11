import { useEffect, useMemo, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Button, Empty, Message, Modal, Select, Space, Spin, Tag, Typography } from '@arco-design/web-react';
import { Check, ChevronLeft, ChevronRight, FilePlus2, Play, RotateCcw, Sparkles, X } from 'lucide-react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { get, post } from '../lib/api';
import type { Bank, NotePage, Notebook, Question, QuizSession, SubmitResult } from '../lib/types';
import { useSessionStore } from '../stores/sessionStore';
import { useUiStore } from '../stores/uiStore';
import { MarkdownContent } from '../components/markdown/MarkdownRenderer';
import { questionsToMarkdown } from '../lib/aiContext';
import { useRegisterPageContext } from '../hooks/useRegisterPageContext';

const { Text } = Typography;

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
  const [limit, setLimit] = useState(20);
  const [session, setSession] = useState<QuizSession>();
  const [index, setIndex] = useState(0);
  const [selected, setSelected] = useState<string[]>([]);
  const [result, setResult] = useState<SubmitResult>();
  const [noteQuestion, setNoteQuestion] = useState<Question>();
  const [noteVisible, setNoteVisible] = useState(false);

  useEffect(() => {
    if (!bankId && banksQuery.data?.length) setBankId(banksQuery.data[0].id);
  }, [bankId, banksQuery.data]);

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
      const newSession = await post<QuizSession>('/api/quiz/sessions', { bankId, questionIds, shuffle: true, limit });
      setSession(newSession);
      setSessionId(newSession.sessionId);
      setIndex(0);
      setSelected([]);
      setResult(undefined);
    } catch (error) {
      Message.error(error instanceof Error ? error.message : '无法开始练习');
    }
  };

  const choose = (key: string): void => {
    if (!question || result) return;
    setSelected((current) => question.type === 'multiple' ? current.includes(key) ? current.filter((value) => value !== key) : [...current, key].sort() : [key]);
  };

  const submit = async (): Promise<void> => {
    if (!session || !question || selected.length === 0) {
      Message.warning('请选择答案');
      return;
    }
    try {
      const submission = await post<SubmitResult>(`/api/quiz/sessions/${session.sessionId}/submit`, { questionId: question.id, userAnswer: selected.join(','), timeSpent: 0 });
      setResult(submission);
    } catch (error) {
      Message.error(error instanceof Error ? error.message : '提交失败');
    }
  };

  const next = (): void => {
    if (!session) return;
    if (index >= session.questions.length - 1) {
      Message.success('本轮练习完成');
      return;
    }
    setIndex((current) => current + 1);
    setSelected([]);
    setResult(undefined);
  };

  const previous = (): void => {
    if (!session || index === 0) return;
    setIndex((current) => Math.max(0, current - 1));
    setSelected([]);
    setResult(undefined);
  };

  return <main className="page">
    <div className="page-heading">
      <div><h1>刷题</h1><p>按题库随机抽题，提交后查看答案和解析。可用右下角 AI 助手讲解当前题。</p></div>
      <Space>
        <Button icon={<Sparkles size={16} />} onClick={() => setAiOpen(true)}>问 AI</Button>
        <Button icon={<RotateCcw size={16} />} onClick={() => { setSession(undefined); setResult(undefined); }}>重新选择</Button>
        <Button type="primary" icon={<Play size={16} />} onClick={() => void start()}>开始练习</Button>
      </Space>
    </div>
    {!session ? <section className="panel" style={{ maxWidth: 620 }}>
      <div className="panel-header"><h2>练习设置</h2></div>
      <div className="panel-body">
        <Space direction="vertical" style={{ width: '100%' }} size="large">
          <div><Text type="secondary">题库</Text><Select style={{ width: '100%', marginTop: 8 }} value={bankId} onChange={(value) => setBankId(Number(value))} placeholder="选择题库">
            {banksQuery.data?.map((bank) => <Select.Option key={bank.id} value={bank.id}>{bank.name}（{bank.questionCount ?? 0} 道题）</Select.Option>)}
          </Select></div>
          <div><Text type="secondary">题目数量</Text><Select style={{ width: '100%', marginTop: 8 }} value={limit} onChange={(value) => setLimit(Number(value))}><Select.Option value={10}>10 题</Select.Option><Select.Option value={20}>20 题</Select.Option><Select.Option value={50}>50 题</Select.Option></Select></div>
          {questionIds?.length ? <Tag color="green">错题再练：{questionIds.length} 题</Tag> : null}
          {!banksQuery.isLoading && !banksQuery.data?.length && <Empty description="请先在题库页创建并导入题目" />}
        </Space>
      </div>
    </section> : question ? <div className="quiz-layout">
      <div className="quiz-card">
        <div className="quiz-progress"><span>第 {index + 1} / {session.questions.length} 题</span><Tag color={question.type === 'multiple' ? 'purple' : 'arcoblue'}>{question.type === 'multiple' ? '多选题' : '单选题'}</Tag></div>
        <div className="quiz-stem"><MarkdownContent value={question.stem} /></div>
        <div className="quiz-options">
          {question.options.map((option) => {
            const isCorrect = result?.correctAnswer.split(',').includes(option.key);
            const isWrong = result && selected.includes(option.key) && !isCorrect;
            return <button key={option.key} type="button" className={`quiz-option ${selected.includes(option.key) ? 'selected' : ''} ${result && isCorrect ? 'correct' : ''} ${isWrong ? 'wrong' : ''}`} onClick={() => choose(option.key)}>
              <span className="quiz-key">{option.key}</span><MarkdownContent inline value={option.text} />
              {result && isCorrect ? <Check size={17} color="#2ba471" /> : null}
              {isWrong ? <X size={17} color="#f53f3f" /> : null}
            </button>;
          })}
        </div>
        {result && <div className="feedback"><strong>{result.isCorrect ? '回答正确' : `回答错误，正确答案：${result.correctAnswer}`}</strong><MarkdownContent value={result.analysis || '这道题暂无解析。'} /></div>}
        <div className="quiz-actions">
          <Button icon={<FilePlus2 size={16} />} onClick={() => { setNoteQuestion(question); setNoteVisible(true); }}>添加到笔记</Button>
          <Button icon={<Sparkles size={16} />} onClick={() => setAiOpen(true)}>AI 讲解</Button>
          {!result ? <Button type="primary" onClick={() => void submit()}>提交答案</Button> : <Button type="primary" icon={<ChevronRight size={16} />} onClick={next}>{index === session.questions.length - 1 ? '完成' : '下一题'}</Button>}
        </div>
      </div>
      <div style={{ display: 'flex', justifyContent: 'space-between', marginTop: 14 }}><Button type="text" icon={<ChevronLeft size={16} />} disabled={index === 0} onClick={previous}>上一题</Button><Text type="secondary">数字 1-4 选择，Enter 提交，←/→ 或 P/N 切题 · Ctrl+J AI</Text></div>
    </div> : <Empty description="题库中没有可练习的题目" />}
    <AddToNoteModal question={noteQuestion} visible={noteVisible} onClose={() => setNoteVisible(false)} />
  </main>;
}
