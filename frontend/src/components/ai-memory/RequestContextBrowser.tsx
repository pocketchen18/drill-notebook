import { useEffect, useMemo, useRef, useState } from 'react';
import { readTurnContext } from '../../lib/aiContextApi';
import type { RequestContextPage } from '../../lib/aiContextApi';
import { formatMeasuredTokens, formatTokens } from '../ai-session/ContextMeter';
import './context-request-browser.css';

export const CONTEXT_CATEGORIES: Record<string, { label: string; color: string }> = {
  SYSTEM: { label: '系统提示词', color: '#8174d9' },
  TOOL_DEFINITIONS: { label: '工具定义', color: '#d59a43' },
  USER: { label: '用户消息', color: '#69a769' },
  INJECTED_CONTEXT: { label: '注入内容', color: '#9c80be' },
  ASSISTANT: { label: '助手消息', color: '#629bd1' },
  TOOL_CALLS: { label: '工具调用参数', color: '#d67d54' },
  TOOL_RESULTS: { label: '工具结果', color: '#55aaa1' },
  IMAGE: { label: '图片', color: '#b276a6' },
  OTHER: { label: '消息封装', color: '#8a919c' }
};
export const contextCategoryOf = (kind: string) => CONTEXT_CATEGORIES[kind] ?? { label: '其他内容', color: '#8a919c' };
const categoryOf = contextCategoryOf;
const timeOf = (value: string) => {
  const time = new Date(value);
  return Number.isNaN(time.getTime()) ? '' : time.toLocaleTimeString('zh-CN', { hour12: false });
};
const deltaText = (delta: number) => `${delta > 0 ? '+' : delta < 0 ? '−' : ''}${formatTokens(Math.abs(delta))}`;

/** 选择已发请求查看组成；展开和对比只用本地已读的数值摘要。 */
export function RequestContextBrowser({ requestId, page }: { requestId: string; page: RequestContextPage }) {
  return <RequestContextBrowserBody key={requestId} requestId={requestId} page={page} />;
}

