import { useEffect, useState } from 'react';
import { Presentation } from 'lucide-react';
import { listPptxSlides, pptxMediaUrl, type PptxSlide } from '../../../lib/attachments';

const RENDERABLE_IMAGE = /\.(png|jpe?g|gif|webp|svg)$/i;

function SlideImage({ attachmentId, path }: { attachmentId: number; path: string }): JSX.Element | null {
  const [src, setSrc] = useState('');
  useEffect(() => {
    let cancelled = false;
    void pptxMediaUrl(attachmentId, path).then((url) => { if (!cancelled) setSrc(url); });
    return () => { cancelled = true; };
  }, [attachmentId, path]);
  if (!src) return null;
  return <img className="pptx-img" src={src} alt="" />;
}

export function PptxPreview({ attachmentId, fileName }: { attachmentId: number; fileName: string; fileSize: number }): JSX.Element {
  const [slides, setSlides] = useState<PptxSlide[] | null>(null);
  const [failed, setFailed] = useState(false);

  useEffect(() => {
    let cancelled = false;
    setSlides(null);
    setFailed(false);
    listPptxSlides(attachmentId)
      .then((list) => { if (!cancelled) setSlides(list); })
      .catch(() => { if (!cancelled) { setFailed(true); setSlides([]); } });
    return () => { cancelled = true; };
  }, [attachmentId]);

  return (
    <div className="file-pptx-preview">
      {slides === null ? (
        <div className="file-preview-loading">读取中…</div>
      ) : failed ? (
        <div className="file-preview-error">无法读取演示文稿内容</div>
      ) : slides.length === 0 ? (
        <div className="file-preview-loading">未找到幻灯片内容</div>
      ) : (
        <>
          <div className="pptx-slides">
            {slides.map((slide, index) => {
              const images = slide.images.filter((path) => RENDERABLE_IMAGE.test(path));
              const empty = slide.paragraphs.length === 0 && images.length === 0;
              return (
                <div className="pptx-slide" key={index}>
                  {empty ? <span className="pptx-slide-empty">（空白页）</span> : (
                    <>
                      {slide.paragraphs.map((text, lineIndex) => (
                        <p className={`pptx-line${lineIndex === 0 ? ' is-first' : ''}`} key={`t${lineIndex}`}>{text}</p>
                      ))}
                      {images.map((path) => (
                        <SlideImage key={path} attachmentId={attachmentId} path={path} />
                      ))}
                    </>
                  )}
                </div>
              );
            })}
          </div>
          <div className="pptx-footer"><Presentation size={16} />{fileName}</div>
        </>
      )}
    </div>
  );
}
