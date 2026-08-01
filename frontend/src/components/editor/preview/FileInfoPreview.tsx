import { FileIcon, FileImage, FileText, FileType, FileArchive, Video, EyeOff } from 'lucide-react';

const typeIcons: Record<string, typeof FileIcon> = {
  image: FileImage,
  video: Video,
  pdf: FileText,
  word: FileType,
  ppt: FileType,
  archive: FileArchive,
};

function fileCategory(mimeType: string, fileName: string): keyof typeof typeIcons {
  if (mimeType.startsWith('image/')) return 'image';
  if (mimeType.startsWith('video/')) return 'video';
  if (mimeType === 'application/pdf') return 'pdf';
  if (mimeType.includes('word') || fileName.endsWith('.docx') || fileName.endsWith('.doc')) return 'word';
  if (mimeType.includes('presentation') || fileName.endsWith('.pptx') || fileName.endsWith('.ppt')) return 'ppt';
  return 'archive';
}

function categoryLabel(category: string): string {
  const labels: Record<string, string> = {
    image: '图片', video: '视频', pdf: 'PDF',
    word: 'Word 文档', ppt: 'PPT 演示文稿', archive: '压缩文件',
  };
  return labels[category] ?? '文件';
}

export function FileInfoPreview({ fileName, fileSize, mimeType }: { attachmentId: number; fileName: string; fileSize: number; mimeType: string }): JSX.Element {
  const category = fileCategory(mimeType, fileName);
  const IconComponent = typeIcons[category] ?? FileIcon;
  return (
    <div className="file-info-preview">
      <div className="file-info-icon">
        <IconComponent size={40} strokeWidth={1.5} />
      </div>
      <div className="file-info-meta">
        <span className="file-info-name">{fileName}</span>
        <span className="file-info-size">{formatBytes(fileSize)}</span>
        <span className="file-info-type">{categoryLabel(category)}</span>
      </div>
      <div className="file-info-unavailable">
        <EyeOff size={14} />
        <span>暂不支持预览</span>
      </div>
    </div>
  );
}

function formatBytes(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  if (bytes < 1024 * 1024 * 1024) return `${(bytes / 1024 / 1024).toFixed(1)} MB`;
  return `${(bytes / 1024 / 1024 / 1024).toFixed(2)} GB`;
}
