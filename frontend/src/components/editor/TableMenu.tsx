import { useRef, useState, type ReactNode } from 'react';
import { useEditorState, type Editor } from '@tiptap/react';
import type { EditorState } from '@tiptap/pm/state';
import { CellSelection } from '@tiptap/pm/tables';
import {
  BetweenHorizontalEnd,
  BetweenHorizontalStart,
  BetweenVerticalEnd,
  BetweenVerticalStart,
  Ellipsis,
  PanelTop,
  TableCellsMerge,
  TableCellsSplit,
  Trash2
} from 'lucide-react';
import { FloatingLayer, editorChromeBottom } from './floating';
import { MenuPopover, type MenuSection } from './MenuPopover';

function tablePosition(state: EditorState): number | null {
  const { $from } = state.selection;
  for (let depth = $from.depth; depth > 0; depth -= 1) {
    if ($from.node(depth).type.name === 'table') return $from.before(depth);
  }
  return null;
}

function TableButton({ label, icon, onClick, active }: { label: string; icon: ReactNode; onClick: () => void; active?: boolean }): JSX.Element {
  return (
    <button type="button" className={active ? 'is-active' : undefined} onClick={onClick} aria-label={label} aria-pressed={active} title={label}>
      {icon}
    </button>
  );
}

/** 光标所在表格上方的行列操作条。 */
export function TableMenu({ editor }: { editor: Editor }): JSX.Element | null {
  const snapshot = useEditorState({
    editor,
    selector: ({ editor: current }) => {
      const { state } = current;
      const position = tablePosition(state);
      const table = position === null ? null : state.doc.nodeAt(position);
      const cellSelection = state.selection instanceof CellSelection;
      return {
        position,
        focused: current.isFocused,
        textSelection: !state.selection.empty && !cellSelection,
        headerRow: table?.firstChild?.firstChild?.type.name === 'tableHeader',
        canMerge: position !== null && current.can().mergeCells(),
        canSplit: position !== null && current.can().splitCell()
      };
    }
  });
  const [moreOpen, setMoreOpen] = useState(false);
  const moreRef = useRef<HTMLButtonElement>(null);

  const visible = snapshot.position !== null && (snapshot.focused || moreOpen) && !snapshot.textSelection;
  const chain = () => editor.chain().focus();
  const moreSections: MenuSection[] = [{
    key: 'delete',
    items: [
      { key: 'row', label: '删除行', icon: <Trash2 size={16} />, danger: true, onSelect: () => { chain().deleteRow().run(); } },
      { key: 'column', label: '删除列', icon: <Trash2 size={16} />, danger: true, onSelect: () => { chain().deleteColumn().run(); } },
      { key: 'table', label: '删除表格', icon: <Trash2 size={16} />, danger: true, onSelect: () => { chain().deleteTable().run(); } }
    ]
  }];

  return (
    <FloatingLayer
      open={visible}
      getAnchor={() => {
        if (snapshot.position === null) return null;
        const dom = editor.view.nodeDOM(snapshot.position);
        return dom instanceof HTMLElement ? dom.getBoundingClientRect() : null;
      }}
      placement="top"
      align="start"
      hideWhenDetached
      getBoundaryTop={() => editorChromeBottom(editor.view.dom)}
      className="editor-table-menu"
      role="toolbar"
      ariaLabel="表格操作"
      onMouseDown={(event) => event.preventDefault()}
    >
      <TableButton label="上方插入行" icon={<BetweenHorizontalStart size={15} />} onClick={() => { chain().addRowBefore().run(); }} />
      <TableButton label="下方插入行" icon={<BetweenHorizontalEnd size={15} />} onClick={() => { chain().addRowAfter().run(); }} />
      <TableButton label="左侧插入列" icon={<BetweenVerticalStart size={15} />} onClick={() => { chain().addColumnBefore().run(); }} />
      <TableButton label="右侧插入列" icon={<BetweenVerticalEnd size={15} />} onClick={() => { chain().addColumnAfter().run(); }} />
      <span className="editor-bubble__divider" aria-hidden="true" />
      <TableButton label="表头行" icon={<PanelTop size={15} />} active={snapshot.headerRow} onClick={() => { chain().toggleHeaderRow().run(); }} />
      {snapshot.canMerge ? <TableButton label="合并单元格" icon={<TableCellsMerge size={15} />} onClick={() => { chain().mergeCells().run(); }} /> : null}
      {snapshot.canSplit ? <TableButton label="拆分单元格" icon={<TableCellsSplit size={15} />} onClick={() => { chain().splitCell().run(); }} /> : null}
      <button
        ref={moreRef}
        type="button"
        onClick={() => setMoreOpen((value) => !value)}
        aria-label="更多表格操作"
        aria-haspopup="menu"
        aria-expanded={moreOpen}
        title="更多表格操作"
      >
        <Ellipsis size={15} />
      </button>
      <MenuPopover
        open={moreOpen}
        onClose={() => setMoreOpen(false)}
        getAnchor={() => moreRef.current?.getBoundingClientRect() ?? null}
        sections={moreSections}
        ariaLabel="更多表格操作"
        triggerRef={moreRef}
        keepEditorFocus
        align="end"
      />
    </FloatingLayer>
  );
}
