import { describe, expect, it } from 'vitest';
import { Editor } from '@tiptap/core';
import StarterKit from '@tiptap/starter-kit';
import { MarkdownBlock, MathBlock, MathInline, MermaidBlock, QuestionBlockNode } from './extensions';

describe('notebook document serialization', () => {
  it('round-trips core custom nodes through TipTap JSON', () => {
    const document = {
      type: 'doc',
      content: [
        { type: 'heading', attrs: { level: 2 }, content: [{ type: 'text', text: '学习记录' }] },
        { type: 'mathBlock', attrs: { latex: 'E=mc^2' } },
        { type: 'mermaidBlock', attrs: { code: 'graph TD; A-->B' } },
        { type: 'markdownBlock', attrs: { markdown: '**Markdown** and $E=mc^2$' } },
        { type: 'questionBlock', attrs: { questionId: 7, snapshot: { id: 7, stem: '快照题' } } }
      ]
    };
    const editor = new Editor({ extensions: [StarterKit, MathBlock, MathInline, MermaidBlock, MarkdownBlock, QuestionBlockNode], content: document });
    expect(editor.getJSON()).toEqual(document);
    editor.destroy();
  });
});
