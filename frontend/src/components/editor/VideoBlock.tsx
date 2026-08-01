import { useEffect, useRef, useState } from 'react';
import { Message } from '@arco-design/web-react';
import { NodeViewWrapper, type NodeViewProps } from '@tiptap/react';
import { resolveEmbedUrl } from '../../lib/videoEmbed';
import { attachmentContentUrl } from '../../lib/attachments';

type View = 'link' | 'title' | 'preview';
type VideoType = 'url' | 'local' | 'remote';

interface VideoAttrs {
  videoType: VideoType;
  url: string | null;
  attachmentId: number | null;
  title: string;
  view: View;
}

export function VideoBlockNode({ node, updateAttributes, selected }: NodeViewProps): JSX.Element {
  const attrs = node.attrs as VideoAttrs;
  const [menuOpen, setMenuOpen] = useState(false);
  const [menuUpward, setMenuUpward] = useState(false);
  const [editing, setEditing] = useState(false);
  const [draft, setDraft] = useState('');
  // 当前编辑的字段：菜单「编辑标题」可在任意视图下修改标题
  const [editField, setEditField] = useState<'title' | 'url'>('title');
  const [localSrc, setLocalSrc] = useState('');
  // iframe 加载状态机：loading → loaded / error
  const [iframeState, setIframeState] = useState<'loading' | 'loaded' | 'error'>('loading');
  const iframeRef = useRef<HTMLIFrameElement>(null);
  const loadTimeoutRef = useRef<number | null>(null);
  const checkTimeoutRef = useRef<number | null>(null);
  // 使用本地 state 同步 view，避免 tiptap ReactNodeViewRenderer 不重渲染的问题
  const [localView, setLocalView] = useState<View>(attrs.view);
  const clickTimer = useRef<number | null>(null);
  const handleRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    setLocalView(attrs.view);
    setMenuOpen(false);
  }, [attrs.view]);

  useEffect(() => {
    if (attrs.videoType === 'local' && attrs.attachmentId !== null) {
      void attachmentContentUrl(attrs.attachmentId).then(setLocalSrc);
    }
  }, [attrs.videoType, attrs.attachmentId]);

  // 网址视频：未自定义标题（标题为空或与 URL 相同）时自动抓取原始标题
  useEffect(() => {
    if (attrs.videoType !== 'url') return;
    const url = attrs.url;
    if (!url) return;
    if (attrs.title && attrs.title !== url) return;
    const fetchTitle = window.api?.video?.fetchTitle;
    if (!fetchTitle) return;
    let cancelled = false;
    void fetchTitle(url)
      .then((fetched) => { if (!cancelled && fetched) updateAttributes({ title: fetched }); })
      .catch(() => { /* 抓取失败时保留 URL 作为标题 */ });
    return () => { cancelled = true; };
  }, [attrs.videoType, attrs.url]);

  // 点击菜单外部时自动收起
  useEffect(() => {
    if (!menuOpen) return;
    const onMouseDown = (event: MouseEvent): void => {
      if (handleRef.current && !handleRef.current.contains(event.target as Node)) {
        setMenuOpen(false);
      }
    };
    document.addEventListener('mousedown', onMouseDown);
    return () => document.removeEventListener('mousedown', onMouseDown);
  }, [menuOpen]);

  // 组件卸载时清理 iframe 定时器
  useEffect(() => {
    return () => {
      if (loadTimeoutRef.current) window.clearTimeout(loadTimeoutRef.current);
      if (checkTimeoutRef.current) window.clearTimeout(checkTimeoutRef.current);
    };
  }, []);

  const openExternal = (): void => {
    if (attrs.videoType === 'url' || attrs.videoType === 'remote') {
      const target = attrs.url ?? '';
      if (target) void window.api?.shell.openExternal(target);
    } else if (attrs.videoType === 'local' && localSrc) {
      void window.api?.shell.openExternal(localSrc);
    }
  };

  const handleIframeLoad = (): void => {
    // 2s 后检查 iframe 内容是否正常
    checkTimeoutRef.current = window.setTimeout(() => {
      try {
        const doc = iframeRef.current?.contentDocument;
        const body = doc?.body;
        // 能访问到内容但空白 → 加载失败（安全策略阻止了内容渲染）
        if (body && body.innerText.trim() === '' && !body.querySelector('iframe, video, img, script')) {
          setIframeState('error');
          return;
        }
      } catch {
        // 跨域访问 contentDocument 抛异常 → 说明内容正常加载（跨域安全限制），视为成功
      }
      setIframeState('loaded');
    }, 2000);

    // 10s 兜底超时
    loadTimeoutRef.current = window.setTimeout(() => {
      setIframeState((prev) => prev === 'loading' ? 'error' : prev);
    }, 10000);
  };

  const handleClick = (): void => {
    if (editing) return;
    if (clickTimer.current) window.clearTimeout(clickTimer.current);
    clickTimer.current = window.setTimeout(() => { openExternal(); }, 250);
  };

  const handleDoubleClick = (): void => {
    if (clickTimer.current) { window.clearTimeout(clickTimer.current); clickTimer.current = null; }
    const field = localView === 'title' ? 'title' : 'url';
    setEditField(field);
    setDraft(field === 'title' ? (attrs.title || '') : (attrs.url ?? ''));
    setEditing(true);
  };

  const startEditTitle = (): void => {
    setMenuOpen(false);
    setEditField('title');
    setDraft(attrs.title || '');
    setEditing(true);
  };

  const commitEdit = (): void => {
    setEditing(false);
    if (editField === 'title') {
      updateAttributes({ title: draft });
    } else {
      updateAttributes({ url: draft });
    }
  };

  const switchView = (view: View): void => {
    setLocalView(view);
    updateAttributes({ view });
    setMenuOpen(false);
    // 切换回 preview 时重置 iframe 状态
    if (view === 'preview') {
      setIframeState('loading');
    }
  };

  const toggleMenu = (event: React.MouseEvent): void => {
    event.stopPropagation();
    const nextOpen = !menuOpen;
    if (nextOpen && handleRef.current) {
      const rect = handleRef.current.getBoundingClientRect();
      // 估算菜单高度 ~220px，检查下方空间是否足够
      setMenuUpward(rect.bottom + 220 > window.innerHeight);
    }
    setMenuOpen(nextOpen);
  };

  const renderEditInput = (): JSX.Element => (
    <input
      className="video-link-input"
      value={draft}
      autoFocus
      onChange={(event) => setDraft(event.target.value)}
      onKeyDown={(event) => {
        event.stopPropagation();
        if (event.key === 'Enter') { event.preventDefault(); commitEdit(); }
        if (event.key === 'Escape') { setEditing(false); }
      }}
      onBlur={commitEdit}
    />
  );

  const renderLinkOrTitleText = (): JSX.Element => {
    const text = localView === 'title' ? (attrs.title || '（无标题）') : (attrs.url || '（无 URL）');
    return (
      <span
        className="video-link-text"
        onClick={handleClick}
        onDoubleClick={handleDoubleClick}
        title="单击打开，双击编辑"
      >
        {text}
      </span>
    );
  };

  const renderLoadingSkeleton = (platform: string | null): JSX.Element => (
    <div className="video-embed-loading">
      <div className="video-embed-loading-spinner" />
      <span>正在加载视频…</span>
      <span className="video-embed-loading-platform">
        {platform === 'youtube' ? 'YouTube' : platform === 'bilibili' ? 'Bilibili' : ''}
      </span>
    </div>
  );

  const renderErrorCard = (url: string, platform: string | null, reason: string): JSX.Element => {
    const platformLabel = platform === 'youtube' ? 'YouTube' : platform === 'bilibili' ? '哔哩哔哩' : '视频';
    return (
      <div className="video-embed-error-card">
        <div className="video-embed-error-header">
          <span className="video-embed-error-icon">
            {platform === 'youtube' ? '▶️' : platform === 'bilibili' ? '📺' : '📹'}
          </span>
          <span className="video-embed-error-platform">{platformLabel}</span>
        </div>
        <div className="video-embed-error-title">{attrs.title || url}</div>
        <div className="video-embed-error-reason">{reason}</div>
        <div className="video-embed-error-actions">
          <button type="button" className="video-link-btn" onClick={openExternal}>🌐 在浏览器打开</button>
          <button type="button" className="video-link-btn" onClick={() => { void navigator.clipboard?.writeText(url); Message.success('链接已复制'); }}>📋 复制链接</button>
        </div>
      </div>
    );
  };

  const renderPreviewView = (): JSX.Element => {
    if (attrs.videoType === 'url') {
      const url = attrs.url ?? '';
      if (!url) return <div className="video-embed-fallback">没有视频 URL</div>;
      const embed = resolveEmbedUrl(url);
      if (!embed.embedUrl) {
        return renderErrorCard(url, embed.platform, '该网址不支持嵌入预览');
      }
      // YouTube 嵌入在桌面端（file:// origin）会被 YouTube 拦截：缺 Referer 报 153、
      // 伪造 Referer 报 152，无法稳定预览（与飞书等在线文档表现一致），
      // 故不再加载 iframe，统一显示友好提示卡片并引导跳转浏览器观看。
      if (embed.platform === 'youtube') {
        return renderErrorCard(url, embed.platform, '该网站暂不支持预览');
      }
      if (iframeState === 'error') {
        return renderErrorCard(url, embed.platform, '当前环境不支持直接嵌入预览');
      }
      // 始终渲染 iframe 使其挂载 DOM，onLoad 才能触发；
      // loading 时叠加骨架屏覆盖层，loaded 后覆盖层消失
      return (
        <div className="video-embed-preview-wrap">
          {iframeState === 'loading' ? (
            <div className="video-embed-loading-overlay">
              {renderLoadingSkeleton(embed.platform)}
            </div>
          ) : null}
          <iframe
            ref={iframeRef}
            className="video-embed-iframe"
            src={embed.embedUrl}
            title={attrs.title || url || '视频'}
            allow="fullscreen; encrypted-media; autoplay; picture-in-picture"
            allowFullScreen
            frameBorder="0"
            referrerPolicy="strict-origin-when-cross-origin"
            onLoad={handleIframeLoad}
          />
        </div>
      );
    }
    if (attrs.videoType === 'remote') {
      const url = attrs.url ?? '';
      if (!url) return <div className="video-embed-fallback">没有视频 URL</div>;
      return (
        <div className="video-player-wrap" onMouseDown={(e) => e.stopPropagation()}>
          <video className="video-embed-video" src={url} controls onError={() => console.warn('[video] remote load failed', url)} />
        </div>
      );
    }
    // local
    if (!localSrc) return <div className="video-embed-fallback">加载中…</div>;
    return (
      <div className="video-player-wrap" onMouseDown={(e) => e.stopPropagation()}>
        <video className="video-embed-video" src={localSrc} controls />
      </div>
    );
  };

  const isPreview = localView === 'preview';

  return (
    <NodeViewWrapper
      className={`video-block${selected ? ' is-selected' : ''}`}
      contentEditable={false}
      data-video-block="true"
    >
      <div className="video-block-handle" ref={handleRef}>
        <button
          type="button"
          className="video-block-handle-btn"
          contentEditable={false}
          onClick={toggleMenu}
          title="切换视图"
        >▾</button>
        {menuOpen ? (
          <div className={`video-block-menu${menuUpward ? ' is-upward' : ''}`} contentEditable={false}>
            <div className="video-block-menu-label">视图</div>
            {(['link', 'title', 'preview'] as View[]).map((view) => (
              <button
                key={view}
                type="button"
                className={`video-block-menu-item${localView === view ? ' is-active' : ''}`}
                onClick={(event) => { event.stopPropagation(); switchView(view); }}
              >{viewLabel(view)}</button>
            ))}
            <div className="video-block-menu-sep" />
            <button type="button" className="video-block-menu-item" onClick={(event) => { event.stopPropagation(); startEditTitle(); }}>编辑标题</button>
            {(attrs.videoType === 'url' || attrs.videoType === 'remote') ? (
              <>
                <button type="button" className="video-block-menu-item" onClick={(event) => { event.stopPropagation(); void navigator.clipboard?.writeText(attrs.url ?? ''); setMenuOpen(false); }}>复制链接</button>
                <button type="button" className="video-block-menu-item" onClick={(event) => { event.stopPropagation(); openExternal(); setMenuOpen(false); }}>在浏览器打开</button>
              </>
            ) : null}
          </div>
        ) : null}
      </div>
      <div className={`video-block-body${isPreview ? ' is-preview' : ''}`}>
        {isPreview ? (
          <>
            {editing ? renderEditInput() : null}
            {renderPreviewView()}
          </>
        ) : (
          editing ? renderEditInput() : renderLinkOrTitleText()
        )}
      </div>
    </NodeViewWrapper>
  );
}

function viewLabel(view: View): string {
  if (view === 'link') return '链接视图';
  if (view === 'title') return '标题视图';
  return '预览视图';
}
