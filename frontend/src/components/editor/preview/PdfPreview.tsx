import { useEffect, useState } from 'react';
import { attachmentContentUrl } from '../../../lib/attachments';

export function PdfPreview({ attachmentId }: { attachmentId: number; fileName: string; fileSize: number }): JSX.Element {
  const [src, setSrc] = useState('');
  useEffect(() => { void attachmentContentUrl(attachmentId).then(setSrc); }, [attachmentId]);
  if (!src) return <div className="file-preview-loading">加载中…</div>;
  return <iframe className="file-pdf-preview" src={src} title="PDF 预览" />;
}
