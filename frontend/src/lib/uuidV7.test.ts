import { afterEach, describe, expect, it, vi } from 'vitest';
import { generateUuidV7, isUuidV7, uuidV7Timestamp } from './uuidV7';

/**
 * 轮次身份的唯一形式（contracts §4.1 L432）：requestId 必须是 UUIDv7，
 * 服务端按版本位/变体位/内嵌时间戳判定幂等与时效，写错一位就等于给同一请求发了两个 ID。
 */

const NOW = 1_760_000_000_000;

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('uuidV7 生成', () => {
  it('字节序与位掩码逐字节钉死：随机位全 0xAB 时结果只有一个形状', () => {
    vi.stubGlobal('crypto', { getRandomValues: (bytes: Uint8Array) => bytes.fill(0xab) });

    expect(generateUuidV7(NOW)).toBe('0199c82c-c000-7bab-abab-abababababab');
  });

  it('版本位是 7、变体位是 10xx、高 48 位回读同一个毫秒', () => {
    const id = generateUuidV7(NOW);

    expect(isUuidV7(id)).toBe(true);
    expect(id.charAt(14)).toBe('7');
    expect('89ab').toContain(id.charAt(19));
    expect(uuidV7Timestamp(id)).toBe(NOW);
  });

  it('同一毫秒靠随机位区分，否则两轮请求会撞成同一个身份', () => {
    expect(generateUuidV7(NOW)).not.toBe(generateUuidV7(NOW));
  });
});

describe('uuidV7 解析与拒绝', () => {
  it('v4、大写、无分隔、变体位错的都不算 v7', () => {
    expect(isUuidV7(crypto.randomUUID())).toBe(false);
    expect(isUuidV7(generateUuidV7(NOW).toUpperCase())).toBe(false);
    expect(isUuidV7(generateUuidV7(NOW).replace(/-/g, ''))).toBe(false);
    expect(isUuidV7('01a109ca-1100-8000-8000-000000000001')).toBe(false);
    expect(isUuidV7('')).toBe(false);
    expect(isUuidV7(null)).toBe(false);
    expect(isUuidV7(undefined)).toBe(false);
  });

  it('坏形态解析时抛错，不能静默给一个能通过的默认值', () => {
    expect(() => uuidV7Timestamp('not-an-id')).toThrow('requestId 必须是 UUIDv7');
    expect(() => uuidV7Timestamp(crypto.randomUUID())).toThrow();
  });
});

describe('uuidV7 时钟边界', () => {
  it('负数与坏输入都夹到 0，不写出非法字节', () => {
    for (const input of [-1, -NOW, Number.NaN, Number.POSITIVE_INFINITY]) {
      const id = generateUuidV7(input);
      expect(isUuidV7(id)).toBe(true);
      expect(uuidV7Timestamp(id)).toBe(0);
    }
  });

  it('48 位内原样回读，超出只保留低 48 位（与后端同一口径）', () => {
    expect(uuidV7Timestamp(generateUuidV7(0))).toBe(0);
    expect(uuidV7Timestamp(generateUuidV7(2 ** 48 - 1))).toBe(2 ** 48 - 1);
    expect(uuidV7Timestamp(generateUuidV7(2 ** 48 + 5))).toBe(5);
  });
});
