import { useRef, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Alert, Button, Empty, Message, Modal, Select, Space, Spin, Tag, Typography } from '@arco-design/web-react';
import { ApiError } from '../../lib/api';
import { friendlyMessage } from '../../lib/errors';
import {
  listMemoryProposals,
  reviewMemoryCandidate,
  reviewMemoryProposal,
  searchMemoryItems
} from '../../lib/aiMemoryApi';
import type { CandidateDecision, MemoryItem, MemoryProposal, ProposalDecision } from '../../lib/aiMemoryTypes';

/**
 * 待确认审阅（§12）：把"替换/撤销建议"与"待确认新记忆"两堆放在一处决定。
 *
 * <p>卡片必须同时摊开新值、当前值、原话、作用域与为什么待确认——少任何一项，用户都是在猜模型的意思。
 * 「分别保留」只有服务端认定为多值的类别才给；「替换」只由人点名旧目标，服务端从不猜。
 * 409/过期/换代不静默重试：把服务端原因摊出来，给一个显式的重新读取。</p>
 */

const PAGE_SIZE = 20;

/** 这些 code 的意思都是"你看到的那一版已经旧了"，界面上要给出重新读取的出口。 */
const STALE_CODES = new Set([
  'MEMORY_VERSION_CONFLICT',
  'MEMORY_PROPOSAL_EXPIRED',
  'MEMORY_PROPOSAL_STALE',
  'MEMORY_PROPOSAL_NOT_FOUND',
  'MEMORY_PAGE_STALE',
  'MEMORY_ITEM_NOT_FOUND',
  'MEMORY_TARGET_NOT_FOUND'
]);

export interface MemoryReviewPanelProps {
  /** 未解锁时不请求：§3 末段规定正文只在已解锁的响应里出现，423 不能显示成"没有待确认"。 */
  enabled: boolean;
  notebooks: Array<{ value: number; label: string }>;
}

interface ReviewSubject {
  key: string;
  id: string;
  source: 'proposal' | 'candidate';
  version: number;
  title: string;
  newValue?: string | null;
  currentValue?: string | null;
  currentVersion?: number;
  currentMissing?: boolean;
  quote?: string | null;
  scope: string;
  scopeType: 'GLOBAL' | 'NOTEBOOK';
  scopeId: number;
  reason: string;
  multiValued: boolean;
  retract: boolean;
  corrupt: boolean;
  proposedAgainst?: { memoryId: string; version: number };
}

interface ReviewFailure {
  message: string;
  stale: boolean;
}

interface ReviewRequest {
  subject: ReviewSubject;
  decision: CandidateDecision;
  target?: { memoryId: string; version: number };
}

function newOperationId(): string {
  return globalThis.crypto?.randomUUID?.() ?? `review-${Date.now()}-${Math.random().toString(36).slice(2)}`;
}

function scopeLabel(scopeType: 'GLOBAL' | 'NOTEBOOK' | undefined, scopeId: number | undefined,
                   notebooks: Array<{ value: number; label: string }>): string {
  if (scopeType === 'NOTEBOOK') {
    const hit = notebooks.find((notebook) => notebook.value === scopeId);
    return `笔记本：${hit?.label ?? `#${scopeId ?? 0}`}`;
  }
  return '全局';
}

function fromProposal(row: MemoryProposal, notebooks: MemoryReviewPanelProps['notebooks']): ReviewSubject {
  return {
    key: `proposal:${row.proposalId}`,
    id: row.proposalId,
    source: 'proposal',
    version: row.version,
    title: row.operation === 'RETRACT_PROPOSAL' ? '建议忘记一条记忆' : '建议换一种说法',
    newValue: row.corrupt ? undefined : row.newValue ?? null,
    currentValue: row.target?.value ?? null,
    currentVersion: row.target?.version,
    currentMissing: row.target == null,
    quote: row.quote ?? null,
    scope: scopeLabel(row.scopeType, row.scopeId, notebooks),
    scopeType: row.scopeType ?? 'GLOBAL',
    scopeId: row.scopeId ?? 0,
    reason: row.corrupt
      ? '这条建议的密文读不出来，任何决定都做不了，只能等来源重新整理或清空全部记忆'
      : row.reviewReasonText ?? row.reviewReason ?? '模型建议先给你看一眼',
    multiValued: row.multiValued === true,
    retract: row.operation === 'RETRACT_PROPOSAL',
    corrupt: row.corrupt
  };
}

