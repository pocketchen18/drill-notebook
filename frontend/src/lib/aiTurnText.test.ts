import { describe, expect, it } from 'vitest';
import {
  auditReasonText,
  dispatchStateText,
  formatStoredBytes,
  historyNotice,
  historyUnlockText,
  omissionReasonText,
  outcomeNotice,
  previewLine,
  regionLabel,
  sourceKindLabel,
  statusNotice,
  storageAttachmentSuffix,
  storageMeterText,
  storageReadFailureText,
  turnStatusText
} from './aiTurnText';
import type { ChatResultView } from './aiStream';

/** 提示码只翻文案（contracts §1、§4.5）：界面不参与判定，也不许把未知码吞成"没问题"。 */

const CONTRACT_CODES = [
  'MEMORY_DISABLED', 'MEMORY_LOCKED', 'MEMORY_INDEX_BUILDING', 'MEMORY_LOOKUP_TIMEOUT',
  'MEMORY_CONSENT_REQUIRED', 'MEMORY_CONFLICT', 'MEMORY_BUDGET_PAUSED', 'CONTEXT_TRIMMED',
  'SUMMARY_UNAVAILABLE', 'PERSISTENCE_FAILED', 'USAGE_UNKNOWN', 'HISTORY_STORAGE_WARNING',
  'SOURCE_DELETED', 'SOURCE_TOO_LARGE', 'SOURCE_CHANGED', 'SUMMARY_GAPS', 'NO_COMPRESSION_GAIN',
  'OUTPUT_CAPTURE_LIMIT', 'HISTORY_ATTACHMENT_OMITTED', 'CONTEXT_AUDIT_TRUNCATED',
  // 服务端实际还会发这几个（AiService / ContextBudgeter / ChatContextAssembler）
  'RETRIEVAL_UNAVAILABLE', 'IMAGE_COST_UNKNOWN', 'ATTACHMENT_UNAVAILABLE'
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

describe('aiTurnText 历史行的终态', () => {
  it('生成失败或中断只标注助手回复，用户原文删除提示仍保留', () => {
    for (const turnStatus of ['FAILED', 'ABORTED', 'TRUNCATED', 'RUNNING']) {
      expect(historyNotice({ role: 'user', turnStatus })).toBeUndefined();
      expect(historyNotice({ role: 'assistant', turnStatus })).toBeDefined();
    }
    expect(historyNotice({ role: 'user', contentDeleted: true, turnStatus: 'FAILED' }))
      .toMatchObject({ code: 'CONTENT_DELETED' });
  });

  it('清理过原文、失败轮次、正常完成各说各话，不把缺原文当成没问题', () => {
    expect(historyNotice({ contentDeleted: true, turnStatus: 'COMPLETED' })).toMatchObject({
      code: 'CONTENT_DELETED', message: '这一轮的原文已按你的要求清理，只剩时间与状态'
    });
    expect(historyNotice({ turnStatus: 'FAILED' })).toMatchObject({
      code: 'FAILED', message: '这一轮失败，显示的内容可能不完整'
    });
    expect(historyNotice({ turnStatus: 'COMPLETED' })).toBeUndefined();
    expect(historyNotice({})).toBeUndefined();
    // 一行同时带两个信号时先说原文为什么没了，状态那一句留给「本轮上下文」
    expect(historyNotice({ contentDeleted: true, turnStatus: 'FAILED' })?.code).toBe('CONTENT_DELETED');
    expect(historyUnlockText()).toBe('这段历史用主密码加密，解锁本会话后才能显示原文');
  });
});

describe('aiTurnText 容量条', () => {
  const snapshot = (over: Record<string, unknown> = {}) => ({
    usedHistoryBytes: 1024, historyLimitBytes: 268435456, historyWarning: false, attachmentWarning: false, ...over
  });

  it('正常、接近上限、已达上限三句各不相同', () => {
    expect(storageMeterText(snapshot())).toMatchObject({ code: 'OK', message: '本地存储 1 KB / 256 MB' });
    expect(storageMeterText(snapshot({ usedHistoryBytes: 230686720, historyWarning: true })).message)
      .toBe('本地存储 220 MB / 256 MB · 接近上限，可清理历史');
    expect(storageMeterText(snapshot({ usedHistoryBytes: 268435456, historyWarning: true }))).toMatchObject({
      code: 'HISTORY_STORAGE_FULL', message: '本地存储 256 MB / 256 MB · 已达上限，先清理才能继续发'
    });
    expect(storageReadFailureText()).toBe('存储容量读不出');
  });

  it('说的是存储不是额度：三句都不许蹭后台预算的词', () => {
    const all = [
      storageMeterText(snapshot()).message,
      storageMeterText(snapshot({ usedHistoryBytes: 230686720, historyWarning: true })).message,
      storageMeterText(snapshot({ usedHistoryBytes: 268435456, historyWarning: true })).message,
      storageAttachmentSuffix({ attachmentWarning: true }),
      storageReadFailureText()
    ].join('\n');
    expect(all).not.toMatch(/额度|预算/);
  });

  it('附件预警只在真要紧时补一句，正常时一个字都不占', () => {
    expect(storageAttachmentSuffix({ attachmentWarning: false })).toBe('');
    expect(storageAttachmentSuffix({ attachmentWarning: true })).toBe(' · 附件也接近上限');
  });

  it('落库字节按 1024 进制换算，整数不硬凑小数', () => {
    expect(formatStoredBytes(0)).toBe('0 B');
    expect(formatStoredBytes(512)).toBe('512 B');
    expect(formatStoredBytes(1024)).toBe('1 KB');
    expect(formatStoredBytes(1536)).toBe('1.5 KB');
    expect(formatStoredBytes(268435456)).toBe('256 MB');
    expect(formatStoredBytes(1024 ** 4)).toBe('1024 GB');
  });
});

/** 检查器的码表（ContextAudit 与 ContextInspector 的固定枚举）：一类都不能并进另一类。 */
const AUDIT_REASONS = ['NO_AUDIT_SAVED', 'AUDIT_UNREADABLE', 'LEGACY_TURN_WITHOUT_CONTEXT_PLAN', 'REPLAYED_WITHOUT_AUDIT'];
const OMISSION_REASONS = ['BUDGET', 'COVERED_BY_SUMMARY', 'OUTSIDE_SCAN_WINDOW', 'WRONG_GENERATION', 'NON_COMPLETED',
  'SOURCE_DELETED', 'SOURCE_CHANGED', 'LOCKED', 'NOT_AUTHORIZED', 'DUPLICATE', 'ATTACHMENT_POLICY', 'ITEM_LIMIT'];
const REGIONS = ['SYSTEM', 'MEMORY', 'SESSION_STATE', 'RECENT_TURNS', 'PAGE', 'NOTEBOOK_RAG', 'USER_TEXT', 'TEXT_ATTACHMENT', 'IMAGE'];
const SELECTED_KINDS = ['MEMORY', 'SUMMARY', 'RESUME_STATE', 'TURN', 'LEGACY_MESSAGE', 'TEXT_ATTACHMENT', 'IMAGE',
  'NOTEBOOK_RAG', 'PAGE'];
/** 省略项的 kind 比"来源类别"更宽：HISTORY/LEGACY/ATTACHMENT 来自投影与附件策略，后三类是账本的收敛类别。 */
const OMISSION_KINDS = ['SUMMARY', 'RESUME_STATE', 'TURN', 'ATTACHMENT', 'LEGACY', 'HISTORY', 'OTHER', 'SELECTION',
  'DIAGNOSTICS'];
const PREVIEW_REASONS = ['LOCKED', 'SOURCE_CHANGED', 'SOURCE_DELETED', 'OUTSIDE_SCOPE', 'CHECKPOINT_REPLACED', 'WRONG_GENERATION'];

describe('aiTurnText 检查器码表', () => {
  it('账本不可用的四种原因都保留契约缺省句，未知原因也不装成没发生', () => {
    for (const reason of AUDIT_REASONS) {
      expect(auditReasonText(reason), reason).toMatch(/^本轮未保存上下文记录/);
    }
    expect(auditReasonText('NO_AUDIT_SAVED')).toBe('本轮未保存上下文记录');
    expect(auditReasonText('WHATEVER_NEW')).toBe('本轮未保存上下文记录（原因未知）');
    expect(auditReasonText(undefined)).toBe('本轮未保存上下文记录（原因未知）');
  });

  it('发送态只说三种事实，未知状态不许挑一种好看的', () => {
    expect(dispatchStateText('sent')).toBe('已发送给模型');
    expect(dispatchStateText('not_sent')).toBe('尚未发送给模型');
    expect(dispatchStateText('unknown')).toBe('发送状态未知');
    expect(dispatchStateText('drifted')).toBe('发送状态未知');
    expect(dispatchStateText(undefined)).toBe('发送状态未知');
  });

  it('省略原因、区域与来源类别逐条有名，不合并成"其他"', () => {
    for (const reason of OMISSION_REASONS) {
      expect(omissionReasonText(reason), reason).not.toBe('未提供（原因未记录）');
    }
    expect(omissionReasonText('ITEM_LIMIT')).toBe('明细超出账本上限，只保留了计数');
    expect(new Set(OMISSION_REASONS.map(omissionReasonText)).size).toBe(OMISSION_REASONS.length);
    for (const region of REGIONS) {
      expect(regionLabel(region), region).not.toBe(region);
    }
    expect(new Set(REGIONS.map(regionLabel)).size).toBe(REGIONS.length);
    for (const kind of SELECTED_KINDS) {
      expect(sourceKindLabel(kind), kind).not.toBe(kind);
    }
    expect(new Set(SELECTED_KINDS.map(sourceKindLabel)).size).toBe(SELECTED_KINDS.length);
    // 省略项的类别词汇比"来源类别"更宽（HISTORY/LEGACY/ATTACHMENT/OTHER/SELECTION/DIAGNOSTICS），
    // 漏一个就会在界面上露出英文原样（D164）。LEGACY 与 LEGACY_MESSAGE 同译「旧消息」是故意的，
    // 所以"两两不同"只在同一张集合内成立。
    for (const kind of OMISSION_KINDS) {
      expect(sourceKindLabel(kind), kind).not.toBe(kind);
    }
    expect(new Set(OMISSION_KINDS.map(sourceKindLabel)).size).toBe(OMISSION_KINDS.length);
    expect(sourceKindLabel('LEGACY')).toBe(sourceKindLabel('LEGACY_MESSAGE'));
    // 未知码回落到原样，界面不能替它编一个中文类别
    expect(sourceKindLabel('MYSTERY')).toBe('MYSTERY');
  });

  it('预览的四种失败占位各不相同，且不把正文算进去', () => {
    const lines = PREVIEW_REASONS.map((reason) => previewLine({ available: false, reason }));
    expect(new Set(lines.map((line) => line.text)).size).toBe(PREVIEW_REASONS.length);
    for (const line of lines) {
      expect(line.tone).toBe('gone');
      expect(line.text).not.toContain('常驻杭州');
    }
    expect(previewLine({ available: false, reason: 'SOURCE_DELETED' }).text).toBe('已删除');
    expect(previewLine({ available: false, reason: 'MYSTERY' }).text).toBe('暂时读不到这条来源');
  });

  it('可用来源才给引文：图片与空正文另说一句，截断要留标记', () => {
    expect(previewLine({ available: true, text: '常驻杭州', truncated: true }))
      .toEqual({ tone: 'quote', text: '常驻杭州…（已截断）' });
    expect(previewLine({ available: true, text: '常驻杭州' })).toEqual({ tone: 'quote', text: '常驻杭州' });
    expect(previewLine({ available: true, text: null, note: 'IMAGE_WITHOUT_TEXT_PREVIEW' }))
      .toEqual({ tone: 'note', text: '图片不提供文字预览' });
    expect(previewLine({ available: true, text: '   ' })).toEqual({ tone: 'note', text: '这条来源没有文字内容' });
  });
});
