import { NodeViewWrapper, type NodeViewProps } from '@tiptap/react';
import type { Question, QuestionOption } from '../../lib/types';
import { MarkdownContent } from '../markdown/MarkdownRenderer';
import { questionTypeLabel } from '../../lib/quiz';
import { BlockDragHandle, exitNodeSelection } from './EditorChrome';

function snapshotQuestion(attrs: Record<string, unknown>): Partial<Question> {
  const snapshot = attrs.snapshot;
  if (snapshot && typeof snapshot === 'object') return snapshot as Partial<Question>;
  return { id: Number(attrs.questionId) };
}

export function QuestionBlock({ node, selected, view, getPos }: NodeViewProps): JSX.Element {
  const question = snapshotQuestion(node.attrs as Record<string, unknown>);
  const options = Array.isArray(question.options) ? question.options as QuestionOption[] : [];
  return <NodeViewWrapper className={`question-block${selected ? ' is-selected' : ''}`} contentEditable={false} data-question-block="true" onClick={() => exitNodeSelection(view, getPos, node)}>
    <BlockDragHandle label="拖动题目块" />
    <div className="question-block-header"><span>题目快照 · {question.type ? questionTypeLabel(question.type) : '未知题型'}</span><span>#{question.id ?? node.attrs.questionId}</span></div>
    <MarkdownContent className="question-block-stem" value={question.stem || '原题目已删除，仅保留题块快照。'} />
    {options.length ? <div className="question-block-options">{options.map((option) => <div key={option.key}><strong>{option.key}.</strong><MarkdownContent inline value={option.text} /></div>)}</div> : null}
    {(question.answer || question.analysis) ? <div className="question-block-analysis">{question.answer ? <strong>{question.type === 'essay' ? '参考答案' : '答案'}：{question.type === 'true_false' ? question.answer === 'true' ? '正确' : '错误' : question.answer}</strong> : null}{question.analysis ? <MarkdownContent value={question.analysis} /> : null}</div> : null}
  </NodeViewWrapper>;
}