function fromCandidate(row: MemoryItem, notebooks: MemoryReviewPanelProps['notebooks']): ReviewSubject {
  return {
    key: `candidate:${row.memoryId}`,
    id: row.memoryId,
    source: 'candidate',
    version: row.version,
    title: '新的待确认记忆',
    newValue: row.corrupt ? undefined : row.value ?? null,
    currentValue: null,
    currentMissing: row.proposedAgainstMemoryId == null,
    quote: row.sources?.[0]?.quote ?? null,
    scope: scopeLabel(row.scopeType, row.scopeId, notebooks),
    scopeType: row.scopeType,
    scopeId: row.scopeId,
    reason: row.corrupt
      ? '这一行密文不可用，采用或拒绝都会失败，可用「清空全部记忆」清掉'
      : row.reviewReasonText ?? row.reviewReason ?? '模型建议先给你看一眼',
    multiValued: row.multiValued === true,
    retract: false,
    corrupt: row.corrupt,
    proposedAgainst: row.proposedAgainstMemoryId
      ? { memoryId: row.proposedAgainstMemoryId, version: row.proposedAgainstVersion ?? 0 }
      : undefined
  };
}

export function MemoryReviewPanel(props: MemoryReviewPanelProps): JSX.Element {
  const queryClient = useQueryClient();
  const [proposalCursor, setProposalCursor] = useState<string[]>([]);
  const [candidateCursor, setCandidateCursor] = useState<string[]>([]);
  const [failures, setFailures] = useState<Record<string, ReviewFailure>>({});
  const [replaceTarget, setReplaceTarget] = useState<ReviewSubject | null>(null);
  const [pickedTarget, setPickedTarget] = useState<string>('');
  // 弹窗关掉后把焦点还给触发它的按钮，键盘用户才不会掉回页面顶部
  const triggerRef = useRef<HTMLElement | null>(null);

  const proposalQuery = useQuery({
    queryKey: ['ai-memory-proposals', { cursor: proposalCursor[proposalCursor.length - 1] ?? '' }],
    queryFn: () => listMemoryProposals({
      limit: PAGE_SIZE,
      cursor: proposalCursor[proposalCursor.length - 1]
    }),
    enabled: props.enabled
  });
  const candidateQuery = useQuery({
    queryKey: ['ai-memory-candidates', { cursor: candidateCursor[candidateCursor.length - 1] ?? '' }],
    queryFn: () => searchMemoryItems({
      status: 'PENDING',
      limit: PAGE_SIZE,
      cursor: candidateCursor[candidateCursor.length - 1]
    }),
    enabled: props.enabled
  });
  // 替换选择器的候选：只在弹窗开着时才取
  const replaceCandidatesQuery = useQuery({
    queryKey: ['ai-memory-active-for-replace', replaceTarget?.scopeType ?? '', replaceTarget?.scopeId ?? 0],
    queryFn: () => searchMemoryItems({
      status: 'ACTIVE',
      scopeType: replaceTarget?.scopeType,
      scopeId: replaceTarget?.scopeType === 'NOTEBOOK' ? replaceTarget?.scopeId : undefined,
      limit: 50
    }),
    enabled: replaceTarget != null
  });

  const subjects: ReviewSubject[] = [
    ...(proposalQuery.data?.proposals ?? []).map((row) => fromProposal(row, props.notebooks)),
    ...(candidateQuery.data?.items ?? []).map((row) => fromCandidate(row, props.notebooks))
  ];

  // 预选的那条旧目标可能已经不生效：找不到就不给按"替换"，否则点下去只是静默什么都不做
  const replaceChosen = (replaceCandidatesQuery.data?.items ?? []).find((item) => item.memoryId === pickedTarget);

  const refreshAll = (): void => {
    void queryClient.invalidateQueries({ queryKey: ['ai-memory-proposals'] });
    void queryClient.invalidateQueries({ queryKey: ['ai-memory-candidates'] });
    void queryClient.invalidateQueries({ queryKey: ['ai-memory-list'] });
    void queryClient.invalidateQueries({ queryKey: ['ai-memory-status'] });
  };

  const mutation = useMutation({
    mutationFn: (request: ReviewRequest) => {
      const operationId = newOperationId();
      if (request.subject.source === 'proposal') {
        const decision = request.decision as ProposalDecision;
        return reviewMemoryProposal(request.subject.id, {
          expectedVersion: request.subject.version,
          decision,
          // 采纳与并存都要带上"当前值"的版本；拒绝不动事实，所以不必带
          expectedTargetVersion: decision === 'reject' ? undefined : request.subject.currentVersion,
          operationId
        });
      }
      return reviewMemoryCandidate(request.subject.id, {
        expectedVersion: request.subject.version,
        decision: request.decision,
        targetMemoryId: request.target?.memoryId,
        expectedTargetVersion: request.target?.version,
        operationId
      });
    },
    onSuccess: (result, request) => {
      setFailures((current) => {
        const next = { ...current };
        delete next[request.subject.key];
        return next;
      });
      setReplaceTarget(null);
      setPickedTarget('');
      refreshAll();
      if (result.changed === false) {
        Message.info('这一条已经处理过了，列表已重新读取');
        return;
      }
      Message.success(request.decision === 'reject' ? '已保留当前值，这句原话不会再被反复提起' : '已按你的选择更新记忆');
    },
    onError: (error, request) => {
      const stale = error instanceof ApiError && STALE_CODES.has(error.code);
      setFailures((current) => ({ ...current, [request.subject.key]: { message: friendlyMessage(error, '审阅失败'), stale } }));
      setReplaceTarget(null);
    }
  });

  const decide = (subject: ReviewSubject, decision: CandidateDecision, trigger?: EventTarget | null): void => {
    if (trigger instanceof HTMLElement) triggerRef.current = trigger;
    mutation.mutate({ subject, decision });
  };

  const openReplace = (subject: ReviewSubject, trigger?: EventTarget | null): void => {
    if (trigger instanceof HTMLElement) triggerRef.current = trigger;
    else triggerRef.current = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    setPickedTarget(subject.proposedAgainst?.memoryId ?? '');
    setReplaceTarget(subject);
  };

  const closeReplace = (): void => {
    setReplaceTarget(null);
    setPickedTarget('');
    const trigger = triggerRef.current;
    if (trigger) trigger.focus();
    triggerRef.current = null;
  };

  const confirmReplace = (): void => {
    const subject = replaceTarget;
    if (!subject || !replaceChosen) return;
    mutation.mutate({ subject, decision: 'replace', target: { memoryId: replaceChosen.memoryId,
      version: replaceChosen.version } });
  };

  if (!props.enabled) {
    return <Alert type="warning" content="记忆库未解锁时看不到待确认内容，这不是「没有待确认」。" />;
  }

  const loading = proposalQuery.isPending || candidateQuery.isPending;
  const readFailure = proposalQuery.isError
    ? friendlyMessage(proposalQuery.error, '建议列表读取失败')
    : candidateQuery.isError ? friendlyMessage(candidateQuery.error, '待确认记忆读取失败') : null;

  return (
    <div className="ai-memory-review">
      {loading && (
        <span className="ai-memory-spin" role="status" aria-label="正在读取待确认内容">
          <Spin size={16} />
        </span>
      )}
      {readFailure && (
        <Alert
          className="ai-memory-review-error"
          type="error"
          content={readFailure}
          action={<Button size="mini" onClick={refreshAll}>重新读取</Button>}
        />
      )}

      {!loading && !readFailure && subjects.length === 0 && (
        <Empty className="ai-memory-empty" description="暂时没有需要你确认的记忆改动" />
      )}

      {subjects.map((subject) => {
        const failure = failures[subject.key];
        const busy = mutation.isPending && mutation.variables?.subject.key === subject.key;
        // 载荷解不开时服务端每个决定都会拒（readPayload 先失败），按钮按下去只能换来一条错误
        const blocked = subject.corrupt;
        const blockedTitle = blocked ? '密文读不出来，这条做不了任何决定' : undefined;
        return (
          <article key={subject.key} className="ai-memory-review-card">
            <header className="ai-memory-review-head">
              <Typography.Text bold>{subject.title}</Typography.Text>
              {subject.retract && <Tag color="orange">撤销建议</Tag>}
              {subject.corrupt && <Tag color="red">密文不可用</Tag>}
            </header>
            <dl className="ai-memory-review-body">
              <div><dt>新值</dt><dd>{subject.retract ? '（无：这条建议是忘记）' : subject.newValue ?? '（读不出来）'}</dd></div>
              <div><dt>当前值</dt><dd>{subject.currentMissing ? '（已经没有生效版本）' : subject.currentValue ?? '（没有可对照的当前值）'}</dd></div>
              <div><dt>你说过的原话</dt><dd>{subject.quote ?? '（原话已不可读）'}</dd></div>
              <div><dt>作用域</dt><dd>{subject.scope}</dd></div>
              <div><dt>为什么待确认</dt><dd>{subject.reason}</dd></div>
            </dl>
            <Space size="small" wrap className="ai-memory-review-actions">
              <Button
                size="small"
                type="primary"
                loading={busy}
                disabled={blocked}
                title={blockedTitle}
                onClick={(event) => decide(subject, 'accept', event.currentTarget)}
              >
                采用新值
              </Button>
              <Button
                size="small"
                disabled={blocked}
                title={blockedTitle}
                onClick={(event) => decide(subject, 'reject', event.currentTarget)}
              >
                保留当前
              </Button>
              <Button
                size="small"
                disabled={blocked || !subject.multiValued || subject.retract}
                title={blockedTitle ?? (subject.multiValued ? undefined : '这一类是单值偏好，不能同时留两条')}
                onClick={(event) => decide(subject, 'keep_both', event.currentTarget)}
              >
                分别保留
              </Button>
              {subject.source === 'candidate' && !subject.corrupt && (
                <Button size="small" onClick={(event) => openReplace(subject, event.currentTarget)}>替换某条旧记忆…</Button>
              )}
            </Space>
            {failure && (
              <Alert
                className="ai-memory-review-error"
                type="warning"
                content={failure.message}
                action={failure.stale ? <Button size="mini" onClick={refreshAll}>重新读取</Button> : undefined}
              />
            )}
          </article>
        );
      })}

      <Space size="small" className="ai-memory-pager">
        <Button
          size="mini"
          aria-label="待确认上一页"
          disabled={proposalCursor.length === 0 && candidateCursor.length === 0}
          onClick={() => {
            setProposalCursor((stack) => stack.slice(0, -1));
            setCandidateCursor((stack) => stack.slice(0, -1));
          }}
        >
          上一页
        </Button>
        <Button
          size="mini"
          aria-label="待确认下一页"
          disabled={!proposalQuery.data?.hasMore && !candidateQuery.data?.hasMore}
          onClick={() => {
            const proposalNext = proposalQuery.data?.nextCursor;
            const candidateNext = candidateQuery.data?.nextCursor;
            if (proposalNext) setProposalCursor((stack) => [...stack, proposalNext]);
            if (candidateNext) setCandidateCursor((stack) => [...stack, candidateNext]);
          }}
        >
          下一页
        </Button>
        <Typography.Text type="secondary">
          建议 {(proposalQuery.data?.proposals ?? []).length} 条 · 候选 {(candidateQuery.data?.items ?? []).length} 条
        </Typography.Text>
      </Space>

      <Modal
        title="用这条候选替换哪条旧记忆"
        visible={replaceTarget != null}
        okText="替换"
        cancelText="取消"
        confirmLoading={mutation.isPending}
        okButtonProps={{ disabled: replaceChosen == null, status: 'danger' }}
        onOk={confirmReplace}
        onCancel={closeReplace}
        unmountOnExit
      >
        <div className="form-stack">
          <Typography.Text type="secondary">
            只有你点名的那条会被替换；旧值记为「已被替换」，随时能回看。
          </Typography.Text>
          <Select
            placeholder={replaceCandidatesQuery.isFetching ? '正在读取生效的记忆…' : '选择要替换的旧记忆'}
            value={pickedTarget || undefined}
            onChange={(value) => setPickedTarget(String(value))}
            aria-label="要替换的旧记忆"
            options={(replaceCandidatesQuery.data?.items ?? []).map((item) => ({
              value: item.memoryId,
              label: `${item.corrupt ? '（读不出正文）' : item.value ?? '（无正文）'} · v${item.version}`
            }))}
          />
        </div>
      </Modal>
    </div>
  );
}
