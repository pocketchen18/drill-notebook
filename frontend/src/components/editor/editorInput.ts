import { TextSelection } from '@tiptap/pm/state';
import type { EditorView } from '@tiptap/pm/view';
import { codeFromClipboard, markdownFromClipboard, markdownToPasteHtml } from './markdownPaste';

/**
 * 只认真正的文件：编辑器内拖动块或文字同样带有 `DataTransfer.items`，不能因此亮起“拖入文件”遮罩。
 */
export function hasFileTransfer(dataTransfer: DataTransfer | null | undefined): boolean {
  if (!dataTransfer) return false;
  if ((dataTransfer.files?.length ?? 0) > 0) return true;
  if (Array.from(dataTransfer.types ?? []).includes('Files')) return true;
  return Array.from(dataTransfer.items ?? []).some((item) => item.kind === 'file');
}

export function transferFiles(dataTransfer: DataTransfer | null | undefined): File[] {
  if (!dataTransfer) return [];
  const files = Array.from(dataTransfer.files ?? []);
  if (files.length) return files;
  return Array.from(dataTransfer.items ?? [])
    .filter((item) => item.kind === 'file')
    .map((item) => item.getAsFile())
    .filter((file): file is File => file !== null);
}

export function pastedImageFiles(dataTransfer: DataTransfer | null | undefined): File[] {
  return transferFiles(dataTransfer).filter((file) => file.type.startsWith('image/'));
}

export interface PasteHandlerOptions {
  onImages: (files: File[]) => void;
  /** 刚按下 Ctrl+Shift+V 时为 true，此时必须保持纯文本粘贴。 */
  isPlainPaste: () => boolean;
}

/**
 * 剪贴板图片上传为附件；从 VS Code 复制的代码转为代码块；Markdown 文本经 ProseMirror 自身的
 * HTML 粘贴流程转成原生块（一步撤销）。
 */
export function createPasteHandler({ onImages, isPlainPaste }: PasteHandlerOptions): (view: EditorView, event: ClipboardEvent) => boolean {
  let converting = false;
  return (view, event) => {
    // `pasteHTML` 会用同一个事件再次调用粘贴处理，此时直接放行。
    if (converting) return false;
    const data = event.clipboardData;
    const images = pastedImageFiles(data);
    if (images.length) {
      event.preventDefault();
      onImages(images);
      return true;
    }
    if (isPlainPaste() || view.state.selection.$from.parent.type.spec.code) return false;
    const code = codeFromClipboard(data);
    const codeBlock = view.state.schema.nodes.codeBlock;
    if (code && codeBlock) {
      const node = codeBlock.create({ language: code.language }, view.state.schema.text(code.code));
      view.dispatch(view.state.tr.replaceSelectionWith(node).scrollIntoView().setMeta('paste', true).setMeta('uiEvent', 'paste'));
      return true;
    }
    const markdown = markdownFromClipboard(data);
    if (markdown === null) return false;
    converting = true;
    try {
      return view.pasteHTML(markdownToPasteHtml(markdown), event);
    } finally {
      converting = false;
    }
  };
}

export function isBelowLastBlock(view: EditorView, clientY: number): boolean {
  const last = view.dom.lastElementChild;
  return last ? clientY > last.getBoundingClientRect().bottom : false;
}

const NON_TEXT_ENDINGS = new Set(['table', 'codeBlock']);

/**
 * 点击最后一个块下方：光标移到文末；若最后是表格、代码块或自定义块，先补一个段落以便继续书写。
 */
export function focusDocumentEnd(view: EditorView): void {
  const { doc, schema } = view.state;
  const last = doc.lastChild;
  const tr = view.state.tr;
  if (!last || last.isAtom || NON_TEXT_ENDINGS.has(last.type.name)) tr.insert(doc.content.size, schema.nodes.paragraph.create());
  tr.setSelection(TextSelection.atEnd(tr.doc));
  view.dispatch(tr.scrollIntoView());
  view.focus();
}
