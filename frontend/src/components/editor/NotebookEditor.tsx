import { useEffect, useRef, useState } from 'react';
import { EditorContent, useEditor } from '@tiptap/react';
import StarterKit from '@tiptap/starter-kit';
import Placeholder from '@tiptap/extension-placeholder';
import { Button, Divider, Message, Modal, Radio, Space, Input as ArcoInput } from '@arco-design/web-react';
import { Bold, Code, Expand, FileCode2, Heading2, Italic, Network, Paperclip, Sigma, Video } from 'lucide-react';
import { MarkdownBlock, MathBlock, MathInline, MermaidBlock, QuestionBlockNode, FileBlock, VideoBlock } from './extensions';
import { uploadAttachment } from '../../lib/attachments';
import type { NoteAttachment, Question } from '../../lib/types';

export interface NotebookEditorProps {
  content?: Record<string, unknown>;
  onChange?: (content: Record<string, unknown>) => void;
  question?: Question;
  pageId?: number;
  focusMode?: boolean;
  onFocusModeChange?: (focus: boolean) => void;
}

const emptyDocument = { type: 'doc', content: [{ type: 'paragraph' }] };
const markdownPastePattern = /\$[^$\n]+\$|^\s*#{1,6}\s|^\s*[-*+]\s|^\s*\d+\.\s|```/m;

