import { useEffect, useRef, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { get } from '../../lib/api';
import './context-meter.css';

export interface SessionContextUsage {
  sessionId: number;
  latest: { requestId: string; inputTokens: number; contextWindow: number; model: string | null;
    cacheReadTokens: number | null; outputTokens: number | null } | null;
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

/**
 * 当前会话送入模型的上下文占用。只讲三件事：输入（缓存命中 / 未命中）、输出、窗口占比；
 * 窗口大小已经在第一行和进度条里，输出预留、分项估算、摘要与省略原因都不在这里重复，
 * 需要时去「查看本轮上下文」看。
 */
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
  // 命中是供应商回报的缓存读取量：它已经包含在输入长度里，未命中就是剩下的部分。
  const hit = measured?.cacheReadTokens == null ? null : Math.min(measured.cacheReadTokens, measured.inputTokens);
  const miss = hit === null || tokens === null ? null : Math.max(0, tokens - hit);
  const output = measured?.outputTokens ?? null;
  const hitShare = hit === null || !tokens ? null : Math.round((hit / tokens) * 100);
  const label = percent === null ? '上下文占用：尚无数据' : `上下文占用 ${percent}%（${source}）`;

  return <div className="context-meter" ref={root}>
    <button type="button" className={`context-meter-trigger${high ? ' is-high' : ''}`} aria-haspopup="dialog" aria-expanded={open}
      aria-label={label} title={label} onClick={() => setOpen(!open)}>
      <svg viewBox="0 0 24 24" aria-hidden="true"><circle cx="12" cy="12" r="9" className="context-meter-track" />
        <circle cx="12" cy="12" r="9" className="context-meter-value" strokeDasharray={`${ratio * 2 * Math.PI * 9} ${2 * Math.PI * 9}`} /></svg>
      <span>{percent === null ? '—' : `${percent}%`}</span>
    </button>
    {open ? <div className="context-meter-pop" role="dialog" aria-label="上下文占用">
      <div className="context-meter-body">
        {tokens === null ? <strong>还没有可用的占用数据</strong> : <>
          <strong className="context-meter-headline">{measured ? '输入' : '输入（估算）'} {formatTokens(tokens)}<i> / {formatTokens(window)}</i></strong>
          <div className="context-meter-bar" aria-hidden="true"><span className={high ? 'is-high' : undefined} style={{ width: `${percent ?? 0}%` }} /></div>
          <dl className="context-meter-rows">
            <div><dt>缓存命中</dt><dd>{hit === null ? '未回报' : <>{formatTokens(hit)}{hitShare === null ? null : <i> · 占输入 {hitShare}%</i>}</>}</dd></div>
            <div><dt>缓存未命中</dt><dd>{miss === null ? '未回报' : formatTokens(miss)}</dd></div>
            <div><dt>输出</dt><dd>{output === null ? '未回报' : formatTokens(output)}</dd></div>
          </dl>
        </>}
        {data?.model ? <p className="context-meter-meta">{data.model}</p> : null}
        {tokens === null ? <p>发送第一条消息后，这里显示供应商回报的实际长度。</p> : null}
        {measured && measured.contextWindow !== window ? <p>该请求发出时使用的模型窗口为 {formatTokens(measured.contextWindow)}，现按当前模型的窗口计算。</p> : null}
        {!measured && tokens !== null ? <p>供应商没有回报用量，这是发送前的本地估算，不含工具定义与工具结果。</p> : null}
        {high ? <p className="context-meter-warn">已接近模型窗口上限，较早的对话可能被摘要或省略。</p> : null}
      </div>
      <div className="context-meter-foot"><span>不含多次工具往返的累计用量</span>
        {requestId ? <button type="button" onClick={() => { setOpen(false); onInspect(requestId); }}>查看本轮上下文</button> : null}</div>
    </div> : null}
  </div>;
}
