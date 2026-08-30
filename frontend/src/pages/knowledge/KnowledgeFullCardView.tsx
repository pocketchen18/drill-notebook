import { useEffect, useRef, useState, useCallback } from 'react';
import { Button, Input, Message, Popconfirm, Tooltip } from '@arco-design/web-react';
import { ArrowLeft, ChevronDown, ChevronUp, Edit3, PanelLeftClose, PanelLeftOpen, Plus, RotateCcw, Search, Trash2, X } from 'lucide-react';
import { MarkdownContent } from '../../components/markdown/MarkdownRenderer';
import { buildFullMarkdown, ROOT_ID } from '../../lib/knowledgeTree';
import type { KnowledgeTree, KnowledgeTreeNode } from '../../lib/knowledgeTree';
import {
  summarizePoint,
  resummarizePoint,
  restoreOriginal,
  restoreSummary,
  summarizeBank,
  resummarizeBank,
  restoreOriginalBank,
  restoreSummaryBank,
} from '../../lib/knowledgeApi';
import { friendlyMessage } from '../../lib/errors';
import type { KnowledgePoint, Question } from '../../lib/types';
import { KnowledgeTreeNav } from './KnowledgeTreeNav';

export interface KnowledgeFullCardViewProps {
  tree: KnowledgeTree;
  node: KnowledgeTreeNode;
  questions: Question[];
  bankId?: number;
  onNavigate: (id: number) => void;
  onClose: () => void;
  onDeleted: () => void;
  onModified: () => void;
  onEdit: (point: KnowledgePoint) => void;
  onAddChild?: (parentPoint: KnowledgePoint) => void;
}

const MIN_SIDEBAR_WIDTH = 180;
const DEFAULT_SIDEBAR_WIDTH = 280;
const MAX_SIDEBAR_WIDTH = 600;
const COLLAPSE_THRESHOLD = 120; // 拖拽宽度小于 120px 时直接折叠大纲（类似 VSCode 体验）

