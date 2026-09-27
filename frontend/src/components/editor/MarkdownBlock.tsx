import { useEffect, useRef, useState, type KeyboardEvent as ReactKeyboardEvent } from 'react';
import { NodeViewWrapper, type NodeViewProps } from '@tiptap/react';
import { MarkdownContent } from '../markdown/MarkdownRenderer';
import { FinishButton, exitNodeSelection, focusFieldSoon, insertSoftTab } from './EditorChrome';
import { matchesAny } from '../../lib/shortcuts';
import { useUiStore } from '../../stores/uiStore';

export function MarkdownBlockNode({ node, updateAttributes, selected, view, getPos }: NodeViewProps): JSX.Element {
  const markdown = String(node.attrs.markdown ?? '');
  const [editing, setEditing] = useState(!markdown.trim());
  const [draft, setDraft] = useState(markdown);
  const areaRef = useRef<HTMLTextAreaElement>(null);
  const cancelBeforeBlurRef = useRef(false);

  useEffect(() => {
    if (!editing) setDraft(markdown);
  }, [editing, markdown]);
  useEffect(() => {
    if (editing) return focusFieldSoon(() => areaRef.current);
    return undefined;
  }, [editing]);

  const commit = (): void => {
    if (cancelBeforeBlurRef.current) {
      cancelBeforeBlurRef.current = false;
      return;
    }
    updateAttributes({ markdown: draft });
    setEditing(false);
  };

  const cancelEditing = (): void => {
    // 按 Esc 移除输入框时浏览器可能随后触发 blur，先记下“已取消”，避免 blur 提交旧草稿。
    cancelBeforeBlurRef.current = true;
    setDraft(markdown);
    setEditing(false);
  };

  const startEditing = (): void => {
    exitNodeSelection(view, getPos, node);
    cancelBeforeBlurRef.current = false;
    setEditing(true);
  };

  if (editing) {
    return (
      <NodeViewWrapper className={`markdown-block is-editing${selected ? ' is-selected' : ''}`} contentEditable={false} data-markdown-block="true">
        <div className="node-edit-toolbar">
          <span className="node-edit-label">编辑 Markdown</span>
          <FinishButton onFinish={commit} />
        </div>
        <div className="node-edit-body">
          <textarea
            ref={areaRef}
            className="markdown-block-input"
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
            aria-label="编辑 Markdown 内容"
            spellCheck={false}
            placeholder={'支持 **Markdown**、$E=mc^2$ 与 mermaid 代码块'}
          />
          <div className="node-live-preview markdown-block-preview">
            <MarkdownContent value={draft} />
          </div>
        </div>
      </NodeViewWrapper>
    );
  }

  return (
    <NodeViewWrapper
      className={`markdown-block is-preview${selected ? ' is-selected' : ''}`}
      contentEditable={false}
      data-markdown-block="true"
      onClick={startEditing}
      onKeyDown={(event: ReactKeyboardEvent<HTMLElement>) => {
        if (event.key === 'Enter' || event.key === ' ') {
          event.preventDefault();
          startEditing();
        }
      }}
      role="button"
      tabIndex={0}
      aria-label="编辑 Markdown 块"
      title="点击编辑 Markdown"
    >
      {markdown.trim()
        ? <div className="markdown-block-preview"><MarkdownContent value={markdown} /></div>
        : <span className="node-placeholder">点击输入 Markdown</span>}
    </NodeViewWrapper>
  );
}
