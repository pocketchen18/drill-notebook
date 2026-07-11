import { useEffect, useRef, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Button, Form, Input, Message, Select, Spin, Tag, Typography } from '@arco-design/web-react';
import { Clipboard, FilePlus2, Paperclip, Send, Sparkles, X } from 'lucide-react';
import { get, post, put } from '../lib/api';
import type { AiConfig, Bank, ChatContentPart, ChatMessage, NotePage, Notebook, Question } from '../lib/types';
import { appendMarkdownBlock, notePageToMarkdown, questionsToMarkdown } from '../lib/aiContext';
import { MarkdownContent } from '../components/markdown/MarkdownRenderer';

const { TextArea } = Input;

type ContextSource = 'manual' | 'wrong' | 'bank' | 'note';
type AttachmentKind = 'text' | 'image';

interface Attachment {
  id: string;
  name: string;
  kind: AttachmentKind;
  value: string;
  mime: string;
}

interface ChatRequest {
  content: string | ChatContentPart[];
  displayContent: string;
}

function contentText(content: string | ChatContentPart[], displayContent?: string): string {
  if (displayContent) return displayContent;
  if (typeof content === 'string') return content;
  return content.map((part) => part.type === 'text' ? part.text ?? '' : '[图片附件]').filter(Boolean).join('\n');
}

function readAsDataUrl(file: File): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onerror = () => reject(new Error('读取文件失败'));
    reader.onload = () => resolve(String(reader.result ?? ''));
    reader.readAsDataURL(file);
  });
}

function isTextFile(file: File): boolean {
  return file.type.startsWith('text/') || /\.(md|markdown|txt|json|csv|log|java|kt|ts|tsx|js|jsx|py|go|rs|sql|xml|yaml|yml|css|html)$/i.test(file.name);
}

function questionOptions(questions: Question[]): JSX.Element[] {
  return questions.map((question) => <Select.Option key={question.id} value={String(question.id)}>{`#${question.id} ${question.stem.slice(0, 56)}`}</Select.Option>);
}

