import { useEffect, useRef, useState } from 'react';
import type { CSSProperties, DragEvent } from 'react';
import { EditorContent, useEditor } from '@tiptap/react';
import StarterKit from '@tiptap/starter-kit';
import Placeholder from '@tiptap/extension-placeholder';
import { Message, Modal, Radio, Input as ArcoInput } from '@arco-design/web-react';
import { MarkdownBlock, MathBlock, MathInline, MermaidBlock, QuestionBlockNode, FileBlock, VideoBlock } from './extensions';
import { EditorBubbleMenu, EditorOutline } from './EditorChrome';
import { EditorStatusBar } from './EditorStatusBar';
import { EditorToolbar } from './EditorToolbar';
import { captureHeadingMoveSources, cleanupMovedHeadingSources, collapseMovedSelection, handleHeadingDrop, type HeadingMoveSource } from './headingDrag';
import { uploadAttachment } from '../../lib/attachments';
import { useUiStore } from '../../stores/uiStore';
import type { NoteAttachment, Question } from '../../lib/types';

export interface NotebookEditorProps {
  content?: Record<string, unknown>;
  onChange?: (content: Record<string, unknown>) => void;
  question?: Question;
  pageId?: number;
  focusMode?: boolean;
  onFocusModeChange?: (focus: boolean) => void;
  /** 由父页面注入新建页面操作；缺省时工具栏不渲染该按钮。 */
  onNewPage?: () => void;
}

const emptyDocument = { type: 'doc', content: [{ type: 'paragraph' }] };
const markdownPastePattern = /\$[^$\n]+\$|^\s*#{1,6}\s|^\s*[-*+]\s|^\s*\d+\.\s|```/m;

// 工作台几何契约（DESIGN.md）：编辑器画布 padding 与工具栏最小高度以内联
// 承载（jsdom 契约测试可读），其余视觉层由 app.css 的 .editor-canvas 提供。
const canvasPadding: CSSProperties = { paddingTop: 16, paddingLeft: 20, paddingRight: 20, paddingBottom: 24 };
const focusCanvasPadding: CSSProperties = { paddingTop: 0, paddingLeft: 0, paddingRight: 0, paddingBottom: 0 };

function hasFileTransfer(dataTransfer: DataTransfer | null | undefined): boolean {
  if (!dataTransfer) return false;
  return (dataTransfer.files?.length ?? 0) > 0
    || (dataTransfer.items?.length ?? 0) > 0
    || Array.from(dataTransfer.types ?? []).includes('Files');
}

function transferFiles(dataTransfer: DataTransfer | null | undefined): File[] {
  if (!dataTransfer) return [];
  const files = Array.from(dataTransfer.files ?? []);
  if (files.length) return files;
  return Array.from(dataTransfer.items ?? [])
    .filter((item) => item.kind === 'file')
    .map((item) => item.getAsFile())
    .filter((file): file is File => file !== null);
}

function pastedImageFiles(dataTransfer: DataTransfer | null | undefined): File[] {
  const files = transferFiles(dataTransfer).filter((file) => file.type.startsWith('image/'));
  if (files.length) return files;
  return [];
}

