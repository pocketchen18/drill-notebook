import { useEffect, useRef, useState, type KeyboardEvent as ReactKeyboardEvent } from 'react';
import { Button, Input } from '@arco-design/web-react';
import type { RefInputType } from '@arco-design/web-react/es/Input/interface';
import { useEditorState, type Editor } from '@tiptap/react';
import { TextSelection } from '@tiptap/pm/state';
import { SearchQuery, findNext, findPrev, getMatchHighlights, getSearchState, replaceAll, replaceNext, setSearchState } from 'prosemirror-search';
import { CaseSensitive, ChevronDown, ChevronUp, Replace, Search, X } from 'lucide-react';

export interface FindRequest {
  readonly open: boolean;
  readonly replace: boolean;
  /** 打开时用作查询的选中文字；为空则保留上次的查询。 */
  readonly seed: string;
  /** 每次打开请求递增，重复按 Ctrl+F 时重新聚焦输入框。 */
  readonly token: number;
}

export interface EditorFindBarProps {
  editor: Editor;
  request: FindRequest;
  onClose: () => void;
  onToggleReplace: () => void;
}

function clearSearch(editor: Editor): void {
  if (editor.isDestroyed || !getSearchState(editor.state)?.query.search) return;
  editor.view.dispatch(setSearchState(editor.state.tr, new SearchQuery({ search: '' })));
}

/**
 * 页内查找替换，外观与按键对齐知识卡片全屏的查找。
 * 匹配项用 ProseMirror 装饰实现（不直接改动可编辑区的 DOM），当前匹配项即编辑器选区。
 */
export function EditorFindBar({ editor, request, onClose, onToggleReplace }: EditorFindBarProps): JSX.Element | null {
  const [query, setQuery] = useState('');
  const [replacement, setReplacement] = useState('');
  const [caseSensitive, setCaseSensitive] = useState(false);
  const inputRef = useRef<RefInputType>(null);

  const stats = useEditorState({
    editor,
    selector: ({ editor: current }) => {
      const matches = getMatchHighlights(current.state).find();
      const { from, to } = current.state.selection;
      return { total: matches.length, index: matches.findIndex((match) => match.from === from && match.to === to) };
    }
  });

  useEffect(() => {
    if (!request.open) return undefined;
    if (request.seed) setQuery(request.seed);
    const frame = window.requestAnimationFrame(() => {
      inputRef.current?.focus();
      inputRef.current?.dom?.select();
    });
    return () => window.cancelAnimationFrame(frame);
  }, [request.open, request.seed, request.token]);

  // 查询变化时跳到光标处或之后的第一个匹配，与浏览器一致。
  useEffect(() => {
    if (!request.open || editor.isDestroyed) return;
    const { state, view } = editor;
    const searchQuery = new SearchQuery({ search: query, caseSensitive, literal: true, replace: replacement });
    const tr = setSearchState(state.tr, searchQuery);
    if (searchQuery.valid) {
      const match = searchQuery.findNext(state, state.selection.from) ?? searchQuery.findNext(state, 0);
      if (match) tr.setSelection(TextSelection.create(state.doc, match.from, match.to)).scrollIntoView();
    }
    view.dispatch(tr);
    // 替换文本单独同步，输入它时不会移动选区。
  }, [caseSensitive, editor, query, request.open]);

  useEffect(() => {
    if (!request.open || editor.isDestroyed) return;
    editor.view.dispatch(setSearchState(editor.state.tr, new SearchQuery({ search: query, caseSensitive, literal: true, replace: replacement })));
  }, [replacement]);

  useEffect(() => {
    if (!request.open) clearSearch(editor);
  }, [editor, request.open]);

  useEffect(() => () => clearSearch(editor), [editor]);

  if (!request.open) return null;

  const next = (): void => { findNext(editor.state, editor.view.dispatch); };
  const previous = (): void => { findPrev(editor.state, editor.view.dispatch); };
  const replace = (): void => { replaceNext(editor.state, editor.view.dispatch); };
  const replaceEverything = (): void => { replaceAll(editor.state, editor.view.dispatch); };
  const onFindKeyDown = (event: ReactKeyboardEvent<HTMLInputElement>): void => {
    if (event.key === 'Enter') {
      event.preventDefault();
      if (event.shiftKey) previous();
      else next();
    } else if (event.key === 'Escape') {
      event.preventDefault();
      event.stopPropagation();
      onClose();
    }
  };
  const onReplaceKeyDown = (event: ReactKeyboardEvent<HTMLInputElement>): void => {
    if (event.key === 'Enter') {
      event.preventDefault();
      replace();
    } else if (event.key === 'Escape') {
      event.preventDefault();
      event.stopPropagation();
      onClose();
    }
  };

  const hasMatches = stats.total > 0;
  return (
    <div className="editor-findbar" role="search" aria-label="查找与替换">
      <div className="editor-findbar__row">
        <Input
          ref={inputRef}
          className="editor-findbar__input"
          prefix={<Search size={14} />}
          value={query}
          onChange={(value) => setQuery(value)}
          onKeyDown={onFindKeyDown}
          placeholder="查找（Enter 下一个，Shift+Enter 上一个）"
          allowClear
          aria-label="查找"
        />
        <span className={`editor-findbar__count${query && !hasMatches ? ' is-empty' : ''}`} aria-live="polite">
          {query ? (hasMatches ? `${stats.index >= 0 ? stats.index + 1 : 0} / ${stats.total}` : '无匹配') : ''}
        </span>
        <div className="editor-findbar__nav">
          <Button size="mini" type="text" className={caseSensitive ? 'is-active' : undefined} icon={<CaseSensitive size={14} />} onClick={() => setCaseSensitive((value) => !value)} aria-label="区分大小写" aria-pressed={caseSensitive} title="区分大小写" />
          <Button size="mini" type="text" icon={<ChevronUp size={14} />} disabled={!hasMatches} onClick={previous} aria-label="上一个匹配" title="上一个（Shift+Enter）" />
          <Button size="mini" type="text" icon={<ChevronDown size={14} />} disabled={!hasMatches} onClick={next} aria-label="下一个匹配" title="下一个（Enter）" />
          <Button size="mini" type="text" className={request.replace ? 'is-active' : undefined} icon={<Replace size={14} />} onClick={onToggleReplace} aria-label="替换" aria-pressed={request.replace} title="替换" />
          <Button size="mini" type="text" icon={<X size={14} />} onClick={onClose} aria-label="关闭搜索" title="关闭（Esc）" />
        </div>
      </div>
      {request.replace ? (
        <div className="editor-findbar__row">
          <Input className="editor-findbar__input" value={replacement} onChange={(value) => setReplacement(value)} onKeyDown={onReplaceKeyDown} placeholder="替换为" aria-label="替换为" />
          <div className="editor-findbar__nav">
            <Button size="mini" disabled={!hasMatches} onClick={replace}>替换</Button>
            <Button size="mini" disabled={!hasMatches} onClick={replaceEverything}>全部替换</Button>
          </div>
        </div>
      ) : null}
    </div>
  );
}
