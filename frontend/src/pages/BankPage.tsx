import { useEffect, useRef, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Button, Checkbox, Empty, Input, Message, Modal, Popconfirm, Space, Spin, Tag, Typography } from '@arco-design/web-react';
import { BookOpenText, Edit3, FileUp, Plus, RefreshCw, Trash2 } from 'lucide-react';
import { del, get, post, put } from '../lib/api';
import type { Bank, Question } from '../lib/types';
import { useNavigate } from 'react-router-dom';
import { MarkdownContent } from '../components/markdown/MarkdownRenderer';
import { ExportActions } from '../components/ExportActions';
import { questionExportDocument } from '../lib/export';
import { QuestionEditorModal } from '../components/QuestionEditorModal';
import { questionTypeColor, questionTypeLabel } from '../lib/quiz';

const { Text } = Typography;

function PageHeading({ onCreate, onImport, importing }: { onCreate: () => void; onImport: () => void; importing: boolean }): JSX.Element {
  return (
    <div className="page-heading">
      <div>
        <h1>题库</h1>
        <p>导入 Markdown 题库，管理题目并进入练习。</p>
      </div>
      <Space>
        <Button icon={<FileUp size={16} />} loading={importing} onClick={onImport}>导入 Markdown</Button>
        <Button type="primary" icon={<Plus size={16} />} onClick={onCreate}>新建题库</Button>
      </Space>
    </div>
  );
}

