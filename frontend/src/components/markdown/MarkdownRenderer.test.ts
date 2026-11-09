import { describe, expect, it } from 'vitest';
import { renderMarkdownHtml } from './MarkdownRenderer';

describe('MarkdownRenderer', () => {
  it('将合法会话引用转为安全页内链接，拒绝任意自定义协议', () => {
    const ref = '0123456789abcdef0123456789abcdef';
    const html = renderMarkdownHtml(`[旧会话](drill-notebook://session/${ref})`);
    expect(html).toContain(`data-session-ref="${ref}"`);
    expect(html).toContain(`href="#ai-session-${ref}"`);
    expect(renderMarkdownHtml('[错误](drill-notebook://session/not-a-reference)')).not.toContain('href=');
    expect(renderMarkdownHtml('[危险](javascript:alert(1))')).not.toContain('href="javascript:');
    expect(renderMarkdownHtml('[网站](https://example.com)')).toContain('href="https://example.com"');
  });
  it('renders Markdown and inline/display LaTeX instead of leaving source markers', () => {
    const html = renderMarkdownHtml('**重点** $E=mc^2$\n\n$$\\sum_{i=1}^{n} i$$');
    expect(html).toContain('<strong>重点</strong>');
    expect(html).toContain('katex');
    expect(html).not.toContain('$E=mc^2$');
  });
});
