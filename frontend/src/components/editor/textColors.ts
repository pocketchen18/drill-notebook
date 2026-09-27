import { Mark, mergeAttributes } from '@tiptap/core';
import Highlight from '@tiptap/extension-highlight';

/**
 * 文档里存语义色名而不是具体 CSS 颜色，同一篇笔记在明暗主题下都清晰可读；
 * app.css 为每个色名提供明、暗两套令牌。
 */
export const TEXT_COLORS = ['gray', 'red', 'orange', 'yellow', 'green', 'blue', 'purple'] as const;
export type TextColor = typeof TEXT_COLORS[number];

export const TEXT_COLOR_LABELS: Record<TextColor, string> = {
  gray: '灰色',
  red: '红色',
  orange: '橙色',
  yellow: '黄色',
  green: '绿色',
  blue: '蓝色',
  purple: '紫色'
};

export function isTextColor(value: unknown): value is TextColor {
  return typeof value === 'string' && (TEXT_COLORS as readonly string[]).includes(value);
}

declare module '@tiptap/core' {
  interface Commands<ReturnType> {
    textColor: {
      setTextColor: (color: TextColor) => ReturnType;
      unsetTextColor: () => ReturnType;
    };
  }
}

export const TextColorMark = Mark.create({
  name: 'textColor',
  addAttributes() {
    return {
      color: {
        default: null,
        parseHTML: (element) => {
          const value = element.getAttribute('data-text-color');
          return isTextColor(value) ? value : null;
        },
        renderHTML: (attributes) => (isTextColor(attributes.color) ? { 'data-text-color': attributes.color } : {})
      }
    };
  },
  parseHTML() {
    return [{ tag: 'span[data-text-color]', getAttrs: (element) => (isTextColor((element as HTMLElement).getAttribute('data-text-color')) ? null : false) }];
  },
  renderHTML({ HTMLAttributes }) {
    return ['span', mergeAttributes(HTMLAttributes), 0];
  },
  addCommands() {
    return {
      setTextColor: (color) => ({ commands }) => commands.setMark(this.name, { color }),
      unsetTextColor: () => ({ commands }) => commands.unsetMark(this.name)
    };
  }
});

/**
 * 背景高亮使用同一套语义色。官方扩展会把颜色写进内联 style，把浅色主题的色值固化进文档；
 * 这里只保留 `data-color`。
 */
export const ColorHighlight = Highlight.extend({
  addAttributes() {
    return {
      color: {
        default: null,
        parseHTML: (element) => {
          const value = element.getAttribute('data-color');
          return isTextColor(value) ? value : null;
        },
        renderHTML: (attributes) => (isTextColor(attributes.color) ? { 'data-color': attributes.color } : {})
      }
    };
  }
}).configure({ multicolor: true });
