import { useEffect, useMemo, useRef, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Button, Empty, Input, Message, Modal, Select, Space, Spin, Typography } from '@arco-design/web-react';
import { FilePlus2, FolderPlus, Save, Sparkles } from 'lucide-react';
import { get, post, put } from '../lib/api';
import type { NotePage, Notebook } from '../lib/types';
import { NotebookEditor } from '../components/editor/NotebookEditor';
import { useUiStore } from '../stores/uiStore';
import { notePageToMarkdown } from '../lib/aiContext';
import { useRegisterPageContext } from '../hooks/useRegisterPageContext';

export function NotebookPage(): JSX.Element {
  const queryClient = useQueryClient();
  const setAiOpen = useUiStore((state) => state.setAiOpen);
  const [notebookId, setNotebookId] = useState<number>();
  const [pageId, setPageId] = useState<number>();
  const [newPageVisible, setNewPageVisible] = useState(false);
  const [newPageTitle, setNewPageTitle] = useState('');
  const [pendingContent, setPendingContent] = useState<Record<string, unknown>>();
  const [saveState, setSaveState] = useState<'saved' | 'pending' | 'saving'>('saved');
  const bootstrapped = useRef(false);
  const notebooksQuery = useQuery({ queryKey: ['notebooks'], queryFn: () => get<Notebook[]>('/api/notebooks') });
  const pagesQuery = useQuery({ queryKey: ['note-pages', notebookId], queryFn: () => get<NotePage[]>(`/api/notebooks/${notebookId}/pages`), enabled: notebookId !== undefined });
  const pageQuery = useQuery({ queryKey: ['note-page', pageId], queryFn: () => get<NotePage>(`/api/note-pages/${pageId}`), enabled: pageId !== undefined });

  const refresh = (): void => {
    void queryClient.invalidateQueries({ queryKey: ['notebooks'] });
    void queryClient.invalidateQueries({ queryKey: ['note-pages', notebookId] });
    void queryClient.invalidateQueries({ queryKey: ['note-page', pageId] });
  };

  const createNotebook = useMutation({
    mutationFn: (title: string) => post<Notebook>('/api/notebooks', { title }),
    onSuccess: (notebook) => { setNotebookId(notebook.id); refresh(); Message.success('笔记本已创建'); },
    onError: (error) => Message.error(error.message)
  });
  const createPage = useMutation({
    mutationFn: (title: string) => post<NotePage>(`/api/notebooks/${notebookId}/pages`, { title, content: { type: 'doc', content: [{ type: 'paragraph' }] } }),
    onSuccess: (page) => { setPageId(page.id); setNewPageVisible(false); setNewPageTitle(''); refresh(); Message.success('页面已创建'); },
    onError: (error) => Message.error(error.message)
  });

  useEffect(() => {
    if (notebooksQuery.isSuccess && !notebooksQuery.data.length && !bootstrapped.current) {
      bootstrapped.current = true;
      createNotebook.mutate('默认笔记本');
    }
  }, [createNotebook, notebooksQuery.data, notebooksQuery.isSuccess]);
  useEffect(() => {
    if (notebookId === undefined && notebooksQuery.data?.length) setNotebookId(notebooksQuery.data[0].id);
  }, [notebookId, notebooksQuery.data]);
  useEffect(() => {
    if (pageId === undefined && pagesQuery.data?.length) setPageId(pagesQuery.data[0].id);
  }, [pageId, pagesQuery.data]);
  useEffect(() => {
    if (!pageQuery.data) return;
    setPendingContent(pageQuery.data.content);
    setSaveState('saved');
  }, [pageQuery.data]);
  useEffect(() => {
    if (!pageId || !pendingContent || pendingContent === pageQuery.data?.content) return;
    setSaveState('pending');
    const timer = window.setTimeout(() => {
      setSaveState('saving');
      void put(`/api/note-pages/${pageId}`, { content: pendingContent }).then(() => setSaveState('saved')).catch((error: unknown) => {
        setSaveState('pending');
        Message.error(error instanceof Error ? error.message : '保存失败');
      });
    }, 1000);
    return () => window.clearTimeout(timer);
  }, [pageId, pendingContent, pageQuery.data?.content]);

  const currentPage = pageQuery.data;

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

  return <main className="page">
    <div className="page-heading">
      <div><h1>笔记本</h1><p>所见即所得：公式/图表/Markdown 块默认渲染，点击即可编辑。AI 回复可一键插入本页。</p></div>
      <Space>
        <Button icon={<Sparkles size={16} />} onClick={() => setAiOpen(true)}>AI 助手</Button>
        <Select value={notebookId} placeholder="选择笔记本" onChange={(value) => { setNotebookId(Number(value)); setPageId(undefined); }}>
          {notebooksQuery.data?.map((notebook) => <Select.Option key={notebook.id} value={notebook.id}>{notebook.title}</Select.Option>)}
        </Select>
        <Button icon={<FolderPlus size={16} />} onClick={() => { const title = window.prompt('笔记本名称', '新笔记本'); if (title?.trim()) createNotebook.mutate(title.trim()); }}>新建笔记本</Button>
      </Space>
    </div>
    {notebooksQuery.isLoading ? <Spin /> : notebooksQuery.data?.length ? <div className="note-layout">
      <section className="panel">
        <div className="panel-header"><h2>页面</h2><Button type="text" icon={<FilePlus2 size={16} />} onClick={() => setNewPageVisible(true)} aria-label="新建页面" /></div>
        <div className="panel-body">
          {pagesQuery.isLoading ? <Spin /> : pagesQuery.data?.length ? <div className="note-list">{pagesQuery.data.map((page) => <div key={page.id} className={`note-page-item ${pageId === page.id ? 'selected' : ''}`} onClick={() => setPageId(page.id)} role="button" tabIndex={0} onKeyDown={(event) => { if (event.key === 'Enter') setPageId(page.id); }}><span>{page.title}</span>{pageId === page.id && <Save size={14} className="muted" />}</div>)}</div> : <div className="empty-state"><div><p>还没有页面</p><Button type="text" onClick={() => setNewPageVisible(true)}>创建第一页</Button></div></div>}
        </div>
      </section>
      <section>
        {currentPage ? <>
          <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 12, marginBottom: 12 }}>
            <Input value={currentPage.title} onChange={(title) => { void put(`/api/note-pages/${currentPage.id}`, { title }); void queryClient.invalidateQueries({ queryKey: ['note-pages', notebookId] }); }} style={{ maxWidth: 460 }} />
            <Typography.Text type="secondary">{saveState === 'saved' ? '已保存' : saveState === 'saving' ? '保存中…' : '等待保存…'}</Typography.Text>
          </div>
          <NotebookEditor content={pendingContent ?? currentPage.content} onChange={setPendingContent} />
        </> : <div className="panel"><div className="empty-state"><div><p>选择一个页面开始记录。</p></div></div></div>}
      </section>
    </div> : <Empty description="正在创建默认笔记本…" />}
    <Modal title="新建页面" visible={newPageVisible} onCancel={() => setNewPageVisible(false)} onOk={() => { if (!newPageTitle.trim()) { Message.warning('请输入页面标题'); return; } createPage.mutate(newPageTitle.trim()); }} confirmLoading={createPage.isPending} autoFocus={false}>
      <Input autoFocus placeholder="例如：错题总结" value={newPageTitle} onChange={setNewPageTitle} onPressEnter={() => { if (newPageTitle.trim()) createPage.mutate(newPageTitle.trim()); }} />
    </Modal>
  </main>;
}
