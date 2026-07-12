import type { NotePage, Question } from './types';

function childNodes(node: Record<string, unknown>): Record<string, unknown>[] {
  return Array.isArray(node.content) ? node.content.filter((item): item is Record<string, unknown> => Boolean(item && typeof item === 'object')) : [];
}

function inlineText(node: Record<string, unknown>): string {
  if (node.type === 'text') return String(node.text ?? '');
  if (node.type === 'hardBreak') return '\n';
  return childNodes(node).map(inlineText).join('');
}

function questionMarkdown(question: Question): string {
  const options = (Array.isArray(question.options) ? question.options : [])
    .map((option) => `${option?.key ?? ''}. ${option?.text ?? ''}`)
    .join('\n');
  const stem = question.stem ?? '';
  const answer = question.answer ? `\n\n**答案：** ${question.answer}` : '';
  const analysis = question.analysis ? `\n\n**解析：**\n\n${question.analysis}` : '';
  const metadata = [
    question.chapter ? `章节：${question.chapter}` : '',
    question.tags?.length ? `标签：${question.tags.join('、')}` : '',
    question.difficulty !== undefined ? `难度：${question.difficulty}` : ''
  ].filter(Boolean).join('；');
  return `### ${stem}${metadata ? `\n\n> ${metadata}` : ''}\n\n${options}${answer}${analysis}`;
}

export function questionsToMarkdown(questions: Question[] | null | undefined): string {
  if (!questions?.length) return '';
  return questions.map(questionMarkdown).join('\n\n---\n\n');
}


function nodeMarkdown(node: Record<string, unknown>): string {
  const type = String(node.type ?? '');
  const attrs = node.attrs && typeof node.attrs === 'object' ? node.attrs as Record<string, unknown> : {};
  const children = childNodes(node);
  switch (type) {
    case 'text': return String(node.text ?? '');
    case 'hardBreak': return '\n';
    case 'paragraph': return `${children.map(inlineText).join('')}\n\n`;
    case 'heading': {
      const level = Math.min(6, Math.max(1, Number(attrs.level ?? 2)));
      return `${'#'.repeat(level)} ${children.map(inlineText).join('')}\n\n`;
    }
    case 'bulletList': return `${children.map((item) => `- ${inlineText(item)}`).join('\n')}\n\n`;
    case 'orderedList': return `${children.map((item, index) => `${index + 1}. ${inlineText(item)}`).join('\n')}\n\n`;
    case 'blockquote': return `${children.map((item) => `> ${nodeMarkdown(item).trim()}`).join('\n')}\n\n`;
    case 'codeBlock': return `\`\`\`${String(attrs.language ?? '')}\n${children.map(inlineText).join('')}\n\`\`\`\n\n`;
    case 'mathInline': return `$${String(attrs.latex ?? '')}$`;
    case 'mathBlock': return `$$\n${String(attrs.latex ?? '')}\n$$\n\n`;
    case 'mermaidBlock': return `\`\`\`mermaid\n${String(attrs.code ?? '')}\n\`\`\`\n\n`;
    case 'markdownBlock': return `${String(attrs.markdown ?? '')}\n\n`;
    case 'questionBlock': {
      const snapshot = attrs.snapshot && typeof attrs.snapshot === 'object' ? attrs.snapshot as Question : undefined;
      return snapshot ? `${questionMarkdown(snapshot)}\n\n` : '';
    }
    default: return children.map(nodeMarkdown).join('');
  }
}

export function notePageToMarkdown(page: NotePage): string {
  return nodeMarkdown(page.content).trim();
}

export function appendMarkdownBlock(content: Record<string, unknown>, markdown: string): Record<string, unknown> {
  const existing = Array.isArray(content.content) ? content.content : [];
  return {
    ...content,
    type: content.type ?? 'doc',
    content: [...existing, { type: 'markdownBlock', attrs: { markdown } }, { type: 'paragraph' }]
  };
}
