import { useEffect } from 'react';
import { EditorContent, useEditor } from '@tiptap/react';
import StarterKit from '@tiptap/starter-kit';
import Placeholder from '@tiptap/extension-placeholder';
import { Button, Divider, Space } from '@arco-design/web-react';
import { Bold, Code, FileCode2, Heading2, Italic, Network, Sigma } from 'lucide-react';
import { MarkdownBlock, MathBlock, MathInline, MermaidBlock, QuestionBlockNode } from './extensions';
import type { Question } from '../../lib/types';

export interface NotebookEditorProps {
  content?: Record<string, unknown>;
  onChange?: (content: Record<string, unknown>) => void;
  question?: Question;
}

const emptyDocument = { type: 'doc', content: [{ type: 'paragraph' }] };
const markdownPastePattern = /\$[^$\n]+\$|^\s*#{1,6}\s|^\s*[-*+]\s|^\s*\d+\.\s|```/m;

export function NotebookEditor({ content, onChange }: NotebookEditorProps): JSX.Element {
  const editor = useEditor({
    extensions: [
      StarterKit,
      Placeholder.configure({ placeholder: '写下学习笔记… 工具栏可插入公式 / 图表 / Markdown 块，点击块即可编辑。' }),
      MathBlock,
      MathInline,
      MermaidBlock,
      MarkdownBlock,
      QuestionBlockNode
    ],
    content: content || emptyDocument,
    editorProps: {
      attributes: { class: 'notebook-prosemirror' },
      handlePaste: (view, event) => {
        const text = event.clipboardData?.getData('text/plain') ?? '';
        if (!text.trim() || !markdownPastePattern.test(text)) return false;
        const markdownNode = view.state.schema.nodes.markdownBlock?.create({ markdown: text });
        if (!markdownNode) return false;
        view.dispatch(view.state.tr.replaceSelectionWith(markdownNode).scrollIntoView());
        return true;
      }
    },
    onUpdate: ({ editor: current }) => {
      onChange?.(current.getJSON() as Record<string, unknown>);
    }
  });

  useEffect(() => {
    if (!editor || !content) return;
    const next = JSON.stringify(content);
    const current = JSON.stringify(editor.getJSON());
    if (next !== current) editor.commands.setContent(content);
  }, [content, editor]);

  if (!editor) return <div className="editor-shell"><div className="empty-state">正在加载编辑器…</div></div>;

  const appendBlock = (type: 'mathBlock' | 'mermaidBlock' | 'markdownBlock', attrs: Record<string, string>): void => {
    const current = editor.getJSON() as { type: 'doc'; content?: Array<Record<string, unknown>> };
    const nodes = current.content ?? [{ type: 'paragraph' }];
    editor.commands.setContent({
      type: 'doc',
      content: [...nodes, { type, attrs }, { type: 'paragraph' }]
    });
    editor.commands.focus('end');
  };

  return (
    <div className="editor-shell">
      <div className="editor-toolbar">
        <Space size={4}>
          <Button type={editor.isActive('bold') ? 'primary' : 'text'} size="small" icon={<Bold size={16} />} onClick={() => editor.chain().focus().toggleBold().run()} aria-label="加粗" title="加粗" />
          <Button type={editor.isActive('italic') ? 'primary' : 'text'} size="small" icon={<Italic size={16} />} onClick={() => editor.chain().focus().toggleItalic().run()} aria-label="斜体" title="斜体" />
          <Button type={editor.isActive('heading', { level: 2 }) ? 'primary' : 'text'} size="small" icon={<Heading2 size={16} />} onClick={() => editor.chain().focus().toggleHeading({ level: 2 }).run()} aria-label="二级标题" title="二级标题" />
          <Button type={editor.isActive('code') ? 'primary' : 'text'} size="small" icon={<Code size={16} />} onClick={() => editor.chain().focus().toggleCode().run()} aria-label="行内代码" title="行内代码" />
          <Divider type="vertical" />
          <Button type="text" size="small" icon={<Sigma size={16} />} onClick={() => appendBlock('mathBlock', { latex: '' })}>公式</Button>
          <Button type="text" size="small" icon={<Network size={16} />} onClick={() => appendBlock('mermaidBlock', { code: '' })}>图表</Button>
          <Button type="text" size="small" icon={<FileCode2 size={16} />} onClick={() => appendBlock('markdownBlock', { markdown: '' })}>Markdown</Button>
        </Space>
        <span className="editor-hint">块默认渲染 · 点击即可编辑 · Ctrl/⌘+Enter 完成</span>
      </div>
      <div className="editor-content">
        <EditorContent editor={editor} />
      </div>
    </div>
  );
}
