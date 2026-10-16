import { describe, expect, it } from 'vitest';
import { outcomeNotice, statusNotice, turnStatusText } from './aiTurnText';
import type { ChatResultView } from './aiStream';

/** 提示码只翻文案（contracts §1、§4.5）：界面不参与判定，也不许把未知码吞成"没问题"。 */

const CONTRACT_CODES = [
  'MEMORY_DISABLED', 'MEMORY_LOCKED', 'MEMORY_INDEX_BUILDING', 'MEMORY_LOOKUP_TIMEOUT',
  'MEMORY_CONSENT_REQUIRED', 'MEMORY_CONFLICT', 'MEMORY_BUDGET_PAUSED', 'CONTEXT_TRIMMED',
  'SUMMARY_UNAVAILABLE', 'PERSISTENCE_FAILED', 'USAGE_UNKNOWN', 'HISTORY_STORAGE_WARNING',
  'SOURCE_DELETED', 'SOURCE_TOO_LARGE', 'SOURCE_CHANGED', 'SUMMARY_GAPS', 'NO_COMPRESSION_GAIN',
  'OUTPUT_CAPTURE_LIMIT', 'HISTORY_ATTACHMENT_OMITTED', 'CONTEXT_AUDIT_TRUNCATED',
  // 服务端实际还会发这几个（AiService / ContextBudgeter / ChatContextAssembler）
  'RETRIEVAL_UNAVAILABLE', 'WINDOW_UNSET', 'IMAGE_COST_UNKNOWN', 'ATTACHMENT_UNAVAILABLE'
];

const withNotices = (notices?: string[]): ChatResultView => ({ contextUsage: { notices } });

describe('aiTurnText 统一结果的提示', () => {
  it('契约里的码都有话说，不会静默丢提示', () => {
    for (const code of CONTRACT_CODES) {
      expect(outcomeNotice(withNotices([code])), code)?.toMatchObject({ code });
    }
  });

  it('落库没成先说落库，非完成终态先说终态，都不占用降级提示的位置', () => {
    expect(outcomeNotice({ stateDurable: false, contextUsage: { notices: ['RETRIEVAL_UNAVAILABLE'] } }))
      .toMatchObject({ code: 'PERSISTENCE_FAILED' });
    expect(outcomeNotice({ status: 'TRUNCATED', contextUsage: { notices: ['RETRIEVAL_UNAVAILABLE'] } }))
      .toMatchObject({ code: 'TRUNCATED', message: '这一轮被截断，显示的内容可能不完整' });
    expect(outcomeNotice({ status: 'COMPLETED', contextUsage: { notices: ['RETRIEVAL_UNAVAILABLE'] } }))
      .toMatchObject({ code: 'RETRIEVAL_UNAVAILABLE', message: '笔记检索暂时不可用，本次回答未使用笔记内容' });
  });

  it('同一个降级码按有无引用分句，不在有引用时自相矛盾', () => {
    const notice = { contextUsage: { notices: ['RETRIEVAL_UNAVAILABLE'] } };
    expect(outcomeNotice(notice)?.message).toBe('笔记检索暂时不可用，本次回答未使用笔记内容');
    expect(outcomeNotice({ ...notice, citations: [{ pageId: 5 }] }))
      .toMatchObject({ code: 'RETRIEVAL_UNAVAILABLE', message: '笔记检索降级：本次只用了关键词召回结果' });
  });

  it('取第一条认识的码；未知码与缺字段都不编造文案', () => {
    expect(outcomeNotice(withNotices(['SOMETHING_NEW', 'CONTEXT_TRIMMED'])))
      .toMatchObject({ code: 'CONTEXT_TRIMMED' });
    expect(outcomeNotice(withNotices(['SOMETHING_NEW']))).toBeUndefined();
    expect(outcomeNotice(withNotices(undefined))).toBeUndefined();
    expect(outcomeNotice({})).toBeUndefined();
  });
});

describe('aiTurnText 查状态查出来的终态', () => {
  it('RUNNING 与"没有句柄的 RUNNING"是两句话', () => {
    expect(statusNotice({ status: 'RUNNING', active: true })).toMatchObject({
      code: 'RUNNING', message: '这一轮仍在后端收尾，结果稍后出现在历史里'
    });
    expect(statusNotice({ status: 'RUNNING', active: false }).code).toBe('RUNNING_WITHOUT_HANDLE');
    expect(statusNotice({ status: 'RUNNING', active: false }).message).toContain('没有收到结果');
  });

  it('其余终态各自有名，未知状态说未知', () => {
    expect(statusNotice({ status: 'ABORTED' }).message).toBe('这一轮已中断，显示的内容可能不完整');
    expect(turnStatusText('INTERRUPTED')).toContain('进程退出');
    expect(turnStatusText('WEIRD')).toBe('状态未知');
    expect(turnStatusText(undefined)).toBe('状态未知');
    expect(statusNotice({}).code).toBe('UNKNOWN');
  });
});
