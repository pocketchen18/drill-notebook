import { Extension } from '@tiptap/core';
import { Plugin, PluginKey, type EditorState, type Transaction } from '@tiptap/pm/state';
import { ReplaceStep } from '@tiptap/pm/transform';
import type { EditorView } from '@tiptap/pm/view';

export interface SlashState {
  readonly active: boolean;
  /** 触发字符在文档中的位置。 */
  readonly from: number;
  /** 触发字符之后输入的文字，用于筛选命令。 */
  readonly query: string;
}

export interface SlashCommandStorage {
  /** 由已挂载的菜单注册；返回 true 表示该按键已被菜单处理。 */
  onKeyDown: ((event: KeyboardEvent) => boolean) | null;
}

type SlashMeta = { type: 'close' };

const INACTIVE: SlashState = { active: false, from: -1, query: '' };
const MAX_QUERY_LENGTH = 24;
// 中文输入法下按斜杠键得到的是 `、`，它只在块首触发，避免与顿号冲突。
const TRIGGERS = new Set(['/', '／', '、']);

export const slashCommandKey = new PluginKey<SlashState>('slashCommand');

export function getSlashState(state: EditorState): SlashState {
  return slashCommandKey.getState(state) ?? INACTIVE;
}

export function closeSlashMenu(view: EditorView): void {
  if (!getSlashState(view.state).active) return;
  view.dispatch(view.state.tr.setMeta(slashCommandKey, { type: 'close' } satisfies SlashMeta));
}

function charBefore(state: EditorState, position: number, blockStart: number): string {
  return position > blockStart ? state.doc.textBetween(position - 1, position, '\n', '￼') : '';
}

/**
 * 只有“刚刚输入”的触发符才打开菜单：单次插入一个字符、位于普通文本块内、在块首或空白之后。
 * 光标移回旧文本不会重新弹出。
 */
function typedTrigger(tr: Transaction, state: EditorState): number | null {
  if (!tr.docChanged || tr.steps.length !== 1) return null;
  const uiEvent = tr.getMeta('uiEvent');
  if (tr.getMeta('paste') || uiEvent === 'paste' || uiEvent === 'drop') return null;
  const step = tr.steps[0];
  if (!(step instanceof ReplaceStep) || step.slice.size !== 1 || step.slice.content.childCount !== 1) return null;
  const inserted = step.slice.content.firstChild;
  const trigger = inserted?.isText ? inserted.text ?? '' : '';
  if (!TRIGGERS.has(trigger)) return null;

  const from = step.from;
  const { selection } = state;
  if (!selection.empty || selection.from !== from + 1) return null;
  const $after = state.doc.resolve(from + 1);
  if (!$after.parent.isTextblock || $after.parent.type.spec.code) return null;
  if ($after.marks().some((mark) => mark.type.spec.code)) return null;
  const blockStart = $after.start();
  const before = charBefore(state, from, blockStart);
  if (trigger === '、' ? from !== blockStart : !(before === '' || /\s/.test(before))) return null;
  return from;
}

function resolveActive(state: EditorState, from: number): SlashState | null {
  const { doc, selection } = state;
  if (!selection.empty || from < 0 || from + 1 > doc.content.size) return null;
  const head = selection.from;
  if (head < from + 1) return null;
  const $from = doc.resolve(from);
  if (!$from.sameParent(selection.$from)) return null;
  if (!TRIGGERS.has(doc.textBetween(from, from + 1, '\n', '￼'))) return null;
  const query = doc.textBetween(from + 1, head, '\n', '￼');
  if (query.length > MAX_QUERY_LENGTH || /[\s￼]/.test(query)) return null;
  return { active: true, from, query };
}

export const SlashCommand = Extension.create<Record<string, never>, SlashCommandStorage>({
  name: 'slashCommand',
  // 必须排在核心按键（Enter 拆段）与列表按键（Tab 缩进）之前，菜单打开时才能先拿到这些键；做法同官方 Mention 扩展。
  priority: 1000,

  addStorage() {
    return { onKeyDown: null };
  },

  addProseMirrorPlugins() {
    const storage = this.storage;
    return [
      new Plugin<SlashState>({
        key: slashCommandKey,
        state: {
          init: () => INACTIVE,
          apply(tr, previous, _oldState, state) {
            const meta = tr.getMeta(slashCommandKey) as SlashMeta | undefined;
            if (meta?.type === 'close') return INACTIVE;
            const typed = typedTrigger(tr, state);
            if (typed !== null) return { active: true, from: typed, query: '' };
            if (!previous.active || (!tr.docChanged && !tr.selectionSet)) return previous;
            const mapped = tr.mapping.mapResult(previous.from, 1);
            if (mapped.deleted) return INACTIVE;
            return resolveActive(state, mapped.pos) ?? INACTIVE;
          }
        },
        props: {
          handleKeyDown(view, event) {
            if (!getSlashState(view.state).active) return false;
            if (view.composing || event.isComposing || event.keyCode === 229) return false;
            if (event.key === 'Escape') {
              closeSlashMenu(view);
              return true;
            }
            return storage.onKeyDown?.(event) ?? false;
          },
          handleDOMEvents: {
            blur(view) {
              closeSlashMenu(view);
              return false;
            }
          }
        }
      })
    ];
  }
});
