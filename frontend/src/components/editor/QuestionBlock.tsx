import { NodeViewWrapper, type NodeViewProps } from '@tiptap/react';
import type { Question, QuestionOption } from '../../lib/types';
import { MarkdownContent } from '../markdown/MarkdownRenderer';

function snapshotQuestion(attrs: Record<string, unknown>): Partial<Question> {
  const snapshot = attrs.snapshot;
  if (snapshot && typeof snapshot === 'object') return snapshot as Partial<Question>;
  return { id: Number(attrs.questionId) };
}

export function QuestionBlock({ node }: NodeViewProps): JSX.Element {
  const question = snapshotQuestion(node.attrs as Record<string, unknown>);
  const options = Array.isArray(question.options) ? question.options as QuestionOption[] : [];
  return <NodeViewWrapper className="question-block" contentEditable={false} data-question-block="true">
    <div className="question-block-header"><span>题目快照</span><span>#{question.id ?? node.attrs.questionId}</span></div>
    <MarkdownContent className="question-block-stem" value={question.stem || '原题目已删除，仅保留题块快照。'} />
    {options.length ? <div className="question-block-options">{options.map((option) => <div key={option.key}><strong>{option.key}.</strong><MarkdownContent inline value={option.text} /></div>)}</div> : null}
    {question.answer ? <div className="question-block-analysis"><strong>答案：{question.answer}</strong>{question.analysis ? <MarkdownContent value={question.analysis} /> : null}</div> : null}
  </NodeViewWrapper>;
}
