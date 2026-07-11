import { useEffect, useRef, useState } from 'react';
import { NodeViewWrapper, type NodeViewProps } from '@tiptap/react';
import { MarkdownContent } from '../markdown/MarkdownRenderer';

export function MarkdownBlockNode({ node, updateAttributes, selected }: NodeViewProps): JSX.Element {
  const markdown = String(node.attrs.markdown ?? '');
  const [editing, setEditing] = useState(!markdown.trim());
  const [draft, setDraft] = useState(markdown);
  const areaRef = useRef<HTMLTextAreaElement>(null);

  useEffect(() => {
    if (!editing) setDraft(markdown);
  }, [editing, markdown]);
  useEffect(() => {
    if (editing) areaRef.current?.focus();
  }, [editing]);

  const commit = (): void => {
    updateAttributes({ markdown: draft });
    setEditing(false);
  };

  if (editing) {
    return (
      <NodeViewWrapper className={`markdown-block is-editing${selected ? ' is-selected' : ''}`} contentEditable={false} data-markdown-block="true">
        <div className="node-edit-toolbar">
          <span className="node-edit-label">编辑 Markdown</span>
          <button type="button" className="node-chip-btn" onClick={commit}>完成</button>
        </div>
        <textarea
          ref={areaRef}
          className="markdown-block-input"
          value={draft}
          onChange={(event) => setDraft(event.target.value)}
          onKeyDown={(event) => {
            event.stopPropagation();
            if (event.key === 'Escape') {
              setDraft(markdown);
              setEditing(false);
            }
            if (event.key === 'Enter' && (event.metaKey || event.ctrlKey)) {
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
      </NodeViewWrapper>
    );
  }

  return (
    <NodeViewWrapper
      className={`markdown-block is-preview${selected ? ' is-selected' : ''}`}
      contentEditable={false}
      data-markdown-block="true"
      onClick={() => setEditing(true)}
      title="点击编辑 Markdown"
    >
      {markdown.trim()
        ? <div className="markdown-block-preview"><MarkdownContent value={markdown} /></div>
        : <span className="node-placeholder">点击输入 Markdown</span>}
    </NodeViewWrapper>
  );
}
