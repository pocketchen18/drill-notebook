import { useEffect, useMemo, useRef, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Button, Checkbox, Empty, Input, Message, Modal, Popconfirm, Select, Space, Spin } from '@arco-design/web-react';
import type { RefInputType } from '@arco-design/web-react/es/Input/interface';
import { CalendarPlus, Edit3, FilePlus2, Plus, Trash2 } from 'lucide-react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { del, flushRequest, get, post, put } from '../lib/api';
import { friendlyMessage } from '../lib/errors';
import type { NotePage, Notebook } from '../lib/types';
import { NotebookEditor } from '../components/editor/NotebookEditor';
import { useUiStore } from '../stores/uiStore';
import { notePageToMarkdown } from '../lib/aiContext';
import { useRegisterPageContext } from '../hooks/useRegisterPageContext';
import { ExportActions } from '../components/ExportActions';
import { noteExportDocument } from '../lib/export';
import { AddToPlanModal } from '../components/AddToPlanModal';
import { CompletePlanButton } from '../components/CompletePlanButton';
import { DayQueueSessionBar, finishDayQueueStep } from '../components/DayQueueSessionBar';
import { truncateTitle } from '../lib/studyPlan';
import { useIdSwitchReset, usePersistSlice } from '../hooks/useViewState';
import { readPageSlice } from '../lib/viewState';
import { matchesAny } from '../lib/shortcuts';

/** 页面标题的两个改名入口：左侧列表行 vs 编辑区标题。任一时刻只有一个在编辑。 */
type PageRenameSurface = 'list' | 'header';

