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
import type { ContextOmission, ContextSource, SourcePreview, TurnContextAudit } from '../../lib/aiContextApi';
import type { TurnResult } from '../../lib/types';

/**
 * 本轮上下文检查器（执行方案 §12、contracts §4.5、§9.4）：只读已保存的审计账本，
 * 列出实际提供给模型的发送范围、区域估算、摘要缺口、省略原因与用量。
 *
 * <p>打开它不会新建计划、不会触发检索/维护/重放，也不会发一轮新聊天；正文预览必须逐条显式点，
 * 面板关闭即随组件销毁，不留上一次的解密结果。它描述的是"提供给模型什么"，不是模型内部注意力。</p>
 */
export interface ChatContextInspectorProps {
  sessionId?: number;
  requestId: string;
  /** 本轮 done 的统一结果快照：账本里没有的 usage 只能从它取。 */
  turn?: TurnResult;
  open: boolean;
  onClose: () => void;
  /** 助手窗口的详情栏里直接铺开，不再套弹窗。 */
  embedded?: boolean;
}

export function ChatContextInspector({ sessionId, requestId, turn, open, onClose, embedded }: ChatContextInspectorProps) {
  if (embedded) return open ? <InspectorBody sessionId={sessionId} requestId={requestId} turn={turn} /> : null;
  return (
    <Modal title="本轮上下文" visible={open} footer={null} onCancel={onClose} autoFocus={false} unmountOnExit style={{ width: 660 }}>
      {open ? <InspectorBody sessionId={sessionId} requestId={requestId} turn={turn} /> : null}
    </Modal>
  );
}

function InspectorBody({ sessionId, requestId, turn }: { sessionId?: number; requestId: string; turn?: TurnResult }) {
  const audit = useTurnContextAudit(sessionId, requestId, true);
  const previews = useSourcePreview(requestId);

  if (audit.isError) {
    return (
      <p className="ai-inspector-empty">
        {audit.error instanceof ApiError ? friendlyMessage(audit.error, '本轮上下文记录读取失败') : '本轮上下文记录读取失败'}
      </p>
    );
  }
  if (audit.isPending) return <p className="ai-inspector-empty">正在读取本轮账本…</p>;

  const read = audit.data;
  if (!read?.available || !read.audit) {
    return <p className="ai-inspector-empty">{auditReasonText(read?.reason)}</p>;
  }
  return <AuditView audit={read.audit} turn={turn} previews={previews} />;
}

function AuditView({ audit, turn, previews }: {
  audit: TurnContextAudit;
  turn?: TurnResult;
  previews: ReturnType<typeof useSourcePreview>;
}) {
  return (
    <div className="ai-inspector">
      <p className="ai-inspector-note">这里是本轮提供给模型的上下文清单，不表示模型内部的注意力。</p>

      <section className="ai-inspector-block">
        <Typography.Text bold>计划与发送态</Typography.Text>
        <p className="ai-inspector-line">
          <code>{audit.planId}</code>
          <span className="ai-inspector-flag">{dispatchStateText(audit.dispatchState)}</span>
        </p>
        <p className="ai-inspector-line">{`估算 ${audit.estimatedInputTokens} / 预算 ${audit.inputBudget} tokens`}</p>
        <p className="ai-inspector-muted">{`估算器 ${audit.estimatorVersion}，非厂商计数`}</p>
        {audit.auditTruncated ? <p className="ai-inspector-muted">账本过长，部分诊断明细未保存。</p> : null}
        <p className="ai-inspector-muted">只保存最后一次尝试的计划；更早尝试的完整提示词未保留。</p>
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

      <section className="ai-inspector-block">
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
      </section>

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

      <UsageLine turn={turn} />
    </div>
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

function UsageLine({ turn }: { turn?: TurnResult }) {
  const usage = turn?.usage;
  if (!usage) return null;
  const token = (value: number | null | undefined) => (value == null ? '未知' : String(value));
  return (
    <section className="ai-inspector-block">
      <Typography.Text bold>用量</Typography.Text>
      <p className="ai-inspector-usage">
        <span>{`来源 ${usage.source ?? 'unknown'}`}</span>
        <span>{`输入 ${token(usage.inputTokens)}`}</span>
        <span>{`输出 ${token(usage.outputTokens)}`}</span>
        <span>{`缓存读 ${token(usage.cacheReadTokens)}`}</span>
        <span>{`缓存写 ${token(usage.cacheWriteTokens)}`}</span>
      </p>
      <p className="ai-inspector-muted">缺字段就是上游没给，不按估算冒充当过的计数。</p>
    </section>
  );
}
