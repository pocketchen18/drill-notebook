import { InputRule, mergeAttributes, Node } from '@tiptap/core';
import { ReactNodeViewRenderer } from '@tiptap/react';
import { MathInlineNode, MathNode } from './MathNode';
import { MermaidNode } from './MermaidNode';
import { MarkdownBlockNode } from './MarkdownBlock';
import { QuestionBlock } from './QuestionBlock';

export const MathBlock = Node.create({
  name: 'mathBlock',
  group: 'block',
  atom: true,
  addAttributes: () => ({ latex: { default: 'E=mc^2' } }),
  parseHTML: () => [{ tag: 'div[data-math-block]' }],
  renderHTML: ({ HTMLAttributes }) => ['div', mergeAttributes(HTMLAttributes, { 'data-math-block': '' })],
  addNodeView: () => ReactNodeViewRenderer(MathNode),
  addInputRules: () => [new InputRule({
    find: /\$\$([^$\n]+)\$\$$/,
    handler: ({ state, range, match }) => {
      const latex = match[1]?.trim();
      if (latex) state.tr.replaceWith(range.from, range.to, state.schema.nodes.mathBlock.create({ latex }));
    }
  })]
});

export const MathInline = Node.create({
  name: 'mathInline',
  group: 'inline',
  inline: true,
  atom: true,
  addAttributes: () => ({ latex: { default: 'E=mc^2' } }),
  parseHTML: () => [{ tag: 'span[data-math-inline]' }],
  renderHTML: ({ HTMLAttributes }) => ['span', mergeAttributes(HTMLAttributes, { 'data-math-inline': '' })],
  addNodeView: () => ReactNodeViewRenderer(MathInlineNode),
  addInputRules: () => [new InputRule({
    find: /(?<!\$)\$([^$\n]+)\$$/,
    handler: ({ state, range, match }) => {
      const latex = match[1]?.trim();
      if (latex) state.tr.replaceWith(range.from, range.to, state.schema.nodes.mathInline.create({ latex }));
    }
  })]
});

export const MermaidBlock = Node.create({
  name: 'mermaidBlock',
  group: 'block',
  atom: true,
  addAttributes: () => ({ code: { default: 'graph TD; A-->B' } }),
  parseHTML: () => [{ tag: 'div[data-mermaid-block]' }],
  renderHTML: ({ HTMLAttributes }) => ['div', mergeAttributes(HTMLAttributes, { 'data-mermaid-block': '' })],
  addNodeView: () => ReactNodeViewRenderer(MermaidNode)
});

export const MarkdownBlock = Node.create({
  name: 'markdownBlock',
  group: 'block',
  atom: true,
  addAttributes: () => ({ markdown: { default: '# 学习记录\n\n在这里编辑 Markdown 和 $E=mc^2$。\n' } }),
  parseHTML: () => [{ tag: 'div[data-markdown-block]' }],
  renderHTML: ({ HTMLAttributes }) => ['div', mergeAttributes(HTMLAttributes, { 'data-markdown-block': '' })],
  addNodeView: () => ReactNodeViewRenderer(MarkdownBlockNode)
});

export const QuestionBlockNode = Node.create({
  name: 'questionBlock',
  group: 'block',
  atom: true,
  addAttributes: () => ({ questionId: { default: null }, snapshot: { default: {} } }),
  parseHTML: () => [{ tag: 'div[data-question-block]' }],
  renderHTML: ({ HTMLAttributes }) => ['div', mergeAttributes(HTMLAttributes, { 'data-question-block': '' })],
  addNodeView: () => ReactNodeViewRenderer(QuestionBlock)
});