export function NotebookPage(): JSX.Element {
  const navigate = useNavigate();
  const [searchParams] = useSearchParams();
  const planItemId = Number(searchParams.get('planItemId')) || undefined;
  const pageIdFromQuery = Number(searchParams.get('pageId')) || undefined;
  const dayQueueMode = searchParams.get('dayQueue') === '1';
  const pageIdsFromQuery = useMemo(
    () => searchParams.get('pageIds')?.split(',').map(Number).filter(Boolean) ?? [],
    [searchParams]
  );
  const queryClient = useQueryClient();
  const setNotebookFocusMode = useUiStore((state) => state.setNotebookFocusMode);
  const cachedNotebooks = readPageSlice('notebooks');
  const [notebookId, setNotebookId] = useState<number | undefined>(cachedNotebooks.notebookId);
  const [pageId, setPageId] = useState<number | undefined>(pageIdFromQuery ?? cachedNotebooks.pageId);
  const [newPageVisible, setNewPageVisible] = useState(false);
  const [newPageTitle, setNewPageTitle] = useState('');
  const [newNotebookVisible, setNewNotebookVisible] = useState(false);
  const [newNotebookTitle, setNewNotebookTitle] = useState('');
  const [pendingContent, setPendingContent] = useState<Record<string, unknown>>();
  const pendingSaveRef = useRef<{ pageId: number; content: Record<string, unknown> } | null>(null);
  const [selectedPageIds, setSelectedPageIds] = useState<number[]>(() => cachedNotebooks.selectedPageIds ?? []);
  const [planVisible, setPlanVisible] = useState(false);
  const [planItems, setPlanItems] = useState<Array<{ resourceId: number; title: string }>>([]);
  const [focusMode, setFocusMode] = useState<boolean>(() => cachedNotebooks.focusMode ?? false);
  const [renamingPageId, setRenamingPageId] = useState<number | undefined>(undefined);
  const [renameSurface, setRenameSurface] = useState<PageRenameSurface | null>(null);
  const [renameDraft, setRenameDraft] = useState('');
  const [renamingNotebook, setRenamingNotebook] = useState(false);
  const [notebookDraft, setNotebookDraft] = useState('');
  const listRenameInputRef = useRef<RefInputType>(null);
  const headerRenameInputRef = useRef<RefInputType>(null);
  const notebookRenameInputRef = useRef<RefInputType>(null);
  const deepLinkApplied = useRef(false);
  const notebooksQuery = useQuery({ queryKey: ['notebooks'], queryFn: () => get<Notebook[]>('/api/notebooks') });
  const pagesQuery = useQuery({ queryKey: ['note-pages', notebookId], queryFn: () => get<NotePage[]>(`/api/notebooks/${notebookId}/pages`), enabled: notebookId !== undefined });
  const pageQuery = useQuery({ queryKey: ['note-page', pageId], queryFn: () => get<NotePage>(`/api/note-pages/${pageId}`), enabled: pageId !== undefined });
  // When deep-linking with pageId, fetch that page first so we can select its notebook.
  const deepLinkPageQuery = useQuery({
    queryKey: ['note-page-deep-link', pageIdFromQuery],
    queryFn: () => get<NotePage>(`/api/note-pages/${pageIdFromQuery}`),
    enabled: pageIdFromQuery !== undefined
  });

  const refresh = (): void => {
    void queryClient.invalidateQueries({ queryKey: ['notebooks'] });
    void queryClient.invalidateQueries({ queryKey: ['note-pages', notebookId] });
    void queryClient.invalidateQueries({ queryKey: ['note-page', pageId] });
  };

  const createPage = useMutation({
    mutationFn: (title: string) => post<NotePage>(`/api/notebooks/${notebookId}/pages`, { title, content: { type: 'doc', content: [{ type: 'paragraph' }] } }),
    onSuccess: (page) => { setPageId(page.id); setNewPageVisible(false); setNewPageTitle(''); refresh(); Message.success('页面已创建'); },
    onError: (error) => Message.error(friendlyMessage(error, '页面创建失败，请稍后重试'))
  });
  const createNotebook = useMutation({
    mutationFn: (title: string) => post<Notebook>('/api/notebooks', { title }),
    onSuccess: (notebook) => {
      // 先写入缓存再切换，避免「不在列表中的笔记本」被兜底逻辑重置回第一个
      queryClient.setQueryData<Notebook[]>(['notebooks'], (current) => [...(current ?? []), notebook]);
      setNotebookId(notebook.id);
      setPageId(undefined);
      setNewNotebookVisible(false);
      setNewNotebookTitle('');
      void queryClient.invalidateQueries({ queryKey: ['notebooks'] });
      Message.success('笔记本已创建');
    },
    onError: (error) => Message.error(friendlyMessage(error, '笔记本创建失败，请稍后重试'))
  });
  const deletePage = useMutation({
    mutationFn: (id: number) => del<void>(`/api/note-pages/${id}`),
    onSuccess: () => {
      setPageId(undefined);
      cancelPageRename();
      void queryClient.invalidateQueries({ queryKey: ['note-pages', notebookId] });
      Message.success('页面已删除');
    },
    onError: (error) => Message.error(friendlyMessage(error, '页面删除失败，请稍后重试'))
  });

  const renamePage = useMutation({
    mutationFn: ({ id, title }: { id: number; title: string }) => put<NotePage>(`/api/note-pages/${id}`, { title }),
    onSuccess: (page) => {
      cancelPageRename();
      // 直接写缓存：标题在列表、编辑区标题、AI 引用里同时出现，等 refetch 会闪一下旧名。
      queryClient.setQueryData<NotePage[]>(['note-pages', notebookId], (current) =>
        (current ?? []).map((item) => (item.id === page.id ? { ...item, title: page.title } : item))
      );
      queryClient.setQueryData<NotePage>(['note-page', page.id], (current) =>
        current ? { ...current, title: page.title } : current
      );
      void queryClient.invalidateQueries({ queryKey: ['note-pages', notebookId] });
      Message.success('页面标题已更新');
    },
    onError: (error) => Message.error(friendlyMessage(error, '页面重命名失败，请稍后重试'))
  });

  const renameNotebook = useMutation({
    mutationFn: ({ id, title }: { id: number; title: string }) => put<Notebook>(`/api/notebooks/${id}`, { title }),
    onSuccess: (notebook) => {
      cancelNotebookRename();
      queryClient.setQueryData<Notebook[]>(['notebooks'], (current) =>
        (current ?? []).map((item) => (item.id === notebook.id ? { ...item, title: notebook.title } : item))
      );
      void queryClient.invalidateQueries({ queryKey: ['notebooks'] });
      Message.success('笔记本名称已更新');
    },
    onError: (error) => Message.error(friendlyMessage(error, '笔记本重命名失败，请稍后重试'))
  });

  // 只有列表行的改名需要抢焦点：编辑区标题是常驻输入框，用户一打字就已经在里面了，
  // 这时再 select() 会把光标位置和已输入内容一起冲掉。
  useEffect(() => {
    if (renamingPageId === undefined || renameSurface !== 'list') return;
    const timer = window.setTimeout(() => {
      const input = listRenameInputRef.current?.dom;
      if (!input) return;
      input.focus();
      input.select();
    }, 0);
    return () => window.clearTimeout(timer);
  }, [renamingPageId, renameSurface]);

  useEffect(() => {
    if (!renamingNotebook) return;
    const timer = window.setTimeout(() => {
      const input = notebookRenameInputRef.current?.dom;
      if (!input) return;
      input.focus();
      input.select();
    }, 0);
    return () => window.clearTimeout(timer);
  }, [renamingNotebook]);

  // 切走页面时丢弃未提交的编辑区草稿，否则下一页的标题框会显示上一页的半成品。
  useEffect(() => {
    if (renameSurface === 'header' && renamingPageId !== undefined && renamingPageId !== pageId) {
      cancelPageRename();
    }
  }, [pageId, renameSurface, renamingPageId]);

  // 换笔记本时同样收掉笔记本改名框（Select 已被输入框顶掉，留着会指向旧本子）。
  useEffect(() => {
    cancelNotebookRename();
  }, [notebookId]);

  function beginPageRename(page: NotePage, surface: PageRenameSurface, event?: React.MouseEvent | React.KeyboardEvent): void {
    event?.stopPropagation();
    event?.preventDefault();
    if (renamePage.isPending) return;
    // 从列表改名时同时选中该页；编辑区改名保持当前选中不变。
    if (surface === 'list') setPageId(page.id);
    setRenameDraft(page.title);
    setRenamingPageId(page.id);
    setRenameSurface(surface);
  }

  function cancelPageRename(): void {
    setRenamingPageId(undefined);
    setRenameSurface(null);
    setRenameDraft('');
  }

  function commitPageRename(page: NotePage, surface: PageRenameSurface): void {
    if (renamePage.isPending) return;
    // 忽略已经不在编辑中的入口传来的 blur
    if (renamingPageId !== page.id || renameSurface !== surface) return;
    const title = renameDraft.trim();
    if (!title) {
      Message.warning('页面标题不能为空');
      const input = (surface === 'list' ? listRenameInputRef : headerRenameInputRef).current?.dom;
      input?.focus();
      input?.select();
      return;
    }
    if (title === page.title) {
      cancelPageRename();
      return;
    }
    renamePage.mutate({ id: page.id, title });
  }

  function beginNotebookRename(notebook: Notebook, event?: React.MouseEvent): void {
    event?.stopPropagation();
    event?.preventDefault();
    if (renameNotebook.isPending) return;
    setNotebookDraft(notebook.title);
    setRenamingNotebook(true);
  }

  function cancelNotebookRename(): void {
    setRenamingNotebook(false);
    setNotebookDraft('');
  }

  function commitNotebookRename(notebook: Notebook): void {
    if (renameNotebook.isPending || !renamingNotebook) return;
    const title = notebookDraft.trim();
    if (!title) {
      Message.warning('笔记本名称不能为空');
      const input = notebookRenameInputRef.current?.dom;
      input?.focus();
      input?.select();
      return;
    }
    if (title === notebook.title) {
      cancelNotebookRename();
      return;
    }
    renameNotebook.mutate({ id: notebook.id, title });
  }

  // Deep link: select notebook + page from ?pageId=
  useEffect(() => {
    if (deepLinkApplied.current || !pageIdFromQuery || !deepLinkPageQuery.data) return;
    deepLinkApplied.current = true;
    setNotebookId(deepLinkPageQuery.data.notebookId);
    setPageId(deepLinkPageQuery.data.id);
  }, [deepLinkPageQuery.data, pageIdFromQuery]);

  useEffect(() => {
    if (notebookId === undefined && notebooksQuery.data?.length && !pageIdFromQuery) {
      setNotebookId(notebooksQuery.data[0].id);
    }
    if (notebookId === undefined && notebooksQuery.data?.length && pageIdFromQuery && deepLinkApplied.current === false && deepLinkPageQuery.isError) {
      // Fallback if deep-link page fetch failed
      setNotebookId(notebooksQuery.data[0].id);
    }
  }, [deepLinkPageQuery.isError, notebookId, notebooksQuery.data, pageIdFromQuery]);
  useEffect(() => {
    const notebooks = notebooksQuery.data;
    if (!notebooks?.length || notebookId === undefined) return;
    if (!notebooks.some((notebook) => notebook.id === notebookId)) setNotebookId(undefined);
  }, [notebookId, notebooksQuery.data]);
  useIdSwitchReset(notebookId, () => setSelectedPageIds([]));
  useEffect(() => {
    if (!pagesQuery.data) return;
    const available = new Set(pagesQuery.data.map((page) => page.id));
    setSelectedPageIds((ids) => ids.filter((id) => available.has(id)));
  }, [pagesQuery.data]);
  useEffect(() => {
    if (!pagesQuery.data?.length) return;
    if (pageId !== undefined) {
      // Keep deep-linked or user-selected page if it belongs to current notebook
      if (pagesQuery.data.some((page) => page.id === pageId)) return;
      setPageId(undefined);
      return;
    }
    if (pageIdFromQuery && pagesQuery.data.some((page) => page.id === pageIdFromQuery)) {
      setPageId(pageIdFromQuery);
      return;
    }
    if (pageId === undefined) setPageId(pagesQuery.data[0].id);
  }, [pageId, pageIdFromQuery, pagesQuery.data]);
  useEffect(() => {
    if (!pageQuery.data) return;
    setPendingContent(pageQuery.data.content);
  }, [pageQuery.data]);
  // 无感自动保存：内容变更即进入 400ms 防抖保存，无保存状态提示；退出前有 keepalive 兜底冲刷。
  useEffect(() => {
    if (!pageId || !pendingContent || pendingContent === pageQuery.data?.content) return;
    pendingSaveRef.current = { pageId, content: pendingContent };
    const timer = window.setTimeout(() => {
      const payload = pendingSaveRef.current;
      if (!payload) return;
      void put(`/api/note-pages/${payload.pageId}`, { content: payload.content })
        .then(() => { if (pendingSaveRef.current === payload) pendingSaveRef.current = null; })
        .catch((error: unknown) => Message.error(friendlyMessage(error, '笔记保存失败，请稍后重试')));
    }, 400);
    return () => window.clearTimeout(timer);
  }, [pageId, pendingContent, pageQuery.data?.content]);
  useEffect(() => {
    const flush = (): void => {
      const payload = pendingSaveRef.current;
      if (payload) flushRequest(`/api/note-pages/${payload.pageId}`, { content: payload.content });
    };
    const onVisibility = (): void => { if (document.visibilityState === 'hidden') flush(); };
    window.addEventListener('pagehide', flush);
    window.addEventListener('beforeunload', flush);
    document.addEventListener('visibilitychange', onVisibility);
    return () => {
      window.removeEventListener('pagehide', flush);
      window.removeEventListener('beforeunload', flush);
      document.removeEventListener('visibilitychange', onVisibility);
    };
  }, []);

  useEffect(() => {
    setNotebookFocusMode(focusMode);
    return () => setNotebookFocusMode(false);
  }, [focusMode, setNotebookFocusMode]);

  const currentPage = pageQuery.data;
  const selectedNotebook = notebooksQuery.data?.find((notebook) => notebook.id === notebookId);
  const validSelectedPageIds = useMemo(() => {
    const available = new Set((pagesQuery.data ?? []).map((page) => page.id));
    return selectedPageIds.filter((id) => available.has(id));
  }, [pagesQuery.data, selectedPageIds]);
  usePersistSlice('notebooks', { notebookId, pageId, selectedPageIds: validSelectedPageIds, focusMode });

  const openPlanForPages = (pages: NotePage[]): void => {
    if (!pages.length) {
      Message.warning('请先选择要加入计划的笔记页');
      return;
    }
    setPlanItems(
      pages.map((page) => ({
        resourceId: page.id,
        title: truncateTitle(page.title || `笔记 #${page.id}`)
      }))
    );
    setPlanVisible(true);
  };

  const exportPages = async (): Promise<ReturnType<typeof noteExportDocument>> => {
    const pages = await Promise.all(validSelectedPageIds.map((id) => get<NotePage>(`/api/note-pages/${id}`)));
    if (currentPage && pendingContent && validSelectedPageIds.includes(currentPage.id)) {
      const index = pages.findIndex((page) => page.id === currentPage.id);
      if (index >= 0) pages[index] = { ...pages[index], content: pendingContent };
    }
    return noteExportDocument(`${selectedNotebook?.title ?? '笔记本'} · 笔记`, pages);
  };

  const pageContext = useMemo(() => {
    if (!currentPage) {
      return { kind: 'note' as const, title: '笔记本', markdown: '', route: '/notebooks', notebookId, notePageId: pageId };
    }
    const content = pendingContent ?? currentPage.content;
    return {
      kind: 'note' as const,
      title: `笔记 · ${currentPage.title}`,
      markdown: notePageToMarkdown({ ...currentPage, content }),
      route: '/notebooks',
      notebookId: currentPage.notebookId,
      notePageId: currentPage.id
    };
  }, [currentPage, notebookId, pageId, pendingContent]);

  useRegisterPageContext(pageContext);

  const continueDayQueueFromNotes = (): void => {
    if (!dayQueueMode) return;
    finishDayQueueStep(navigate);
  };

  return <main className="page route-workspace route-workspace--notebook">
    {dayQueueMode ? <DayQueueSessionBar /> : null}
    {focusMode ? null : <div className="route-command-row">
      <div className="route-command-row__context">
        <h1 className="route-workspace__sr-only">笔记本</h1>
        <p className="route-workspace__sr-only">所见即所得：公式/图表/Markdown 块默认渲染，点击即可编辑。AI 回复可一键插入本页。</p>
        {renamingNotebook && selectedNotebook ? (
          <Input
            ref={notebookRenameInputRef}
            className="notebook-rename-input"
            value={notebookDraft}
            disabled={renameNotebook.isPending}
            onChange={setNotebookDraft}
            onBlur={() => commitNotebookRename(selectedNotebook)}
            onPressEnter={(event) => { event.preventDefault(); commitNotebookRename(selectedNotebook); }}
            onKeyDown={(event) => { if (event.key === 'Escape') { event.preventDefault(); cancelNotebookRename(); } }}
            aria-label="重命名当前笔记本"
          />
        ) : (
          <Select value={notebookId} placeholder="选择笔记本" onChange={(value) => { cancelPageRename(); setNotebookId(Number(value)); setPageId(undefined); }}>
            {notebooksQuery.data?.map((notebook) => <Select.Option key={notebook.id} value={notebook.id}>{notebook.title}</Select.Option>)}
          </Select>
        )}
        {selectedNotebook && !renamingNotebook ? <Button type="text" icon={<Edit3 size={16} />} onClick={() => beginNotebookRename(selectedNotebook)} aria-label="重命名笔记本" title="重命名笔记本" /> : null}
        <Button icon={<Plus size={16} />} onClick={() => setNewNotebookVisible(true)}>新建笔记本</Button>
      </div>
      <Space className="route-command-row__actions">
        <CompletePlanButton
          planItemId={planItemId}
          resourceType={pageIdFromQuery ? 'note_page' : pageId ? 'note_page' : undefined}
          resourceId={pageIdFromQuery ?? pageId}
        />
        {dayQueueMode ? (
          <Button type="primary" onClick={continueDayQueueFromNotes}>
            {pageIdsFromQuery.length > 1 ? '笔记段完成，继续' : '完成今日任务'}
          </Button>
        ) : null}
        <ExportActions count={validSelectedPageIds.length} document={exportPages} />
      </Space>
    </div>}
    {notebooksQuery.isLoading ? <Spin /> : notebooksQuery.data?.length ? <div className={`route-workspace__body note-layout${focusMode ? ' is-focus' : ''}`}>
      <aside className="local-explorer local-explorer--notebook">
        <div className="local-explorer__header">
          <h2>页面</h2>
          <Space size={4}>
            <Button
              type="text"
              size="small"
              icon={<CalendarPlus size={16} />}
              disabled={!validSelectedPageIds.length}
              onClick={() => {
                const pages = (pagesQuery.data ?? []).filter((page) =>
                  validSelectedPageIds.includes(page.id)
                );
                openPlanForPages(pages);
              }}
            >
              加入计划{validSelectedPageIds.length ? `（${validSelectedPageIds.length}）` : ''}
            </Button>
            <Button type="text" icon={<FilePlus2 size={16} />} onClick={() => setNewPageVisible(true)} aria-label="新建页面" />
          </Space>
        </div>
        <div className="local-explorer__list">
          {pagesQuery.isLoading ? <Spin /> : pagesQuery.data?.length ? <div className="note-list">
            <div className="selection-toolbar"><Checkbox checked={validSelectedPageIds.length === pagesQuery.data.length} indeterminate={validSelectedPageIds.length > 0 && validSelectedPageIds.length < pagesQuery.data.length} onChange={(checked) => setSelectedPageIds(checked ? pagesQuery.data.map((page) => page.id) : [])}>全选页面</Checkbox></div>
            {pagesQuery.data.map((page) => {
              const isListRenaming = renamingPageId === page.id && renameSurface === 'list';
              return <div
                key={page.id}
                className={`dense-content-row note-page-item ${pageId === page.id ? 'selected' : ''} ${validSelectedPageIds.includes(page.id) ? 'is-export-selected' : ''}${isListRenaming ? ' is-renaming' : ''}`}
                onClick={() => { if (!isListRenaming) setPageId(page.id); }}
                role="button"
                tabIndex={0}
                onKeyDown={(event) => {
                  if (isListRenaming) return;
                  if (event.key === 'Enter') setPageId(page.id);
                  // 重命名键可在设置 → 常规 → 快捷键改绑（默认 F2）
                  if (matchesAny(event, useUiStore.getState().shortcutConfig.noteRename)) beginPageRename(page, 'list', event);
                }}
              >
                <span className="selection-line">
                  <Checkbox aria-label={`选择页面：${page.title}`} checked={validSelectedPageIds.includes(page.id)} onClick={(event) => event.stopPropagation()} onChange={(checked) => setSelectedPageIds((ids) => checked ? [...ids, page.id] : ids.filter((id) => id !== page.id))} />
                  {isListRenaming ? (
                    <Input
                      ref={listRenameInputRef}
                      size="small"
                      className="note-rename-input"
                      value={renameDraft}
                      disabled={renamePage.isPending}
                      onClick={(event) => event.stopPropagation()}
                      onChange={setRenameDraft}
                      onBlur={() => commitPageRename(page, 'list')}
                      onPressEnter={(event) => { event.preventDefault(); commitPageRename(page, 'list'); }}
                      onKeyDown={(event) => {
                        event.stopPropagation();
                        if (event.key === 'Escape') { event.preventDefault(); cancelPageRename(); }
                      }}
                      aria-label="重命名页面"
                    />
                  ) : (
                    <span className="note-page-title note-page-title-editable" title="双击重命名" onDoubleClick={(event) => beginPageRename(page, 'list', event)}>{page.title}</span>
                  )}
                </span>
                <Popconfirm title="删除这个页面" content="页面内容和 AI 引用将一并删除，且不可恢复。" onOk={() => deletePage.mutate(page.id)}><Button type="text" status="danger" size="mini" icon={<Trash2 size={14} />} onClick={(event) => event.stopPropagation()} aria-label={`删除${page.title}`} /></Popconfirm>
              </div>;
            })}
          </div> : <div className="empty-state"><div><p>还没有页面</p><Button type="text" onClick={() => setNewPageVisible(true)}>创建第一页</Button></div></div>}
        </div>
      </aside>
      <section className="route-workspace__content">
        {currentPage ? <>
          {focusMode ? null : <div className="editor-canvas__header">
            <Input
              ref={headerRenameInputRef}
              className="editor-canvas__title"
              value={renamingPageId === currentPage.id && renameSurface === 'header' ? renameDraft : currentPage.title}
              disabled={renamePage.isPending}
              onChange={(title) => {
                // 先本地成稿、失焦或回车才提交：原先每敲一个字符发一次 PUT，而 ['note-page', pageId]
                // 从不失效，输入框立刻被服务端旧标题覆盖回去，看起来就是「改不了名」。
                setRenameDraft(title);
                setRenamingPageId(currentPage.id);
                setRenameSurface('header');
              }}
              onBlur={() => commitPageRename(currentPage, 'header')}
              onPressEnter={(event) => { event.preventDefault(); commitPageRename(currentPage, 'header'); }}
              onKeyDown={(event) => { if (event.key === 'Escape') { event.preventDefault(); cancelPageRename(); } }}
              aria-label="重命名当前页面"
            />
            <Button icon={<CalendarPlus size={16} />} onClick={() => openPlanForPages([currentPage])}>加入计划</Button>
          </div>}
          <NotebookEditor content={pendingContent ?? currentPage.content} onChange={setPendingContent} pageId={pageId} focusMode={focusMode} onFocusModeChange={setFocusMode} />
        </> : <div className="panel"><div className="empty-state"><div><p>选择一个页面开始记录。</p></div></div></div>}
      </section>
    </div> : <Empty description="正在创建默认笔记本…" />}
    <Modal title="新建页面" visible={newPageVisible} onCancel={() => setNewPageVisible(false)} onOk={() => { if (!newPageTitle.trim()) { Message.warning('请输入页面标题'); return; } createPage.mutate(newPageTitle.trim()); }} confirmLoading={createPage.isPending} autoFocus={false}>
      <Input autoFocus placeholder="例如：错题总结" value={newPageTitle} onChange={setNewPageTitle} onPressEnter={() => { if (newPageTitle.trim()) createPage.mutate(newPageTitle.trim()); }} />
    </Modal>
    <Modal title="新建笔记本" visible={newNotebookVisible} onCancel={() => setNewNotebookVisible(false)} onOk={() => { if (!newNotebookTitle.trim()) { Message.warning('请输入笔记本标题'); return; } createNotebook.mutate(newNotebookTitle.trim()); }} confirmLoading={createNotebook.isPending} autoFocus={false}>
      <Input autoFocus placeholder="例如：高等数学" value={newNotebookTitle} onChange={setNewNotebookTitle} onPressEnter={() => { if (newNotebookTitle.trim()) createNotebook.mutate(newNotebookTitle.trim()); }} />
    </Modal>
    <AddToPlanModal
      visible={planVisible}
      onClose={() => setPlanVisible(false)}
      resourceType="note_page"
      items={planItems}
      defaultTitle="笔记计划"
    />
  </main>;
}
