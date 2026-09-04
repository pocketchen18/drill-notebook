/**
 * 题干 / 标题的纯文本视图：去掉行内 Markdown 与 LaTeX 标记，供列表、tooltip、计划标题等
 * 不走 Markdown 渲染的地方使用（渲染场景请直接用 `MarkdownContent inline`）。
 */
export function markdownToPlainText(markdown: string): string {
  if (!markdown) return '';
  return markdown
    .replace(/```[^\n]*\n?([\s\S]*?)```/g, '$1')
    .replace(/\$\$([\s\S]+?)\$\$/g, (_, tex: string) => stripTex(tex))
    .replace(/\$([^$\n]+?)\$/g, (_, tex: string) => stripTex(tex))
    .replace(/!\[([^\]]*)\]\([^)]*\)/g, '$1')
    .replace(/\[([^\]]+)\]\([^)]*\)/g, '$1')
    .replace(/`([^`]+)`/g, '$1')
    .replace(/(\*\*|__)(.+?)\1/g, '$2')
    .replace(/(^|[^\w*])\*(?!\s)([^*\n]+?)\*(?!\w)/g, '$1$2')
    .replace(/(^|[^\w_])_(?!\s)([^_\n]+?)_(?!\w)/g, '$1$2')
    .replace(/~~(.+?)~~/g, '$1')
    .replace(/^\s{0,3}#{1,6}\s+/gm, '')
    .replace(/^\s{0,3}>\s?/gm, '')
    .replace(/^\s*(?:[-*+]|\d+[.)])\s+/gm, '')
    .replace(/<[^>]+>/g, '')
    .replace(/\s+/g, ' ')
    .trim();
}

/** `\log n` → `log n`，`\frac{a}{b}` → `frac{a}{b}`：只去反斜杠命令前缀，保留可读骨架。 */
function stripTex(tex: string): string {
  return tex.replace(/\\([a-zA-Z]+)/g, ' $1 ').replace(/\s+/g, ' ').trim();
}
