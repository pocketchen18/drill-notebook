import { useQuery } from '@tanstack/react-query';
import { Button, Empty, Spin, Table, Tag, Typography } from '@arco-design/web-react';
import { RotateCcw, XCircle } from 'lucide-react';
import { useNavigate } from 'react-router-dom';
import { get } from '../lib/api';
import type { Question } from '../lib/types';

export function WrongPage(): JSX.Element {
  const navigate = useNavigate();
  const query = useQuery({ queryKey: ['wrong'], queryFn: () => get<Question[]>('/api/quiz/wrong') });
  const rows = query.data ?? [];
  return <main className="page">
    <div className="page-heading">
      <div><h1>错题</h1><p>这里显示每道题最近一次回答错误且尚未纠正的记录。</p></div>
      <Button type="primary" icon={<RotateCcw size={16} />} disabled={!rows.length} onClick={() => navigate(`/quiz?questionIds=${rows.map((row) => row.id).join(',')}`)}>再练一遍</Button>
    </div>
    <section className="panel">
      <div className="panel-header"><h2>待巩固题目</h2><Tag color={rows.length ? 'red' : 'green'}>{rows.length} 道</Tag></div>
      <div className="panel-body">
        {query.isLoading ? <Spin /> : rows.length ? <Table rowKey="id" data={rows} pagination={false} columns={[
          { title: '题目', dataIndex: 'stem', render: (stem: string) => <Typography.Text ellipsis={{ showTooltip: true }}>{stem}</Typography.Text> },
          { title: '类型', dataIndex: 'type', width: 100, render: (type: string) => type === 'multiple' ? '多选' : '单选' },
          { title: '章节', dataIndex: 'chapter', width: 160, render: (chapter?: string) => chapter || '未分类' },
          { title: '操作', width: 100, render: (_: unknown, row: Question) => <Button type="text" onClick={() => navigate(`/quiz?questionIds=${row.id}`)}>练习</Button> }
        ]} /> : <Empty icon={<XCircle size={34} />} description="还没有错题，完成一次练习后会自动统计。" />}
      </div>
    </section>
  </main>;
}
