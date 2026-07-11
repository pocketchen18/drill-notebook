import { useEffect, useState } from 'react';
import { NodeViewWrapper, type NodeViewProps } from '@tiptap/react';
import mermaid from 'mermaid';
import DOMPurify from 'dompurify';

mermaid.initialize({ startOnLoad: false, securityLevel: 'strict', theme: 'neutral' });

export function MermaidNode({ node, updateAttributes }: NodeViewProps): JSX.Element {
  const code = String(node.attrs.code ?? 'graph TD; A-->B');
  const [svg, setSvg] = useState('');
  const [error, setError] = useState('');

  useEffect(() => {
    let active = true;
    const id = `drill-mermaid-${Math.random().toString(36).slice(2)}`;
    void mermaid.render(id, code).then((result) => {
      if (!active) return;
      setSvg(DOMPurify.sanitize(result.svg, { USE_PROFILES: { svg: true, svgFilters: true } }));
      setError('');
    }).catch(() => {
      if (active) {
        setSvg('');
        setError('Mermaid 语法无法解析');
      }
    });
    return () => { active = false; };
  }, [code]);

  return <NodeViewWrapper className="mermaid-block" contentEditable={false} data-mermaid-block="true">
    <div className="node-edit-label">Mermaid 图表代码</div>
    <textarea
      className="mermaid-editor-input"
      value={code}
      onChange={(event) => updateAttributes({ code: event.target.value })}
      onKeyDown={(event) => event.stopPropagation()}
      aria-label="编辑 Mermaid 图表代码"
      spellCheck={false}
    />
    {svg ? <div className="mermaid-rendered" dangerouslySetInnerHTML={{ __html: svg }} /> : <pre className="muted">{error || code}</pre>}
  </NodeViewWrapper>;
}
