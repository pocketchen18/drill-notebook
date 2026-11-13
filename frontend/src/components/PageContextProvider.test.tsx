import { beforeEach, describe, expect, it } from 'vitest';
import { render } from '@testing-library/react';
import { PageContextProvider, pageContextRows, usePageContextSection } from './PageContextProvider';
import { capturePageSnapshot } from '../lib/pageSnapshot';
import { useUiStore, type AiPageContext } from '../stores/uiStore';

function Section({ id = 'panel', text, draft = false }: { id?: string; text: string; draft?: boolean }): JSX.Element {
  usePageContextSection(id, text, draft);
  return <div><input type="password" defaultValue="fake-password" /><input defaultValue="fake-unmasked-key" />未登记的界面文字</div>;
}
const general: AiPageContext = { kind: 'settings', title: '设置 · 常规', route: '/settings?tab=general', markdown: '# 设置\n常规' };
const about: AiPageContext = { kind: 'settings', title: '设置 · 关于', route: '/settings?tab=about', markdown: '# 设置\n关于' };
beforeEach(() => useUiStore.getState().clearPageContext());

describe('组合页面快照', () => {
  it('只读取面板明确登记的数据，不扫描输入框或页面文字', () => {
    render(<PageContextProvider context={general}><Section text="主题：深色" /></PageContextProvider>);
    const snapshot = capturePageSnapshot(useUiStore.getState().pageContext);
    expect(snapshot).toMatchObject({ kind: 'settings', title: '设置 · 常规', route: '/settings?tab=general', isDraft: false });
    expect(snapshot.markdown).toContain('主题：深色');
    expect(snapshot.markdown).not.toMatch(/fake-password|fake-unmasked-key|未登记的界面文字/);
  });

  it('分区切换清掉旧面板，提交后的快照仍保持原内容', () => {
    const page = render(<PageContextProvider context={general}><Section text="常规选项" /></PageContextProvider>);
    const submitted = capturePageSnapshot(useUiStore.getState().pageContext);
    page.rerender(<PageContextProvider context={about}><Section text="当前版本 v0.8.1" /></PageContextProvider>);
    expect(useUiStore.getState().pageContext).toMatchObject({ title: '设置 · 关于', route: '/settings?tab=about' });
    expect(useUiStore.getState().pageContext.markdown).not.toContain('常规选项');
    expect(useUiStore.getState().pageContext.markdown).toContain('当前版本 v0.8.1');
    expect(submitted.markdown).toContain('常规选项');
    expect(submitted.route).toBe('/settings?tab=general');
  });

  it('旧面板清理不会删除后来登记的同名面板', () => {
    const page = render(<PageContextProvider context={general}><Section key="old" text="旧内容" /><Section key="new" text="新内容" /></PageContextProvider>);
    page.rerender(<PageContextProvider context={general}><Section key="new" text="新内容" /></PageContextProvider>);
    expect(useUiStore.getState().pageContext.markdown).toContain('新内容');
    expect(useUiStore.getState().pageContext.markdown).not.toContain('旧内容');
  });

  it('草稿与截断沿用消息快照约定，重复渲染不反复通知主窗口', () => {
    const page = render(<PageContextProvider context={general}><Section text="未保存设置" draft /></PageContextProvider>);
    expect(capturePageSnapshot(useUiStore.getState().pageContext).isDraft).toBe(true);
    let updates = 0;
    const stop = useUiStore.subscribe((state, previous) => { if (state.pageContext !== previous.pageContext) updates += 1; });
    page.rerender(<PageContextProvider context={general}><Section text="未保存设置" draft /></PageContextProvider>);
    expect(updates).toBe(0);
    stop();
    page.rerender(<PageContextProvider context={general}><Section text={'😀'.repeat(32001)} /></PageContextProvider>);
    const snapshot = capturePageSnapshot(useUiStore.getState().pageContext);
    expect(Array.from(snapshot.markdown)).toHaveLength(32000);
    expect(snapshot.truncated).toBe(true);
  });

  it('在未登记的其他页面复用面板不会覆盖当前页，卸载只清理自己的内容', () => {
    const page = render(<PageContextProvider context={general}><Section text="本页" /></PageContextProvider>);
    const standalone = render(<Section text="其他位置" />);
    expect(useUiStore.getState().pageContext.markdown).not.toContain('其他位置');
    const next = render(<PageContextProvider context={about}><Section text="下一页" /></PageContextProvider>);
    page.unmount();
    expect(useUiStore.getState().pageContext.markdown).toContain('下一页');
    standalone.unmount(); next.unmount();
    expect(useUiStore.getState().pageContext.kind).toBe('none');
  });

  it('设置行只显示允许的标量与开关，未展示的行直接省略', () => {
    expect(pageContextRows('更新设置', [['自动更新', false], ['自动检查', true], ['间隔', 24], ['未展示', undefined]]))
      .toBe('## 更新设置\n- 自动更新：关闭\n- 自动检查：开启\n- 间隔：24');
  });
});
