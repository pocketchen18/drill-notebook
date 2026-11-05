import { useEffect, useRef, useState, type ReactNode } from 'react';
import { useEditorState, type Editor } from '@tiptap/react';
import { getMarkRange, posToDOMRect } from '@tiptap/core';
import { NodeSelection } from '@tiptap/pm/state';
import { CellSelection } from '@tiptap/pm/tables';
import {
  Baseline,
  Bold,
  Check,
  ChevronDown,
  Code,
  Copy,
  ExternalLink,
  Italic,
  Link as LinkIcon,
  Pencil,
  Radical,
  RemoveFormatting,
  Strikethrough,
  Subscript,
  Superscript,
  Trash2,
  Underline,
  Unlink
} from 'lucide-react';
import { FloatingLayer, editorChromeBottom, type AnchorRect } from './floating';
import { MenuPopover, type MenuSection } from './MenuPopover';
import { BLOCK_KIND_LABEL, BLOCK_KIND_OPTIONS } from './commandCatalog';
import { blockAtSelection, currentBlockKind, duplicateBlock, editSelectedMathBlock, turnInto, type BlockKind, type NotebookKeymapStorage } from './blockCommands';
import { TEXT_COLORS, TEXT_COLOR_LABELS, isTextColor, type TextColor } from './textColors';
import { canOpenExternally, normalizeHref, openExternalLink } from './links';

interface BubbleSnapshot {
  readonly focused: boolean;
  readonly editable: boolean;
  readonly empty: boolean;
  readonly nodeSelection: boolean;
  readonly blockMath: boolean;
  readonly cellSelection: boolean;
  readonly inCode: boolean;
  readonly singleBlock: boolean;
  readonly inlineMath: boolean;
  readonly mixedInlineMath: boolean;
  readonly from: number;
  readonly to: number;
  readonly blockKind: BlockKind;
  readonly bold: boolean;
  readonly italic: boolean;
  readonly underline: boolean;
  readonly strike: boolean;
  readonly code: boolean;
  readonly superscript: boolean;
  readonly subscript: boolean;
  readonly textColor: TextColor | null;
  readonly highlight: TextColor | 'default' | null;
  readonly linkHref: string | null;
}

/** 选区正好落在一个行内公式上时返回它，用于「再点一次取消公式」。 */
function inlineMathInSelection(editor: Editor): { from: number; to: number; latex: string } | null {
  const { from, to } = editor.state.selection;
  const hits: { from: number; to: number; latex: string }[] = [];
  editor.state.doc.nodesBetween(from, to, (node, pos) => {
    if (node.type.name !== 'mathInline') return true;
    hits.push({ from: pos, to: pos + node.nodeSize, latex: String(node.attrs.latex ?? '') });
    return false;
  });
  const only = hits.length === 1 ? hits[0] : null;
  return only && only.from === from && only.to === to ? only : null;
}

function selectionContainsInlineMath(editor: Editor): boolean {
  const { from, to } = editor.state.selection;
  let found = false;
  editor.state.doc.nodesBetween(from, to, (node) => {
    if (node.type.name === 'mathInline') found = true;
    return !found;
  });
  return found;
}

function snapshotOf(editor: Editor): BubbleSnapshot {
  const { selection } = editor.state;
  const textColor = editor.getAttributes('textColor').color;
  const highlightColor = editor.getAttributes('highlight').color;
  const inlineMath = inlineMathInSelection(editor) !== null;
  return {
    focused: editor.isFocused,
    editable: editor.isEditable,
    empty: selection.empty,
    nodeSelection: selection instanceof NodeSelection,
    blockMath: selection instanceof NodeSelection && selection.node.type.name === 'mathBlock',
    cellSelection: selection instanceof CellSelection,
    inCode: editor.isActive('codeBlock'),
    singleBlock: selection.$from.sameParent(selection.$to),
    inlineMath,
    mixedInlineMath: !inlineMath && selectionContainsInlineMath(editor),
    from: selection.from,
    to: selection.to,
    blockKind: currentBlockKind(editor),
    bold: editor.isActive('bold'),
    italic: editor.isActive('italic'),
    underline: editor.isActive('underline'),
    strike: editor.isActive('strike'),
    code: editor.isActive('code'),
    superscript: editor.isActive('superscript'),
    subscript: editor.isActive('subscript'),
    textColor: isTextColor(textColor) ? textColor : null,
    highlight: editor.isActive('highlight') ? (isTextColor(highlightColor) ? highlightColor : 'default') : null,
    linkHref: editor.isActive('link') ? String(editor.getAttributes('link').href ?? '') : null
  };
}

