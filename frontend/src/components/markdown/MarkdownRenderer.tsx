import { useEffect, useRef, type MouseEvent } from 'react';
import DOMPurify from 'dompurify';
import MarkdownIt from 'markdown-it';
import { renderToString } from 'katex';
import { ensureMermaidTheme, readDocumentTheme } from '../../lib/mermaidTheme';
import { renderMermaid } from '../../lib/mermaidRender';
import { useUiStore } from '../../stores/uiStore';
import { materialLinkTarget } from '../../lib/pageSnapshot';
import { canOpenExternally, openExternalLink } from '../../lib/externalLink';

const markdown = new MarkdownIt({
  breaks: true,
  html: false,
  linkify: true,
  typographer: true
});

// 内部会话引用转成页内链接，由调用方处理；不放宽 HTML 清洗器的协议白名单。
const defaultLinkOpen = markdown.renderer.rules.link_open;
markdown.renderer.rules.link_open = (tokens, index, options, env, renderer) => {
  const token = tokens[index];
  const ref = /^drill-notebook:\/\/session\/([a-f0-9]{32})$/.exec(token.attrGet('href') ?? '')?.[1];
  if (ref) {
    token.attrSet('href', `#ai-session-${ref}`);
    token.attrSet('data-session-ref', ref);
  }
  const material = materialLinkTarget(token.attrGet('href') ?? '');
  if (material) {
    token.attrSet('href', `#ai-material-${material.type}-${material.id}`);
    token.attrSet('data-material-link', `drill-notebook://material/${material.type}/${material.id}`);
  }
  const href = token.attrGet('href') ?? '';
  if (canOpenExternally(href)) {
    // 悬停显示实际目标；浏览器预览环境也在新标签中打开。
    token.attrSet('title', new URL(href).href);
    token.attrSet('target', '_blank');
    token.attrSet('rel', 'noopener noreferrer');
  }
  return defaultLinkOpen ? defaultLinkOpen(tokens, index, options, env, renderer)
    : renderer.renderToken(tokens, index, options);
};

const mathPattern = /(?<!\\)\$\$([\s\S]+?)\$\$|(?<!\\)\$([^$\n]+?)\$|(?<!\\)\\\[([\s\S]+?)\\\]|(?<!\\)\\\(([^()\n]+?)\\\)/g;
let mermaidId = 0;

function renderMath(latex: string, displayMode: boolean): string {
  try {
    return renderToString(latex.trim(), { displayMode, throwOnError: false });
  } catch {
    return `<code>${markdown.utils.escapeHtml(latex)}</code>`;
  }
}

export function renderMarkdownHtml(value: string): string {
  const mathHtml: Array<{ html: string; display: boolean }> = [];
  const source = value.replace(mathPattern, (match, dollarBlock, dollarInline, bracketBlock, parenInline) => {
    const latex = String(dollarBlock ?? dollarInline ?? bracketBlock ?? parenInline ?? '').trim();
    if (!latex) return match;
    const display = dollarBlock !== undefined || bracketBlock !== undefined;
    const index = mathHtml.length;
    mathHtml.push({ html: renderMath(latex, display), display });
    return `DRILL_MATH_PLACEHOLDER_${index}_END`;
  });

  let html = DOMPurify.sanitize(markdown.render(source), { ADD_ATTR: ['target'] });
  mathHtml.forEach(({ html: formula, display }, index) => {
    const className = display ? 'markdown-math markdown-math-display' : 'markdown-math';
    html = html.split(`DRILL_MATH_PLACEHOLDER_${index}_END`).join(`<span class="${className}">${formula}</span>`);
  });
  return html;
}

function renderMermaidBlocks(root: HTMLDivElement, appTheme: 'light' | 'dark'): () => void {
  ensureMermaidTheme(appTheme);
  let active = true;
  const blocks = Array.from(root.querySelectorAll('pre code.language-mermaid'));
  void Promise.all(blocks.map(async (codeBlock) => {
    const code = codeBlock.textContent ?? '';
    const id = `drill-markdown-mermaid-${mermaidId++}`;
    try {
      const result = await renderMermaid(id, code);
      if (!active || !codeBlock.parentElement) return;
      const wrapper = document.createElement('div');
      wrapper.className = 'markdown-mermaid';
      wrapper.innerHTML = DOMPurify.sanitize(result.svg, { USE_PROFILES: { svg: true, svgFilters: true } });
      codeBlock.parentElement.replaceWith(wrapper);
    } catch {
      if (active) codeBlock.parentElement?.classList.add('markdown-mermaid-error');
    }
  }));
  return () => { active = false; };
}

export interface MarkdownContentProps {
  value?: string;
  className?: string;
  inline?: boolean;
  onSessionLink?: (link: string) => void;
  onMaterialLink?: (link: string) => void;
}

export function MarkdownContent({ value = '', className = '', inline = false, onSessionLink, onMaterialLink }: MarkdownContentProps): JSX.Element {
  const rootRef = useRef<HTMLDivElement>(null);
  const theme = useUiStore((state) => state.theme);
  const html = renderMarkdownHtml(value);

  useEffect(() => {
    if (!rootRef.current) return undefined;
    return renderMermaidBlocks(rootRef.current, theme ?? readDocumentTheme());
  }, [html, theme]);

  const onLinkClick = (event: MouseEvent<HTMLDivElement>): void => {
    if (event.button !== 0 && event.button !== 1) return;
    const link = event.target instanceof Element ? event.target.closest<HTMLAnchorElement>('a[href]') : null;
    if (!link || !event.currentTarget.contains(link)) return;
    const material = link.getAttribute('data-material-link');
    if (material && materialLinkTarget(material)) {
      event.preventDefault();
      if (event.button === 0) onMaterialLink?.(material);
      return;
    }
    const ref = link.getAttribute('data-session-ref');
    if (ref && /^[a-f0-9]{32}$/.test(ref)) {
      event.preventDefault();
      if (event.button === 0) onSessionLink?.(`drill-notebook://session/${ref}`);
      return;
    }
    const href = link.getAttribute('href') ?? '';
    if (!canOpenExternally(href)) return;
    event.preventDefault();
    event.stopPropagation();
    openExternalLink(href);
  };

  return <div ref={rootRef} className={`markdown-content ${inline ? 'markdown-content-inline' : ''} ${className}`.trim()}
    onClick={onLinkClick} onAuxClick={onLinkClick} dangerouslySetInnerHTML={{ __html: html }} />;
}
