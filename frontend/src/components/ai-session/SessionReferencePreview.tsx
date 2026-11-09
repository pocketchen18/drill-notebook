import { useQuery } from '@tanstack/react-query';
import { Alert, Button, Modal, Spin } from '@arco-design/web-react';
import { get } from '../../lib/api';
import type { AiChatSession } from '../../lib/types';
import { useChatHistoryPages } from '../../hooks/useChatHistoryPages';
import { MarkdownContent } from '../markdown/MarkdownRenderer';
import './session-reference-preview.css';

/** 引用预览只读：不切换主聊天，不覆盖输入草稿，也不重发历史轮次。 */
export function SessionReferencePreview({ link, onClose, onSessionLink }: {
  link: string; onClose: () => void; onSessionLink: (link: string) => void;
}): JSX.Element {
  const session = useQuery({
    queryKey: ['ai-session-reference', link],
    queryFn: async () => {
      const rows = await get<AiChatSession[]>('/api/ai/sessions?includeArchived=true');
      const target = rows.find((row) => row.sessionLink === link);
      if (!target) throw new Error('会话不存在或已被删除');
      return target;
    },
    retry: false,
    staleTime: 0
  });
  const history = useChatHistoryPages(session.isSuccess ? session.data.id : undefined);
  const statuses: Record<string, string> = { FAILED: '回复失败', ABORTED: '已停止', INTERRUPTED: '已中断',
    TRUNCATED: '回复未完成', RUNNING: '回复中' };
  return <Modal visible title={<span style={{ overflowWrap: 'anywhere' }}>{session.data?.title ?? '会话记录'}</span>} footer={null} onCancel={onClose}
    style={{ width: 'min(720px, calc(100vw - 32px))' }} unmountOnExit>
    <div className="session-reference-preview">
      {session.isPending || history.isLoading ? <Spin tip="正在读取会话…" /> : null}
      {session.isError ? <Alert type="warning" content={session.error instanceof Error ? session.error.message : '会话暂时无法读取'}
        action={<Button size="mini" onClick={() => { void session.refetch(); }}>重试</Button>} /> : null}
      {history.isError ? <Alert type="warning" content="会话记录暂时无法读取"
        action={<Button size="mini" onClick={history.retry}>重试</Button>} /> : null}
      {session.isSuccess && !history.isLoading && !history.isError && history.messages.length === 0
        ? <p className="muted">此会话暂无已保存的消息。</p> : null}
      {history.hasOlder && !history.residentCapped ? <Button size="small" loading={history.isLoadingOlder}
        onClick={history.loadOlder}>加载更早的消息</Button> : null}
      {history.residentCapped ? <p className="muted">当前已展示 500 条消息，更早的记录仍保留在原会话中。</p> : null}
      {history.messages.map((message) => <article key={message.id} className="session-reference-message">
        <div className="session-reference-meta">
          <strong>{message.role === 'user' ? '你' : message.role === 'assistant' ? 'AI' : '系统'}</strong>
          {message.createdAt ? <time>{message.createdAt}</time> : null}
          {message.turnStatus && statuses[message.turnStatus] ? <span>{statuses[message.turnStatus]}</span> : null}
        </div>
        <MarkdownContent value={message.contentDeleted ? '消息内容已清理' : message.content} onSessionLink={onSessionLink} />
      </article>)}
    </div>
  </Modal>;
}
