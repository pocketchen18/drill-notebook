import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { RenderResult } from 'mermaid';
import { renderMermaid } from './mermaidRender';

const { render } = vi.hoisted(() => ({ render: vi.fn() }));
vi.mock('mermaid', () => ({ default: { render } }));

const result: RenderResult = { svg: '<svg><text>valid</text></svg>', diagramType: 'flowchart' };

beforeEach(() => { render.mockReset(); });

describe('renderMermaid temporary DOM ownership', () => {
  it('uses a connected, measurable but invisible host and cleans it after success', async () => {
    render.mockImplementation(async (id: string, code: string, host: HTMLElement) => {
      expect(id).toBe('owned-success');
      expect(code).toBe('flowchart TD\n A-->B');
      expect(host.parentElement).toBe(document.body);
      expect(host).toHaveAttribute('aria-hidden', 'true');
      expect(host.style.position).toBe('fixed');
      expect(host.style.visibility).toBe('hidden');
      expect(host.style.display).not.toBe('none');
      host.innerHTML = result.svg;
      return result;
    });
    expect(await renderMermaid('owned-success', 'flowchart TD\n A-->B')).toBe(result);
    expect(document.querySelector('[data-mermaid-render-host]')).toBeNull();
    expect(document.querySelector('svg')).toBeNull();
  });

  it.each(['sync', 'async'])('cleans library leftovers after a %s failure without touching unrelated SVGs', async (kind) => {
    const unrelated = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
    document.body.append(unrelated);
    const error = new Error('invalid diagram');
    render.mockImplementation((_id: string, _code: string, host: HTMLElement) => {
      host.innerHTML = '<svg class="error-icon"><text>Syntax error in text</text></svg>';
      if (kind === 'sync') throw error;
      return Promise.reject(error);
    });
    try {
      await expect(renderMermaid('owned-failure', 'invalid')).rejects.toBe(error);
      expect(document.querySelector('[data-mermaid-render-host]')).toBeNull();
      expect(document.querySelector('.error-icon')).toBeNull();
      expect(unrelated.isConnected).toBe(true);
    } finally {
      unrelated.remove();
    }
  });

  it('removes body-level fallback nodes created by Mermaid error rendering', async () => {
    const id = 'owned-body-failure';
    const existing = document.createElement('div');
    existing.id = id;
    document.body.append(existing);
    const error = new Error('draw failure');
    render.mockImplementation(() => {
      const wrapper = document.createElement('div');
      wrapper.id = `d${id}`;
      const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
      svg.id = id;
      wrapper.append(svg);
      document.body.append(wrapper);
      const iframe = document.createElement('iframe');
      iframe.id = `i${id}`;
      document.body.append(iframe);
      return Promise.reject(error);
    });
    try {
      await expect(renderMermaid(id, 'invalid')).rejects.toBe(error);
      expect(document.getElementById(`d${id}`)).toBeNull();
      expect(document.getElementById(`i${id}`)).toBeNull();
      // A pre-existing application node is not owned by this render call.
      expect(document.getElementById(id)).toBe(existing);
    } finally {
      existing.remove();
    }
  });

  it('only removes the settled render host while another diagram is pending', async () => {
    let rejectFirst!: (error: Error) => void;
    let resolveSecond!: (value: RenderResult) => void;
    render
      .mockImplementationOnce(() => new Promise<RenderResult>((_resolve, reject) => { rejectFirst = reject; }))
      .mockImplementationOnce(() => new Promise<RenderResult>((resolve) => { resolveSecond = resolve; }));
    const first = renderMermaid('owned-first', 'invalid');
    const second = renderMermaid('owned-second', 'valid');
    const hosts = document.querySelectorAll('[data-mermaid-render-host]');
    expect(hosts).toHaveLength(2);
    rejectFirst(new Error('invalid'));
    await expect(first).rejects.toThrow('invalid');
    expect(hosts[0].isConnected).toBe(false);
    expect(hosts[1].isConnected).toBe(true);
    resolveSecond(result);
    expect(await second).toBe(result);
    expect(document.querySelector('[data-mermaid-render-host]')).toBeNull();
  });
});
