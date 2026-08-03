import { useEffect, useMemo, useState } from 'react';
import { createPortal } from 'react-dom';
import { X, Download, Folder, File as FileIcon, FileCode, FileText, ChevronRight, FileArchive } from 'lucide-react';
import { listArchiveEntries, attachmentContentUrl, type ArchiveEntry } from '../../../lib/attachments';
import { formatBytes } from './FileInfoPreview';

interface TreeNode {
  name: string;
  path: string;
  dir: boolean;
  size: number;
  children: Map<string, TreeNode>;
}

function newNode(name: string, path: string, dir: boolean): TreeNode {
  return { name, path, dir, size: 0, children: new Map() };
}

function buildTree(entries: ArchiveEntry[]): TreeNode {
  const root = newNode('', '', true);
  for (const entry of entries) {
    const parts = entry.name.split('/').filter(Boolean);
    if (parts.length === 0) continue;
    let cursor = root;
    let path = '';
    parts.forEach((segment, index) => {
      const isLast = index === parts.length - 1;
      path += segment + (entry.dir || !isLast ? '/' : '');
      let child = cursor.children.get(segment);
      if (!child) {
        child = newNode(segment, path, entry.dir || !isLast);
        cursor.children.set(segment, child);
      }
      if (isLast) {
        child.dir = entry.dir;
        child.size = entry.size;
      }
      cursor = child;
    });
  }
  return root;
}

function EntryIcon({ name }: { name: string }): JSX.Element {
  const lower = name.toLowerCase();
  if (/\.(zip|rar|7z|tar|gz)$/.test(lower)) return <FileArchive size={20} className="archive-row-icon is-file" />;
  if (/\.(json|py|js|ts|tsx|jsx|java|c|cpp|h|cs|go|rs|sh|yml|yaml|xml|html|css|vue)$/.test(lower)) {
    return <FileCode size={20} className="archive-row-icon is-file" />;
  }
  if (/\.(md|markdown|txt)$/.test(lower)) return <FileText size={20} className="archive-row-icon is-file" />;
  return <FileIcon size={20} className="archive-row-icon is-file" />;
}

export function ArchiveBrowser({ attachmentId, fileName, fileSize, onClose }: {
  attachmentId: number;
  fileName: string;
  fileSize: number;
  onClose: () => void;
}): JSX.Element {
  const [entries, setEntries] = useState<ArchiveEntry[] | null>(null);
  const [failed, setFailed] = useState(false);
  const [currentPath, setCurrentPath] = useState<string[]>([]);
  const [href, setHref] = useState('');

  useEffect(() => {
    let cancelled = false;
    listArchiveEntries(attachmentId)
      .then((list) => { if (!cancelled) setEntries(list); })
      .catch(() => { if (!cancelled) { setFailed(true); setEntries([]); } });
    void attachmentContentUrl(attachmentId).then((url) => { if (!cancelled) setHref(url); });
    return () => { cancelled = true; };
  }, [attachmentId]);

  const tree = useMemo(() => buildTree(entries ?? []), [entries]);

  const current = useMemo(() => {
    let node = tree;
    for (const segment of currentPath) {
      const next = node.children.get(segment);
      if (!next) break;
      node = next;
    }
    return node;
  }, [tree, currentPath]);

  const rows = useMemo(() => {
    const list = Array.from(current.children.values());
    return list.sort((a, b) => {
      if (a.dir !== b.dir) return a.dir ? -1 : 1;
      return a.name.localeCompare(b.name, 'zh');
    });
  }, [current]);

  return createPortal(
    <div className="archive-browser" role="dialog" aria-modal="true">
      <div className="archive-browser-bar">
        <button type="button" className="archive-browser-exit" onClick={onClose}>
          <X size={16} /> 退出
        </button>
        <div className="archive-browser-file">
          <span className="archive-browser-fileicon"><FileArchive size={22} /></span>
          <span className="archive-browser-meta">
            <span className="archive-browser-name">{fileName}</span>
            <span className="archive-browser-size">{formatBytes(fileSize)}</span>
          </span>
        </div>
        <a className="archive-browser-download" href={href} download={fileName}>
          <Download size={15} /> 下载
        </a>
      </div>

      <div className="archive-browser-body">
        <div className="archive-browser-panel">
          <div className="archive-browser-crumb">
            <button type="button" className="archive-crumb-item" onClick={() => setCurrentPath([])}>{fileName}</button>
            {currentPath.map((segment, index) => (
              <span className="archive-crumb-seg" key={segment}>
                <ChevronRight size={14} />
                <button type="button" className="archive-crumb-item" onClick={() => setCurrentPath(currentPath.slice(0, index + 1))}>{segment}</button>
              </span>
            ))}
          </div>

          {entries === null ? (
            <div className="file-preview-loading">读取中…</div>
          ) : failed ? (
            <div className="file-preview-error">无法读取压缩包内容</div>
          ) : (
            <div className="archive-browser-table">
              <div className="archive-row archive-row-head">
                <span className="archive-row-name">名称</span>
                <span className="archive-row-size">大小</span>
              </div>
              {rows.length === 0 ? (
                <div className="file-preview-loading">空文件夹</div>
              ) : (
                rows.map((row) => (
                  row.dir ? (
                    <button
                      type="button"
                      className="archive-row archive-row-dir"
                      key={row.path}
                      onClick={() => setCurrentPath([...currentPath, row.name])}
                    >
                      <span className="archive-row-name"><Folder size={20} className="archive-row-icon is-dir" />{row.name}</span>
                      <span className="archive-row-size" />
                    </button>
                  ) : (
                    <div className="archive-row" key={row.path}>
                      <span className="archive-row-name"><EntryIcon name={row.name} />{row.name}</span>
                      <span className="archive-row-size">{formatBytes(row.size)}</span>
                    </div>
                  )
                ))
              )}
            </div>
          )}
        </div>
      </div>
    </div>,
    document.body
  );
}
