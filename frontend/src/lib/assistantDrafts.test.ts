import { describe, expect, it } from 'vitest';
import { parseAssistantDrafts } from './assistantDrafts';

const document = () => ({ format: 1, selectedSessionId: null, choice: { modelConfigId: null, reasoningEffort: null }, titleDraft: null,
  drafts: [{ sessionId: null, message: '文字', attachments: [], scroll: 0 }] });

describe('便携草稿读取校验', () => {
  it('首次使用没有草稿，已保存的内容保留', () => {
    expect(parseAssistantDrafts(null)).toBeNull();
    expect(parseAssistantDrafts(document())).toEqual(document());
  });
  it('非法会话标识和重复记录不能覆盖新会话草稿', () => {
    for (const id of [0, -1, '10', undefined, NaN]) expect(() => parseAssistantDrafts({ ...document(), selectedSessionId: id })).toThrow('会话标识无效');
    expect(() => parseAssistantDrafts({ ...document(), drafts: [document().drafts[0], document().drafts[0]] })).toThrow('重复');
  });
  it('非有限附件大小、重复附件和非图片 URL 被拒绝', () => {
    const image = { id: 'a', name: '图.png', kind: 'image', value: 'data:image/png;base64,YQ==', mime: 'image/png', bytes: 1 };
    const withAttachments = (attachments: unknown[]) => ({ ...document(), drafts: [{ ...document().drafts[0], attachments }] });
    expect(parseAssistantDrafts(withAttachments([image]))?.drafts[0].attachments[0]).toEqual(image);
    for (const invalid of [{ ...image, bytes: Infinity }, { ...image, bytes: -1 }, { ...image, value: 'https://example.com/private.png' }]) {
      expect(() => parseAssistantDrafts(withAttachments([invalid]))).toThrow('附件无效');
    }
    expect(() => parseAssistantDrafts(withAttachments([image, image]))).toThrow('附件无效');
  });
});
