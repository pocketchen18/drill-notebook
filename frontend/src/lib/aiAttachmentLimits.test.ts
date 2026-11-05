import { describe, expect, it } from 'vitest';
import {
  ATTACHMENT_LIMIT_TEXT,
  MAX_ATTACHMENTS,
  MAX_IMAGE_BYTES,
  MAX_IMAGES_TOTAL_BYTES,
  MAX_TEXT_BYTES_WITHOUT_READING,
  MAX_TEXT_CODEPOINTS,
  attachmentLimitError
} from './aiAttachmentLimits';
import type { PendingAttachment } from './aiAttachmentLimits';

const text = (codepoints: number, bytes = codepoints): PendingAttachment => ({ kind: 'text', bytes, codepoints });
const image = (bytes: number): PendingAttachment => ({ kind: 'image', bytes });

describe('attachmentLimitError 与服务端 ChatAttachmentService 同值', () => {
  it('数量门限 4：第 4 项收下，第 5 项拒绝', () => {
    const three = [image(1), image(1), text(1)];
    expect(MAX_ATTACHMENTS).toBe(4);
    expect(attachmentLimitError(three, text(1))).toBeNull();
    expect(attachmentLimitError([...three, text(1)], text(1))).toBe(ATTACHMENT_LIMIT_TEXT.COUNT);
  });

  it('单图 5 MiB：恰好 5 MiB 收下，多 1 字节拒绝', () => {
    expect(MAX_IMAGE_BYTES).toBe(5 * 1024 * 1024);
    expect(attachmentLimitError([], image(MAX_IMAGE_BYTES))).toBeNull();
    expect(attachmentLimitError([], image(MAX_IMAGE_BYTES + 1))).toBe(ATTACHMENT_LIMIT_TEXT.IMAGE_BYTES);
  });

  it('图片合计 12 MiB：只累加图片，恰好 12 MiB 收下，多 1 字节拒绝', () => {
    const five = image(5 * 1024 * 1024);
    const almost = image(MAX_IMAGES_TOTAL_BYTES - 2 * 5 * 1024 * 1024);
    const current = [five, five, text(MAX_TEXT_CODEPOINTS, MAX_TEXT_BYTES_WITHOUT_READING)];
    expect(attachmentLimitError(current, almost)).toBeNull();
    expect(attachmentLimitError(current, image(MAX_IMAGES_TOTAL_BYTES - 2 * 5 * 1024 * 1024 + 1)))
      .toBe(ATTACHMENT_LIMIT_TEXT.IMAGES_TOTAL);
  });

  it('文本合计 20 万码点：跨附件累加，不是每个文件各判各的', () => {
    expect(MAX_TEXT_CODEPOINTS).toBe(200_000);
    const current = [text(150_000)];
    expect(attachmentLimitError(current, text(50_000))).toBeNull();
    expect(attachmentLimitError(current, text(50_001))).toBe(ATTACHMENT_LIMIT_TEXT.TEXT_CODEPOINTS);
    expect(attachmentLimitError([text(100_000)], text(100_001))).toBe(ATTACHMENT_LIMIT_TEXT.TEXT_CODEPOINTS);
  });

  it('还没读出来的大文本按 4 字节/码点直接拒绝，图片与文本互不占额度', () => {
    const unread = (bytes: number): PendingAttachment => ({ kind: 'text', bytes });
    expect(attachmentLimitError([], unread(MAX_TEXT_BYTES_WITHOUT_READING))).toBeNull();
    expect(attachmentLimitError([], unread(MAX_TEXT_BYTES_WITHOUT_READING + 1))).toBe(ATTACHMENT_LIMIT_TEXT.TEXT_CODEPOINTS);
    // 文本字节再大也不影响图片额度，反之亦然
    expect(attachmentLimitError([unread(MAX_TEXT_BYTES_WITHOUT_READING)], image(MAX_IMAGE_BYTES))).toBeNull();
  });
});
