import { afterEach, describe, expect, it } from 'vitest';
import { render, act, cleanup } from '@testing-library/react';
import { capturePageSnapshot, materialLinkTarget } from './pageSnapshot';
import { buildTurnBody } from '../hooks/useAiChatTurn';
import { useRegisterPageContext } from '../hooks/useRegisterPageContext';
import { useUiStore, type AiPageContext } from '../stores/uiStore';
import { readToolActivities, toolResultText, toolGroupTitle } from './toolActivity';

afterEach(cleanup);
describe('消息级业务页面快照', () => {
  it('提交后内容、选中目标不再随切页或表单编辑变化', () => {
    const context: AiPageContext = { kind: 'note', title: '草稿', markdown: '尚未保存', notePageId: 4, isDraft: true, selectedIds: [4] };
    const snapshot = capturePageSnapshot(context);
    const body = buildTurnBody('id', { sessionId: 1, userText: '解释', pageSnapshot: snapshot });
    context.markdown = '后来的修改'; context.selectedIds!.push(8); snapshot.selectedIds!.push(9);
    expect(body.pageSnapshot).toMatchObject({ pageId: 4, markdown: '尚未保存', selectedIds: [4], isDraft: true });
    expect(body).not.toHaveProperty('pageContext'); expect(body).not.toHaveProperty('retrievalOptions');
  });
  it('Unicode 截短不拆分字符并明确标记不完整', () => {
    const snapshot = capturePageSnapshot({ kind: 'note', title: '长文', markdown: '😀'.repeat(32001) });
    expect(Array.from(snapshot.markdown)).toHaveLength(32000); expect(snapshot.truncated).toBe(true);
  });
  it('无所属题库的知识点保留目标并省略 null 定位字段', () => {
    const snapshot = capturePageSnapshot({ kind: 'knowledge', title: '独立知识点', markdown: '正文', pointId: 7, bankId: null as unknown as number });
    expect(JSON.parse(JSON.stringify(snapshot))).toMatchObject({ pointId: 7, markdown: '正文' });
    expect(JSON.parse(JSON.stringify(snapshot))).not.toHaveProperty('bankId');
  });
  it('离开页面清理旧正文，下一页面注册不会被旧组件卸载清掉', () => {
    function Page({ context }: { context: AiPageContext }) { useRegisterPageContext(context); return null; }
    const first = render(<Page context={{ kind: 'note', title: '旧页面', markdown: '旧正文' }} />);
    const next = render(<Page context={{ kind: 'knowledge', title: '新页面', markdown: '新正文' }} />);
    first.unmount(); expect(useUiStore.getState().pageContext.markdown).toBe('新正文');
    next.unmount(); expect(useUiStore.getState().pageContext.markdown).toBe('');
  });
  it('重新编辑同一页面更新草稿，不会无限注册', () => {
    function Page({ content }: { content: string }) { useRegisterPageContext({ kind: 'note', title: '草稿', markdown: content, isDraft: true }); return null; }
    const view = render(<Page content="第一版" />);
    act(() => view.rerender(<Page content="第二版" />));
    expect(capturePageSnapshot(useUiStore.getState().pageContext)).toMatchObject({ markdown: '第二版', isDraft: true });
  });
  it('来源链接只接受已知类型和安全整数', () => {
    expect(materialLinkTarget('drill-notebook://material/knowledge/12')).toEqual({ type: 'knowledge', id: 12 });
    for (const link of ['javascript:alert(1)', 'drill-notebook://material/file/1', 'drill-notebook://material/note/-1', 'drill-notebook://material/note/9007199254740993']) expect(materialLinkTarget(link)).toBeUndefined();
  });
  it('六个工具有各自名称与资料结果文案', () => {
    const rows = readToolActivities(['read_current_page', 'search_materials', 'read_notebook', 'read_knowledge', 'read_question_bank', 'read_wrong_questions'].map((toolName, i) => ({ sequence: i + 1, toolName, state: 'FINISHED', outcome: 'OK', count: 1, incomplete: true })));
    expect(rows).toHaveLength(6); expect(rows.every((r) => r.toolName !== 'unknown_tool')).toBe(true);
    expect(toolResultText(rows[0])).toContain('部分内容'); expect(toolGroupTitle(rows)).toContain('已读取学习资料');
    expect(toolResultText({ ...rows[0], outcome: 'NOT_FOUND' })).toContain('资料');
  });
});
