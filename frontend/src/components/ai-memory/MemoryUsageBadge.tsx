import { useMemo, useState } from 'react';
import { ApiError } from '../../lib/api';
import { friendlyMessage } from '../../lib/errors';
import { useTurnContextAudit, useSourcePreview } from '../../hooks/useTurnContext';
import type { MemoryReference } from '../../lib/aiStream';
import { auditReasonText, previewLine } from '../../lib/aiTurnText';

/**
 * 每条回答下的记忆徽标（执行方案 §12）：先只说"本轮提供 N 条记忆"，点开列当时的 ID/版本。
 *
 * <p>界面不保存任何明文副本：计数与 ID/版本来自本轮 done 里那份由同一个不可变计划导出的快照，
 * 想对照来源得逐条显式点，且每次只取一条（§4.5「不能自动批量预览」）。记忆被删或改版时，
 * 那一行只会出现占位文案，绝不从旧快照或旧缓存里把正文恢复出来。</p>
 */
export interface MemoryUsageBadgeProps {
  sessionId?: number;
  requestId: string;
  /** done 的 `memoryContext.provided`：本轮实际提供的记忆版本，无正文。 */
  provided: MemoryReference[];
  onOpenInspector?: () => void;
}

export function MemoryUsageBadge({ sessionId, requestId, provided, onOpenInspector }: MemoryUsageBadgeProps) {
  const [open, setOpen] = useState(false);
  const audit = useTurnContextAudit(sessionId, requestId, open);
  const previews = useSourcePreview(open ? requestId : null);

  // 来源键只存在于落库的账本里：按 id + 版本对齐，对不上就只给 ID/版本，不给对照入口
  const keyById = useMemo(() => {
    const map = new Map<string, string>();
    for (const row of audit.data?.audit?.selected ?? []) {
      if (row.kind !== 'MEMORY' || row.id == null) continue;
      map.set(`${row.id}|${row.version ?? ''}`, row.sourceKey);
    }
    return map;
  }, [audit.data]);

  if (!provided.length) return null;
  const readError = audit.isError
    ? (audit.error instanceof ApiError ? friendlyMessage(audit.error, '本轮上下文记录读取失败') : '本轮上下文记录读取失败')
    : null;

  return (
    <div className="ai-memory-badge">
      <button
        type="button"
        className="ai-memory-badge-toggle"
        aria-expanded={open}
        onClick={() => setOpen((prev) => !prev)}
      >
        本轮提供 {provided.length} 条记忆
      </button>
      {open ? (
        <div className="ai-memory-badge-panel">
          <p className="ai-memory-badge-note">只显示当时的 ID 与版本，正文要逐条对照。</p>
          <ul className="ai-memory-badge-list">
            {provided.map((item) => {
              const sourceKey = keyById.get(`${item.memoryId}|${item.version ?? ''}`);
              const preview = sourceKey ? previews.rows[sourceKey] : undefined;
              const line = preview ? previewLine(preview) : null;
              return (
                <li key={`${item.memoryId}-${item.version}`} className="ai-memory-badge-item">
                  <code className="ai-memory-badge-id">{item.memoryId}</code>
                  <span className="ai-memory-badge-version">v{item.version ?? '?'}</span>
                  {sourceKey ? (
                    <button
                      type="button"
                      className="ai-memory-badge-check"
                      disabled={previews.pendingKey === sourceKey}
                      onClick={() => previews.preview(sourceKey)}
                    >
                      {previews.pendingKey === sourceKey ? '对照中…' : '对照来源'}
                    </button>
                  ) : null}
                  {line ? (
                    <span className={line.tone === 'quote' ? 'ai-memory-badge-quote'
                      : line.tone === 'gone' ? 'ai-memory-badge-gone' : 'ai-memory-badge-plain'}>
                      {line.text}
                    </span>
                  ) : null}
                  {!sourceKey && audit.data?.available === false
                    ? <span className="ai-memory-badge-gone">{auditReasonText(audit.data.reason)}</span>
                    : null}
                </li>
              );
            })}
          </ul>
          {previews.error ? <p className="ai-memory-badge-error">{previews.error}</p> : null}
          {readError ? <p className="ai-memory-badge-error">{readError}</p> : null}
          {audit.isPending && !readError ? <p className="ai-memory-badge-note">正在读取本轮账本…</p> : null}
          {onOpenInspector ? (
            <button type="button" className="ai-memory-badge-inspector" onClick={onOpenInspector}>
              查看上下文详情
            </button>
          ) : null}
        </div>
      ) : null}
    </div>
  );
}
