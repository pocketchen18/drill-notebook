/** 聊天数据库的无时区时间为 UTC；已有时区的时间保持原义。 */
export function parseChatTimestamp(value?: string | null): Date | undefined {
  if (!value) return undefined;
  const normalized = value.trim().replace(' ', 'T');
  if (!/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(?::\d{2}(?:\.\d+)?)?(?:Z|[+-]\d{2}:?\d{2})?$/i.test(normalized)) return undefined;
  const zoned = /(?:Z|[+-]\d{2}:?\d{2})$/i.test(normalized) ? normalized : `${normalized}Z`;
  const timestamp = new Date(zoned);
  return Number.isNaN(timestamp.getTime()) ? undefined : timestamp;
}
