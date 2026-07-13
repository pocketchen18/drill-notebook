import { describe, expect, it } from 'vitest';
import { appendMarkdownBlock, notePageToMarkdown, questionsToMarkdown } from './aiContext';

describe('AI context helpers', () => {
  it('formats questions and note custom nodes as Markdown', () => {
    const question = { id: 1, bankId: 2, type: 'single' as const, stem: '选择 $x$', options: [{ key: 'A', text: '正确' }], answer: 'A', analysis: '**原因**' };
    expect(questionsToMarkdown([question])).toContain('### 选择 $x$');
    expect(notePageToMarkdown({ id: 1, notebookId: 1, title: '页', content: { type: 'doc', content: [{ type: 'mathBlock', attrs: { latex: 'E=mc^2' } }] } })).toContain('$$');
  });

  it('labels essay references without rendering choice options', () => {
    const essay = { id: 2, bankId: 2, type: 'essay' as const, stem: '解释 JVM', options: [], answer: 'Java 虚拟机', analysis: '参考解析' };
    const markdown = questionsToMarkdown([essay]);
    expect(markdown).toContain('题型：解答');
    expect(markdown).toContain('**参考答案：** Java 虚拟机');
    expect(markdown).not.toContain('A.');
  });

  it('appends a renderable Markdown block without discarding existing content', () => {
    const content = appendMarkdownBlock({ type: 'doc', content: [{ type: 'paragraph' }] }, '**总结**');
    expect(content.content).toHaveLength(3);
    expect((content.content as unknown[])[1]).toEqual({ type: 'markdownBlock', attrs: { markdown: '**总结**' } });
  });
});
