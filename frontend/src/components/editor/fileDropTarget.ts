import type { Editor } from '@tiptap/core';
import type { Transaction } from '@tiptap/pm/state';

export interface FileDropTarget {
  insertBlock: (type: string, attrs: Record<string, unknown>) => void;
  dispose: () => void;
}

/** 上传异步完成前跟踪鼠标落点；不借用当前选区，用户移动光标不会改变文件的插入位置。 */
export function captureFileDropTarget(editor: Editor, coords: { left: number; top: number }): FileDropTarget | null {
  const hit = editor.view.posAtCoords(coords);
  if (!hit) return null;
  let position = hit.pos;
  let inserting = false;
  let disposed = false;
  const mapPosition = ({ transaction }: { transaction: Transaction }): void => {
    if (!inserting) position = transaction.mapping.map(position, 1);
  };
  const dispose = (): void => {
    if (disposed) return;
    disposed = true;
    editor.off('transaction', mapPosition);
    editor.off('destroy', dispose);
  };
  editor.on('transaction', mapPosition);
  editor.on('destroy', dispose);

  return {
    dispose,
    insertBlock(type, attrs) {
      if (disposed || editor.isDestroyed) return;
      // 本次插入会拆分段落；后续文件跟随新建的尾段落，不再对旧落点做重复映射。
      inserting = true;
      try {
        editor.chain().focus()
          .insertContentAt(position, [{ type, attrs }, { type: 'paragraph' }])
          .command(({ tr }) => { position = tr.selection.to; return true; })
          .run();
      } finally {
        inserting = false;
      }
    }
  };
}
