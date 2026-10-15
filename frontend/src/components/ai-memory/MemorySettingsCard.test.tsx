import { beforeEach, describe, expect, it, vi } from 'vitest';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MemorySettingsCard } from './MemorySettingsCard';
import { ApiError } from '../../lib/api';

/**
 * 记忆设置卡（§12）里最容易写错的三件事：CAS 用的是刚读到的 revision、
 * 授权比对的是服务端解析出的目标指纹、主密码只出现在请求体。
 */

const { apiGet, apiPost, apiPut } = vi.hoisted(() => ({
  apiGet: vi.fn(), apiPost: vi.fn(), apiPut: vi.fn()
}));

/** ApiError 必须留真身：组件里靠 `instanceof` 判 code，整体替换会把它抹掉。 */
vi.mock('../../lib/api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../lib/api')>();
  return {
    ...actual,
    get: (...args: unknown[]) => apiGet(...args),
    post: (...args: unknown[]) => apiPost(...args),
    put: (...args: unknown[]) => apiPut(...args)
  };
});

function settings(overrides: Record<string, unknown> = {}): Record<string, unknown> {
  return {
    revision: 4,
    enabled: true,
    autoExtract: true,
    compressSession: false,
    autoApplyLowRisk: true,
    policyVersion: 'memory-policy-v1',
    maintenanceModelMode: 'chat',
    dailyCallLimit: 20,
    dailyTokenLimit: 30000,
    budgetTimezone: 'Asia/Shanghai',
    memoryHistoryFloor: 0,
    consents: {
      MEMORY_USE: {
        purpose: 'MEMORY_USE',
        state: 'MISSING',
        granted: false,
        target: {
          purpose: 'MEMORY_USE', slot: 'chat', host: 'api.example.com', model: 'gpt-x',
          configured: true, keyPresent: true, fingerprint: 'fp-chat-1'
        }
      },
      MEMORY_MAINTENANCE: {
        purpose: 'MEMORY_MAINTENANCE',
        state: 'GRANTED',
        granted: true,
        target: {
          purpose: 'MEMORY_MAINTENANCE', slot: 'chat', host: 'api.example.com', model: 'gpt-x',
          configured: true, keyPresent: true, fingerprint: 'fp-chat-1'
        },
        storedFingerprint: 'fp-chat-1'
      }
    },
    limits: { activeFacts: 2000, pendingFacts: 500 },
    initialized: true,
    unlocked: true,
    sessionUnlockedCount: 1,
    mode: 'password',
    vaultRevision: 2,
    ...overrides
  };
}

function status(overrides: Record<string, unknown> = {}): Record<string, unknown> {
  return {
    initialized: true,
    unlocked: true,
    sessionUnlockedCount: 1,
    mode: 'password',
    limits: { activeFacts: 2000, pendingFacts: 500 },
    activeFacts: 12,
    pendingFacts: 0,
    pendingProposals: 0,
    suppressions: 0,
    queuedJobs: 0,
    retryJobs: 0,
    extraction: { gate: 'ALLOWED' },
    ...overrides
  };
}

let settingsReads = 0;

function renderCard(): void {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  render(<QueryClientProvider client={client}><MemorySettingsCard /></QueryClientProvider>);
}

beforeEach(() => {
  vi.clearAllMocks();
  localStorage.clear();
  settingsReads = 0;
  apiGet.mockImplementation((url: string) => {
    if (url === '/api/ai/memory/settings') {
      settingsReads += 1;
      return Promise.resolve(settings());
    }
    if (url === '/api/ai/memory/status') return Promise.resolve(status());
    return Promise.resolve({});
  });
  apiPut.mockResolvedValue({ revision: 5 });
  apiPost.mockResolvedValue({});
});

