import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import type { NodeViewProps } from '@tiptap/react';
import { MarkdownBlockNode } from './MarkdownBlock';
import { MathInlineNode, MathNode } from './MathNode';
import { MermaidNode } from './MermaidNode';
import { VideoBlockNode } from './VideoBlock';

afterEach(() => cleanup());

function nodeProps(attrs: Record<string, unknown>, updateAttributes = vi.fn()): NodeViewProps {
  return { node: { attrs }, selected: false, updateAttributes } as unknown as NodeViewProps;
}

describe('custom editor block cancellation', () => {
  it.each([
    ['Markdown', MarkdownBlockNode, { markdown: '**原内容**' }, '编辑 Markdown 内容', '.markdown-block.is-preview'],
    ['Mermaid', MermaidNode, { code: 'flowchart TD\n A-->B' }, '编辑 Mermaid 图表代码', '.mermaid-block.is-preview'],
    ['LaTeX', MathNode, { latex: 'E=mc^2' }, '编辑 LaTeX 公式', '.math-block.is-preview']
  ])('Escape cancels %s edits even when blur follows', async (_label, Component, attrs, inputLabel, previewSelector) => {
    const updateAttributes = vi.fn();
    const { container } = render(<Component {...nodeProps(attrs, updateAttributes)} />);
    fireEvent.click(container.querySelector(previewSelector)!);
    const input = await screen.findByRole('textbox', { name: inputLabel });
    fireEvent.change(input, { target: { value: 'temporary change' } });
    fireEvent.keyDown(input, { key: 'Escape' });
    fireEvent.blur(input);
    expect(updateAttributes).not.toHaveBeenCalled();
  });

  it('Escape cancels inline LaTeX instead of committing the stale draft', async () => {
    const updateAttributes = vi.fn();
    const { container } = render(<MathInlineNode {...nodeProps({ latex: 'x^2' }, updateAttributes)} />);
    fireEvent.click(container.querySelector('.math-inline.is-preview')!);
    const input = await screen.findByRole('textbox', { name: '编辑行内 LaTeX' });
    fireEvent.change(input, { target: { value: 'x^3' } });
    fireEvent.keyDown(input, { key: 'Escape' });
    fireEvent.blur(input);
    expect(updateAttributes).not.toHaveBeenCalled();
  });

  it.each([
    ['Markdown', MarkdownBlockNode, { markdown: '**原内容**' }, '编辑 Markdown 内容', '.markdown-block.is-preview'],
    ['Mermaid', MermaidNode, { code: 'flowchart TD\n A-->B' }, '编辑 Mermaid 图表代码', '.mermaid-block.is-preview'],
    ['LaTeX', MathNode, { latex: 'E=mc^2' }, '编辑 LaTeX 公式', '.math-block.is-preview']
  ])('Enter and Space activate %s preview blocks from the keyboard', async (_label, Component, attrs, inputLabel, previewSelector) => {
    const { container } = render(<Component {...nodeProps(attrs)} />);
    const preview = container.querySelector(previewSelector)!;
    fireEvent.keyDown(preview, { key: 'Enter' });
    expect(await screen.findByRole('textbox', { name: inputLabel })).toBeInTheDocument();
    cleanup();

    const second = render(<Component {...nodeProps(attrs)} />);
    fireEvent.keyDown(second.container.querySelector(previewSelector)!, { key: ' ' });
    expect(await screen.findByRole('textbox', { name: inputLabel })).toBeInTheDocument();
  });

  it('commits a draft on blur and the completion button does not submit twice', async () => {
    const updateAttributes = vi.fn();
    const { container } = render(<MarkdownBlockNode {...nodeProps({ markdown: '原内容' }, updateAttributes)} />);
    fireEvent.click(container.querySelector('.markdown-block.is-preview')!);
    const input = await screen.findByRole('textbox', { name: '编辑 Markdown 内容' });
    fireEvent.change(input, { target: { value: '失焦后保存' } });
    fireEvent.blur(input);
    expect(updateAttributes).toHaveBeenCalledTimes(1);
    expect(updateAttributes).toHaveBeenCalledWith({ markdown: '失焦后保存' });

    cleanup();
    updateAttributes.mockClear();
    const second = render(<MarkdownBlockNode {...nodeProps({ markdown: '原内容' }, updateAttributes)} />);
    fireEvent.click(second.container.querySelector('.markdown-block.is-preview')!);
    const secondInput = await screen.findByRole('textbox', { name: '编辑 Markdown 内容' });
    fireEvent.change(secondInput, { target: { value: '按钮后保存' } });
    const done = screen.getByRole('button', { name: '完成' });
    fireEvent.mouseDown(done);
    fireEvent.click(done);
    expect(updateAttributes).toHaveBeenCalledTimes(1);
    expect(updateAttributes).toHaveBeenCalledWith({ markdown: '按钮后保存' });
  });

  it('Escape cancels a video title edit even when the input blurs during removal', async () => {
    const updateAttributes = vi.fn();
    const { container } = render(<VideoBlockNode {...nodeProps({ videoType: 'url', url: 'https://example.com/video', title: '原标题', view: 'title' }, updateAttributes)} />);
    fireEvent.doubleClick(container.querySelector('.video-link-text')!);
    const input = await screen.findByRole('textbox');
    fireEvent.change(input, { target: { value: '临时标题' } });
    fireEvent.keyDown(input, { key: 'Escape' });
    fireEvent.blur(input);
    expect(updateAttributes).not.toHaveBeenCalled();
  });
});
