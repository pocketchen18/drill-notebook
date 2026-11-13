import { beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { useUiStore } from '../stores/uiStore';
import { capturePageSnapshot } from '../lib/pageSnapshot';
import { buildTurnBody } from '../hooks/useAiChatTurn';
import { toolGroups } from '../lib/aiToolSettings';
import { SettingsPage } from './SettingsPage';

const { apiGet, updateState, updateApi } = vi.hoisted(() => ({
  apiGet: vi.fn(),
  updateState: {
    currentVersion: '0.8.1', development: false, checking: false, checkStatus: 'current',
    lastCheckAt: '2026-11-14T02:00:00Z', skippedVersion: null,
    target: null as null | { version: string; asset: null },
    settings: { automaticUpdate: false, automaticCheck: true, startupReminder: true, intervalHours: 24 }
  },
  updateApi: { check: vi.fn(), setSettings: vi.fn(), openHistory: vi.fn() }
}));
vi.mock('../lib/api', async importOriginal => ({ ...await importOriginal<object>(), get: apiGet }));
vi.mock('../components/app-update/AppUpdateProvider', () => ({
  useAppUpdate: () => ({ api: updateApi, state: updateState }), updateAction: vi.fn()
}));

const runtimeValues = { maxToolStages: 100, maxToolExecutions: 200, turnTimeoutMinutes: 60, requestTimeoutMinutes: 30,
  maxOutputTokens: 32768, requestOutputTokens: 8192, finalOutputTokens: 4096, repeatedToolLimit: 2,
  retry: { httpRetries: 4, streamRetries: 5, networkPersistent: true, networkRetries: 5 } };

beforeEach(() => {
  useUiStore.getState().clearPageContext();
  useUiStore.getState().setThemeMode('light');
  updateState.target = null;
  updateState.settings.intervalHours = 24;
  apiGet.mockReset();
  apiGet.mockImplementation(async (path: string) => {
    if (path === '/api/ai/connections') return { revision: 1, chatModelId: 'model-1', importModelId: null,
      apiKey: 'fake-response-key', headers: { Authorization: 'fake-header-value' }, providers: [{
        id: 'provider-1', name: '演示供应商', apiFormat: 'chat_completions', endpoint: 'https://provider.test/v1',
        authMode: 'api_key', hasKey: true, needsPassword: false, models: [{ id: 'model-1', modelId: 'demo-model',
          name: '演示模型', group: '', streaming: true, tools: true, contextWindow: 64000, maxOutputTokens: 8192 }]
      }] };
    if (path === '/api/ai/runtime-settings') return { revision: 1, values: runtimeValues, defaults: runtimeValues };
    if (path === '/api/ai/tool-settings') return { revision: 1, tools: toolGroups.flatMap(group => group.tools.map(name => ({ name, group: group.id, enabled: true, defaultEnabled: true }))) };
    if (path === '/api/ai/memory/settings') return { revision: 1, enabled: false, initialized: false, unlocked: false,
      autoExtract: false, compressSession: false, consents: {} };
    if (path === '/api/ai/config') return { embedding: { provider: 'openai', model: 'demo-embedding', dimensions: 512,
      endpoint: 'https://embedding.test/v1', hasKey: true, apiKey: 'fake-embedding-key' } };
    if (path === '/api/ai/embeddings/catalog') return { models: [] };
    if (path.startsWith('/api/ai/retrieval/status')) return { indexState: 'ACTIVE', totalPages: 2, totalChunks: 6,
      indexedChunks: 6, staleChunks: 0, queuedJobs: 0, failedJobs: 0 };
    if (path === '/api/data/backup/config') return { directory: '', autoIntervalHours: 24, maxCount: 10, lite: false };
    if (path === '/api/data/backup/status') return { running: false, autoEnabled: true, autoIntervalHours: 24, lastBackupAt: null };
    if (path === '/api/data/backups') return [{ name: 'backup-2026.zip', sizeBytes: 1024, createdAt: '2026-11-14T02:00:00Z', lite: false }];
    if (path === '/api/review/configs') return [{ id: 1, name: '标准复习', isDefault: true, dailyNewLimit: 20,
      dailyReviewLimit: 100, wrongStrategy: 'reduce_half', priorityMode: 'due_first', intervals: { '1': 1 },
      initialEf: 2.5, minimumEf: 1.3, maxIntervalDays: 365 }];
    if (path === '/api/notebooks') return [];
    throw new Error('Unexpected fixture request: ' + path);
  });
});

function renderPage(tab: string) {
  window.location.hash = '#/settings?tab=' + tab;
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
  return render(<QueryClientProvider client={client}><MemoryRouter initialEntries={['/settings']}><SettingsPage /></MemoryRouter></QueryClientProvider>);
}

describe('设置页当前页面适配', () => {
  it.each([
    ['general', '常规', '主题：浅色'], ['ai', 'AI 连接', '演示模型'], ['ai-behavior', 'AI 对话', '请求重试次数：4'],
    ['tools', '工具', '读取当前页面：开启'], ['memory', '记忆', '启用记忆：关闭'],
    ['embedding', '嵌入与检索', '远程模型：demo-embedding'], ['study', '学习与复习', '标准复习'],
    ['data', '数据管理', 'backup-2026.zip'], ['about', '关于', '当前版本：v0.8.1']
  ])('%s 分区提供真实展示状态并进入发送快照', async (tab, label, expected) => {
    renderPage(tab);
    await waitFor(() => expect(useUiStore.getState().pageContext.markdown).toContain(expected));
    const snapshot = capturePageSnapshot(useUiStore.getState().pageContext);
    expect(snapshot).toMatchObject({ kind: 'settings', title: '设置 · ' + label, route: '/settings?tab=' + tab });
    const body = buildTurnBody('main', { sessionId: 1, userText: '当前设置有什么内容', pageSnapshot: snapshot });
    expect(body.pageSnapshot).toMatchObject({ kind: 'settings', markdown: snapshot.markdown });
    expect(JSON.stringify(snapshot)).not.toMatch(/fake-response-key|fake-header-value|fake-embedding-key/);
  });

  it('主题切换与分区切换更新助手；已提交消息仍绑定原分区', async () => {
    renderPage('general');
    fireEvent.click(screen.getByRole('radio', { name: '深色' }));
    await waitFor(() => expect(useUiStore.getState().pageContext.markdown).toContain('主题：深色'));
    const submitted = capturePageSnapshot(useUiStore.getState().pageContext);
    fireEvent.click(screen.getByRole('tab', { name: '关于' }));
    await waitFor(() => expect(useUiStore.getState().pageContext.markdown).toContain('当前版本：v0.8.1'));
    expect(useUiStore.getState().pageContext.markdown).not.toContain('主题：深色');
    expect(submitted.route).toBe('/settings?tab=general');
    expect(submitted.markdown).toContain('主题：深色');
  });

  it('关于页版本、更新状态和间隔随实际状态变化', async () => {
    const page = renderPage('about');
    await waitFor(() => expect(useUiStore.getState().pageContext.markdown).toContain('当前已是最新版本'));
    updateState.target = { version: '0.8.2', asset: null };
    updateState.settings.intervalHours = 48;
    page.rerender(<QueryClientProvider client={new QueryClient()}><MemoryRouter><SettingsPage /></MemoryRouter></QueryClientProvider>);
    await waitFor(() => expect(useUiStore.getState().pageContext.markdown).toContain('发现新版本 v0.8.2'));
    expect(useUiStore.getState().pageContext.markdown).toContain('检查与提醒间隔：48 小时');
    expect(useUiStore.getState().pageContext.markdown).toContain('https://github.com/qilin-software/drill-notebook');
  });

  it('输入的新 API Key 只提供配置状态，即使明文显示也不进入正文', async () => {
    renderPage('ai');
    fireEvent.click(await screen.findByRole('button', { name: '供应商更多操作' }));
    fireEvent.click(await screen.findByText('编辑连接'));
    fireEvent.click(await screen.findByRole('button', { name: '更换' }));
    const input = screen.getByLabelText('API Key') as HTMLInputElement;
    fireEvent.change(input, { target: { value: 'fake-new-secret-for-test' } });
    input.type = 'text';
    await waitFor(() => expect(useUiStore.getState().pageContext.markdown).toContain('已输入（未保存）'));
    expect(useUiStore.getState().pageContext.isDraft).toBe(true);
    expect(JSON.stringify(capturePageSnapshot(useUiStore.getState().pageContext))).not.toMatch(/fake-new-secret-for-test|fake-response-key|fake-header-value|provider\.test/);
  });

  it('设置读取失败时明确说明失败，不伪造已加载设置', async () => {
    apiGet.mockRejectedValue(new Error('fixture unavailable'));
    renderPage('ai-behavior');
    await waitFor(() => expect(useUiStore.getState().pageContext.markdown).toContain('读取失败'));
    expect(useUiStore.getState().pageContext.markdown).not.toContain('请求重试次数：4');
    expect(useUiStore.getState().pageContext.kind).toBe('settings');
  });
});
