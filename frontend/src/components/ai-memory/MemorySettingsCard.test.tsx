import { beforeEach, describe, expect, it, vi } from 'vitest';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemorySettingsCard } from './MemorySettingsCard';

/**
 * 简化后的记忆总开关：一个开关负责建库/解锁/授权/写开关；
 * 密码库只在锁定时露出解锁框；换模型后打开页面自动接受新目标。
 */

const { apiGet, apiPost, apiPut } = vi.hoisted(() => ({
  apiGet: vi.fn(), apiPost: vi.fn(), apiPut: vi.fn()
}));

vi.mock('../../lib/api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../lib/api')>();
  return {
    ...actual,
    get: (...args: unknown[]) => apiGet(...args),
    post: (...args: unknown[]) => apiPost(...args),
    put: (...args: unknown[]) => apiPut(...args)
  };
});

function target(purpose: 'MEMORY_USE' | 'MEMORY_MAINTENANCE', fingerprint = 'fp-chat-1') {
  return {
    purpose,
    slot: 'chat',
    host: 'api.example.com',
    model: 'gpt-x',
    configured: true,
    keyPresent: true,
    fingerprint
  };
}

function consent(purpose: 'MEMORY_USE' | 'MEMORY_MAINTENANCE', granted: boolean, fingerprint = 'fp-chat-1') {
  return {
    purpose,
    state: granted ? 'GRANTED' : 'MISSING',
    granted,
    target: target(purpose, fingerprint),
    ...(granted ? { storedFingerprint: fingerprint } : {})
  };
}

function makeSettings(overrides: Record<string, unknown> = {}): Record<string, unknown> {
  return {
    revision: 4,
    enabled: false,
    autoExtract: false,
    compressSession: false,
    autoApplyLowRisk: true,
    policyVersion: 'memory-policy-v1',
    maintenanceModelMode: 'chat',
    dailyCallLimit: 20,
    dailyTokenLimit: 30000,
    budgetTimezone: 'Asia/Shanghai',
    memoryHistoryFloor: 0,
    consents: {
      MEMORY_USE: consent('MEMORY_USE', true),
      MEMORY_MAINTENANCE: consent('MEMORY_MAINTENANCE', true)
    },
    limits: { activeFacts: 2000, pendingFacts: 500 },
    initialized: true,
    unlocked: true,
    sessionUnlockedCount: 0,
    mode: 'fingerprint',
    vaultRevision: 2,
    ...overrides
  };
}

let current = makeSettings();

function renderCard(): void {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  render(<QueryClientProvider client={client}><MemorySettingsCard /></QueryClientProvider>);
}

beforeEach(() => {
  vi.clearAllMocks();
  current = makeSettings();
  apiGet.mockImplementation((url: string) => {
    if (url === '/api/ai/memory/settings') return Promise.resolve(current);
    return Promise.resolve({});
  });
  apiPost.mockImplementation((url: string, body: Record<string, unknown>) => {
    if (url === '/api/ai/memory/vault') {
      current = { ...current, initialized: true, unlocked: true, mode: body.mode };
      return Promise.resolve(current);
    }
    if (url === '/api/ai/memory/vault/unlock') {
      current = { ...current, unlocked: true, sessionUnlockedCount: 0 };
      return Promise.resolve(current);
    }
    if (url === '/api/ai/memory/consents') {
      const purpose = String(body.purpose);
      current = {
        ...current,
        revision: Number(current.revision) + 1,
        consents: {
          ...(current.consents as Record<string, unknown>),
          [purpose]: { ...(current.consents as Record<string, Record<string, unknown>>)[purpose], granted: true, state: 'GRANTED' }
        }
      };
      return Promise.resolve({ revision: current.revision, purpose, consented: true });
    }
    return Promise.resolve({});
  });
  apiPut.mockImplementation((_url: string, body: Record<string, unknown>) => {
    const { revision: _revision, ...changes } = body;
    current = { ...current, ...changes, revision: Number(current.revision) + 1 };
    return Promise.resolve({ revision: current.revision });
  });
});

