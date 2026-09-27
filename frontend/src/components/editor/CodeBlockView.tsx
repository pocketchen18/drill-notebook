import { useEffect, useRef, useState } from 'react';
import { NodeViewContent, NodeViewWrapper, ReactNodeViewRenderer, type NodeViewProps } from '@tiptap/react';
import CodeBlockLowlight from '@tiptap/extension-code-block-lowlight';
import { common, createLowlight } from 'lowlight';
import latex from 'highlight.js/lib/languages/latex';
import matlab from 'highlight.js/lib/languages/matlab';
import { Check, Copy } from 'lucide-react';

const lowlight = createLowlight(common);
lowlight.register({ latex, matlab });

/** 常用语言在前；每个取值都已在 lowlight 中注册。 */
const LANGUAGE_OPTIONS: ReadonlyArray<readonly [string, string]> = [
  ['', '纯文本'],
  ['python', 'Python'],
  ['javascript', 'JavaScript'],
  ['typescript', 'TypeScript'],
  ['java', 'Java'],
  ['c', 'C'],
  ['cpp', 'C++'],
  ['csharp', 'C#'],
  ['go', 'Go'],
  ['rust', 'Rust'],
  ['matlab', 'MATLAB'],
  ['r', 'R'],
  ['sql', 'SQL'],
  ['bash', 'Bash'],
  ['shell', 'Shell 会话'],
  ['json', 'JSON'],
  ['yaml', 'YAML'],
  ['xml', 'HTML / XML'],
  ['css', 'CSS'],
  ['scss', 'SCSS'],
  ['less', 'Less'],
  ['markdown', 'Markdown'],
  ['latex', 'LaTeX'],
  ['php', 'PHP'],
  ['ruby', 'Ruby'],
  ['kotlin', 'Kotlin'],
  ['swift', 'Swift'],
  ['lua', 'Lua'],
  ['perl', 'Perl'],
  ['objectivec', 'Objective-C'],
  ['vbnet', 'VB.NET'],
  ['graphql', 'GraphQL'],
  ['ini', 'INI / TOML'],
  ['makefile', 'Makefile'],
  ['diff', 'Diff'],
  ['arduino', 'Arduino'],
  ['wasm', 'WebAssembly']
];

async function copyText(text: string): Promise<boolean> {
  try {
    await navigator.clipboard.writeText(text);
    return true;
  } catch {
    const area = document.createElement('textarea');
    area.value = text;
    area.setAttribute('readonly', '');
    area.style.position = 'fixed';
    area.style.opacity = '0';
    document.body.appendChild(area);
    area.select();
    try {
      return document.execCommand('copy');
    } catch {
      return false;
    } finally {
      area.remove();
    }
  }
}

export function CodeBlockView({ node, updateAttributes }: NodeViewProps): JSX.Element {
  const language = typeof node.attrs.language === 'string' ? node.attrs.language : '';
  const [copied, setCopied] = useState(false);
  const resetRef = useRef<number | null>(null);
  const known = LANGUAGE_OPTIONS.some(([value]) => value === language);

  useEffect(() => () => {
    if (resetRef.current) window.clearTimeout(resetRef.current);
  }, []);

  const copy = async (): Promise<void> => {
    if (!(await copyText(node.textContent))) return;
    setCopied(true);
    if (resetRef.current) window.clearTimeout(resetRef.current);
    resetRef.current = window.setTimeout(() => setCopied(false), 1500);
  };

  return (
    <NodeViewWrapper className="code-block" data-language={language || undefined}>
      <div className="code-block__bar" contentEditable={false}>
        <select
          className="code-block__language"
          value={language}
          onChange={(event) => updateAttributes({ language: event.target.value || null })}
          aria-label="代码语言"
        >
          {known ? null : <option value={language}>{language}</option>}
          {LANGUAGE_OPTIONS.map(([value, label]) => <option key={value || 'plain'} value={value}>{label}</option>)}
        </select>
        <button type="button" className={`code-block__copy${copied ? ' is-done' : ''}`} onClick={() => void copy()} aria-label={copied ? '已复制' : '复制代码'} title={copied ? '已复制' : '复制代码'}>
          {copied ? <Check size={14} aria-hidden="true" /> : <Copy size={14} aria-hidden="true" />}
        </button>
      </div>
      <pre className="code-block__pre" spellCheck={false}>
        <NodeViewContent as="code" className={language ? `hljs language-${language}` : 'hljs'} />
      </pre>
    </NodeViewWrapper>
  );
}

/**
 * 带语法高亮的代码块。用户未选语言时文档里的 `language` 保持 null；
 * `defaultLanguage` 只让高亮器把这类代码块当纯文本处理，而不是每次编辑都自动识别语言。
 */
export const NotebookCodeBlock = CodeBlockLowlight.extend({
  addAttributes() {
    const parent = (this.parent?.() ?? {}) as Record<string, Record<string, unknown>>;
    return { ...parent, language: { ...(parent.language ?? {}), default: null } };
  },
  addNodeView() {
    return ReactNodeViewRenderer(CodeBlockView);
  }
}).configure({ lowlight, defaultLanguage: 'plaintext' });
