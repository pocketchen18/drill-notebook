import MarkdownIt from 'markdown-it';

type InlineRule = Parameters<MarkdownIt['inline']['ruler']['after']>[2];
type BlockRule = Parameters<MarkdownIt['block']['ruler']['before']>[2];

function escapeAttribute(value: string): string {
  return value.replaceAll('&', '&amp;').replaceAll('<', '&lt;').replaceAll('>', '&gt;').replaceAll('"', '&quot;');
}

/**
 * 按 pandoc 的边界规则识别 `$…$`：两个美元符号内侧不能紧挨空白，闭合的 `$` 后不能紧跟数字，
 * 因此 “$5 和 $10” 仍是普通文本。段落中的 `$$…$$` 同样按行内公式处理。
 */
const mathInline: InlineRule = (state, silent) => {
  const { src } = state;
  const start = state.pos;
  if (src.charCodeAt(start) !== 0x24) return false;
  const width = src.charCodeAt(start + 1) === 0x24 ? 2 : 1;
  const first = src.charAt(start + width);
  if (!first || first === '$' || /\s/.test(first)) return false;
  let position = start + width;
  while (position < state.posMax) {
    const code = src.charCodeAt(position);
    if (code === 0x5c) {
      position += 2;
      continue;
    }
    if (code === 0x24 && (width === 1 || src.charCodeAt(position + 1) === 0x24)) break;
    position += 1;
  }
  if (position >= state.posMax) return false;
  const content = src.slice(start + width, position);
  if (/\s$/.test(content)) return false;
  if (width === 1 && /\d/.test(src.charAt(position + 1))) return false;
  if (!silent) state.push('math_inline', 'span', 0).content = content;
  state.pos = position + width;
  return true;
};

/** 独占一行的 `$$`（或单行的 `$$ … $$`）开启行间公式。 */
const mathBlock: BlockRule = (state, startLine, endLine, silent) => {
  const begin = state.bMarks[startLine] + state.tShift[startLine];
  const lineEnd = state.eMarks[startLine];
  if (state.sCount[startLine] - state.blkIndent >= 4) return false;
  if (state.src.slice(begin, begin + 2) !== '$$') return false;
  const rest = state.src.slice(begin + 2, lineEnd).trim();
  let content: string;
  let last = startLine;
  if (rest.length >= 2 && rest.endsWith('$$')) {
    content = rest.slice(0, -2);
  } else {
    const lines = rest ? [rest] : [];
    let closed = false;
    for (last = startLine + 1; last < endLine; last += 1) {
      const lineStart = state.bMarks[last] + state.tShift[last];
      const end = state.eMarks[last];
      if (lineStart < end && state.sCount[last] < state.blkIndent) break;
      const text = state.src.slice(lineStart, end).trim();
      if (text.endsWith('$$')) {
        const before = text.slice(0, -2).trim();
        if (before) lines.push(before);
        closed = true;
        break;
      }
      lines.push(text);
    }
    if (!closed) return false;
    content = lines.join('\n');
  }
  if (!content.trim()) return false;
  if (silent) return true;
  state.line = last + 1;
  const token = state.push('math_block', 'div', 0);
  token.block = true;
  token.content = content;
  token.map = [startLine, state.line];
  return true;
};

const markdown = new MarkdownIt({ html: false, linkify: true, breaks: true, typographer: false });
markdown.inline.ruler.after('escape', 'math_inline', mathInline);
markdown.block.ruler.before('fence', 'math_block', mathBlock, { alt: ['paragraph', 'reference', 'blockquote', 'list'] });
markdown.renderer.rules.math_inline = (tokens, index) => `<span data-math-inline="" latex="${escapeAttribute(tokens[index].content.trim())}"></span>`;
markdown.renderer.rules.math_block = (tokens, index) => `<div data-math-block="" latex="${escapeAttribute(tokens[index].content.trim())}"></div>\n`;
// 自己输出代码块而不用默认渲染：默认会在代码末尾多留一个换行，粘贴进来就成了空行。
markdown.renderer.rules.fence = (tokens, index) => {
  const token = tokens[index];
  const language = token.info.trim().split(/\s+/)[0]?.toLowerCase() ?? '';
  const body = token.content.replace(/\n$/, '');
  if (language === 'mermaid') return `<div data-mermaid-block="" code="${escapeAttribute(body)}"></div>\n`;
  if (language === 'math') return `<div data-math-block="" latex="${escapeAttribute(body)}"></div>\n`;
  const languageClass = language ? ` class="language-${escapeAttribute(language)}"` : '';
  return `<pre><code${languageClass}>${markdown.utils.escapeHtml(body)}</code></pre>\n`;
};
// 笔记没有图片节点，把图片地址保留为链接，而不是直接丢弃。
markdown.renderer.rules.image = (tokens, index) => {
  const token = tokens[index];
  const source = token.attrGet('src') ?? '';
  const label = token.content || source;
  return source ? `<a href="${escapeAttribute(source)}">${markdown.utils.escapeHtml(label)}</a>` : markdown.utils.escapeHtml(label);
};

