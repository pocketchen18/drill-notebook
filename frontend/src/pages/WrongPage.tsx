import { useEffect, useMemo, useState } from 'react';
import { useMutation, useQuery } from '@tanstack/react-query';
import { Button, Empty, Message, Spin, Table, Tag, Typography } from '@arco-design/web-react';
import { BrainCircuit, RotateCcw, Sparkles, XCircle } from 'lucide-react';
import { useNavigate } from 'react-router-dom';
import { get } from '../lib/api';
import type { Question } from '../lib/types';
import { useUiStore } from '../stores/uiStore';
import { questionsToMarkdown } from '../lib/aiContext';
import { useRegisterPageContext } from '../hooks/useRegisterPageContext';
import { ExportActions } from '../components/ExportActions';
import { questionExportDocument } from '../lib/export';
import { questionTypeLabel } from '../lib/quiz';
import { enrollItems } from '../lib/review';

/** Stable empty array - never allocate a new [] for missing query data. */
const EMPTY_QUESTIONS: Question[] = [];

export function WrongPage(): JSX.Element {
  const navigate = useNavigate();
  const setAiOpen = useUiStore((state) => state.setAiOpen);
  const query = useQuery({ queryKey: ['wrong'], queryFn: () => get<Question[]>('/api/quiz/wrong') });
  const rows = query.data ?? EMPTY_QUESTIONS;
  const [selectedIds, setSelectedIds] = useState<number[]>([]);
  const selectedRows = rows.filter((row) => selectedIds.includes(row.id));
  const enrollMutation = useMutation({
    mutationFn: () => enrollItems('question', selectedIds),
    onSuccess: (result) => {
      const enrolled = result.filter((r) => r.status === 'enrolled').length;
      Message.success(`已将 ${enrolled} 道错题加入记忆曲线复习计划`);
    },
    onError: (error) => Message.error(error.message),
  });
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

  return (
    <main className="page">
      <div className="page-heading">
        <div>
          <h1>错题</h1>
          <p>这里显示每道题最近一次回答错误且尚未纠正的记录。</p>
        </div>
        <div style={{ display: 'flex', gap: 8 }}>
          <ExportActions count={selectedRows.length} document={() => questionExportDocument('错题本', selectedRows)} />
          <Button icon={<BrainCircuit size={16} />} disabled={!selectedIds.length} loading={enrollMutation.isPending} onClick={() => enrollMutation.mutate()}>加入复习计划</Button>
          <Button icon={<Sparkles size={16} />} disabled={!rows.length} onClick={() => setAiOpen(true)}>AI 分析错题</Button>
          <Button type="primary" icon={<RotateCcw size={16} />} disabled={!rows.length} onClick={() => navigate(`/quiz?questionIds=${rows.map((row) => row.id).join(',')}`)}>再练一遍</Button>
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
                { title: '操作', width: 100, render: (_: unknown, row: Question) => <Button type="text" onClick={() => navigate(`/quiz?questionIds=${row.id}`)}>练习</Button> }
              ]}
            />
          ) : (
            <Empty icon={<XCircle size={34} />} description="还没有错题，完成一次练习后会自动统计。" />
          )}
        </div>
      </section>
    </main>
  );
}
