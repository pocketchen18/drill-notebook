import type { ReactNode } from 'react';
import { useEditorState, type Editor } from '@tiptap/react';
import {
  Bold,
  Code,
  CodeXml,
  Expand,
  FileCode2,
  FilePlus2,
  Heading1,
  Heading2,
  Heading3,
  IndentDecrease,
  IndentIncrease,
  Italic,
  List,
  ListOrdered,
  ListTree,
  Minus,
  Network,
  Paperclip,
  Pilcrow,
  Quote,
  Redo2,
  RemoveFormatting,
  Sigma,
  Strikethrough,
  Undo2,
  Video
} from 'lucide-react';
import { describeAccelerators } from '../../lib/shortcuts';

type InsertBlockType = 'mathBlock' | 'mermaidBlock' | 'markdownBlock';

export interface EditorToolbarProps {
  editor: Editor;
  focusMode?: boolean;
  onFocusModeChange?: (focus: boolean) => void;
  onNewPage?: () => void;
  onInsertBlock: (type: InsertBlockType, attrs: Record<string, string>) => void;
  onPickFiles: () => void;
  onAddVideo: () => void;
  outlineOpen: boolean;
  onToggleOutline: () => void;
  finishKeys: string[];
}

interface CommandButtonProps {
  label: string;
  ariaLabel?: string;
  icon: ReactNode;
  onClick: () => void;
  active?: boolean;
  toggle?: boolean;
  primary?: boolean;
  disabled?: boolean;
  text?: boolean;
  title?: string;
  shortcut?: string;
}

function CommandButton({ label, ariaLabel = label, icon, onClick, active = false, toggle = false, primary = false, disabled = false, text = false, title, shortcut }: CommandButtonProps): JSX.Element {
  return (
    <button
      type="button"
      className={`editor-command-button${active ? ' is-active' : ''}${primary ? ' is-primary' : ''}${text ? ' has-label' : ''}`}
      onMouseDown={(event) => event.preventDefault()}
      onClick={onClick}
      disabled={disabled}
      aria-label={ariaLabel}
      aria-pressed={toggle ? active : undefined}
      aria-keyshortcuts={shortcut}
      title={shortcut ? `${title ?? label}（${shortcut}）` : (title ?? label)}
    >
      {icon}
      {text ? <span className="editor-command-label">{label}</span> : null}
    </button>
  );
}

function CommandGroup({ label, children }: { label: string; children: ReactNode }): JSX.Element {
  return <div className="editor-toolbar__group" role="group" aria-label={label}>{children}</div>;
}