export function NotebookEditor({ content, onChange, pageId, focusMode, onFocusModeChange }: NotebookEditorProps): JSX.Element {
  const [videoModalVisible, setVideoModalVisible] = useState(false);
  const [videoModalType, setVideoModalType] = useState<'url' | 'remote'>('url');
  const [videoModalUrl, setVideoModalUrl] = useState('');
  const [videoModalTitle, setVideoModalTitle] = useState('');
  const fileInputRef = useRef<HTMLInputElement>(null);

  const insertFileBlock = (attachment: NoteAttachment): void => {
    if (!editor) return;
    // 与 appendBlock / insertVideoBlock 保持一致：setContent 在末尾追加节点
    const current = editor.getJSON() as { type: 'doc'; content?: Array<Record<string, unknown>> };
    const nodes = current.content ?? [{ type: 'paragraph' }];
    editor.commands.setContent({
      type: 'doc',
      content: [...nodes, { type: 'fileBlock', attrs: { attachmentId: attachment.id, fileName: attachment.fileName, mimeType: attachment.mimeType, fileSize: attachment.fileSize } }, { type: 'paragraph' }]
    });
    editor.commands.focus('end');
    Message.success(`已添加文件：${attachment.fileName}`);
  };

  const insertVideoBlock = (blockAttrs: Record<string, unknown>): void => {
    if (!editor) return;
    // 与 appendBlock(公式/图表/Markdown) 保持一致：重设全文在末尾追加
    const current = editor.getJSON() as { type: 'doc'; content?: Array<Record<string, unknown>> };
    const nodes = current.content ?? [{ type: 'paragraph' }];
    editor.commands.setContent({
      type: 'doc',
      content: [...nodes, { type: 'videoBlock', attrs: blockAttrs }, { type: 'paragraph' }]
    });
    editor.commands.focus('end');
  };

  const handleFileObjects = async (files: File[]): Promise<void> => {
    if (!files.length) return;
    if (pageId === undefined) {
      Message.error('请先保存页面后再添加文件');
      return;
    }
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
    const picks = await window.api.file.pickFiles([
      { name: '图片', extensions: ['png', 'jpg', 'jpeg', 'gif', 'webp', 'svg'] },
      { name: '视频', extensions: ['mp4', 'webm', 'mov'] },
      { name: 'PDF', extensions: ['pdf'] },
      { name: 'Word', extensions: ['docx'] },
      { name: 'PPT', extensions: ['pptx'] },
      { name: '压缩包', extensions: ['zip', 'rar', '7z'] },
      { name: '所有文件', extensions: ['*'] }
    ]);
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
      attributes: { class: 'notebook-prosemirror' },
      handlePaste: (view, event) => {
        const text = event.clipboardData?.getData('text/plain') ?? '';
        if (!text.trim() || !markdownPastePattern.test(text)) return false;
        const markdownNode = view.state.schema.nodes.markdownBlock?.create({ markdown: text });
        if (!markdownNode) return false;
        view.dispatch(view.state.tr.replaceSelectionWith(markdownNode).scrollIntoView());
        return true;
      },
      handleDrop: (_view, event) => {
        const transfer = event.dataTransfer;
        if (!transfer || !transfer.files || transfer.files.length === 0) return false;
        event.preventDefault();
        const files = Array.from(transfer.files);
        void handleFileObjects(files);
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

  const toolbarElement = (
    <div className={`editor-toolbar${focusMode ? ' is-focus' : ''}`}>
      <Space size={4}>
        <Button type={editor.isActive('bold') ? 'primary' : 'text'} size="small" icon={<Bold size={16} />} onClick={() => editor.chain().focus().toggleBold().run()} aria-label="加粗" title="加粗" />
        <Button type={editor.isActive('italic') ? 'primary' : 'text'} size="small" icon={<Italic size={16} />} onClick={() => editor.chain().focus().toggleItalic().run()} aria-label="斜体" title="斜体" />
        <Button type={editor.isActive('heading', { level: 2 }) ? 'primary' : 'text'} size="small" icon={<Heading2 size={16} />} onClick={() => editor.chain().focus().toggleHeading({ level: 2 }).run()} aria-label="二级标题" title="二级标题" />
        <Button type={editor.isActive('code') ? 'primary' : 'text'} size="small" icon={<Code size={16} />} onClick={() => editor.chain().focus().toggleCode().run()} aria-label="行内代码" title="行内代码" />
        <Divider type="vertical" />
        <Button type="text" size="small" icon={<Sigma size={16} />} onClick={() => appendBlock('mathBlock', { latex: '' })}>公式</Button>
        <Button type="text" size="small" icon={<Network size={16} />} onClick={() => appendBlock('mermaidBlock', { code: '' })}>图表</Button>
        <Button type="text" size="small" icon={<FileCode2 size={16} />} onClick={() => appendBlock('markdownBlock', { markdown: '' })}>Markdown</Button>
        <Divider type="vertical" />
        <Button type="text" size="small" icon={<Paperclip size={16} />} onClick={pickFilesFromDialog} aria-label="添加文件" title="添加文件：弹出系统文件选择器，可选图片/视频/PDF/Word/PPT/压缩包等，支持多选。也可直接把文件拖进编辑器或 Ctrl+V 粘贴图片。">添加文件</Button>
        <Button type="text" size="small" icon={<Video size={16} />} onClick={() => setVideoModalVisible(true)} aria-label="添加视频" title="添加视频：弹窗里填网址（Bilibili/YouTube）或远程视频直链（mp4/webm URL），可自填标题。插入后可切三视图：链接/标题/预览。">添加视频</Button>
        <Divider type="vertical" />
        <Button
          type={focusMode ? 'primary' : 'text'}
          size="small"
          icon={<Expand size={16} />}
          onClick={() => onFocusModeChange?.(!focusMode)}
          aria-label="专注模式"
          title="专注模式：隐藏左侧页面列表与顶部标题栏，让编辑器铺满整个笔记本页面。再次点击恢复原始布局。"
        >
          {focusMode ? '退出专注' : '专注模式'}
        </Button>
      </Space>
      {focusMode ? null : <span className="editor-hint">块默认渲染 · 点击即可编辑 · Ctrl/⌘+Enter 完成 · 工具栏「添加文件」或拖拽/粘贴插入附件</span>}
    </div>
  );

  return (
    <>
    {focusMode ? toolbarElement : null}
    <div className={`editor-shell${focusMode ? ' is-focus' : ''}`}>
      {focusMode ? null : toolbarElement}
      <div className="editor-content">
        <EditorContent editor={editor} />
      </div>
    </div>
    <Modal
      title="添加视频"
      visible={videoModalVisible}
      onCancel={() => setVideoModalVisible(false)}
      onOk={() => {
        if (videoModalType === 'url') {
          insertVideoBlock({ videoType: 'url', url: videoModalUrl, attachmentId: null, title: videoModalTitle || videoModalUrl, view: 'link' });
        } else {
          insertVideoBlock({ videoType: 'remote', url: videoModalUrl, attachmentId: null, title: videoModalTitle || videoModalUrl, view: 'preview' });
        }
        setVideoModalVisible(false);
        setVideoModalUrl('');
        setVideoModalTitle('');
      }}
    >
      <div style={{ display: 'grid', gap: 12 }}>
        <Radio.Group value={videoModalType} onChange={(value) => setVideoModalType(value as 'url' | 'remote')}>
          <Radio value="url">网址链接（B站/YouTube）</Radio>
          <Radio value="remote">远程视频直链（mp4/webm URL）</Radio>
        </Radio.Group>
        <ArcoInput placeholder="视频 URL" value={videoModalUrl} onChange={setVideoModalUrl} />
        <ArcoInput placeholder="标题（可选，网址视频用）" value={videoModalTitle} onChange={setVideoModalTitle} />
      </div>
    </Modal>
    <input ref={fileInputRef} type="file" multiple style={{ display: 'none' }} onChange={handleBrowserFilePick} />
    </>
  );
}
