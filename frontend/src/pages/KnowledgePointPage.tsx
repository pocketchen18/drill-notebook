import { useEffect, useMemo, useRef, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Button, Form, Input, Message, Modal, Popconfirm, Select, Space, TreeSelect } from '@arco-design/web-react';
import { FileUp, Plus, Sparkles, Trash2 } from 'lucide-react';
import { useSearchParams } from 'react-router-dom';
import { del, get, post, put } from '../lib/api';
import { friendlyMessage } from '../lib/errors';
import type { Bank, KnowledgePoint, Question } from '../lib/types';
import { summarizeBank, resummarizeBank, summarizeImport, deleteKnowledgePoints } from '../lib/knowledgeApi';
import { AiSummaryModal } from '../components/AiSummaryModal';
import { DayQueueSessionBar } from '../components/DayQueueSessionBar';
import { KnowledgeFullCardView } from './knowledge/KnowledgeFullCardView';
import { KnowledgeMemorizeSession } from './knowledge/KnowledgeMemorizeSession';
import { planScopeFromSearch } from '../lib/planProgress';
import { buildKnowledgeTree } from '../lib/knowledgeTree';
import { KnowledgeTreeNav } from './knowledge/KnowledgeTreeNav';
import { KnowledgeCardWorkspace } from './knowledge/KnowledgeCardWorkspace';
import { usePersistSlice } from '../hooks/useViewState';
import { putScoped, readPageSlice, readScoped } from '../lib/viewState';
import { markdownToPlainText } from '../lib/markdownText';

