import { useEffect, useMemo, useRef, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Button, Drawer, Dropdown, Input, Message, Select, Space, Tag, Typography } from '@arco-design/web-react';
import type { RefInputType } from '@arco-design/web-react/es/Input/interface';
import { Download, FilePlus2, MoreHorizontal, Paperclip, Plus, Send, Sparkles, Trash2, X } from 'lucide-react';
import { useNavigate } from 'react-router-dom';
import { del, get, post, put } from '../lib/api';
import { friendlyMessage } from '../lib/errors';
import type { AiChatSession, AiConfig, ChatCitation, ChatContentPart, ChatMessage, NotePage, Notebook, RetrievalNotice, RetrievalOptions } from '../lib/types';
import { appendMarkdownBlock } from '../lib/aiContext';
import { safeFileName } from '../lib/export';
import { LS_RETRIEVAL_SCOPE, LS_RETRIEVE_NOTES, readBoolPref, readStringPref, writeBoolPref, writeStringPref } from '../lib/sessionPrefs';
import { MarkdownContent } from './markdown/MarkdownRenderer';
import { useUiStore } from '../stores/uiStore';

const { TextArea } = Input;

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
  return content.map((part) => (part.type === 'text' ? part.text ?? '' : '[图片附件]')).filter(Boolean).join('\n');
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
  return file.type.startsWith('text/')
    || /\.(md|markdown|txt|json|csv|log|java|kt|ts|tsx|js|jsx|py|go|rs|sql|xml|yaml|yml|css|html)$/i.test(file.name);
}

