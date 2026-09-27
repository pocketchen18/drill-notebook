import { Extension, type Extensions } from '@tiptap/core';
import { Plugin } from '@tiptap/pm/state';
import StarterKit from '@tiptap/starter-kit';
import Placeholder from '@tiptap/extension-placeholder';
import Link from '@tiptap/extension-link';
import Underline from '@tiptap/extension-underline';
import Superscript from '@tiptap/extension-superscript';
import Subscript from '@tiptap/extension-subscript';
import TaskList from '@tiptap/extension-task-list';
import TaskItem from '@tiptap/extension-task-item';
import Table from '@tiptap/extension-table';
import TableRow from '@tiptap/extension-table-row';
import TableHeader from '@tiptap/extension-table-header';
import TableCell from '@tiptap/extension-table-cell';
import { search } from 'prosemirror-search';
import { NotebookCodeBlock } from './CodeBlockView';
import { ColorHighlight, TextColorMark } from './textColors';
import { SlashCommand } from './SlashCommand';
import { BlockKeymap, BlockTargetHighlight } from './blockCommands';
import { FileBlock, MarkdownBlock, MathBlock, MathInline, MermaidBlock, QuestionBlockNode, VideoBlock } from './extensions';
import { isAllowedLinkUri, openExternalLink } from './links';

const LinkOpener = Extension.create({
  name: 'linkOpener',
  addProseMirrorPlugins() {
    return [
      new Plugin({
        props: {
          handleClick(view, position, event) {
            if (!(event.ctrlKey || event.metaKey) || event.button !== 0) return false;
            const $position = view.state.doc.resolve(position);
            const marks = [...$position.marks(), ...(view.state.doc.nodeAt(position)?.marks ?? [])];
            const href = marks.find((mark) => mark.type.name === 'link')?.attrs.href;
            if (typeof href !== 'string' || !openExternalLink(href)) return false;
            event.preventDefault();
            return true;
          }
        }
      })
    ];
  }
});

const SearchHighlight = Extension.create({
  name: 'searchHighlight',
  addProseMirrorPlugins: () => [search()]
});

// 自定义块内部不再有 data-drag-handle，整块会变成原生可拖元素：点编辑框时鼠标稍一抖动就会把整块拖走（块“消失”）。
// 整块移动只走正文外的 ⋮⋮ 手柄，这里拦下从块内部发起的原生拖动。
const NodeViewDragGuard = Extension.create({
  name: 'nodeViewDragGuard',
  addProseMirrorPlugins() {
    return [
      new Plugin({
        props: {
          handleDOMEvents: {
            dragstart(view, event) {
              const target = event.target instanceof Element ? event.target : null;
              const island = target?.closest('[contenteditable="false"]');
              if (!island || !view.dom.contains(island)) return false;
              event.preventDefault();
              return true;
            }
          }
        }
      })
    ];
  }
});

export const EMPTY_DOCUMENT_PLACEHOLDER = '写点什么，或输入 “/” 插入块…';

export function notebookExtensions(): Extensions {
  return [
    StarterKit.configure({ codeBlock: false, dropcursor: { color: 'var(--accent)', width: 2 } }),
    Placeholder.configure({
      includeChildren: false,
      placeholder: ({ editor, node }) => {
        if (editor.isEmpty) return EMPTY_DOCUMENT_PLACEHOLDER;
        if (!editor.isFocused) return '';
        if (node.type.name === 'heading') return `标题 ${node.attrs.level}`;
        return '输入 “/” 插入块';
      }
    }),
    Underline,
    Superscript,
    Subscript,
    TextColorMark,
    ColorHighlight,
    Link.configure({
      openOnClick: false,
      autolink: true,
      linkOnPaste: true,
      defaultProtocol: 'https',
      HTMLAttributes: { rel: 'noopener noreferrer nofollow', target: null },
      isAllowedUri: (url, context) => context.defaultValidate(url) && isAllowedLinkUri(url)
    }),
    TaskList,
    TaskItem.configure({ nested: true }),
    Table.configure({ resizable: true, cellMinWidth: 64 }),
    TableRow,
    TableHeader,
    TableCell,
    NotebookCodeBlock,
    MathBlock,
    MathInline,
    MermaidBlock,
    MarkdownBlock,
    QuestionBlockNode,
    FileBlock,
    VideoBlock,
    SlashCommand,
    BlockKeymap,
    BlockTargetHighlight,
    LinkOpener,
    SearchHighlight,
    NodeViewDragGuard
  ];
}
