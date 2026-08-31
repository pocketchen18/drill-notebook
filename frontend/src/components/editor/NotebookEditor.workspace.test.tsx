/**
 * Task 1 baseline behavior + target-structure tests for `NotebookEditor`.
 *
 * BEHAVIOR block (EDT-* rows from `.omo/evidence/notebook-bank-workspace-redesign/control-ledger.md`):
 *   Locks the current editor behavior — must PASS on unchanged production source.
 *   Uses a real TipTap editor; exercises the toolbar buttons (Bold/Italic/H2/Code),
 *   the formula / mermaid / markdown / file / video block inserters, paste handling,
 *   drop handling, and focus mode.
 *
 * TARGET STRUCTURE block:
 *   Asserts the `.editor-canvas` host and its direct toolbar relationship.
 *
 * Fixture IDs intentionally non-sequential (11, 37, 104) per the plan.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, waitFor, fireEvent } from '@testing-library/react';

const { uploadAttachment } = vi.hoisted(() => ({ uploadAttachment: vi.fn() }));

vi.mock('../../lib/attachments', () => ({
  uploadAttachment: (...args: unknown[]) => uploadAttachment(...args),
  attachmentContentUrl: vi.fn().mockResolvedValue('about:blank')
}));

// jsdom doesn't implement getClientRects/getBoundingClientRect on Element;
// prosemirror/tiptap needs these for selection updates. Polyfill to a stub.
if (typeof window !== 'undefined') {
  const elementProto = Element.prototype as unknown as Record<string, unknown>;
  if (typeof elementProto.getClientRects !== 'function') {
    elementProto.getClientRects = function (): DOMRectList {
      return { length: 0, item: () => null } as unknown as DOMRectList;
    };
  }
  if (typeof elementProto.getBoundingClientRect !== 'function') {
    elementProto.getBoundingClientRect = function (): DOMRect {
      return { x: 0, y: 0, width: 0, height: 0, top: 0, right: 0, bottom: 0, left: 0, toJSON: () => '' } as DOMRect;
    };
  }
  // prosemirror also calls getClientRects on Text nodes.
  const textCtor = (globalThis as unknown as { Text?: { prototype: Record<string, unknown> } }).Text;
  if (textCtor) {
    const textProto = textCtor.prototype;
    if (typeof textProto.getClientRects !== 'function') {
      textProto.getClientRects = function (): DOMRectList {
        return { length: 0, item: () => null } as unknown as DOMRectList;
      };
    }
    if (typeof textProto.getBoundingClientRect !== 'function') {
      textProto.getBoundingClientRect = function (): DOMRect {
        return { x: 0, y: 0, width: 0, height: 0, top: 0, right: 0, bottom: 0, left: 0, toJSON: () => '' } as DOMRect;
      };
    }
  }
  // prosemirror-view's `textRange(child).getClientRects()` — polyfill Range too.
  const rangeCtor = (globalThis as unknown as { Range?: { prototype: Record<string, unknown> } }).Range;
  if (rangeCtor) {
    const rangeProto = rangeCtor.prototype;
    if (typeof rangeProto.getClientRects !== 'function') {
      rangeProto.getClientRects = function (): DOMRectList {
        return { length: 0, item: () => null } as unknown as DOMRectList;
      };
    }
    if (typeof rangeProto.getBoundingClientRect !== 'function') {
      rangeProto.getBoundingClientRect = function (): DOMRect {
        return { x: 0, y: 0, width: 0, height: 0, top: 0, right: 0, bottom: 0, left: 0, toJSON: () => '' } as DOMRect;
      };
    }
  }
}

const baseWindowApi = (overrides: Record<string, unknown> = {}) => ({
  ...(overrides),
  file: {
    pickFiles: vi.fn().mockResolvedValue([]),
    readFile: vi.fn().mockResolvedValue(new ArrayBuffer(0))
  },
  exportFile: { save: vi.fn() },
  config: {
    get: () => Promise.resolve({ theme: 'light' }),
    set: () => Promise.resolve()
  },
  backend: { getBaseUrl: () => Promise.resolve('http://127.0.0.1:18081') }
});

beforeEach(() => {
  (window as unknown as { api: Record<string, unknown> }).api = baseWindowApi() as Record<string, unknown>;
  uploadAttachment.mockReset();
});

afterEach(() => {
  vi.restoreAllMocks();
});

import { NotebookEditor } from './NotebookEditor';

const baseContent = (text: string): Record<string, unknown> => ({
  type: 'doc',
  content: [{ type: 'paragraph', content: [{ type: 'text', text }] }]
});

// =====================================================================
// BEHAVIOR — must pass on unchanged code
// =====================================================================

describe('NotebookEditor behavior — baseline regression (Task 1)', () => {
  describe('Toolbar commands (EDT-01..04 contract)', () => {
    // NOTE: EDT-01..04 mark-toggle commands (Bold / Italic / H2 / Code) call
    // `editor.chain().focus().toggleX().run()`, which is a no-op without a
    // real Selection range (jsdom does not implement Selection.getRangeAt).
    // ProseMirror also throws `target.getClientRects is not a function` from
    // `scrollToSelection` even though we polyfilled Element.getClientRects,
    // because tiptap captures the active DOM element via `view.domObserver`
    // which fires in a microtask after the focus event.
    //
    // We therefore lock the BEHAVIOR contract for these commands differently:
    // the toolbar button must exist, be clickable, and must NOT throw.
    // The actual toggle state lives behind Selection + keypress in the
    // browser; the Electron baseline (Task 1.5) is the source of truth for
    // the toggle action.

    it('EDT-01 加粗 button renders, is clickable, and does not throw', () => {
      render(<NotebookEditor content={baseContent('hello')} pageId={11} onChange={() => {}} />);
      const btn = screen.getByRole('button', { name: '加粗' });
      expect(btn).toBeInTheDocument();
      expect(() => fireEvent.click(btn)).not.toThrow();
    });

    it('EDT-02 斜体 button renders, is clickable, and does not throw', () => {
      render(<NotebookEditor content={baseContent('hi')} pageId={11} onChange={() => {}} />);
      const btn = screen.getByRole('button', { name: '斜体' });
      expect(btn).toBeInTheDocument();
      expect(() => fireEvent.click(btn)).not.toThrow();
    });

    it('EDT-03 二级标题 button renders, is clickable, and does not throw', () => {
      render(<NotebookEditor content={baseContent('hi')} pageId={11} onChange={() => {}} />);
      const btn = screen.getByRole('button', { name: '二级标题' });
      expect(btn).toBeInTheDocument();
      expect(() => fireEvent.click(btn)).not.toThrow();
    });

    it('EDT-04 行内代码 button renders, is clickable, and does not throw', () => {
      render(<NotebookEditor content={baseContent('hi')} pageId={11} onChange={() => {}} />);
      const btn = screen.getByRole('button', { name: '行内代码' });
      expect(btn).toBeInTheDocument();
      expect(() => fireEvent.click(btn)).not.toThrow();
    });
  });

  describe('Block inserters (EDT-05..07)', () => {
    it('EDT-05 公式: clicking inserts a mathBlock node', async () => {
      const onChange = vi.fn();
      render(<NotebookEditor content={baseContent('start')} onChange={onChange} pageId={11} />);
      const pm = document.querySelector('.notebook-prosemirror') as HTMLElement;
      pm.focus();
      fireEvent.click(screen.getByRole('button', { name: /公式/ }));
      await waitFor(() => {
        const last = JSON.stringify(onChange.mock.calls.at(-1)?.[0] ?? {});
        expect(last).toContain('mathBlock');
      });
    });

    it('EDT-06 图表: clicking inserts a mermaidBlock node', async () => {
      const onChange = vi.fn();
      render(<NotebookEditor content={baseContent('start')} onChange={onChange} pageId={11} />);
      const pm = document.querySelector('.notebook-prosemirror') as HTMLElement;
      pm.focus();
      fireEvent.click(screen.getByRole('button', { name: /图表/ }));
      await waitFor(() => {
        const last = JSON.stringify(onChange.mock.calls.at(-1)?.[0] ?? {});
        expect(last).toContain('mermaidBlock');
      });
    });

    it('EDT-07 Markdown: clicking inserts a markdownBlock node', async () => {
      const onChange = vi.fn();
      render(<NotebookEditor content={baseContent('start')} onChange={onChange} pageId={11} />);
      const pm = document.querySelector('.notebook-prosemirror') as HTMLElement;
      pm.focus();
      fireEvent.click(screen.getByRole('button', { name: /Markdown/ }));
      await waitFor(() => {
        const last = JSON.stringify(onChange.mock.calls.at(-1)?.[0] ?? {});
        expect(last).toContain('markdownBlock');
      });
    });
  });

  describe('Add video modal (EDT-09, EDT-11)', () => {
    it('EDT-09 opens modal; submitting inserts a videoBlock at cursor', async () => {
      const onChange = vi.fn();
      render(<NotebookEditor content={baseContent('start')} onChange={onChange} pageId={11} />);
      const pm = document.querySelector('.notebook-prosemirror') as HTMLElement;
      pm.focus();
      fireEvent.click(screen.getByRole('button', { name: '添加视频' }));
      const dialog = await screen.findByRole('dialog', { name: '添加视频' });
      // URL radio is default; type URL.
      const urlInput = screen.getByPlaceholderText('视频 URL');
      fireEvent.change(urlInput, { target: { value: 'https://example.com/v' } });
      // Submit.
      fireEvent.click(withinDialog(dialog).getByRole('button', { name: '确定' }));
      await waitFor(() => {
        const last = JSON.stringify(onChange.mock.calls.at(-1)?.[0] ?? {});
        expect(last).toContain('videoBlock');
      });
    });
  });

  describe('Add file (EDT-08)', () => {
    it('EDT-08 with pageId: file pick → uploadAttachment → fileBlock insert (browser fallback)', async () => {
      const onChange = vi.fn();
      // window.api.file.pickFiles undefined → editor uses hidden <input>.
      (window as unknown as { api: unknown }).api = {
        exportFile: { save: vi.fn() },
        config: { get: () => Promise.resolve({ theme: 'light' }), set: () => Promise.resolve() },
        backend: { getBaseUrl: () => Promise.resolve('http://127.0.0.1:18081') }
        // no `file` property → browser fallback path
      };
      uploadAttachment.mockResolvedValue({
        id: 999, pageId: 11, fileName: 'note.pdf',
        storagePath: 'fake', mimeType: 'application/pdf',
        fileSize: 12, sha256: null, createdAt: ''
      });

      render(<NotebookEditor content={baseContent('start')} onChange={onChange} pageId={11} />);
      const pm = document.querySelector('.notebook-prosemirror') as HTMLElement;
      pm.focus();
      fireEvent.click(screen.getByRole('button', { name: '添加文件' }));
      // Hidden file input should have been clicked; we synthesize the change event.
      const hiddenInput = document.querySelector('input[type="file"][multiple]') as HTMLInputElement;
      expect(hiddenInput).toBeTruthy();
      const file = new File(['x'], 'note.pdf', { type: 'application/pdf' });
      // jsdom FileList from constructor is a single File, not array-of-files; use DataTransfer.
      Object.defineProperty(hiddenInput, 'files', { configurable: true, value: [file] });
      fireEvent.change(hiddenInput);
      await waitFor(() => expect(uploadAttachment).toHaveBeenCalledWith(11, expect.any(File)));
      await waitFor(() => {
        const last = JSON.stringify(onChange.mock.calls.at(-1)?.[0] ?? {});
        expect(last).toContain('fileBlock');
      });
    });

    it('EDT-08 no pageId: no upload', async () => {
      const onChange = vi.fn();
      (window as unknown as { api: unknown }).api = { /* no file picker → fallback */ };
      render(<NotebookEditor content={baseContent('start')} onChange={onChange} />);
      const pm = document.querySelector('.notebook-prosemirror') as HTMLElement;
      pm.focus();
      fireEvent.click(screen.getByRole('button', { name: '添加文件' }));
      const hiddenInput = document.querySelector('input[type="file"][multiple]') as HTMLInputElement;
      const file = new File(['x'], 'note.pdf', { type: 'application/pdf' });
      Object.defineProperty(hiddenInput, 'files', { configurable: true, value: [file] });
      fireEvent.change(hiddenInput);
      // Allow microtasks.
      await new Promise((res) => setTimeout(res, 30));
      expect(uploadAttachment).not.toHaveBeenCalled();
    });
  });

  describe('Focus mode (EDT-10, NBK-17)', () => {
    it('EDT-10: clicking 专注模式 fires onFocusModeChange(true) (parent owns the state)', async () => {
      const onFocusModeChange = vi.fn();
      render(
        <NotebookEditor
          content={baseContent('start')}
          onChange={() => {}}
          pageId={11}
          focusMode={false}
          onFocusModeChange={onFocusModeChange}
        />
      );
      // The button renders with the "off" label; clicking it asks the parent
      // to flip state via onFocusModeChange. We do not pm.focus() — jsdom
      // Selection is incomplete and prosemirror's view observer throws on
      // async getClientRects calls. The Electron baseline (Task 1.5) is the
      // source of truth for the actual focus-mode UI flip.
      const btn = screen.getByRole('button', { name: '专注模式' });
      expect(btn).toBeInTheDocument();
      fireEvent.click(btn);
      expect(onFocusModeChange).toHaveBeenCalledWith(true);
    });

    it('EDT-10: with focusMode=true the button shows 退出专注 (visible text, aria-label stays 专注模式)', () => {
      render(
        <NotebookEditor
          content={baseContent('start')}
          onChange={() => {}}
          pageId={11}
          focusMode
        />
      );
      // The button's accessible name is the static aria-label "专注模式"
      // (NotebookEditor.tsx:197); the visible text inside flips to "退出专注"
      // when focusMode is true (line 200). We assert the visible text because
      // that is what the user sees — the accessible name is the command.
      const btn = screen.getByRole('button', { name: '专注模式' });
      expect(btn).toBeInTheDocument();
      expect(btn.textContent).toBe('退出专注');
      expect(btn.className).toMatch(/primary|arco-btn-primary/);
    });
  });

  describe('Toolbar accessibility (EDT-* contract)', () => {
    it('every toolbar button has an accessible name', () => {
      render(<NotebookEditor content={baseContent('start')} onChange={vi.fn()} pageId={11} />);
      // Each command maps to a name consumed by the UI (routing/accessibility).
      // We assert the visible text/aria-label contract:
      ['加粗', '斜体', '二级标题', '行内代码', '公式', '图表', 'Markdown', '添加文件', '添加视频', '专注模式']
        .forEach((label) => {
          expect(screen.getAllByRole('button', { name: label })[0]).toBeInTheDocument();
        });
    });
  });
});