describe('MemorySettingsCard', () => {
  it('每个开关单独发一次 CAS，带的是刚读到的 settings revision', async () => {
    renderCard();
    fireEvent.click(await screen.findByRole('switch', { name: '长期记忆' }));

    await waitFor(() => expect(apiPut).toHaveBeenCalledWith('/api/ai/memory/settings', {
      revision: 4,
      enabled: false
    }));
    expect(apiPut).toHaveBeenCalledTimes(1);
  });

  it('设置冲突（409）要说给用户并作废缓存重读，不假装已保存', async () => {
    apiPut.mockRejectedValue(new ApiError('MEMORY_SETTINGS_CONFLICT', '记忆设置刚被改过，请重试', 409, { details: { revision: 9 } }));

    renderCard();
    fireEvent.click(await screen.findByRole('switch', { name: '会话续接摘要' }));

    expect(await screen.findByText('记忆设置刚被改过，请重试')).toBeTruthy();
    await waitFor(() => expect(settingsReads).toBeGreaterThanOrEqual(2));
    expect(screen.getByRole('switch', { name: '会话续接摘要' })).toHaveAttribute('aria-checked', 'false');
  });

  it('授权提交的是服务端解析出的目标指纹与策略版本，不是界面文案', async () => {
    renderCard();
    const row = (await screen.findByText('对话时把记忆交给模型')).closest('.settings-row') as HTMLElement;
    fireEvent.click(within(row).getByRole('button', { name: '授权' }));

    await waitFor(() => expect(apiPost).toHaveBeenCalledWith('/api/ai/memory/consents', {
      purpose: 'MEMORY_USE',
      targetFingerprint: 'fp-chat-1',
      policyVersion: 'memory-policy-v1'
    }));
  });

  it('换模型后授权失效要看得见，重新授权认的是新指纹', async () => {
    apiGet.mockImplementation((url: string) => {
      if (url === '/api/ai/memory/settings') {
        return Promise.resolve(settings({
          consents: {
            MEMORY_USE: {
              purpose: 'MEMORY_USE', state: 'STALE', granted: false, storedFingerprint: 'fp-old-model',
              target: {
                purpose: 'MEMORY_USE', slot: 'chat', host: 'api.other.com', model: 'gpt-y',
                configured: true, keyPresent: true, fingerprint: 'fp-new-model'
              }
            }
          }
        }));
      }
      if (url === '/api/ai/memory/status') return Promise.resolve(status({ extraction: { gate: 'CONSENT_STALE' } }));
      return Promise.resolve({});
    });

    renderCard();
    const row = (await screen.findByText('对话时把记忆交给模型')).closest('.settings-row') as HTMLElement;
    expect(within(row).getByText('已失效')).toBeTruthy();
    expect(screen.getByText('授权已失效，需重新授予')).toBeTruthy();

    fireEvent.click(within(row).getByRole('button', { name: '授权' }));
    await waitFor(() => expect(apiPost).toHaveBeenCalledWith('/api/ai/memory/consents', {
      purpose: 'MEMORY_USE',
      targetFingerprint: 'fp-new-model',
      policyVersion: 'memory-policy-v1'
    }));
  });

  it('已授权的目标只给撤销，不再给授权按钮', async () => {
    renderCard();
    const row = (await screen.findByText('把聊天内容交给后台整理')).closest('.settings-row') as HTMLElement;

    expect(within(row).getByRole('button', { name: '撤销' })).toBeTruthy();
    expect(within(row).queryByRole('button', { name: '授权' })).toBeNull();
  });

  it('锁定时给解锁：主密码只进请求体，不进 URL', async () => {
    apiGet.mockImplementation((url: string) => {
      if (url === '/api/ai/memory/settings') return Promise.resolve(settings({ unlocked: false, sessionUnlockedCount: 0 }));
      if (url === '/api/ai/memory/status') return Promise.resolve(status({ unlocked: false, extraction: { gate: 'LOCKED' } }));
      return Promise.resolve({});
    });

    renderCard();
    const input = await screen.findByLabelText('解锁用主密码');
    fireEvent.change(input, { target: { value: 's3cr3t-master' } });
    fireEvent.click(screen.getByRole('button', { name: '解锁' }));

    await waitFor(() => expect(apiPost).toHaveBeenCalledWith('/api/ai/memory/vault/unlock', {
      password: 's3cr3t-master',
      sessionUnlocks: undefined
    }));
    expect(screen.queryByRole('button', { name: '锁定' })).toBeNull();
    expect(screen.getByText('等待解锁')).toBeTruthy();
  });

  it('还没建库时先讲清不落盘，再让创建走 mode+password', async () => {
    apiGet.mockImplementation((url: string) => {
      if (url === '/api/ai/memory/settings') return Promise.resolve(settings({ initialized: false, unlocked: false, mode: undefined }));
      if (url === '/api/ai/memory/status') return Promise.resolve(status({ initialized: false, extraction: { gate: 'VAULT_MISSING' } }));
      return Promise.resolve({});
    });

    renderCard();
    expect(await screen.findByText(/不写入任何配置文件/)).toBeTruthy();

    fireEvent.click(screen.getByRole('radio', { name: '主密码' }));
    fireEvent.change(screen.getByLabelText('新记忆库主密码'), { target: { value: 's3cr3t-master' } });
    fireEvent.click(screen.getByRole('button', { name: '创建记忆库' }));

    await waitFor(() => expect(apiPost).toHaveBeenCalledWith('/api/ai/memory/vault', {
      mode: 'password',
      password: 's3cr3t-master',
      operationId: expect.any(String)
    }));
  });
});
