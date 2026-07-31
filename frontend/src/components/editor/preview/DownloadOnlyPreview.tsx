import { useEffect, useState } from 'react';
import { Download } from 'lucide-react';
import { attachmentContentUrl } from '../../../lib/attachments';

export function DownloadOnlyPreview({ attachmentId, fileName, fileSize }: { attachmentId: number; fileName: string; fileSize: number }): JSX.Element {
  const [href, setHref] = useState('');
  useEffect(() => { void attachmentContentUrl(attachmentId).then(setHref); }, [attachmentId]);
  return (
    <div className="file-download-card">
      <div className="file-download-info">
        <span className="file-download-name">{fileName}</span>
        <span className="file-download-size">{formatBytes(fileSize)}</span>
      </div>
      <a className="file-download-btn" href={href} download={fileName}>
        <Download size={16} /> 下载
      </a>
    </div>
  );
}

function formatBytes(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  if (bytes < 1024 * 1024 * 1024) return `${(bytes / 1024 / 1024).toFixed(1)} MB`;
  return `${(bytes / 1024 / 1024 / 1024).toFixed(2)} GB`;
}
