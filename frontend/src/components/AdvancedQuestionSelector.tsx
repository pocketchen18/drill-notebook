import { useMemo, useState } from 'react';
import { Button, Checkbox, Empty, Input, Select, Space, Tag, Typography } from '@arco-design/web-react';
import { ArrowDown, ArrowUp, ListRestart, Shuffle } from 'lucide-react';
import type { Question } from '../lib/types';
import { filterQuestions, moveId, shuffleIds } from '../lib/study';
import { questionTypeLabel } from '../lib/quiz';

interface AdvancedQuestionSelectorProps {
  questions: Question[];
  selectedIds: number[];
  onChange: (ids: number[]) => void;
}

export function AdvancedQuestionSelector({ questions, selectedIds, onChange }: AdvancedQuestionSelectorProps): JSX.Element {
  const [search, setSearch] = useState('');
  const [types, setTypes] = useState<string[]>([]);
  const [chapters, setChapters] = useState<string[]>([]);
  const [tags, setTags] = useState<string[]>([]);
  const chapterOptions = useMemo(() => [...new Set(questions.map((question) => question.chapter).filter((value): value is string => Boolean(value)))].sort(), [questions]);
  const tagOptions = useMemo(() => [...new Set(questions.flatMap((question) => question.tags ?? []))].sort(), [questions]);
  const filtered = useMemo(() => filterQuestions(questions, { search, types, chapters, tags }), [chapters, questions, search, tags, types]);
  const selectedQuestions = selectedIds.map((id) => questions.find((question) => question.id === id)).filter((question): question is Question => Boolean(question));

  const selectFiltered = (): void => {
    const filteredIds = filtered.map((question) => question.id);
    onChange([...selectedIds.filter((id) => !filteredIds.includes(id)), ...filteredIds]);
  };

  return <div className="advanced-selector">
    <div className="advanced-filters">
      <Input allowClear placeholder="搜索题干、章节或标签" value={search} onChange={setSearch} />
      <Select mode="multiple" allowClear placeholder="题型" value={types} onChange={(value) => setTypes(value.map(String))}>
        <Select.Option value="single">单选题</Select.Option><Select.Option value="multiple">多选题</Select.Option><Select.Option value="fill">填空题</Select.Option><Select.Option value="true_false">判断题</Select.Option><Select.Option value="essay">解答题</Select.Option>
      </Select>
      <Select mode="multiple" allowClear placeholder="章节" value={chapters} onChange={(value) => setChapters(value.map(String))}>{chapterOptions.map((chapter) => <Select.Option key={chapter} value={chapter}>{chapter}</Select.Option>)}</Select>
      <Select mode="multiple" allowClear placeholder="知识标签" value={tags} onChange={(value) => setTags(value.map(String))}>{tagOptions.map((tag) => <Select.Option key={tag} value={tag}>{tag}</Select.Option>)}</Select>
    </div>
    <div className="selection-toolbar">
      <Space wrap>
        <Button size="small" onClick={selectFiltered}>全选筛选结果（{filtered.length}）</Button>
        <Button size="small" onClick={() => onChange(selectedIds.filter((id) => !filtered.some((question) => question.id === id)))}>取消筛选结果</Button>
        <Button size="small" icon={<Shuffle size={14} />} disabled={selectedIds.length < 2} onClick={() => onChange(shuffleIds(selectedIds))}>随机重排</Button>
        <Button size="small" icon={<ListRestart size={14} />} onClick={() => onChange(questions.map((question) => question.id))}>恢复题库顺序</Button>
      </Space>
      <Typography.Text type="secondary">已选 {selectedQuestions.length} / {questions.length}</Typography.Text>
    </div>
    <div className="advanced-selector-grid">
      <div className="selector-list">
        {filtered.length ? filtered.map((question) => <label className={`selector-question ${selectedIds.includes(question.id) ? 'selected' : ''}`} key={question.id}>
          <Checkbox checked={selectedIds.includes(question.id)} onChange={(checked) => onChange(checked ? [...selectedIds, question.id] : selectedIds.filter((id) => id !== question.id))} />
          <span><span className="selector-question-title">{question.stem}</span><span className="selector-question-meta">{questionTypeLabel(question.type)} · {question.chapter || '未分类'} {(question.tags ?? []).map((tag) => <Tag size="small" key={tag}>{tag}</Tag>)}</span></span>
        </label>) : <Empty description="没有匹配题目" />}
      </div>
      <div className="selector-order">
        <div className="selector-order-title">会话顺序</div>
        {selectedQuestions.map((question, index) => <div className="order-item" key={question.id}>
          <span className="order-index">{index + 1}</span><span title={question.stem}>{question.stem}</span>
          <Space size={2}><Button type="text" size="mini" icon={<ArrowUp size={13} />} disabled={index === 0} onClick={() => onChange(moveId(selectedIds, question.id, -1))} aria-label="上移" /><Button type="text" size="mini" icon={<ArrowDown size={13} />} disabled={index === selectedQuestions.length - 1} onClick={() => onChange(moveId(selectedIds, question.id, 1))} aria-label="下移" /></Space>
        </div>)}
      </div>
    </div>
  </div>;
}
