import { useEffect, useRef, useState } from 'react';
import { NodeViewWrapper, type NodeViewProps } from '@tiptap/react';
import { ImagePreview } from './preview/ImagePreview';
import { DocxPreview } from './preview/DocxPreview';
import { PdfPreview } from './preview/PdfPreview';
import { FileInfoPreview } from './preview/FileInfoPreview';
import { DownloadOnlyPreview } from './preview/DownloadOnlyPreview';

type View = 'preview' | 'download';

interface FileAttrs {
  attachmentId: number;
  fileName: string;
  mimeType: string;
  fileSize: number;
  view: View;
}

export function FileBlockNode({ node, updateAttributes, selected }: NodeViewProps): JSX.Element {
  const attrs = node.attrs as FileAttrs;
  // 本地 state 同步 view，避免 tip tap ReactNodeViewRenderer 不重渲染的问题
  const [localView, setLocalView] = useState<View>(attrs.view);
  const [menuOpen, setMenuOpen] = useState(false);
  const [menuUpward, setMenuUpward] = useState(false);
  const handleRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    setLocalView(attrs.view);
    setMenuOpen(false);
  }, [attrs.view]);

  // 点击菜单外部时自动收起
  useEffect(() => {
    if (!menuOpen) return;
    const onMouseDown = (event: MouseEvent): void => {
      if (handleRef.current && !handleRef.current.contains(event.target as Node)) {
        setMenuOpen(false);
      }
    };
    document.addEventListener('mousedown', onMouseDown);
    return () => document.removeEventListener('mousedown', onMouseDown);
  }, [menuOpen]);

  const switchView = (view: View): void => {
    setLocalView(view);
    updateAttributes({ view });
    setMenuOpen(false);
  };

  const toggleMenu = (event: React.MouseEvent): void => {
    event.stopPropagation();
    const nextOpen = !menuOpen;
    if (nextOpen && handleRef.current) {
      const rect = handleRef.current.getBoundingClientRect();
      setMenuUpward(rect.bottom + 120 > window.innerHeight);
    }
    setMenuOpen(nextOpen);
  };

  const previewComponent = (): JSX.Element => {
    const { mimeType } = attrs;
    if (mimeType.startsWith('image/')) return <ImagePreview {...attrs} />;
    if (mimeType === 'application/vnd.openxmlformats-officedocument.wordprocessingml.document') return <DocxPreview {...attrs} />;
    if (mimeType === 'application/pdf') return <PdfPreview {...attrs} />;
    return <FileInfoPreview {...attrs} />;
  };

  return (
    <NodeViewWrapper
      className={`file-block${selected ? ' is-selected' : ''}`}
      contentEditable={false}
      data-file-block="true"
    >
      <div className="file-block-handle" ref={handleRef}>
        <button
          type="button"
          className="file-block-handle-btn"
          contentEditable={false}
          onClick={toggleMenu}
          title="切换视图"
        >▾</button>
        {menuOpen ? (
          <div className={`file-block-menu${menuUpward ? ' is-upward' : ''}`} contentEditable={false}>
            <div className="file-block-menu-label">视图</div>
            {(['preview', 'download'] as View[]).map((view) => (
              <button
                key={view}
                type="button"
                className={`file-block-menu-item${localView === view ? ' is-active' : ''}`}
                onClick={(event) => { event.stopPropagation(); switchView(view); }}
              >{view === 'preview' ? '预览视图' : '下载视图'}</button>
            ))}
          </div>
        ) : null}
      </div>
      <div className={`file-block-body${localView === 'preview' ? ' is-preview' : ''}`}>
        {localView === 'preview' ? previewComponent() : <DownloadOnlyPreview {...attrs} />}
      </div>
    </NodeViewWrapper>
  );
}
