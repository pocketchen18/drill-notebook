import { useEffect, useRef, useState, type KeyboardEvent as ReactKeyboardEvent } from 'react';
import { NodeViewWrapper, type NodeViewProps } from '@tiptap/react';
import DOMPurify from 'dompurify';
import { BlockDragHandle, exitNodeSelection } from './EditorChrome';
import { ensureMermaidTheme } from '../../lib/mermaidTheme';
import { renderMermaid } from '../../lib/mermaidRender';
import { matchesAny } from '../../lib/shortcuts';
import { useUiStore } from '../../stores/uiStore';

function MermaidPreview({ code }: { code: string }): JSX.Element {
  const theme = useUiStore((state) => state.theme);
  const [svg, setSvg] = useState('');
  const [error, setError] = useState('');

  useEffect(() => {
    let active = true;
    setSvg('');
    setError('');
    ensureMermaidTheme(theme);
    const id = `drill-mermaid-${Math.random().toString(36).slice(2)}`;
    void renderMermaid(id, code || 'flowchart TD\n  A[空]').then((result) => {
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

export function MermaidNode({ node, updateAttributes, selected, view, getPos }: NodeViewProps): JSX.Element {
  const code = String(node.attrs.code ?? '');
  const [editing, setEditing] = useState(!code.trim());
  const [draft, setDraft] = useState(code);
  const areaRef = useRef<HTMLTextAreaElement>(null);
  const cancelBeforeBlurRef = useRef(false);

  useEffect(() => {
    if (!editing) setDraft(code);
  }, [code, editing]);
  useEffect(() => {
    if (editing) areaRef.current?.focus();
  }, [editing]);

  const commit = (): void => {
    if (cancelBeforeBlurRef.current) {
      cancelBeforeBlurRef.current = false;
      return;
    }
    updateAttributes({ code: draft });
    setEditing(false);
  };

  const cancelEditing = (): void => {
    // Escape removes the textarea; browsers may dispatch blur during that
    // removal. Keep the cancelled draft from being submitted by that blur.
    cancelBeforeBlurRef.current = true;
    setDraft(code);
    setEditing(false);
  };

  const startEditing = (): void => {
    exitNodeSelection(view, getPos, node);
    cancelBeforeBlurRef.current = false;
    setEditing(true);
  };

  if (editing) {
    return (
      <NodeViewWrapper className={`mermaid-block is-editing${selected ? ' is-selected' : ''}`} contentEditable={false} data-mermaid-block="true">
        <BlockDragHandle label="拖动 Mermaid 块" />
        <div className="node-edit-toolbar">
          <span className="node-edit-label">编辑 Mermaid</span>
          <button type="button" className="node-chip-btn" onMouseDown={(event) => event.preventDefault()} onClick={commit}>完成</button>
        </div>
        <textarea
          ref={areaRef}
          className="mermaid-editor-input"
          value={draft}
          onChange={(event) => setDraft(event.target.value)}
          onBlur={commit}
          onKeyDown={(event) => {
            event.stopPropagation();
            if (event.key === 'Escape') {
              event.preventDefault();
              cancelEditing();
              return;
            }
            if (matchesAny(event, useUiStore.getState().shortcutConfig.editorFinishBlock)) {
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
      onClick={startEditing}
      onKeyDown={(event: ReactKeyboardEvent<HTMLElement>) => {
        if (event.key === 'Enter' || event.key === ' ') {
          event.preventDefault();
          startEditing();
        }
      }}
      role="button"
      tabIndex={0}
      aria-label="编辑 Mermaid 块"
      title="点击编辑图表"
    >
      <BlockDragHandle label="拖动 Mermaid 块" />
      {code.trim() ? <MermaidPreview code={code} /> : <span className="node-placeholder">点击输入 Mermaid 图表</span>}
    </NodeViewWrapper>
  );
}
