import { useEffect, useMemo, useRef, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Button, Checkbox, Empty, Form, Input, Message, Modal, Popconfirm, Select, Space, Tag, Typography } from '@arco-design/web-react';
import { ArrowDown, ArrowUp, BookOpenCheck, CalendarPlus, ChevronLeft, ChevronRight, Edit3, Eye, FileUp, Plus, RotateCcw, Shuffle, Trash2 } from 'lucide-react';
import { useSearchParams } from 'react-router-dom';
import { del, get, post, put } from '../lib/api';
import { friendlyMessage } from '../lib/errors';
import type { Bank, KnowledgePoint, Question } from '../lib/types';
import { MarkdownContent } from '../components/markdown/MarkdownRenderer';
import { moveId, shuffleIds } from '../lib/study';
import { AddToPlanModal } from '../components/AddToPlanModal';
import { CompletePlanButton } from '../components/CompletePlanButton';
import { SessionPlanRecommendModal } from '../components/SessionPlanRecommendModal';
import { completePlanResources, planScopeFromSearch } from '../lib/planProgress';
import { truncateTitle } from '../lib/studyPlan';

export function KnowledgePointPage(): JSX.Element {
  const [searchParams] = useSearchParams();
  const { planItemId, planDate, planGroupId } = planScopeFromSearch(searchParams);
  const hasPlanScope = Boolean(planItemId || planDate || planGroupId);
  const pointIdsFromQuery = useMemo(
    () => searchParams.get('pointIds')?.split(',').map(Number).filter(Boolean) ?? [],
    [searchParams]
  );
  const completedPointIdsRef = useRef<Set<number>>(new Set());
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
  const [headingLevel, setHeadingLevel] = useState<number>(2);
  const [planVisible, setPlanVisible] = useState(false);
  const [planItems, setPlanItems] = useState<Array<{ resourceId: number; title: string }>>([]);
  const [recommendVisible, setRecommendVisible] = useState(false);
  const [recommendPayload, setRecommendPayload] = useState<{ pointIds?: number[] }>({});
  const recommendShownRef = useRef(false);

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
  const importMutation = useMutation({ mutationFn: (payload: { markdown: string; headingLevel: number }) => post<{ imported: number; failed: number; errors: string[]; strategy?: string }>('/api/knowledge-points/import/markdown', { bankId, content: payload.markdown, headingLevel: payload.headingLevel }), onSuccess: (result) => { refresh(); const usedAi = result.strategy === 'ai-fallback'; Message.success(`已导入 ${result.imported} 个知识点${usedAi ? '（AI 兜底）' : ''}`); if (result.errors.length) Message.warning(result.errors.slice(0, 2).join('；')); }, onError: (error) => Message.error(friendlyMessage(error, '知识点导入失败，请稍后重试')) });
  const openEditor = (point?: KnowledgePoint): void => { setEditing(point); setTitle(point?.title ?? ''); setContent(point?.content ?? ''); setCategory(point?.category ?? ''); setTagText((point?.tags ?? []).join(', ')); setLinkedQuestionIds(point?.questionIds ?? []); setEditorVisible(true); };
  const startImport = async (): Promise<void> => { if (window.api) { const result = await window.api.dialog.openTextFile(); if (!result.canceled && result.content !== undefined) importMutation.mutate({ markdown: result.content, headingLevel }); } else fallbackFile.current?.click(); };
  const jump = (next: number): void => { setIndex(next); setRevealed(false); };

  // 从计划进入：揭示知识点后即标完成（中途退出也保留）
  useEffect(() => {
    if (!hasPlanScope || !revealed || !sessionIds?.length) return;
    const current = sessionIds[index];
    if (!current || completedPointIdsRef.current.has(current)) return;
    completedPointIdsRef.current.add(current);
    void completePlanResources({
      resourceType: 'knowledge_point',
      resourceIds: [current],
      planDate,
      groupId: planGroupId
    }).catch(() => {
      completedPointIdsRef.current.delete(current);
    });
  }, [hasPlanScope, revealed, sessionIds, index, planDate, planGroupId]);

  const openSessionRecommend = (): void => {
    if (recommendShownRef.current || !sessionIds?.length) return;
    recommendShownRef.current = true;
    setRecommendPayload({ pointIds: [...sessionIds] });
    setRecommendVisible(true);
  };

  const nextPoint = (): void => {
    if (!sessionPoints.length) return;
    if (index >= sessionPoints.length - 1) {
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

  return <main className="page"><input hidden ref={fallbackFile} type="file" accept=".md,.markdown,.txt" onChange={(event) => { const file = event.target.files?.[0]; if (file) void file.text().then((value) => importMutation.mutate({ markdown: value, headingLevel })); event.target.value = ''; }} />
    <div className="page-heading"><div><h1>背知识点</h1><p>建立真正的知识点库，关联题目，并按分类、标签或自定义顺序反复记忆。</p></div><Space><CompletePlanButton planItemId={planItemId} />{sessionIds ? <Button icon={<RotateCcw size={16} />} onClick={returnToLibrary}>返回知识库</Button> : <><Select value={headingLevel} onChange={(value) => setHeadingLevel(Number(value))} style={{ width: 130 }}>{[1, 2, 3, 4, 5, 6].map((level) => <Select.Option key={level} value={level}>{level} 级标题分块 ({'#'.repeat(level)})</Select.Option>)}</Select><Button icon={<FileUp size={16} />} loading={importMutation.isPending} onClick={() => void startImport()}>导入 Markdown</Button><Button type="primary" icon={<Plus size={16} />} onClick={() => openEditor()}>新建知识点</Button></>}</Space></div>
    {!sessionIds ? <><section className="panel"><div className="panel-header"><h2>知识卡编排</h2><Select value={bankId} onChange={(value) => setBankId(Number(value))} placeholder="选择题库" style={{ width: 280 }}>{banksQuery.data?.map((bank) => <Select.Option key={bank.id} value={bank.id}>{bank.name}</Select.Option>)}</Select></div><div className="panel-body"><div className="advanced-filters"><Select mode="multiple" allowClear placeholder="分类" value={categories} onChange={(values) => setCategories(values.map(String))}>{categoryOptions.map((item) => <Select.Option key={item} value={item}>{item}</Select.Option>)}</Select><Select mode="multiple" allowClear placeholder="标签" value={tags} onChange={(values) => setTags(values.map(String))}>{tagOptions.map((item) => <Select.Option key={item} value={item}>{item}</Select.Option>)}</Select></div><div className="selection-toolbar"><Space><Button size="small" onClick={() => setSelectedIds([...selectedIds.filter((id) => !filtered.some((point) => point.id === id)), ...filtered.map((point) => point.id)])}>全选筛选结果（{filtered.length}）</Button><Button size="small" icon={<Shuffle size={14} />} onClick={() => setSelectedIds(shuffleIds(selectedIds))}>随机重排</Button><Button size="small" icon={<CalendarPlus size={14} />} disabled={!setupSelectedPoints.length} onClick={() => openPlanForPoints(setupSelectedPoints)}>加入计划</Button></Space><Typography.Text type="secondary">已选 {selectedIds.length}</Typography.Text></div>
      <div className="knowledge-grid">{filtered.map((point) => <article className={`knowledge-item ${selectedIds.includes(point.id) ? 'selected' : ''}`} key={point.id}><div className="knowledge-item-top"><Checkbox checked={selectedIds.includes(point.id)} onChange={(checked) => setSelectedIds((ids) => checked ? [...ids, point.id] : ids.filter((id) => id !== point.id))} /><div><h3>{point.title}</h3><Space wrap>{point.category && <Tag color="arcoblue">{point.category}</Tag>}{point.tags.map((tag) => <Tag key={tag}>{tag}</Tag>)}</Space></div><Space size={2}><Button type="text" size="mini" icon={<ArrowUp size={13} />} onClick={() => setSelectedIds(moveId(selectedIds, point.id, -1))} /><Button type="text" size="mini" icon={<ArrowDown size={13} />} onClick={() => setSelectedIds(moveId(selectedIds, point.id, 1))} /><Button type="text" size="mini" icon={<CalendarPlus size={13} />} onClick={() => openPlanForPoints([point])} /><Button type="text" size="mini" icon={<Edit3 size={14} />} onClick={() => openEditor(point)} /><Popconfirm title="删除这个知识点？" onOk={() => deleteMutation.mutate(point.id)}><Button type="text" status="danger" size="mini" icon={<Trash2 size={14} />} /></Popconfirm></Space></div><MarkdownContent value={point.content} /><Typography.Text type="secondary">关联 {point.questionIds.length} 道题</Typography.Text></article>)}</div>{!filtered.length && <Empty description="暂无知识点；可新建或导入 Markdown" />}<div className="setup-actions"><Button type="primary" icon={<BookOpenCheck size={16} />} disabled={!selectedIds.length} onClick={startSession}>开始背知识点（{selectedIds.length}）</Button></div></div></section></> : current ? <div className="study-session-layout"><aside className="panel question-palette"><div className="panel-header"><h2>知识点跳转</h2></div><div className="panel-body"><div className="palette-grid">{sessionPoints.map((point, itemIndex) => <button type="button" className={`palette-item ${itemIndex === index ? 'current' : ''}`} key={point.id} onClick={() => jump(itemIndex)}>{itemIndex + 1}</button>)}</div></div></aside><section className="quiz-card memory-card"><div className="quiz-progress"><span>第 {index + 1} / {sessionPoints.length} 个知识点</span>{current.category && <Tag color="arcoblue">{current.category}</Tag>}</div><h2 className="knowledge-study-title">{current.title}</h2>{revealed ? <div className="knowledge-study-content"><MarkdownContent value={current.content} />{current.questionIds.length > 0 && <div className="linked-question-list"><strong>关联题目</strong>{current.questionIds.map((id) => <div key={id}>{questionsQuery.data?.find((question) => question.id === id)?.stem ?? `题目 #${id}`}</div>)}</div>}</div> : <div className="memory-cover"><Eye size={22} /><span>先回忆定义、原理和例子，再揭示知识点</span></div>}<div className="quiz-actions"><Button icon={<ChevronLeft size={16} />} disabled={index === 0} onClick={() => jump(index - 1)}>上一个</Button><Button type="primary" icon={<Eye size={16} />} onClick={() => setRevealed((value) => !value)}>{revealed ? '隐藏内容' : '揭示内容'}</Button><Button icon={<CalendarPlus size={16} />} onClick={() => openPlanForPoints([current])}>加入计划</Button><Button icon={<ChevronRight size={16} />} onClick={nextPoint}>{index === sessionPoints.length - 1 ? '完成' : '下一个'}</Button><Button type="outline" onClick={openSessionRecommend}>结束并推荐</Button></div></section></div> : <Empty description="没有可背诵的知识点" />}
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
      onClose={() => setRecommendVisible(false)}
      sessionType="knowledge"
      payload={recommendPayload}
    />
  </main>;
}
