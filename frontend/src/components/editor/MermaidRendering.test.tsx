import { afterEach, describe, expect, it, vi } from 'vitest';
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import type { NodeViewProps } from '@tiptap/react';
import mermaid, { type RenderResult } from 'mermaid';
import { MermaidNode } from './MermaidNode';
import { MarkdownContent } from '../markdown/MarkdownRenderer';
import { ensureMermaidTheme } from '../../lib/mermaidTheme';

const temporaryMermaidNodes = '[id^="drill-mermaid-"], [id^="drill-markdown-mermaid-"], [data-mermaid-render-host]';

function nodeProps(code: string): NodeViewProps {
  return { node: { attrs: { code } }, selected: false, updateAttributes: () => {} } as unknown as NodeViewProps;
}

afterEach(() => {
  cleanup();
  // Failed pre-fix renders live outside React's root; keep this regression isolated.
  document.querySelectorAll(temporaryMermaidNodes).forEach((element) => element.remove());
  vi.restoreAllMocks();
});

describe('Mermaid syntax errors stay inside their block (real Mermaid)', () => {
  it('does not accumulate error SVGs when invalid diagram source changes', async () => {
    const { rerender } = render(<MermaidNode {...nodeProps('flowchart TD\n  A[')} />);
    for (const code of ['flowchart TD\n  A[', 'flowchart LR\n  B[', 'not-a-diagram']) {
      rerender(<MermaidNode {...nodeProps(code)} />);
      await waitFor(() => {
        expect(screen.getByText('Mermaid 语法无法解析')).toBeInTheDocument();
        expect(document.querySelectorAll(temporaryMermaidNodes)).toHaveLength(0);
        expect(document.querySelector('.error-icon, .error-text')).toBeNull();
      });
    }
  });

  it('stays clean when the page is scrolled after an invalid render', async () => {
    render(<MermaidNode {...nodeProps('flowchart TD\n  A[')} />);
    await screen.findByText('Mermaid 语法无法解析');
    fireEvent.scroll(window);
    fireEvent.scroll(document);
    expect(document.querySelectorAll(temporaryMermaidNodes)).toHaveLength(0);
    expect(document.querySelector('.error-icon, .error-text')).toBeNull();
  });

  it('keeps invalid Mermaid fences in Markdown without leaking error SVGs', async () => {
    const { container, rerender } = render(<MarkdownContent value={'```mermaid\nflowchart TD\n  A[\n```'} />);
    for (const code of ['flowchart TD\n  A[', 'not-a-diagram']) {
      rerender(<MarkdownContent value={`\`\`\`mermaid\n${code}\n\`\`\``} />);
      await waitFor(() => {
        expect(container.querySelector('pre')).toHaveClass('markdown-mermaid-error');
        expect(container.querySelector('code')).toHaveTextContent(code.replace(/\s+/g, ' '));
        expect(document.querySelectorAll(temporaryMermaidNodes)).toHaveLength(0);
        expect(document.querySelector('.error-icon, .error-text')).toBeNull();
      });
    }
  });

  it('still contains library errors if another caller resets the global Mermaid options', async () => {
    ensureMermaidTheme('light');
    const previousConfig = mermaid.mermaidAPI.getConfig();
    mermaid.initialize({ startOnLoad: false, securityLevel: 'strict', theme: 'neutral', suppressErrorRendering: false });
    try {
      render(<MermaidNode {...nodeProps('flowchart TD\n A[')} />);
      await waitFor(() => {
        expect(screen.getByText('Mermaid 语法无法解析')).toBeInTheDocument();
        expect(document.querySelectorAll(temporaryMermaidNodes)).toHaveLength(0);
        expect(document.querySelector('.error-icon')).toBeNull();
      });
    } finally {
      mermaid.initialize(previousConfig);
    }
  });
});

describe('Mermaid preview asynchronous lifecycle', () => {
  it('cleans a late failed render after its block unmounts', async () => {
    let rejectRender!: (error: Error) => void;
    vi.spyOn(mermaid, 'render').mockImplementation((_id, _code, host) => {
      host!.innerHTML = '<svg class="error-icon" />';
      return new Promise<RenderResult>((_resolve, reject) => { rejectRender = reject; });
    });
    const { unmount } = render(<MermaidNode {...nodeProps('invalid')} />);
    const host = document.querySelector('[data-mermaid-render-host]');
    unmount();
    expect(host?.isConnected).toBe(true);
    await act(async () => { rejectRender(new Error('late failure')); });
    expect(host?.isConnected).toBe(false);
    expect(document.querySelector('.error-icon')).toBeNull();
  });

  it('does not replace a current valid preview with a stale error', async () => {
    let rejectOld!: (error: Error) => void;
    let resolveCurrent!: (value: RenderResult) => void;
    vi.spyOn(mermaid, 'render')
      .mockImplementationOnce(() => new Promise<RenderResult>((_resolve, reject) => { rejectOld = reject; }))
      .mockImplementationOnce(() => new Promise<RenderResult>((resolve) => { resolveCurrent = resolve; }));
    const { rerender, container } = render(<MermaidNode {...nodeProps('invalid')} />);
    rerender(<MermaidNode {...nodeProps('flowchart TD\n A-->B')} />);
    await act(async () => { resolveCurrent({ svg: '<svg><text>current diagram</text></svg>', diagramType: 'flowchart' }); });
    await act(async () => { rejectOld(new Error('stale failure')); });
    expect(container.querySelector('.mermaid-rendered')).toHaveTextContent('current diagram');
    expect(screen.queryByText('Mermaid 语法无法解析')).toBeNull();
    expect(document.querySelectorAll(temporaryMermaidNodes)).toHaveLength(0);
  });

  it('does not replace a current error with a stale successful preview', async () => {
    let resolveOld!: (value: RenderResult) => void;
    vi.spyOn(mermaid, 'render')
      .mockImplementationOnce(() => new Promise<RenderResult>((resolve) => { resolveOld = resolve; }))
      .mockRejectedValueOnce(new Error('current failure'));
    const { rerender, container } = render(<MermaidNode {...nodeProps('flowchart TD\n A-->B')} />);
    rerender(<MermaidNode {...nodeProps('invalid')} />);
    await screen.findByText('Mermaid 语法无法解析');
    await act(async () => { resolveOld({ svg: '<svg><text>stale diagram</text></svg>', diagramType: 'flowchart' }); });
    expect(screen.getByText('Mermaid 语法无法解析')).toBeInTheDocument();
    expect(container.querySelector('.mermaid-rendered')).toBeNull();
    expect(document.querySelectorAll(temporaryMermaidNodes)).toHaveLength(0);
  });
});
