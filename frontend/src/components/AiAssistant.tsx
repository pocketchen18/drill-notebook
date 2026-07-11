import { useEffect, useMemo, useRef, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Button, Drawer, Input, Message, Select, Space, Tag, Typography } from '@arco-design/web-react';
import { FilePlus2, Paperclip, Send, Sparkles, X } from 'lucide-react';
import { get, post, put } from '../lib/api';
import type { AiConfig, ChatContentPart, ChatMessage, NotePage, Notebook } from '../lib/types';
import { appendMarkdownBlock } from '../lib/aiContext';
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
  const fileInput = useRef<HTMLInputElement>(null);
  const aiOpen = useUiStore((state) => state.aiOpen);
  const setAiOpen = useUiStore((state) => state.setAiOpen);
  const toggleAi = useUiStore((state) => state.toggleAi);
  const pageContext = useUiStore((state) => state.pageContext);
  const configQuery = useQuery({ queryKey: ['ai-config'], queryFn: () => get<AiConfig>('/api/ai/config') });
  const notebooksQuery = useQuery({ queryKey: ['notebooks'], queryFn: () => get<Notebook[]>('/api/notebooks'), enabled: aiOpen });

  const [message, setMessage] = useState('');
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const [attachments, setAttachments] = useState<Attachment[]>([]);
  const [usePageContext, setUsePageContext] = useState(true);
  const [targetNotebookId, setTargetNotebookId] = useState<number>();
  const [targetPageId, setTargetPageId] = useState<number>();
  const [pendingInsert, setPendingInsert] = useState<string>();

  const targetPagesQuery = useQuery({
    queryKey: ['ai-drawer-pages', targetNotebookId],
    queryFn: () => get<NotePage[]>(`/api/notebooks/${targetNotebookId}/pages`),
    enabled: targetNotebookId !== undefined && aiOpen
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
    const onKey = (event: KeyboardEvent): void => {
      if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 'j') {
        event.preventDefault();
        toggleAi();
      }
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [toggleAi]);

  const contextMarkdown = useMemo(
    () => (usePageContext && pageContext.markdown.trim() ? pageContext.markdown.trim() : ''),
    [pageContext.markdown, usePageContext]
  );

  const chatMutation = useMutation({
    mutationFn: (request: ChatRequest) => post<{ reply: string }>('/api/ai/chat', {
      messages: [
        ...(contextMarkdown
          ? [{ role: 'system' as const, content: `你是学习助手。请结合以下当前页面上下文回答，必要时用 Markdown 与 LaTeX。\n\n${contextMarkdown}` }]
          : []),
        ...messages.map(({ role, content }) => ({ role, content })),
        { role: 'user', content: request.content }
      ]
    }),
    onSuccess: (result, request) => {
      setMessages((current) => [
        ...current,
        { role: 'user', content: request.content, displayContent: request.displayContent },
        { role: 'assistant', content: result.reply }
      ]);
      setMessage('');
      setAttachments([]);
    },
    onError: (error) => Message.error(error.message)
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
        Message.warning('目前支持图片和文本类文件');
        return;
      }
      const value = (await file.text()).slice(0, 200_000);
      setAttachments((current) => [...current, { id: `${file.name}-${file.lastModified}`, name: file.name, kind: 'text', value, mime: file.type || 'text/plain' }]);
    } catch (error) {
      Message.error(error instanceof Error ? error.message : '附件读取失败');
    }
  };

  const send = (): void => {
    if (!message.trim() && !attachments.length) return;
    if (!configQuery.data?.hasKey) {
      Message.warning('请先在设置中配置 API Key');
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
        width={420}
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
      >
        <div className="ai-drawer-body">
          <div className={`ai-context-card${contextMarkdown ? ' has-context' : ''}`}>
            <div className="ai-context-card-top">
              <div>
                <div className="ai-context-kicker">当前上下文</div>
                <div className="ai-context-title">{pageContext.kind === 'none' ? '未绑定页面（可手动提问）' : pageContext.title}</div>
              </div>
              <label className="ai-context-toggle">
                <input type="checkbox" checked={usePageContext} onChange={(event) => setUsePageContext(event.target.checked)} />
                使用
              </label>
            </div>
            {contextMarkdown ? <pre className="ai-context-preview">{contextMarkdown.slice(0, 480)}{contextMarkdown.length > 480 ? '…' : ''}</pre> : <p className="muted">在刷题、错题或笔记页打开助手时，会自动带上当前内容。</p>}
          </div>

          <div className="ai-quick-prompts">
            {quickPrompts.map((item) => (
              <button key={item.label} type="button" className="ai-chip" disabled={!configQuery.data?.hasKey} onClick={() => {
                setMessage(item.text);
              }}>{item.label}</button>
            ))}
          </div>

          <div className="ai-drawer-chat">
            {messages.length ? messages.map((item, index) => {
              const text = contentText(item.content, item.displayContent);
              const isAssistant = item.role === 'assistant';
              return (
                <div key={`${item.role}-${index}`} className={`chat-message ${item.role}`}>
                  <MarkdownContent value={text} />
                  {isAssistant ? (
                    <div className="chat-message-actions">
                      <Button type="text" size="mini" icon={<FilePlus2 size={14} />} onClick={() => setPendingInsert(text)}>插入笔记</Button>
                    </div>
                  ) : null}
                </div>
              );
            }) : (
              <div className="empty-state ai-empty">
                <div>
                  <Sparkles size={28} />
                  <p>随时提问。回复右下角可一键插入笔记。</p>
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
            <Button type="primary" icon={<Send size={16} />} loading={chatMutation.isPending} disabled={!configQuery.data?.hasKey} onClick={send} aria-label="发送" />
          </div>
          {!configQuery.data?.hasKey ? <Typography.Text type="secondary" className="ai-drawer-hint">在「设置」中配置 Endpoint 与 API Key 后即可使用。</Typography.Text> : null}
        </div>
      </Drawer>
    </>
  );
}
