/** UUIDv7（RFC 9562）：轮次请求身份的唯一生成处，v4 冒充会被服务端拒（contracts §4.1）。 */

const CANONICAL = /^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;

export function isUuidV7(value: string | null | undefined): value is string {
  return typeof value === 'string' && CANONICAL.test(value);
}

/** 高 48 位放毫秒时间戳（超出部分自然截成低 48 位），随机位取 crypto.getRandomValues。 */
export function generateUuidV7(timestampMillis: number): string {
  const bytes = new Uint8Array(16);
  globalThis.crypto.getRandomValues(bytes);
  const millis = Number.isFinite(timestampMillis) ? Math.max(Math.floor(timestampMillis), 0) : 0;
  for (let index = 0; index < 6; index++) {
    bytes[index] = Math.floor(millis / 256 ** (5 - index)) % 256;
  }
  bytes[6] = (bytes[6] & 0x0f) | 0x70;
  bytes[8] = (bytes[8] & 0x3f) | 0x80;
  const hex = Array.from(bytes, (byte) => byte.toString(16).padStart(2, '0')).join('');
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}

/** 取 ID 内嵌的毫秒时间戳；形态不合法就抛错，不能让调用方把坏 ID 当成 0 毫秒。 */
export function uuidV7Timestamp(value: string): number {
  if (!isUuidV7(value)) throw new Error('requestId 必须是 UUIDv7');
  return Number.parseInt(value.replace(/-/g, '').slice(0, 12), 16);
}