function selectionAnchor(editor: Editor): AnchorRect | null {
  const { view, state } = editor;
  const { selection } = state;
  try {
    if (selection instanceof NodeSelection) {
      const dom = view.nodeDOM(selection.from);
      return dom instanceof HTMLElement ? dom.getBoundingClientRect() : null;
    }
    return posToDOMRect(view, selection.from, selection.to);
  } catch {
    return null;
  }
}

function linkAnchor(editor: Editor): AnchorRect | null {
  const { state, view } = editor;
  const type = state.schema.marks.link;
  const range = type ? getMarkRange(state.selection.$from, type) : undefined;
  if (!range) return null;
  try {
    return posToDOMRect(view, range.from, range.to);
  } catch {
    return null;
  }
}

interface BubbleButtonProps {
  label: string;
  icon: ReactNode;
  onClick: () => void;
  active?: boolean;
  disabled?: boolean;
  danger?: boolean;
  title?: string;
  shortcut?: string;
}

function BubbleButton({ label, icon, onClick, active, disabled = false, danger = false, title, shortcut }: BubbleButtonProps): JSX.Element {
  return (
    <button
      type="button"
      className={[active ? 'is-active' : '', danger ? 'is-danger' : ''].filter(Boolean).join(' ') || undefined}
      onClick={onClick}
      disabled={disabled}
      aria-label={label}
      aria-pressed={active}
      title={shortcut ? `${title ?? label}（${shortcut}）` : (title ?? label)}
    >
      {icon}
    </button>
  );
}

function BubbleMenuButton({ label, ariaLabel, children, sections, className = '' }: { label: string; ariaLabel: string; children: ReactNode; sections: readonly MenuSection[]; className?: string }): JSX.Element {
  const [open, setOpen] = useState(false);
  const triggerRef = useRef<HTMLButtonElement>(null);
  return (
    <>
      <button
        ref={triggerRef}
        type="button"
        className={`editor-bubble__menu-trigger ${className}`.trim()}
        onClick={() => setOpen((value) => !value)}
        aria-label={ariaLabel}
        aria-haspopup="menu"
        aria-expanded={open}
        title={label}
      >
        {children}
        <ChevronDown size={12} aria-hidden="true" />
      </button>
      <MenuPopover
        open={open}
        onClose={() => setOpen(false)}
        getAnchor={() => triggerRef.current?.getBoundingClientRect() ?? null}
        sections={sections}
        ariaLabel={ariaLabel}
        triggerRef={triggerRef}
        keepEditorFocus
      />
    </>
  );
}

const Divider = (): JSX.Element => <span className="editor-bubble__divider" aria-hidden="true" />;

/**
 * 选区浮条、链接编辑与链接卡片。浮条在页面滚动时跟随选区，
 * 只在选区离开视口、焦点离开编辑器或鼠标仍在拖选时隐藏。
 */
