import { useEffect, useId, useLayoutEffect, useMemo, useRef, useState } from 'react';
import type { CSSProperties, PointerEvent } from 'react';
import type { RoundPreview } from '../../lib/conversationNavigation';
import { previewText } from '../../lib/conversationNavigation';
import { parseChatTimestamp } from '../../lib/chatTimestamp';

const MAX_VISIBLE_ROUNDS = 64;
const TICK_STEP = 10;
const RAIL_PADDING = 8;
const MAX_RAIL_HEIGHT = RAIL_PADDING * 2 + (MAX_VISIBLE_ROUNDS - 1) * TICK_STEP;
type ExcerptCache = Map<string, { layout: string; excerpt: string }>;

/** 用实际排版计算截断点，明确保留三个点，不截断 Unicode 码点。 */
function PreviewExcerpt({ text, lines, cache, answer = false }: {
  text: string; lines: number; cache: ExcerptCache; answer?: boolean;
}): JSX.Element {
  const wrapper = useRef<HTMLSpanElement>(null);
  const measure = useRef<HTMLSpanElement>(null);
  const [excerpt, setExcerpt] = useState(text);
  useLayoutEffect(() => {
    const update = () => {
      const node = measure.current;
      const width = node?.getBoundingClientRect().width;
      if (!node || !width) { setExcerpt(text); return; }
      const style = window.getComputedStyle(node);
      const lineHeight = Number.parseFloat(style.lineHeight) || (Number.parseFloat(style.fontSize) || 13) * 1.65;
      const layout = [width, lines, lineHeight, style.fontFamily, style.fontSize, style.fontWeight, style.letterSpacing].join('|');
      const cached = cache.get(text);
      if (cached?.layout === layout) { setExcerpt(cached.excerpt); return; }
      const fits = (value: string) => {
        node.dataset.text = value;
        return node.scrollHeight <= Math.ceil(lineHeight * lines) + 1;
      };
      const characters = Array.from(text);
      // 先测短前缀；长回答不必整段写入 DOM 后再反复强制排版。
      let bound = Math.min(128, characters.length);
      while (bound < characters.length && fits(characters.slice(0, bound).join(''))) {
        bound = Math.min(characters.length, bound * 2);
      }
      let result = text;
      if (bound < characters.length || !fits(text)) {
        let low = 0, high = bound;
        while (low < high) {
          const middle = Math.ceil((low + high) / 2);
          if (fits(characters.slice(0, middle).join('').trimEnd() + '...')) low = middle;
          else high = middle - 1;
        }
        result = characters.slice(0, low).join('').trimEnd() + '...';
      }
      cache.delete(text); cache.set(text, { layout, excerpt: result });
      if (cache.size > MAX_VISIBLE_ROUNDS * 2) cache.delete(cache.keys().next().value!);
      setExcerpt(result);
    };
    update();
    if (typeof ResizeObserver === 'undefined' || !wrapper.current) return;
    const observer = new ResizeObserver(update); observer.observe(wrapper.current);
    return () => observer.disconnect();
  }, [text, lines, cache]);
  return <span ref={wrapper} className={`ai-conversation-excerpt${answer ? ' is-answer' : ''}`}
    style={{ '--preview-lines': lines } as CSSProperties}>
    <span className="ai-conversation-excerpt-content">{excerpt}</span>
    <span ref={measure} className="ai-conversation-excerpt-measure" aria-hidden="true" />
  </span>;
}

