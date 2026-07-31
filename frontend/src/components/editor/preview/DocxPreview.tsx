import { useEffect, useRef, useState } from 'react';
import { renderAsync } from 'docx-preview';
import { attachmentContentUrl } from '../../../lib/attachments';

export function DocxPreview({ attachmentId }: { attachmentId: number; fileName: string; fileSize: number }): JSX.Element {
  const containerRef = useRef<HTMLDivElement>(null);
  const [error, setError] = useState('');
  useEffect(() => {
    let active = true;
    void attachmentContentUrl(attachmentId).then(async (url) => {
      try {
        const response = await fetch(url);
        if (!response.ok) throw new Error(`HTTP ${response.status}`);
        const blob = await response.blob();
        if (!active || !containerRef.current) return;
        await renderAsync(blob, containerRef.current, undefined, {
          className: 'docx-preview-content',
          inWrapper: true,
          ignoreWidth: false,
          ignoreHeight: false
        });
      } catch (err) {
        if (active) setError(err instanceof Error ? err.message : 'Word 文档渲染失败');
      }
    });
    return () => { active = false; };
  }, [attachmentId]);
  if (error) return <div className="file-preview-error">Word 预览失败：{error}</div>;
  return <div className="file-docx-preview" ref={containerRef} />;
}
