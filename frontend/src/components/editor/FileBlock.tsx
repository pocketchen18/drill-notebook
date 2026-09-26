import { useEffect, useState } from 'react';
import { NodeViewWrapper, type NodeViewProps } from '@tiptap/react';
import { Eye, EyeOff, Download, File as FileIcon, Minimize2 } from 'lucide-react';
import { ImagePreview } from './preview/ImagePreview';
import { DocxPreview } from './preview/DocxPreview';
import { PdfPreview } from './preview/PdfPreview';
import { PptxPreview } from './preview/PptxPreview';
import { ArchiveBrowser } from './preview/ArchiveBrowser';
import { typeIcons, fileCategory, formatBytes } from './preview/FileInfoPreview';
import { attachmentContentUrl } from '../../lib/attachments';
import { exitNodeSelection } from './EditorChrome';

type View = 'preview' | 'download';

interface FileAttrs {
  attachmentId: number;
  fileName: string;
  mimeType: string;
  fileSize: number;
  view: View;
  width: number | null;
}

// 图片宽度档位（占正文栏百分比）；null 为原始尺寸，不超过正文栏宽。
const IMAGE_WIDTHS: ReadonlyArray<readonly [number | null, string]> = [[30, '小'], [50, '中'], [75, '大'], [null, '原始']];

export function FileBlockNode({ node, updateAttributes, selected, view, getPos }: NodeViewProps): JSX.Element {
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
  const isImage = attrs.mimeType.startsWith('image/');
  const inlinePreviewable = isImage
    || attrs.mimeType === 'application/pdf'
    || attrs.mimeType === 'application/vnd.openxmlformats-officedocument.wordprocessingml.document'
    || attrs.mimeType === 'application/vnd.openxmlformats-officedocument.presentationml.presentation';

  const togglePreview = (): void => {
    exitNodeSelection(view, getPos, node);
    if (isZip) { setBrowserOpen(true); return; }
    updateAttributes({ view: inlineOpen ? 'download' : 'preview' });
  };

  const previewOpen = isZip ? browserOpen : inlineOpen;

  // 预览状态下的图片以图片本身为主体显示，悬停时出现宽度、下载与收起操作。
  if (isImage && inlineOpen) {
    const width = typeof attrs.width === 'number' ? attrs.width : null;
    return (
      <NodeViewWrapper className={`file-block file-block--image${selected ? ' is-selected' : ''}`} contentEditable={false} data-file-block="true">
        <figure className={`file-image-figure${width ? ' has-width' : ''}`} style={width ? { width: `${width}%` } : undefined}>
          <ImagePreview {...attrs} />
          <div className="file-image-toolbar" role="toolbar" aria-label="图片操作">
            {IMAGE_WIDTHS.map(([value, label]) => (
              <button
                key={label}
                type="button"
                className={width === value ? 'is-active' : undefined}
                aria-pressed={width === value}
                title={value ? `宽度 ${value}%` : '原始尺寸'}
                onMouseDown={(event) => event.stopPropagation()}
                onClick={() => updateAttributes({ width: value })}
              >{label}</button>
            ))}
            <span className="file-image-toolbar__divider" aria-hidden="true" />
            <a className="file-image-toolbar__icon" href={href} download={attrs.fileName} aria-label={`下载 ${attrs.fileName}`} title="下载" onMouseDown={(event) => event.stopPropagation()}>
              <Download size={15} />
            </a>
            <button type="button" className="file-image-toolbar__icon" aria-label="收起为卡片" title="收起为卡片" onMouseDown={(event) => event.stopPropagation()} onClick={togglePreview}>
              <Minimize2 size={15} />
            </button>
          </div>
        </figure>
      </NodeViewWrapper>
    );
  }

  const renderBody = (): JSX.Element => {
    const { mimeType } = attrs;
    if (mimeType === 'application/vnd.openxmlformats-officedocument.wordprocessingml.document') return <DocxPreview {...attrs} />;
    if (mimeType === 'application/vnd.openxmlformats-officedocument.presentationml.presentation') return <PptxPreview {...attrs} />;
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
            aria-label={previewOpen ? '收起预览' : '预览'}
            onMouseDown={(event) => event.stopPropagation()}
            onClick={togglePreview}
          >{previewOpen ? <EyeOff size={17} /> : <Eye size={17} />}</button>
          <a className="file-block-action" title="下载" aria-label={`下载 ${attrs.fileName}`} href={href} download={attrs.fileName} onMouseDown={(event) => event.stopPropagation()}>
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
