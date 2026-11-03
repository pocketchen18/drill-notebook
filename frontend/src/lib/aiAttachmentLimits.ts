/**
 * 聊天附件的前置门限（contracts §4.3、执行方案 §7.2-2）。
 *
 * <p>数值与后端 `ChatAttachmentService` 逐字同值：4 项、单图 5 MiB、图合计 12 MiB、文本 20 万码点。
 * 客户端先挡只是为了不让用户等一次注定 413 的发送——**服务端仍是唯一权威**：它还按文件签名判类型、
 * 按像素判解压炸弹，客户端替它做这些既做不到也不该做。</p>
 */

export const MAX_ATTACHMENTS = 4;
export const MAX_IMAGE_BYTES = 5 * 1024 * 1024;
export const MAX_IMAGES_TOTAL_BYTES = 12 * 1024 * 1024;
export const MAX_TEXT_CODEPOINTS = 200_000;
/**
 * 文本按 UTF-8 每码点最多 4 字节：文件超过这个字节数就**必然**超过 20 万码点，
 * 所以连读都不用读（避免把几百 MB 的文本整个读进内存再数）。
 */
export const MAX_TEXT_BYTES_WITHOUT_READING = MAX_TEXT_CODEPOINTS * 4;

export const ATTACHMENT_LIMIT_TEXT = {
  COUNT: `一次最多 ${MAX_ATTACHMENTS} 个附件`,
  IMAGE_BYTES: '单张图片不能超过 5 MB',
  IMAGES_TOTAL: '图片合计不能超过 12 MB',
  TEXT_CODEPOINTS: '文本附件合计不能超过 20 万字'
} as const;

export interface PendingAttachment {
  kind: 'text' | 'image';
  bytes: number;
  /** 文本的码点数；图片不需要。 */
  codepoints?: number;
}

/**
 * 纯预检：返回一句人话，或 null 表示本地这一关过了。
 * 文本若还没读出来就先不传 {@code codepoints}，读完之后再用同一个函数复查一次。
 */
export function attachmentLimitError(current: PendingAttachment[], candidate: PendingAttachment): string | null {
  if (current.length + 1 > MAX_ATTACHMENTS) return ATTACHMENT_LIMIT_TEXT.COUNT;
  if (candidate.kind === 'image') {
    if (candidate.bytes > MAX_IMAGE_BYTES) return ATTACHMENT_LIMIT_TEXT.IMAGE_BYTES;
    const imagesTotal = current
      .filter((item) => item.kind === 'image')
      .reduce((sum, item) => sum + item.bytes, 0);
    if (imagesTotal + candidate.bytes > MAX_IMAGES_TOTAL_BYTES) return ATTACHMENT_LIMIT_TEXT.IMAGES_TOTAL;
    return null;
  }
  if (candidate.bytes > MAX_TEXT_BYTES_WITHOUT_READING) return ATTACHMENT_LIMIT_TEXT.TEXT_CODEPOINTS;
  // 服务端按"本轮文本合计"判 200k，不是每个文件各判各的
  const textTotal = current
    .filter((item) => item.kind === 'text')
    .reduce((sum, item) => sum + (item.codepoints ?? 0), 0);
  if (textTotal + (candidate.codepoints ?? 0) > MAX_TEXT_CODEPOINTS) return ATTACHMENT_LIMIT_TEXT.TEXT_CODEPOINTS;
  return null;
}