export function NotebookEditor({ content, onChange, pageId, focusMode, onFocusModeChange, onNewPage }: NotebookEditorProps): JSX.Element {
  // 「完成块编辑」当前绑定，提示文案跟随设置
  const finishKeys = useUiStore((state) => state.shortcutConfig.editorFinishBlock);
  const focusOutlineSide = useUiStore((state) => state.outlineSide);
  const notebookPanelsSwapped = useUiStore((state) => state.notebookPanelsSwapped);
  const [videoModalVisible, setVideoModalVisible] = useState(false);
  const [videoModalType, setVideoModalType] = useState<'url' | 'remote'>('url');
  const [videoModalUrl, setVideoModalUrl] = useState('');
  const [videoModalTitle, setVideoModalTitle] = useState('');
  const [uploadingCount, setUploadingCount] = useState(0);
  const [draggingFiles, setDraggingFiles] = useState(false);
  const [outlineOpen, setOutlineOpen] = useState(false);
  const fileInputRef = useRef<HTMLInputElement>(null);
  const dragDepthRef = useRef(0);
  const pendingHeadingMoveRef = useRef<HeadingMoveSource[]>([]);
  const pendingMoveRef = useRef(false);

  // 在当前光标位置插入一个块节点，并在其后留一个空段落方便继续输入。
  // 不再使用 setContent 重写全文 —— 否则新块永远被追加到文档末尾，
  // 无视用户光标位置（这是「点添加文件却插到末尾」的根因）。
  const insertBlockAtCursor = (type: string, attrs: Record<string, unknown>): void => {
    if (!editor) return;
    const pos = editor.state.selection.to;
    // 用 JSON 描述节点（而非 schema.create 出的 Node 实例），
    // insertContentAt 才能正确解析 atom 块（markdownBlock/fileBlock 等）。
    editor
      .chain()
      .focus()
      .insertContentAt(pos, [
        { type, attrs },
        { type: 'paragraph' }
      ])
      .run();
  };

  const insertFileBlock = (attachment: NoteAttachment): void => {
    if (!editor) return;
    insertBlockAtCursor('fileBlock', {
      attachmentId: attachment.id,
      fileName: attachment.fileName,
      mimeType: attachment.mimeType,
      fileSize: attachment.fileSize
    });
    Message.success(`已添加文件：${attachment.fileName}`);
  };

  const insertVideoBlock = (blockAttrs: Record<string, unknown>): void => {
    if (!editor) return;
    insertBlockAtCursor('videoBlock', blockAttrs);
  };

  const handleFileObjects = async (files: File[]): Promise<void> => {
    if (!files.length) return;
    if (pageId === undefined) {
      Message.error('请先保存页面后再添加文件');
      return;
    }
    setUploadingCount((count) => count + files.length);
    try {
      for (const file of files) {
        try {
          const attachment = await uploadAttachment(pageId, file);
          if (attachment.mimeType.startsWith('video/')) {
            insertVideoBlock({
              videoType: 'local',
              url: null,
              attachmentId: attachment.id,
              title: attachment.fileName,
              view: 'preview'
            });
          } else {
            insertFileBlock(attachment);
          }
        } catch (error) {
          console.error('[file] upload failed', error);
          Message.error(`文件「${file.name}」上传失败`);
        }
      }
    } finally {
      setUploadingCount((count) => Math.max(0, count - files.length));
    }
  };

  const handleBrowserFilePick = (event: React.ChangeEvent<HTMLInputElement>): void => {
    const files = event.target.files;
    if (files?.length) void handleFileObjects(Array.from(files));
    // 清空 value 以便再次选择同一文件
    event.target.value = '';
  };

  const pickFilesFromDialog = async (): Promise<void> => {
    if (!window.api?.file?.pickFiles || !window.api?.file?.readFile) {
      // 浏览器开发模式：通过隐藏的 input 弹出系统文件选择器
      fileInputRef.current?.click();
      return;
    }
    let picks: Array<{ path: string; name: string }> | null;
    try {
      picks = await window.api.file.pickFiles([
        { name: '所有文件', extensions: ['*'] },
        { name: '图片', extensions: ['png', 'jpg', 'jpeg', 'gif', 'webp', 'svg'] },
        { name: '视频', extensions: ['mp4', 'webm', 'mov'] },
        { name: 'PDF', extensions: ['pdf'] },
        { name: 'Word', extensions: ['docx'] },
        { name: 'PPT', extensions: ['pptx'] },
        { name: '压缩包', extensions: ['zip', 'rar', '7z'] }
      ]);
    } catch (error) {
      console.error('[file] picker failed', error);
      Message.error('无法打开文件选择器');
      return;
    }
    if (!picks || picks.length === 0) return;
    for (const pick of picks) {
      try {
        const buffer = await window.api.file.readFile(pick.path);
        const file = new File([buffer], pick.name);
        await handleFileObjects([file]);
      } catch (error) {
        console.error('[file] read failed', pick.path, error);
      }
    }
  };

  const editor = useEditor({
    extensions: [
      StarterKit,
      Placeholder.configure({ placeholder: '写下学习笔记… 工具栏可插入公式 / 图表 / Markdown 块，点击块即可编辑。' }),
      MathBlock,
      MathInline,
      MermaidBlock,
      MarkdownBlock,
      QuestionBlockNode,
      FileBlock,
      VideoBlock
    ],
    content: content || emptyDocument,
    editorProps: {
      attributes: {
        class: 'notebook-prosemirror',
        role: 'textbox',
        'aria-label': '笔记编辑器',
        'aria-multiline': 'true'
      },
      handlePaste: (view, event) => {
        const images = pastedImageFiles(event.clipboardData);
        if (images.length) {
          event.preventDefault();
          void handleFileObjects(images);
          return true;
        }
        const text = event.clipboardData?.getData('text/plain') ?? '';
        if (!text.trim() || !markdownPastePattern.test(text)) return false;
        const markdownNode = view.state.schema.nodes.markdownBlock?.create({ markdown: text });
        if (!markdownNode) return false;
        view.dispatch(view.state.tr.replaceSelectionWith(markdownNode).scrollIntoView());
        return true;
      },
      // Keep complete heading moves in one history transaction; other moves
      // use ProseMirror's default drop path and only need selection cleanup.
      handleDrop: (view, event, _slice, moved) => {
        const transfer = event.dataTransfer;
        const files = transferFiles(transfer);
        if (files.length === 0) {
          pendingMoveRef.current = false;
          pendingHeadingMoveRef.current = [];
          if (handleHeadingDrop(view, event, _slice, Boolean(moved))) return true;
          pendingMoveRef.current = Boolean(moved);
          pendingHeadingMoveRef.current = captureHeadingMoveSources(view, Boolean(moved));
          return false;
        }
        pendingMoveRef.current = false;
        pendingHeadingMoveRef.current = [];
        event.preventDefault();
        dragDepthRef.current = 0;
        setDraggingFiles(false);
        void handleFileObjects(files);
        return true;
      }
    },
    onUpdate: ({ editor: current, transaction }) => {
      if (transaction.getMeta('uiEvent') === 'drop') {
        const moved = pendingMoveRef.current;
        pendingMoveRef.current = false;
        const sources = pendingHeadingMoveRef.current;
        pendingHeadingMoveRef.current = [];
        const normalizedHeading = sources.length > 0 ? cleanupMovedHeadingSources(current, transaction, sources) : false;
        if (moved && !normalizedHeading) collapseMovedSelection(current);
      }
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

  // 工具栏插入公式 / 图表 / Markdown 块时，也走 insertBlockAtCursor，
  // 保证插入到当前光标位置而不是文档末尾。
  const appendBlock = (type: 'mathBlock' | 'mermaidBlock' | 'markdownBlock', attrs: Record<string, string>): void => {
    insertBlockAtCursor(type, attrs);
  };

  const closeVideoModal = (): void => {
    setVideoModalVisible(false);
    setVideoModalType('url');
    setVideoModalUrl('');
    setVideoModalTitle('');
  };

  const submitVideoModal = (): void => {
    const url = videoModalUrl.trim();
    if (!url) {
      Message.warning('请输入视频 URL');
      return;
    }
    const title = videoModalTitle.trim() || url;
    insertVideoBlock({
      videoType: videoModalType,
      url,
      attachmentId: null,
      title,
      view: videoModalType === 'url' ? 'link' : 'preview'
    });
    closeVideoModal();
  };

  const handleDragEnter = (event: DragEvent<HTMLDivElement>): void => {
    if (!hasFileTransfer(event.dataTransfer)) return;
    event.preventDefault();
    dragDepthRef.current += 1;
    setDraggingFiles(true);
  };

  const handleDragOver = (event: DragEvent<HTMLDivElement>): void => {
    if (!hasFileTransfer(event.dataTransfer)) return;
    event.preventDefault();
    setDraggingFiles(true);
  };

  const handleDragLeave = (event: DragEvent<HTMLDivElement>): void => {
    if (dragDepthRef.current === 0 && !hasFileTransfer(event.dataTransfer)) return;
    event.preventDefault();
    dragDepthRef.current = Math.max(0, dragDepthRef.current - 1);
    if (dragDepthRef.current === 0) setDraggingFiles(false);
  };

  const handleCanvasDrop = (event: DragEvent<HTMLDivElement>): void => {
    if (!draggingFiles && !hasFileTransfer(event.dataTransfer)) return;
    dragDepthRef.current = 0;
    setDraggingFiles(false);
  };

  const handleDragEnd = (): void => {
    dragDepthRef.current = 0;
    setDraggingFiles(false);
  };

  // 按偏好给大纲预留空间；普通模式的大纲限制在编辑器内，不覆盖全局导航。
  const outlineSide = focusMode ? focusOutlineSide : (notebookPanelsSwapped ? 'left' : 'right');
  const canvasStyle: CSSProperties = outlineOpen
    ? { ...(focusMode ? focusCanvasPadding : canvasPadding), [outlineSide === 'left' ? 'paddingLeft' : 'paddingRight']: 260 }
    : (focusMode ? focusCanvasPadding : canvasPadding);

  return (
    <div
      className={`editor-canvas${focusMode ? ' editor-canvas--focus' : ''}${draggingFiles ? ' is-dragging-files' : ''}`}
      style={canvasStyle}
      aria-busy={uploadingCount > 0}
      onDragEnter={handleDragEnter}
      onDragOver={handleDragOver}
      onDragLeave={handleDragLeave}
      onDrop={handleCanvasDrop}
      onDragEnd={handleDragEnd}
    >
    {focusMode ? <EditorToolbar editor={editor} focusMode onFocusModeChange={onFocusModeChange} onNewPage={onNewPage} onInsertBlock={appendBlock} onPickFiles={pickFilesFromDialog} onAddVideo={() => setVideoModalVisible(true)} outlineOpen={outlineOpen} onToggleOutline={() => setOutlineOpen((value) => !value)} finishKeys={finishKeys} /> : null}
    <div className={`editor-shell${focusMode ? ' is-focus' : ''}`}>
      {focusMode ? null : <EditorToolbar editor={editor} onFocusModeChange={onFocusModeChange} onNewPage={onNewPage} onInsertBlock={appendBlock} onPickFiles={pickFilesFromDialog} onAddVideo={() => setVideoModalVisible(true)} outlineOpen={outlineOpen} onToggleOutline={() => setOutlineOpen((value) => !value)} finishKeys={finishKeys} />}
      <div className="editor-content">
        <EditorContent editor={editor} />
      </div>
      <EditorStatusBar editor={editor} uploadingCount={uploadingCount} />
    </div>
    <EditorBubbleMenu editor={editor} />
    <EditorOutline editor={editor} open={outlineOpen} onClose={() => setOutlineOpen(false)} focusMode={Boolean(focusMode)} side={outlineSide} />
    <Modal
      title="添加视频"
      visible={videoModalVisible}
      onCancel={closeVideoModal}
      onOk={submitVideoModal}
    >
      <div style={{ display: 'grid', gap: 12 }}>
        <Radio.Group value={videoModalType} onChange={(value) => setVideoModalType(value as 'url' | 'remote')}>
          <Radio value="url">网址链接（B站/YouTube）</Radio>
          <Radio value="remote">远程视频直链（mp4/webm URL）</Radio>
        </Radio.Group>
        <ArcoInput autoFocus placeholder="视频 URL" value={videoModalUrl} onChange={setVideoModalUrl} onPressEnter={submitVideoModal} />
        <ArcoInput placeholder="标题（可选，网址视频用）" value={videoModalTitle} onChange={setVideoModalTitle} />
      </div>
    </Modal>
    <input ref={fileInputRef} type="file" multiple style={{ display: 'none' }} onChange={handleBrowserFilePick} />
    </div>
  );
}
