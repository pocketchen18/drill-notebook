import { describe, expect, it } from 'vitest';
import { markdownToPlainText } from './markdownText';

describe('markdownToPlainText', () => {
  it('去掉加粗 / 斜体 / 行内代码 / 删除线标记', () => {
    expect(markdownToPlainText('下列关于 **TCP 三次握手** 的说法')).toBe('下列关于 TCP 三次握手 的说法');
    expect(markdownToPlainText('用 `Object.freeze` 冻结 *对象*，~~旧写法~~')).toBe('用 Object.freeze 冻结 对象，旧写法');
  });

  it('去掉 LaTeX 定界符并保留可读骨架', () => {
    expect(markdownToPlainText('简述 $O(n\\log n)$ 与 $O(n^2)$ 的差异')).toBe('简述 O(n log n) 与 O(n^2) 的差异');
    expect(markdownToPlainText('$$\\frac{a}{b}$$')).toBe('frac {a}{b}');
  });

  it('链接 / 图片只留文字，标题 / 引用 / 列表去前缀，多行压成一行', () => {
    expect(markdownToPlainText('# 标题\n> 引用 [文档](https://x.y)\n- 项 ![图](a.png)')).toBe('标题 引用 文档 项 图');
  });

  it('空值与纯文本原样返回', () => {
    expect(markdownToPlainText('')).toBe('');
    expect(markdownToPlainText('  普通题干  ')).toBe('普通题干');
    expect(markdownToPlainText('3 * 4 = 12')).toBe('3 * 4 = 12');
  });
});
