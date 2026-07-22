import { useEffect, useMemo, useRef, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Button, Checkbox, Divider, Empty, Form, Input, Message, Modal, Popconfirm, Select, Space, Tag, Typography } from '@arco-design/web-react';
import { ArrowDown, ArrowUp, BookOpenCheck, CalendarPlus, ChevronLeft, ChevronRight, Edit3, Eye, FileUp, Plus, RotateCcw, Shuffle, Trash2 } from 'lucide-react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { del, get, post, put } from '../lib/api';
import { friendlyMessage } from '../lib/errors';
import type { Bank, KnowledgePoint, Question } from '../lib/types';
import { MarkdownContent } from '../components/markdown/MarkdownRenderer';
import { moveId, shuffleIds } from '../lib/study';
import { AddToPlanModal } from '../components/AddToPlanModal';
import { CompletePlanButton } from '../components/CompletePlanButton';
import { DayQueueSessionBar, finishDayQueueStep } from '../components/DayQueueSessionBar';
import { SessionPlanRecommendModal } from '../components/SessionPlanRecommendModal';
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
  const [collapsedGroups, setCollapsedGroups] = useState<Set<string>>(new Set());
  const [planVisible, setPlanVisible] = useState(false);
  const [planItems, setPlanItems] = useState<Array<{ resourceId: number; title: string }>>([]);
  const [recommendVisible, setRecommendVisible] = useState(false);
  const [recommendPayload, setRecommendPayload] = useState<{ pointIds?: number[] }>({});
  const recommendShownRef = useRef(false);
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
  const categoryOptions = useMemo(() => [...new Set((pointsQuery.data ?? []).map((point) => point.category).filter((value): value is string => Boolean(value)))], [pointsQuery.data]);
  const tagOptions = useMemo(() => [...new Set((pointsQuery.data ?? []).flatMap((point) => point.tags))], [pointsQuery.data]);
  const matchingPoints = (pointsQuery.data ?? []).filter((point) => (!categories.length || Boolean(point.category && categories.includes(point.category))) && (!tags.length || point.tags.some((tag) => tags.includes(tag))));
  const matchingById = new Map(matchingPoints.map((point) => [point.id, point]));
  const selectedSet = new Set(selectedIds);
  const filtered = [...selectedIds.map((id) => matchingById.get(id)).filter((point): point is KnowledgePoint => Boolean(point)), ...matchingPoints.filter((point) => !selectedSet.has(point.id))];
  const pointById = new Map((pointsQuery.data ?? []).map((point) => [point.id, point]));
  const sessionPoints = (sessionIds ?? []).map((id) => pointById.get(id)).filter((point): point is KnowledgePoint => Boolean(point));
  const current = sessionPoints[index];

  const refresh = (): void => { void queryClient.invalidateQueries({ queryKey: ['knowledge-points', bankId] }); };
  const saveMutation = useMutation({ mutationFn: () => editing ? put(`/api/knowledge-points/${editing.id}`, { title, content, category: category || null, tags: tagText.split(/[,，]/).map((item) => item.trim()).filter(Boolean), questionIds: linkedQuestionIds }) : post('/api/knowledge-points', { bankId, title, content, category: category || null, tags: tagText.split(/[,，]/).map((item) => item.trim()).filter(Boolean), questionIds: linkedQuestionIds }), onSuccess: () => { refresh(); setEditorVisible(false); Message.success(editing ? '知识点已更新' : '知识点已创建'); }, onError: (error) => Message.error(friendlyMessage(error, '知识点保存失败，请稍后重试')) });
  const deleteMutation = useMutation({ mutationFn: (id: number) => del(`/api/knowledge-points/${id}`), onSuccess: () => { refresh(); Message.success('知识点已删除'); }, onError: (error) => Message.error(friendlyMessage(error, '知识点删除失败，请稍后重试')) });
  const importMutation = useMutation({ mutationFn: (payload: { markdown: string }) => post<{ imported: number; failed: number; errors: string[]; strategy?: string }>('/api/knowledge-points/import/markdown', { bankId, content: payload.markdown }), onSuccess: (result) => { refresh(); const usedAi = result.strategy === 'ai-fallback'; Message.success(`已导入 ${result.imported} 个知识点${usedAi ? '（AI 兜底）' : ''}`); if (result.errors.length) Message.warning(result.errors.slice(0, 2).join('；')); }, onError: (error) => Message.error(friendlyMessage(error, '知识点导入失败，请稍后重试')) });
  const openEditor = (point?: KnowledgePoint): void => { setEditing(point); setTitle(point?.title ?? ''); setContent(point?.content ?? ''); setCategory(point?.category ?? ''); setTagText((point?.tags ?? []).join(', ')); setLinkedQuestionIds(point?.questionIds ?? []); setEditorVisible(true); };
  const startImport = async (): Promise<void> => { if (window.api) { const result = await window.api.dialog.openTextFile(); if (!result.canceled && result.content !== undefined) importMutation.mutate({ markdown: result.content }); } else fallbackFile.current?.click(); };
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

  // 按 groupLevel 对 filtered 做本地分组（纯视图，不写库）
  const grouped = useMemo(() => {
    if (groupLevel === 0) return null;
    const groups = new Map<string, KnowledgePoint[]>();
    const orphansKey = '（未分类 / 无标题链）';
    for (const point of filtered) {
      const key = point.headingPath?.[groupLevel - 1] ?? orphansKey;
      const arr = groups.get(key) ?? [];
      arr.push(point);
      groups.set(key, arr);
    }
    // 把"未分类"组排到最后
    const entries = [...groups.entries()].sort(([a], [b]) => {
      if (a === orphansKey) return 1;
      if (b === orphansKey) return -1;
      return a.localeCompare(b, 'zh');
    });
    return entries;
  }, [filtered, groupLevel]);

  const toggleGroup = (key: string): void => {
    setCollapsedGroups((prev) => {
      const next = new Set(prev);
      if (next.has(key)) next.delete(key);
      else next.add(key);
      return next;
    });
  };

  const renderKnowledgeItem = (point: KnowledgePoint): JSX.Element => (
    <article className={`knowledge-item ${selectedIds.includes(point.id) ? 'selected' : ''}`} key={point.id}>
      <div className="knowledge-item-top">
        <Checkbox checked={selectedIds.includes(point.id)} onChange={(checked) => setSelectedIds((ids) => checked ? [...ids, point.id] : ids.filter((id) => id !== point.id))} />
        <div>
          <h3>{point.title}</h3>
          <Space wrap>{point.category && <Tag color="arcoblue">{point.category}</Tag>}{point.tags.map((tag) => <Tag key={tag}>{tag}</Tag>)}</Space>
        </div>
        <Space size={2}>
          <Button type="text" size="mini" icon={<ArrowUp size={13} />} onClick={() => setSelectedIds(moveId(selectedIds, point.id, -1))} />
          <Button type="text" size="mini" icon={<ArrowDown size={13} />} onClick={() => setSelectedIds(moveId(selectedIds, point.id, 1))} />
          <Button type="text" size="mini" icon={<CalendarPlus size={13} />} onClick={() => openPlanForPoints([point])} />
          <Button type="text" size="mini" icon={<Edit3 size={14} />} onClick={() => openEditor(point)} />
          <Popconfirm title="删除这个知识点？" onOk={() => deleteMutation.mutate(point.id)}><Button type="text" status="danger" size="mini" icon={<Trash2 size={14} />} /></Popconfirm>
        </Space>
      </div>
      <MarkdownContent value={point.content} />
      <Typography.Text type="secondary">关联 {point.questionIds.length} 道题</Typography.Text>
    </article>
  );

  return <main className="page"><input hidden ref={fallbackFile} type="file" accept=".md,.markdown,.txt" onChange={(event) => { const file = event.target.files?.[0]; if (file) void file.text().then((value) => importMutation.mutate({ markdown: value })); event.target.value = ''; }} />
    {dayQueueMode ? <DayQueueSessionBar /> : null}
    <div className="page-heading"><div><h1>背知识点</h1><p>建立真正的知识点库，关联题目，并按分类、标签或自定义顺序反复记忆。</p></div><Space><CompletePlanButton planItemId={planItemId} resourceType={planResourceId ? 'knowledge_point' : undefined} resourceId={planResourceId} />{sessionIds ? <Button icon={<RotateCcw size={16} />} onClick={returnToLibrary}>返回知识库</Button> : <><Button icon={<FileUp size={16} />} loading={importMutation.isPending} onClick={() => void startImport()}>导入 Markdown</Button><Button type="primary" icon={<Plus size={16} />} onClick={() => openEditor()}>新建知识点</Button></>}</Space></div>
    {!sessionIds ? <><section className="panel"><div className="panel-header"><h2>知识卡编排</h2><Select value={bankId} onChange={(value) => setBankId(Number(value))} placeholder="选择题库" style={{ width: 280 }}>{banksQuery.data?.map((bank) => <Select.Option key={bank.id} value={bank.id}>{bank.name}</Select.Option>)}</Select></div><div className="panel-body"><div className="advanced-filters"><Select mode="multiple" allowClear placeholder="分类" value={categories} onChange={(values) => setCategories(values.map(String))}>{categoryOptions.map((item) => <Select.Option key={item} value={item}>{item}</Select.Option>)}</Select><Select mode="multiple" allowClear placeholder="标签" value={tags} onChange={(values) => setTags(values.map(String))}>{tagOptions.map((item) => <Select.Option key={item} value={item}>{item}</Select.Option>)}</Select><Select value={groupLevel} onChange={(value) => setGroupLevel(Number(value))} style={{ width: 180 }} aria-label="按级分组查看"><Select.Option key={0} value={0}>不分组</Select.Option>{[1, 2, 3, 4, 5, 6].map((level) => <Select.Option key={level} value={level}>{level} 级标题分组</Select.Option>)}</Select></div><div className="selection-toolbar"><Space><Button size="small" onClick={() => setSelectedIds([...selectedIds.filter((id) => !filtered.some((point) => point.id === id)), ...filtered.map((point) => point.id)])}>全选筛选结果（{filtered.length}）</Button><Button size="small" icon={<Shuffle size={14} />} onClick={() => setSelectedIds(shuffleIds(selectedIds))}>随机重排</Button><Button size="small" icon={<CalendarPlus size={14} />} disabled={!setupSelectedPoints.length} onClick={() => openPlanForPoints(setupSelectedPoints)}>加入计划</Button></Space><Typography.Text type="secondary">已选 {selectedIds.length}</Typography.Text></div>
      {grouped ? (
        <div className="knowledge-groups">
          {grouped.map(([groupKey, items]) => {
            const collapsed = collapsedGroups.has(groupKey);
            const groupSelectedCount = items.filter((p) => selectedIds.includes(p.id)).length;
            return (
              <section className="knowledge-group" key={groupKey}>
                <header className="knowledge-group-header" onClick={() => toggleGroup(groupKey)}>
                  <Space>
                    <span className="knowledge-group-caret">{collapsed ? '▶' : '▼'}</span>
                    <h3>{groupKey}</h3>
                    <Typography.Text type="secondary">{items.length} 个 · 已选 {groupSelectedCount}</Typography.Text>
                  </Space>
                  <Space size={4}>
                    <Button size="mini" onClick={(e) => { e.stopPropagation(); setSelectedIds((ids) => [...ids.filter((id) => !items.some((p) => p.id === id)), ...items.map((p) => p.id)]); }}>全选本组</Button>
                  </Space>
                </header>
                {!collapsed && <div className="knowledge-grid">{items.map(renderKnowledgeItem)}</div>}
              </section>
            );
          })}
        </div>
      ) : (
        <div className="knowledge-grid">{filtered.map(renderKnowledgeItem)}</div>
      )}
      {!filtered.length && <Empty description="暂无知识点；可新建或导入 Markdown" />}<div className="setup-actions"><Button type="primary" icon={<BookOpenCheck size={16} />} disabled={!selectedIds.length} onClick={startSession}>开始背知识点（{selectedIds.length}）</Button></div></div></section></> : current ? <div className="study-session-layout"><aside className="panel question-palette"><div className="panel-header"><h2>知识点跳转</h2></div><div className="panel-body"><div className="palette-grid">{sessionPoints.map((point, itemIndex) => <button type="button" className={`palette-item ${itemIndex === index ? 'current' : ''}`} key={point.id} onClick={() => jump(itemIndex)}>{itemIndex + 1}</button>)}</div></div></aside><section className="quiz-card memory-card"><div className="quiz-progress"><span>第 {index + 1} / {sessionPoints.length} 个知识点</span>{current.category && <Tag color="arcoblue">{current.category}</Tag>}</div><h2 className="knowledge-study-title">{current.title}</h2>{revealed ? <div className="knowledge-study-content"><MarkdownContent value={current.content} />{current.questionIds.length > 0 && <div className="linked-question-list"><strong>关联题目</strong>{current.questionIds.map((id) => <div key={id}>{questionsQuery.data?.find((question) => question.id === id)?.stem ?? `题目 #${id}`}</div>)}</div>}{!kpSubmittedIds.has(current.id) ? <div className="quality-rating"><Divider /><Typography.Text style={{ marginBottom: 8, display: 'block' }}>对这个知识点的掌握程度？</Typography.Text><div className="quality-buttons">{[0, 2, 3, 4, 5].map((q) => <button key={q} className={`quality-btn quality-${q}`} disabled={kpSubmitting} onClick={() => submitKpReview(current.id, q)}><span className="quality-label">{QUALITY_LABELS[q]}</span><span className="quality-score">{q}</span></button>)}</div></div> : <div className="quality-rating" style={{ textAlign: 'center', padding: '16px 0' }}><Divider /><Typography.Text type="secondary">已评分：{QUALITY_LABELS[kpRating ?? 3]}（{kpRating}分）</Typography.Text></div>}</div> : <div className="memory-cover"><Eye size={22} /><span>先回忆定义、原理和例子，再揭示知识点</span></div>}<div className="quiz-actions"><Button icon={<ChevronLeft size={16} />} disabled={index === 0} onClick={() => jump(index - 1)}>上一个</Button><Button type="primary" icon={<Eye size={16} />} onClick={() => setRevealed((value) => !value)}>{revealed ? '隐藏内容' : '揭示内容'}</Button><Button icon={<CalendarPlus size={16} />} onClick={() => openPlanForPoints([current])}>加入计划</Button><Button icon={<ChevronRight size={16} />} onClick={nextPoint}>{index === sessionPoints.length - 1 ? '完成' : '下一个'}</Button><Button type="outline" onClick={openSessionRecommend}>结束并推荐</Button></div></section></div> : <Empty description="没有可背诵的知识点" />}
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
  </main>;
}
