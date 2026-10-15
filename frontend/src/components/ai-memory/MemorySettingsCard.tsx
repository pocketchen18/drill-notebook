import { useEffect, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Button, Input, InputNumber, Message, Modal, Radio, Select, Space, Switch, Tag, Typography } from '@arco-design/web-react';
import { Lock, RefreshCw, Trash2, Unlock } from 'lucide-react';
import { friendlyMessage } from '../../lib/errors';
import type { ConsentPurpose, MaintenanceGate } from '../../lib/aiMemoryTypes';
import {
  changeMemoryVaultPassword,
  createMemoryVault,
  getMemorySettings,
  getMemoryStatus,
  grantMemoryConsent,
  lockMemoryVault,
  resetMemoryVault,
  revokeMemoryConsent,
  unlockMemoryVault,
  updateMemorySettings
} from '../../lib/aiMemoryApi';

/**
 * 记忆设置卡（§12 第一张）：四道开关、实际维护模型、两种授权、预算与记忆库解锁状态。
 *
 * <p>开关彼此不合并：关闭长期记忆不等于关闭会话摘要，反之亦然，所以每个开关单独发一次 CAS。
 * 主密码只活在组件内存里，用完即清，既不写 localStorage 也不回显到任何日志。</p>
 */

const GATE_LABELS: Record<MaintenanceGate, string> = {
  ALLOWED: '已就绪',
  MEMORY_DISABLED: '已暂停',
  AUTO_EXTRACT_DISABLED: '自动提取已关闭',
  COMPRESS_DISABLED: '会话摘要已关闭',
  VAULT_MISSING: '还没有记忆库',
  LOCKED: '等待解锁',
  CONSENT_MISSING: '等待外发授权',
  CONSENT_STALE: '授权已失效，需重新授予',
  TARGET_MISSING: '维护模型未配置',
  SCOPE_INVALID: '作用域不可用',
  QUOTA_ACTIVE_FULL: '生效记忆已达上限',
  QUOTA_PENDING_FULL: '待确认已达上限'
};

const CONSENT_TITLES: Record<ConsentPurpose, string> = {
  MEMORY_USE: '对话时把记忆交给模型',
  MEMORY_MAINTENANCE: '把聊天内容交给后台整理'
};

function newOperationId(): string {
  return globalThis.crypto?.randomUUID?.() ?? `mem-${Date.now()}-${Math.random().toString(36).slice(2)}`;
}

/** 后端用 ZoneId.of 校验，"跟随系统"这种字面值会被拒；候选里必须带上当前已存的值。 */
function timezoneOptions(current: string): Array<{ value: string; label: string }> {
  return Array.from(new Set([current, 'Asia/Shanghai', 'UTC'])).map((zone) => ({ value: zone, label: zone }));
}

