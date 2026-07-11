import { describe, expect, it } from 'vitest';
import { renderMarkdownHtml } from './MarkdownRenderer';

describe('MarkdownRenderer', () => {
  it('renders Markdown and inline/display LaTeX instead of leaving source markers', () => {
    const html = renderMarkdownHtml('**重点** $E=mc^2$\n\n$$\\sum_{i=1}^{n} i$$');
    expect(html).toContain('<strong>重点</strong>');
    expect(html).toContain('katex');
    expect(html).not.toContain('$E=mc^2$');
  });
});
