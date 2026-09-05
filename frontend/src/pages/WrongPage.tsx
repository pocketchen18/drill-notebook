import { useEffect, useMemo, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Button, Empty, Message, Popconfirm, Spin, Table, Tag, Tooltip, Typography } from '@arco-design/web-react';
import { BrainCircuit, CalendarPlus, CheckCircle2, RotateCcw, Sparkles, Target, XCircle } from 'lucide-react';
import { useNavigate } from 'react-router-dom';
import { get, put } from '../lib/api';
import type { WrongBookEntry } from '../lib/types';
import { useUiStore } from '../stores/uiStore';
import { questionsToMarkdown } from '../lib/aiContext';
import { useRegisterPageContext } from '../hooks/useRegisterPageContext';
import { ExportActions } from '../components/ExportActions';
import { questionExportDocument } from '../lib/export';
import { questionTypeLabel } from '../lib/quiz';
import { AddToPlanModal } from '../components/AddToPlanModal';
import { truncateTitle } from '../lib/studyPlan';
import { enrollItems } from '../lib/review';
import { usePersistSlice } from '../hooks/useViewState';
import { readPageSlice } from '../lib/viewState';
import { markdownToPlainText } from '../lib/markdownText';

/** Stable empty array - never allocate a new [] for missing query data. */
const EMPTY_QUESTIONS: WrongBookEntry[] = [];

