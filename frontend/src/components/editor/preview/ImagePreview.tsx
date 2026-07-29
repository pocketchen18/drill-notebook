import { useEffect, useState } from 'react';
import { attachmentContentUrl } from '../../../lib/attachments';

export function ImagePreview({ attachmentId, fileName }: { attachmentId: number; fileName: string; fileSize: number }): JSX.Element {
  const [src, setSrc] = useState('');
  useEffect(() => { void attachmentContentUrl(attachmentId).then(setSrc); }, [attachmentId]);
  if (!src) return <div className="file-preview-loading">加载中…</div>;
  return <img className="file-image-preview" src={src} alt={fileName} loading="lazy" />;
}