/** 整条刻度只有一个定位控件，卡片整体可点击；视觉刻度不制造额外停靠点。 */
export function ConversationNavigation({ total, reading, hidden, getPreview, onJump }: {
  total: number; reading: number; hidden?: boolean;
  getPreview: (round: number) => Promise<RoundPreview>;
  onJump: (preview: RoundPreview) => void | Promise<void>;
}): JSX.Element | null {
  const rail = useRef<HTMLDivElement>(null);
  const previewButton = useRef<HTMLButtonElement>(null);
  const range = useRef<HTMLInputElement>(null);
  const callbacks = useRef({ getPreview, onJump });
  callbacks.current = { getPreview, onJump };
  const [candidate, setCandidate] = useState(reading || total);
  const [open, setOpen] = useState(false);
  const [preview, setPreview] = useState<RoundPreview | null>(null);
  const [error, setError] = useState('');
  const [top, setTop] = useState<number>();
  const [capacity, setCapacity] = useState(MAX_VISIBLE_ROUNDS);
  const [windowStart, setWindowStart] = useState(Math.max(1, Math.min(total - MAX_VISIBLE_ROUNDS + 1, (reading || total) - MAX_VISIBLE_ROUNDS + 1)));
  const windowStartRef = useRef(windowStart);
  windowStartRef.current = windowStart;
  const wheelRemainder = useRef(0);
  const wheelAction = useRef<(event: WheelEvent) => void>(() => undefined);
  const previewId = useId();
  const showTimer = useRef<number>();
  const closeTimer = useRef<number>();
  const hoverFrame = useRef<number>();
  const hoverRound = useRef(candidate);
  const openRef = useRef(open);
  openRef.current = open;
  const previewRef = useRef(preview);
  previewRef.current = preview;
  const loadingRound = useRef<number>();
  const excerptCache = useRef<ExcerptCache>(new Map());
  const request = useRef(0);
  const selected = useRef(candidate);
  const clamp = (value: number) => Math.max(1, Math.min(total, value));
  const visibleCount = Math.min(total, capacity);
  const railHeight = RAIL_PADDING * 2 + Math.max(0, visibleCount - 1) * TICK_STEP;
  const maxStart = Math.max(1, total - visibleCount + 1);
  const contain = (start: number, round: number) => Math.max(1, Math.min(maxStart,
    round < start ? round : round >= start + visibleCount ? round - visibleCount + 1 : start));
  const moveWindow = (start: number) => {
    const next = Math.max(1, Math.min(maxStart, start));
    windowStartRef.current = next; setWindowStart(next);
  };
  const clearTimers = () => {
    window.clearTimeout(showTimer.current); window.clearTimeout(closeTimer.current);
    if (hoverFrame.current !== undefined) window.cancelAnimationFrame(hoverFrame.current);
    showTimer.current = closeTimer.current = hoverFrame.current = undefined;
  };
  const close = () => { clearTimers(); request.current++; loadingRound.current = undefined; openRef.current = false; setOpen(false); };
  const keepOpen = () => window.clearTimeout(closeTimer.current);
  const delayClose = () => {
    window.clearTimeout(showTimer.current);
    showTimer.current = undefined;
    if (hoverFrame.current !== undefined) window.cancelAnimationFrame(hoverFrame.current);
    hoverFrame.current = undefined;
    closeTimer.current = window.setTimeout(() => {
      if (document.activeElement !== range.current && document.activeElement !== previewButton.current) close();
    }, 180);
  };
  useEffect(() => () => { clearTimers(); request.current++; }, []);
  useEffect(() => {
    if (hidden || total < 2) close();
    if (!open) { selected.current = clamp(reading || total); setCandidate(selected.current); }
    else if (candidate > total) close();
  }, [hidden, total, reading, open]);
  useEffect(() => {
    moveWindow(contain(windowStartRef.current, clamp(open ? selected.current : reading || total)));
  }, [reading, total, capacity]);
  useLayoutEffect(() => {
    if (!rail.current || hidden || total < 2) return;
    const parent = rail.current.parentElement;
    if (!parent) return;
    const update = () => {
      const available = Math.min(MAX_RAIL_HEIGHT, parent.clientHeight ? Math.max(0, parent.clientHeight - 64) : MAX_RAIL_HEIGHT);
      setCapacity(Math.min(MAX_VISIBLE_ROUNDS, Math.max(2, Math.floor((available - RAIL_PADDING * 2) / TICK_STEP) + 1)));
    };
    update();
    if (typeof ResizeObserver === 'undefined') return;
    const observer = new ResizeObserver(update); observer.observe(parent);
    return () => observer.disconnect();
  }, [hidden, total]);
  useLayoutEffect(() => {
    const receive: EventListener = event => wheelAction.current(event as WheelEvent);
    const targets = [rail.current, previewButton.current].filter((node): node is HTMLDivElement | HTMLButtonElement => node !== null);
    targets.forEach(node => node.addEventListener('wheel', receive, { passive: false }));
    return () => targets.forEach(node => node.removeEventListener('wheel', receive));
  }, [hidden, open, total]);
  useLayoutEffect(() => {
    if (!open || !rail.current || !previewButton.current) return;
    const parent = rail.current.parentElement;
    if (!parent) return;
    const center = (parent.clientHeight - railHeight) / 2 + RAIL_PADDING + (candidate - windowStart) * TICK_STEP;
    setTop(Math.max(8, Math.min(parent.clientHeight - previewButton.current.offsetHeight - 8,
      center - previewButton.current.offsetHeight / 2)));
  }, [open, candidate, preview, error, windowStart, railHeight]);

  async function show(round: number, jump = false) {
    clearTimers();
    const chosen = clamp(round);
    if (!jump && openRef.current && chosen === selected.current &&
      (previewRef.current?.roundNumber === chosen || loadingRound.current === chosen)) return;
    moveWindow(contain(windowStartRef.current, chosen));
    selected.current = chosen; setCandidate(chosen); setError('');
    if (!jump) { openRef.current = true; setOpen(true); }
    loadingRound.current = chosen;
    const identity = ++request.current;
    try {
      const value = await callbacks.current.getPreview(chosen);
      if (request.current !== identity) return;
      loadingRound.current = undefined;
      if (jump) { close(); await callbacks.current.onJump(value); }
      else setPreview(value);
    } catch (failure) {
      if (request.current !== identity) return;
      loadingRound.current = undefined;
      openRef.current = true; setOpen(true); setPreview(null); setError(failure instanceof Error ? failure.message : '预览暂时无法读取');
    }
  }
  const position = (event: PointerEvent<HTMLInputElement>) => {
    const bounds = event.currentTarget.getBoundingClientRect();
    return clamp(windowStartRef.current + Math.round(Math.max(0, Math.min(visibleCount - 1,
      (event.clientY - bounds.top - RAIL_PADDING) / TICK_STEP))));
  };
  wheelAction.current = event => {
    if (!event.deltaY || total <= visibleCount) return;
    event.preventDefault(); event.stopPropagation();
    wheelRemainder.current += event.deltaY * (event.deltaMode === 1 ? 1 : event.deltaMode === 2 ? visibleCount : 1 / 32);
    const steps = Math.trunc(wheelRemainder.current);
    if (!steps) return;
    wheelRemainder.current -= steps;
    const previous = windowStartRef.current;
    const next = Math.max(1, Math.min(maxStart, previous + steps));
    if (next === previous) return;
    const offset = Math.max(0, Math.min(visibleCount - 1, selected.current - previous));
    moveWindow(next);
    void show(next + offset);
  };
  const text = useMemo(() => ({
    question: preview ? previewText(preview.question) || '图片或附件提问' : '',
    answer: preview ? previewText(preview.answer) || (preview.status === 'RUNNING' ? '正在生成...' : '暂无回答') : ''
  }), [preview]);
  const currentPreview = preview?.roundNumber === candidate ? preview : null;
  const timestamp = parseChatTimestamp(currentPreview?.createdAt);
  const time = timestamp?.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }) ?? '';
  if (hidden || total < 2) return null;
  return <>
    <div className="ai-conversation-rail" ref={rail} style={{ height: railHeight }}
      data-first-round={windowStart} data-last-round={windowStart + visibleCount - 1}>
      <div className="ai-conversation-ticks" aria-hidden="true">
        {Array.from({ length: visibleCount }, (_, index) => {
          const round = windowStart + index;
          const distance = Math.abs(round - candidate);
          const width = open && distance < 5 ? 24 - distance * 4 : round === reading ? 8 : 6;
          return <span key={round} data-round={round}
            className={`ai-conversation-tick${round === reading ? ' is-reading' : ''}${open && round === candidate ? ' is-preview' : ''}`}
            style={{ top: index * TICK_STEP, width, opacity: open && distance < 5 ? 1 - distance * 0.12 : round === reading ? 0.8 : 0.4 }} />;
        })}
      </div>
      <input ref={range} className="ai-conversation-range" type="range" min={1} max={total} step={1} value={candidate}
        aria-label="会话内对话跳转" aria-orientation="vertical" aria-valuetext={`第 ${candidate} / ${total} 轮`}
        aria-controls={previewId} onChange={event => void show(Number(event.target.value))}
        onFocus={() => void show(selected.current)} onBlur={delayClose} onPointerLeave={delayClose}
        onPointerMove={event => {
          keepOpen();
          const chosen = position(event);
          hoverRound.current = chosen;
          if (openRef.current) {
            if (chosen === selected.current || hoverFrame.current !== undefined) return;
            hoverFrame.current = window.requestAnimationFrame(() => {
              hoverFrame.current = undefined;
              if (hoverRound.current !== selected.current) void show(hoverRound.current);
            });
          } else if (showTimer.current === undefined) {
            showTimer.current = window.setTimeout(() => {
              showTimer.current = undefined;
              void show(hoverRound.current);
            }, 150);
          }
        }}
        onPointerDown={event => {
          event.preventDefault();
          range.current?.blur();
          void show(position(event), event.pointerType !== 'touch');
        }}
        onKeyDown={event => {
          if (!['ArrowUp', 'ArrowDown', 'ArrowLeft', 'ArrowRight', 'Home', 'End', 'Enter', ' ', 'Escape'].includes(event.key)) return;
          event.preventDefault(); event.stopPropagation();
          if (event.key === 'Escape') close();
          else if (event.key === 'Enter' || event.key === ' ') void show(selected.current, true);
          else if (event.key === 'Home') void show(1);
          else if (event.key === 'End') void show(total);
          else void show(selected.current + (event.key === 'ArrowUp' || event.key === 'ArrowLeft' ? -1 : 1));
        }} />
    </div>
    {open && <button id={previewId} ref={previewButton} type="button" className="ai-conversation-preview"
      style={{ top }} aria-label={`第 ${candidate} / ${total} 轮对话预览，点击定位`}
      onPointerEnter={keepOpen} onPointerLeave={delayClose} onFocus={keepOpen} onBlur={delayClose}
      onKeyDown={event => { if (event.key === 'Escape') { event.preventDefault(); event.stopPropagation(); range.current?.focus({ preventScroll: true }); close(); } }}
      onClick={() => { if (currentPreview) { close(); void callbacks.current.onJump(currentPreview); } else void show(candidate, true); }}>
      <span hidden={!currentPreview}>
        <span className="ai-conversation-preview-meta">
          <span>第 {candidate} / {total} 轮</span>
          {time && <time dateTime={timestamp?.toISOString()}>{time}</time>}
        </span>
        <PreviewExcerpt text={text.question} lines={2} cache={excerptCache.current} />
        <PreviewExcerpt text={text.answer} lines={3} cache={excerptCache.current} answer />
      </span>
      <span hidden={!!currentPreview}><span className="ai-conversation-preview-loading" role={error ? 'alert' : 'status'}>{error || '正在读取预览…'}</span></span>
    </button>}
  </>;
}
