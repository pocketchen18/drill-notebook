import { useCallback, useEffect, useRef, useState } from 'react';
import type { CSSProperties, DragEvent, MouseEvent as ReactMouseEvent } from 'react';
import { EditorContent, useEditor } from '@tiptap/react';
import type { ChainedCommands } from '@tiptap/core';
import { Message, Modal, Radio, Input as ArcoInput } from '@arco-design/web-react';
import { notebookExtensions } from './editorExtensions';
import { EditorBubbleMenu, EditorOutline } from './EditorChrome';
import { EditorStatusBar } from './EditorStatusBar';
import { EditorToolbar } from './EditorToolbar';
import { EditorFindBar, type FindRequest } from './EditorFindBar';
import { SlashMenu } from './SlashMenu';
import { BlockHandle } from './BlockHandle';
import { TableMenu } from './TableMenu';
import type { CommandContext } from './commandCatalog';
import { createPasteHandler, focusDocumentEnd, hasFileTransfer, isBelowLastBlock, transferFiles } from './editorInput';
import { captureHeadingMoveSources, cleanupMovedHeadingSources, collapseMovedSelection, handleHeadingDrop, type HeadingMoveSource } from './headingDrag';
import { uploadAttachment } from '../../lib/attachments';
import { isShortcutRecording, matchesAny } from '../../lib/shortcuts';
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
// ProseMirror 滚动到光标时为吸顶工具栏预留的空间。
const scrollInsets = { top: 88, bottom: 48, left: 8, right: 8 };
const closedFind: FindRequest = { open: false, replace: false, seed: '', token: 0 };

// 工作台几何契约（DESIGN.md）：编辑器画布 padding 与工具栏最小高度以内联
// 承载（jsdom 契约测试可读），其余视觉层由 app.css 的 .editor-canvas 提供。
const canvasPadding: CSSProperties = { paddingTop: 16, paddingLeft: 20, paddingRight: 20, paddingBottom: 24 };
const focusCanvasPadding: CSSProperties = { paddingTop: 0, paddingLeft: 0, paddingRight: 0, paddingBottom: 0 };

