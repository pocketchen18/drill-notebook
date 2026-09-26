import type { ChainedCommands, Editor } from '@tiptap/core';
import {
  CodeXml,
  FileCode2,
  Heading1,
  Heading2,
  Heading3,
  List,
  ListOrdered,
  ListTodo,
  Minus,
  Network,
  Paperclip,
  Pilcrow,
  Quote,
  Radical,
  Sigma,
  Table,
  Video,
  type LucideIcon
} from 'lucide-react';
import { turnInto, type BlockKind } from './blockCommands';

export type InsertableBlock = 'mathBlock' | 'mermaidBlock' | 'markdownBlock';

/** 由笔记页面负责、而非编辑器 schema 本身提供的动作。 */
export interface CommandContext {
  /**
   * 命令的事务起点。斜杠菜单传入的链已包含“删除 /查询词”，命令在同一条链上继续，
   * 整次操作只提交一个事务（中途不会把半成品文档经 onChange 回传），撤销一次即可复原。
   */
  chain: () => ChainedCommands;
  insertBlock: (type: InsertableBlock, attrs: Record<string, string>, chain?: ChainedCommands) => void;
  pickFiles: () => void;
  addVideo: () => void;
}

export interface EditorCommand {
  readonly id: string;
  readonly label: string;
  readonly icon: LucideIcon;
  /** 小写英文词、拼音全拼与拼音首字母。 */
  readonly keywords: readonly string[];
  readonly shortcut?: string;
  readonly isAvailable?: (editor: Editor) => boolean;
  readonly run: (editor: Editor, context: CommandContext) => void;
}

export interface BlockKindOption {
  readonly kind: BlockKind;
  readonly label: string;
  readonly icon: LucideIcon;
  readonly shortcut: string;
  readonly keywords: readonly string[];
}

export const BLOCK_KIND_OPTIONS: readonly BlockKindOption[] = [
  { kind: 'paragraph', label: '正文', icon: Pilcrow, shortcut: 'Ctrl+Alt+0', keywords: ['text', 'paragraph', 'p', 'zhengwen', 'zw', 'wenben'] },
  { kind: 'heading1', label: '标题 1', icon: Heading1, shortcut: 'Ctrl+Alt+1', keywords: ['h1', 'heading1', 'title', 'biaoti1', 'bt1', 'yijibiaoti'] },
  { kind: 'heading2', label: '标题 2', icon: Heading2, shortcut: 'Ctrl+Alt+2', keywords: ['h2', 'heading2', 'biaoti2', 'bt2', 'erjibiaoti'] },
  { kind: 'heading3', label: '标题 3', icon: Heading3, shortcut: 'Ctrl+Alt+3', keywords: ['h3', 'heading3', 'biaoti3', 'bt3', 'sanjibiaoti'] },
  { kind: 'bulletList', label: '无序列表', icon: List, shortcut: 'Ctrl+Shift+8', keywords: ['bullet', 'list', 'ul', 'wuxuliebiao', 'wxlb', 'liebiao', 'lb'] },
  { kind: 'orderedList', label: '有序列表', icon: ListOrdered, shortcut: 'Ctrl+Shift+7', keywords: ['ordered', 'number', 'ol', 'youxuliebiao', 'yxlb', 'bianhao'] },
  { kind: 'taskList', label: '待办清单', icon: ListTodo, shortcut: 'Ctrl+Shift+9', keywords: ['todo', 'task', 'checkbox', 'daiban', 'db', 'dbqd', 'renwu', 'qingdan'] },
  { kind: 'blockquote', label: '引用', icon: Quote, shortcut: 'Ctrl+Shift+B', keywords: ['quote', 'blockquote', 'yinyong', 'yy'] },
  { kind: 'codeBlock', label: '代码块', icon: CodeXml, shortcut: 'Ctrl+Alt+C', keywords: ['code', 'codeblock', 'pre', 'daima', 'dmk', 'dm'] }
];

export const BLOCK_KIND_LABEL: Record<BlockKind, string> = Object.fromEntries(
  BLOCK_KIND_OPTIONS.map((option) => [option.kind, option.label])
) as Record<BlockKind, string>;