export function KnowledgeFullCardView({ tree, node, questions, bankId, onNavigate, onClose, onDeleted, onModified, onEdit, onAddChild }: KnowledgeFullCardViewProps): JSX.Element {
  const isRoot = node.id === ROOT_ID;
  const rootHasSummary = tree.flatList.some((n) => n.hasOriginal);

  // 收集节点及其子孙节点中的有效知识点
  const collectSubtreePoints = (targetNode: KnowledgeTreeNode): KnowledgeTreeNode[] => {
    const list: KnowledgeTreeNode[] = [];
    const walk = (n: KnowledgeTreeNode) => {
      if (n.id !== ROOT_ID) list.push(n);
      n.children.forEach(walk);
    };
    walk(targetNode);
    return list;
  };

  const [sidebarOpen, setSidebarOpen] = useState(true);
  const [sidebarWidth, setSidebarWidth] = useState(DEFAULT_SIDEBAR_WIDTH);
  const [isResizing, setIsResizing] = useState(false);
  const [loading, setLoading] = useState(false);
  const [current, setCurrent] = useState<KnowledgeTreeNode>(node);
  const [searchVisible, setSearchVisible] = useState(false);
  const [searchQuery, setSearchQuery] = useState('');
  const [currentMatchIndex, setCurrentMatchIndex] = useState(0);
  const [matchCount, setMatchCount] = useState(0);

  const searchInputRef = useRef<any>(null);
  const bodyContentRef = useRef<HTMLDivElement>(null);

  const subPoints = collectSubtreePoints(current);
  const nodeHasSummary = isRoot
    ? rootHasSummary
    : (current.children.length === 0 ? Boolean(current.hasOriginal) : subPoints.some((p) => p.hasOriginal));

  const [view, setView] = useState<'original' | 'summary'>(nodeHasSummary ? 'summary' : 'original');
  const hasOriginal = nodeHasSummary;
  const isLeaf = !isRoot && current.children.length === 0;
  const index = tree.flatList.findIndex((n) => n.id === current.id);
  const total = tree.flatList.length;
  const full = buildFullMarkdown(current);

  const prevNodeIdRef = useRef<number | null>(null);
  const containerRef = useRef<HTMLDivElement>(null);
  const isResizingRef = useRef(false);
  const startXRef = useRef(0);
  const startWidthRef = useRef(DEFAULT_SIDEBAR_WIDTH);

  const sidebarWidthRef = useRef(sidebarWidth);
  sidebarWidthRef.current = sidebarWidth;

  const startResizing = (e: React.MouseEvent) => {
    e.preventDefault();
    isResizingRef.current = true;
    startXRef.current = e.clientX;
    startWidthRef.current = sidebarWidthRef.current;
    setIsResizing(true);
  };

  useEffect(() => {
    const handleMouseMove = (e: MouseEvent) => {
      if (!isResizingRef.current) return;
      const deltaX = e.clientX - startXRef.current;
      const nextWidth = startWidthRef.current + deltaX;

      if (nextWidth < COLLAPSE_THRESHOLD) {
        // 小于阈值直接折叠
        setSidebarOpen(false);
        setSidebarWidth(DEFAULT_SIDEBAR_WIDTH);
        isResizingRef.current = false;
        setIsResizing(false);
      } else {
        // 限制在 [MIN_SIDEBAR_WIDTH, MAX_SIDEBAR_WIDTH] 之间
        const clamped = Math.min(Math.max(nextWidth, MIN_SIDEBAR_WIDTH), MAX_SIDEBAR_WIDTH);
        setSidebarWidth(clamped);
      }
    };

    const handleMouseUp = () => {
      if (isResizingRef.current) {
        isResizingRef.current = false;
        setIsResizing(false);
      }
    };

    window.addEventListener('mousemove', handleMouseMove);
    window.addEventListener('mouseup', handleMouseUp);
    return () => {
      window.removeEventListener('mousemove', handleMouseMove);
      window.removeEventListener('mouseup', handleMouseUp);
    };
  }, []);

  useEffect(() => {
    const idChanged = prevNodeIdRef.current !== node.id;
    prevNodeIdRef.current = node.id;
    setCurrent(node);
    if (idChanged) {
      const isTargetRoot = node.id === ROOT_ID;
      const targetSub = collectSubtreePoints(node);
      const targetHasSummary = isTargetRoot
        ? tree.flatList.some((n) => n.hasOriginal)
        : (node.children.length === 0 ? Boolean(node.hasOriginal) : targetSub.some((p) => p.hasOriginal));
      setView(targetHasSummary ? 'summary' : 'original');
    }
  }, [node, tree]);

  // 高亮正文中的关键字并定位
  const highlightMatches = useCallback(() => {
    if (!bodyContentRef.current) {
      setMatchCount(0);
      setCurrentMatchIndex(0);
      return;
    }
    const container = bodyContentRef.current;

    // 清理旧的高亮
    const oldMarks = container.querySelectorAll('mark.kp-search-highlight');
    oldMarks.forEach((mark) => {
      const parent = mark.parentNode;
      if (parent) {
        parent.replaceChild(document.createTextNode(mark.textContent ?? ''), mark);
        parent.normalize();
      }
    });

    const query = searchQuery.trim();
    if (!query) {
      setMatchCount(0);
      setCurrentMatchIndex(0);
      return;
    }

    const walker = document.createTreeWalker(container, NodeFilter.SHOW_TEXT, null);
    const textNodes: Text[] = [];
    let textNode: Text | null;
    while ((textNode = walker.nextNode() as Text | null)) {
      if (textNode.nodeValue && textNode.nodeValue.toLowerCase().includes(query.toLowerCase())) {
        // 避开 script/style 等
        const parent = textNode.parentElement;
        if (parent && !['SCRIPT', 'STYLE', 'BUTTON', 'INPUT'].includes(parent.tagName)) {
          textNodes.push(textNode);
        }
      }
    }

    const createdMarks: HTMLElement[] = [];
    const lowerQuery = query.toLowerCase();
    textNodes.forEach((node) => {
      let currentTextNode: Text | null = node;
      while (currentTextNode) {
        const val = currentTextNode.nodeValue ?? '';
        const idx = val.toLowerCase().indexOf(lowerQuery);
        if (idx === -1) break;

        const matchNode = currentTextNode.splitText(idx);
        const remainderNode = matchNode.splitText(query.length);
        const mark = document.createElement('mark');
        mark.className = 'kp-search-highlight';
        mark.textContent = matchNode.nodeValue;
        matchNode.parentNode?.replaceChild(mark, matchNode);
        createdMarks.push(mark);

        currentTextNode = remainderNode;
      }
    });

    setMatchCount(createdMarks.length);
    if (createdMarks.length > 0) {
      const targetIdx = Math.min(Math.max(currentMatchIndex, 0), createdMarks.length - 1);
      setCurrentMatchIndex(targetIdx);
      createdMarks[targetIdx].classList.add('kp-search-highlight-active');
      createdMarks[targetIdx].scrollIntoView?.({ block: 'nearest', behavior: 'smooth' });
    } else {
      setCurrentMatchIndex(0);
    }
  }, [searchQuery, currentMatchIndex]);

  useEffect(() => {
    highlightMatches();
  }, [searchQuery, full, highlightMatches]);

  const jumpToMatch = (newIdx: number) => {
    if (!bodyContentRef.current || matchCount === 0) return;
    const marks = bodyContentRef.current.querySelectorAll('mark.kp-search-highlight');
    if (!marks.length) return;

    let targetIdx = newIdx;
    if (targetIdx < 0) targetIdx = marks.length - 1;
    if (targetIdx >= marks.length) targetIdx = 0;

    marks.forEach((m, idx) => {
      if (idx === targetIdx) {
        m.classList.add('kp-search-highlight-active');
        m.scrollIntoView?.({ block: 'nearest', behavior: 'smooth' });
      } else {
        m.classList.remove('kp-search-highlight-active');
      }
    });
    setCurrentMatchIndex(targetIdx);
  };

  const openSearch = useCallback(() => {
    setSearchVisible(true);
    setSidebarOpen(true);
    setTimeout(() => {
      searchInputRef.current?.focus?.();
    }, 50);
  }, []);

  const closeSearch = useCallback(() => {
    setSearchVisible(false);
    setSearchQuery('');
    setMatchCount(0);
    setCurrentMatchIndex(0);
  }, []);

  useEffect(() => {
    const onKey = (e: KeyboardEvent): void => {
      // Ctrl+F / Cmd+F 开启搜索
      if ((e.ctrlKey || e.metaKey) && (e.key === 'f' || e.key === 'F')) {
        e.preventDefault();
        e.stopPropagation();
        openSearch();
        return;
      }

      // 如果搜索框打开且按下 Enter，支持正文匹配跳转
      if (searchVisible && e.key === 'Enter') {
        const isFocusedInSearch = document.activeElement?.tagName === 'INPUT' && document.activeElement.getAttribute('aria-label') === '搜索大纲与正文';
        if (isFocusedInSearch || matchCount > 0) {
          e.preventDefault();
          if (e.shiftKey) {
            jumpToMatch(currentMatchIndex - 1);
          } else {
            jumpToMatch(currentMatchIndex + 1);
          }
          return;
        }
      }

      if (e.key === 'Escape') {
        if (searchVisible) {
          e.preventDefault();
          e.stopPropagation();
          closeSearch();
        } else {
          onClose();
        }
      }
      else if (!searchVisible && e.key === 'ArrowLeft') { const p = tree.flatList[index - 1]; if (p) onNavigate(p.id); }
      else if (!searchVisible && e.key === 'ArrowRight') { const n = tree.flatList[index + 1]; if (n) onNavigate(n.id); }
      else if (!searchVisible && (e.key === 't' || e.key === 'T')) setSidebarOpen((v) => !v);
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [onClose, onNavigate, tree, index, openSearch, closeSearch, searchVisible, matchCount, currentMatchIndex]);

  const nodeAsPoint = (n: KnowledgeTreeNode): KnowledgePoint => ({
    id: n.id, title: n.title, content: n.content, category: n.category,
    tags: n.tags, questionIds: n.questionIds, headingPath: n.headingPath, hasOriginal: n.hasOriginal,
  });

  const handleToggle = async (): Promise<void> => {
    setLoading(true);
    try {
      if (isRoot) {
        if (!bankId) return;
        if (view === 'summary') {
          await restoreOriginalBank(bankId);
          setView('original');
          onModified();
        } else if (hasOriginal) {
          await restoreSummaryBank(bankId);
          setView('summary');
          onModified();
        } else {
          const result = await summarizeBank(bankId);
          if (result.summarized > 0) {
            await restoreSummaryBank(bankId);
            setView('summary');
            onModified();
          } else {
            const msg = result.errors?.[0] ? `：${result.errors[0]}` : '';
            Message.error(`总结全文失败${msg}`);
          }
        }
      } else if (current.children.length === 0) {
        if (view === 'summary') {
          const result = await restoreOriginal(current.id);
          setCurrent((p) => ({ ...p, content: result.content }));
          setView('original');
          onModified();
        } else if (hasOriginal) {
          try {
            const result = await restoreSummary(current.id);
            setCurrent((p) => ({ ...p, content: result.content }));
            setView('summary');
            onModified();
          } catch {
            // 如果数据库缺失 summary 记录，自愈自动发起即时总结
            const result = await summarizePoint(current.id);
            if (result.summarized > 0) {
              const restored = await restoreSummary(current.id);
              setCurrent((p) => ({ ...p, content: restored.content, hasOriginal: true }));
              setView('summary');
              onModified();
            } else {
              const msg = result.errors?.[0] ? `：${result.errors[0]}` : '';
              Message.error(`总结失败${msg}`);
            }
          }
        } else {
          const result = await summarizePoint(current.id);
          if (result.summarized > 0) {
            const restored = await restoreSummary(current.id);
            setCurrent((p) => ({ ...p, content: restored.content, hasOriginal: true }));
            setView('summary');
            onModified();
          } else {
            const msg = result.errors?.[0] ? `：${result.errors[0]}` : '';
            Message.error(`总结失败${msg}`);
          }
        }
      } else {
        // 中间章节/目录分支节点：批量总结或还原该分支下的所有子知识点
        if (view === 'summary') {
          for (const p of subPoints) {
            if (p.hasOriginal) {
              try { await restoreOriginal(p.id); } catch { /* ignore */ }
            }
          }
          setView('original');
          onModified();
        } else if (hasOriginal) {
          for (const p of subPoints) {
            if (p.hasOriginal) {
              try { await restoreSummary(p.id); } catch { /* ignore */ }
            }
          }
          setView('summary');
          onModified();
        } else {
          for (const p of subPoints) {
            if (!p.hasOriginal) {
              try {
                const result = await summarizePoint(p.id);
                if (result.summarized > 0) await restoreSummary(p.id);
              } catch { /* ignore */ }
            }
          }
          setView('summary');
          onModified();
        }
      }
    } catch (error) {
      Message.error(friendlyMessage(error, '切换总结显示失败'));
    } finally {
      setLoading(false);
    }
  };

  const handleResummarize = async (): Promise<void> => {
    if (!hasOriginal) return;
    setLoading(true);
    try {
      if (isRoot) {
        if (!bankId) return;
        const result = await resummarizeBank(bankId);
        if (result.summarized > 0) {
          await restoreSummaryBank(bankId);
          setView('summary');
          onModified();
        } else {
          const msg = result.errors?.[0] ? `：${result.errors[0]}` : '';
          Message.error(`重新总结全文失败${msg}`);
        }
      } else if (current.children.length === 0) {
        const result = await resummarizePoint(current.id);
        if (result.summarized > 0) {
          const restored = await restoreSummary(current.id);
          setCurrent((p) => ({ ...p, content: restored.content }));
          setView('summary');
          onModified();
        }
      } else {
        for (const p of subPoints) {
          if (p.hasOriginal) {
            try {
              const res = await resummarizePoint(p.id);
              if (res.summarized > 0) await restoreSummary(p.id);
            } catch { /* ignore */ }
          }
        }
        setView('summary');
        onModified();
      }
    } catch (error) {
      Message.error(friendlyMessage(error, '重新总结失败'));
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className={`knowledge-full-card ${isResizing ? 'is-resizing' : ''}`} role="dialog" aria-modal="true" aria-label={current.title} ref={containerRef}>
      <div className="knowledge-full-card-toolbar">
        <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
          <Button type="text" icon={<ArrowLeft size={18} />} onClick={onClose}>返回</Button>
          <Tooltip content={sidebarOpen ? '折叠大纲 (T)' : '展开大纲 (T)'}>
            <Button
              type="text"
              icon={sidebarOpen ? <PanelLeftClose size={18} /> : <PanelLeftOpen size={18} />}
              onClick={() => setSidebarOpen((v) => !v)}
              aria-label={sidebarOpen ? '折叠大纲' : '展开大纲'}
            >
              {sidebarOpen ? '折叠大纲' : '大纲'}
            </Button>
          </Tooltip>
        </div>
        <span className="knowledge-full-card-progress">
          {isRoot ? '总览全文' : `第 ${index + 1} / ${total} 个知识点`}
        </span>
        <div className="knowledge-full-card-actions">
          <Tooltip content="搜索大纲与正文 (Ctrl+F)">
            <Button
              type={searchVisible ? 'primary' : 'default'}
              icon={<Search size={14} />}
              onClick={() => {
                if (searchVisible) {
                  closeSearch();
                } else {
                  openSearch();
                }
              }}
              aria-label="搜索"
            >
              查找
            </Button>
          </Tooltip>
          <>
            <Button loading={loading} onClick={() => void handleToggle()}>
              {isRoot ? (view === 'original' ? '总结全文' : '还原全文') : (view === 'original' ? '总结' : '还原')}
            </Button>
            <Tooltip content={hasOriginal ? '' : (isRoot ? '当前知识库还未总结，请先点击"总结全文"' : '当前知识卡片还未总结，请先点击"总结"')}>
              <Button loading={loading} icon={<RotateCcw size={14} />} disabled={!hasOriginal} onClick={() => void handleResummarize()}>
                {isRoot ? '重新总结全文' : '重新总结'}
              </Button>
            </Tooltip>
          </>
          <Button icon={<Plus size={14} />} onClick={() => onAddChild?.(nodeAsPoint(current))}>
            {isRoot ? '新建根知识点' : '添加子知识点'}
          </Button>
          {!isRoot && (
            <>
              <Button icon={<Edit3 size={14} />} onClick={() => onEdit(nodeAsPoint(current))}>修改</Button>
              <Popconfirm title="删除这个知识点？" onOk={onDeleted}>
                <Button status="danger" icon={<Trash2 size={14} />}>删除</Button>
              </Popconfirm>
            </>
          )}
          <Button type="text" icon={<X size={18} />} onClick={onClose} aria-label="关闭" />
        </div>
      </div>

      {searchVisible && (
        <div className="knowledge-full-card-searchbar">
          <div className="searchbar-inner">
            <Input
              ref={searchInputRef}
              prefix={<Search size={14} />}
              placeholder="搜索大纲与正文 (Enter 下一个，Shift+Enter 上一个，Esc 退出)..."
              value={searchQuery}
              onChange={(val) => {
                setSearchQuery(val);
                setCurrentMatchIndex(0);
              }}
              allowClear
              aria-label="搜索大纲与正文"
            />
            <div className="searchbar-info">
              {searchQuery ? (
                matchCount > 0 ? (
                  <span>正文 {currentMatchIndex + 1} / {matchCount}</span>
                ) : (
                  <span className="searchbar-no-match">正文无匹配</span>
                )
              ) : null}
            </div>
            <div className="searchbar-nav">
              <Button
                size="mini"
                type="text"
                icon={<ChevronUp size={14} />}
                disabled={matchCount === 0}
                onClick={() => jumpToMatch(currentMatchIndex - 1)}
                aria-label="上一个匹配"
              />
              <Button
                size="mini"
                type="text"
                icon={<ChevronDown size={14} />}
                disabled={matchCount === 0}
                onClick={() => jumpToMatch(currentMatchIndex + 1)}
                aria-label="下一个匹配"
              />
              <Button
                size="mini"
                type="text"
                icon={<X size={14} />}
                onClick={closeSearch}
                aria-label="关闭搜索"
              />
            </div>
          </div>
        </div>
      )}

      <div className={`knowledge-full-card-main ${sidebarOpen ? 'with-sidebar' : 'without-sidebar'}`}>
        {sidebarOpen && (
          <div
            className="knowledge-full-card-sidebar-wrapper"
            style={{ width: sidebarWidth, flex: `0 0 ${sidebarWidth}px` }}
          >
            <div className="knowledge-full-card-sidebar">
              <KnowledgeTreeNav
                tree={tree}
                activeId={current.id}
                search={searchQuery}
                activeTag={null}
                onSelect={(id) => onNavigate(id)}
              />
            </div>
            <div
              className="knowledge-full-card-resizer"
              onMouseDown={startResizing}
              role="separator"
              aria-orientation="vertical"
              aria-label="拖拽调节大纲宽度"
            />
          </div>
        )}
        <div className="knowledge-full-card-body" ref={bodyContentRef}>
          <h2 className="knowledge-full-card-title">{current.title}</h2>
          <div className="knowledge-full-card-content">
            <MarkdownContent value={full} />
          </div>
          {current.questionIds.length > 0 && (
            <div className="knowledge-full-card-links">
              <strong>关联题目</strong>
              {current.questionIds.map((id) => (
                <div key={id}>{questions.find((q) => q.id === id)?.stem ?? `题目 #${id}`}</div>
              ))}
            </div>
          )}
        </div>
      </div>
    </div>
  );
}