export function AiAssistant(): JSX.Element {
  const queryClient = useQueryClient();
  const navigate = useNavigate();
  const fileInput = useRef<HTMLInputElement>(null);
  const aiOpen = useUiStore((state) => state.aiOpen);
  const setAiOpen = useUiStore((state) => state.setAiOpen);
  const toggleAi = useUiStore((state) => state.toggleAi);
  const pageContext = useUiStore((state) => state.pageContext);
  const configQuery = useQuery({
    queryKey: ['ai-config'],
    queryFn: async () => {
      const raw = await get<AiConfig>('/api/ai/config');
      // 兼容新旧 API：优先 chat 槽
      const chat = raw.chat ?? raw;
      return { ...raw, hasKey: chat.hasKey, provider: chat.provider, endpoint: chat.endpoint, model: chat.model, chat };
    }
  });
  const notebooksQuery = useQuery({ queryKey: ['notebooks'], queryFn: () => get<Notebook[]>('/api/notebooks'), enabled: aiOpen });
  const sessionsQuery = useQuery({
    queryKey: ['ai-sessions'],
    queryFn: () => get<AiChatSession[]>('/api/ai/sessions'),
    enabled: aiOpen
  });

  const [sessionId, setSessionId] = useState<number>();
  const [message, setMessage] = useState('');
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const [attachments, setAttachments] = useState<Attachment[]>([]);
  const [usePageContext, setUsePageContext] = useState(true);
  const [retrieveNotes, setRetrieveNotes] = useState(() => readBoolPref(LS_RETRIEVE_NOTES, false));
  const [retrievalScope, setRetrievalScope] = useState<'current' | 'all'>(() => {
    const saved = readStringPref(LS_RETRIEVAL_SCOPE, '');
    if (saved === 'current' || saved === 'all') return saved;
    return useUiStore.getState().pageContext.notebookId !== undefined ? 'current' : 'all';
  });
  const [targetNotebookId, setTargetNotebookId] = useState<number>();
  const [targetPageId, setTargetPageId] = useState<number>();
  const [pendingInsert, setPendingInsert] = useState<string>();
  const [exporting, setExporting] = useState(false);
  const [renaming, setRenaming] = useState(false);
  const [renameDraft, setRenameDraft] = useState('');
  const renameInputRef = useRef<RefInputType>(null);

  const targetPagesQuery = useQuery({
    queryKey: ['ai-drawer-pages', targetNotebookId],
    queryFn: () => get<NotePage[]>(`/api/notebooks/${targetNotebookId}/pages`),
    enabled: targetNotebookId !== undefined && aiOpen
  });

  const messagesQuery = useQuery({
    queryKey: ['ai-session-messages', sessionId],
    queryFn: () => get<ChatMessage[]>(`/api/ai/sessions/${sessionId}/messages`),
    enabled: aiOpen && sessionId !== undefined
  });

  useEffect(() => {
    if (!aiOpen) return;
    if (pageContext.notebookId !== undefined) setTargetNotebookId(pageContext.notebookId);
    if (pageContext.notePageId !== undefined) setTargetPageId(pageContext.notePageId);
  }, [aiOpen, pageContext.notebookId, pageContext.notePageId]);

  useEffect(() => {
    if (targetNotebookId === undefined && notebooksQuery.data?.length) setTargetNotebookId(notebooksQuery.data[0].id);
  }, [notebooksQuery.data, targetNotebookId]);

  useEffect(() => {
    if (targetPageId === undefined && targetPagesQuery.data?.length) setTargetPageId(targetPagesQuery.data[0].id);
  }, [targetPageId, targetPagesQuery.data]);

  useEffect(() => {
    if (!sessionsQuery.data?.length) return;
    if (sessionId === undefined || !sessionsQuery.data.some((item) => item.id === sessionId)) {
      setSessionId(sessionsQuery.data[0].id);
    }
  }, [sessionId, sessionsQuery.data]);

  useEffect(() => {
    if (!messagesQuery.data) return;
    setMessages(messagesQuery.data.map((item) => ({
      id: item.id,
      role: item.role,
      content: item.content,
      displayContent: typeof item.content === 'string' ? item.content : undefined,
      createdAt: item.createdAt
    })));
  }, [messagesQuery.data]);

  useEffect(() => {
    const onKey = (event: KeyboardEvent): void => {
      if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 'j') {
        event.preventDefault();
        toggleAi();
      }
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [toggleAi]);

  // 开启笔记检索且当前上下文本身就是笔记页时，不再重复注入整页 markdown（后端会按需检索）。
  const suppressPageMarkdown = retrieveNotes && pageContext.kind === 'note';

  const contextMarkdown = useMemo(
    () => (usePageContext && !suppressPageMarkdown && pageContext.markdown.trim() ? pageContext.markdown.trim() : ''),
    [pageContext.markdown, suppressPageMarkdown, usePageContext]
  );

  // Task 14：进入无当前 notebook 的页面时，current 自动降级为 all，保证绝不发送缺 notebookId 的 current。
  useEffect(() => {
    if (pageContext.notebookId === undefined && retrievalScope === 'current') setRetrievalScope('all');
  }, [pageContext.notebookId, retrievalScope]);

  const onToggleRetrieveNotes = (checked: boolean): void => {
    setRetrieveNotes(checked);
    writeBoolPref(LS_RETRIEVE_NOTES, checked);
    if (checked) {
      // 开启时若无显式持久化选择，按当前上下文推断默认范围：note 页→current，其他→all。
      const saved = readStringPref(LS_RETRIEVAL_SCOPE, '');
      if (saved !== 'current' && saved !== 'all') {
        setRetrievalScope(pageContext.notebookId !== undefined ? 'current' : 'all');
      }
    }
  };

  const onChangeRetrievalScope = (value: 'current' | 'all'): void => {
    setRetrievalScope(value);
    writeStringPref(LS_RETRIEVAL_SCOPE, value);
  };

  // Task 8/14：开启时发送 enabled/scope/notebookId；关闭时完全不携带该字段，保持旧请求契约。
  const retrievalOptions = useMemo<RetrievalOptions | undefined>(() => {
    if (!retrieveNotes) return undefined;
    if (retrievalScope === 'current' && pageContext.notebookId !== undefined) {
      return { enabled: true, scope: 'current', notebookId: pageContext.notebookId };
    }
    return { enabled: true, scope: 'all' };
  }, [pageContext.notebookId, retrievalScope, retrieveNotes]);

  const createSessionMutation = useMutation({
    mutationFn: () => post<AiChatSession>('/api/ai/sessions', { title: '新会话' }),
    onSuccess: (session) => {
      void queryClient.invalidateQueries({ queryKey: ['ai-sessions'] });
      setSessionId(session.id);
      setMessages([]);
      Message.success('已新建会话');
    },
    onError: (error) => Message.error(error.message)
  });

  const renameSessionMutation = useMutation({
    mutationFn: ({ id, title }: { id: number; title: string }) => put<AiChatSession>(`/api/ai/sessions/${id}`, { title }),
    onSuccess: (session) => {
      void queryClient.invalidateQueries({ queryKey: ['ai-sessions'] });
      setSessionId(session.id);
      setRenaming(false);
      Message.success('会话已重命名');
    },
    onError: (error) => Message.error(error.message)
  });

  const currentSession = sessionsQuery.data?.find((item) => item.id === sessionId);

  useEffect(() => {
    if (!renaming) return;
    const timer = window.setTimeout(() => {
      const input = renameInputRef.current?.dom;
      if (!input) return;
      input.focus();
      input.select();
    }, 0);
    return () => window.clearTimeout(timer);
  }, [renaming]);

  const beginRename = (): void => {
    if (sessionId === undefined || renameSessionMutation.isPending) return;
    setRenameDraft(currentSession?.title ?? '');
    setRenaming(true);
  };

  const cancelRename = (): void => {
    setRenaming(false);
    setRenameDraft('');
  };

  const commitRename = (): void => {
    if (sessionId === undefined || renameSessionMutation.isPending) return;
    const title = renameDraft.trim();
    if (!title) {
      Message.warning('标题不能为空');
      const input = renameInputRef.current?.dom;
      input?.focus();
      input?.select();
      return;
    }
    if (title === (currentSession?.title ?? '')) {
      cancelRename();
      return;
    }
    renameSessionMutation.mutate({ id: sessionId, title });
  };

  const deleteSessionMutation = useMutation({
    mutationFn: (id: number) => del(`/api/ai/sessions/${id}`),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['ai-sessions'] });
      setSessionId(undefined);
      setMessages([]);
      Message.success('会话已删除');
    },
    onError: (error) => Message.error(error.message)
  });

  const chatMutation = useMutation({
    mutationFn: (request: ChatRequest) => post<{ reply: string; sessionId: number; citations?: ChatCitation[]; retrievalNotice?: RetrievalNotice }>('/api/ai/chat', {
      sessionId,
      messages: [
        ...(contextMarkdown
          ? [{ role: 'system' as const, content: `你是学习助手。请结合以下当前页面上下文回答，必要时用 Markdown 与 LaTeX。\n\n${contextMarkdown}` }]
          : []),
        ...messages.map(({ role, content }) => ({ role, content })),
        { role: 'user', content: request.content }
      ],
      ...(retrievalOptions ? { retrievalOptions } : {})
    }),
    onSuccess: (result, request) => {
      if (result.sessionId && result.sessionId !== sessionId) setSessionId(result.sessionId);
      setMessages((current) => [
        ...current,
        { role: 'user', content: request.content, displayContent: request.displayContent },
        { role: 'assistant', content: result.reply, citations: result.citations, notice: result.retrievalNotice }
      ]);
      setMessage('');
      setAttachments([]);
      void queryClient.invalidateQueries({ queryKey: ['ai-sessions'] });
      void queryClient.invalidateQueries({ queryKey: ['ai-session-messages', result.sessionId ?? sessionId] });
    },
    onError: (error) => Message.error(friendlyMessage(error, 'AI 对话失败，请稍后重试'))
  });

  const insertMutation = useMutation({
    mutationFn: async (markdown: string) => {
      if (targetPageId === undefined) throw new Error('请选择目标笔记页面');
      const page = await get<NotePage>(`/api/note-pages/${targetPageId}`);
      return put(`/api/note-pages/${targetPageId}`, { content: appendMarkdownBlock(page.content, markdown) });
    },
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['note-page', targetPageId] });
      void queryClient.invalidateQueries({ queryKey: ['note-pages'] });
      setPendingInsert(undefined);
      Message.success('已插入到笔记（可编辑 Markdown 块）');
    },
    onError: (error) => Message.error(friendlyMessage(error, '插入笔记失败，请稍后重试'))
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
        Message.warning('目前支持图片和文本类文件');
        return;
      }
      const value = (await file.text()).slice(0, 200_000);
      setAttachments((current) => [...current, { id: `${file.name}-${file.lastModified}`, name: file.name, kind: 'text', value, mime: file.type || 'text/plain' }]);
    } catch (error) {
      Message.error(friendlyMessage(error, '附件读取失败'));
    }
  };

  const send = (): void => {
    if (!message.trim() && !attachments.length) return;
    if (!configQuery.data?.hasKey) {
      Message.warning('请先在设置中配置 API Key');
      return;
    }
    if (sessionId === undefined) {
      Message.warning('请先选择或新建会话');
      return;
    }
    const textParts = [
      message.trim(),
      ...attachments.filter((item) => item.kind === 'text').map((item) => `[文件：${item.name}]\n${item.value}`)
    ].filter(Boolean);
    const text = textParts.join('\n\n') || '请分析附件内容';
    const displayText = [message.trim(), ...attachments.map((item) => `[附件：${item.name}]`)].filter(Boolean).join('\n\n') || '请分析附件内容';
    const images = attachments.filter((item) => item.kind === 'image');
    const content: string | ChatContentPart[] = images.length
      ? [{ type: 'text', text }, ...images.map((item) => ({ type: 'image_url' as const, image_url: { url: item.value } }))]
      : text;
    chatMutation.mutate({ content, displayContent: displayText });
  };

  const exportSession = async (format: 'md' | 'html' | 'json'): Promise<void> => {
    if (sessionId === undefined) {
      Message.warning('请先选择会话');
      return;
    }
    if (!window.api) {
      Message.error('导出功能仅在桌面应用中可用');
      return;
    }
    setExporting(true);
    try {
      const payload = await get<{ title: string; content: string; format: string }>(`/api/ai/sessions/${sessionId}/export?format=${format}`);
      const extension = format === 'json' ? 'json' : format;
      const result = await window.api.exportFile.save({
        format: format === 'json' ? 'md' : format,
        suggestedName: `${safeFileName(payload.title)}.${extension}`,
        content: payload.content,
        html: format === 'html' ? payload.content : payload.content
      });
      if (!result.canceled) Message.success(`已导出到 ${result.path ?? '所选位置'}`);
    } catch (error) {
      Message.error(error instanceof Error ? error.message : '导出会话失败');
    } finally {
      setExporting(false);
    }
  };

  const quickPrompts = [
    { label: '讲解当前内容', text: '请用通俗语言讲解当前页面的重点，并给出 3 个记忆要点。' },
    { label: '出练习题', text: '根据当前内容出 3 道选择题（含答案与简短解析），用 Markdown。' },
    { label: '总结成笔记', text: '把当前内容整理成结构化学习笔记（标题+要点+易错点）。' }
  ];

  return (
    <>
      <button type="button" className={`ai-fab${aiOpen ? ' is-open' : ''}`} onClick={() => setAiOpen(!aiOpen)} title="AI 助手 (Ctrl+J)" aria-label="打开 AI 助手">
        <Sparkles size={22} />
      </button>
      <Drawer
        width={460}
        title={
          <div className="ai-drawer-title">
            <Sparkles size={16} />
            <span>AI 学习助手</span>
            <Tag size="small" color={configQuery.data?.hasKey ? 'green' : 'orange'}>{configQuery.data?.hasKey ? '已配置' : '未配置'}</Tag>
          </div>
        }
        visible={aiOpen}
        onCancel={() => setAiOpen(false)}
        footer={null}
        unmountOnExit={false}
        className="ai-drawer"
        // Mount inside app root so CSS variables + theme cascade apply reliably
        getPopupContainer={() => document.getElementById('root') ?? document.body}
      >
        <div className="ai-drawer-body">
          <div className="ai-session-bar">
            {renaming ? (
              <Input
                ref={renameInputRef}
                size="small"
                className="ai-session-title-input"
                value={renameDraft}
                disabled={renameSessionMutation.isPending}
                onChange={setRenameDraft}
                onBlur={() => commitRename()}
                onPressEnter={(event) => {
                  event.preventDefault();
                  commitRename();
                }}
                onKeyDown={(event) => {
                  if (event.key === 'Escape') {
                    event.preventDefault();
                    cancelRename();
                  }
                }}
                aria-label="重命名会话"
              />
            ) : (
              <button
                type="button"
                className="ai-session-title"
                title="双击重命名"
                disabled={sessionId === undefined}
                onDoubleClick={(event) => {
                  event.preventDefault();
                  beginRename();
                }}
              >
                <span className="ai-session-title-text">{currentSession?.title ?? (sessionsQuery.isLoading ? '加载会话…' : '选择会话')}</span>
                {currentSession?.messageCount ? <span className="ai-session-title-count">{currentSession.messageCount}</span> : null}
              </button>
            )}
            <Select
              size="small"
              placeholder="切换"
              value={sessionId}
              onChange={(value) => {
                setRenaming(false);
                setSessionId(Number(value));
              }}
              style={{ width: 108 }}
              loading={sessionsQuery.isLoading}
              aria-label="切换会话"
            >
              {sessionsQuery.data?.map((session) => (
                <Select.Option key={session.id} value={session.id}>
                  {session.title}{session.messageCount ? `（${session.messageCount}）` : ''}
                </Select.Option>
              ))}
            </Select>
            <Button size="small" type="outline" icon={<Plus size={14} />} loading={createSessionMutation.isPending} onClick={() => createSessionMutation.mutate()}>新建</Button>
            <Dropdown
              droplist={
                <div className="ai-session-menu">
                  <button type="button" disabled={exporting || sessionId === undefined} onClick={() => void exportSession('md')}>导出 Markdown</button>
                  <button type="button" disabled={exporting || sessionId === undefined} onClick={() => void exportSession('html')}>导出 HTML</button>
                  <button type="button" disabled={exporting || sessionId === undefined} onClick={() => void exportSession('json')}>导出 JSON</button>
                  <button
                    type="button"
                    className="danger"
                    disabled={deleteSessionMutation.isPending || !sessionId || (sessionsQuery.data?.length ?? 0) <= 1}
                    onClick={() => {
                      if (sessionId === undefined) return;
                      if (!window.confirm(`删除会话「${currentSession?.title ?? ''}」？消息将一并删除。`)) return;
                      deleteSessionMutation.mutate(sessionId);
                    }}
                  >
                    <Trash2 size={13} /> 删除会话
                  </button>
                </div>
              }
              position="br"
            >
              <Button size="small" type="text" icon={<MoreHorizontal size={16} />} aria-label="会话更多操作" />
            </Dropdown>
          </div>

          <div className={`ai-context-card${contextMarkdown ? ' has-context' : ''}`}>
            <div className="ai-context-card-top">
              <div>
                <div className="ai-context-kicker">当前上下文</div>
                <div className="ai-context-title">{pageContext.kind === 'none' ? '未绑定页面（可手动提问）' : pageContext.title}</div>
              </div>
              <Space size={8}>
                <label className="ai-context-toggle" title="发送时检索相关笔记片段并返回引用">
                  <input type="checkbox" checked={retrieveNotes} onChange={(event) => onToggleRetrieveNotes(event.target.checked)} />
                  检索笔记
                </label>
                <label className="ai-context-toggle">
                  <input type="checkbox" checked={usePageContext} onChange={(event) => setUsePageContext(event.target.checked)} />
                  使用
                </label>
              </Space>
            </div>
            {retrieveNotes ? (
              <div className="ai-retrieval-scope">
                <Typography.Text type="secondary">检索范围</Typography.Text>
                <Select
                  size="small"
                  value={retrievalScope}
                  onChange={(value) => onChangeRetrievalScope(value as 'current' | 'all')}
                  style={{ width: 150 }}
                  aria-label="检索范围"
                >
                  <Select.Option value="current" disabled={pageContext.notebookId === undefined}>当前笔记本</Select.Option>
                  <Select.Option value="all">全部笔记本</Select.Option>
                </Select>
              </div>
            ) : null}
            {contextMarkdown ? <pre className="ai-context-preview">{contextMarkdown.slice(0, 480)}{contextMarkdown.length > 480 ? '…' : ''}</pre> : <p className="muted">{suppressPageMarkdown ? '已启用笔记检索：不再整页注入，发送时自动检索相关片段并返回引用。' : '在刷题、错题或笔记页打开助手时，会自动带上当前内容。'}</p>}
          </div>

          <div className="ai-quick-prompts">
            {quickPrompts.map((item) => (
              <button key={item.label} type="button" className="ai-chip" disabled={!configQuery.data?.hasKey} onClick={() => {
                setMessage(item.text);
              }}>{item.label}</button>
            ))}
          </div>

          <div className="ai-drawer-chat">
            {messagesQuery.isLoading ? (
              <div className="empty-state ai-empty"><p>加载会话消息…</p></div>
            ) : messages.length ? messages.map((item, index) => {
              const text = contentText(item.content, item.displayContent);
              const isAssistant = item.role === 'assistant';
              return (
                <div key={`${item.role}-${item.id ?? index}`} className={`chat-message ${item.role}`}>
                  <MarkdownContent value={text} />
                  {isAssistant ? (
                    <>
                      {item.notice ? (
                        <div className="ai-retrieval-notice">
                          {item.notice.message ?? '笔记检索暂时不可用，本次回答未使用笔记内容'}
                        </div>
                      ) : null}
                      {item.citations?.length ? (
                        <div className="ai-citations">
                          <div className="ai-citations-kicker">笔记引用（{item.citations.length}）</div>
                          <div className="ai-citation-list">
                            {item.citations.map((citation, citationIndex) => (
                              <button
                                key={`${citation.pageId}-${citation.chunkId}`}
                                type="button"
                                className="ai-citation-chip"
                                title={citation.snippet}
                                onClick={() => navigate(`/notebooks?pageId=${citation.pageId}`)}
                              >
                                <span className="ai-citation-index">{citationIndex + 1}</span>
                                <span className="ai-citation-title">{citation.title}</span>
                                {citation.headingPath ? <span className="ai-citation-heading">{citation.headingPath}</span> : null}
                              </button>
                            ))}
                          </div>
                        </div>
                      ) : null}
                      <div className="chat-message-actions">
                        <Button type="text" size="mini" icon={<FilePlus2 size={14} />} onClick={() => setPendingInsert(text)}>插入笔记</Button>
                      </div>
                    </>
                  ) : null}
                </div>
              );
            }) : (
              <div className="empty-state ai-empty">
                <div>
                  <Sparkles size={28} />
                  <p>当前会话还没有消息。可新建多个会话分别保存不同主题的对话。</p>
                </div>
              </div>
            )}
          </div>

          {pendingInsert ? (
            <div className="ai-insert-bar">
              <Typography.Text type="secondary">插入到</Typography.Text>
              <Select size="small" placeholder="笔记本" value={targetNotebookId} onChange={(value) => { setTargetNotebookId(Number(value)); setTargetPageId(undefined); }} style={{ width: 120 }}>
                {notebooksQuery.data?.map((notebook) => <Select.Option key={notebook.id} value={notebook.id}>{notebook.title}</Select.Option>)}
              </Select>
              <Select size="small" placeholder="页面" value={targetPageId} onChange={(value) => setTargetPageId(Number(value))} style={{ width: 120 }}>
                {targetPagesQuery.data?.map((page) => <Select.Option key={page.id} value={page.id}>{page.title}</Select.Option>)}
              </Select>
              <Space size={4}>
                <Button size="mini" type="primary" loading={insertMutation.isPending} onClick={() => pendingInsert && insertMutation.mutate(pendingInsert)}>确认插入</Button>
                <Button size="mini" type="text" onClick={() => setPendingInsert(undefined)}>取消</Button>
              </Space>
            </div>
          ) : null}

          {attachments.length ? (
            <div className="attachment-list">
              {attachments.map((item) => (
                <div className="attachment-chip" key={item.id}>
                  {item.kind === 'image' ? <img src={item.value} alt={item.name} /> : <Paperclip size={14} />}
                  <span>{item.name}</span>
                  <Button type="text" size="mini" icon={<X size={14} />} onClick={() => setAttachments((current) => current.filter((entry) => entry.id !== item.id))} aria-label={`移除${item.name}`} />
                </div>
              ))}
            </div>
          ) : null}

          <div className="chat-compose ai-drawer-compose">
            <TextArea
              autoSize={{ minRows: 2, maxRows: 6 }}
              value={message}
              onChange={setMessage}
              onPressEnter={(event) => {
                if (!event.shiftKey) {
                  event.preventDefault();
                  send();
                }
              }}
              placeholder="提问… Enter 发送 · Shift+Enter 换行"
            />
            <input
              ref={fileInput}
              type="file"
              hidden
              multiple
              accept="image/*,.txt,.md,.markdown,.json,.csv,.log,.java,.kt,.ts,.tsx,.js,.jsx,.py,.go,.rs,.sql,.xml,.yaml,.yml,.css,.html"
              onChange={(event) => {
                const files = Array.from(event.target.files ?? []);
                event.target.value = '';
                void Promise.all(files.map(loadAttachment));
              }}
            />
            <Button type="secondary" icon={<Paperclip size={16} />} onClick={() => fileInput.current?.click()} aria-label="添加附件" />
            <Button type="primary" icon={<Send size={16} />} loading={chatMutation.isPending} disabled={!configQuery.data?.hasKey || sessionId === undefined} onClick={send} aria-label="发送" />
          </div>
          {!configQuery.data?.hasKey ? <Typography.Text type="secondary" className="ai-drawer-hint">在「设置」中配置 Endpoint 与 API Key 后即可使用。</Typography.Text> : null}
          {exporting ? <Typography.Text type="secondary" className="ai-drawer-hint"><Download size={12} style={{ marginRight: 4 }} />正在导出会话…</Typography.Text> : null}
        </div>
      </Drawer>
    </>
  );
}
