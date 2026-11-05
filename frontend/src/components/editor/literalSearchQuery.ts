import { Fragment, Slice } from '@tiptap/pm/model';
import type { EditorState } from '@tiptap/pm/state';
import { SearchQuery, type SearchResult } from 'prosemirror-search';

/** 普通文本查找替换：库的 literal 只关闭反斜杠解析，仍会解释 $1 / $&，因此单独覆盖替换切片生成。 */
export class LiteralSearchQuery extends SearchQuery {
  constructor(config: { search: string; caseSensitive?: boolean; replace?: string }) {
    super({ ...config, literal: true, regexp: false });
  }

  override getReplacements(state: EditorState, { from, to }: SearchResult): { from: number; to: number; insert: Slice }[] {
    const marks = state.doc.resolve(from).marksAcross(state.doc.resolve(to));
    const content = this.replace ? Fragment.from(state.schema.text(this.replace, marks)) : Fragment.empty;
    return [{ from, to, insert: new Slice(content, 0, 0) }];
  }
}
