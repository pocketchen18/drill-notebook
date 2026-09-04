import { useState, type KeyboardEvent } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Button, Card, Empty, Form, Input, InputNumber, Message, Modal, Popconfirm, Radio, Select, Space, Spin, Switch, Tag, Typography } from '@arco-design/web-react';
import { Edit3, Plus, RotateCcw, Sparkles, Star, Trash2 } from 'lucide-react';
import { AiModelSlotCard } from '../components/AiModelSlotCard';
import { EmbeddingSettingsCard } from '../components/EmbeddingSettingsCard';
import { DataManagementPanel } from '../components/DataManagementPanel';
import { ShortcutRecorder } from '../components/ShortcutRecorder';
import { useUiStore, type ThemeMode } from '../stores/uiStore';
import { listConfigs, createConfig, updateConfig, deleteConfig } from '../lib/review';
import type { SpacedRepetitionConfig } from '../lib/review';
import {
  SHORTCUT_ACTIONS,
  SHORTCUT_SCOPES,
  defaultShortcutConfig,
  describeAccelerator,
  findShortcutConflicts,
  isActionDefault,
  isDefaultShortcutConfig,
  ruleForAction,
  shortcutActionMeta,
  type ShortcutAction
} from '../lib/shortcuts';
import {
  LS_ENROLL_DEFAULT,
  LS_FORCE_ADVANCE,
  LS_PLAN_DEFAULT,
  LS_REMEMBER_VIEW_STATE,
  readBoolPref,
  writeBoolPref
} from '../lib/sessionPrefs';
import {
  clearViewState,
  persistViewState,
  readPageSlice,
  type SettingsTab
} from '../lib/viewState';
import {
  normalizeSessionCurveConfig,
  readSessionCurveConfig,
  writeSessionCurveConfig
} from '../lib/sessionCurve';
import type { SessionCurveConfig } from '../lib/sessionCurve';

const WRONG_STRATEGY_OPTIONS = [
  { value: 'reduce_half', label: '间隔减半（推荐）' },
  { value: 'reset', label: '重置到 1 天' },
  { value: 'reduce_quarter', label: '减少 25%' },
  { value: 'fixed', label: '固定天数' },
];

const PRIORITY_OPTIONS = [
  { value: 'due_first', label: '到期优先' },
  { value: 'worst_first', label: '最不熟优先' },
  { value: 'random', label: '随机顺序' },
  { value: 'mixed', label: '新旧混合' },
];

const DEFAULT_REVIEW_INTERVALS: Record<string, number> = {
  '1': 1, '2': 6, '3': 16, '4': 36, '5': 70,
};

// 设置页顶部横向 Tab（pill 分区，按本软件实际设置内容划分）：
// 常规=外观与全局行为；AI 连接=生成模型接入；嵌入与检索=知识库索引基础设施；学习与复习=学习行为与 SRS；数据管理=备份/导出/导入。
const TABS: Array<{ key: SettingsTab; label: string }> = [
  { key: 'general', label: '常规' },
  { key: 'ai', label: 'AI 连接' },
  { key: 'embedding', label: '嵌入与检索' },
  { key: 'study', label: '学习与复习' },
  { key: 'data', label: '数据管理' }
];

function readTabFromUrl(): SettingsTab {
  // HashRouter：查询参数在 hash 段内（#/settings?tab=ai），window.location.search 恒为空
  const hashQuery = window.location.hash.split('?')[1] ?? '';
  const tab = new URLSearchParams(hashQuery).get('tab');
  if (TABS.some((item) => item.key === tab)) return tab as SettingsTab;
  return readPageSlice('settings').tab ?? 'general';
}

