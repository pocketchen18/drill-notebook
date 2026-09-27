import { forwardRef, useRef, useState, type ReactNode } from 'react';
import { useEditorState, type Editor } from '@tiptap/react';
import {
  Bold,
  ChevronDown,
  Code,
  Expand,
  FilePlus2,
  Highlighter,
  Italic,
  Link as LinkIcon,
  List,
  ListOrdered,
  ListTodo,
  ListTree,
  Minimize2,
  Network,
  Paperclip,
  Plus,
  Redo2,
  Search,
  Sigma,
  Strikethrough,
  Table,
  Underline,
  Undo2
} from 'lucide-react';
import { MenuPopover, type MenuSection } from './MenuPopover';
import { BLOCK_KIND_LABEL, BLOCK_KIND_OPTIONS, INSERT_COMMANDS, type CommandContext } from './commandCatalog';
import { currentBlockKind, turnInto, type BlockKind, type NotebookKeymapStorage } from './blockCommands';
import { describeAccelerators } from '../../lib/shortcuts';
import { useUiStore } from '../../stores/uiStore';

export interface EditorToolbarProps {
  editor: Editor;
  focusMode?: boolean;
  onFocusModeChange?: (focus: boolean) => void;
  onNewPage?: () => void;
  commandContext: CommandContext;
  outlineOpen: boolean;
  onToggleOutline: () => void;
  findOpen: boolean;
  onToggleFind: () => void;
}

interface ToolbarButtonProps {
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
  menu?: boolean;
  expanded?: boolean;
  trailing?: ReactNode;
  /** 标签是唯一内容（块类型），窄宽度下也不收起。 */
  keepLabel?: boolean;
}

const ToolbarButton = forwardRef<HTMLButtonElement, ToolbarButtonProps>(function ToolbarButton(
  { label, ariaLabel = label, icon, onClick, active = false, toggle = false, primary = false, disabled = false, text = false, title, shortcut, menu = false, expanded = false, trailing, keepLabel = false },
  ref
) {
  return (
    <button
      ref={ref}
      type="button"
      className={`editor-command-button${active ? ' is-active' : ''}${primary ? ' is-primary' : ''}${text ? ' has-label' : ''}${menu ? ' has-menu' : ''}${keepLabel ? ' keeps-label' : ''}`}
      // 点击命令时保留编辑器选区。
      onMouseDown={(event) => event.preventDefault()}
      onClick={onClick}
      disabled={disabled}
      aria-label={ariaLabel}
      aria-pressed={toggle ? active : undefined}
      aria-haspopup={menu ? 'menu' : undefined}
      aria-expanded={menu ? expanded : undefined}
      aria-keyshortcuts={shortcut}
      title={shortcut ? `${title ?? label}（${shortcut}）` : (title ?? label)}
    >
      {icon}
      {text ? <span className="editor-command-label">{label}</span> : null}
      {trailing}
    </button>
  );
});

function ToolbarMenu({ label, ariaLabel, icon, text = false, keepLabel = false, title, sections }: { label: string; ariaLabel: string; icon: ReactNode; text?: boolean; keepLabel?: boolean; title?: string; sections: readonly MenuSection[] }): JSX.Element {
  const [open, setOpen] = useState(false);
  const triggerRef = useRef<HTMLButtonElement>(null);
  return (
    <>
      <ToolbarButton
        ref={triggerRef}
        label={label}
        ariaLabel={ariaLabel}
        icon={icon}
        text={text}
        keepLabel={keepLabel}
        title={title}
        menu
        expanded={open}
        onClick={() => setOpen((value) => !value)}
        trailing={<ChevronDown size={13} className="editor-command-caret" aria-hidden="true" />}
      />
      <MenuPopover open={open} onClose={() => setOpen(false)} getAnchor={() => triggerRef.current?.getBoundingClientRect() ?? null} sections={sections} ariaLabel={ariaLabel} triggerRef={triggerRef} />
    </>
  );
}

function CommandGroup({ label, children }: { label: string; children: ReactNode }): JSX.Element {
  return <div className="editor-toolbar__group" role="group" aria-label={label}>{children}</div>;
}

