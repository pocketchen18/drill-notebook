import { useEffect, useMemo, useRef, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Button, Divider, Empty, Form, Input, Message, Modal, Popconfirm, Select, Space, Tag, TreeSelect, Typography } from '@arco-design/web-react';
import { ChevronLeft, ChevronRight, Eye, FileUp, Plus, Sparkles, Trash2 } from 'lucide-react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { del, get, post, put } from '../lib/api';
import { friendlyMessage } from '../lib/errors';
import type { Bank, KnowledgePoint, Question } from '../lib/types';
import { MarkdownContent } from '../components/markdown/MarkdownRenderer';
import { summarizeBank, resummarizeBank, summarizeImport, deleteKnowledgePoints } from '../lib/knowledgeApi';
import { AiSummaryModal } from '../components/AiSummaryModal';
import { DayQueueSessionBar, finishDayQueueStep } from '../components/DayQueueSessionBar';
import { SessionPlanRecommendModal } from '../components/SessionPlanRecommendModal';
import { KnowledgeFullCardView } from './knowledge/KnowledgeFullCardView';
import { planScopeFromSearch } from '../lib/planProgress';
import { completeStudy } from '../lib/study';
import { applyCurveAnswer, buildCurveQueue, readSessionCurveConfig } from '../lib/sessionCurve';
import type { CurveEntry, CurveItemState } from '../lib/sessionCurve';
import { buildKnowledgeTree } from '../lib/knowledgeTree';
import { KnowledgeTreeNav } from './knowledge/KnowledgeTreeNav';
import { KnowledgeCardWorkspace } from './knowledge/KnowledgeCardWorkspace';