export function KnowledgePointPage(): JSX.Element {
  const [searchParams] = useSearchParams();
  const { planItemId, planDate } = planScopeFromSearch(searchParams);
  const pointIdsFromQuery = useMemo(
    () => searchParams.get('pointIds')?.split(',').map(Number).filter(Boolean) ?? [],
    [searchParams]
  );
  const dayQueueMode = searchParams.get('dayQueue') === '1';
  const planResourceId = pointIdsFromQuery.length === 1 ? pointIdsFromQuery[0] : undefined;
  const queryClient = useQueryClient();
  const fallbackFile = useRef<HTMLInputElement>(null);
  const banksQuery = useQuery({ queryKey: ['banks'], queryFn: () => get<Bank[]>('/api/banks') });
  // When deep-linking with pointIds, load all points (no bank filter) so the id is found across banks.
  const deepLinkActive = pointIdsFromQuery.length > 0;
  const cachedKnowledge = readPageSlice('knowledge');
  const [bankId, setBankId] = useState<number | undefined>(cachedKnowledge.bankId);
  const pointsQuery = useQuery({
    queryKey: ['knowledge-points', deepLinkActive ? 'all' : bankId],
    queryFn: () => get<KnowledgePoint[]>(`/api/knowledge-points${deepLinkActive || !bankId ? '' : `?bankId=${bankId}`}`),
    enabled: deepLinkActive || bankId !== undefined
  });
  const questionsQuery = useQuery({ queryKey: ['knowledge-questions', bankId], queryFn: () => get<Question[]>(`/api/banks/${bankId}/questions`), enabled: bankId !== undefined });
  const [editorVisible, setEditorVisible] = useState(false);
  const [editing, setEditing] = useState<KnowledgePoint>();
  const [parentPointId, setParentPointId] = useState<number | undefined>();
  const deepLinkStarted = useRef(false);
  const [title, setTitle] = useState(''); const [content, setContent] = useState(''); const [category, setCategory] = useState(''); const [tagText, setTagText] = useState(''); const [linkedQuestionIds, setLinkedQuestionIds] = useState<number[]>([]);
  // 背诵会话清单：本页仅由日历/今日队列深链自动进入，主动背诵请去「练习 → 背诵 → 背知识点」
  const [sessionIds, setSessionIds] = useState<number[]>();
  // AI 总结 / 单卡全屏
  const [aiSummaryVisible, setAiSummaryVisible] = useState(false);
  // 当前正在跑的总结任务态：null=空闲，否则 Modal 关掉后后台继续跑，完成后弹 Toast + 刷新
  const [activeSummaryTask, setActiveSummaryTask] = useState<'import' | 'summarize' | 'resummarize' | null>(null);
  const [fullCardPoint, setFullCardPoint] = useState<KnowledgePoint>();
  // 树阅读：标题搜索、当前选中节点、标签筛选（均从上次记忆恢复）
  const [search, setSearch] = useState(cachedKnowledge.search ?? '');
  const [activeId, setActiveId] = useState<number | null>(cachedKnowledge.activeId ?? null);
  const [activeTag, setActiveTag] = useState<string | null>(cachedKnowledge.activeTag ?? null);
  // KnowledgeTreeNav 自行套用本库折叠状态并在变化时回报，这里只做镜像用于落盘
  const [collapsedKeys, setCollapsedKeys] = useState<string[]>(
    () => readScoped(cachedKnowledge.treeCollapsed, cachedKnowledge.bankId) ?? []
  );
  const currentBank = banksQuery.data?.find((b) => b.id === bankId);
  const tree = useMemo(() => buildKnowledgeTree(pointsQuery.data ?? [], currentBank ? currentBank.name : '全部知识点'), [pointsQuery.data, currentBank]);
  const treeSelectData = useMemo(() => {
    const formatNode = (item: typeof tree.rootNode): { key: string; value: string; title: string; disabled: boolean; children: any[] } => {
      // 正在编辑某个节点时，不能选择自己及自己的子代作为父节点（避免产生环）
      const isSelfOrDescendant = (n: typeof tree.rootNode, targetId: number): boolean => {
        if (n.id === targetId) return true;
        return n.children.some((c) => isSelfOrDescendant(c, targetId));
      };
      const disabled = editing ? isSelfOrDescendant(item, editing.id) : false;

      return {
        key: String(item.id),
        value: String(item.id),
        title: item.title,
        disabled,
        children: item.children.map(formatNode),
      };
    };

    return [
      {
        key: '0',
        value: '0',
        title: `(顶层根节点) - ${currentBank?.name ?? '全部知识点'}`,
        disabled: false,
        children: tree.roots.map(formatNode),
      },
    ];
  }, [tree, currentBank, editing]);
  const currentNode = activeId != null ? tree.byId.get(activeId) : undefined;

  useEffect(() => { if (bankId === undefined && banksQuery.data?.length) setBankId(banksQuery.data[0].id); }, [bankId, banksQuery.data]);
  useEffect(() => {
    const banks = banksQuery.data;
    if (!banks?.length || bankId === undefined) return;
    if (!banks.some((bank) => bank.id === bankId)) setBankId(undefined);
  }, [bankId, banksQuery.data]);
  useEffect(() => {
    if (!pointsQuery.data || activeId === null) return;
    if (!tree.byId.has(activeId)) setActiveId(null);
  }, [activeId, pointsQuery.data, tree]);
  usePersistSlice('knowledge', {
    bankId,
    search,
    activeId,
    activeTag,
    treeCollapsed: putScoped(cachedKnowledge.treeCollapsed, bankId, collapsedKeys)
  });

  // Auto-start session once deep-linked points are loaded (once per visit).
  // 定时任务/深链只安排一次出场，强制单轮，避免同一知识点在会话内循环多遍。
  useEffect(() => {
    if (!deepLinkActive || deepLinkStarted.current || !pointsQuery.data?.length) return;
    const availableIds = new Set(pointsQuery.data.map((point) => point.id));
    const preferred = pointIdsFromQuery.filter((id) => availableIds.has(id));
    if (!preferred.length) return;
    deepLinkStarted.current = true;
    setSessionIds(preferred);
  }, [deepLinkActive, pointIdsFromQuery, pointsQuery.data]);

  const refresh = (): void => { void queryClient.invalidateQueries({ queryKey: ['knowledge-points', bankId] }); };

  const computeHeadingPath = (): string[] => {
    if (!parentPointId || parentPointId === 0) return [];
    const parentNode = tree.byId.get(parentPointId);
    if (!parentNode || parentNode.id === 0) return [];
    return [...parentNode.headingPath, parentNode.title];
  };

  const saveMutation = useMutation({
    mutationFn: () => {
      const headingPath = computeHeadingPath();
      return editing
        ? put(`/api/knowledge-points/${editing.id}`, {
            title,
            content,
            category: category || null,
            tags: tagText.split(/[,，]/).map((item) => item.trim()).filter(Boolean),
            headingPath,
            questionIds: linkedQuestionIds,
          })
        : post('/api/knowledge-points', {
            bankId,
            title,
            content,
            category: category || null,
            tags: tagText.split(/[,，]/).map((item) => item.trim()).filter(Boolean),
            headingPath,
            questionIds: linkedQuestionIds,
          });
    },
    onSuccess: () => {
      refresh();
      setEditorVisible(false);
      Message.success(editing ? '知识点已更新' : '知识点已创建');
    },
    onError: (error) => Message.error(friendlyMessage(error, '知识点保存失败，请稍后重试')),
  });
  const deleteMutation = useMutation({
    mutationFn: (id: number) => del(`/api/knowledge-points/${id}`),
    onSuccess: (_, deletedId) => {
      refresh();
      if (activeId === deletedId) {
        setActiveId(null);
      }
      Message.success('知识点已删除');
    },
    onError: (error) => Message.error(friendlyMessage(error, '知识点删除失败，请稍后重试')),
  });
  const clearBankMutation = useMutation({
    mutationFn: async () => {
      const ids = (pointsQuery.data ?? []).map((p) => p.id);
      if (ids.length === 0) return { deleted: 0 };
      return deleteKnowledgePoints(ids);
    },
    onSuccess: (result) => {
      refresh();
      setActiveId(null);
      setFullCardPoint(undefined);
      Message.success(`已清空 ${result.deleted} 个知识点`);
    },
    onError: (error) => Message.error(friendlyMessage(error, '清空知识库失败，请稍后重试')),
  });
  const importMutation = useMutation({ mutationFn: (payload: { markdown: string }) => post<{ imported: number; failed: number; errors: string[]; strategy?: string }>('/api/knowledge-points/import/markdown', { bankId, content: payload.markdown }), onSuccess: (result) => { refresh(); const usedAi = result.strategy === 'ai-fallback'; Message.success(`已导入 ${result.imported} 个知识点${usedAi ? '（AI 兜底）' : ''}`); if (result.errors.length) Message.warning(result.errors.slice(0, 2).join('；')); }, onError: (error) => Message.error(friendlyMessage(error, '知识点导入失败，请稍后重试')) });

  const openEditor = (point?: KnowledgePoint, parentId?: number): void => {
    setEditing(point);
    setTitle(point?.title ?? '');
    setContent(point?.content ?? '');
    setCategory(point?.category ?? '');
    setTagText((point?.tags ?? []).join(', '));
    setLinkedQuestionIds(point?.questionIds ?? []);
    if (point) {
      // 编辑已有知识点：找到父节点 ID
      const directParentId = tree.parentById.get(point.id);
      setParentPointId(directParentId ?? 0);
    } else {
      // 新建知识点：如果传入了 parentId（或当前有选中节点），则默认挂在该节点下，否则为根节点 0
      setParentPointId(parentId !== undefined ? parentId : (activeId ?? 0));
    }
    setEditorVisible(true);
  };
  const startImport = async (): Promise<void> => { if (window.api) { const result = await window.api.dialog.openTextFile(); if (!result.canceled && result.content !== undefined) importMutation.mutate({ markdown: result.content }); } else fallbackFile.current?.click(); };
  const startAiSummary = (): void => { setAiSummaryVisible(true); };

  // 三个总结任务：设 activeSummaryTask 让 Modal 显示 loading；fetch 不绑 Modal 可见态，关掉后后台继续跑，完成后弹 Toast + 刷新 + 清态。
  const runSummaryImport = async (rawContent: string): Promise<void> => {
    if (!bankId || activeSummaryTask) return;
    setActiveSummaryTask('import');
    try {
      const result = await summarizeImport(bankId, rawContent);
      refresh();
      Message.success(`已总结并导入 ${result.imported} 个知识点`);
    } catch (error) {
      Message.error(friendlyMessage(error, '总结并导入失败'));
    } finally {
      setActiveSummaryTask(null);
    }
  };
  const runSummarizeBank = async (): Promise<void> => {
    if (!bankId || activeSummaryTask) return;
    setActiveSummaryTask('summarize');
    try {
      const result = await summarizeBank(bankId);
      refresh();
      if (result.summarized === 0 && result.failed > 0) {
        const detail = result.errors?.[0] ? `：${result.errors[0]}` : '';
        Message.error(`总结失败（${result.failed} 张失败）${detail}`);
      } else {
        Message.success(`已总结 ${result.summarized} 个知识点${result.failed ? `（${result.failed} 张失败）` : ''}`);
      }
    } catch (error) {
      Message.error(friendlyMessage(error, '总结当前知识库失败'));
    } finally {
      setActiveSummaryTask(null);
    }
  };
  const runResummarizeBank = async (): Promise<void> => {
    if (!bankId || activeSummaryTask) return;
    setActiveSummaryTask('resummarize');
    try {
      const result = await resummarizeBank(bankId);
      refresh();
      if (result.summarized === 0 && result.failed > 0) {
        const detail = result.errors?.[0] ? `：${result.errors[0]}` : '';
        Message.error(`重新总结失败（${result.failed} 张失败）${detail}`);
      } else {
        Message.success(`已重新总结 ${result.summarized} 个知识点${result.failed ? `（${result.failed} 张失败）` : ''}`);
      }
    } catch (error) {
      Message.error(friendlyMessage(error, '重新总结失败'));
    } finally {
      setActiveSummaryTask(null);
    }
  };

  const returnToLibrary = (): void => {
    setSessionIds(undefined);
  };

  return <main className="page kp-page"><input hidden ref={fallbackFile} type="file" accept=".md,.markdown,.txt" onChange={(event) => { const file = event.target.files?.[0]; if (file) void file.text().then((value) => importMutation.mutate({ markdown: value })); event.target.value = ''; }} />
    {dayQueueMode ? <DayQueueSessionBar /> : null}
    <div className="page-heading">
      <div>
        <h1>知识点</h1>
        <p>树状知识阅读，按文档结构浏览知识点。</p>
      </div>
      <Space>
        {sessionIds ? <Button onClick={returnToLibrary}>返回知识库</Button> : null}
        <Select value={bankId} onChange={(v) => setBankId(Number(v))} placeholder="选择知识库" style={{ width: 200 }}>
          {banksQuery.data?.map((bank) => <Select.Option key={bank.id} value={bank.id}>{bank.name}</Select.Option>)}
        </Select>
        <Input prefix={null} allowClear placeholder="搜索标题" value={search} onChange={setSearch} style={{ width: 200 }} />
        <Button icon={<FileUp size={16} />} loading={importMutation.isPending} onClick={() => void startImport()}>导入 Markdown</Button>
        <Button type="secondary" icon={<Sparkles size={16} />} onClick={startAiSummary}>AI 总结</Button>
        <Button type="primary" icon={<Plus size={16} />} onClick={() => openEditor()}>新建知识点</Button>
        {(pointsQuery.data?.length ?? 0) > 0 && !sessionIds && (
          <Popconfirm
            title="清空当前题库所有知识点？"
            content="此操作将删除当前题库下的全部知识点及其关联记录，不可恢复。"
            onOk={() => clearBankMutation.mutate()}
          >
            <Button status="danger" icon={<Trash2 size={16} />} loading={clearBankMutation.isPending}>
              清空知识库
            </Button>
          </Popconfirm>
        )}
      </Space>
    </div>
    {!sessionIds ? (
      <div className="knowledge-tree-layout">
        <KnowledgeTreeNav
          tree={tree}
          activeId={activeId}
          search={search}
          activeTag={activeTag}
          onSelect={setActiveId}
          initialCollapsedKeys={readScoped(cachedKnowledge.treeCollapsed, bankId) ?? []}
          collapsedScope={bankId ?? 'all'}
          onCollapsedKeysChange={setCollapsedKeys}
        />
        {currentNode ? (
          <KnowledgeCardWorkspace
            tree={tree}
            node={currentNode}
            questions={questionsQuery.data ?? []}
            bankId={bankId}
            onNavigate={setActiveId}
            onEdit={(p) => openEditor(p)}
            onAddChild={(p) => openEditor(undefined, p.id)}
            onDelete={(id) => deleteMutation.mutate(id)}
            onTagClick={(tag) => setActiveTag((cur) => (cur === tag ? null : tag))}
            onFullscreen={() => setFullCardPoint(currentNode.id === 0 ? (tree.rootNode as unknown as KnowledgePoint) : pointsQuery.data?.find((p) => p.id === currentNode.id))}
            onModified={() => refresh()}
          />
        ) : (
          <div className="kp-card-empty">在左侧选择一个知识点开始阅读</div>
        )}
      </div>
    ) : (
      <KnowledgeMemorizeSession
        key={sessionIds?.join(',') ?? 'none'}
        points={pointsQuery.data ?? []}
        questions={questionsQuery.data ?? []}
        ids={sessionIds ?? []}
        singleLoop
        planItemId={planItemId}
        planDate={planDate}
        planResourceId={planResourceId}
        dayQueueMode={dayQueueMode}
      />
    )}
    <Modal
      title={editing ? '编辑知识点' : '新建知识点'}
      visible={editorVisible}
      onCancel={() => setEditorVisible(false)}
      onOk={() => {
        if (!title.trim() || !content.trim()) {
          Message.warning('请填写标题和内容');
          return;
        }
        saveMutation.mutate();
      }}
      confirmLoading={saveMutation.isPending}
      style={{ width: 760 }}
      autoFocus={false}
    >
      <Form layout="vertical">
        <Form.Item label="所属层级 / 父节点">
          <TreeSelect
            treeData={treeSelectData}
            value={String(parentPointId ?? 0)}
            onChange={(val) => setParentPointId(val ? Number(val) : 0)}
            placeholder="选择父节点（默认顶层根节点）"
            allowClear={false}
          />
        </Form.Item>
        <Form.Item label="标题" required>
          <Input value={title} onChange={setTitle} />
        </Form.Item>
        <Form.Item label="Markdown 内容" required>
          <Input.TextArea value={content} onChange={setContent} autoSize={{ minRows: 8, maxRows: 18 }} />
        </Form.Item>
        <div className="form-row">
          <Form.Item label="分类">
            <Input value={category} onChange={setCategory} />
          </Form.Item>
          <Form.Item label="标签（逗号分隔）">
            <Input value={tagText} onChange={setTagText} />
          </Form.Item>
        </div>
        <Form.Item label="关联题目">
          <Select
            mode="multiple"
            allowClear
            value={linkedQuestionIds}
            onChange={(values) => setLinkedQuestionIds(values.map(Number))}
            placeholder="选择与此知识点相关的题目"
          >
            {questionsQuery.data?.map((question) => (
              <Select.Option key={question.id} value={question.id}>
                {markdownToPlainText(question.stem)}
              </Select.Option>
            ))}
          </Select>
        </Form.Item>
      </Form>
    </Modal>
    {fullCardPoint && currentNode && (
      <KnowledgeFullCardView
        tree={tree}
        node={currentNode}
        questions={questionsQuery.data ?? []}
        bankId={bankId}
        onNavigate={(id) => { setActiveId(id); setFullCardPoint(id === 0 ? (tree.rootNode as unknown as KnowledgePoint) : pointsQuery.data?.find((p) => p.id === id)); }}
        onClose={() => setFullCardPoint(undefined)}
        onDeleted={() => { deleteMutation.mutate(fullCardPoint.id); setFullCardPoint(undefined); }}
        onModified={() => refresh()}
        onEdit={(p) => { setFullCardPoint(undefined); openEditor(p); }}
        onAddChild={(p) => { setFullCardPoint(undefined); openEditor(undefined, p.id); }}
      />
    )}
    <AiSummaryModal
      visible={aiSummaryVisible}
      bankId={bankId}
      bankHasSummary={(pointsQuery.data ?? []).some((point) => point.hasOriginal)}
      bankHasContent={(pointsQuery.data ?? []).length > 0}
      onPickFile={async (): Promise<string | undefined> => {
        if (!window.api) { fallbackFile.current?.click(); return undefined; }
        const result = await window.api.dialog.openTextFile();
        if (result.canceled || result.content === undefined) return undefined;
        return result.content;
      }}
      onClose={() => setAiSummaryVisible(false)}
      activeTask={activeSummaryTask}
      onRunImport={(rawContent) => void runSummaryImport(rawContent)}
      onRunSummarize={() => void runSummarizeBank()}
      onRunResummarize={() => void runResummarizeBank()}
    />
  </main>;
}
