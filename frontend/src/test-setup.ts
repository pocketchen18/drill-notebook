import '@testing-library/jest-dom';
import { beforeEach } from 'vitest';
import { clearViewState } from './lib/viewState';

// 视图状态改为 localStorage 持久化后，测试之间必须隔离存储，
// 否则前一个用例写入的记忆会让后一个用例「凭空恢复」。
// 必须用 clearViewState：仅清 localStorage 会留下模块级待写入补丁与防抖定时器，
// 上一用例的写入可能在下一个用例运行中途才落盘。
beforeEach(() => {
  try {
    clearViewState();
    localStorage.clear();
  } catch {
    /* jsdom 未提供 localStorage 时忽略 */
  }
});

// jsdom 不提供 matchMedia；arco-design 响应式组件（Grid/Row 等）依赖它。
if (typeof window !== 'undefined' && !window.matchMedia) {
  window.matchMedia = (query: string): MediaQueryList => ({
    matches: false,
    media: query,
    onchange: null,
    addListener: () => {},
    removeListener: () => {},
    addEventListener: () => {},
    removeEventListener: () => {},
    dispatchEvent: () => false
  });
}