export function BankPage(): JSX.Element {
  const queryClient = useQueryClient();
  const navigate = useNavigate();
  const fileInput = useRef<HTMLInputElement>(null);
  const [selectedId, setSelectedId] = useState<number>();
  const [createVisible, setCreateVisible] = useState(false);
  const [newName, setNewName] = useState('');
  const [selectedQuestionIds, setSelectedQuestionIds] = useState<number[]>([]);
  const [questionEditorVisible, setQuestionEditorVisible] = useState(false);
  const [editingQuestion, setEditingQuestion] = useState<Question>();
  const banksQuery = useQuery({ queryKey: ['banks'], queryFn: () => get<Bank[]>('/api/banks') });
  const questionsQuery = useQuery({
    queryKey: ['questions', selectedId],
    queryFn: () => get<Question[]>(`/api/banks/${selectedId}/questions`),
    enabled: selectedId !== undefined
  });

  useEffect(() => {
    if (selectedId === undefined && banksQuery.data?.length) setSelectedId(banksQuery.data[0].id);
  }, [banksQuery.data, selectedId]);
  useEffect(() => { setSelectedQuestionIds([]); }, [selectedId]);
  useEffect(() => {
    if (!questionsQuery.data) return;
    const available = new Set(questionsQuery.data.map((question) => question.id));
    setSelectedQuestionIds((ids) => ids.filter((id) => available.has(id)));
  }, [questionsQuery.data]);

  const invalidate = () => {
    void queryClient.invalidateQueries({ queryKey: ['banks'] });
    void queryClient.invalidateQueries({ queryKey: ['questions', selectedId] });
  };

  const createMutation = useMutation({
    mutationFn: () => post<Bank>('/api/banks', { name: newName.trim(), description: '', sourceType: 'markdown' }),
    onSuccess: (bank) => {
      setCreateVisible(false);
      setNewName('');
      setSelectedId(bank.id);
      invalidate();
      Message.success('题库已创建');
    },
    onError: (error) => Message.error(error.message)
  });

  const deleteMutation = useMutation({
    mutationFn: (id: number) => del<void>(`/api/banks/${id}`),
    onSuccess: () => {
      setSelectedId(undefined);
      invalidate();
      Message.success('题库已删除');
    },
    onError: (error) => Message.error(error.message)
  });

  const renameMutation = useMutation({
    mutationFn: ({ id, name }: { id: number; name: string }) => put<Bank>(`/api/banks/${id}`, { name }),
    onSuccess: () => { invalidate(); Message.success('题库名称已更新'); },
    onError: (error) => Message.error(error.message)
  });

  const importMutation = useMutation({
    mutationFn: (content: string) => post<{ imported: number; skipped: number; failed: number; errors?: string[] }>(`/api/banks/${selectedId}/import/markdown`, { content }),
    onSuccess: (result) => {
      invalidate();
      Message.success(`导入完成：新增 ${result.imported}，跳过 ${result.skipped}，失败 ${result.failed}`);
      if (result.errors?.length) Message.warning(result.errors.slice(0, 2).join('；'));
    },
    onError: (error) => Message.error(error.message)
  });

  const deleteQuestionMutation = useMutation({
    mutationFn: (id: number) => del<void>(`/api/questions/${id}`),
    onSuccess: () => { invalidate(); Message.success('题目已删除'); },
    onError: (error) => Message.error(error.message)
  });

  const openImport = async (): Promise<void> => {
    if (!selectedId) {
      Message.warning('请先创建或选择题库');
      return;
    }
    if (window.api) {
      const result = await window.api.dialog.openTextFile();
      if (!result.canceled && result.content !== undefined) importMutation.mutate(result.content);
      return;
    }
    fileInput.current?.click();
  };

  const onFallbackFile = (event: React.ChangeEvent<HTMLInputElement>): void => {
    const file = event.target.files?.[0];
    if (!file) return;
    void file.text().then((content) => importMutation.mutate(content));
    event.target.value = '';
  };

  const selectedBank = banksQuery.data?.find((bank) => bank.id === selectedId);
  const questions = questionsQuery.data ?? [];
  const selectedQuestions = questions.filter((question) => selectedQuestionIds.includes(question.id));
  const allSelected = questions.length > 0 && selectedQuestionIds.length === questions.length;

  return (
    <main className="page">
      <PageHeading onCreate={() => setCreateVisible(true)} onImport={() => void openImport()} importing={importMutation.isPending} />
      <input ref={fileInput} type="file" accept=".md,.markdown,.txt" hidden onChange={onFallbackFile} />
      <div className="content-grid">
        <section className="panel">
          <div className="panel-header">
            <h2>我的题库</h2>
            <Button type="text" icon={<RefreshCw size={15} />} onClick={() => void banksQuery.refetch()} aria-label="刷新题库" />
          </div>
          <div className="panel-body">
            {banksQuery.isLoading ? <Spin /> : banksQuery.data?.length ? (
              <div className="bank-list">
                {banksQuery.data.map((bank) => (
                  <div key={bank.id} className={`bank-item ${selectedId === bank.id ? 'selected' : ''}`} onClick={() => setSelectedId(bank.id)} role="button" tabIndex={0} onKeyDown={(event) => { if (event.key === 'Enter') setSelectedId(bank.id); }}>
                    <div style={{ minWidth: 0 }}>
                      <div className="bank-item-title">{bank.name}</div>
                      <div className="bank-item-meta">{bank.questionCount ?? 0} 道题</div>
                    </div>
                    <Space size={2}>
                      <Button type="text" size="mini" onClick={(event) => { event.stopPropagation(); const name = window.prompt('题库名称', bank.name); if (name?.trim()) renameMutation.mutate({ id: bank.id, name: name.trim() }); }}>改名</Button>
                      <Popconfirm title="删除这个题库？" content="题库中的题目和答题记录也会删除。" onOk={() => deleteMutation.mutate(bank.id)}>
                        <Button type="text" status="danger" size="mini" icon={<Trash2 size={14} />} onClick={(event) => event.stopPropagation()} aria-label={`删除${bank.name}`} />
                      </Popconfirm>
                    </Space>
                  </div>
                ))}
              </div>
            ) : <Empty description="还没有题库" />}
          </div>
        </section>
        <section className="panel">
          <div className="panel-header">
            <div>
              <h2>{selectedBank?.name ?? '选择题库'}</h2>
              {selectedBank && <Text type="secondary">支持单选、多选、填空、判断和解答题，重复导入会自动跳过。</Text>}
            </div>
            {selectedBank && <Space>
              <ExportActions count={selectedQuestions.length} document={() => questionExportDocument(`${selectedBank.name} · 题库`, selectedQuestions)} />
              <Button icon={<Plus size={15} />} onClick={() => { setEditingQuestion(undefined); setQuestionEditorVisible(true); }}>新建题目</Button>
              <Button type="primary" onClick={() => navigate(`/quiz?bankId=${selectedBank.id}`)}>开始练习</Button>
            </Space>}
          </div>
          <div className="panel-body">
            {questionsQuery.isLoading ? <Spin /> : questions.length ? (
              <div className="question-list">
                <div className="selection-toolbar">
                  <Checkbox checked={allSelected} indeterminate={selectedQuestionIds.length > 0 && !allSelected} onChange={(checked) => setSelectedQuestionIds(checked ? questions.map((question) => question.id) : [])}>全选当前题库</Checkbox>
                </div>
                {questions.map((question) => (
                  <div className={`question-row ${selectedQuestionIds.includes(question.id) ? 'is-export-selected' : ''}`} key={question.id}>
                    <div className="question-row-top">
                      <div className="selection-line"><Checkbox aria-label={`选择题目：${question.stem}`} checked={selectedQuestionIds.includes(question.id)} onChange={(checked) => setSelectedQuestionIds((ids) => checked ? [...ids, question.id] : ids.filter((id) => id !== question.id))} /><MarkdownContent className="question-stem" value={question.stem} /></div>
                      <Space><Tag color={questionTypeColor(question.type)}>{questionTypeLabel(question.type)}</Tag><Button type="text" size="mini" icon={<Edit3 size={14} />} onClick={() => { setEditingQuestion(question); setQuestionEditorVisible(true); }} aria-label="编辑题目" /><Popconfirm title="删除这道题？" onOk={() => deleteQuestionMutation.mutate(question.id)}><Button type="text" status="danger" size="mini" icon={<Trash2 size={14} />} aria-label="删除题目" /></Popconfirm></Space>
                    </div>
                    <div className="question-options">{question.options.map((option) => <div className="question-option" key={option.key}><strong>{option.key}.</strong><MarkdownContent inline value={option.text} /></div>)}</div>
                    {question.chapter && <Text type="secondary">章节：{question.chapter}</Text>}
                  </div>
                ))}
              </div>
            ) : <div className="empty-state"><div><BookOpenText size={30} /><p>导入 Markdown 题库后，题目会显示在这里。</p></div></div>}
          </div>
        </section>
      </div>
      <Modal title="新建题库" visible={createVisible} onCancel={() => setCreateVisible(false)} onOk={() => { if (!newName.trim()) { Message.warning('请输入题库名称'); return; } createMutation.mutate(); }} confirmLoading={createMutation.isPending} autoFocus={false}>
        <Input autoFocus placeholder="例如：Java 基础" value={newName} onChange={setNewName} onPressEnter={() => { if (newName.trim()) createMutation.mutate(); }} />
      </Modal>
      {selectedId && <QuestionEditorModal bankId={selectedId} question={editingQuestion} visible={questionEditorVisible} onClose={() => setQuestionEditorVisible(false)} onSaved={invalidate} />}
    </main>
  );
}
