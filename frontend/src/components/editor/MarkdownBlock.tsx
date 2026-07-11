import { NodeViewWrapper, type NodeViewProps } from '@tiptap/react';
import { MarkdownContent } from '../markdown/MarkdownRenderer';

export function MarkdownBlockNode({ node, updateAttributes }: NodeViewProps): JSX.Element {
  const markdown = String(node.attrs.markdown ?? '');
  return <NodeViewWrapper className="markdown-block" contentEditable={false} data-markdown-block="true">
    <div className="node-edit-label">Markdown / LaTeX</div>
    <textarea
      className="markdown-block-input"
      value={markdown}
      onChange={(event) => updateAttributes({ markdown: event.target.value })}
      onKeyDown={(event) => event.stopPropagation()}
      aria-label="编辑 Markdown 内容"
      spellCheck={false}
    />
    <div className="markdown-block-preview">
      <MarkdownContent value={markdown} />
    </div>
  </NodeViewWrapper>;
}
