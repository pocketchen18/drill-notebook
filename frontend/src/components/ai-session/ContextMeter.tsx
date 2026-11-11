import { useEffect, useRef, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { get } from '../../lib/api';
import { useTurnContextAudit } from '../../hooks/useTurnContext';
import './context-meter.css';

export interface SessionContextUsage {
  sessionId: number;
  latest: { requestId: string; inputTokens: number; contextWindow: number; model: string | null } | null;
  model: string | null;
  contextWindow: number | null;
  outputReserve: number | null;
}

interface Props {
  sessionId?: number;
  /** 供应商没有回报用量时退而使用的本地估算（来自最近一轮的上下文计划）。 */
  estimate?: { tokens: number; requestId?: string } | null;
  /** 轮次状态变化时重新读取。 */
  refreshKey?: string;
  onInspect: (requestId: string) => void;
}

export const contextUsageKey = (sessionId?: number) => ['ai-context-usage', sessionId ?? 'new'] as const;
export const formatTokens = (value: number): string => value >= 1000 ? `${(value / 1000).toFixed(value >= 100000 ? 0 : 1)}k` : String(value);

const GROUPS: Array<{ label: string; kinds: string[] }> = [
  { label: '历史对话与摘要', kinds: ['RECENT_TURNS', 'SESSION_STATE'] },
  { label: '本条提问与附件', kinds: ['USER_TEXT', 'TEXT_ATTACHMENT', 'IMAGE', 'PAGE', 'NOTEBOOK_RAG'] },
  { label: '记忆', kinds: ['MEMORY'] },
  { label: '系统提示', kinds: ['SYSTEM'] }
];

/** 当前会话送入模型的上下文占用：说的是一次请求的长度，不是多次工具往返的累计用量。 */
export function ContextMeter({ sessionId, estimate, refreshKey, onInspect }: Props): JSX.Element | null {
  const [open, setOpen] = useState(false);
  const root = useRef<HTMLDivElement>(null);
  const usage = useQuery({ queryKey: [...contextUsageKey(sessionId), refreshKey ?? ''],
    queryFn: () => get<SessionContextUsage>(`/api/ai/sessions/${sessionId}/context-usage`), enabled: sessionId !== undefined, staleTime: 0 });
  const data = sessionId === undefined ? undefined : usage.data;
  const measured = data?.latest ?? null;
  const tokens = measured?.inputTokens ?? estimate?.tokens ?? null;
  const window = data?.contextWindow ?? measured?.contextWindow ?? null;
  const requestId = measured?.requestId ?? estimate?.requestId;
  const audit = useTurnContextAudit(sessionId, requestId, open && !!requestId);

  useEffect(() => {
    if (!open) return;
    const outside = (event: PointerEvent): void => { if (!root.current?.contains(event.target as Node)) setOpen(false); };
    const escape = (event: KeyboardEvent): void => {
      if (event.key !== 'Escape') return;
      event.preventDefault(); event.stopPropagation(); setOpen(false);
      root.current?.querySelector('button')?.focus();
    };
    document.addEventListener('pointerdown', outside);
    document.addEventListener('keydown', escape, true);
    return () => { document.removeEventListener('pointerdown', outside); document.removeEventListener('keydown', escape, true); };
  }, [open]);

  if (sessionId === undefined || !window) return null;
  const ratio = tokens === null ? 0 : Math.min(1, tokens / window);
  const percent = tokens === null ? null : Math.round(ratio * 100);
  const high = ratio >= 0.8;
  const source = measured ? '实测' : '估算';
  const circumference = 2 * Math.PI * 9;
  const regions = audit.data?.audit?.regions ?? [];
  const rows = GROUPS.map((group) => ({ label: group.label,
    tokens: regions.filter((region) => group.kinds.includes(region.kind)).reduce((sum, region) => sum + region.estimatedTokens, 0) }))
    .filter((row) => row.tokens > 0);
  const listed = rows.reduce((sum, row) => sum + row.tokens, 0);
  const summary = audit.data?.audit?.summary;
  const trimmed = (audit.data?.audit?.omissions ?? []).some((item) => item.reason === 'BUDGET');
  const label = percent === null ? '上下文占用：尚无数据' : `上下文占用 ${percent}%（${source}）`;

  return <div className="context-meter" ref={root}>
    <button type="button" className={`context-meter-trigger${high ? ' is-high' : ''}`} aria-haspopup="dialog" aria-expanded={open}
      aria-label={label} title={label} onClick={() => setOpen(!open)}>
      <svg viewBox="0 0 24 24" aria-hidden="true"><circle cx="12" cy="12" r="9" className="context-meter-track" />
        <circle cx="12" cy="12" r="9" className="context-meter-value" strokeDasharray={`${ratio * circumference} ${circumference}`} /></svg>
      <span>{percent === null ? '—' : `${percent}%`}</span>
    </button>
    {open ? <div className="context-meter-pop" role="dialog" aria-label="上下文占用">
      <div className="context-meter-body">
        {tokens === null ? <strong>还没有可用的占用数据</strong>
          : <strong>{measured ? '最近一次请求 · 实测' : '最近一轮 · 估算'} {formatTokens(tokens)} / {formatTokens(window)}</strong>}
        <span>{data?.model ?? '当前模型'} 的上下文窗口{data?.outputReserve ? `，另为输出预留 ${formatTokens(data.outputReserve)}` : ''}</span>
        {tokens === null ? <p>发送第一条消息后，这里显示供应商回报的实际长度。</p> : null}
        {measured && measured.contextWindow !== window ? <p>该请求发出时使用的模型窗口为 {formatTokens(measured.contextWindow)}，现按当前模型的窗口计算。</p> : null}
        {!measured && tokens !== null ? <p>供应商没有回报用量，这是发送前的本地估算，不含工具定义与工具结果。</p> : null}
        {rows.length ? <>
          <div className="context-meter-title">发送前的分项估算</div>
          <dl>{rows.map((row) => <div key={row.label}><dt>{row.label}</dt><dd>{formatTokens(row.tokens)}</dd></div>)}
            {measured && measured.inputTokens > listed ? <div><dt>工具定义、工具结果及估算误差</dt><dd>{formatTokens(measured.inputTokens - listed)}</dd></div> : null}</dl>
        </> : audit.isPending && requestId ? <p>正在读取分项…</p> : tokens !== null ? <p>这一轮没有保存分项记录。</p> : null}
        {summary ? <p>较早的对话已用摘要代替{summary.hasGaps ? '，其中有未覆盖的部分' : ''}。</p> : null}
        {trimmed ? <p>部分内容因容量限制没有送入模型。</p> : null}
        {high ? <p className="context-meter-warn">已接近模型窗口上限，较早的对话可能被摘要或省略。</p> : null}
      </div>
      <div className="context-meter-foot"><span>不含多次工具往返的累计用量</span>
        {requestId ? <button type="button" onClick={() => { setOpen(false); onInspect(requestId); }}>查看本轮上下文</button> : null}</div>
    </div> : null}
  </div>;
}