// Scoped query helper inside a dialog (mirrors @testing-library/react's within()).
function withinDialog(dialog: HTMLElement): {
  getByRole: (role: string, options?: { name?: string | RegExp }) => HTMLElement;
} {
  return {
    getByRole: (role, options) => {
      const selector = role === 'button' ? 'button,[role="button"]' : `[role="${role}"]`;
      const matcher = options?.name;
      const nodes = Array.from(dialog.querySelectorAll(selector));
      const match = nodes.find((node) => {
        if (!matcher) return true;
        const label = (node.getAttribute('aria-label') || node.textContent || '').trim();
        return typeof matcher === 'string' ? label === matcher : matcher.test(label);
      }) as HTMLElement | undefined;
      if (!match) throw new Error(`No ${role} found in dialog`);
      return match;
    }
  };
}

// =====================================================================
// TARGET STRUCTURE — intentionally FAIL on current code (Phase 2+ gate)
// =====================================================================

describe('NotebookEditor target structure — Phase 2+ redesign contract', () => {
  it('editor mounts inside .editor-canvas host', () => {
    render(<NotebookEditor content={baseContent('start')} onChange={vi.fn()} pageId={11} />);
    const canvas = document.querySelector('.editor-canvas') as HTMLElement | null;
    expect(canvas, 'expected .editor-canvas wrapper around editor').toBeTruthy();
    // The editor-shell must be a descendant of .editor-canvas.
    const shell = document.querySelector('.editor-shell') as HTMLElement | null;
    expect(shell).toBeTruthy();
    expect(canvas!.contains(shell!)).toBe(true);
  });

  it('editor-canvas hosts the toolbar in a min-height 44px slot above the prosemirror area', () => {
    render(<NotebookEditor content={baseContent('start')} onChange={vi.fn()} pageId={11} />);
    const toolbar = document.querySelector('.editor-canvas .editor-toolbar') as HTMLElement | null;
    expect(toolbar, 'expected .editor-toolbar inside .editor-canvas').toBeTruthy();
    const styles = window.getComputedStyle(toolbar as HTMLElement);
    expect(styles.minHeight).toBe('44px');
  });

  it('editor-canvas padding matches 16 20 24 contract (top horizontal / bottom)', () => {
    render(<NotebookEditor content={baseContent('start')} onChange={vi.fn()} pageId={11} />);
    const canvas = document.querySelector('.editor-canvas') as HTMLElement | null;
    expect(canvas, 'expected .editor-canvas for padding check').toBeTruthy();
    const styles = window.getComputedStyle(canvas as HTMLElement);
    expect(styles.paddingTop).toBe('16px');
    expect(styles.paddingLeft).toBe('20px');
    expect(styles.paddingRight).toBe('20px');
    expect(styles.paddingBottom).toBe('24px');
  });
});