const notInTable = (editor: Editor): boolean => !editor.isActive('table');

export const INSERT_COMMANDS: readonly EditorCommand[] = [
  {
    id: 'table',
    label: '表格',
    icon: Table,
    keywords: ['table', 'grid', 'biaoge', 'bg'],
    isAvailable: notInTable,
    run: (_editor, context) => { context.chain().insertTable({ rows: 3, cols: 3, withHeaderRow: true }).run(); }
  },
  {
    id: 'mathBlock',
    label: '公式块',
    icon: Sigma,
    keywords: ['math', 'latex', 'formula', 'equation', 'gongshi', 'gs', 'gongshikuai'],
    run: (_editor, context) => context.insertBlock('mathBlock', { latex: '' }, context.chain())
  },
  {
    id: 'mathInline',
    label: '行内公式',
    icon: Radical,
    keywords: ['inline', 'math', 'latex', 'hangneigongshi', 'hngs', 'gongshi'],
    run: (_editor, context) => { context.chain().insertContent({ type: 'mathInline', attrs: { latex: '' } }).run(); }
  },
  {
    id: 'mermaidBlock',
    label: 'Mermaid 图表',
    icon: Network,
    keywords: ['mermaid', 'chart', 'diagram', 'flow', 'tubiao', 'tb', 'liuchengtu', 'lct'],
    run: (_editor, context) => context.insertBlock('mermaidBlock', { code: '' }, context.chain())
  },
  {
    id: 'markdownBlock',
    label: 'Markdown 块',
    icon: FileCode2,
    keywords: ['markdown', 'md'],
    run: (_editor, context) => context.insertBlock('markdownBlock', { markdown: '' }, context.chain())
  },
  {
    id: 'divider',
    label: '分隔线',
    icon: Minus,
    keywords: ['divider', 'hr', 'line', 'separator', 'fengexian', 'fgx'],
    run: (_editor, context) => { context.chain().setHorizontalRule().run(); }
  },
  {
    id: 'file',
    label: '图片 / 文件',
    icon: Paperclip,
    keywords: ['image', 'file', 'upload', 'attachment', 'tupian', 'tp', 'wenjian', 'wj', 'fujian', 'fj'],
    // 文件在异步选择后才插入，这里先单独提交“删除 /查询词”。
    run: (_editor, context) => { context.chain().run(); context.pickFiles(); }
  },
  {
    id: 'video',
    label: '视频',
    icon: Video,
    keywords: ['video', 'movie', 'shipin', 'sp'],
    run: (_editor, context) => { context.chain().run(); context.addVideo(); }
  }
];

export function slashCommands(): EditorCommand[] {
  const kinds: EditorCommand[] = BLOCK_KIND_OPTIONS.map((option) => ({
    id: option.kind,
    label: option.label,
    icon: option.icon,
    keywords: option.keywords,
    shortcut: option.shortcut,
    // 已是目标类型时 turnInto 不动文档，仍需提交起点链（斜杠菜单下即删除 /查询词）。
    run: (editor, context) => { if (!turnInto(editor, option.kind, null, context.chain())) context.chain().run(); }
  }));
  return [...kinds, ...INSERT_COMMANDS];
}

function scoreCommand(command: { label: string; keywords: readonly string[] }, query: string): number {
  const label = command.label.toLowerCase();
  if (label.startsWith(query)) return 4;
  if (command.keywords.some((keyword) => keyword.startsWith(query))) return 3;
  if (label.includes(query)) return 2;
  if (command.keywords.some((keyword) => keyword.includes(query))) return 1;
  return 0;
}

/** 前缀命中优先；同分时保持目录顺序。 */
export function filterCommands<T extends { label: string; keywords: readonly string[] }>(commands: readonly T[], rawQuery: string): T[] {
  const query = rawQuery.trim().toLowerCase().replace(/\s+/g, '');
  if (!query) return [...commands];
  return commands
    .map((command, index) => ({ command, index, score: scoreCommand(command, query) }))
    .filter((entry) => entry.score > 0)
    .sort((a, b) => b.score - a.score || a.index - b.index)
    .map((entry) => entry.command);
}