const Separator = (): JSX.Element => <span className="editor-toolbar__separator" aria-hidden="true" />;

const TOOLBAR_BLOCK_KINDS: readonly BlockKind[] = ['paragraph', 'heading1', 'heading2', 'heading3', 'blockquote', 'codeBlock'];
const INSERT_MENU: ReadonlyArray<{ id: string; label?: string }> = [
  { id: 'markdownBlock' },
  { id: 'divider' },
  { id: 'mathInline' },
  { id: 'video', label: '添加视频' }
];

export function EditorToolbar({
  editor,
  focusMode = false,
  onFocusModeChange,
  onNewPage,
  commandContext,
  outlineOpen,
  onToggleOutline,
  findOpen,
  onToggleFind
}: EditorToolbarProps): JSX.Element {
  // 用选择器订阅，命令状态没变的事务不会触发工具栏重渲染。
  const state = useEditorState({
    editor,
    selector: ({ editor: current }) => ({
      canUndo: current.can().undo(),
      canRedo: current.can().redo(),
      blockKind: currentBlockKind(current),
      bold: current.isActive('bold'),
      italic: current.isActive('italic'),
      underline: current.isActive('underline'),
      strike: current.isActive('strike'),
      code: current.isActive('code'),
      highlight: current.isActive('highlight'),
      link: current.isActive('link'),
      bulletList: current.isActive('bulletList'),
      orderedList: current.isActive('orderedList'),
      taskList: current.isActive('taskList'),
      inTable: current.isActive('table')
    })
  });
  const findKeys = useUiStore((store) => store.shortcutConfig.editorFind);

  const chain = () => editor.chain().focus();
  const toggleList = (kind: 'bulletList' | 'orderedList' | 'taskList'): void => {
    const toggled = kind === 'bulletList' ? chain().toggleBulletList().run()
      : kind === 'orderedList' ? chain().toggleOrderedList().run()
        : chain().toggleTaskList().run();
    // 标题和代码块不能直接包进列表，改为转换。
    if (!toggled) turnInto(editor, kind);
  };
  const openLinkEditor = (): void => {
    (editor.storage.notebookKeymap as NotebookKeymapStorage | undefined)?.openLinkEditor?.();
  };

  const blockLabel = TOOLBAR_BLOCK_KINDS.includes(state.blockKind) ? BLOCK_KIND_LABEL[state.blockKind] : BLOCK_KIND_LABEL.paragraph;
  const blockSections: MenuSection[] = [{
    key: 'kinds',
    items: BLOCK_KIND_OPTIONS.filter((option) => TOOLBAR_BLOCK_KINDS.includes(option.kind)).map((option) => ({
      key: option.kind,
      label: option.label,
      icon: <option.icon size={16} />,
      shortcut: option.shortcut,
      active: option.kind === state.blockKind,
      onSelect: () => { turnInto(editor, option.kind); }
    }))
  }];
  const insertSections: MenuSection[] = [{
    key: 'insert',
    items: INSERT_MENU.flatMap(({ id, label }) => {
      const command = INSERT_COMMANDS.find((entry) => entry.id === id);
      return command ? [{ key: id, label: label ?? command.label, icon: <command.icon size={16} />, onSelect: () => command.run(editor, commandContext) }] : [];
    })
  }];

  return (
    <div className={`editor-toolbar${focusMode ? ' is-focus' : ''}`} style={{ minHeight: 44 }} role="toolbar" aria-label="编辑器工具栏">
      <div className="editor-toolbar__commands">
        <CommandGroup label="历史">
          <ToolbarButton label="撤销" icon={<Undo2 size={16} />} onClick={() => chain().undo().run()} disabled={!state.canUndo} shortcut="Ctrl+Z" />
          <ToolbarButton label="重做" icon={<Redo2 size={16} />} onClick={() => chain().redo().run()} disabled={!state.canRedo} shortcut="Ctrl+Shift+Z" />
        </CommandGroup>
        <Separator />
        <CommandGroup label="段落格式">
          <ToolbarMenu label={blockLabel} ariaLabel={`块类型：${blockLabel}`} icon={null} text keepLabel title="块类型" sections={blockSections} />
          <ToolbarButton label="无序列表" icon={<List size={16} />} active={state.bulletList} toggle shortcut="Ctrl+Shift+8" onClick={() => toggleList('bulletList')} />
          <ToolbarButton label="有序列表" icon={<ListOrdered size={16} />} active={state.orderedList} toggle shortcut="Ctrl+Shift+7" onClick={() => toggleList('orderedList')} />
          <ToolbarButton label="待办清单" icon={<ListTodo size={16} />} active={state.taskList} toggle shortcut="Ctrl+Shift+9" onClick={() => toggleList('taskList')} />
        </CommandGroup>
        <Separator />
        <CommandGroup label="文字格式">
          <ToolbarButton label="加粗" icon={<Bold size={16} />} active={state.bold} toggle shortcut="Ctrl+B" onClick={() => chain().toggleBold().run()} />
          <ToolbarButton label="斜体" icon={<Italic size={16} />} active={state.italic} toggle shortcut="Ctrl+I" onClick={() => chain().toggleItalic().run()} />
          <ToolbarButton label="下划线" icon={<Underline size={16} />} active={state.underline} toggle shortcut="Ctrl+U" onClick={() => chain().toggleUnderline().run()} />
          <ToolbarButton label="删除线" icon={<Strikethrough size={16} />} active={state.strike} toggle shortcut="Ctrl+Shift+S" onClick={() => chain().toggleStrike().run()} />
          <ToolbarButton label="行内代码" icon={<Code size={16} />} active={state.code} toggle shortcut="Ctrl+E" onClick={() => chain().toggleCode().run()} />
          <ToolbarButton label="高亮" icon={<Highlighter size={16} />} active={state.highlight} toggle shortcut="Ctrl+Shift+H" onClick={() => chain().toggleHighlight().run()} />
          <ToolbarButton label="链接" icon={<LinkIcon size={16} />} active={state.link} toggle shortcut="Ctrl+K" onClick={openLinkEditor} />
        </CommandGroup>
        <Separator />
        <CommandGroup label="插入内容">
          <ToolbarButton label="公式" icon={<Sigma size={16} />} text onClick={() => commandContext.insertBlock('mathBlock', { latex: '' })} />
          <ToolbarButton label="表格" icon={<Table size={16} />} text disabled={state.inTable} onClick={() => chain().insertTable({ rows: 3, cols: 3, withHeaderRow: true }).run()} />
          <ToolbarButton label="图表" icon={<Network size={16} />} text onClick={() => commandContext.insertBlock('mermaidBlock', { code: '' })} />
          <ToolbarButton label="文件" ariaLabel="添加文件" icon={<Paperclip size={16} />} text onClick={commandContext.pickFiles} title="添加文件：支持拖拽或粘贴图片" />
          <ToolbarMenu label="插入" ariaLabel="插入" icon={<Plus size={16} />} text sections={insertSections} />
        </CommandGroup>
      </div>
      <div className="editor-toolbar__aside" role="group" aria-label="视图">
        <ToolbarButton label="查找" icon={<Search size={16} />} active={findOpen} toggle shortcut={findKeys.length ? describeAccelerators(findKeys) : undefined} onClick={onToggleFind} />
        <ToolbarButton label="大纲" icon={<ListTree size={16} />} text active={outlineOpen} toggle onClick={onToggleOutline} title="大纲：展开/收起文档标题目录" />
        <ToolbarButton label={focusMode ? '退出专注' : '专注模式'} ariaLabel="专注模式" icon={focusMode ? <Minimize2 size={16} /> : <Expand size={16} />} text active={focusMode} toggle primary={focusMode} onClick={() => onFocusModeChange?.(!focusMode)} title="专注模式" />
        {onNewPage ? <ToolbarButton label="新建页面" icon={<FilePlus2 size={16} />} text onClick={onNewPage} /> : null}
      </div>
    </div>
  );
}
