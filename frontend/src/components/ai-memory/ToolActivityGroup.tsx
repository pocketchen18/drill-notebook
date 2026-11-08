import { useEffect, useId, useRef, useState } from 'react';
import { Check, ChevronRight, CircleMinus, LoaderCircle, TriangleAlert } from 'lucide-react';
import { TOOL_NAMES, toolDuration, toolGroupTitle, toolResultText, toolStartedAt, toolTone } from '../../lib/toolActivity';
import type { ToolActivity, ToolPhase } from '../../lib/toolActivity';

interface Props {
  activities: ToolActivity[];
  active?: boolean;
  phase?: ToolPhase;
  onDisclosure?: () => void;
}

function StatusIcon({ tone }: { tone: ReturnType<typeof toolTone> }): JSX.Element {
  const Icon = { running: LoaderCircle, success: Check, neutral: CircleMinus, warning: TriangleAlert }[tone];
  return <Icon size={16} className={`ai-tool-icon is-${tone}`} aria-hidden="true" />;
}

/** 折叠选择与服务端快照分开保存，事件更新不会抢走用户正在看的详情。 */
export function ToolActivityGroup({ activities, active = false, phase, onDisclosure }: Props): JSX.Element | null {
  const id = useId();
  const historyOnMount = useRef(!active);
  const [manualOpen, setManualOpen] = useState<boolean | null>(null);
  const [details, setDetails] = useState<Set<number>>(() => new Set());
  const [now, setNow] = useState(Date.now);
  const running = activities.some((row) => row.state === 'RUNNING');
  const warning = activities.some((row) => toolTone(row) === 'warning');
  const open = manualOpen ?? (!historyOnMount.current && (active || warning));
  useEffect(() => {
    if (!active || !running || !open) return;
    const timer = window.setInterval(() => setNow(Date.now()), 1000);
    return () => window.clearInterval(timer);
  }, [active, running, open]);
  if (!activities.length) return null;

  const duration = (row: ToolActivity): string => toolDuration(row.state === 'RUNNING'
    ? row.startedAt ? Math.max(0, now - Date.parse(row.startedAt)) : null : row.elapsedMs);
  const completed = activities.filter((row) => row.state === 'FINISHED').length;
  const totalDuration = activities.every((row) => row.elapsedMs !== null)
    ? toolDuration(activities.reduce((sum, row) => sum + (row.elapsedMs ?? 0), 0)) : null;
  const written = activities.some((row) => row.state === 'FINISHED' && ['CREATED', 'UPDATED'].includes(row.outcome));
  const unknownWrite = activities.some((row) => row.state === 'UNKNOWN' && ['create_memory', 'update_memory'].includes(row.toolName));
  const tone = running ? 'running' : warning ? 'warning' : activities.some((row) => toolTone(row) === 'success') ? 'success' : 'neutral';
  const toggle = (button: HTMLButtonElement, change: () => void): void => {
    onDisclosure?.();
    const scroller = button.closest<HTMLElement>('.ai-drawer-chat');
    const top = button.getBoundingClientRect().top;
    change();
    window.requestAnimationFrame(() => {
      if (scroller && button.isConnected) scroller.scrollTop += button.getBoundingClientRect().top - top;
    });
  };

  return <section className="ai-tool-activity" aria-label="工具调用记录">
    <button type="button" className="ai-tool-heading" aria-expanded={open} aria-controls={`${id}-rows`}
      onClick={(event) => toggle(event.currentTarget, () => setManualOpen(!open))}>
      <StatusIcon tone={tone} />
      <span className="ai-tool-copy">
        <span className="ai-tool-title" aria-live="polite">{toolGroupTitle(activities)}</span>
        <span className="ai-tool-meta">{activities.length} 次工具调用{running ? ` · 已结束 ${completed} 项` : totalDuration ? ` · ${totalDuration}` : ''}</span>
      </span>
      <ChevronRight size={14} className={`ai-tool-chevron${open ? ' is-open' : ''}`} aria-hidden="true" />
    </button>
    <div id={`${id}-rows`} hidden={!open}>
      <ol className="ai-tool-list">
        {activities.map((row) => <li key={row.sequence} className="ai-tool-row">
          <button type="button" className="ai-tool-row-button" aria-expanded={details.has(row.sequence)}
            aria-controls={`${id}-${row.sequence}`} onClick={(event) => toggle(event.currentTarget, () => {
              setManualOpen(true);
              setDetails((previous) => {
                const next = new Set(previous);
                if (next.has(row.sequence)) next.delete(row.sequence); else next.add(row.sequence);
                return next;
              });
            })}>
            <StatusIcon tone={toolTone(row)} />
            <span className="ai-tool-copy"><span>{TOOL_NAMES[row.toolName]}</span>
              <span className={`ai-tool-result${toolTone(row) === 'warning' ? ' is-warning' : ''}`}>{toolResultText(row)}</span></span>
            <span className="ai-tool-time">{duration(row)}</span>
            <ChevronRight size={13} className={`ai-tool-chevron${details.has(row.sequence) ? ' is-open' : ''}`} aria-hidden="true" />
          </button>
          <dl id={`${id}-${row.sequence}`} className="ai-tool-details" hidden={!details.has(row.sequence)}>
            <dt>开始时间</dt><dd>{toolStartedAt(row.startedAt)}</dd>
            <dt>执行耗时</dt><dd>{duration(row)}</dd>
            <dt>{['create_memory', 'update_memory'].includes(row.toolName) ? '操作范围' : '查询范围'}</dt><dd>当前有权访问的记忆</dd>
            <dt>执行结果</dt><dd>{toolResultText(row)}{row.incomplete ? '（部分结果）' : ''}</dd>
            <dt>工具名称</dt><dd><code>{row.toolName}</code></dd>
          </dl>
        </li>)}
      </ol>
      {unknownWrite ? <p className="ai-tool-note">部分写入结果未确认，可到记忆库核对。{written ? '已确认的变更已保留。' : ''}</p> : null}
    </div>
    {active && phase === 'answer' && !running ? <div className="ai-tool-answer" role="status">正在生成回答…</div> : null}
  </section>;
}