export function EditorBubbleMenu({ editor }: { editor: Editor }): JSX.Element | null {
  const snapshot = useEditorState({ editor, selector: ({ editor: current }) => snapshotOf(current) });
  const [mode, setMode] = useState<'format' | 'link'>('format');
  const [linkDraft, setLinkDraft] = useState('');
  const [selecting, setSelecting] = useState(false);
  const bubbleRef = useRef<HTMLDivElement | null>(null);
  const linkInputRef = useRef<HTMLInputElement>(null);
  const [focusTarget, setFocusTarget] = useState<EventTarget | null>(() => document.activeElement);

  useEffect(() => {
    // 公式预览是 contenteditable=false 的可聚焦按钮，持焦时 editor.isFocused 为 false。
    // 监听真实焦点边界，避免把仍在公式上的焦点误判为离开编辑器，也不让源码框弹出块浮窗。
    const onFocusIn = (event: FocusEvent): void => setFocusTarget(event.target);
    const onFocusOut = (event: FocusEvent): void => setFocusTarget(event.relatedTarget);
    document.addEventListener('focusin', onFocusIn);
    document.addEventListener('focusout', onFocusOut);
    return () => {
      document.removeEventListener('focusin', onFocusIn);
      document.removeEventListener('focusout', onFocusOut);
    };
  }, []);

  useEffect(() => {
    const storage = editor.storage.notebookKeymap as NotebookKeymapStorage | undefined;
    if (!storage) return undefined;
    const open = (): void => {
      setLinkDraft(editor.isActive('link') ? String(editor.getAttributes('link').href ?? '') : '');
      setMode('link');
    };
    storage.openLinkEditor = open;
    return () => {
      if (storage.openLinkEditor === open) storage.openLinkEditor = null;
    };
  }, [editor]);

  useEffect(() => {
    const dom = editor.view.dom;
    const onDown = (event: MouseEvent): void => {
      if (event.button === 0) setSelecting(true);
    };
    const onUp = (): void => setSelecting(false);
    dom.addEventListener('mousedown', onDown);
    window.addEventListener('mouseup', onUp);
    return () => {
      dom.removeEventListener('mousedown', onDown);
      window.removeEventListener('mouseup', onUp);
    };
  }, [editor]);

  useEffect(() => {
    if (mode !== 'link') return undefined;
    const frame = window.requestAnimationFrame(() => {
      linkInputRef.current?.focus();
      linkInputRef.current?.select();
    });
    const onPointerDown = (event: MouseEvent): void => {
      if (bubbleRef.current?.contains(event.target as Node)) return;
      setMode('format');
    };
    document.addEventListener('mousedown', onPointerDown, true);
    return () => {
      window.cancelAnimationFrame(frame);
      document.removeEventListener('mousedown', onPointerDown, true);
    };
  }, [mode]);

  const chain = () => editor.chain().focus();
  const openLinkEditor = (): void => (editor.storage.notebookKeymap as NotebookKeymapStorage | undefined)?.openLinkEditor?.();
  const closeLinkEditor = (): void => {
    setMode('format');
    editor.commands.focus();
  };
  const applyLink = (): void => {
    const href = normalizeHref(linkDraft);
    if (!href) chain().extendMarkRange('link').unsetLink().run();
    else if (editor.state.selection.empty && !editor.isActive('link')) {
      chain().insertContent({ type: 'text', text: href, marks: [{ type: 'link', attrs: { href } }] }).run();
    } else {
      chain().extendMarkRange('link').setLink({ href }).run();
    }
    setMode('format');
  };
  const removeLink = (): void => {
    chain().extendMarkRange('link').unsetLink().run();
    setMode('format');
  };
  const toInlineMath = (): void => {
    const existing = inlineMathInSelection(editor);
    if (existing) {
      // 再点一次 = 取消行内公式，把 LaTeX 源码还原成普通文字
      if (existing.latex.trim()) chain().insertContentAt({ from: existing.from, to: existing.to }, { type: 'text', text: existing.latex }).run();
      else chain().deleteRange({ from: existing.from, to: existing.to }).run();
      return;
    }
    // 混合选区中的公式源码存于 attrs，textBetween 会跳过它；不能用剩余文字覆盖整个选区。
    if (selectionContainsInlineMath(editor)) return;
    const { from, to } = editor.state.selection;
    const latex = editor.state.doc.textBetween(from, to, ' ').trim();
    // 选区内没有文字（整块图片或公式被选中）时直接放过，否则会拿空公式盖掉原内容
    if (!latex) return;
    chain().insertContentAt({ from, to }, { type: 'mathInline', attrs: { latex } }).run();
  };

  const selectedMathDOM = snapshot.blockMath ? editor.view.nodeDOM(snapshot.from) : null;
  const mathPreviewFocused = focusTarget instanceof Element && focusTarget.matches('.math-block.is-preview')
    && selectedMathDOM?.contains(focusTarget);
  const mathToolsFocused = focusTarget instanceof Node && bubbleRef.current?.contains(focusTarget);
  const selectionFocused = snapshot.focused || Boolean(snapshot.blockMath && (mathPreviewFocused || mathToolsFocused));
  const formatVisible = mode === 'format' && snapshot.editable && selectionFocused && !snapshot.empty
    && !snapshot.cellSelection && !snapshot.inCode && !selecting;
  const linkCardVisible = mode === 'format' && snapshot.focused && snapshot.empty && snapshot.linkHref !== null && !selecting;

  const kindSections: MenuSection[] = [{
    key: 'kinds',
    title: '转换为',
    items: BLOCK_KIND_OPTIONS.map((option) => ({
      key: option.kind,
      label: option.label,
      icon: <option.icon size={16} />,
      shortcut: option.shortcut,
      active: option.kind === snapshot.blockKind,
      onSelect: () => { turnInto(editor, option.kind); }
    }))
  }];
  const colorSections: MenuSection[] = [
    {
      key: 'text',
      title: '文字颜色',
      layout: 'grid',
      items: [
        { key: 'text-default', label: '默认文字颜色', icon: <span className="editor-color-swatch">A</span>, active: snapshot.textColor === null, onSelect: () => { chain().unsetTextColor().run(); } },
        ...TEXT_COLORS.map((color) => ({
          key: `text-${color}`,
          label: `${TEXT_COLOR_LABELS[color]}文字`,
          icon: <span className="editor-color-swatch" data-text-color={color}>A</span>,
          active: snapshot.textColor === color,
          onSelect: () => { chain().setTextColor(color).run(); }
        }))
      ]
    },
    {
      key: 'background',
      title: '背景颜色',
      layout: 'grid',
      items: [
        { key: 'bg-none', label: '无背景', icon: <span className="editor-color-swatch is-none" />, active: snapshot.highlight === null, onSelect: () => { chain().unsetHighlight().run(); } },
        ...TEXT_COLORS.map((color) => ({
          key: `bg-${color}`,
          label: `${TEXT_COLOR_LABELS[color]}背景`,
          icon: <span className="editor-color-swatch is-bg" data-color={color} />,
          active: snapshot.highlight === color || (color === 'yellow' && snapshot.highlight === 'default'),
          onSelect: () => { chain().setHighlight({ color }).run(); }
        }))
      ]
    }
  ];

  const href = snapshot.linkHref ?? '';

  return (
    <>
      <FloatingLayer
        open={formatVisible || mode === 'link'}
        getAnchor={() => selectionAnchor(editor)}
        getBoundaryTop={() => editorChromeBottom(editor.view.dom)}
        placement="top"
        align="center"
        hideWhenDetached={mode === 'format'}
        className="editor-bubble"
        role="toolbar"
        ariaLabel={mode === 'link' ? '编辑链接' : snapshot.nodeSelection ? '选中块操作' : '选中文本格式'}
        layerRef={bubbleRef}
        // 按钮不能抢走编辑器焦点，否则选区会消失。
        onMouseDown={(event) => {
          if (!(event.target instanceof HTMLInputElement)) event.preventDefault();
        }}
      >
        {mode === 'link' ? (
          <form className="editor-bubble__link" onSubmit={(event) => { event.preventDefault(); applyLink(); }}>
            <LinkIcon size={15} aria-hidden="true" />
            <input
              ref={linkInputRef}
              className="editor-bubble__link-input"
              value={linkDraft}
              onChange={(event) => setLinkDraft(event.target.value)}
              onKeyDown={(event) => {
                if (event.key === 'Escape') {
                  event.preventDefault();
                  event.stopPropagation();
                  closeLinkEditor();
                }
              }}
              placeholder="输入或粘贴链接"
              aria-label="链接地址"
              spellCheck={false}
            />
            <button type="submit" aria-label="应用链接" title="应用（Enter）"><Check size={15} /></button>
            {snapshot.linkHref !== null ? <button type="button" aria-label="移除链接" title="移除链接" onClick={removeLink}><Unlink size={15} /></button> : null}
          </form>
        ) : snapshot.nodeSelection ? (
          <>
            {snapshot.blockMath ? <BubbleButton label="编辑公式" icon={<Pencil size={15} />} shortcut="Enter" onClick={() => { editSelectedMathBlock(editor); }} /> : null}
            <BubbleButton label="复制副本" icon={<Copy size={15} />} shortcut="Ctrl+D" onClick={() => { duplicateBlock(editor, blockAtSelection(editor.state)); }} />
            <BubbleButton label="删除" icon={<Trash2 size={15} />} danger title="删除选中块" onClick={() => { chain().deleteSelection().run(); }} />
          </>
        ) : (
          <>
            <BubbleMenuButton label="块类型" ariaLabel={`块类型：${BLOCK_KIND_LABEL[snapshot.blockKind]}`} sections={kindSections} className="has-label">
              <span className="editor-bubble__kind">{BLOCK_KIND_LABEL[snapshot.blockKind]}</span>
            </BubbleMenuButton>
            <Divider />
            <BubbleButton label="加粗" icon={<Bold size={15} />} active={snapshot.bold} shortcut="Ctrl+B" onClick={() => { chain().toggleBold().run(); }} />
            <BubbleButton label="斜体" icon={<Italic size={15} />} active={snapshot.italic} shortcut="Ctrl+I" onClick={() => { chain().toggleItalic().run(); }} />
            <BubbleButton label="下划线" icon={<Underline size={15} />} active={snapshot.underline} shortcut="Ctrl+U" onClick={() => { chain().toggleUnderline().run(); }} />
            <BubbleButton label="删除线" icon={<Strikethrough size={15} />} active={snapshot.strike} shortcut="Ctrl+Shift+S" onClick={() => { chain().toggleStrike().run(); }} />
            <BubbleButton label="行内代码" icon={<Code size={15} />} active={snapshot.code} shortcut="Ctrl+E" onClick={() => { chain().toggleCode().run(); }} />
            <Divider />
            <BubbleButton label="链接" icon={<LinkIcon size={15} />} active={snapshot.linkHref !== null} shortcut="Ctrl+K" onClick={openLinkEditor} />
            <BubbleMenuButton label="文字与背景颜色" ariaLabel="颜色" sections={colorSections}>
              <Baseline size={15} aria-hidden="true" />
            </BubbleMenuButton>
            <BubbleButton label="上标" icon={<Superscript size={15} />} active={snapshot.superscript} shortcut="Ctrl+." onClick={() => { chain().toggleSuperscript().run(); }} />
            <BubbleButton label="下标" icon={<Subscript size={15} />} active={snapshot.subscript} shortcut="Ctrl+," onClick={() => { chain().toggleSubscript().run(); }} />
            <BubbleButton label="转为行内公式" icon={<Radical size={15} />} active={snapshot.inlineMath} disabled={!snapshot.singleBlock || snapshot.mixedInlineMath} title={snapshot.mixedInlineMath ? '请仅选择普通文字，或单独选择一个公式进行还原' : undefined} onClick={toInlineMath} />
            <BubbleButton label="清除格式" icon={<RemoveFormatting size={15} />} shortcut="Ctrl+\" onClick={() => { chain().unsetAllMarks().run(); }} />
            <Divider />
            <BubbleButton label="删除" icon={<Trash2 size={15} />} danger title="删除选中内容" onClick={() => { chain().deleteSelection().run(); }} />
          </>
        )}
      </FloatingLayer>
      <FloatingLayer
        open={linkCardVisible}
        getAnchor={() => linkAnchor(editor)}
        getBoundaryTop={() => editorChromeBottom(editor.view.dom)}
        placement="bottom"
        align="start"
        hideWhenDetached
        className="editor-link-card"
        role="group"
        ariaLabel="链接"
        onMouseDown={(event) => event.preventDefault()}
      >
        <span className="editor-link-card__url" title={href}>{href}</span>
        <button type="button" aria-label="打开链接" title="在浏览器打开（Ctrl+点击）" disabled={!canOpenExternally(href)} onClick={() => openExternalLink(href)}><ExternalLink size={14} /></button>
        <button type="button" aria-label="编辑链接" title="编辑链接（Ctrl+K）" onClick={openLinkEditor}><Pencil size={14} /></button>
        <button type="button" aria-label="移除链接" title="移除链接" onClick={removeLink}><Unlink size={14} /></button>
      </FloatingLayer>
    </>
  );
}
