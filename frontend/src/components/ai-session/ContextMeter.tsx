import { useEffect, useRef, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { get } from '../../lib/api';
import type { ContextTokenTotals } from '../../lib/aiContextApi';
import './context-meter.css';

export interface SessionContextUsage {
  sessionId: number;
  current: { tokens: number; estimated: boolean; complete: boolean; requestId: string | null;
    contextGeneration: number; historyRevision: number; active: boolean; estimatorVersion: string;
    summaryRevision: number | null; categories?: { kind: string; tokens: number }[] } | null;
  latest: { requestId: string; inputTokens: number; contextWindow: number; model: string | null;
    cacheReadTokens: number | null; outputTokens: number | null } | null;
  cumulative?: ContextTokenTotals;
  model: string | null;
  contextWindow: number | null;
  outputReserve: number | null;
}

interface Props {
  sessionId?: number;
  /** 回复增长时即使小卡片未打开，也刷新当前会话窗口占用。 */
  streaming?: boolean;
  /** 轮次状态变化时重新读取。 */
  refreshKey?: string;
  onInspect: (requestId: string) => void;
}

export const contextUsageKey = (sessionId?: number) => ['ai-context-usage', sessionId ?? 'new'] as const;
export const formatTokens = (value: number): string => value >= 1000 ? `${(value / 1000).toFixed(value >= 100000 ? 0 : 1)}k` : String(value);
/** 缺失不是 0；部分回报的累计只表示已知下界。 */
export const formatMeasuredTokens = (value: number | null | undefined, complete?: boolean): string =>
  value == null ? '未回报' : `${complete === false ? '≥ ' : ''}${formatTokens(value)}`;

/**
 * 当前会话保留的全局上下文用于窗口占用，输入 / 命中 / 未命中与输出使用会话累计的实测用量；
 * 窗口大小已经在第一行和进度条里，输出预留、分项估算、摘要与省略原因都不在这里重复，
 * 需要时去「查看上下文详情」看。
 */
export function ContextMeter({ sessionId, streaming = false, refreshKey, onInspect }: Props): JSX.Element | null {
  const [open, setOpen] = useState(false);
  const root = useRef<HTMLDivElement>(null);
  const usage = useQuery({ queryKey: [...contextUsageKey(sessionId), refreshKey ?? ''],
    queryFn: () => get<SessionContextUsage>(`/api/ai/sessions/${sessionId}/context-usage`), enabled: sessionId !== undefined,
    staleTime: 0, refetchInterval: open || streaming ? 1500 : false });
  const data = sessionId === undefined ? undefined : usage.data;
  const current = data?.current;
  const tokens = current && Number.isFinite(current.tokens) && current.tokens >= 0 ? current.tokens : null;
  const window = data?.contextWindow ?? null;
  const requestId = current?.requestId ?? data?.latest?.requestId;
  const cumulative = data?.cumulative;

  useEffect(() => { setOpen(false); }, [sessionId]);

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
  const ratio = tokens === null ? 0 : tokens / window;
  const percent = tokens === null ? null : Math.round(ratio * 100);
  const barPercent = Math.min(100, percent ?? 0);
  const high = ratio >= 0.8;
  const source = current?.estimated === false ? '实测' : '估算';
  const incomplete = current?.complete === false;
  // 累计命中率的分母是累计输入，不能拿最新请求长度或上下文窗口来除。
  const hit = cumulative?.cacheReadTokens ?? null;
  const miss = cumulative?.cacheMissTokens ?? null;
  const output = cumulative?.outputTokens ?? null;
  const hitShare = hit === null || !cumulative?.inputTokens || !cumulative.inputComplete || !cumulative.cacheReadComplete
    ? null : Math.round((hit / cumulative.inputTokens) * 100);
  const label = percent === null ? '上下文占用：尚无数据' : `上下文占用${incomplete ? '至少' : ''} ${percent}%（${source}）`;

  return <div className="context-meter" ref={root}>
    <button type="button" className={`context-meter-trigger${high ? ' is-high' : ''}`} aria-haspopup="dialog" aria-expanded={open}
      aria-label={label} title={label} onClick={() => { if (!open) void usage.refetch(); setOpen(!open); }}>
      <svg viewBox="0 0 24 24" aria-hidden="true"><circle cx="12" cy="12" r="9" className="context-meter-track" />
        <circle cx="12" cy="12" r="9" className="context-meter-value" strokeDasharray={`${Math.min(1, ratio) * 2 * Math.PI * 9} ${2 * Math.PI * 9}`} /></svg>
      <span>{percent === null ? '—' : `${incomplete ? '≥' : ''}${percent}%`}</span>
    </button>
    {open ? <div className="context-meter-pop" role="dialog" aria-label="上下文占用">
      <div className="context-meter-body">
        {tokens === null ? <strong>还没有可用的占用数据</strong> : <>
          <strong className="context-meter-headline">上下文总量 {incomplete ? '≥ ' : ''}{formatTokens(tokens)}<i> / {formatTokens(window)}</i></strong>
          <div className="context-meter-bar" aria-hidden="true"><span className={high ? 'is-high' : undefined} style={{ width: `${barPercent}%` }} /></div>
        </>}
        {tokens !== null || (cumulative?.requests ?? 0) > 0 ? <dl className="context-meter-rows"
          title={`会话累计输入 ${formatMeasuredTokens(cumulative?.inputTokens, cumulative?.inputComplete)} · 总 Token ${formatMeasuredTokens(cumulative?.totalTokens, cumulative?.totalComplete)}`}>
          <div><dt>输入总量</dt><dd>{formatMeasuredTokens(cumulative?.inputTokens, cumulative?.inputComplete)}</dd></div>
          <div><dt>缓存命中</dt><dd>{formatMeasuredTokens(hit, cumulative?.cacheReadComplete)}{hitShare === null ? null : <i> · 命中率 {hitShare}%</i>}</dd></div>
          <div><dt>缓存未命中</dt><dd>{formatMeasuredTokens(miss, cumulative?.cacheMissComplete)}</dd></div>
          <div><dt>输出</dt><dd>{formatMeasuredTokens(output, cumulative?.outputComplete)}</dd></div>
        </dl> : null}
        {incomplete ? <p>部分旧内容无法估算，显示已知占用下界。</p> : null}
        {high ? <p className="context-meter-warn">已接近模型窗口上限。</p> : null}
      </div>
      <div className="context-meter-foot">
        {data?.model ? <span className="context-meter-meta" title={data.model}>{data.model}</span> : null}
        {requestId ? <button type="button" onClick={() => { setOpen(false); onInspect(requestId); }}>查看上下文详情</button> : null}</div>
    </div> : null}
  </div>;
}
