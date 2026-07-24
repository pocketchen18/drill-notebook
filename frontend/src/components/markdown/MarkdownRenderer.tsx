import { useEffect, useRef } from 'react';
import DOMPurify from 'dompurify';
import mermaid from 'mermaid';
import MarkdownIt from 'markdown-it';
import { renderToString } from 'katex';
import { ensureMermaidTheme, readDocumentTheme } from '../../lib/mermaidTheme';
import { useUiStore } from '../../stores/uiStore';

const markdown = new MarkdownIt({
  breaks: true,
  html: false,
  linkify: true,
  typographer: true
});

const mathPattern = /(?<!\\)\$\$([\s\S]+?)\$\$|(?<!\\)\$([^$\n]+?)\$|(?<!\\)\\\[([\s\S]+?)\\\]|(?<!\\)\\\(([^()\n]+?)\\\)/g;
let mermaidReady = false;
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

  let html = DOMPurify.sanitize(markdown.render(source));
  mathHtml.forEach(({ html: formula, display }, index) => {
    const className = display ? 'markdown-math markdown-math-display' : 'markdown-math';
    html = html.split(`DRILL_MATH_PLACEHOLDER_${index}_END`).join(`<span class="${className}">${formula}</span>`);
  });
  return html;
}

function renderMermaidBlocks(root: HTMLDivElement, appTheme: 'light' | 'dark'): () => void {
  ensureMermaidTheme(appTheme);
  mermaidReady = true;
  let active = true;
  const blocks = Array.from(root.querySelectorAll('pre code.language-mermaid'));
  void Promise.all(blocks.map(async (codeBlock) => {
    const code = codeBlock.textContent ?? '';
    const id = `drill-markdown-mermaid-${mermaidId++}`;
    try {
      const result = await mermaid.render(id, code);
      if (!active || !codeBlock.parentElement) return;
      const wrapper = document.createElement('div');
      wrapper.className = 'markdown-mermaid';
      wrapper.innerHTML = DOMPurify.sanitize(result.svg, { USE_PROFILES: { svg: true, svgFilters: true } });
      codeBlock.parentElement.replaceWith(wrapper);
    } catch {
      codeBlock.parentElement?.classList.add('markdown-mermaid-error');
    }
  }));
  return () => { active = false; };
}

export interface MarkdownContentProps {
  value?: string;
  className?: string;
  inline?: boolean;
}

export function MarkdownContent({ value = '', className = '', inline = false }: MarkdownContentProps): JSX.Element {
  const rootRef = useRef<HTMLDivElement>(null);
  const theme = useUiStore((state) => state.theme);
  const html = renderMarkdownHtml(value);

  useEffect(() => {
    if (!rootRef.current) return undefined;
    return renderMermaidBlocks(rootRef.current, theme ?? readDocumentTheme());
  }, [html, theme]);

  return <div ref={rootRef} className={`markdown-content ${inline ? 'markdown-content-inline' : ''} ${className}`.trim()} dangerouslySetInnerHTML={{ __html: html }} />;
}