describe('MemorySettingsCard', () => {
  it('关闭记忆只发一次开关 CAS，带刚读到的 revision', async () => {
    current = makeSettings({ enabled: true, autoExtract: true, compressSession: true });
    renderCard();

    fireEvent.click(await screen.findByRole('switch', { name: '启用记忆' }));

    await waitFor(() => expect(apiPut).toHaveBeenCalledWith('/api/ai/memory/settings', {
      revision: 4,
      enabled: false
    }));
    expect(apiPut).toHaveBeenCalledTimes(1);
  });

  it('首次开启：自动建指纹库、授权两个用途，再一次性写好所有开关', async () => {
    current = makeSettings({
      initialized: false,
      unlocked: false,
      mode: undefined,
      consents: {
        MEMORY_USE: consent('MEMORY_USE', false),
        MEMORY_MAINTENANCE: consent('MEMORY_MAINTENANCE', false)
      }
    });
    renderCard();

    fireEvent.click(await screen.findByRole('switch', { name: '启用记忆' }));

    await waitFor(() => expect(apiPut).toHaveBeenCalledWith('/api/ai/memory/settings', {
      revision: 6,
      enabled: true,
      autoExtract: true,
      compressSession: true,
      autoApplyLowRisk: true
    }));
    expect(apiPost).toHaveBeenCalledWith('/api/ai/memory/vault', {
      mode: 'fingerprint',
      password: undefined,
      operationId: expect.any(String)
    });
    expect(apiPost).toHaveBeenCalledWith('/api/ai/memory/consents', {
      purpose: 'MEMORY_USE',
      targetFingerprint: 'fp-chat-1',
      policyVersion: 'memory-policy-v1'
    });
    expect(apiPost).toHaveBeenCalledWith('/api/ai/memory/consents', {
      purpose: 'MEMORY_MAINTENANCE',
      targetFingerprint: 'fp-chat-1',
      policyVersion: 'memory-policy-v1'
    });
  });

  it('指纹库锁定时不问密码，直接解锁后开启', async () => {
    current = makeSettings({ unlocked: false, mode: 'fingerprint' });
    renderCard();

    fireEvent.click(await screen.findByRole('switch', { name: '启用记忆' }));

    await waitFor(() => expect(apiPost).toHaveBeenCalledWith('/api/ai/memory/vault/unlock', {
      password: undefined
    }));
    await waitFor(() => expect(apiPut).toHaveBeenCalledWith('/api/ai/memory/settings', {
      revision: 4,
      enabled: true,
      autoExtract: true,
      compressSession: true,
      autoApplyLowRisk: true
    }));
    expect(screen.queryByLabelText('解锁用主密码')).toBeNull();
  });

  it('密码库锁定也不再显示密码框', async () => {
    current = makeSettings({ unlocked: false, mode: 'password' });
    renderCard();

    expect(await screen.findByRole('switch', { name: '启用记忆' })).toBeTruthy();
    expect(screen.queryByLabelText('解锁用主密码')).toBeNull();
    expect(screen.queryByRole('button', { name: '解锁' })).toBeNull();
  });

  it('开启状态下换模型，打开页面自动接受新目标指纹', async () => {
    current = makeSettings({
      enabled: true,
      autoExtract: true,
      compressSession: true,
      consents: {
        MEMORY_USE: consent('MEMORY_USE', false, 'fp-new-model'),
        MEMORY_MAINTENANCE: consent('MEMORY_MAINTENANCE', true)
      }
    });
    renderCard();

    await waitFor(() => expect(apiPost).toHaveBeenCalledWith('/api/ai/memory/consents', {
      purpose: 'MEMORY_USE',
      targetFingerprint: 'fp-new-model',
      policyVersion: 'memory-policy-v1'
    }));
    expect(screen.queryByText('外发授权')).toBeNull();
    expect(screen.queryByText('整理预算')).toBeNull();
  });

  it('目标未配置时只提示，不发授权请求', async () => {
    const missing = {
      purpose: 'MEMORY_USE',
      state: 'TARGET_MISSING',
      granted: false,
      target: { purpose: 'MEMORY_USE', slot: 'chat', configured: false, keyPresent: false }
    };
    current = makeSettings({
      enabled: true,
      consents: { MEMORY_USE: missing, MEMORY_MAINTENANCE: missing }
    });
    renderCard();

    expect(await screen.findByText('当前 AI 连接未配置，记忆暂时不会参与对话。')).toBeTruthy();
    expect(apiPost).not.toHaveBeenCalled();
  });

  it('自动整理开关同时写提取与摘要两个键', async () => {
    current = makeSettings({ enabled: true, autoExtract: true, compressSession: true });
    renderCard();

    fireEvent.click(await screen.findByRole('switch', { name: '自动整理对话' }));

    await waitFor(() => expect(apiPut).toHaveBeenCalledWith('/api/ai/memory/settings', {
      revision: 4,
      autoExtract: false,
      compressSession: false
    }));
  });

  it('旧的防御性区域不再出现', async () => {
    current = makeSettings({ enabled: true, autoExtract: true, compressSession: true });
    renderCard();

    await screen.findByRole('switch', { name: '启用记忆' });
    expect(screen.queryByText('长期记忆')).toBeNull();
    expect(screen.queryByText('会话续接摘要')).toBeNull();
    expect(screen.queryByText('低风险候选自动生效')).toBeNull();
    expect(screen.queryByText('维护模型来源')).toBeNull();
    expect(screen.queryByText('每日次数与 Token')).toBeNull();
    expect(screen.queryByText('已解锁会话 0')).toBeNull();
  });
});
