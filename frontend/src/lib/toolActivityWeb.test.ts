import { expect, it } from 'vitest';
import { readToolActivities, readWebRoute, toolLabel, toolResultText, webRouteText } from './toolActivity';

it('联网活动保留真实来源、限流与冷却；参数、密钥和上游任意文字不会进入显示模型', () => {
  const [row] = readToolActivities([{ sequence: 1, toolName: 'search_web', state: 'FINISHED', outcome: 'OK', count: 2,
    webProvider: 'tavily', query: 'PRIVATE', items: [{ url: 'https://example.com/?PRIVATE', snippet: 'PRIVATE' }],
    webRoute: [{ provider: 'exa', status: 'RATE_LIMITED', httpStatus: 429, retryAt: '2026-11-14T12:00:00Z', message: 'PRIVATE' },
      { provider: 'tavily', status: 'OK', apiKey: 'PRIVATE' }] }]);
  expect(JSON.stringify(row)).not.toContain('PRIVATE');
  expect(toolLabel(row)).toBe('联网搜索 · Tavily'); expect(toolResultText(row)).toBe('本次找到 2 个来源');
  expect(webRouteText(row.webRoute![0])).toContain('Exa：服务请求受限（HTTP 429）');
  expect(webRouteText(row.webRoute![0])).toContain('下次尝试');
  const [failed] = readToolActivities([{ ...row, outcome: 'QUOTA_EXHAUSTED', count: 0 }]);
  expect(toolResultText(failed)).toBe('服务额度不足');
});

it('未知服务和非法路由字段被丢弃，路由最多四步，普通工具不接受联网元数据', () => {
  const route = readWebRoute([{ provider: 'PRIVATE', status: 'OK' },
    { provider: 'local', status: 'PRIVATE', httpStatus: 999, retryAt: 'PRIVATE' },
    { provider: 'exa', status: 'OK' }, { provider: 'tavily', status: 'OK' }, { provider: 'tinyfish', status: 'OK' }]);
  expect(route).toHaveLength(3); expect(route[0]).toEqual({ provider: 'local', status: 'UNKNOWN', httpStatus: null, retryAt: null });
  expect(JSON.stringify(route)).not.toContain('PRIVATE');
  const [row] = readToolActivities([{ sequence: 1, toolName: 'read_notebook', state: 'FINISHED', outcome: 'OK', count: 1,
    webProvider: 'exa', webRoute: [{ provider: 'exa', status: 'OK' }] }]);
  expect(row.webProvider).toBeUndefined(); expect(row.webRoute).toBeUndefined();
});