export function KnowledgePointPage(): JSX.Element {
  const navigate = useNavigate();
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
  const [bankId, setBankId] = useState<number>();
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
  const [sessionIds, setSessionIds] = useState<number[]>(); const [index, setIndex] = useState(0); const [revealed, setRevealed] = useState(false);
  // 会话内记忆曲线：不会的知识点延迟重现
  const [curveQueue, setCurveQueue] = useState<CurveEntry[]>([]);
  const [curveStates, setCurveStates] = useState<Record<number, CurveItemState>>({});
  const curveConfigRef = useRef(readSessionCurveConfig());
  const [recommendVisible, setRecommendVisible] = useState(false);
  const [recommendPayload, setRecommendPayload] = useState<{ pointIds?: number[] }>({});
  const recommendShownRef = useRef(false);
  // AI 总结 / 单卡全屏
  const [aiSummaryVisible, setAiSummaryVisible] = useState(false);
  // 当前正在跑的总结任务态：null=空闲，否则 Modal 关掉后后台继续跑，完成后弹 Toast + 刷新
  const [activeSummaryTask, setActiveSummaryTask] = useState<'import' | 'summarize' | 'resummarize' | null>(null);
  const [fullCardPoint, setFullCardPoint] = useState<KnowledgePoint>();
  // 树阅读：标题搜索、当前选中节点、标签筛选
  const [search, setSearch] = useState('');
  const [activeId, setActiveId] = useState<number | null>(null);
  const [activeTag, setActiveTag] = useState<string | null>(null);
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
  // 知识点评分（SM-2 记忆曲线）：仅防重复提交 SRS
  const [kpSubmittedIds, setKpSubmittedIds] = useState<Set<number>>(new Set());

  useEffect(() => { if (bankId === undefined && banksQuery.data?.length) setBankId(banksQuery.data[0].id); }, [bankId, banksQuery.data]);

  // Auto-start session once deep-linked points are loaded (once per visit).
  useEffect(() => {
    if (!deepLinkActive || deepLinkStarted.current || !pointsQuery.data?.length) return;
    const availableIds = new Set(pointsQuery.data.map((point) => point.id));
    const preferred = pointIdsFromQuery.filter((id) => availableIds.has(id));
    if (!preferred.length) return;
    deepLinkStarted.current = true;
    startCurveSession(preferred);
  }, [deepLinkActive, pointIdsFromQuery, pointsQuery.data]);

  const pointById = new Map((pointsQuery.data ?? []).map((point) => [point.id, point]));
  const sessionPoints = (sessionIds ?? []).map((id) => pointById.get(id)).filter((point): point is KnowledgePoint => Boolean(point));
  const currentEntry = curveQueue[index];
  const current = currentEntry ? pointById.get(currentEntry.resourceId) : undefined;

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

  const jump = (next: number): void => { setIndex(next); setRevealed(false); };

  /** 二元评分：会 → quality 4；不会 → quality 0。SRS 只提交首次，重现仅推进会话内队列。 */
  const rateKp = (known: boolean): void => {
    if (!current) return;
    const quality = known ? 4 : 0;
    if (!kpSubmittedIds.has(current.id)) {
      setKpSubmittedIds((prev) => new Set(prev).add(current.id));
      void completeStudy({
        resourceType: 'knowledge_point',
        resourceId: current.id,
        quality,
        source: 'knowledge',
        planItemId: planItemId && planResourceId === current.id ? planItemId : undefined,
        planDate,
      }).catch(() => {
        // 后端不可用时静默失败
      });
    }
    const curve = applyCurveAnswer(curveQueue, index, known, curveStates, curveConfigRef.current);
    setCurveQueue(curve.entries);
    setCurveStates(curve.states);
    if (index < curve.entries.length - 1) {
      jump(index + 1);
    } else if (dayQueueMode && finishDayQueueStep(navigate)) {
      return;
    } else {
      Message.success('本轮背诵完成');
      openSessionRecommend();
    }
  };

  const openSessionRecommend = (): void => {
    if (recommendShownRef.current || !sessionIds?.length) return;
    recommendShownRef.current = true;
    setRecommendPayload({ pointIds: [...sessionIds] });
    setRecommendVisible(true);
  };

  const nextPoint = (): void => {
    if (!curveQueue.length) return;
    if (index >= curveQueue.length - 1) {
      if (dayQueueMode && finishDayQueueStep(navigate)) {
        return;
      }
      Message.success('本轮知识点背诵完成');
      openSessionRecommend();
      return;
    }
    jump(index + 1);
  };

  /** 开始一轮背诵会话：按当前会话曲线配置构建出场队列。 */
  const startCurveSession = (ids: number[]): void => {
    curveConfigRef.current = readSessionCurveConfig();
    setSessionIds(ids);
    setCurveQueue(buildCurveQueue(ids));
    setCurveStates({});
    setIndex(0);
    setRevealed(false);
    setKpSubmittedIds(new Set());
    recommendShownRef.current = false;
    setRecommendVisible(false);
  };

  const returnToLibrary = (): void => {
    setSessionIds(undefined);
    recommendShownRef.current = false;
    setRecommendVisible(false);
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
    ) : current ? <div className="study-session-layout"><aside className="panel question-palette"><div className="panel-header"><h2>知识点跳转</h2></div><div className="panel-body"><div className="palette-grid">{curveQueue.map((entry, itemIndex) => <button type="button" title={entry.attempt > 0 ? `第 ${entry.attempt + 1} 遍重现` : undefined} className={`palette-item ${itemIndex === index ? 'current' : ''} ${entry.attempt > 0 ? 'repeat' : ''}`} key={entry.entryId} onClick={() => jump(itemIndex)}>{itemIndex + 1}</button>)}</div><Typography.Text type="secondary">橙色描边：重现条目</Typography.Text></div></aside><section className="quiz-card memory-card"><div className="quiz-progress"><span>第 {index + 1} / {curveQueue.length} 个知识点</span>{currentEntry && currentEntry.attempt > 0 ? <Tag color="orange">第 {currentEntry.attempt + 1} 遍</Tag> : null}{current.category && <Tag color="arcoblue">{current.category}</Tag>}</div><h2 className="knowledge-study-title">{current.title}</h2>{revealed ? <div className="knowledge-study-content"><MarkdownContent value={current.content} />{current.questionIds.length > 0 && <div className="linked-question-list"><strong>关联题目</strong>{current.questionIds.map((id) => <div key={id}>{questionsQuery.data?.find((question) => question.id === id)?.stem ?? `题目 #${id}`}</div>)}</div>}{(curveStates[current.id]?.done ?? false) ? <div className="quality-rating" style={{ textAlign: 'center', padding: '16px 0' }}><Divider /><Typography.Text type="secondary">{curveStates[current.id]?.abandoned ? '本轮未记住（已达最大重复次数，可在会话结束后加入跨天复习方案）' : '已记住'}</Typography.Text></div> : <div className="quality-rating"><Divider /><Typography.Text style={{ marginBottom: 8, display: 'block' }}>记住了吗？（不会将在本轮稍后重现，评分写入记忆曲线）</Typography.Text><div className="quality-buttons"><button className="quality-btn quality-0" onClick={() => rateKp(false)}><span className="quality-label">不会</span></button><button className="quality-btn quality-4" onClick={() => rateKp(true)}><span className="quality-label">会</span></button></div></div>}</div> : <div className="knowledge-study-prompt"><Typography.Text type="secondary">先回想这个知识点的核心要点，再展开看内容。</Typography.Text><Button type="primary" icon={<Eye size={16} />} onClick={() => setRevealed(true)}>显示内容</Button></div>}</section><div className="session-nav"><Button icon={<ChevronLeft size={16} />} disabled={index === 0} onClick={() => jump(index - 1)}>上一个</Button><Button icon={<ChevronRight size={16} />} disabled={index === curveQueue.length - 1} onClick={nextPoint}>下一个</Button></div></div> : <Empty description="本轮没有可背的知识点" />}
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
                {question.stem}
              </Select.Option>
            ))}
          </Select>
        </Form.Item>
      </Form>
    </Modal>
    <SessionPlanRecommendModal
      visible={recommendVisible}
      onClose={() => {
        recommendShownRef.current = false;
        setRecommendVisible(false);
      }}
      sessionType="knowledge"
      payload={recommendPayload}
    />
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
