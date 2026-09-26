import { afterEach, describe, expect, it } from 'vitest';
import { Editor } from '@tiptap/core';
import { notebookExtensions } from './editorExtensions';
import { codeFromClipboard, looksLikeMarkdown, markdownFromClipboard, markdownToPasteHtml } from './markdownPaste';

const editors: Editor[] = [];

function clipboard(data: Record<string, string>): DataTransfer {
  return { getData: (type: string) => data[type] ?? '' } as unknown as DataTransfer;
}

afterEach(() => {
  editors.splice(0).forEach((editor) => editor.destroy());
});

describe('markdownToPasteHtml', () => {
  it('行内公式遵循 pandoc 边界：价格里的 $ 不会被当成公式', () => {
    const html = markdownToPasteHtml('单价 $5，总价 $10\n\n勾股 $a^2+b^2=c^2$ 成立');
    expect(html).toContain('单价 $5，总价 $10');
    expect(html).toContain('<span data-math-inline="" latex="a^2+b^2=c^2"></span>');
  });

  it('代码段里的 $ 保持原样', () => {
    expect(markdownToPasteHtml('写成 `$x$` 即可')).toContain('<code>$x$</code>');
  });

  it('独立的 $$ 转为公式块，mermaid 围栏转为图表块，普通围栏保留语言且不留尾部空行', () => {
    const html = markdownToPasteHtml('$$\nE=mc^2\n$$\n\n```mermaid\ngraph TD; A-->B\n```\n\n```python\nprint(1)\n```');
    expect(html).toContain('<div data-math-block="" latex="E=mc^2"></div>');
    // 经 template 序列化后，属性值里的 `>` 不再转义（HTML 规范只转义 & 与 "）。
    expect(html).toContain('<div data-mermaid-block="" code="graph TD; A-->B"></div>');
    expect(html).toContain('<pre><code class="language-python">print(1)</code></pre>');
  });

  it('待办列表项带上勾选状态，图片地址保留为链接', () => {
    const html = markdownToPasteHtml('- [x] 完成\n- [ ] 待做\n\n![示意图](https://example.com/a.png)');
    expect(html).toContain('<ul data-type="taskList">');
    expect(html).toContain('<li data-type="taskItem" data-checked="true">完成</li>');
    expect(html).toContain('<li data-type="taskItem" data-checked="false">待做</li>');
    expect(html).toContain('<a href="https://example.com/a.png">示意图</a>');
  });
});

describe('粘贴来源识别', () => {
  it('只转换纯文本 Markdown；带 HTML 的网页内容交给默认粘贴', () => {
    expect(markdownFromClipboard(clipboard({ 'text/plain': '# 标题\n\n- 一' }))).toBe('# 标题\n\n- 一');
    expect(markdownFromClipboard(clipboard({ 'text/plain': '# 标题', 'text/html': '<h1>标题</h1>' }))).toBeNull();
    expect(markdownFromClipboard(clipboard({ 'text/plain': '今天学习了第三章，明天复习。' }))).toBeNull();
  });

  it('VS Code 里的 Markdown 按 Markdown 转换，代码文件转为对应语言的代码块', () => {
    const markdown = clipboard({ 'text/plain': '## 笔记', 'text/html': '<div>…</div>', 'vscode-editor-data': '{"mode":"markdown"}' });
    expect(markdownFromClipboard(markdown)).toBe('## 笔记');
    const code = clipboard({ 'text/plain': 'const a = 1;\nconsole.log(a);\n', 'text/html': '<div>…</div>', 'vscode-editor-data': '{"mode":"typescriptreact"}' });
    expect(markdownFromClipboard(code)).toBeNull();
    expect(codeFromClipboard(code)).toEqual({ code: 'const a = 1;\nconsole.log(a);', language: 'typescript' });
    expect(codeFromClipboard(clipboard({ 'text/plain': 'one line', 'vscode-editor-data': '{"mode":"python"}' }))).toBeNull();
    expect(codeFromClipboard(clipboard({ 'text/plain': 'a\nb', 'vscode-editor-data': '{"mode":"dockerfile"}' }))?.language).toBeNull();
  });

  it('常见的 Markdown 信号都能识别', () => {
    ['# 标题', '1. 第一步', '> 引用', '| a | b |', '**重点**', '[链接](https://a.com)', '行内 $x$ 公式', '```\ncode\n```'].forEach((sample) => {
      expect(looksLikeMarkdown(sample)).toBe(true);
    });
  });
});

describe('转换结果进入编辑器', () => {
  it('生成原生的标题、待办、表格、公式、图表与代码块', () => {
    const editor = new Editor({ extensions: notebookExtensions() });
    editors.push(editor);
    const source = [
      '# 标题',
      '- [x] 完成',
      '| a | b |\n| - | - |\n| 1 | 2 |',
      '$$\nE=mc^2\n$$',
      '```mermaid\ngraph TD; A-->B\n```',
      '```python\nprint(1)\n```'
    ].join('\n\n');
    // 真实粘贴会传入原始事件；jsdom 没有 ClipboardEvent，这里给一个普通事件。
    editor.view.pasteHTML(markdownToPasteHtml(source), new Event('paste') as ClipboardEvent);
    const json = JSON.stringify(editor.getJSON());
    expect(json).toContain('"type":"heading"');
    expect(json).toContain('"type":"taskItem","attrs":{"checked":true}');
    expect(json).toContain('"type":"table"');
    expect(json).toContain('"type":"mathBlock","attrs":{"latex":"E=mc^2"}');
    expect(json).toContain('"type":"mermaidBlock","attrs":{"code":"graph TD; A-->B"}');
    expect(json).toContain('"type":"codeBlock","attrs":{"language":"python"},"content":[{"type":"text","text":"print(1)"}]');
  });
});
