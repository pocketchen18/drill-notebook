import { describe, expect, it } from 'vitest';
import { parseChatTimestamp } from './chatTimestamp';

describe('聊天时间解析', () => {
  it.each([
    '2026-11-12 21:54:00',
    '2026-11-12T21:54:00',
    '2026-11-12T21:54:00Z',
    '2026-11-13T05:54:00+08:00',
    '2026-11-12T16:54:00-05:00'
  ])('数据库时间与显式时区表示同一时刻：%s', value => {
    expect(parseChatTimestamp(value)?.toISOString()).toBe('2026-11-12T21:54:00.000Z');
  });

  it('同一提问按查看时区显示，跨日换算不固定加八小时', () => {
    const timestamp = parseChatTimestamp('2026-11-12 21:54:00')!;
    const options = { hour: '2-digit', minute: '2-digit', hour12: false } as const;
    expect(timestamp.toLocaleTimeString('zh-CN', { ...options, timeZone: 'Asia/Singapore' })).toBe('05:54');
    expect(timestamp.toLocaleTimeString('zh-CN', { ...options, timeZone: 'UTC' })).toBe('21:54');
    expect(timestamp.toLocaleTimeString('zh-CN', { ...options, timeZone: 'America/New_York' })).toBe('16:54');
  });

  it('保留数据库中的毫秒精度', () => {
    expect(parseChatTimestamp('2026-11-12 21:54:00.123')?.toISOString()).toBe('2026-11-12T21:54:00.123Z');
  });

  it.each([undefined, null, '', 'not-a-timestamp', '2026-11-12'])('缺失或无法解析的时间不补造时刻：%s', value => {
    expect(parseChatTimestamp(value)).toBeUndefined();
  });
});
