import { NodeViewWrapper, type NodeViewProps } from '@tiptap/react';
import { renderToString } from 'katex';

export function MathNode({ node, updateAttributes }: NodeViewProps): JSX.Element {
  const latex = String(node.attrs.latex ?? '');
  let html = '';
  try {
    html = renderToString(latex, { displayMode: true, throwOnError: false });
  } catch {
    html = `<span>${latex.replaceAll('<', '&lt;')}</span>`;
  }
  return <NodeViewWrapper className="math-block" contentEditable={false} data-math-block="true">
    <div className="node-edit-label">LaTeX 公式</div>
    <textarea
      className="math-editor-input"
      value={latex}
      onChange={(event) => updateAttributes({ latex: event.target.value })}
      onKeyDown={(event) => event.stopPropagation()}
      aria-label="编辑 LaTeX 公式"
      spellCheck={false}
    />
    <div className="math-rendered" dangerouslySetInnerHTML={{ __html: html }} />
  </NodeViewWrapper>;
}

export function MathInlineNode({ node, updateAttributes }: NodeViewProps): JSX.Element {
  const latex = String(node.attrs.latex ?? '');
  let html = '';
  try {
    html = renderToString(latex, { displayMode: false, throwOnError: false });
  } catch {
    html = `<span>${latex.replaceAll('<', '&lt;')}</span>`;
  }
  return <NodeViewWrapper as="span" contentEditable={false} data-math-inline="true">
    <input
      className="math-inline-input"
      value={latex}
      onChange={(event) => updateAttributes({ latex: event.target.value })}
      onKeyDown={(event) => event.stopPropagation()}
      aria-label="编辑行内 LaTeX"
      spellCheck={false}
    />
    <span className="math-rendered" dangerouslySetInnerHTML={{ __html: html }} />
  </NodeViewWrapper>;
}