export function MemorySettingsCard(): JSX.Element {
  const queryClient = useQueryClient();
  const settingsQuery = useQuery({ queryKey: ['ai-memory-settings'], queryFn: getMemorySettings });
  const statusQuery = useQuery({ queryKey: ['ai-memory-status'], queryFn: getMemoryStatus });
  const settings = settingsQuery.data;
  const status = statusQuery.data;

  const [mode, setMode] = useState<'fingerprint' | 'password'>('fingerprint');
  const [password, setPassword] = useState('');
  const [oldPassword, setOldPassword] = useState('');
  const [resetPassword, setResetPassword] = useState('');
  const [resetVisible, setResetVisible] = useState(false);

  const refresh = (): void => {
    void queryClient.invalidateQueries({ queryKey: ['ai-memory-settings'] });
    void queryClient.invalidateQueries({ queryKey: ['ai-memory-status'] });
    void queryClient.invalidateQueries({ queryKey: ['ai-memory-list'] });
  };

  // 后台任务活跃时才轮询状态，空闲时不打扰（§12：轻量状态，不用转圈遮挡别处）。
  const busy = (status?.queuedJobs ?? 0) + (status?.retryJobs ?? 0) > 0;
  useEffect(() => {
    if (!busy) return;
    const timer = setInterval(() => {
      void queryClient.invalidateQueries({ queryKey: ['ai-memory-status'] });
    }, 5000);
    return () => clearInterval(timer);
  }, [busy, queryClient]);

  const patchMutation = useMutation({
    mutationFn: (changes: Parameters<typeof updateMemorySettings>[1]) => {
      if (!settings) throw new Error('设置还没读到，稍后再试');
      return updateMemorySettings(settings.revision, changes);
    },
    onSuccess: refresh,
    onError: (error) => {
      // 409 只带最新 revision：本地缓存作废，让用户按最新值重开一次，不替用户猜
      refresh();
      Message.warning(friendlyMessage(error, '记忆设置未保存'));
    }
  });

  const createMutation = useMutation({
    mutationFn: () => createMemoryVault(mode, mode === 'password' ? password : undefined, newOperationId()),
    onSuccess: () => {
      setPassword('');
      refresh();
      Message.success('记忆库已创建');
    },
    onError: (error) => Message.error(friendlyMessage(error, '创建记忆库失败'))
  });

  const unlockMutation = useMutation({
    mutationFn: () => unlockMemoryVault(settings?.mode === 'password' ? password : undefined),
    onSuccess: () => {
      setPassword('');
      refresh();
      Message.success('记忆库已解锁');
    },
    onError: (error) => Message.error(friendlyMessage(error, '解锁失败'))
  });

  const lockMutation = useMutation({
    mutationFn: lockMemoryVault,
    onSuccess: () => {
      refresh();
      Message.success('记忆库已锁定，正文不再可读');
    },
    onError: (error) => Message.error(friendlyMessage(error, '锁定失败'))
  });

  const changePasswordMutation = useMutation({
    mutationFn: () => changeMemoryVaultPassword(oldPassword, password),
    onSuccess: () => {
      setOldPassword('');
      setPassword('');
      refresh();
      Message.success('主密码已更换');
    },
    onError: (error) => Message.error(friendlyMessage(error, '更换主密码失败'))
  });

  const resetMutation = useMutation({
    mutationFn: () => resetMemoryVault(mode, mode === 'password' ? resetPassword : undefined, newOperationId()),
    onSuccess: () => {
      setResetVisible(false);
      setResetPassword('');
      refresh();
      Message.success('旧记忆库已重置，开关保持原样但需要重新授权');
    },
    onError: (error) => Message.error(friendlyMessage(error, '重置失败'))
  });

  const consentMutation = useMutation({
    mutationFn: (purpose: ConsentPurpose) => {
      const target = settings?.consents[purpose]?.target;
      if (!target?.fingerprint || !settings) throw new Error('还没解析出实际目标，无法授权');
      return grantMemoryConsent(purpose, target.fingerprint, settings.policyVersion);
    },
    onSuccess: refresh,
    onError: (error) => Message.error(friendlyMessage(error, '授权失败'))
  });

  const revokeMutation = useMutation({
    mutationFn: (purpose: ConsentPurpose) => revokeMemoryConsent(purpose),
    onSuccess: refresh,
    onError: (error) => Message.error(friendlyMessage(error, '撤销授权失败'))
  });

  if (settingsQuery.isPending) return <section className="panel"><div className="panel-body">正在读取记忆设置…</div></section>;
  if (!settings) return <section className="panel"><div className="panel-body">{settingsQuery.error ? friendlyMessage(settingsQuery.error, '记忆设置读取失败') : '没有读到记忆设置'}</div></section>;

  return (
    <>
      <section className="panel">
        <div className="panel-header">
          <h2>记忆库</h2>
          <Space size="small">
            <Tag color={settings.unlocked ? 'green' : settings.initialized ? 'orange' : 'gray'}>
              {settings.initialized ? (settings.unlocked ? '已解锁' : '已锁定') : '未创建'}
            </Tag>
            {status && (
              <Tag color={status.extraction.gate === 'ALLOWED' ? 'arcblue' : 'gold'}>
                {GATE_LABELS[status.extraction.gate]}
              </Tag>
            )}
          </Space>
        </div>
        <div className="panel-body form-stack">
          {!settings.initialized && (
            <div className="ai-memory-vault-form">
              <Typography.Text type="secondary">本地加密保存，主密码只用于解钥匙，不写入任何配置文件。</Typography.Text>
              <Space wrap size="small">
                <Radio.Group type='button' size="small" value={mode} onChange={(value) => setMode(value as 'fingerprint' | 'password')} aria-label="记忆库模式">
                  <Radio value="fingerprint">跟随系统指纹</Radio>
                  <Radio value="password">主密码</Radio>
                </Radio.Group>
                {mode === 'password' && (
                  <Input.Password placeholder="主密码" value={password} onChange={setPassword} aria-label="新记忆库主密码" />
                )}
                <Button type="primary" icon={<Unlock size={14} />} loading={createMutation.isPending} onClick={() => createMutation.mutate()}>
                  创建记忆库
                </Button>
              </Space>
            </div>
          )}

          {settings.initialized && (
            <div className="settings-row">
              <div>
                <Typography.Text bold>模式 {settings.mode}，已解锁会话 {settings.sessionUnlockedCount}</Typography.Text>
                <br />
                <Typography.Text type="secondary">锁定后清钥匙与缓存，飞行中的整理结果不再提交。</Typography.Text>
              </div>
              <Space wrap size="small">
                {!settings.unlocked && (
                  <>
                    {settings.mode === 'password' && (
                      <Input.Password placeholder="主密码" value={password} onChange={setPassword} aria-label="解锁用主密码" />
                    )}
                    <Button icon={<Unlock size={14} />} loading={unlockMutation.isPending} onClick={() => unlockMutation.mutate()}>
                      解锁
                    </Button>
                  </>
                )}
                {settings.unlocked && (
                  <Button icon={<Lock size={14} />} loading={lockMutation.isPending} onClick={() => lockMutation.mutate()}>
                    锁定
                  </Button>
                )}
                <Button type="text" icon={<RefreshCw size={14} />} onClick={refresh} aria-label="重新读取记忆状态">
                  刷新
                </Button>
              </Space>
            </div>
          )}

          {settings.initialized && settings.mode === 'password' && settings.unlocked && (
            <div className="settings-row">
              <div>
                <Typography.Text bold>更换主密码</Typography.Text>
                <br />
                <Typography.Text type="secondary">只重新包装钥匙，记忆正文不动。</Typography.Text>
              </div>
              <Space wrap size="small">
                <Input.Password placeholder="当前主密码" value={oldPassword} onChange={setOldPassword} aria-label="当前主密码" />
                <Input.Password placeholder="新主密码" value={password} onChange={setPassword} aria-label="新主密码" />
                <Button loading={changePasswordMutation.isPending} onClick={() => changePasswordMutation.mutate()}>更换</Button>
              </Space>
            </div>
          )}

          {settings.initialized && (
            <div className="settings-row">
              <div>
                <Typography.Text bold>忘记密码</Typography.Text>
                <br />
                <Typography.Text type="secondary">重置会删除旧库的派生记忆，开关保留但需重新授权。</Typography.Text>
              </div>
              <Button status="danger" type="outline" icon={<Trash2 size={14} />} onClick={() => setResetVisible(true)}>
                重置记忆库
              </Button>
            </div>
          )}
        </div>
      </section>

      <section className="panel">
        <div className="panel-header"><h2>开关</h2></div>
        <div className="panel-body form-stack">
          <SwitchRow
            title="长期记忆"
            hint="关闭后不再使用也不再自动整理，已存的记忆仍在。"
            checked={settings.enabled}
            pending={patchMutation.isPending}
            onChange={(checked) => patchMutation.mutate({ enabled: checked })}
          />
          <SwitchRow
            title="自动提取记忆"
            hint="后台把明确表达的偏好与目标整理成候选。"
            checked={settings.autoExtract}
            pending={patchMutation.isPending}
            onChange={(checked) => patchMutation.mutate({ autoExtract: checked })}
          />
          <SwitchRow
            title="会话续接摘要"
            hint="独立开关：与长期记忆互不牵连，只用于接续当前任务。"
            checked={settings.compressSession}
            pending={patchMutation.isPending}
            onChange={(checked) => patchMutation.mutate({ compressSession: checked })}
          />
          <SwitchRow
            title="低风险候选自动生效"
            hint="只允许服务端判定为确定不冲突的候选直接落库。"
            checked={settings.autoApplyLowRisk}
            pending={patchMutation.isPending}
            onChange={(checked) => patchMutation.mutate({ autoApplyLowRisk: checked })}
          />
        </div>
      </section>

      <section className="panel">
        <div className="panel-header"><h2>外发授权</h2></div>
        <div className="panel-body form-stack">
          {(['MEMORY_USE', 'MEMORY_MAINTENANCE'] as ConsentPurpose[]).map((purpose) => {
            const consent = settings.consents[purpose];
            const target = consent?.target;
            return (
              <div className="settings-row" key={purpose}>
                <div>
                  <Typography.Text bold>{CONSENT_TITLES[purpose]}</Typography.Text>
                  <br />
                  <Typography.Text type="secondary">
                    {target?.configured
                      ? `${target.slot} · ${target.host || '未知地址'} · ${target.model || '未填模型'}${target.keyPresent ? '' : '（缺 Key）'}`
                      : '该槽位还没配好端点与模型。'}
                  </Typography.Text>
                </div>
                <Space size="small">
                  <Tag color={consent?.granted ? 'green' : consent?.state === 'STALE' ? 'red' : 'gray'}>
                    {consent?.granted ? '已授权' : consent?.state === 'STALE' ? '已失效' : consent?.state === 'TARGET_MISSING' ? '无目标' : '未授权'}
                  </Tag>
                  {!consent?.granted && (
                    <Button
                      size="small"
                      loading={consentMutation.isPending}
                      onClick={() => consentMutation.mutate(purpose)}
                    >
                      授权
                    </Button>
                  )}
                  {consent?.granted && (
                    <Button size="small" type="text" loading={revokeMutation.isPending} onClick={() => revokeMutation.mutate(purpose)}>
                      撤销
                    </Button>
                  )}
                </Space>
              </div>
            );
          })}
          <Typography.Text type="secondary">
            换模型或换端点后授权自动作废，需在「AI 连接」标签重新配置。
          </Typography.Text>
        </div>
      </section>

      <section className="panel">
        <div className="panel-header"><h2>整理预算</h2></div>
        <div className="panel-body form-stack">
          <div className="settings-row">
            <div>
              <Typography.Text bold>维护模型来源</Typography.Text>
              <br />
              <Typography.Text type="secondary">专用槽位不会退回主模型。</Typography.Text>
            </div>
            <Radio.Group
              type="button"
              size="small"
              value={settings.maintenanceModelMode}
              onChange={(value) => patchMutation.mutate({ policy: { maintenanceModelMode: value as 'chat' | 'dedicated' } })}
              aria-label="维护模型来源"
            >
              <Radio value="chat">复用对话模型</Radio>
              <Radio value="dedicated">专用记忆模型</Radio>
            </Radio.Group>
          </div>
          <div className="settings-row">
            <div>
              <Typography.Text bold>每日次数与 Token</Typography.Text>
              <br />
              <Typography.Text type="secondary">用尽当天暂停，次日按预算时区恢复。</Typography.Text>
            </div>
            <Space size="small" wrap>
              <InputNumber
                min={1}
                max={1000}
                value={settings.dailyCallLimit}
                onChange={(value) => patchMutation.mutate({ policy: { dailyCallLimit: Number(value) } })}
                aria-label="每日调用次数上限"
                style={{ width: 110 }}
              />
              <InputNumber
                min={1000}
                max={1000000}
                step={1000}
                value={settings.dailyTokenLimit}
                onChange={(value) => patchMutation.mutate({ policy: { dailyTokenLimit: Number(value) } })}
                aria-label="每日 Token 上限"
                style={{ width: 130 }}
              />
              <Select
                value={settings.budgetTimezone}
                onChange={(value) => patchMutation.mutate({ policy: { budgetTimezone: String(value) } })}
                aria-label="预算时区"
                style={{ width: 180 }}
                options={timezoneOptions(settings.budgetTimezone)}
              />
            </Space>
          </div>
          {status && (
            <div className="ai-memory-counts">
              <Typography.Text bold>当前统计</Typography.Text>
              <Typography.Text type="secondary">
                生效 {status.activeFacts ?? 0}/{status.limits.activeFacts} · 待确认 {status.pendingFacts ?? 0} · 建议 {status.pendingProposals ?? 0} · 抑制 {status.suppressions ?? 0} · 排队 {status.queuedJobs} · 重试 {status.retryJobs}
              </Typography.Text>
            </div>
          )}
        </div>
      </section>

      <Modal
        title="重置记忆库"
        visible={resetVisible}
        okText="确认重置"
        cancelText="取消"
        okButtonProps={{ status: 'danger' }}
        confirmLoading={resetMutation.isPending}
        onOk={() => resetMutation.mutate()}
        onCancel={() => setResetVisible(false)}
      >
        <div className="form-stack">
          <Typography.Text type="secondary">旧库的派生记忆与检查点会整份删除，此操作不可撤销。</Typography.Text>
          {settings.mode === 'password' && (
            <Input.Password placeholder="新库主密码" value={resetPassword} onChange={setResetPassword} aria-label="重置后的新主密码" />
          )}
        </div>
      </Modal>
    </>
  );
}

function SwitchRow(props: {
  title: string;
  hint: string;
  checked: boolean;
  pending: boolean;
  onChange: (checked: boolean) => void;
}): JSX.Element {
  return (
    <div className="settings-row">
      <div>
        <Typography.Text bold>{props.title}</Typography.Text>
        <br />
        <Typography.Text type="secondary">{props.hint}</Typography.Text>
      </div>
      <Switch
        checked={props.checked}
        loading={props.pending}
        onChange={props.onChange}
        aria-label={props.title}
      />
    </div>
  );
}
