import { FileIcon, FileImage, FileText, FileType, FileArchive, Video } from 'lucide-react';

export const typeIcons: Record<string, typeof FileIcon> = {
  image: FileImage,
  video: Video,
  pdf: FileText,
  word: FileType,
  ppt: FileType,
  text: FileText,
  archive: FileArchive,
  other: FileIcon,
};

export function fileCategory(mimeType: string, fileName: string): keyof typeof typeIcons {
  const lower = fileName.toLowerCase();
  if (mimeType.startsWith('image/')) return 'image';
  if (mimeType.startsWith('video/')) return 'video';
  if (mimeType === 'application/pdf' || lower.endsWith('.pdf')) return 'pdf';
  if (mimeType.includes('word') || lower.endsWith('.docx') || lower.endsWith('.doc')) return 'word';
  if (mimeType.includes('presentation') || lower.endsWith('.pptx') || lower.endsWith('.ppt')) return 'ppt';
  if (mimeType.startsWith('text/') || /\.(md|markdown|txt)$/.test(lower)) return 'text';
  if (/\.(zip|rar|7z|tar|gz)$/.test(lower) || /zip|rar|tar|compressed/.test(mimeType)) return 'archive';
  return 'other';
}

export function categoryLabel(category: string): string {
  const labels: Record<string, string> = {
    image: '图片', video: '视频', pdf: 'PDF',
    word: 'Word 文档', ppt: 'PPT 演示文稿', text: '文本文件',
    archive: '压缩文件', other: '文件',
  };
  return labels[category] ?? '文件';
}

export function formatBytes(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  if (bytes < 1024 * 1024 * 1024) return `${(bytes / 1024 / 1024).toFixed(1)} MB`;
  return `${(bytes / 1024 / 1024 / 1024).toFixed(2)} GB`;
}