/** 把 `- [ ] 事项` 形式的列表项转成 TipTap 待办项。 */
function markTaskItems(html: string): string {
  const template = document.createElement('template');
  template.innerHTML = html;
  template.content.querySelectorAll('li').forEach((item) => {
    const host = item.firstElementChild?.tagName === 'P' ? item.firstElementChild : item;
    const first = host.firstChild;
    if (!first || first.nodeType !== Node.TEXT_NODE) return;
    const match = /^\[([ xX])\](?:\s+|$)/.exec(first.textContent ?? '');
    if (!match) return;
    first.textContent = (first.textContent ?? '').slice(match[0].length);
    item.setAttribute('data-type', 'taskItem');
    item.setAttribute('data-checked', match[1] === ' ' ? 'false' : 'true');
    item.parentElement?.setAttribute('data-type', 'taskList');
  });
  return template.innerHTML;
}

export function markdownToPasteHtml(source: string): string {
  return markTaskItems(markdown.render(source));
}

const MARKDOWN_SIGNALS: readonly RegExp[] = [
  /^ {0,3}#{1,6}\s+\S/m,
  /^ {0,3}(?:[-*+]|\d{1,9}[.)])\s+\S/m,
  /^ {0,3}>\s?\S/m,
  /^ {0,3}(?:```|~~~)/m,
  /^ {0,3}\|.*\|\s*$/m,
  /\$\$[\s\S]+?\$\$/,
  /(?:^|[^\\$\w])\$[^\s$](?:[^$\n]*[^\s$\\])?\$(?!\d)/,
  /\*\*[^*\n]+\*\*|__[^_\n]+__/,
  /\[[^\]\n]+\]\([^)\s]+\)/,
  /`[^`\n]+`/
];

export function looksLikeMarkdown(text: string): boolean {
  return MARKDOWN_SIGNALS.some((pattern) => pattern.test(text));
}

function vscodeMode(data: DataTransfer): string | null {
  const raw = data.getData('vscode-editor-data');
  if (!raw) return null;
  try {
    const mode = (JSON.parse(raw) as { mode?: unknown }).mode;
    return typeof mode === 'string' ? mode : null;
  } catch {
    return null;
  }
}

/** 返回需要转换的 Markdown 源文本；返回 null 表示交给 ProseMirror 常规粘贴。 */
export function markdownFromClipboard(data: DataTransfer | null | undefined): string | null {
  if (!data) return null;
  const text = data.getData('text/plain');
  if (!text.trim()) return null;
  const mode = vscodeMode(data);
  if (mode !== null) return mode === 'markdown' ? text : null;
  // 已渲染的 HTML（网页、Word）本身带有结构。
  if (data.getData('text/html')) return null;
  return looksLikeMarkdown(text) ? text : null;
}

// 与高亮器命名不同的 VS Code 语言 id。
const VSCODE_LANGUAGES: Record<string, string> = {
  typescriptreact: 'typescript',
  javascriptreact: 'javascript',
  jsonc: 'json',
  html: 'xml',
  shellscript: 'bash',
  vb: 'vbnet',
  'objective-c': 'objectivec'
};
const HIGHLIGHTED = new Set([
  'python', 'javascript', 'typescript', 'java', 'c', 'cpp', 'csharp', 'go', 'rust', 'matlab', 'r', 'sql', 'bash', 'json', 'yaml', 'xml',
  'css', 'scss', 'less', 'latex', 'php', 'ruby', 'kotlin', 'swift', 'lua', 'perl', 'objectivec', 'vbnet', 'graphql', 'ini', 'makefile', 'diff'
]);

/** 从 VS Code 复制的多行代码转为对应语言的代码块。 */
export function codeFromClipboard(data: DataTransfer | null | undefined): { code: string; language: string | null } | null {
  if (!data) return null;
  const mode = vscodeMode(data);
  if (mode === null || mode === 'markdown') return null;
  const code = data.getData('text/plain').replace(/\r\n?/g, '\n').replace(/\n$/, '');
  if (!code.includes('\n')) return null;
  const language = VSCODE_LANGUAGES[mode] ?? mode;
  return { code, language: HIGHLIGHTED.has(language) ? language : null };
}
