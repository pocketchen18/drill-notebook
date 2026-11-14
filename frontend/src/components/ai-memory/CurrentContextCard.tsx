import { useQuery } from '@tanstack/react-query';
import { Gauge } from 'lucide-react';
import { get } from '../../lib/api';
import { contextUsageKey, formatTokens, type SessionContextUsage } from '../ai-session/ContextMeter';
import { CONTEXT_CATEGORIES, contextCategoryOf } from './RequestContextBrowser';
import './current-context-card.css';

/** 当前会话的保留上下文；独立于下方浏览器正在选择的历史请求。 */
export function CurrentContextCard({ sessionId, refreshKey }: { sessionId?: number; refreshKey?: string }) {
  const usage = useQuery({
    queryKey: [...contextUsageKey(sessionId), refreshKey ?? ''],
    queryFn: () => get<SessionContextUsage>(`/api/ai/sessions/${sessionId}/context-usage`),
    enabled: sessionId !== undefined,
    staleTime: 0,
    refetchInterval: 1500,
    retry: false
  });
  return <CurrentContextCardView data={sessionId === undefined ? undefined : usage.data}
    loading={usage.isPending && sessionId !== undefined} />;
}

export function CurrentContextCardView({ data, loading = false }: { data?: SessionContextUsage; loading?: boolean }) {
  const current = data?.current;
  const tokens = current && Number.isFinite(current.tokens) && current.tokens >= 0 ? current.tokens : null;
  const window = data?.contextWindow && data.contextWindow > 0 ? data.contextWindow : null;
  const percent = tokens !== null && window !== null ? Math.round(tokens / window * 100) : null;
  const incomplete = current?.complete === false;
  const categories = (current?.categories ?? []).filter(category => Number.isFinite(category.tokens) && category.tokens > 0)
    .sort((a, b) => Object.keys(CONTEXT_CATEGORIES).indexOf(a.kind) - Object.keys(CONTEXT_CATEGORIES).indexOf(b.kind));
  // 旧后端只回总量时不给它拼出一份历史请求的分类。
  const hasBreakdown = tokens !== null && categories.reduce((sum, category) => sum + category.tokens, 0) === tokens;
  const occupancy = percent === null ? 0 : Math.min(100, percent);
  const category = (kind: string) => kind === 'OTHER'
    ? { ...contextCategoryOf(kind), label: '其他内容' } : contextCategoryOf(kind);

  return <section className="current-context-card" aria-label="当前上下文">
    <div className="current-context-heading">
      <strong><Gauge size={14} aria-hidden="true" />当前上下文</strong>
      {data?.model ? <span title={data.model}>{data.model}</span> : null}
    </div>
    {tokens === null ? <p className="current-context-empty">
      {loading ? '正在读取当前上下文…' : '当前上下文暂不可用'}
    </p> : <>
      <div className="current-context-total">
        <span><strong>{incomplete ? '≥ ' : ''}{formatTokens(tokens)}</strong><small> / {window === null ? '窗口未知' : `${formatTokens(window)} tokens`}</small></span>
        {percent !== null ? <span className="current-context-percent"><strong>{incomplete ? '≥ ' : ''}{percent}%</strong><small>上下文已用</small></span> : null}
      </div>
      <div className="current-context-bar" role="meter" aria-label="当前上下文窗口占用"
        aria-valuemin={0} aria-valuemax={100} aria-valuenow={percent === null ? undefined : occupancy}
        aria-valuetext={percent === null ? '模型窗口未知' : `${incomplete ? '至少 ' : ''}${percent}%`}>
        {hasBreakdown && window !== null ? categories.map(item => <span key={item.kind}
          style={{ width: `${item.tokens / Math.max(tokens, window) * 100}%`, background: category(item.kind).color }}
          title={`${category(item.kind).label} ≈ ${formatTokens(item.tokens)}`} />)
          : window !== null ? <span style={{ width: `${occupancy}%`, background: 'var(--accent)' }} /> : null}
      </div>
      {hasBreakdown && categories.length ? <dl className="current-context-categories">
        {categories.map(item => <div key={item.kind} title={item.kind === 'OTHER' ? '包含消息封装及未保存分类的旧内容' : undefined}>
          <dt><i style={{ background: category(item.kind).color }} />{category(item.kind).label}</dt>
          <dd>≈ {formatTokens(item.tokens)}<span>{Math.round(item.tokens / tokens * 100)}%</span></dd>
        </div>)}
      </dl> : tokens > 0 ? <p className="current-context-empty">当前占用未提供分类明细</p> : null}
      {incomplete ? <p className="current-context-empty">部分旧内容无法统计，显示已知占用下界。</p> : null}
    </>}
  </section>;
}
