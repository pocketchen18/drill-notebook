import type { NotePage, Question } from './types';
import { notePageToMarkdown, questionsToMarkdown } from './aiContext';
import DOMPurify from 'dompurify';
import mermaid from 'mermaid';
import { renderMarkdownHtml } from '../components/markdown/MarkdownRenderer';

export type ExportFormat = 'md' | 'html' | 'pdf';

export interface ExportDocument {
  title: string;
  markdown: string;
}

function escapeHtml(value: string): string {
  return value
    .replaceAll('&', '&amp;')
    .replaceAll('<', '&lt;')
    .replaceAll('>', '&gt;')
    .replaceAll('"', '&quot;')
    .replaceAll("'", '&#39;');
}

let exportMermaidReady = false;
let exportMermaidId = 0;

export async function markdownToHtml(markdown: string): Promise<string> {
  const root = document.createElement('div');
  root.innerHTML = renderMarkdownHtml(markdown);
  const blocks = Array.from(root.querySelectorAll('pre code.language-mermaid'));
  if (blocks.length && !exportMermaidReady) {
    mermaid.initialize({ startOnLoad: false, securityLevel: 'strict', theme: 'neutral' });
    exportMermaidReady = true;
  }
  for (const block of blocks) {
    const source = block.textContent ?? '';
    try {
      const result = await mermaid.render(`drill-export-mermaid-${exportMermaidId++}`, source);
      const wrapper = document.createElement('div');
      wrapper.className = 'markdown-mermaid';
      wrapper.innerHTML = DOMPurify.sanitize(result.svg, { USE_PROFILES: { svg: true, svgFilters: true } });
      block.parentElement?.replaceWith(wrapper);
    } catch {
      block.parentElement?.classList.add('markdown-mermaid-error');
    }
  }
  root.querySelectorAll('.katex-html').forEach((node) => node.remove());
  return DOMPurify.sanitize(root.innerHTML, { USE_PROFILES: { html: true, svg: true, svgFilters: true, mathMl: true } });
}

export async function buildStandaloneHtml(document: ExportDocument): Promise<string> {
  const title = escapeHtml(document.title);
  const renderedContent = await markdownToHtml(document.markdown);
  return `<!doctype html>
<html lang="zh-CN"><head><meta charset="utf-8"><meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src 'unsafe-inline'; img-src data:"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>${title}</title><style>
body{max-width:900px;margin:0 auto;padding:48px 56px;color:#1d2129;font:16px/1.75 "Microsoft YaHei","Segoe UI",sans-serif}
h1,h2,h3,h4,h5,h6{line-height:1.35;margin:1.5em 0 .65em}h1{border-bottom:2px solid #155eef;padding-bottom:.35em}
p{white-space:pre-wrap}hr{border:0;border-top:1px solid #d9dce1;margin:2em 0}li{margin:.35em 0}
blockquote{margin:1em 0;padding:.5em 1em;border-left:4px solid #8bb8ff;background:#f5f8fd;color:#4e5969}
pre{overflow:auto;padding:16px;border-radius:6px;background:#f2f3f5}code{font-family:Consolas,monospace;background:#f2f3f5;padding:2px 5px;border-radius:4px}
.markdown-mermaid{overflow:auto;margin:20px 0;text-align:center}.markdown-mermaid svg{max-width:100%;height:auto}.markdown-math{font-family:"Cambria Math","STIX Two Math",serif}.markdown-math-display{display:block;overflow:auto;margin:18px 0;text-align:center}.katex-mathml{display:inline-block}.katex-mathml math{font-size:1.1em}
@media print{body{padding:0}h1,h2,h3{break-after:avoid}pre,blockquote{break-inside:avoid}}
</style></head><body><h1>${title}</h1>${renderedContent}</body></html>`;
}

export function questionExportDocument(title: string, questions: Question[]): ExportDocument {
  return { title, markdown: questionsToMarkdown(questions) };
}

export function noteExportDocument(title: string, pages: NotePage[]): ExportDocument {
  const markdown = pages.map((page) => `## ${page.title}\n\n${notePageToMarkdown(page)}`).join('\n\n---\n\n');
  return { title, markdown };
}

export function safeFileName(value: string): string {
  const normalized = value.replace(/[<>:"/\\|?*\u0000-\u001f]/g, '-').replace(/\s+/g, ' ').trim();
  return normalized || 'drill-notebook-export';
}