export function NotebookEditor({ content, onChange, pageId, focusMode, onFocusModeChange, onNewPage }: NotebookEditorProps): JSX.Element {
  const focusOutlineSide = useUiStore((state) => state.outlineSide);
  const notebookPanelsSwapped = useUiStore((state) => state.notebookPanelsSwapped);
  const [videoModalVisible, setVideoModalVisible] = useState(false);
  const [videoModalType, setVideoModalType] = useState<'url' | 'remote'>('url');
  const [videoModalUrl, setVideoModalUrl] = useState('');
  const [videoModalTitle, setVideoModalTitle] = useState('');
  const [uploadingCount, setUploadingCount] = useState(0);
  const [draggingFiles, setDraggingFiles] = useState(false);
  const [outlineOpen, setOutlineOpen] = useState(false);
  const [find, setFind] = useState<FindRequest>(closedFind);
  const fileInputRef = useRef<HTMLInputElement>(null);
  const canvasRef = useRef<HTMLDivElement>(null);
  const contentRef = useRef<HTMLDivElement>(null);
  const dockRef = useRef<HTMLDivElement>(null);
  const dragDepthRef = useRef(0);
  const pendingHeadingMoveRef = useRef<HeadingMoveSource[]>([]);
  const pendingMoveRef = useRef(false);
  const plainPasteAtRef = useRef(0);
  const handleFilesRef = useRef<(files: File[]) => void>(() => {});
  // 编辑器经 onChange 发出的内容对象；父页面原样回传时据此识别为回声。
  const emittedContentRef = useRef(new WeakSet<object>());
  const [extensions] = useState(notebookExtensions);
  const [handlePaste] = useState(() => createPasteHandler({
    onImages: (files) => handleFilesRef.current(files),
    isPlainPaste: () => Date.now() - plainPasteAtRef.current < 1000
  }));

  // 在当前光标位置插入一个块节点，并在其后留一个空段落方便继续输入。
  // 不再使用 setContent 重写全文 —— 否则新块永远被追加到文档末尾，
  // 无视用户光标位置（这是「点添加文件却插到末尾」的根因）。
  const insertBlockAtCursor = (type: string, attrs: Record<string, unknown>, chain?: ChainedCommands): void => {
    if (!editor) return;
    // 位置取链内事务的当前选区：斜杠菜单的链已先删掉“/查询词”，外面读 editor.state 会错位。
    // 用 JSON 描述节点（而非 schema.create 出的 Node 实例），
    // insertContentAt 才能正确解析 atom 块（markdownBlock/fileBlock 等）。
    (chain ?? editor.chain().focus())
      .command(({ tr, commands }) => commands.insertContentAt(tr.selection.to, [
        { type, attrs },
        { type: 'paragraph' }
      ]))
      .run();
  };

  const insertFileBlock = (attachment: NoteAttachment): void => {
    if (!editor) return;
    const image = attachment.mimeType.startsWith('image/');
    insertBlockAtCursor('fileBlock', {
      attachmentId: attachment.id,
      fileName: attachment.fileName,
      mimeType: attachment.mimeType,
      fileSize: attachment.fileSize,
      // 截图 / 图片直接显示；其它附件保持文件卡片。
      view: image ? 'preview' : 'download'
    });
    if (!image) Message.success(`已添加文件：${attachment.fileName}`);
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
  handleFilesRef.current = (files) => { void handleFileObjects(files); };

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
    extensions,
    content: content || emptyDocument,
    // 工具栏、状态栏与各浮层各自订阅需要的状态，打字时不整树重渲染。
    shouldRerenderOnTransaction: false,
    editorProps: {
      attributes: {
        class: 'notebook-prosemirror',
        role: 'textbox',
        'aria-label': '笔记编辑器',
        'aria-multiline': 'true'
      },
      scrollMargin: scrollInsets,
      scrollThreshold: scrollInsets,
      handleKeyDown: (_view, event) => {
        if ((event.ctrlKey || event.metaKey) && event.shiftKey && event.key.toLowerCase() === 'v') plainPasteAtRef.current = Date.now();
        return false;
      },
      handlePaste: (view, event) => handlePaste(view, event),
      handleClick: (view, _position, event) => {
        if (event.button !== 0 || !isBelowLastBlock(view, event.clientY)) return false;
        focusDocumentEnd(view);
        return true;
      },
      // 完整标题的移动保持为一次历史事务；其它移动走 ProseMirror 默认的 drop 流程，只需收束选区。
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
      const json = current.getJSON() as Record<string, unknown>;
      emittedContentRef.current.add(json);
      onChange?.(json);
    }
  });

  // 同步边界：父页面把编辑器自己发出的草稿原样回传只是“回声”，可能落后一笔事务
  // （例如节点视图 flushSync 在插入事务中途触发父组件重渲染），绝不能反向 setContent 覆盖编辑器；
  // 只有外部来的新内容（服务端快照等）才整篇替换。切页靠父组件 key 重建编辑器，不走这里。
  useEffect(() => {
    if (!editor || !content || emittedContentRef.current.has(content)) return;
    const next = JSON.stringify(content);
    const current = JSON.stringify(editor.getJSON());
    if (next !== current) editor.commands.setContent(content);
  }, [content, editor]);

  const openFind = useCallback((withReplace: boolean): void => {
    if (!editor) return;
    const { from, to, empty } = editor.state.selection;
    const selected = empty ? '' : editor.state.doc.textBetween(from, to, '\n');
    const seed = selected && !selected.includes('\n') && selected.length <= 120 ? selected : '';
    setFind((previous) => ({ open: true, replace: withReplace || (previous.open && previous.replace), seed, token: previous.token + 1 }));
  }, [editor]);

  const closeFind = useCallback((): void => {
    setFind((previous) => ({ ...previous, open: false, seed: '' }));
    editor?.commands.focus();
  }, [editor]);

  // 查找 / 替换快捷键可在设置中修改（与知识卡片全屏的查找一致）；焦点在编辑器以外的输入框时不拦截。
  useEffect(() => {
    const onKeyDown = (event: KeyboardEvent): void => {
      if (event.defaultPrevented || isShortcutRecording()) return;
      const config = useUiStore.getState().shortcutConfig;
      const replace = matchesAny(event, config.editorReplace);
      if (!replace && !matchesAny(event, config.editorFind)) return;
      const target = event.target instanceof HTMLElement ? event.target : null;
      const inCanvas = Boolean(target && canvasRef.current?.contains(target));
      if (!inCanvas && target && (target.isContentEditable || ['INPUT', 'TEXTAREA', 'SELECT'].includes(target.tagName))) return;
      event.preventDefault();
      openFind(replace);
    };
    window.addEventListener('keydown', onKeyDown);
    return () => window.removeEventListener('keydown', onKeyDown);
  }, [openFind]);

  // 工具栏换行时高度会变，把吸顶停靠区的实际高度交给 CSS（大纲吸顶位置据此避让）。
  useEffect(() => {
    const dock = dockRef.current;
    const canvas = canvasRef.current;
    if (!dock || !canvas || typeof ResizeObserver === 'undefined') return undefined;
    const observer = new ResizeObserver(() => {
      canvas.style.setProperty('--editor-dock-height', `${Math.round(dock.getBoundingClientRect().height)}px`);
    });
    observer.observe(dock);
    return () => observer.disconnect();
  }, [editor, focusMode]);

  if (!editor) return <div className="editor-shell"><div className="empty-state">正在加载编辑器…</div></div>;

  const commandContext: CommandContext = {
    chain: () => editor.chain().focus(),
    insertBlock: insertBlockAtCursor,
    pickFiles: () => { void pickFilesFromDialog(); },
    addVideo: () => setVideoModalVisible(true)
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

  // 点击正文区域下方的留白：光标移到文末（必要时补一个空段落）。
  const handleContentMouseDown = (event: ReactMouseEvent<HTMLDivElement>): void => {
    if (event.button !== 0 || event.target !== event.currentTarget || !isBelowLastBlock(editor.view, event.clientY)) return;
    event.preventDefault();
    focusDocumentEnd(editor.view);
  };

  // 按偏好给大纲预留空间；普通模式的大纲限制在编辑器内，不覆盖全局导航。
  const outlineSide = focusMode ? focusOutlineSide : (notebookPanelsSwapped ? 'left' : 'right');
  const canvasStyle: CSSProperties = outlineOpen
    ? { ...(focusMode ? focusCanvasPadding : canvasPadding), [outlineSide === 'left' ? 'paddingLeft' : 'paddingRight']: 260 }
    : (focusMode ? focusCanvasPadding : canvasPadding);

  const toolbar = (
    <div ref={dockRef} className={`editor-toolbar-dock${focusMode ? ' is-focus' : ''}`}>
      <EditorToolbar
        editor={editor}
        focusMode={Boolean(focusMode)}
        onFocusModeChange={onFocusModeChange}
        onNewPage={onNewPage}
        commandContext={commandContext}
        outlineOpen={outlineOpen}
        onToggleOutline={() => setOutlineOpen((value) => !value)}
        findOpen={find.open}
        onToggleFind={() => (find.open ? closeFind() : openFind(false))}
      />
      <EditorFindBar editor={editor} request={find} onClose={closeFind} onToggleReplace={() => setFind((previous) => ({ ...previous, replace: !previous.replace }))} />
    </div>
  );

  return (
    <div
      ref={canvasRef}
      className={`editor-canvas${focusMode ? ' editor-canvas--focus' : ''}${draggingFiles ? ' is-dragging-files' : ''}`}
      style={canvasStyle}
      aria-busy={uploadingCount > 0}
      onDragEnter={handleDragEnter}
      onDragOver={handleDragOver}
      onDragLeave={handleDragLeave}
      onDrop={handleCanvasDrop}
      onDragEnd={handleDragEnd}
    >
      {focusMode ? toolbar : null}
      <div className={`editor-shell${focusMode ? ' is-focus' : ''}`}>
        {focusMode ? null : toolbar}
        <div ref={contentRef} className="editor-content" onMouseDown={handleContentMouseDown}>
          <EditorContent editor={editor} />
          <BlockHandle editor={editor} containerRef={contentRef} />
        </div>
        <EditorStatusBar editor={editor} uploadingCount={uploadingCount} />
      </div>
      <EditorBubbleMenu editor={editor} />
      <SlashMenu editor={editor} context={commandContext} />
      <TableMenu editor={editor} />
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
