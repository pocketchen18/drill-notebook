import { useEffect, useMemo, useRef, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Button, Divider, Empty, Form, Input, Message, Modal, Popconfirm, Select, Space, Tag, Typography } from '@arco-design/web-react';
import { BookOpenCheck, CalendarPlus, ChevronLeft, ChevronRight, Edit3, Eye, FileUp, Plus, RotateCcw, Shuffle, Sparkles, Trash2 } from 'lucide-react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { del, get, post, put } from '../lib/api';
import { friendlyMessage } from '../lib/errors';
import type { Bank, KnowledgePoint, Question } from '../lib/types';
import { MarkdownContent } from '../components/markdown/MarkdownRenderer';
import { moveId, shuffleIds } from '../lib/study';
import { restoreOriginal, restoreSummary, summarizeBank, resummarizeBank, summarizeImport } from '../lib/knowledgeApi';
import { AddToPlanModal } from '../components/AddToPlanModal';
import { AiSummaryModal } from '../components/AiSummaryModal';
import { CompletePlanButton } from '../components/CompletePlanButton';
import { DayQueueSessionBar, finishDayQueueStep } from '../components/DayQueueSessionBar';
import { SessionPlanRecommendModal } from '../components/SessionPlanRecommendModal';
import { KnowledgeFullCardView } from './knowledge/KnowledgeFullCardView';
import { KnowledgeLibraryView } from './knowledge/KnowledgeLibraryView';
import { planScopeFromSearch } from '../lib/planProgress';
import { completeStudy } from '../lib/study';
import { truncateTitle } from '../lib/studyPlan';
import { QUALITY_LABELS } from '../lib/review';

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
  const [selectedIds, setSelectedIds] = useState<number[]>([]);
  const [categories, setCategories] = useState<string[]>([]);
  const [tags, setTags] = useState<string[]>([]);
  const [editorVisible, setEditorVisible] = useState(false);
  const [editing, setEditing] = useState<KnowledgePoint>();
  const selectionBank = useRef<number | 'deep-link'>();
  const selectionInitialized = useRef(false);
  const deepLinkStarted = useRef(false);
  const [title, setTitle] = useState(''); const [content, setContent] = useState(''); const [category, setCategory] = useState(''); const [tagText, setTagText] = useState(''); const [linkedQuestionIds, setLinkedQuestionIds] = useState<number[]>([]);
  const [sessionIds, setSessionIds] = useState<number[]>(); const [index, setIndex] = useState(0); const [revealed, setRevealed] = useState(false);
  const [groupLevel, setGroupLevel] = useState<number>(0); // 0 = 不分组；1-6 = 按 headingPath[level-1] 分组
  const [planVisible, setPlanVisible] = useState(false);
  const [planItems, setPlanItems] = useState<Array<{ resourceId: number; title: string }>>([]);
  const [recommendVisible, setRecommendVisible] = useState(false);
  const [recommendPayload, setRecommendPayload] = useState<{ pointIds?: number[] }>({});
  const recommendShownRef = useRef(false);
  // AI 总结 / 单卡全屏 / 原文-总结切换
  const [aiSummaryVisible, setAiSummaryVisible] = useState(false);
  // 当前正在跑的总结任务态：null=空闲，否则 Modal 关掉后后台继续跑，完成后弹 Toast + 刷新
  const [activeSummaryTask, setActiveSummaryTask] = useState<'import' | 'summarize' | 'resummarize' | null>(null);
  const [fullCardPoint, setFullCardPoint] = useState<KnowledgePoint>();
  // library 列表的原文/总结显示态：'summary' = 显示 AI 总结（默认），'original' = 显示原文
  const [viewMode, setViewMode] = useState<'summary' | 'original'>('summary');
  const [viewModeLoading, setViewModeLoading] = useState(false);
  // 知识点评分（SM-2 记忆曲线）
  const [kpRating, setKpRating] = useState<number | null>(null);
  const [kpSubmitting, setKpSubmitting] = useState(false);
  const [kpSubmittedIds, setKpSubmittedIds] = useState<Set<number>>(new Set());

  useEffect(() => { if (bankId === undefined && banksQuery.data?.length) setBankId(banksQuery.data[0].id); }, [bankId, banksQuery.data]);
  useEffect(() => {
    if (!pointsQuery.data) return;
    const available = pointsQuery.data.map((point) => point.id);
    const availableIds = new Set(available);

    if (deepLinkActive) {
      if (!selectionInitialized.current || selectionBank.current !== 'deep-link') {
        selectionInitialized.current = true;
        selectionBank.current = 'deep-link';
        const preferred = pointIdsFromQuery.filter((id) => availableIds.has(id));
        setSelectedIds(preferred.length ? preferred : available);
      }
      return;
    }

    if (!selectionInitialized.current || selectionBank.current !== bankId) {
      selectionInitialized.current = true;
      selectionBank.current = bankId;
      setSelectedIds(available);
      return;
    }
    setSelectedIds((ids) => ids.filter((id) => availableIds.has(id)));
  }, [bankId, deepLinkActive, pointIdsFromQuery, pointsQuery.data]);

  // Auto-start session once deep-linked points are loaded (once per visit).
  useEffect(() => {
    if (!deepLinkActive || deepLinkStarted.current || !pointsQuery.data?.length) return;
    const availableIds = new Set(pointsQuery.data.map((point) => point.id));
    const preferred = pointIdsFromQuery.filter((id) => availableIds.has(id));
    if (!preferred.length) return;
    deepLinkStarted.current = true;
    setSelectedIds(preferred);
    setSessionIds(preferred);
    setIndex(0);
    setRevealed(false);
    recommendShownRef.current = false;
    setRecommendVisible(false);
  }, [deepLinkActive, pointIdsFromQuery, pointsQuery.data]);

  const pointById = new Map((pointsQuery.data ?? []).map((point) => [point.id, point]));
  const sessionPoints = (sessionIds ?? []).map((id) => pointById.get(id)).filter((point): point is KnowledgePoint => Boolean(point));
  const current = sessionPoints[index];

  const refresh = (): void => { void queryClient.invalidateQueries({ queryKey: ['knowledge-points', bankId] }); };
  const saveMutation = useMutation({ mutationFn: () => editing ? put(`/api/knowledge-points/${editing.id}`, { title, content, category: category || null, tags: tagText.split(/[,，]/).map((item) => item.trim()).filter(Boolean), questionIds: linkedQuestionIds }) : post('/api/knowledge-points', { bankId, title, content, category: category || null, tags: tagText.split(/[,，]/).map((item) => item.trim()).filter(Boolean), questionIds: linkedQuestionIds }), onSuccess: () => { refresh(); setEditorVisible(false); Message.success(editing ? '知识点已更新' : '知识点已创建'); }, onError: (error) => Message.error(friendlyMessage(error, '知识点保存失败，请稍后重试')) });
  const deleteMutation = useMutation({ mutationFn: (id: number) => del(`/api/knowledge-points/${id}`), onSuccess: () => { refresh(); Message.success('知识点已删除'); }, onError: (error) => Message.error(friendlyMessage(error, '知识点删除失败，请稍后重试')) });
  const importMutation = useMutation({ mutationFn: (payload: { markdown: string }) => post<{ imported: number; failed: number; errors: string[]; strategy?: string }>('/api/knowledge-points/import/markdown', { bankId, content: payload.markdown }), onSuccess: (result) => { refresh(); const usedAi = result.strategy === 'ai-fallback'; Message.success(`已导入 ${result.imported} 个知识点${usedAi ? '（AI 兜底）' : ''}`); if (result.errors.length) Message.warning(result.errors.slice(0, 2).join('；')); }, onError: (error) => Message.error(friendlyMessage(error, '知识点导入失败，请稍后重试')) });
  const openEditor = (point?: KnowledgePoint): void => { setEditing(point); setTitle(point?.title ?? ''); setContent(point?.content ?? ''); setCategory(point?.category ?? ''); setTagText((point?.tags ?? []).join(', ')); setLinkedQuestionIds(point?.questionIds ?? []); setEditorVisible(true); };
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
      Message.success(`已总结 ${result.summarized} 个知识点${result.failed ? `（${result.failed} 张失败）` : ''}`);
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
      Message.success(`已重新总结 ${result.summarized} 个知识点${result.failed ? `（${result.failed} 张失败）` : ''}`);
    } catch (error) {
      Message.error(friendlyMessage(error, '重新总结失败'));
    } finally {
      setActiveSummaryTask(null);
    }
  };

  // library 列表原文/总结一键切换：遍历该 bank 所有已总结卡，逐张调 restore API，把更新写回缓存
  const handleToggleViewMode = async (): Promise<void> => {
    const currentPoints = pointsQuery.data ?? [];
    const summarizedPoints = currentPoints.filter((point) => point.hasOriginal);
    if (!summarizedPoints.length) return;
    setViewModeLoading(true);
    try {
      const targetMode: 'original' | 'summary' = viewMode === 'summary' ? 'original' : 'summary';
      const updates = await Promise.all(
        summarizedPoints.map(async (point) => {
          const fn = targetMode === 'original' ? restoreOriginal : restoreSummary;
          try {
            const result = await fn(point.id);
            return { id: point.id, content: result.content };
          } catch {
            return null;  // 单张失败不阻塞其他张
          }
        })
      );
      // 把成功的结果直接写回 react-query 缓存，避免整列重拉
      const nextPoints = currentPoints.map((point) => {
        const update = updates.find((u) => u && u.id === point.id);
        return update ? { ...point, content: update.content } : point;
      });
      queryClient.setQueryData(['knowledge-points', deepLinkActive ? 'all' : bankId], nextPoints);
      setViewMode(targetMode);
    } catch (error) {
      Message.error(friendlyMessage(error, '切换原文/总结显示失败'));
    } finally {
      setViewModeLoading(false);
    }
  };
  const jump = (next: number): void => { setIndex(next); setRevealed(false); setKpRating(null); };

  // Fused complete: plan + SRS (no auto-enroll; soft-fail). quality 0/2/3/4/5 from buttons.
  const submitKpReview = async (pointId: number, quality: number): Promise<void> => {
    if (kpSubmittedIds.has(pointId) || kpSubmitting) return;
    setKpRating(quality);
    setKpSubmitting(true);
    setKpSubmittedIds((prev) => new Set(prev).add(pointId));
    try {
      await completeStudy({
        resourceType: 'knowledge_point',
        resourceId: pointId,
        quality,
        source: 'knowledge',
        planItemId: planItemId && planResourceId === pointId ? planItemId : undefined,
        planDate,
      });
    } catch {
      // 后端不可用时静默失败
    } finally {
      setKpSubmitting(false);
      if (index < sessionPoints.length - 1) {
        jump(index + 1);
      } else if (dayQueueMode && finishDayQueueStep(navigate)) {
        return;
      } else {
        Message.success('本轮背诵完成');
      }
    }
  };

  const openSessionRecommend = (): void => {
    if (recommendShownRef.current || !sessionIds?.length) return;
    recommendShownRef.current = true;
    setRecommendPayload({ pointIds: [...sessionIds] });
    setRecommendVisible(true);
  };

  const nextPoint = (): void => {
    if (!sessionPoints.length) return;
    if (index >= sessionPoints.length - 1) {
      if (dayQueueMode && finishDayQueueStep(navigate)) {
        return;
      }
      Message.success('本轮知识点背诵完成');
      openSessionRecommend();
      return;
    }
    jump(index + 1);
  };

  const startSession = (): void => {
    setSessionIds([...selectedIds]);
    setIndex(0);
    setRevealed(false);
    setKpRating(null);
    setKpSubmittedIds(new Set());
    recommendShownRef.current = false;
    setRecommendVisible(false);
  };

  const returnToLibrary = (): void => {
    setSessionIds(undefined);
    recommendShownRef.current = false;
    setRecommendVisible(false);
  };

  const openPlanForPoints = (items: KnowledgePoint[]): void => {
    setPlanItems(items.map((point) => ({ resourceId: point.id, title: truncateTitle(point.title || `知识点 #${point.id}`) })));
    setPlanVisible(true);
  };

  const setupSelectedPoints = selectedIds
    .map((id) => pointById.get(id))
    .filter((point): point is KnowledgePoint => Boolean(point));

  // 与 KnowledgeLibraryView 内部筛选条件保持一致的派生集合，供「全选筛选结果」使用
  const matchingPointIds = new Set(
    (pointsQuery.data ?? [])
      .filter((point) => (!categories.length || (point.category && categories.includes(point.category))) && (!tags.length || point.tags.some((tag) => tags.includes(tag))))
      .map((point) => point.id)
  );

  return <main className="page"><input hidden ref={fallbackFile} type="file" accept=".md,.markdown,.txt" onChange={(event) => { const file = event.target.files?.[0]; if (file) void file.text().then((value) => importMutation.mutate({ markdown: value })); event.target.value = ''; }} />
    {dayQueueMode ? <DayQueueSessionBar /> : null}
    <div className="page-heading"><div><h1>背知识点</h1><p>建立真正的知识点库，关联题目，并按分类、标签或自定义顺序反复记忆。</p></div><Space><CompletePlanButton planItemId={planItemId} resourceType={planResourceId ? 'knowledge_point' : undefined} resourceId={planResourceId} />{sessionIds ? <Button icon={<RotateCcw size={16} />} onClick={returnToLibrary}>返回知识库</Button> : <><Button icon={<FileUp size={16} />} loading={importMutation.isPending} onClick={() => void startImport()}>导入 Markdown</Button><Button type="secondary" icon={<Sparkles size={16} />} onClick={startAiSummary}>AI 总结</Button><Button type="primary" icon={<Plus size={16} />} onClick={() => openEditor()}>新建知识点</Button></>}</Space></div>
    {!sessionIds ? (
      <KnowledgeLibraryView
        banks={banksQuery.data ?? []}
        bankId={bankId}
        onBankChange={setBankId}
        points={pointsQuery.data ?? []}
        selectedIds={selectedIds}
        categories={categories}
        tags={tags}
        groupLevel={groupLevel}
        onCategoriesChange={setCategories}
        onTagsChange={setTags}
        onGroupLevelChange={setGroupLevel}
        onToggleSelect={(id, checked) => setSelectedIds((ids) => checked ? [...ids, id] : ids.filter((x) => x !== id))}
        onMove={(id, delta) => setSelectedIds(moveId(selectedIds, id, delta as -1 | 1))}
        onShuffle={() => setSelectedIds(shuffleIds(selectedIds))}
        onSelectAllFiltered={() => setSelectedIds([...selectedIds.filter((id) => !matchingPointIds.has(id)), ...matchingPointIds])}
        onAddToPlan={openPlanForPoints}
        onEdit={openEditor}
        onDelete={(id) => deleteMutation.mutate(id)}
        onClickCard={setFullCardPoint}
        onStartSession={startSession}
        bankHasSummary={(pointsQuery.data ?? []).some((point) => point.hasOriginal)}
        viewMode={viewMode}
        onToggleViewMode={() => void handleToggleViewMode()}
        viewModeLoading={viewModeLoading}
      />
    ) : current ? <div className="study-session-layout"><aside className="panel question-palette"><div className="panel-header"><h2>知识点跳转</h2></div><div className="panel-body"><div className="palette-grid">{sessionPoints.map((point, itemIndex) => <button type="button" className={`palette-item ${itemIndex === index ? 'current' : ''}`} key={point.id} onClick={() => jump(itemIndex)}>{itemIndex + 1}</button>)}</div></div></aside><section className="quiz-card memory-card"><div className="quiz-progress"><span>第 {index + 1} / {sessionPoints.length} 个知识点</span>{current.category && <Tag color="arcoblue">{current.category}</Tag>}</div><h2 className="knowledge-study-title">{current.title}</h2>{revealed ? <div className="knowledge-study-content"><MarkdownContent value={current.content} />{current.questionIds.length > 0 && <div className="linked-question-list"><strong>关联题目</strong>{current.questionIds.map((id) => <div key={id}>{questionsQuery.data?.find((question) => question.id === id)?.stem ?? `题目 #${id}`}</div>)}</div>}{!kpSubmittedIds.has(current.id) ? <div className="quality-rating"><Divider /><Typography.Text style={{ marginBottom: 8, display: 'block' }}>对这个知识点的掌握程度？</Typography.Text><div className="quality-buttons">{[0, 2, 3, 4, 5].map((q) => <button key={q} className={`quality-btn quality-${q}`} disabled={kpSubmitting} onClick={() => submitKpReview(current.id, q)}><span className="quality-label">{QUALITY_LABELS[q]}</span><span className="quality-score">{q}</span></button>)}</div></div> : <div className="quality-rating" style={{ textAlign: 'center', padding: '16px 0' }}><Divider /><Typography.Text type="secondary">已评分：{QUALITY_LABELS[kpRating ?? 3]}（{kpRating}分）</Typography.Text></div>}</div> : <div className="knowledge-study-prompt"><Typography.Text type="secondary">先回想这个知识点的核心要点，再展开看内容。</Typography.Text><Button type="primary" icon={<Eye size={16} />} onClick={() => setRevealed(true)}>显示内容</Button></div>}</section><div className="session-nav"><Button icon={<ChevronLeft size={16} />} disabled={index === 0} onClick={() => jump(index - 1)}>上一个</Button><Button icon={<ChevronRight size={16} />} disabled={index === sessionPoints.length - 1} onClick={nextPoint}>下一个</Button></div></div> : <Empty description="本轮没有可背的知识点" />}
    <Modal title={editing ? '编辑知识点' : '新建知识点'} visible={editorVisible} onCancel={() => setEditorVisible(false)} onOk={() => { if (!title.trim() || !content.trim()) { Message.warning('请填写标题和内容'); return; } saveMutation.mutate(); }} confirmLoading={saveMutation.isPending} style={{ width: 760 }} autoFocus={false}><Form layout="vertical"><Form.Item label="标题" required><Input value={title} onChange={setTitle} /></Form.Item><Form.Item label="Markdown 内容" required><Input.TextArea value={content} onChange={setContent} autoSize={{ minRows: 8, maxRows: 18 }} /></Form.Item><div className="form-row"><Form.Item label="分类"><Input value={category} onChange={setCategory} /></Form.Item><Form.Item label="标签（逗号分隔）"><Input value={tagText} onChange={setTagText} /></Form.Item></div><Form.Item label="关联题目"><Select mode="multiple" allowClear value={linkedQuestionIds} onChange={(values) => setLinkedQuestionIds(values.map(Number))} placeholder="选择与此知识点相关的题目">{questionsQuery.data?.map((question) => <Select.Option key={question.id} value={question.id}>{question.stem}</Select.Option>)}</Select></Form.Item></Form></Modal>
    <AddToPlanModal
      visible={planVisible}
      onClose={() => setPlanVisible(false)}
      resourceType="knowledge_point"
      items={planItems}
      defaultTitle="知识点计划"
    />
    <SessionPlanRecommendModal
      visible={recommendVisible}
      onClose={() => {
        recommendShownRef.current = false;
        setRecommendVisible(false);
      }}
      sessionType="knowledge"
      payload={recommendPayload}
    />
    {fullCardPoint && (
      <KnowledgeFullCardView
        point={fullCardPoint}
        total={sessionPoints.length || (pointsQuery.data?.length ?? 1)}
        index={Math.max(0, (sessionIds ?? []).indexOf(fullCardPoint.id))}
        questions={questionsQuery.data ?? []}
        onClose={() => setFullCardPoint(undefined)}
        onDeleted={() => { deleteMutation.mutate(fullCardPoint.id); setFullCardPoint(undefined); }}
        onModified={() => refresh()}
        onEdit={(p) => { setFullCardPoint(undefined); openEditor(p); }}
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
