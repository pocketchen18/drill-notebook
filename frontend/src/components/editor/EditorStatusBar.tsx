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
 * A quiet, factual status line for long writing sessions. It deliberately
 * avoids claiming that a document is saved because persistence is owned by the
 * notebook page, not the editor component.
 */
export function EditorStatusBar({ editor, uploadingCount = 0 }: EditorStatusBarProps): JSX.Element {
  const transactionNumber = useEditorState({ editor, selector: ({ transactionNumber: number }) => number });
  const text = editor.getText();
  const characters = countCharacters(text);
  const blocks = editor.state.doc.childCount;

  return (
    <div className="editor-statusbar" role="status" aria-label="编辑器状态" aria-live={uploadingCount > 0 ? 'polite' : 'off'} data-editor-transaction={transactionNumber}>
      <span>{characters} 字符</span>
      <span aria-hidden="true">·</span>
      <span>{blocks} 个块</span>
      {uploadingCount > 0 ? (
        <span className="editor-statusbar__uploading">
          <LoaderCircle size={13} aria-hidden="true" />
          正在上传 {uploadingCount} 个附件
        </span>
      ) : null}
    </div>
  );
}