function RequestContextBrowserBody({ requestId, page }: { requestId: string; page: RequestContextPage }) {
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [earlier, setEarlier] = useState<RequestContextPage | null>(null);
  const [compare, setCompare] = useState(false);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [visited, setVisited] = useState(page.items);
  const epoch = useRef(0);
  useEffect(() => () => { epoch.current++; }, []);
  useEffect(() => {
    setVisited(current => {
      if (!earlier && !selectedId) return page.items;
      return [...new Map([...current, ...page.items].map(item => [item.attemptId, item])).values()];
    });
  }, [page.items, earlier, selectedId]);
  const entries = useMemo(() => {
    const byId = new Map([...(earlier || selectedId ? visited : []), ...(earlier?.items ?? []), ...page.items]
      .map(item => [item.attemptId, item]));
    return [...byId.values()].sort((a, b) => a.ordinal - b.ordinal);
  }, [earlier, page.items, selectedId, visited]);
  const selected = entries.find(item => item.attemptId === selectedId) ?? entries.at(-1);
  const index = entries.findIndex(item => item.attemptId === selected?.attemptId);
  const previous = index > 0 ? entries[index - 1] : undefined;
  const snapshot = selected?.context;
  const compared = compare && snapshot && previous?.context ? previous.context : null;
  const paging = earlier ?? page;

  async function loadEarlier() {
    const cursor = paging.nextBeforeAttemptId;
    if (!cursor || loading) return;
    const run = ++epoch.current;
    setLoading(true); setError(null);
    try {
      const response = await readTurnContext(requestId, cursor);
      if (run !== epoch.current) return;
      const next = response.requestContexts;
      if (!next || response.requestId !== requestId) throw new Error('请求详情没有返回');
      setEarlier(current => ({ ...next, items: [...next.items, ...(current?.items ?? [])] }));
      setSelectedId(current => next.items.at(-1)?.attemptId ?? current);
    } catch {
      if (run === epoch.current) setError('更早的请求详情读取失败，可重新选择加载。');
    } finally { if (run === epoch.current) setLoading(false); }
  }

  if (!selected) return null;
  const currentCategories = snapshot?.categories ?? [];
  const categories = [...new Set([...currentCategories, ...(compared?.categories ?? [])].map(item => item.kind))]
    .sort((a, b) => Object.keys(CONTEXT_CATEGORIES).indexOf(a) - Object.keys(CONTEXT_CATEGORIES).indexOf(b))
    .map(kind => currentCategories.find(item => item.kind === kind)
      ?? { kind, estimatedTokens: 0, itemCount: 0, items: [] });
  const total = snapshot?.estimatedInputTokens ?? 0;
  return <section className="context-request-browser" aria-label="上下文浏览器">
    <div className="context-request-heading"><strong>上下文浏览器</strong><span>本轮 {page.totalRequests} 次请求</span></div>
    <div className="context-request-controls">
      <select aria-label="模型请求" value={selected.attemptId} disabled={loading} onChange={event => {
        if (event.target.value === 'older') { void loadEarlier(); return; }
        setSelectedId(event.target.value === entries.at(-1)?.attemptId ? null : event.target.value);
      }}>
        {[...entries].reverse().map(item => <option key={item.attemptId} value={item.attemptId}>
          第 {item.ordinal} 次请求 · {timeOf(item.createdAt)}{item.context?.kind === 'NATIVE_WEB' ? ' · 原生联网' : ''}
        </option>)}
        {paging.hasMore ? <option value="older">加载更早请求…</option> : null}
      </select>
      <button type="button" aria-pressed={compare} disabled={!snapshot || !previous?.context}
        onClick={() => setCompare(!compare)}>对比上次请求</button>
    </div>
    {error ? <p className="ai-inspector-error">{error}</p> : null}
    <p className="context-request-actual">实测输入 {formatMeasuredTokens(selected.inputTokens, selected.inputComplete)} · 输出 {formatMeasuredTokens(selected.outputTokens, selected.outputComplete)}</p>
    {snapshot ? <>
      <div className="context-request-estimate"><span>估算合计 ≈ {formatTokens(total)}</span>
        {snapshot.model ? <span title={snapshot.model}>{snapshot.model}</span> : null}</div>
      <div className="context-request-bar" role="img" aria-label="该请求的上下文组成（本地估算）">
        {currentCategories.map(category => <span key={category.kind} style={{ width: `${total > 0 ? category.estimatedTokens / total * 100 : 0}%`, background: categoryOf(category.kind).color }}
          title={`${categoryOf(category.kind).label} ≈ ${formatTokens(category.estimatedTokens)}`} />)}
      </div>
      <div className="context-request-groups">
        {categories.map(category => {
          const previousCategory = compared?.categories.find(item => item.kind === category.kind);
          const delta = category.estimatedTokens - (previousCategory?.estimatedTokens ?? 0);
          const countDelta = category.itemCount - (previousCategory?.itemCount ?? 0);
          const shown = category.items.reduce((sum, item) => sum + item.estimatedTokens, 0);
          return <details key={category.kind} className="context-request-group">
            <summary><span className="context-request-category"><i style={{ background: categoryOf(category.kind).color }} />
              {categoryOf(category.kind).label}<small>{category.itemCount} 项</small>
              {compared && countDelta !== 0 ? <em className={countDelta > 0 ? 'is-added' : 'is-removed'}>{countDelta > 0 ? '+' : ''}{countDelta}</em> : null}</span>
              <span className="context-request-value">{compared ? <em className={delta > 0 ? 'is-added' : delta < 0 ? 'is-removed' : ''}>{deltaText(delta)}</em> : null}
                ≈ {formatTokens(category.estimatedTokens)}<small>{total > 0 ? Math.round(category.estimatedTokens / total * 100) : 0}%</small></span>
            </summary>
            <ul>{[...category.items].sort((a, b) => b.estimatedTokens - a.estimatedTokens).map((item, itemIndex) => <li key={item.key}>
              <span>{item.name ?? `${categoryOf(category.kind).label} ${itemIndex + 1}`}
                {item.key.startsWith('message-') ? <small> · 消息 {Number(item.key.split('-')[1]) + 1}</small> : null}</span>
              <span>≈ {formatTokens(item.estimatedTokens)}</span>
            </li>)}</ul>
            {category.itemCount > category.items.length ? <p className="context-request-omitted">另 {category.itemCount - category.items.length} 项 · ≈ {formatTokens(Math.max(0, category.estimatedTokens - shown))}，已计入分类合计。</p> : null}
          </details>;
        })}
      </div>
      <p className="context-request-note">分项为本地估算，实际用量来自供应商。</p>
    </> : <p className="context-request-note">这次请求未保存组成明细；实测用量仍按供应商回报展示。</p>}
  </section>;
}
