import { Modal, Typography } from '@arco-design/web-react';
import { ApiError } from '../../lib/api';
import { friendlyMessage } from '../../lib/errors';
import { useTurnContextAudit, useSourcePreview } from '../../hooks/useTurnContext';
import {
  auditReasonText,
  dispatchStateText,
  omissionReasonText,
  previewLine,
  regionLabel,
  sourceKindLabel,
  turnStatusText
} from '../../lib/aiTurnText';
import type { ContextOmission, ContextSource, RequestContextPage, SourcePreview, TurnContextAudit } from '../../lib/aiContextApi';
import type { TurnResult } from '../../lib/types';
import { RequestContextBrowser } from './RequestContextBrowser';
import { CurrentContextCard } from './CurrentContextCard';

/**
 * 上下文检查器：上方展示当前会话占用，下方只读所选轮次的请求组成与来源账本。
 *
 * <p>打开它不会新建计划、不会触发检索/维护/重放，也不会发一轮新聊天；正文预览必须逐条显式点，
 * 面板关闭即随组件销毁，不留上一次的解密结果。它描述的是"提供给模型什么"，不是模型内部注意力。</p>
 */
export interface ChatContextInspectorProps {
  sessionId?: number;
  requestId: string;
  /** 兼容旧调用方的轮次快照；当前占用不从它取。 */
  turn?: TurnResult;
  refreshKey?: string;
  open: boolean;
  onClose: () => void;
  /** 助手窗口的详情栏里直接铺开，不再套弹窗。 */
  embedded?: boolean;
}

export function ChatContextInspector({ sessionId, requestId, refreshKey, open, onClose, embedded }: ChatContextInspectorProps) {
  if (embedded) return open ? <InspectorBody sessionId={sessionId} requestId={requestId} refreshKey={refreshKey} /> : null;
  return (
    <Modal title="上下文" visible={open} footer={null} onCancel={onClose} autoFocus={false} unmountOnExit style={{ width: 660 }}>
      {open ? <InspectorBody sessionId={sessionId} requestId={requestId} refreshKey={refreshKey} /> : null}
    </Modal>
  );
}

function InspectorBody({ sessionId, requestId, refreshKey }: { sessionId?: number; requestId: string; refreshKey?: string }) {
  const audit = useTurnContextAudit(sessionId, requestId, true);
  const previews = useSourcePreview(requestId);

  let body;
  if (audit.isError) {
    body = (
      <p className="ai-inspector-empty">
        {audit.error instanceof ApiError ? friendlyMessage(audit.error, '上下文记录读取失败') : '上下文记录读取失败'}
      </p>
    );
  } else if (audit.isPending) {
    body = <p className="ai-inspector-empty">正在读取请求账本…</p>;
  } else {
    const read = audit.data;
    body = !read?.available || !read.audit
      ? <p className="ai-inspector-empty">{auditReasonText(read?.reason)}</p>
      : <AuditView audit={read.audit} previews={previews} requestId={requestId} requests={read.requestContexts} />;
  }
  return <div className="ai-inspector">
    <CurrentContextCard sessionId={sessionId} refreshKey={refreshKey} />
    {body}
  </div>;
}