export function EditorToolbar({
  editor,
  focusMode = false,
  onFocusModeChange,
  onNewPage,
  onInsertBlock,
  onPickFiles,
  onAddVideo,
  outlineOpen,
  onToggleOutline,
  finishKeys
}: EditorToolbarProps): JSX.Element {
  // TipTap does not make a parent component re-render for every transaction by
  // default. Subscribe to its transaction counter so active marks and history
  // availability stay accurate while the cursor moves or text changes.
  const transactionNumber = useEditorState({ editor, selector: ({ transactionNumber: number }) => number });
  const finishHint = finishKeys.length ? `${describeAccelerators(finishKeys)} 完成` : '点「完成」结束编辑';

  return (
    <div
      className={`editor-toolbar${focusMode ? ' is-focus' : ''}`}
      style={{ minHeight: 44 }}
      role="toolbar"
      aria-label="编辑器工具栏"
      data-editor-transaction={transactionNumber}
    >
      <div className="editor-toolbar__commands">
        <CommandGroup label="历史">
          <CommandButton label="撤销" icon={<Undo2 size={16} />} onClick={() => editor.chain().focus().undo().run()} disabled={!editor.can().undo()} shortcut="Ctrl+Z" title="撤销" />
          <CommandButton label="重做" icon={<Redo2 size={16} />} onClick={() => editor.chain().focus().redo().run()} disabled={!editor.can().redo()} shortcut="Ctrl+Shift+Z" title="重做" />
        </CommandGroup>
        <span className="editor-toolbar__separator" aria-hidden="true" />
        <CommandGroup label="文字格式">
          <CommandButton label="加粗" icon={<Bold size={16} />} active={editor.isActive('bold')} toggle shortcut="Ctrl+B" onClick={() => editor.chain().focus().toggleBold().run()} />
          <CommandButton label="斜体" icon={<Italic size={16} />} active={editor.isActive('italic')} toggle shortcut="Ctrl+I" onClick={() => editor.chain().focus().toggleItalic().run()} />
          <CommandButton label="行内代码" icon={<Code size={16} />} active={editor.isActive('code')} toggle onClick={() => editor.chain().focus().toggleCode().run()} />
          <CommandButton label="删除线" icon={<Strikethrough size={16} />} active={editor.isActive('strike')} toggle onClick={() => editor.chain().focus().toggleStrike().run()} />
          <CommandButton label="清除格式" icon={<RemoveFormatting size={16} />} onClick={() => editor.chain().focus().unsetAllMarks().clearNodes().run()} />
        </CommandGroup>
        <span className="editor-toolbar__separator" aria-hidden="true" />
        <CommandGroup label="段落格式">
          <CommandButton label="一级标题" icon={<Heading1 size={16} />} active={editor.isActive('heading', { level: 1 })} toggle onClick={() => editor.chain().focus().toggleHeading({ level: 1 }).run()} />
          <CommandButton label="二级标题" icon={<Heading2 size={16} />} active={editor.isActive('heading', { level: 2 })} toggle onClick={() => editor.chain().focus().toggleHeading({ level: 2 }).run()} />
          <CommandButton label="三级标题" icon={<Heading3 size={16} />} active={editor.isActive('heading', { level: 3 })} toggle onClick={() => editor.chain().focus().toggleHeading({ level: 3 }).run()} />
          <CommandButton label="无序列表" icon={<List size={16} />} active={editor.isActive('bulletList')} toggle onClick={() => editor.chain().focus().toggleBulletList().run()} />
          <CommandButton label="有序列表" icon={<ListOrdered size={16} />} active={editor.isActive('orderedList')} toggle onClick={() => editor.chain().focus().toggleOrderedList().run()} />
          <CommandButton label="引用" icon={<Quote size={16} />} active={editor.isActive('blockquote')} toggle onClick={() => editor.chain().focus().toggleBlockquote().run()} />
          <CommandButton label="代码块" icon={<CodeXml size={16} />} active={editor.isActive('codeBlock')} toggle onClick={() => editor.chain().focus().toggleCodeBlock().run()} />
          <CommandButton label="分隔线" icon={<Minus size={16} />} onClick={() => editor.chain().focus().setHorizontalRule().run()} />
          <CommandButton label="正文" icon={<Pilcrow size={16} />} active={editor.isActive('paragraph')} toggle onClick={() => editor.chain().focus().setParagraph().run()} />
          <CommandButton label="减少缩进" icon={<IndentDecrease size={16} />} onClick={() => editor.chain().focus().liftListItem('listItem').run()} disabled={!editor.can().liftListItem('listItem')} />
          <CommandButton label="增加缩进" icon={<IndentIncrease size={16} />} onClick={() => editor.chain().focus().sinkListItem('listItem').run()} disabled={!editor.can().sinkListItem('listItem')} />
        </CommandGroup>
        <span className="editor-toolbar__separator" aria-hidden="true" />
        <CommandGroup label="插入内容">
          <CommandButton label="公式" icon={<Sigma size={16} />} text onClick={() => onInsertBlock('mathBlock', { latex: '' })} />
          <CommandButton label="图表" icon={<Network size={16} />} text onClick={() => onInsertBlock('mermaidBlock', { code: '' })} />
          <CommandButton label="Markdown" icon={<FileCode2 size={16} />} text onClick={() => onInsertBlock('markdownBlock', { markdown: '' })} />
          <CommandButton label="添加文件" icon={<Paperclip size={16} />} text onClick={onPickFiles} title="添加文件：支持拖拽或粘贴图片" />
          <CommandButton label="添加视频" icon={<Video size={16} />} text onClick={onAddVideo} />
        </CommandGroup>
        <span className="editor-toolbar__separator" aria-hidden="true" />
        <CommandGroup label="视图">
          {onNewPage ? <CommandButton label="新建页面" icon={<FilePlus2 size={16} />} text onClick={onNewPage} /> : null}
          <CommandButton label={focusMode ? '退出专注' : '专注模式'} ariaLabel="专注模式" icon={<Expand size={16} />} text active={focusMode} toggle primary={focusMode} onClick={() => onFocusModeChange?.(!focusMode)} title="专注模式" />
          <CommandButton label="大纲" icon={<ListTree size={16} />} text active={outlineOpen} toggle onClick={onToggleOutline} title="大纲：展开/收起文档标题目录" />
        </CommandGroup>
      </div>
      {!focusMode ? <span className="editor-hint" title="块默认渲染，点击块即可编辑">{finishHint}</span> : null}
    </div>
  );
}
