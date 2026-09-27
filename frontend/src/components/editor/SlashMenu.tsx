import { useEffect, useMemo, useRef, useState } from 'react';
import { useEditorState, type Editor } from '@tiptap/react';
import { FloatingLayer, editorChromeBottom, type AnchorRect } from './floating';
import { closeSlashMenu, getSlashState, type SlashCommandStorage } from './SlashCommand';
import { BLOCK_KIND_OPTIONS, filterCommands, slashCommands, type CommandContext, type EditorCommand } from './commandCatalog';

const BASIC_IDS = new Set<string>(BLOCK_KIND_OPTIONS.map((option) => option.kind));

function caretAnchor(editor: Editor, position: number): AnchorRect | null {
  try {
    const { top, bottom, left, right } = editor.view.coordsAtPos(position);
    return { top, bottom, left, right };
  } catch {
    return null;
  }
}

export function SlashMenu({ editor, context }: { editor: Editor; context: CommandContext }): JSX.Element | null {
  const slash = useEditorState({ editor, selector: ({ editor: current }) => getSlashState(current.state) });
  const [activeIndex, setActiveIndex] = useState(0);
  const listRef = useRef<HTMLDivElement | null>(null);
  const contextRef = useRef(context);
  contextRef.current = context;

  const items = useMemo(() => {
    if (!slash.active) return [];
    return filterCommands(slashCommands().filter((command) => command.isAvailable?.(editor) ?? true), slash.query);
  }, [editor, slash.active, slash.query]);
  const itemsRef = useRef<EditorCommand[]>(items);
  itemsRef.current = items;
  const activeRef = useRef(activeIndex);
  activeRef.current = Math.min(activeIndex, Math.max(items.length - 1, 0));

  useEffect(() => setActiveIndex(0), [slash.from, slash.query]);

  const run = (command: EditorCommand): void => {
    const state = getSlashState(editor.state);
    if (!state.active) return;
    const range = { from: state.from, to: editor.state.selection.from };
    // “删除 /查询词”只作为命令链的起点，与插入/转换同一事务提交：分两步提交时，中间态会经 onChange
    // 回传到笔记页，再在插入事务中途（节点视图 flushSync）被当成外部内容 setContent，吞掉新插入的块。
    command.run(editor, { ...contextRef.current, chain: () => editor.chain().focus().deleteRange(range) });
  };
  const runRef = useRef(run);
  runRef.current = run;

  useEffect(() => {
    const storage = editor.storage.slashCommand as SlashCommandStorage | undefined;
    if (!storage) return undefined;
    storage.onKeyDown = (event) => {
      const list = itemsRef.current;
      if (!list.length) return false;
      if (event.key === 'ArrowDown') {
        setActiveIndex((index) => (index + 1) % list.length);
        return true;
      }
      if (event.key === 'ArrowUp') {
        setActiveIndex((index) => (index - 1 + list.length) % list.length);
        return true;
      }
      if (event.key === 'Enter' || event.key === 'Tab') {
        const command = list[activeRef.current] ?? list[0];
        if (command) runRef.current(command);
        return true;
      }
      return false;
    };
    return () => {
      storage.onKeyDown = null;
    };
  }, [editor]);

  useEffect(() => {
    const active = listRef.current?.querySelector<HTMLElement>('[aria-selected="true"]');
    active?.scrollIntoView?.({ block: 'nearest' });
  }, [activeIndex, items]);

  const open = slash.active && items.length > 0;
  const grouped = !slash.query;
  const current = activeRef.current;

  const renderItem = (command: EditorCommand, index: number): JSX.Element => {
    const Icon = command.icon;
    return (
      <button
        key={command.id}
        type="button"
        role="option"
        id={`editor-slash-${command.id}`}
        aria-selected={index === current}
        className={`editor-slash__item${index === current ? ' is-active' : ''}`}
        tabIndex={-1}
        onMouseEnter={() => setActiveIndex(index)}
        onClick={() => run(command)}
      >
        <span className="editor-slash__icon" aria-hidden="true"><Icon size={17} /></span>
        <span className="editor-slash__label">{command.label}</span>
        {command.shortcut ? <kbd className="editor-slash__shortcut">{command.shortcut}</kbd> : null}
      </button>
    );
  };

  return (
    <FloatingLayer
      open={open}
      getAnchor={() => caretAnchor(editor, slash.from)}
      getBoundaryTop={() => editorChromeBottom(editor.view.dom)}
      placement="bottom"
      align="start"
      offset={6}
      className="editor-slash"
      layerRef={listRef}
      role="listbox"
      ariaLabel="插入块"
      // 保持光标在编辑器内，继续输入即可筛选。
      onMouseDown={(event) => event.preventDefault()}
    >
      {grouped ? (
        <>
          <div className="editor-slash__group" aria-hidden="true">基础块</div>
          {items.map((command, index) => (BASIC_IDS.has(command.id) ? renderItem(command, index) : null))}
          <div className="editor-slash__group" aria-hidden="true">插入</div>
          {items.map((command, index) => (BASIC_IDS.has(command.id) ? null : renderItem(command, index)))}
        </>
      ) : items.map(renderItem)}
    </FloatingLayer>
  );
}

export { closeSlashMenu };
