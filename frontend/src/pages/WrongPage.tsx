import { useEffect, useMemo, useState } from 'react';
import { useMutation, useQuery } from '@tanstack/react-query';
import { Button, Empty, Message, Spin, Table, Tag, Typography } from '@arco-design/web-react';
import { BrainCircuit, CalendarPlus, RotateCcw, Sparkles, XCircle } from 'lucide-react';
import { useNavigate } from 'react-router-dom';
import { get } from '../lib/api';
import type { Question } from '../lib/types';
import { useUiStore } from '../stores/uiStore';
import { questionsToMarkdown } from '../lib/aiContext';
import { useRegisterPageContext } from '../hooks/useRegisterPageContext';
import { ExportActions } from '../components/ExportActions';
import { questionExportDocument } from '../lib/export';
import { questionTypeLabel } from '../lib/quiz';
import { AddToPlanModal } from '../components/AddToPlanModal';
import { truncateTitle } from '../lib/studyPlan';
import { enrollItems } from '../lib/review';

/** Stable empty array - never allocate a new [] for missing query data. */
const EMPTY_QUESTIONS: Question[] = [];

export function WrongPage(): JSX.Element {
  const navigate = useNavigate();
  const setAiOpen = useUiStore((state) => state.setAiOpen);
  const query = useQuery({ queryKey: ['wrong'], queryFn: () => get<Question[]>('/api/quiz/wrong') });
  const rows = query.data ?? EMPTY_QUESTIONS;
  const [selectedIds, setSelectedIds] = useState<number[]>([]);
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
  useEffect(() => {
    if (!query.data) return;
    const available = new Set(query.data.map((row) => row.id));
    setSelectedIds((ids) => ids.filter((id) => available.has(id)));
  }, [query.data]);

  const pageContext = useMemo(() => ({
    kind: 'wrong' as const,
    title: `错题本（${rows.length} 道）`,
    markdown: questionsToMarkdown(rows),
    route: '/wrong'
  }), [rows]);

  useRegisterPageContext(pageContext);

  const openPlanForRows = (items: Question[]): void => {
    setPlanItems(items.map((row) => ({ resourceId: row.id, title: truncateTitle(row.stem || `题目 #${row.id}`) })));
    setPlanVisible(true);
  };

  return (
    <main className="page">
      <div className="page-heading">
        <div>
          <h1>错题</h1>
          <p>
            最近一次答错且尚未纠正的题目。勾选后点「加入记忆曲线」进入间隔复习；「加入日历计划」仅钉日期。
          </p>
        </div>
        <div style={{ display: 'flex', gap: 8 }}>
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
                { title: '题目', dataIndex: 'stem', render: (stem: string) => <Typography.Text ellipsis={{ showTooltip: true }}>{stem}</Typography.Text> },
                { title: '类型', dataIndex: 'type', width: 100, render: (_: unknown, row: Question) => questionTypeLabel(row.type) },
                { title: '章节', dataIndex: 'chapter', width: 160, render: (chapter?: string) => chapter || '未分类' },
                {
                  title: '操作',
                  width: 260,
                  render: (_: unknown, row: Question) => (
                    <div style={{ display: 'flex', gap: 4, flexWrap: 'wrap' }}>
                      <Button type="text" onClick={() => navigate(`/quiz?questionIds=${row.id}`)}>练习</Button>
                      <Button
                        type="text"
                        loading={enrollMutation.isPending}
                        onClick={() => enrollOne(row.id)}
                      >
                        记忆曲线
                      </Button>
                      <Button type="text" onClick={() => openPlanForRows([row])}>日历计划</Button>
                    </div>
                  )
                }
              ]}
            />
          ) : (
            <Empty icon={<XCircle size={34} />} description="还没有错题，完成一次练习后会自动统计。" />
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