function AuditView({ audit, previews, requestId, requests }: {
  audit: TurnContextAudit;
  previews: ReturnType<typeof useSourcePreview>;
  requestId: string;
  requests?: RequestContextPage;
}) {
  const hasRequests = Boolean(requests?.items.length);
  return (
    <>
      {hasRequests && requests ? <RequestContextBrowser key={requestId} requestId={requestId} page={requests} />
        : <p className="ai-inspector-note">这里是本轮提供给模型的上下文清单，不表示模型内部的注意力。</p>}

      <details className="ai-inspector-plan" open={hasRequests ? undefined : true}>
        <summary>来源与省略</summary>

      <section className="ai-inspector-block">
        <Typography.Text bold>计划与发送态</Typography.Text>
        <p className="ai-inspector-line">
          <code>{audit.planId}</code>
          <span className="ai-inspector-flag">{dispatchStateText(audit.dispatchState)}</span>
        </p>
        <p className="ai-inspector-line">{`估算 ${audit.estimatedInputTokens} / 预算 ${audit.inputBudget} tokens`}</p>
        <p className="ai-inspector-muted">{`估算器 ${audit.estimatorVersion}，非厂商计数`}</p>
        {audit.auditTruncated ? <p className="ai-inspector-muted">账本过长，部分诊断明细未保存。</p> : null}
        {!hasRequests ? <p className="ai-inspector-muted">只保存最后一次尝试的计划；更早尝试的完整提示词未保留。</p> : null}
      </section>

      <section className="ai-inspector-block">
        <Typography.Text bold>实际发送范围</Typography.Text>
        <p className="ai-inspector-line">
          <span>{`本轮扫描 ${audit.scan.scannedTurns} 轮`}</span>
          {audit.scan.firstTurnId != null && audit.scan.lastTurnId != null
            ? <span>{`（turnId ${audit.scan.firstTurnId}–${audit.scan.lastTurnId}）`}</span>
            : null}
          <span className="ai-inspector-flag">
            {audit.scan.hasOlderUnscanned ? '更早历史未扫描' : '已扫到最早可用历史'}
          </span>
        </p>
        <SummaryLine audit={audit} />
        {audit.resumeState ? (
          <p className="ai-inspector-line">
            <span>更早有一轮未正常收尾</span>
            <span className="ai-inspector-flag">{turnStatusText(audit.resumeState.status)}</span>
            <span className="ai-inspector-flag">{audit.resumeState.finishedAt}</span>
          </p>
        ) : null}
      </section>

      {!hasRequests ? <section className="ai-inspector-block">
        <Typography.Text bold>区域估算</Typography.Text>
        <table className="ai-inspector-table">
          <thead><tr><th>区域</th><th>条数</th><th>估算 tokens</th></tr></thead>
          <tbody>
            {audit.regions.map((region) => (
              <tr key={region.kind}>
                <td>{regionLabel(region.kind)}</td>
                <td>{region.itemCount}</td>
                <td>{region.estimatedTokens}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </section> : null}

      <section className="ai-inspector-block">
        <Typography.Text bold>{`提供的来源（${audit.selected.length}）`}</Typography.Text>
        <ul className="ai-inspector-sources">
          {audit.selected.map((source) => (
            <SourceRow key={source.sourceKey} source={source} previews={previews} />
          ))}
        </ul>
        {previews.error ? <p className="ai-inspector-error">{previews.error}</p> : null}
      </section>

      <section className="ai-inspector-block">
        <Typography.Text bold>省略与原因</Typography.Text>
        {audit.omissions.length ? (
          <ul className="ai-inspector-omissions">
            {audit.omissions.map((omission, index) => (
              <OmissionRow key={`${omission.reason}-${omission.kind ?? ''}-${index}`} omission={omission} />
            ))}
          </ul>
        ) : <p className="ai-inspector-line">本轮没有省略记录。</p>}
      </section>

      </details>
    </>
  );
}

function SummaryLine({ audit }: { audit: TurnContextAudit }) {
  const summary = audit.summary;
  if (!summary) return <p className="ai-inspector-line">没有可用的续接摘要，较早历史按原文进窗口。</p>;
  const ranges = summary.coverageRanges ?? [];
  const shown = ranges.slice(0, 4).map((range) => `${range.firstTurnId}–${range.lastTurnId}`).join('、');
  return (
    <p className="ai-inspector-line">
      <span>{`续接摘要 v${summary.revision} 覆盖到 turnId ${summary.throughTurnId}`}</span>
      <span className="ai-inspector-flag">{summary.hasGaps ? '有缺口' : '无缺口'}</span>
      {shown ? <span className="ai-inspector-coverage">{`${shown}${ranges.length > 4 ? ` 等 ${ranges.length} 段` : ''}`}</span> : null}
    </p>
  );
}

/**
 * 后端 `ContextInspector.previewOne` 只对"有原文"的类别回内容，`RESUME_STATE` 落 `default` 分支只给占位。
 * 与其给一个注定只能显示占位的按钮，不如直说这一行不可逐条对照（D164）。
 */
const NON_PREVIEWABLE_KINDS = new Set(['RESUME_STATE']);

function SourceRow({ source, previews }: { source: ContextSource; previews: ReturnType<typeof useSourcePreview> }) {
  const preview: SourcePreview | undefined = previews.rows[source.sourceKey];
  const line = preview ? previewLine(preview) : null;
  const previewable = !NON_PREVIEWABLE_KINDS.has(source.kind);
  return (
    <li className="ai-inspector-source">
      <span className="ai-inspector-source-kind">{sourceKindLabel(source.kind)}</span>
      <code className="ai-inspector-source-id">{source.id ?? '—'}</code>
      <span className="ai-inspector-source-version">{source.version ? `v${source.version}` : '版本未记录'}</span>
      {previewable ? (
        <button
          type="button"
          className="ai-inspector-source-check"
          disabled={previews.pendingKey === source.sourceKey}
          onClick={() => previews.preview(source.sourceKey)}
        >
          {previews.pendingKey === source.sourceKey ? '对照中…' : '对照来源'}
        </button>
      ) : (
        <span className="ai-inspector-source-plain">会话状态无法逐条对照</span>
      )}
      {line ? (
        <span className={line.tone === 'quote' ? 'ai-inspector-source-quote'
          : line.tone === 'gone' ? 'ai-inspector-source-gone' : 'ai-inspector-source-plain'}>
          {line.text}
        </span>
      ) : null}
    </li>
  );
}

function OmissionRow({ omission }: { omission: ContextOmission }) {
  return (
    <li className="ai-inspector-omission">
      <span className="ai-inspector-omission-kind">{omission.kind ? sourceKindLabel(omission.kind) : '全部类别'}</span>
      <span className="ai-inspector-omission-reason">{omissionReasonText(omission.reason)}</span>
      <span className="ai-inspector-omission-count">
        {omission.count == null ? '数量未知（更早未扫描）' : `${omission.count} 项`}
      </span>
    </li>
  );
}
