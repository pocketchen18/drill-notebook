import { useCallback, useEffect, useLayoutEffect, useRef, useState, type KeyboardEvent as ReactKeyboardEvent, type MouseEvent as ReactMouseEvent } from 'react';
import { NodeViewWrapper, type NodeViewProps } from '@tiptap/react';
import { NodeSelection, TextSelection, type Transaction } from '@tiptap/pm/state';
import { renderToString } from 'katex';
import { FinishButton, exitNodeSelection, focusFieldSoon, insertSoftTab } from './EditorChrome';
import { matchesAny } from '../../lib/shortcuts';
import { useUiStore } from '../../stores/uiStore';
import { EDIT_MATH_BLOCK_META } from './blockCommands';

function MathDisplay({ latex, displayMode }: { latex: string; displayMode: boolean }): JSX.Element {
  let html = '';
  try {
    html = renderToString(latex || (displayMode ? '\\,' : ''), { displayMode, throwOnError: false });
  } catch {
    html = `<span>${latex.replaceAll('<', '&lt;')}</span>`;
  }
  return <span className={displayMode ? 'math-rendered' : 'math-rendered math-rendered-inline'} dangerouslySetInnerHTML={{ __html: html }} />;
}

export function MathNode({ node, editor, updateAttributes, selected, view, getPos }: NodeViewProps): JSX.Element {
  const latex = String(node.attrs.latex ?? '');
  const [editing, setEditing] = useState(!latex.trim());
  const [draft, setDraft] = useState(latex);
  const areaRef = useRef<HTMLTextAreaElement>(null);
  const previewRef = useRef<HTMLDivElement>(null);
  const endingEditRef = useRef(false);
  const restorePreviewFocusRef = useRef(false);

  useEffect(() => {
    if (!editing) setDraft(latex);
  }, [editing, latex]);
  useEffect(() => {
    if (editing) return focusFieldSoon(() => areaRef.current, { caretAtEnd: true });
    return undefined;
  }, [editing]);
  useLayoutEffect(() => {
    if (!editing && restorePreviewFocusRef.current) {
      restorePreviewFocusRef.current = false;
      previewRef.current?.focus({ preventScroll: true });
    }
  }, [editing]);

  const commit = (restoreFocus = false): void => {
    // 主动退出时聚焦正文也会触发 blur，整次编辑只能提交一次。
    if (endingEditRef.current) return;
    endingEditRef.current = true;
    const pos = getPos?.();
    if (!restoreFocus || !editor || editor.isDestroyed || typeof pos !== 'number') {
      updateAttributes({ latex: draft });
      setEditing(false);
      return;
    }
    const current = editor.state.doc.nodeAt(pos);
    if (current?.type !== node.type) {
      setEditing(false);
      return;
    }
    const tr = editor.state.tr.setNodeMarkup(pos, undefined, { ...current.attrs, latex: draft });
    const after = pos + current.nodeSize;
    const $after = tr.doc.resolve(after);
    const paragraph = editor.schema.nodes.paragraph;
    if ($after.nodeAfter?.isTextblock) {
      tr.setSelection(TextSelection.create(tr.doc, after + 1));
    } else if (paragraph && $after.parent.canReplaceWith($after.index(), $after.index(), paragraph)) {
      // 末尾或紧邻另一个非文本块时补正文；与公式提交同事务、同一步撤销。
      tr.insert(after, paragraph.create());
      tr.setSelection(TextSelection.create(tr.doc, after + 1));
    } else {
      tr.setSelection(NodeSelection.create(tr.doc, pos));
      restorePreviewFocusRef.current = true;
    }
    editor.view.dispatch(tr.scrollIntoView());
    editor.view.focus();
    setEditing(false);
  };

  const cancelEditing = (): void => {
    if (endingEditRef.current) return;
    endingEditRef.current = true;
    restorePreviewFocusRef.current = true;
    setDraft(latex);
    const pos = getPos?.();
    if (editor && !editor.isDestroyed && typeof pos === 'number' && editor.state.doc.nodeAt(pos)?.type === node.type) {
      editor.view.focus();
      editor.commands.setNodeSelection(pos);
    }
    setEditing(false);
  };

  const startEditing = useCallback((): void => {
    exitNodeSelection(view, getPos, node);
    // 预览移除 tabIndex 后 Chromium 会把焦点退到 body。先交回正文，
    // 保证下一帧 focusFieldSoon 能确认是用户主动进入编辑，再接管到输入框。
    view?.focus();
    endingEditRef.current = false;
    setEditing(true);
  }, [view, getPos, node]);

  useEffect(() => {
    if (!editor) return undefined;
    const onTransaction = ({ transaction }: { transaction: Transaction }): void => {
      const position: unknown = transaction.getMeta(EDIT_MATH_BLOCK_META);
      if (typeof position === 'number' && position === getPos?.()) startEditing();
    };
    editor.on('transaction', onTransaction);
    return () => { editor.off('transaction', onTransaction); };
  }, [editor, getPos, startEditing]);

  if (editing) {
    return (
      <NodeViewWrapper className="math-block is-editing" contentEditable={false} data-math-block="true">
        <div className="node-edit-toolbar">
          <span className="node-edit-label">编辑 LaTeX</span>
          <FinishButton onFinish={() => commit(true)} />
        </div>
        <textarea
          ref={areaRef}
          className="math-editor-input"
          value={draft}
          onChange={(event) => setDraft(event.target.value)}
          onBlur={() => commit()}
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
              commit(true);
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
      ref={previewRef}
      className={`math-block is-preview${selected ? ' is-selected' : ''}`}
      contentEditable={false}
      data-math-block="true"
      onClick={(event: ReactMouseEvent<HTMLElement>) => {
        const pos = getPos?.();
        if (typeof pos === 'number' && editor) editor.commands.setNodeSelection(pos);
        // 焦点留在可键盘激活的预览上，Enter / Space 与双击使用同一个编辑入口。
        event.currentTarget.focus({ preventScroll: true });
      }}
      onDoubleClick={startEditing}
      onKeyDown={(event: ReactKeyboardEvent<HTMLElement>) => {
        if (event.key === 'Enter' || event.key === ' ') {
          event.preventDefault();
          event.stopPropagation();
          startEditing();
        }
      }}
      role="button"
      tabIndex={0}
      aria-label="编辑公式块"
      title="单击选中，双击编辑 LaTeX"
    >
      {latex.trim() ? <MathDisplay latex={latex} displayMode /> : <span className="node-placeholder">点击输入公式</span>}
    </NodeViewWrapper>
  );
}

export function MathInlineNode({ node, editor, updateAttributes, selected, view, getPos }: NodeViewProps): JSX.Element {
  const latex = String(node.attrs.latex ?? '');
  const [editing, setEditing] = useState(!latex.trim());
  const [draft, setDraft] = useState(latex);
  const inputRef = useRef<HTMLInputElement>(null);
  const endingEditRef = useRef(false);

  useEffect(() => {
    if (!editing) setDraft(latex);
  }, [editing, latex]);
  useEffect(() => {
    if (editing) return focusFieldSoon(() => inputRef.current, { caretAtEnd: true });
    return undefined;
  }, [editing]);

  const focusAfterFormula = (): void => {
    const pos = getPos?.();
    if (typeof pos !== 'number' || !editor || editor.isDestroyed) return;
    editor.chain().setTextSelection(pos + node.nodeSize).focus().run();
  };

  /** 主动完成或取消都交回正文；点别处失焦只保存，不抢焦点。 */
  const commit = (restoreFocus = false): void => {
    if (endingEditRef.current) return;
    endingEditRef.current = true;
    updateAttributes({ latex: draft });
    setEditing(false);
    if (restoreFocus) focusAfterFormula();
  };

  const cancelEditing = (): void => {
    if (endingEditRef.current) return;
    endingEditRef.current = true;
    setDraft(latex);
    setEditing(false);
    focusAfterFormula();
  };

  const startEditing = (): void => {
    exitNodeSelection(view, getPos, node);
    endingEditRef.current = false;
    setEditing(true);
  };

  // 单击交给选区：原浮窗据此弹出，「转为行内公式」在那里就是取消开关；双击才进 LaTeX 编辑框。
  const selectFormula = (): void => {
    const pos = getPos?.();
    if (typeof pos !== 'number' || !editor) return;
    editor.chain().focus().setTextSelection({ from: pos, to: pos + node.nodeSize }).run();
  };

  if (editing) {
    return (
      <NodeViewWrapper as="span" className="math-inline is-editing" contentEditable={false} data-math-inline="true">
        <input
          ref={inputRef}
          className="math-inline-input"
          value={draft}
          onChange={(event) => setDraft(event.target.value)}
          onBlur={() => commit()}
          onKeyDown={(event) => {
            event.stopPropagation();
            if (event.key === 'Enter' || event.key === 'Escape') {
              event.preventDefault();
              if (event.key === 'Escape') {
                cancelEditing();
                return;
              }
              commit(true);
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
      onClick={selectFormula}
      onDoubleClick={startEditing}
      onKeyDown={(event: ReactKeyboardEvent<HTMLElement>) => {
        if (event.key === 'Enter' || event.key === ' ') {
          event.preventDefault();
          startEditing();
        }
      }}
      role="button"
      tabIndex={0}
      aria-label="编辑行内公式"
      title="单击选中，双击编辑 LaTeX"
    >
      {latex.trim() ? <MathDisplay latex={latex} displayMode={false} /> : <span className="node-placeholder">公式</span>}
    </NodeViewWrapper>
  );
}
