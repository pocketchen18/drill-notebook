import { useEffect, useRef, useState } from 'react';
import { NodeViewWrapper, type NodeViewProps } from '@tiptap/react';
import { renderToString } from 'katex';

function MathDisplay({ latex, displayMode }: { latex: string; displayMode: boolean }): JSX.Element {
  let html = '';
  try {
    html = renderToString(latex || (displayMode ? '\\,' : ''), { displayMode, throwOnError: false });
  } catch {
    html = `<span>${latex.replaceAll('<', '&lt;')}</span>`;
  }
  return <span className={displayMode ? 'math-rendered' : 'math-rendered math-rendered-inline'} dangerouslySetInnerHTML={{ __html: html }} />;
}

export function MathNode({ node, updateAttributes, selected }: NodeViewProps): JSX.Element {
  const latex = String(node.attrs.latex ?? '');
  const [editing, setEditing] = useState(!latex.trim());
  const [draft, setDraft] = useState(latex);
  const areaRef = useRef<HTMLTextAreaElement>(null);

  useEffect(() => {
    if (!editing) setDraft(latex);
  }, [editing, latex]);
  useEffect(() => {
    if (editing) areaRef.current?.focus();
  }, [editing]);

  const commit = (): void => {
    updateAttributes({ latex: draft });
    setEditing(false);
  };

  if (editing) {
    return (
      <NodeViewWrapper className={`math-block is-editing${selected ? ' is-selected' : ''}`} contentEditable={false} data-math-block="true">
        <div className="node-edit-toolbar">
          <span className="node-edit-label">编辑 LaTeX</span>
          <button type="button" className="node-chip-btn" onClick={commit}>完成</button>
        </div>
        <textarea
          ref={areaRef}
          className="math-editor-input"
          value={draft}
          onChange={(event) => setDraft(event.target.value)}
          onBlur={commit}
          onKeyDown={(event) => {
            event.stopPropagation();
            if (event.key === 'Escape') {
              setDraft(latex);
              setEditing(false);
            }
            if (event.key === 'Enter' && (event.metaKey || event.ctrlKey)) {
              event.preventDefault();
              commit();
            }
          }}
          aria-label="编辑 LaTeX 公式"
          spellCheck={false}
          placeholder="例如：E=mc^2"
        />
        <div className="node-live-preview" aria-hidden="true">
          <MathDisplay latex={draft} displayMode />
        </div>
      </NodeViewWrapper>
    );
  }

  return (
    <NodeViewWrapper
      className={`math-block is-preview${selected ? ' is-selected' : ''}`}
      contentEditable={false}
      data-math-block="true"
      onClick={() => setEditing(true)}
      title="点击编辑公式"
    >
      {latex.trim() ? <MathDisplay latex={latex} displayMode /> : <span className="node-placeholder">点击输入公式</span>}
    </NodeViewWrapper>
  );
}

export function MathInlineNode({ node, updateAttributes, selected }: NodeViewProps): JSX.Element {
  const latex = String(node.attrs.latex ?? '');
  const [editing, setEditing] = useState(!latex.trim());
  const [draft, setDraft] = useState(latex);
  const inputRef = useRef<HTMLInputElement>(null);

  useEffect(() => {
    if (!editing) setDraft(latex);
  }, [editing, latex]);
  useEffect(() => {
    if (editing) inputRef.current?.focus();
  }, [editing]);

  const commit = (): void => {
    updateAttributes({ latex: draft });
    setEditing(false);
  };

  if (editing) {
    return (
      <NodeViewWrapper as="span" className={`math-inline is-editing${selected ? ' is-selected' : ''}`} contentEditable={false} data-math-inline="true">
        <input
          ref={inputRef}
          className="math-inline-input"
          value={draft}
          onChange={(event) => setDraft(event.target.value)}
          onBlur={commit}
          onKeyDown={(event) => {
            event.stopPropagation();
            if (event.key === 'Enter' || event.key === 'Escape') {
              event.preventDefault();
              if (event.key === 'Escape') setDraft(latex);
              commit();
            }
          }}
          aria-label="编辑行内 LaTeX"
          spellCheck={false}
        />
      </NodeViewWrapper>
    );
  }

  return (
    <NodeViewWrapper
      as="span"
      className={`math-inline is-preview${selected ? ' is-selected' : ''}`}
      contentEditable={false}
      data-math-inline="true"
      onClick={() => setEditing(true)}
      title="点击编辑行内公式"
    >
      {latex.trim() ? <MathDisplay latex={latex} displayMode={false} /> : <span className="node-placeholder">公式</span>}
    </NodeViewWrapper>
  );
}
