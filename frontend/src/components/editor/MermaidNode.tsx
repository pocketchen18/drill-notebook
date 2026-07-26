import { useEffect, useRef, useState } from 'react';
import { NodeViewWrapper, type NodeViewProps } from '@tiptap/react';
import mermaid from 'mermaid';
import DOMPurify from 'dompurify';
import { ensureMermaidTheme } from '../../lib/mermaidTheme';
import { useUiStore } from '../../stores/uiStore';

function MermaidPreview({ code }: { code: string }): JSX.Element {
  const theme = useUiStore((state) => state.theme);
  const [svg, setSvg] = useState('');
  const [error, setError] = useState('');

  useEffect(() => {
    let active = true;
    ensureMermaidTheme(theme);
    const id = `drill-mermaid-${Math.random().toString(36).slice(2)}`;
    void mermaid.render(id, code || 'flowchart TD\n  A[空]').then((result) => {
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
  }, [code, theme]);

  if (svg) return <div className="mermaid-rendered" dangerouslySetInnerHTML={{ __html: svg }} />;
  return <pre className="muted mermaid-fallback">{error || code || '空图表'}</pre>;
}

export function MermaidNode({ node, updateAttributes, selected }: NodeViewProps): JSX.Element {
  const code = String(node.attrs.code ?? '');
  const [editing, setEditing] = useState(!code.trim());
  const [draft, setDraft] = useState(code);
  const areaRef = useRef<HTMLTextAreaElement>(null);

  useEffect(() => {
    if (!editing) setDraft(code);
  }, [code, editing]);
  useEffect(() => {
    if (editing) areaRef.current?.focus();
  }, [editing]);

  const commit = (): void => {
    updateAttributes({ code: draft });
    setEditing(false);
  };

  if (editing) {
    return (
      <NodeViewWrapper className={`mermaid-block is-editing${selected ? ' is-selected' : ''}`} contentEditable={false} data-mermaid-block="true">
        <div className="node-edit-toolbar">
          <span className="node-edit-label">编辑 Mermaid</span>
          <button type="button" className="node-chip-btn" onClick={commit}>完成</button>
        </div>
        <textarea
          ref={areaRef}
          className="mermaid-editor-input"
          value={draft}
          onChange={(event) => setDraft(event.target.value)}
          onKeyDown={(event) => {
            event.stopPropagation();
            if (event.key === 'Escape') {
              setDraft(code);
              setEditing(false);
            }
            if (event.key === 'Enter' && (event.metaKey || event.ctrlKey)) {
              event.preventDefault();
              commit();
            }
          }}
          aria-label="编辑 Mermaid 图表代码"
          spellCheck={false}
          placeholder={'flowchart TD\n  A[开始] --> B[结束]'}
        />
        <div className="node-live-preview">
          <MermaidPreview code={draft} />
        </div>
      </NodeViewWrapper>
    );
  }

  return (
    <NodeViewWrapper
      className={`mermaid-block is-preview${selected ? ' is-selected' : ''}`}
      contentEditable={false}
      data-mermaid-block="true"
      onClick={() => setEditing(true)}
      title="点击编辑图表"
    >
      {code.trim() ? <MermaidPreview code={code} /> : <span className="node-placeholder">点击输入 Mermaid 图表</span>}
    </NodeViewWrapper>
  );
}
