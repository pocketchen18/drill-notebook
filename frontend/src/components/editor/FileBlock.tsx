import { useEffect, useState } from 'react';
import { NodeViewWrapper, type NodeViewProps } from '@tiptap/react';
import { Eye, EyeOff, Download, File as FileIcon } from 'lucide-react';
import { ImagePreview } from './preview/ImagePreview';
import { DocxPreview } from './preview/DocxPreview';
import { PdfPreview } from './preview/PdfPreview';
import { ArchiveBrowser } from './preview/ArchiveBrowser';
import { typeIcons, fileCategory, formatBytes } from './preview/FileInfoPreview';
import { attachmentContentUrl } from '../../lib/attachments';

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
  const inlineOpen = attrs.view === 'preview';
  const [browserOpen, setBrowserOpen] = useState(false);
  const [href, setHref] = useState('');

  useEffect(() => {
    void attachmentContentUrl(attrs.attachmentId).then(setHref);
  }, [attrs.attachmentId]);

  const category = fileCategory(attrs.mimeType, attrs.fileName);
  const Icon = typeIcons[category] ?? FileIcon;
  const isZip = attrs.fileName.toLowerCase().endsWith('.zip');
  const inlinePreviewable = attrs.mimeType.startsWith('image/')
    || attrs.mimeType === 'application/pdf'
    || attrs.mimeType === 'application/vnd.openxmlformats-officedocument.wordprocessingml.document';

  const togglePreview = (): void => {
    if (isZip) { setBrowserOpen(true); return; }
    updateAttributes({ view: inlineOpen ? 'download' : 'preview' });
  };

  const previewOpen = isZip ? browserOpen : inlineOpen;

  const renderBody = (): JSX.Element => {
    const { mimeType } = attrs;
    if (mimeType.startsWith('image/')) return <ImagePreview {...attrs} />;
    if (mimeType === 'application/vnd.openxmlformats-officedocument.wordprocessingml.document') return <DocxPreview {...attrs} />;
    if (mimeType === 'application/pdf') return <PdfPreview {...attrs} />;
    return <div className="file-preview-unavailable">暂不支持预览</div>;
  };

  return (
    <NodeViewWrapper
      className={`file-block${selected ? ' is-selected' : ''}`}
      contentEditable={false}
      data-file-block="true"
    >
      <div className="file-block-card">
        <div className="file-block-icon"><Icon size={26} strokeWidth={1.6} /></div>
        <div className="file-block-meta">
          <span className="file-block-name">{attrs.fileName}</span>
          <span className="file-block-size">{formatBytes(attrs.fileSize)}</span>
        </div>
        <div className="file-block-actions">
          <button
            type="button"
            className={`file-block-action${previewOpen ? ' is-active' : ''}`}
            title={previewOpen ? '收起预览' : '预览'}
            onClick={togglePreview}
          >{previewOpen ? <EyeOff size={17} /> : <Eye size={17} />}</button>
          <a className="file-block-action" title="下载" href={href} download={attrs.fileName}>
            <Download size={17} />
          </a>
        </div>
      </div>
      {!isZip && inlineOpen ? (
        <div className={`file-block-body${inlinePreviewable ? ' is-preview' : ''}`}>{renderBody()}</div>
      ) : null}
      {isZip && browserOpen ? (
        <ArchiveBrowser
          attachmentId={attrs.attachmentId}
          fileName={attrs.fileName}
          fileSize={attrs.fileSize}
          onClose={() => setBrowserOpen(false)}
        />
      ) : null}
    </NodeViewWrapper>
  );
}