export function AiPage(): JSX.Element {
  const queryClient = useQueryClient();
  const fileInput = useRef<HTMLInputElement>(null);
  const configQuery = useQuery({ queryKey: ['ai-config'], queryFn: () => get<AiConfig>('/api/ai/config') });
  const banksQuery = useQuery({ queryKey: ['banks'], queryFn: () => get<Bank[]>('/api/banks') });
  const wrongQuery = useQuery({ queryKey: ['wrong'], queryFn: () => get<Question[]>('/api/quiz/wrong') });
  const notebooksQuery = useQuery({ queryKey: ['notebooks'], queryFn: () => get<Notebook[]>('/api/notebooks') });

  const [provider, setProvider] = useState('custom');
  const [endpoint, setEndpoint] = useState('');
  const [model, setModel] = useState('');
  const [apiKey, setApiKey] = useState('');
  const [message, setMessage] = useState('');
  const [summaryText, setSummaryText] = useState('');
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const [summary, setSummary] = useState('');
  const [attachments, setAttachments] = useState<Attachment[]>([]);
  const [contextSource, setContextSource] = useState<ContextSource>('manual');
  const [sourceBankId, setSourceBankId] = useState<number>();
  const [sourceQuestionId, setSourceQuestionId] = useState('all');
  const [sourceNotebookId, setSourceNotebookId] = useState<number>();
  const [sourcePageId, setSourcePageId] = useState<number>();
  const [targetNotebookId, setTargetNotebookId] = useState<number>();
  const [targetPageId, setTargetPageId] = useState<number>();

  const bankQuestionsQuery = useQuery({
    queryKey: ['ai-bank-questions', sourceBankId],
    queryFn: () => get<Question[]>(`/api/banks/${sourceBankId}/questions`),
    enabled: sourceBankId !== undefined
  });
  const sourcePagesQuery = useQuery({
    queryKey: ['ai-source-pages', sourceNotebookId],
    queryFn: () => get<NotePage[]>(`/api/notebooks/${sourceNotebookId}/pages`),
    enabled: sourceNotebookId !== undefined
  });
  const sourcePageQuery = useQuery({
    queryKey: ['ai-source-page', sourcePageId],
    queryFn: () => get<NotePage>(`/api/note-pages/${sourcePageId}`),
    enabled: sourcePageId !== undefined
  });
  const targetPagesQuery = useQuery({
    queryKey: ['ai-target-pages', targetNotebookId],
    queryFn: () => get<NotePage[]>(`/api/notebooks/${targetNotebookId}/pages`),
    enabled: targetNotebookId !== undefined
  });

  useEffect(() => {
    if (!configQuery.data) return;
    setProvider(configQuery.data.provider || 'custom');
    setEndpoint(configQuery.data.endpoint || '');
    setModel(configQuery.data.model || '');
  }, [configQuery.data]);
  useEffect(() => {
    if (sourceBankId === undefined && banksQuery.data?.length) setSourceBankId(banksQuery.data[0].id);
  }, [banksQuery.data, sourceBankId]);
  useEffect(() => {
    if (sourceNotebookId === undefined && notebooksQuery.data?.length) setSourceNotebookId(notebooksQuery.data[0].id);
    if (targetNotebookId === undefined && notebooksQuery.data?.length) setTargetNotebookId(notebooksQuery.data[0].id);
  }, [notebooksQuery.data, sourceNotebookId, targetNotebookId]);
  useEffect(() => {
    if (sourcePageId === undefined && sourcePagesQuery.data?.length) setSourcePageId(sourcePagesQuery.data[0].id);
  }, [sourcePageId, sourcePagesQuery.data]);
  useEffect(() => {
    if (targetPageId === undefined && targetPagesQuery.data?.length) setTargetPageId(targetPagesQuery.data[0].id);
  }, [targetPageId, targetPagesQuery.data]);

  const saveMutation = useMutation({
    mutationFn: () => put<AiConfig>('/api/ai/config', { provider, endpoint, model, apiKey: apiKey || undefined }),
    onSuccess: () => { setApiKey(''); void queryClient.invalidateQueries({ queryKey: ['ai-config'] }); Message.success('AI 配置已保存，密钥已加密存储'); },
    onError: (error) => Message.error(error.message)
  });
  const chatMutation = useMutation({
    mutationFn: (request: ChatRequest) => post<{ reply: string }>('/api/ai/chat', { messages: [...messages.map(({ role, content }) => ({ role, content })), { role: 'user', content: request.content }] }),
    onSuccess: (result, request) => {
      setMessages((current) => [...current, { role: 'user', content: request.content, displayContent: request.displayContent }, { role: 'assistant', content: result.reply }]);
      setMessage('');
      setAttachments([]);
    },
    onError: (error) => Message.error(error.message)
  });
  const summarizeMutation = useMutation({
    mutationFn: () => post<{ summary: string }>('/api/ai/summarize', { text: summaryText }),
    onSuccess: (result) => setSummary(result.summary),
    onError: (error) => Message.error(error.message)
  });
  const insertSummaryMutation = useMutation({
    mutationFn: async () => {
      if (!summary.trim() || targetPageId === undefined) throw new Error('请先生成总结并选择目标笔记页面');
      const page = await get<NotePage>(`/api/note-pages/${targetPageId}`);
      return put(`/api/note-pages/${targetPageId}`, { content: appendMarkdownBlock(page.content, summary) });
    },
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['note-page', targetPageId] });
      Message.success('总结已作为可编辑 Markdown 题块插入笔记');
    },
    onError: (error) => Message.error(error.message)
  });

  const loadAttachment = async (file: File): Promise<void> => {
    try {
      if (file.size > 8 * 1024 * 1024) {
        Message.warning('单个附件不能超过 8 MB');
        return;
      }
      if (file.type.startsWith('image/')) {
        const value = await readAsDataUrl(file);
        setAttachments((current) => [...current, { id: `${file.name}-${file.lastModified}`, name: file.name, kind: 'image', value, mime: file.type }]);
        return;
      }
      if (!isTextFile(file)) {
        Message.warning('目前支持图片和文本类文件（Markdown、TXT、JSON、CSV、代码等）');
        return;
      }
      const value = (await file.text()).slice(0, 200_000);
      setAttachments((current) => [...current, { id: `${file.name}-${file.lastModified}`, name: file.name, kind: 'text', value, mime: file.type || 'text/plain' }]);
    } catch (error) {
      Message.error(error instanceof Error ? error.message : '附件读取失败');
    }
  };

  const readContext = (): string => {
    if (contextSource === 'wrong') {
      const questions = sourceQuestionId === 'all' ? wrongQuery.data ?? [] : (wrongQuery.data ?? []).filter((question) => String(question.id) === sourceQuestionId);
      return questionsToMarkdown(questions);
    }
    if (contextSource === 'bank') {
      const questions = sourceQuestionId === 'all' ? bankQuestionsQuery.data ?? [] : (bankQuestionsQuery.data ?? []).filter((question) => String(question.id) === sourceQuestionId);
      return questionsToMarkdown(questions);
    }
    if (contextSource === 'note') return sourcePageQuery.data ? notePageToMarkdown(sourcePageQuery.data) : '';
    return '';
  };

  const loadContext = (): void => {
    const context = readContext();
    if (!context) {
      Message.warning('没有可载入的内容，请先选择来源');
      return;
    }
    setSummaryText((current) => current.trim() ? `${current.trim()}\n\n---\n\n${context}` : context);
    Message.success('内容已载入总结输入框');
  };

  const send = (): void => {
    if (!message.trim() && !attachments.length) return;
    if (!configQuery.data?.hasKey) { Message.warning('请先保存 API Key'); return; }
    const textParts = [message.trim(), ...attachments.filter((item) => item.kind === 'text').map((item) => `[文件：${item.name}]\n${item.value}`)].filter(Boolean);
    const text = textParts.join('\n\n') || '请分析附件内容';
    const displayText = [message.trim(), ...attachments.map((item) => `[附件：${item.name}]`)].filter(Boolean).join('\n\n') || '请分析附件内容';
    const images = attachments.filter((item) => item.kind === 'image');
    const content: string | ChatContentPart[] = images.length ? [
      { type: 'text', text },
      ...images.map((item) => ({ type: 'image_url' as const, image_url: { url: item.value } }))
    ] : text;
    chatMutation.mutate({ content, displayContent: displayText });
  };

  const removeAttachment = (id: string): void => setAttachments((current) => current.filter((item) => item.id !== id));

  return <main className="page">
    <div className="page-heading"><div><h1>AI 工作台</h1><p>AI 请求只经由本地 Java 后端转发，支持 Markdown、LaTeX、题库/错题/笔记上下文和图片/文本附件。</p></div><Tag color={configQuery.data?.hasKey ? 'green' : 'orange'}>{configQuery.data?.hasKey ? '已配置密钥' : '未配置密钥'}</Tag></div>
    <div className="ai-layout">
      <section className="panel">
        <div className="panel-header"><h2>连接配置</h2></div>
        <div className="panel-body form-stack">
          {configQuery.isLoading ? <Spin /> : <>
            <Form layout="vertical">
              <Form.Item label="Provider"><Input value={provider} onChange={setProvider} placeholder="custom" /></Form.Item>
              <Form.Item label="Endpoint"><Input value={endpoint} onChange={setEndpoint} placeholder="https://api.example.com/v1" /></Form.Item>
              <Form.Item label="Model"><Input value={model} onChange={setModel} placeholder="模型名称" /></Form.Item>
              <Form.Item label="API Key"><Input.Password value={apiKey} onChange={setApiKey} placeholder={configQuery.data?.hasKey ? '已配置，留空表示不修改' : '输入 API Key'} /></Form.Item>
              <Button type="primary" loading={saveMutation.isPending} onClick={() => saveMutation.mutate()}>保存配置</Button>
            </Form>
            <Typography.Text type="secondary">保存后输入框会清空；后端数据库只保存 Argon2id + AES-256-GCM 密文。</Typography.Text>
          </>}
        </div>
      </section>
      <div className="form-stack">
        <section className="panel">
          <div className="panel-header"><h2><Sparkles size={17} style={{ verticalAlign: 'text-bottom', marginRight: 6 }} />对话</h2></div>
          <div className="chat-list">{messages.length ? messages.map((item, index) => <div key={`${item.role}-${index}`} className={`chat-message ${item.role}`}><MarkdownContent value={contentText(item.content, item.displayContent)} /></div>) : <div className="empty-state"><div><Sparkles size={30} /><p>配置 AI 后，在这里提问学习内容。</p></div></div>}</div>
          {attachments.length ? <div className="attachment-list">{attachments.map((item) => <div className="attachment-chip" key={item.id}>{item.kind === 'image' ? <img src={item.value} alt={item.name} /> : <Paperclip size={14} />}<span>{item.name}</span><Button type="text" size="mini" icon={<X size={14} />} onClick={() => removeAttachment(item.id)} aria-label={`移除${item.name}`} /></div>)}</div> : null}
          <div className="chat-compose"><TextArea autoSize={{ minRows: 2, maxRows: 5 }} value={message} onChange={setMessage} onPressEnter={(event) => { if (!event.shiftKey) { event.preventDefault(); send(); } }} placeholder="输入问题，Enter 发送；可添加图片或文本文件" /><input ref={fileInput} type="file" hidden multiple accept="image/*,.txt,.md,.markdown,.json,.csv,.log,.java,.kt,.ts,.tsx,.js,.jsx,.py,.go,.rs,.sql,.xml,.yaml,.yml,.css,.html" onChange={(event) => { const files = Array.from(event.target.files ?? []); event.target.value = ''; void Promise.all(files.map(loadAttachment)); }} /><Button type="secondary" icon={<Paperclip size={16} />} onClick={() => fileInput.current?.click()} aria-label="添加图片或文件" title="添加图片或文件" /><Button type="primary" icon={<Send size={16} />} loading={chatMutation.isPending} disabled={!configQuery.data?.hasKey} onClick={send} aria-label="发送" /></div>
        </section>
        <section className="panel">
          <div className="panel-header"><h2>内容总结</h2><div className="panel-header-actions"><Button type="text" icon={<Clipboard size={15} />} disabled={!summary} onClick={() => { void navigator.clipboard?.writeText(summary); Message.success('总结 Markdown 已复制'); }}>复制</Button><Button type="text" icon={<FilePlus2 size={15} />} loading={insertSummaryMutation.isPending} disabled={!summary || targetPageId === undefined} onClick={() => insertSummaryMutation.mutate()}>插入到笔记</Button><Button type="text" icon={<Sparkles size={15} />} loading={summarizeMutation.isPending} onClick={() => { if (!summaryText.trim()) Message.warning('请先载入或粘贴要总结的内容'); else summarizeMutation.mutate(); }}>生成总结</Button></div></div>
          <div className="panel-body form-stack">
            <div className="ai-context-picker">
              <Typography.Text type="secondary">从已有内容载入</Typography.Text>
              <div className="ai-context-row"><Select value={contextSource} onChange={(value) => { setContextSource(value as ContextSource); setSourceQuestionId('all'); }}><Select.Option value="manual">手动输入</Select.Option><Select.Option value="wrong">错题</Select.Option><Select.Option value="bank">题库</Select.Option><Select.Option value="note">笔记页面</Select.Option></Select>
                {contextSource === 'wrong' ? <Select value={sourceQuestionId} onChange={(value) => setSourceQuestionId(String(value))}><Select.Option value="all">全部错题</Select.Option>{questionOptions(wrongQuery.data ?? [])}</Select> : null}
                {contextSource === 'bank' ? <><Select placeholder="选择题库" value={sourceBankId} onChange={(value) => { setSourceBankId(Number(value)); setSourceQuestionId('all'); }}>{banksQuery.data?.map((bank) => <Select.Option key={bank.id} value={bank.id}>{bank.name}</Select.Option>)}</Select><Select value={sourceQuestionId} onChange={(value) => setSourceQuestionId(String(value))}><Select.Option value="all">题库全部题目</Select.Option>{questionOptions(bankQuestionsQuery.data ?? [])}</Select></> : null}
                {contextSource === 'note' ? <><Select placeholder="选择笔记本" value={sourceNotebookId} onChange={(value) => { setSourceNotebookId(Number(value)); setSourcePageId(undefined); }}>{notebooksQuery.data?.map((notebook) => <Select.Option key={notebook.id} value={notebook.id}>{notebook.title}</Select.Option>)}</Select><Select placeholder="选择页面" value={sourcePageId} onChange={(value) => setSourcePageId(Number(value))}>{sourcePagesQuery.data?.map((page) => <Select.Option key={page.id} value={page.id}>{page.title}</Select.Option>)}</Select></> : null}
                {contextSource !== 'manual' ? <Button type="secondary" onClick={loadContext}>载入到总结</Button> : <Typography.Text type="secondary">也可以直接在下方粘贴 Markdown 或 LaTeX。</Typography.Text>}
              </div>
            </div>
            <TextArea autoSize={{ minRows: 7, maxRows: 18 }} value={summaryText} onChange={setSummaryText} placeholder="粘贴笔记、题目解析，或从错题/题库/笔记页面载入…" />
            <div className="ai-note-target"><Typography.Text type="secondary">总结插入目标</Typography.Text><Select placeholder="笔记本" value={targetNotebookId} onChange={(value) => { setTargetNotebookId(Number(value)); setTargetPageId(undefined); }}>{notebooksQuery.data?.map((notebook) => <Select.Option key={notebook.id} value={notebook.id}>{notebook.title}</Select.Option>)}</Select><Select placeholder="页面" value={targetPageId} onChange={(value) => setTargetPageId(Number(value))}>{targetPagesQuery.data?.map((page) => <Select.Option key={page.id} value={page.id}>{page.title}</Select.Option>)}</Select></div>
            {summary ? <div className="ai-summary-result"><div className="ai-summary-label">Markdown 总结</div><MarkdownContent value={summary} /></div> : null}
          </div>
        </section>
      </div>
    </div>
  </main>;
}
