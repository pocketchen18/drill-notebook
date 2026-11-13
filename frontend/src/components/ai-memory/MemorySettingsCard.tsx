import { useEffect, useRef } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Message, Switch, Typography } from '@arco-design/web-react';
import { friendlyMessage } from '../../lib/errors';
import type { ConsentPurpose, MemorySettings } from '../../lib/aiMemoryTypes';
import {
  createMemoryVault,
  getMemorySettings,
  grantMemoryConsent,
  unlockMemoryVault,
  updateMemorySettings
} from '../../lib/aiMemoryApi';
import { pageContextRows, usePageContextSection } from '../PageContextProvider';

/**
 * 记忆的总开关。首次开启时自动建指纹库、自动授权当前 AI 连接，维护细节不再暴露给用户。
 */

const PURPOSES: ConsentPurpose[] = ['MEMORY_USE', 'MEMORY_MAINTENANCE'];

function newOperationId(): string {
  return globalThis.crypto?.randomUUID?.() ?? `mem-${Date.now()}-${Math.random().toString(36).slice(2)}`;
}

function consentKey(purpose: ConsentPurpose, fingerprint: string): string {
  return `${purpose}:${fingerprint}`;
}

export function MemorySettingsCard(): JSX.Element {
  const queryClient = useQueryClient();
  const settingsQuery = useQuery({ queryKey: ['ai-memory-settings'], queryFn: getMemorySettings });
  const settings = settingsQuery.data;
  usePageContextSection('memory-settings', pageContextRows('记忆', settings ? [
    ['启用记忆', settings.enabled],
    ['自动整理对话', settings.enabled ? settings.autoExtract && settings.compressSession : undefined],
    ['当前 AI 连接', Object.values(settings.consents).some(consent => consent?.target?.configured && consent.target.keyPresent) ? '已配置' : '未配置'],
    ['存储', '记忆保存在本机，只交给当前配置的 AI 连接']
  ] : [['状态', settingsQuery.isPending ? '正在读取记忆设置…' : '记忆设置读取失败']]));
  // 同一次页面生命周期里只自动授权一次，失败也不在后台反复重试
  const attemptedGrants = useRef(new Set<string>());

  const refresh = (): void => {
    void queryClient.invalidateQueries({ queryKey: ['ai-memory-settings'] });
    void queryClient.invalidateQueries({ queryKey: ['ai-memory-list'] });
    void queryClient.invalidateQueries({ queryKey: ['ai-memory-status'] });
  };

  const patchMutation = useMutation({
    mutationFn: (changes: Parameters<typeof updateMemorySettings>[1]) => {
      if (!settings) throw new Error('设置还没读到，稍后再试');
      return updateMemorySettings(settings.revision, changes);
    },
    onSuccess: refresh,
    onError: (error) => {
      refresh();
      Message.warning(friendlyMessage(error, '记忆设置未保存'));
    }
  });

  /** 一个开关做完建库、解锁、授权和四个开关，用户不需要逐项确认。 */
  const enableMutation = useMutation({
    mutationFn: async () => {
      let current: MemorySettings = await getMemorySettings();
      if (!current.initialized) {
        await createMemoryVault('fingerprint', undefined, newOperationId());
        current = await getMemorySettings();
      }
      if (!current.unlocked) {
        await unlockMemoryVault();
        current = await getMemorySettings();
      }
      let revision = current.revision;
      for (const purpose of PURPOSES) {
        const consent = current.consents[purpose];
        const target = consent?.target;
        if (!consent || !target?.configured || !target.keyPresent || !target.fingerprint
            || consent.granted) continue;
        attemptedGrants.current.add(consentKey(purpose, target.fingerprint));
        const granted = await grantMemoryConsent(purpose, target.fingerprint, current.policyVersion);
        revision = granted.revision;
      }
      await updateMemorySettings(revision, {
        enabled: true,
        autoExtract: true,
        compressSession: true,
        autoApplyLowRisk: true
      });
    },
    onSuccess: () => {
      refresh();
      Message.success('记忆已开启');
    },
    onError: (error) => {
      refresh();
      Message.error(friendlyMessage(error, '开启记忆失败'));
    }
  });

  // 已经开启记忆但换了模型：打开此页时自动授权新目标，不再让用户点一次
  useEffect(() => {
    if (!settings?.enabled) return;
    const pending = PURPOSES.map((purpose) => [purpose, settings.consents[purpose]] as const)
      .filter(([purpose, consent]) => {
        const target = consent?.target;
        const fingerprint = target?.fingerprint;
        return Boolean(fingerprint && target?.configured && target.keyPresent) && !consent?.granted
          && !attemptedGrants.current.has(consentKey(purpose, fingerprint!));
      });
    if (pending.length === 0) return;
    let cancelled = false;
    void (async () => {
      try {
        for (const [purpose, consent] of pending) {
          const fingerprint = consent!.target!.fingerprint!;
          attemptedGrants.current.add(consentKey(purpose, fingerprint));
          await grantMemoryConsent(purpose, fingerprint, settings.policyVersion);
        }
        if (!cancelled) await queryClient.invalidateQueries({ queryKey: ['ai-memory-settings'] });
      } catch (error) {
        for (const [purpose, consent] of pending) {
          attemptedGrants.current.delete(consentKey(purpose, consent!.target?.fingerprint ?? ''));
        }
        if (!cancelled) Message.warning(friendlyMessage(error, '记忆授权未完成，请重新开启记忆'));
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [settings, queryClient]);

  if (settingsQuery.isPending) {
    return <section className="panel"><div className="panel-body">正在读取记忆设置…</div></section>;
  }
  if (!settings) {
    return (
      <section className="panel">
        <div className="panel-body">
          {settingsQuery.error ? friendlyMessage(settingsQuery.error, '记忆设置读取失败') : '没有读到记忆设置'}
        </div>
      </section>
    );
  }

  const targetReady = Object.values(settings.consents).some(
    (consent) => consent?.target?.configured && consent?.target?.keyPresent
  );
  const autoOrganize = settings.autoExtract && settings.compressSession;

  return (
    <section className="panel">
      <div className="panel-header"><h2>记忆</h2></div>
      <div className="panel-body form-stack">
        <div className="settings-row">
          <div>
            <Typography.Text bold>启用记忆</Typography.Text>
            <br />
            <Typography.Text type="secondary">
              开启后自动记住对话里明确说过的偏好和目标，保存在本机。
            </Typography.Text>
            {settings.enabled && !targetReady && (
              <>
                <br />
                <Typography.Text type="warning">当前 AI 连接未配置，记忆暂时不会参与对话。</Typography.Text>
              </>
            )}
          </div>
          <Switch
            checked={settings.enabled}
            loading={enableMutation.isPending || patchMutation.isPending}
            onChange={(checked) => {
              if (!checked) {
                patchMutation.mutate({ enabled: false });
                return;
              }
              enableMutation.mutate();
            }}
            aria-label="启用记忆"
          />
        </div>

        {settings.enabled && (
          <div className="settings-row">
            <div>
              <Typography.Text bold>自动整理对话</Typography.Text>
              <br />
              <Typography.Text type="secondary">关闭后仍可手动添加，已有记忆不受影响。</Typography.Text>
            </div>
            <Switch
              checked={autoOrganize}
              loading={patchMutation.isPending}
              onChange={(checked) => patchMutation.mutate({ autoExtract: checked, compressSession: checked })}
              aria-label="自动整理对话"
            />
          </div>
        )}

        <Typography.Text type="secondary">记忆只交给你当前配置的 AI 连接。</Typography.Text>
      </div>
    </section>
  );
}