export function WrongPage(): JSX.Element {
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const setAiOpen = useUiStore((state) => state.setAiOpen);
  const query = useQuery({ queryKey: ['wrong'], queryFn: () => get<WrongBookEntry[]>('/api/quiz/wrong') });
  const rows = query.data ?? EMPTY_QUESTIONS;
  const cachedWrong = readPageSlice('wrong');
  const [selectedIds, setSelectedIds] = useState<number[]>(() => cachedWrong.selectedIds ?? []);
  const [planVisible, setPlanVisible] = useState(false);
  const [planItems, setPlanItems] = useState<Array<{ resourceId: number; title: string }>>([]);
  const selectedRows = rows.filter((row) => selectedIds.includes(row.id));
  const enrollMutation = useMutation({
    mutationFn: (ids: number[]) => enrollItems('question', ids),
    onSuccess: (result) => {
      const enrolled = result.filter((r) => r.status === 'enrolled').length;
      const already = result.filter((r) => r.status === 'already_enrolled').length;
      if (enrolled > 0 && already > 0) {
        Message.success(`新加入记忆曲线 ${enrolled} 道，另有 ${already} 道已在复习中`);
      } else if (enrolled > 0) {
        Message.success({
          content: `已将 ${enrolled} 道错题加入记忆曲线。请打开「日历 → 今天」，筛「记忆曲线」查看（标签：新学/待新学）。`,
          duration: 6000
        });
      } else if (already > 0) {
        Message.info(
          `${already} 道已在记忆曲线中。打开日历「今天」→ 筛「记忆曲线」；若曾加入过且已推到未来日期，今天可能暂不显示。`
        );
      } else {
        Message.warning('没有可加入的题目');
      }
    },
    onError: (error) => Message.error(error instanceof Error ? error.message : '加入记忆曲线失败，请稍后重试'),
  });

  const enrollSelected = (): void => {
    if (!selectedIds.length) {
      Message.warning('请先勾选要加入记忆曲线的错题');
      return;
    }
    enrollMutation.mutate(selectedIds);
  };

  const enrollOne = (id: number): void => {
    enrollMutation.mutate([id]);
  };

  const excludeMutation = useMutation({
    mutationFn: (ids: number[]) => Promise.all(ids.map((id) => put(`/api/questions/${id}/wrong-excluded`, { excluded: true }))),
    onSuccess: (_result, ids) => {
      void queryClient.invalidateQueries({ queryKey: ['wrong'] });
      setSelectedIds((prev) => prev.filter((id) => !ids.includes(id)));
      Message.success(ids.length > 1 ? `已将 ${ids.length} 道题移出错题本` : '已移出错题本');
    },
    onError: (error) => Message.error(error instanceof Error ? error.message : '移出失败，请稍后重试')
  });
  useEffect(() => {
    if (!query.data) return;
    const available = new Set(query.data.map((row) => row.id));
    setSelectedIds((ids) => ids.filter((id) => available.has(id)));
  }, [query.data]);
  usePersistSlice('wrong', { selectedIds });
  const pageContext = useMemo(() => ({
    kind: 'wrong' as const,
    title: `错题本（${rows.length} 道）`,
    markdown: questionsToMarkdown(rows),
    route: '/wrong'
  }), [rows]);

  useRegisterPageContext(pageContext);

  const openPlanForRows = (items: WrongBookEntry[]): void => {
    setPlanItems(items.map((row) => ({ resourceId: row.id, title: truncateTitle(row.stem || `题目 #${row.id}`) })));
    setPlanVisible(true);
  };

  return (
    <main className="page">
      <div className="page-heading">
        <div>
          <h1>错题</h1>
          <p>
            累计答错、尚未掌握的题目，按错误次数排序。连续答对 2 次会自动清出；勾选后可批量「移出错题本」或「加入记忆曲线」。
          </p>
        </div>
        <div className="page-heading__actions">
          <ExportActions count={selectedRows.length} document={() => questionExportDocument('错题本', selectedRows)} />
          <Button
            icon={<CalendarPlus size={16} />}
            disabled={!selectedRows.length}
            onClick={() => openPlanForRows(selectedRows)}
          >
            加入日历计划
          </Button>
          <Button
            type="primary"
            icon={<BrainCircuit size={16} />}
            disabled={!selectedIds.length}
            loading={enrollMutation.isPending}
            onClick={enrollSelected}
          >
            加入记忆曲线
          </Button>
          <Popconfirm
            title={`移出错题本？`}
            content={`将把选中的 ${selectedIds.length} 道题移出错题本；下次答错会自动重新计入。`}
            disabled={!selectedIds.length}
            onOk={() => excludeMutation.mutate(selectedIds)}
          >
            <Button icon={<CheckCircle2 size={16} />} disabled={!selectedIds.length} loading={excludeMutation.isPending}>
              移出错题本
            </Button>
          </Popconfirm>
          <Button icon={<Sparkles size={16} />} disabled={!rows.length} onClick={() => setAiOpen(true)}>AI 分析错题</Button>
          <Button icon={<RotateCcw size={16} />} disabled={!rows.length} onClick={() => navigate(`/quiz?questionIds=${rows.map((row) => row.id).join(',')}`)}>再练一遍</Button>
        </div>
      </div>
      <section className="panel">
        <div className="panel-header">
          <h2>待巩固题目</h2>
          <Tag color={rows.length ? 'red' : 'green'}>{rows.length} 道</Tag>
        </div>
        <div className="panel-body">
          {query.isLoading ? (
            <Spin />
          ) : rows.length ? (
            <Table
              rowKey="id"
              data={rows}
              pagination={false}
              rowSelection={{ type: 'checkbox', selectedRowKeys: selectedIds, onChange: (keys) => setSelectedIds(keys.map(Number)) }}
              columns={[
                { title: '题目', dataIndex: 'stem', render: (stem: string) => <Typography.Text ellipsis={{ showTooltip: true }}>{markdownToPlainText(stem)}</Typography.Text> },
                { title: '类型', dataIndex: 'type', width: 90, render: (_: unknown, row: WrongBookEntry) => questionTypeLabel(row.type) },
                { title: '章节', dataIndex: 'chapter', width: 130, render: (chapter?: string) => chapter || '未分类' },
                {
                  title: '错误次数',
                  dataIndex: 'wrongCount',
                  width: 120,
                  sorter: (a: WrongBookEntry, b: WrongBookEntry) => a.wrongCount - b.wrongCount,
                  render: (_: unknown, row: WrongBookEntry) => (
                    <Tag color={row.wrongCount >= 3 ? 'red' : row.wrongCount >= 2 ? 'orange' : 'arcoblue'}>
                      错 {row.wrongCount} / 答 {row.attemptCount}
                    </Tag>
                  )
                },
                {
                  title: '错误率',
                  dataIndex: 'errorRate',
                  width: 90,
                  sorter: (a: WrongBookEntry, b: WrongBookEntry) => a.errorRate - b.errorRate,
                  render: (_: unknown, row: WrongBookEntry) => `${Math.round(row.errorRate * 100)}%`
                },
                {
                  title: '操作',
                  dataIndex: 'actions',
                  width: 152,
                  align: 'center',
                  render: (_: unknown, row: WrongBookEntry) => (
                    <div style={{ display: 'flex', gap: 2, justifyContent: 'center' }}>
                      <Tooltip content="练习这道题">
                        <Button type="text" size="mini" aria-label="练习" icon={<Target size={15} />} onClick={() => navigate(`/quiz?questionIds=${row.id}`)} />
                      </Tooltip>
                      <Tooltip content="加入记忆曲线">
                        <Button
                          type="text"
                          size="mini"
                          aria-label="加入记忆曲线"
                          loading={enrollMutation.isPending && (enrollMutation.variables?.includes(row.id) ?? false)}
                          icon={<BrainCircuit size={15} />}
                          onClick={() => enrollOne(row.id)}
                        />
                      </Tooltip>
                      <Tooltip content="加入日历计划">
                        <Button type="text" size="mini" aria-label="加入日历计划" icon={<CalendarPlus size={15} />} onClick={() => openPlanForRows([row])} />
                      </Tooltip>
                      <Tooltip content="移出错题本（下次答错自动重新计入）">
                        <Button
                          type="text"
                          size="mini"
                          status="success"
                          aria-label="移出错题本"
                          loading={excludeMutation.isPending && (excludeMutation.variables?.includes(row.id) ?? false)}
                          icon={<CheckCircle2 size={15} />}
                          onClick={() => excludeMutation.mutate([row.id])}
                        />
                      </Tooltip>
                    </div>
                  )
                }
              ]}
            />
          ) : (
            <Empty icon={<XCircle size={34} />} description="还没有错题。做错的题会按错误次数累计在这里，连续答对 2 次后自动清出。" />
          )}
        </div>
      </section>
      <AddToPlanModal
        visible={planVisible}
        onClose={() => setPlanVisible(false)}
        resourceType="question"
        items={planItems}
        defaultTitle="错题计划"
      />
    </main>
  );
}
