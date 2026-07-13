import { useEffect, useState } from 'react';
import { Form, Input, InputNumber, Message, Modal, Select } from '@arco-design/web-react';
import { post, put } from '../lib/api';
import type { Question, QuestionOption, QuestionType } from '../lib/types';

interface QuestionEditorModalProps {
  bankId: number;
  question?: Question;
  visible: boolean;
  onClose: () => void;
  onSaved: () => void;
}

function optionsText(options: QuestionOption[]): string { return options.map((option) => `${option.key}. ${option.text}`).join('\n'); }
function parseOptions(value: string): QuestionOption[] {
  return value.split('\n').map((line) => line.match(/^\s*([A-Za-z])[.)]\s*(.+)$/)).filter((match): match is RegExpMatchArray => Boolean(match)).map((match) => ({ key: match[1].toUpperCase(), text: match[2].trim() }));
}

export function QuestionEditorModal({ bankId, question, visible, onClose, onSaved }: QuestionEditorModalProps): JSX.Element {
  const [type, setType] = useState<QuestionType>('single');
  const [stem, setStem] = useState('');
  const [options, setOptions] = useState('A. \nB. \nC. \nD. ');
  const [answer, setAnswer] = useState('');
  const [analysis, setAnalysis] = useState('');
  const [chapter, setChapter] = useState('');
  const [tags, setTags] = useState('');
  const [difficulty, setDifficulty] = useState(3);
  const [saving, setSaving] = useState(false);

  useEffect(() => {
    if (!visible) return;
    setType(question?.type ?? 'single'); setStem(question?.stem ?? ''); setOptions(question ? optionsText(question.options) : 'A. \nB. \nC. \nD. '); setAnswer(question?.answer ?? ''); setAnalysis(question?.analysis ?? ''); setChapter(question?.chapter ?? ''); setTags((question?.tags ?? []).join(', ')); setDifficulty(question?.difficulty ?? 3);
  }, [question, visible]);

  const save = async (): Promise<void> => {
    const isChoice = type === 'single' || type === 'multiple';
    const parsedOptions = isChoice ? parseOptions(options) : [];
    if (!stem.trim()) { Message.warning('请填写题干'); return; }
    if (isChoice && parsedOptions.length < 2) { Message.warning('选择题至少需要两个有效选项（如 A. 内容）'); return; }
    if (type !== 'essay' && !answer.trim()) { Message.warning('请填写答案'); return; }
    setSaving(true);
    try {
      const normalizedAnswer = type === 'true_false' ? answer : (isChoice ? answer.trim().toUpperCase() : answer.trim());
      const payload = { type, stem: stem.trim(), options: parsedOptions, answer: normalizedAnswer, analysis: analysis.trim(), chapter: chapter.trim() || null, tags: tags.split(/[,，]/).map((item) => item.trim()).filter(Boolean), difficulty };
      if (question) await put(`/api/questions/${question.id}`, payload); else await post(`/api/banks/${bankId}/questions`, payload);
      Message.success(question ? '题目已更新' : '题目已创建'); onSaved(); onClose();
    } catch (error) { Message.error(error instanceof Error ? error.message : '题目保存失败'); }
    finally { setSaving(false); }
  };

  return <Modal title={question ? '编辑题目' : '新建题目'} visible={visible} onCancel={onClose} onOk={() => void save()} confirmLoading={saving} style={{ width: 720 }} autoFocus={false}>
    <Form layout="vertical">
      <Form.Item label="题型"><Select value={type} onChange={(value) => { const next = value as QuestionType; setType(next); if (next === 'true_false' && answer !== 'true' && answer !== 'false') setAnswer('true'); }}><Select.Option value="single">单选题</Select.Option><Select.Option value="multiple">多选题</Select.Option><Select.Option value="fill">填空题</Select.Option><Select.Option value="true_false">判断题</Select.Option><Select.Option value="essay">解答题</Select.Option></Select></Form.Item>
      <Form.Item label="题干" required><Input.TextArea value={stem} onChange={setStem} autoSize={{ minRows: 2, maxRows: 6 }} /></Form.Item>
      {(type === 'single' || type === 'multiple') && <Form.Item label="选项（每行使用 A. 内容）" required><Input.TextArea value={options} onChange={setOptions} autoSize={{ minRows: 4, maxRows: 10 }} /></Form.Item>}
      {type === 'true_false' ? <Form.Item label="答案" required><Select value={answer || 'true'} onChange={(value) => setAnswer(String(value))}><Select.Option value="true">正确</Select.Option><Select.Option value="false">错误</Select.Option></Select></Form.Item> : type === 'essay' ? <Form.Item label="参考答案（可选）"><Input.TextArea value={answer} onChange={setAnswer} placeholder="可填写供 AI 辅助判题和学习者复核的参考答案" autoSize={{ minRows: 3, maxRows: 10 }} /></Form.Item> : <Form.Item label="答案" required><Input value={answer} onChange={setAnswer} placeholder={type === 'multiple' ? '例如 A,C' : type === 'fill' ? '填写标准答案' : '例如 A'} /></Form.Item>}
      <Form.Item label="解析"><Input.TextArea value={analysis} onChange={setAnalysis} autoSize={{ minRows: 2, maxRows: 8 }} /></Form.Item>
      <div className="form-row"><Form.Item label="章节"><Input value={chapter} onChange={setChapter} /></Form.Item><Form.Item label="标签（逗号分隔）"><Input value={tags} onChange={setTags} /></Form.Item><Form.Item label="难度"><InputNumber min={1} max={5} value={difficulty} onChange={(value) => setDifficulty(Number(value))} /></Form.Item></div>
    </Form>
  </Modal>;
}
