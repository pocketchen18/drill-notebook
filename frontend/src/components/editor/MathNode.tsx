import { useEffect, useRef, useState, type KeyboardEvent as ReactKeyboardEvent } from 'react';
import { NodeViewWrapper, type NodeViewProps } from '@tiptap/react';
import { renderToString } from 'katex';
import { FinishButton, exitNodeSelection, focusFieldSoon, insertSoftTab } from './EditorChrome';
import { matchesAny } from '../../lib/shortcuts';
import { useUiStore } from '../../stores/uiStore';

function MathDisplay({ latex, displayMode }: { latex: string; displayMode: boolean }): JSX.Element {
  let html = '';
  try {
    html = renderToString(latex || (displayMode ? '\\,' : ''), { displayMode, throwOnError: false });
  } catch {
    html = `<span>${latex.replaceAll('<', '&lt;')}</span>`;
  }
  return <span className={displayMode ? 'math-rendered' : 'math-rendered math-rendered-inline'} dangerouslySetInnerHTML={{ __html: html }} />;
}

export function MathNode({ node, updateAttributes, selected, view, getPos }: NodeViewProps): JSX.Element {
  const latex = String(node.attrs.latex ?? '');
  const [editing, setEditing] = useState(!latex.trim());
  const [draft, setDraft] = useState(latex);
  const areaRef = useRef<HTMLTextAreaElement>(null);
  const cancelBeforeBlurRef = useRef(false);

  useEffect(() => {
    if (!editing) setDraft(latex);
  }, [editing, latex]);
  useEffect(() => {
    if (editing) return focusFieldSoon(() => areaRef.current);
    return undefined;
  }, [editing]);

  const commit = (): void => {
    if (cancelBeforeBlurRef.current) {
      cancelBeforeBlurRef.current = false;
      return;
    }
    updateAttributes({ latex: draft });
    setEditing(false);
  };

  const cancelEditing = (): void => {
    cancelBeforeBlurRef.current = true;
    setDraft(latex);
    setEditing(false);
  };

  const startEditing = (): void => {
    exitNodeSelection(view, getPos, node);
    cancelBeforeBlurRef.current = false;
    setEditing(true);
  };

  if (editing) {
    return (
      <NodeViewWrapper className={`math-block is-editing${selected ? ' is-selected' : ''}`} contentEditable={false} data-math-block="true">
        <div className="node-edit-toolbar">
          <span className="node-edit-label">编辑 LaTeX</span>
          <FinishButton onFinish={commit} />
        </div>
        <textarea
          ref={areaRef}
          className="math-editor-input"
          value={draft}
          onChange={(event) => setDraft(event.target.value)}
          onBlur={commit}
          onKeyDown={(event) => {
            event.stopPropagation();
            if (insertSoftTab(event, setDraft)) return;
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
      onClick={startEditing}
      onKeyDown={(event: ReactKeyboardEvent<HTMLElement>) => {
        if (event.key === 'Enter' || event.key === ' ') {
          event.preventDefault();
          startEditing();
        }
      }}
      role="button"
      tabIndex={0}
      aria-label="编辑公式块"
      title="点击编辑公式"
    >
      {latex.trim() ? <MathDisplay latex={latex} displayMode /> : <span className="node-placeholder">点击输入公式</span>}
    </NodeViewWrapper>
  );
}

export function MathInlineNode({ node, updateAttributes, selected, view, getPos }: NodeViewProps): JSX.Element {
  const latex = String(node.attrs.latex ?? '');
  const [editing, setEditing] = useState(!latex.trim());
  const [draft, setDraft] = useState(latex);
  const inputRef = useRef<HTMLInputElement>(null);
  const cancelBeforeBlurRef = useRef(false);

  useEffect(() => {
    if (!editing) setDraft(latex);
  }, [editing, latex]);
  useEffect(() => {
    if (editing) return focusFieldSoon(() => inputRef.current);
    return undefined;
  }, [editing]);

  const commit = (): void => {
    if (cancelBeforeBlurRef.current) {
      cancelBeforeBlurRef.current = false;
      return;
    }
    updateAttributes({ latex: draft });
    setEditing(false);
  };

  const cancelEditing = (): void => {
    cancelBeforeBlurRef.current = true;
    setDraft(latex);
    setEditing(false);
  };

  const startEditing = (): void => {
    exitNodeSelection(view, getPos, node);
    cancelBeforeBlurRef.current = false;
    setEditing(true);
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
              if (event.key === 'Escape') {
                cancelEditing();
                return;
              }
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
      onClick={startEditing}
      onKeyDown={(event: ReactKeyboardEvent<HTMLElement>) => {
        if (event.key === 'Enter' || event.key === ' ') {
          event.preventDefault();
          startEditing();
        }
      }}
      role="button"
      tabIndex={0}
      aria-label="编辑行内公式"
      title="点击编辑行内公式"
    >
      {latex.trim() ? <MathDisplay latex={latex} displayMode={false} /> : <span className="node-placeholder">公式</span>}
    </NodeViewWrapper>
  );
}
