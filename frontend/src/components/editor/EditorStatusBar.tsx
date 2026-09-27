import { useEditorState, type Editor } from '@tiptap/react';
import { LoaderCircle } from 'lucide-react';

export interface EditorStatusBarProps {
  editor: Editor;
  uploadingCount?: number;
}

function countCharacters(value: string): number {
  return Array.from(value.replace(/\s/g, '')).length;
}

/**
 * 长时间写作用的安静、纯事实的状态栏。刻意不显示“已保存”，
 * 因为保存由笔记页面负责，而不是编辑器组件。
 */
export function EditorStatusBar({ editor, uploadingCount = 0 }: EditorStatusBarProps): JSX.Element {
  const { characters, blocks, selected } = useEditorState({
    editor,
    selector: ({ editor: current }) => {
      const { doc, selection } = current.state;
      return {
        characters: countCharacters(current.getText()),
        blocks: doc.childCount,
        selected: selection.empty ? 0 : countCharacters(doc.textBetween(selection.from, selection.to, ' ', ' '))
      };
    }
  });

  return (
    <div className="editor-statusbar" role="status" aria-label="编辑器状态" aria-live={uploadingCount > 0 ? 'polite' : 'off'}>
      <span>{characters} 字符</span>
      <span aria-hidden="true">·</span>
      <span>{blocks} 个块</span>
      {selected > 0 ? (
        <>
          <span aria-hidden="true">·</span>
          <span>已选 {selected} 字</span>
        </>
      ) : null}
      {uploadingCount > 0 ? (
        <span className="editor-statusbar__uploading">
          <LoaderCircle size={13} aria-hidden="true" />
          正在上传 {uploadingCount} 个附件
        </span>
      ) : null}
    </div>
  );
}
