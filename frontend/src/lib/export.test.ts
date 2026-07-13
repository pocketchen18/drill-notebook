import { describe, expect, it } from 'vitest';
import { buildStandaloneHtml, markdownToHtml, noteExportDocument, questionExportDocument, safeFileName } from './export';
import type { NotePage, Question } from './types';

Object.defineProperty(SVGElement.prototype, 'getBBox', {
  configurable: true,
  value: () => ({ x: 0, y: 0, width: 120, height: 24 })
});
Object.defineProperty(SVGElement.prototype, 'getComputedTextLength', {
  configurable: true,
  value: () => 80
});

describe('export documents', () => {
  it('serializes selected questions with answers and escapes HTML', async () => {
    const questions: Question[] = [{ id: 1, bankId: 1, type: 'single', stem: '<script>alert(1)</script>', options: [{ key: 'A', text: '安全' }], answer: 'A', analysis: '解析', chapter: '安全', tags: ['HTML'], difficulty: 2 }];
    const document = questionExportDocument('题库', questions);
    const html = await buildStandaloneHtml(document);
    expect(document.markdown).toContain('**答案：** A');
    expect(document.markdown).toContain('章节：安全；标签：HTML；难度：2');
    expect(html).toContain("default-src 'none'");
    expect(html).toContain('&lt;script&gt;alert(1)&lt;/script&gt;');
    expect(html).not.toContain('<script>alert(1)</script>');
  });

  it('combines multiple note pages with headings', () => {
    const pages: NotePage[] = [
      { id: 1, notebookId: 1, title: '第一页', content: { type: 'doc', content: [{ type: 'paragraph', content: [{ type: 'text', text: '内容一' }] }] } },
      { id: 2, notebookId: 1, title: '第二页', content: { type: 'doc', content: [{ type: 'heading', attrs: { level: 3 }, content: [{ type: 'text', text: '内容二' }] }] } }
    ];
    const document = noteExportDocument('笔记', pages);
    expect(document.markdown).toContain('## 第一页');
    expect(document.markdown).toContain('内容一');
    expect(document.markdown).toContain('## 第二页');
  });

  it('exports essay reference answers with advisory wording', () => {
    const essay: Question = { id: 2, bankId: 1, type: 'essay', stem: '说明设计', options: [], answer: '参考方案' };
    const document = questionExportDocument('解答题', [essay]);
    expect(document.markdown).toContain('题型：解答');
    expect(document.markdown).toContain('**参考答案：** 参考方案');
  });

  it('renders lists and code without introducing scripts', async () => {
    const html = await markdownToHtml('- item\n\n```js\nconst x = "<tag>";\n```');
    expect(html).toContain('<ul>');
    expect(html).toContain('&lt;tag&gt;');
  });

  it('renders math and mermaid instead of exporting their Markdown source', async () => {
    const html = await buildStandaloneHtml({ title: '渲染测试', markdown: '$$x^2$$\n\n```mermaid\ngraph TD; A-->B\n```' });
    expect(html).toContain('class="katex"');
    expect(html).toContain('<math');
    expect(html).not.toContain('class="katex-html"');
    expect(html).toContain('class="markdown-mermaid"');
    expect(html).toContain('<svg');
    expect(html).not.toContain('```mermaid');
  });

  it('normalizes invalid filename characters', () => {
    expect(safeFileName('题库: A/B?')).toBe('题库- A-B-');
  });
});