export function SettingsPage(): JSX.Element {
  const queryClient = useQueryClient();
  const themeMode = useUiStore((state) => state.themeMode);
  const setThemeMode = useUiStore((state) => state.setThemeMode);
  const setAiOpen = useUiStore((state) => state.setAiOpen);
  const aiFabVisible = useUiStore((state) => state.aiFabVisible);
  const setAiFabVisible = useUiStore((state) => state.setAiFabVisible);
  const shortcutConfig = useUiStore((state) => state.shortcutConfig);
  const setShortcutConfig = useUiStore((state) => state.setShortcutConfig);
  const [activeTab, setActiveTab] = useState<SettingsTab>(readTabFromUrl);

  // 快捷键：同一时刻只允许一个动作处于录制态
  const [recordingAction, setRecordingAction] = useState<ShortcutAction | null>(null);
  const shortcutsAreDefault = isDefaultShortcutConfig(shortcutConfig);

  const onShortcutAdd = (action: ShortcutAction, accelerator: string): void => {
    const label = describeAccelerator(accelerator);
    if (shortcutConfig[action].includes(accelerator)) {
      Message.info(`${label} 已在列表中`);
      return;
    }
    const candidate = { ...shortcutConfig, [action]: [...shortcutConfig[action], accelerator] };
    const conflictWith = findShortcutConflicts(candidate)[action];
    if (conflictWith) {
      Message.warning(`${label} 已用于「${shortcutActionMeta(conflictWith).label}」`);
      return;
    }
    setShortcutConfig(candidate);
    Message.success(`已添加 ${label}`);
  };

  const onShortcutRemove = (action: ShortcutAction, accelerator: string): void => {
    setShortcutConfig({ ...shortcutConfig, [action]: shortcutConfig[action].filter((entry) => entry !== accelerator) });
  };

  const onShortcutClear = (action: ShortcutAction): void => {
    setShortcutConfig({ ...shortcutConfig, [action]: [] });
    Message.success('已清空该项快捷键');
  };

  const onShortcutResetOne = (action: ShortcutAction): void => {
    setRecordingAction(null);
    setShortcutConfig({ ...shortcutConfig, [action]: [...shortcutActionMeta(action).defaults] });
  };

  const onResetShortcuts = (): void => {
    setRecordingAction(null);
    setShortcutConfig(defaultShortcutConfig());
    Message.success('快捷键已恢复默认');
  };

  // 复习方案编辑器 state
  const [reviewEditorVisible, setReviewEditorVisible] = useState(false);
  const [reviewEditing, setReviewEditing] = useState<SpacedRepetitionConfig>();
  const [reviewName, setReviewName] = useState('');
  const [reviewIsDefault, setReviewIsDefault] = useState(false);
  const [reviewIntervalsJson, setReviewIntervalsJson] = useState(JSON.stringify(DEFAULT_REVIEW_INTERVALS, null, 2));
  const [reviewInitialEf, setReviewInitialEf] = useState(2.5);
  const [reviewMinimumEf, setReviewMinimumEf] = useState(1.3);
  const [reviewMaxIntervalDays, setReviewMaxIntervalDays] = useState(365);
  const [reviewWrongStrategy, setReviewWrongStrategy] = useState<string>('reduce_half');
  const [reviewWrongFixedDays, setReviewWrongFixedDays] = useState(1.0);
  const [reviewDailyNewLimit, setReviewDailyNewLimit] = useState(20);
  const [reviewDailyReviewLimit, setReviewDailyReviewLimit] = useState(100);
  const [reviewPriorityMode, setReviewPriorityMode] = useState<string>('due_first');

  // Session / study preferences (localStorage)
  const [enrollDefault, setEnrollDefault] = useState(() => readBoolPref(LS_ENROLL_DEFAULT, true));
  const [planDefault, setPlanDefault] = useState(() => readBoolPref(LS_PLAN_DEFAULT, true));
  const [forceAdvance, setForceAdvance] = useState(() => readBoolPref(LS_FORCE_ADVANCE, false));
  // 界面状态记忆开关：关闭时立即清除已记住的页面与各页选择
  const [rememberViewState, setRememberViewState] = useState(() => readBoolPref(LS_REMEMBER_VIEW_STATE, true));

  const onRememberViewStateChange = (checked: boolean): void => {
    setRememberViewState(checked);
    writeBoolPref(LS_REMEMBER_VIEW_STATE, checked);
    if (!checked) {
      clearViewState();
      Message.success('已停止记忆并清除');
    }
  };

  const onClearViewState = (): void => {
    clearViewState();
    Message.success('已清除，下次启动回到笔记本');
  };

  // 会话内记忆曲线（答错延迟重现）
  const [curveConfig, setCurveConfig] = useState<SessionCurveConfig>(() => readSessionCurveConfig());
  const updateCurveConfig = (partial: Partial<SessionCurveConfig>): void => {
    const next = normalizeSessionCurveConfig({ ...curveConfig, ...partial });
    setCurveConfig(next);
    writeSessionCurveConfig(next);
  };

  const switchTab = (key: SettingsTab): void => {
    setActiveTab(key);
    persistViewState('settings', { tab: key });
    const basePath = window.location.hash.split('?')[0] || '#/settings';
    window.history.replaceState(null, '', `${basePath}?tab=${key}`);
  };

  const onTabBarKeyDown = (event: KeyboardEvent<HTMLDivElement>): void => {
    if (event.key !== 'ArrowLeft' && event.key !== 'ArrowRight') return;
    event.preventDefault();
    const index = TABS.findIndex((tab) => tab.key === activeTab);
    const delta = event.key === 'ArrowRight' ? 1 : -1;
    const next = TABS[(index + delta + TABS.length) % TABS.length];
    switchTab(next.key);
    document.querySelector<HTMLButtonElement>(`.settings-tab-bar .settings-tab[data-tab='${next.key}']`)?.focus();
  };

  // ---- 复习方案 ----

  const reviewConfigsQuery = useQuery({
    queryKey: ['review-configs'],
    queryFn: () => listConfigs(),
  });

  const refreshReviewConfigs = (): void => {
    void queryClient.invalidateQueries({ queryKey: ['review-configs'] });
  };

  const openReviewEditor = (config?: SpacedRepetitionConfig): void => {
    setReviewEditing(config);
    setReviewName(config?.name ?? '');
    setReviewIsDefault(config?.isDefault ?? false);
    setReviewIntervalsJson(config ? JSON.stringify(config.intervals, null, 2) : JSON.stringify(DEFAULT_REVIEW_INTERVALS, null, 2));
    setReviewInitialEf(config?.initialEf ?? 2.5);
    setReviewMinimumEf(config?.minimumEf ?? 1.3);
    setReviewMaxIntervalDays(config?.maxIntervalDays ?? 365);
    setReviewWrongStrategy(config?.wrongStrategy ?? 'reduce_half');
    setReviewWrongFixedDays(config?.wrongFixedDays ?? 1.0);
    setReviewDailyNewLimit(config?.dailyNewLimit ?? 20);
    setReviewDailyReviewLimit(config?.dailyReviewLimit ?? 100);
    setReviewPriorityMode(config?.priorityMode ?? 'due_first');
    setReviewEditorVisible(true);
  };

  const saveReviewConfigMutation = useMutation({
    mutationFn: async () => {
      let intervals: Record<string, number>;
      try { intervals = JSON.parse(reviewIntervalsJson); }
      catch { throw new Error('间隔配置 JSON 格式无效'); }
      const config: Partial<SpacedRepetitionConfig> = {
        name: reviewName, isDefault: reviewIsDefault, intervals,
        initialEf: reviewInitialEf, minimumEf: reviewMinimumEf,
        maxIntervalDays: reviewMaxIntervalDays,
        wrongStrategy: reviewWrongStrategy as SpacedRepetitionConfig['wrongStrategy'],
        wrongFixedDays: reviewWrongFixedDays,
        dailyNewLimit: reviewDailyNewLimit, dailyReviewLimit: reviewDailyReviewLimit,
        priorityMode: reviewPriorityMode as SpacedRepetitionConfig['priorityMode'],
      };
      if (reviewEditing) { await updateConfig(reviewEditing.id, config); }
      else { await createConfig(config); }
    },
    onSuccess: () => {
      refreshReviewConfigs();
      setReviewEditorVisible(false);
      Message.success(reviewEditing ? '复习方案已更新' : '复习方案已创建');
    },
    onError: (error) => Message.error(error instanceof Error ? error.message : '操作失败'),
  });

  const deleteReviewConfigMutation = useMutation({
    mutationFn: (id: number) => deleteConfig(id),
    onSuccess: () => { refreshReviewConfigs(); Message.success('方案已删除'); },
    onError: (error) => Message.error(error instanceof Error ? error.message : '删除失败'),
  });

  return <main className="page">
    <div className="page-heading">
      <div><h1>设置</h1><p>主题、快捷键、AI 连接、嵌入检索与学习复习偏好。日常对话请用右下角 AI 悬浮球{shortcutConfig.toggleAi[0] ? `或 ${describeAccelerator(shortcutConfig.toggleAi[0])}` : ''}。</p></div>
    </div>
    <div className="settings-tab-bar" role="tablist" aria-label="设置分区" onKeyDown={onTabBarKeyDown}>
      {TABS.map((tab) => (
        <button
          key={tab.key}
          type="button"
          role="tab"
          data-tab={tab.key}
          aria-selected={activeTab === tab.key}
          className={`settings-tab${activeTab === tab.key ? ' active' : ''}`}
          onClick={() => switchTab(tab.key)}
        >
          {tab.label}
        </button>
      ))}
    </div>
    {activeTab === 'general' && (
      <div className="settings-tab-stack" role="tabpanel">
      <section className="panel">
        <div className="panel-header"><h2>界面</h2></div>
        <div className="panel-body form-stack">
          <div className="settings-row">
            <div><Typography.Text bold>主题</Typography.Text><br /><Typography.Text type="secondary">跟随系统时随 Windows 深浅色自动切换。</Typography.Text></div>
            <Radio.Group type="button" size="small" value={themeMode} onChange={(value) => setThemeMode(value as ThemeMode)}>
              <Radio value="light">浅色</Radio>
              <Radio value="dark">深色</Radio>
              <Radio value="system">跟随系统</Radio>
            </Radio.Group>
          </div>
          <div className="settings-row">
            <div><Typography.Text bold>显示 AI 悬浮球</Typography.Text><br /><Typography.Text type="secondary">隐藏后仍可用快捷键或下方按钮打开。</Typography.Text></div>
            <Switch checked={aiFabVisible} onChange={setAiFabVisible} />
          </div>
          <div className="settings-row">
            <div><Typography.Text bold>记住上次停留位置与各页选择</Typography.Text><br /><Typography.Text type="secondary">重启后回到上次页面，并恢复各页勾选与切换。</Typography.Text></div>
            <Space align="center">
              <Button type="text" size="mini" disabled={!rememberViewState} onClick={onClearViewState}>清除已记住的位置</Button>
              <Switch checked={rememberViewState} onChange={onRememberViewStateChange} />
            </Space>
          </div>
          <Button type="outline" icon={<Sparkles size={16} />} onClick={() => setAiOpen(true)}>打开 AI 助手</Button>
        </div>
      </section>
      <section className="panel">
        <div className="panel-header">
          <h2>快捷键</h2>
          <Button type="text" size="small" icon={<RotateCcw size={14} />} disabled={shortcutsAreDefault} onClick={onResetShortcuts}>全部恢复默认</Button>
        </div>
        <div className="panel-body form-stack">
          {SHORTCUT_SCOPES.map((scope) => (
            <div key={scope.id} className="settings-group" data-scope={scope.id}>
              <Typography.Text type="secondary" className="settings-group-caption">{`${scope.label} · ${scope.description}`}</Typography.Text>
              {SHORTCUT_ACTIONS.filter((meta) => meta.scope === scope.id).map((meta) => (
                <div key={meta.id} className="settings-row" data-shortcut={meta.id}>
                  <div>
                    <Typography.Text bold>{meta.label}</Typography.Text>
                    {meta.description ? <><br /><Typography.Text type="secondary">{meta.description}</Typography.Text></> : null}
                  </div>
                  <Space align="center" size={4}>
                    {isActionDefault(meta.id, shortcutConfig) ? null : (
                      <Button type="text" size="mini" icon={<RotateCcw size={12} />} aria-label={`恢复默认：${meta.label}`} title="恢复此项默认" onClick={() => onShortcutResetOne(meta.id)} />
                    )}
                    <ShortcutRecorder
                      values={shortcutConfig[meta.id]}
                      rule={ruleForAction(meta.id)}
                      recording={recordingAction === meta.id}
                      onStart={() => setRecordingAction(meta.id)}
                      onStop={() => setRecordingAction(null)}
                      onAdd={(accelerator) => onShortcutAdd(meta.id, accelerator)}
                      onRemove={(accelerator) => onShortcutRemove(meta.id, accelerator)}
                      onClear={() => onShortcutClear(meta.id)}
                    />
                  </Space>
                </div>
              ))}
            </div>
          ))}
          <Typography.Text type="secondary">点 + 录入新键；Esc 取消，Backspace 清空该项。输入框聚焦时页面内快捷键不生效。</Typography.Text>
        </div>
      </section>
      </div>
    )}
    {activeTab === 'study' && (
      <div className="settings-tab-stack" role="tabpanel">
      <section className="panel">
        <div className="panel-header"><h2>学习偏好</h2></div>
        <div className="panel-body form-stack">
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', gap: 16 }}>
            <div>
              <Typography.Text bold>会话结束默认加入记忆曲线</Typography.Text>
              <br />
              <Typography.Text type="secondary">会话推荐弹窗「加入记忆曲线」的默认开关。</Typography.Text>
            </div>
            <Switch
              checked={enrollDefault}
              onChange={(checked) => {
                setEnrollDefault(checked);
                writeBoolPref(LS_ENROLL_DEFAULT, checked);
              }}
            />
          </div>
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', gap: 16 }}>
            <div>
              <Typography.Text bold>会话结束默认写入日历</Typography.Text>
              <br />
              <Typography.Text type="secondary">会话推荐弹窗「加入学习日历」的默认开关。</Typography.Text>
            </div>
            <Switch
              checked={planDefault}
              onChange={(checked) => {
                setPlanDefault(checked);
                writeBoolPref(LS_PLAN_DEFAULT, checked);
              }}
            />
          </div>
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', gap: 16 }}>
            <div>
              <Typography.Text bold>同日正确重复推进曲线</Typography.Text>
              <br />
              <Typography.Text type="secondary">
                开启后，在日历队列完成时若同日已正确过一次，仍会推进 SRS（默认计为额外练习不改到期日）。
              </Typography.Text>
            </div>
            <Switch
              checked={forceAdvance}
              onChange={(checked) => {
                setForceAdvance(checked);
                writeBoolPref(LS_FORCE_ADVANCE, checked);
              }}
            />
          </div>
        </div>
      </section>
      <section className="panel">
        <div className="panel-header"><h2>会话内记忆曲线</h2></div>
        <div className="panel-body form-stack">
          <div>
            <Typography.Text type="secondary">短周期记忆曲线：背题 / 背知识点时所选条目循环出现多遍，答错条目按策略重复；刷题固定为单轮 + 答错延迟重现。也可在「练习 → 背诵」内的记忆曲线设置弹窗中调整。</Typography.Text>
          </div>
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', gap: 16 }}>
            <div>
              <Typography.Text bold>启用循环背诵与错题重现</Typography.Text>
              <br />
              <Typography.Text type="secondary">关闭后背题 / 背知识点按所选顺序单轮过一遍，答错不再重现。</Typography.Text>
            </div>
            <Switch checked={curveConfig.enabled} onChange={(checked) => updateCurveConfig({ enabled: checked })} />
          </div>
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', gap: 16 }}>
            <div>
              <Typography.Text bold>循环轮数</Typography.Text>
              <br />
              <Typography.Text type="secondary">背诵时所选条目整体循环出现几遍（推荐 3）。</Typography.Text>
            </div>
            <InputNumber
              value={curveConfig.loops}
              onChange={(value) => value != null && updateCurveConfig({ loops: value })}
              min={1}
              max={10}
              disabled={!curveConfig.enabled}
              style={{ width: 120 }}
            />
          </div>
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', gap: 16 }}>
            <div>
              <Typography.Text bold>错题重复策略</Typography.Text>
              <br />
              <Typography.Text type="secondary">组末复习（百词斩式，推荐）：每轮分组，错题集中到本组末尾重现；也可选本轮末尾或延迟重现。</Typography.Text>
            </div>
            <Radio.Group type="button" size="small" value={curveConfig.strategy} onChange={(value) => updateCurveConfig({ strategy: value as SessionCurveConfig['strategy'] })} disabled={!curveConfig.enabled}>
              <Radio value="group">组末复习</Radio>
              <Radio value="tail">本轮末尾</Radio>
              <Radio value="gap">延迟重现</Radio>
            </Radio.Group>
          </div>
          {curveConfig.strategy === 'group' && (
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', gap: 16 }}>
              <div>
                <Typography.Text bold>每组题数</Typography.Text>
                <br />
                <Typography.Text type="secondary">每轮按此数量分组，答错插入所在组末尾（推荐 10）。</Typography.Text>
              </div>
              <InputNumber
                value={curveConfig.groupSize}
                onChange={(value) => value != null && updateCurveConfig({ groupSize: value })}
                min={2}
                max={100}
                disabled={!curveConfig.enabled}
                style={{ width: 120 }}
              />
            </div>
          )}
          {curveConfig.strategy === 'gap' && (
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', gap: 16 }}>
              <div>
                <Typography.Text bold>重现间隔</Typography.Text>
                <br />
                <Typography.Text type="secondary">答错后隔多少个条目再次出现（推荐 3）；刷题模式始终使用此策略。</Typography.Text>
              </div>
              <InputNumber
                value={curveConfig.gap}
                onChange={(value) => value != null && updateCurveConfig({ gap: value })}
                min={1}
                max={50}
                disabled={!curveConfig.enabled}
                style={{ width: 120 }}
              />
            </div>
          )}
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', gap: 16 }}>
            <div>
              <Typography.Text bold>不限制重复次数（直到会为止）</Typography.Text>
              <br />
              <Typography.Text type="secondary">开启后错题持续重现直至答对过关；关闭则按下方最大重复次数封顶。</Typography.Text>
            </div>
            <Switch
              checked={curveConfig.maxRepeats === 0}
              disabled={!curveConfig.enabled}
              onChange={(checked) => updateCurveConfig({ maxRepeats: checked ? 0 : 3 })}
            />
          </div>
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', gap: 16 }}>
            <div>
              <Typography.Text bold>最大重复次数</Typography.Text>
              <br />
              <Typography.Text type="secondary">达到上限仍未记住则标记「本轮未记住」继续往下走（推荐 3）。</Typography.Text>
            </div>
            <InputNumber
              value={curveConfig.maxRepeats === 0 ? undefined : curveConfig.maxRepeats}
              onChange={(value) => value != null && updateCurveConfig({ maxRepeats: value })}
              min={1}
              max={99}
              placeholder="不限"
              disabled={!curveConfig.enabled || curveConfig.maxRepeats === 0}
              style={{ width: 120 }}
            />
          </div>
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', gap: 16 }}>
            <div>
              <Typography.Text bold>过关条件（连续答对次数）</Typography.Text>
              <br />
              <Typography.Text type="secondary">重现的条目需连续答对（会）多少次才算过关（推荐 1）。</Typography.Text>
            </div>
            <InputNumber
              value={curveConfig.passStreak}
              onChange={(value) => value != null && updateCurveConfig({ passStreak: value })}
              min={1}
              max={10}
              disabled={!curveConfig.enabled}
              style={{ width: 120 }}
            />
          </div>
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', gap: 16 }}>
            <div>
              <Typography.Text bold>下一轮出场顺序</Typography.Text>
              <br />
              <Typography.Text type="secondary">错题优先：上一轮最后答错的条目排到下一轮开头（百词斩总复习式）。</Typography.Text>
            </div>
            <Radio.Group type="button" size="small" value={curveConfig.nextRoundOrder} onChange={(value) => updateCurveConfig({ nextRoundOrder: value as SessionCurveConfig['nextRoundOrder'] })} disabled={!curveConfig.enabled || curveConfig.loops <= 1}>
              <Radio value="original">保持原序</Radio>
              <Radio value="wrongFirst">错题优先</Radio>
              <Radio value="random">随机</Radio>
            </Radio.Group>
          </div>
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', gap: 16 }}>
            <div>
              <Typography.Text bold>过关后跳过后续轮次</Typography.Text>
              <br />
              <Typography.Text type="secondary">开启后已过关的条目不再出现在之后的循环里；关闭（默认）则每轮都完整过一遍。</Typography.Text>
            </div>
            <Switch checked={curveConfig.skipPassed} onChange={(checked) => updateCurveConfig({ skipPassed: checked })} disabled={!curveConfig.enabled} />
          </div>
        </div>
      </section>
      </div>
    )}
    {activeTab === 'ai' && (
      <div className="settings-tab-panel" role="tabpanel">
      <section className="panel">
        <div className="panel-header"><h2>AI 连接</h2></div>
        <div className="panel-body form-stack">
          <AiModelSlotCard
            purpose="chat"
            title="主模型（对话 / 总结 / 学习计划）"
            description="侧栏助手、计划排程、解答题建议评分等走此配置。"
          />
          <div style={{ borderTop: '1px solid var(--line)', paddingTop: 16 }}>
            <AiModelSlotCard
              purpose="import"
              title="导入兜底模型（PDF / Markdown / JSON / 知识点）"
              description="仅用于导入时的 AI 解析，与主模型分开，可选用更便宜或更长上下文的模型以节省 token。未配置时自动回退主模型。"
            />
          </div>
          <Typography.Text type="secondary">两套密钥均经本地 Java 后端 Argon2id + AES-256-GCM 加密存储。</Typography.Text>
        </div>
      </section>
      </div>
    )}

      {/* 嵌入与检索 Tab：向量索引基础设施（与生成模型接入分开，参照 LobeChat / Cherry Studio 分组惯例） */}
      {activeTab === 'embedding' && (
      <div className="settings-tab-panel" role="tabpanel">
      <EmbeddingSettingsCard />
      </div>
    )}

      {/* 数据管理 Tab：备份配置、备份列表、导出与导入 */}
      {activeTab === 'data' && (
      <div className="settings-tab-panel" role="tabpanel">
      <DataManagementPanel />
      </div>
    )}

      {/* 学习与复习 Tab：复习方案（SM-2） */}
      {activeTab === 'study' && (
      <div className="settings-tab-stack" role="tabpanel">
      <section className="panel">
        <div className="panel-header">
          <h2>复习方案</h2>
          <Button type="primary" size="small" icon={<Plus size={14} />} onClick={() => openReviewEditor()}>新建方案</Button>
        </div>
        <div className="panel-body">
          {reviewConfigsQuery.isLoading ? <Spin /> : (reviewConfigsQuery.data ?? []).length === 0 ? (
            <Empty description="暂无复习方案，请新建一个" />
          ) : (
            <div className="knowledge-grid">
              {(reviewConfigsQuery.data ?? []).map((config) => (
                <Card key={config.id} className="knowledge-item" style={{ position: 'relative' }}>
                  <div className="knowledge-item-top">
                    <div>
                      <h3>
                        {config.name}
                        {config.isDefault && <Tag color="arcoblue" size="small" style={{ marginLeft: 8 }}><Star size={12} /> 默认</Tag>}
                      </h3>
                      <Space wrap>
                        <Tag>每天新学 {config.dailyNewLimit} 项</Tag>
                        <Tag>每天复习 {config.dailyReviewLimit} 项</Tag>
                        <Tag color="orange">{WRONG_STRATEGY_OPTIONS.find((o) => o.value === config.wrongStrategy)?.label}</Tag>
                        <Tag color="purple">{PRIORITY_OPTIONS.find((o) => o.value === config.priorityMode)?.label}</Tag>
                      </Space>
                    </div>
                    <Space size={2}>
                      <Button type="text" size="mini" icon={<Edit3 size={14} />} onClick={() => openReviewEditor(config)} />
                      {!config.isDefault && (
                        <Popconfirm title="删除此方案？" onOk={() => deleteReviewConfigMutation.mutate(config.id)}>
                          <Button type="text" status="danger" size="mini" icon={<Trash2 size={14} />} />
                        </Popconfirm>
                      )}
                    </Space>
                  </div>
                  <Typography.Paragraph type="secondary" style={{ margin: 0, marginTop: 8 }}>
                    间隔：{Object.entries(config.intervals).map(([k, v]) => `第${k}次→${v}天`).join('，')}
                    {' | '}EF: {config.initialEf}{' | '}最大间隔: {config.maxIntervalDays}天
                  </Typography.Paragraph>
                </Card>
              ))}
            </div>
          )}
        </div>
      </section>
      </div>
    )}
    <Modal
      title={reviewEditing ? '编辑复习方案' : '新建复习方案'}
      visible={reviewEditorVisible}
      onCancel={() => setReviewEditorVisible(false)}
      onOk={() => { if (!reviewName.trim()) { Message.warning('请输入方案名称'); return; } saveReviewConfigMutation.mutate(); }}
      confirmLoading={saveReviewConfigMutation.isPending}
      style={{ width: 680 }}
      autoFocus={false}
    >
      <Form layout="vertical">
        <Form.Item label="方案名称" required>
          <Input value={reviewName} onChange={setReviewName} placeholder="例如：标准模式、考前突击" />
        </Form.Item>
        <Form.Item label="设为默认方案">
          <Switch checked={reviewIsDefault} onChange={setReviewIsDefault} />
        </Form.Item>
        <Form.Item label="间隔配置（JSON，第N次通过后的天数）" required>
          <Input.TextArea value={reviewIntervalsJson} onChange={setReviewIntervalsJson} autoSize={{ minRows: 3, maxRows: 8 }} />
        </Form.Item>
        <div className="form-row">
          <Form.Item label="初始难度系数 (EF)">
            <InputNumber value={reviewInitialEf} onChange={(v) => v != null && setReviewInitialEf(v)} min={1.3} max={5} step={0.1} />
          </Form.Item>
          <Form.Item label="最低难度系数">
            <InputNumber value={reviewMinimumEf} onChange={(v) => v != null && setReviewMinimumEf(v)} min={1.0} max={3} step={0.1} />
          </Form.Item>
          <Form.Item label="最大间隔（天）">
            <InputNumber value={reviewMaxIntervalDays} onChange={(v) => v != null && setReviewMaxIntervalDays(v)} min={1} max={9999} />
          </Form.Item>
        </div>
        <div className="form-row">
          <Form.Item label="答错后策略">
            <Select value={reviewWrongStrategy} onChange={setReviewWrongStrategy}>
              {WRONG_STRATEGY_OPTIONS.map((opt) => <Select.Option key={opt.value} value={opt.value}>{opt.label}</Select.Option>)}
            </Select>
          </Form.Item>
          <Form.Item label="固定/重置天数">
            <InputNumber value={reviewWrongFixedDays} onChange={(v) => v != null && setReviewWrongFixedDays(v)} min={0.001} max={30} step={0.001} />
          </Form.Item>
        </div>
        <div className="form-row">
          <Form.Item label="每日新学上限">
            <InputNumber value={reviewDailyNewLimit} onChange={(v) => v != null && setReviewDailyNewLimit(v)} min={0} max={1000} />
          </Form.Item>
          <Form.Item label="每日复习上限">
            <InputNumber value={reviewDailyReviewLimit} onChange={(v) => v != null && setReviewDailyReviewLimit(v)} min={0} max={2000} />
          </Form.Item>
        </div>
        <Form.Item label="排序策略">
          <Radio.Group value={reviewPriorityMode} onChange={setReviewPriorityMode} direction="horizontal">
            {PRIORITY_OPTIONS.map((opt) => <Radio key={opt.value} value={opt.value}>{opt.label}</Radio>)}
          </Radio.Group>
        </Form.Item>
      </Form>
    </Modal>
  </main>;
}
