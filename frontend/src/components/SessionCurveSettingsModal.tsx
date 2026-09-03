import { useEffect, useState } from 'react';
import { Button, InputNumber, Modal, Radio, Space, Switch, Tag, Typography } from '@arco-design/web-react';
import { RotateCcw } from 'lucide-react';
import {
  DEFAULT_SESSION_CURVE_CONFIG,
  SESSION_CURVE_PRESETS,
  normalizeSessionCurveConfig,
  readSessionCurveConfig,
  writeSessionCurveConfig
} from '../lib/sessionCurve';
import type { SessionCurveConfig } from '../lib/sessionCurve';

export interface SessionCurveSettingsModalProps {
  visible: boolean;
  onClose: () => void;
  /** 当前已选条目数，用于预估出场规模 */
  itemCount?: number;
  /** 保存成功后回调（弹窗内已写入 localStorage） */
  onSaved?: (config: SessionCurveConfig) => void;
}

/** 一行「说明 + 控件」的通用布局，与设置页会话曲线卡片风格一致。 */
function SettingRow({ title, description, children }: { title: string; description: string; children: React.ReactNode }): JSX.Element {
  return (
    <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', gap: 16, padding: '8px 0' }}>
      <div style={{ minWidth: 0 }}>
        <Typography.Text bold>{title}</Typography.Text>
        <br />
        <Typography.Text type="secondary" style={{ fontSize: 12 }}>{description}</Typography.Text>
      </div>
      <div style={{ flexShrink: 0 }}>{children}</div>
    </div>
  );
}

/**
 * 背诵记忆曲线设置弹窗：短周期「多轮循环 + 错题重复」的自定义选项，
 * 提供推荐默认值与预设方案。保存后写入 localStorage，下次开始背诵生效。
 */
export function SessionCurveSettingsModal({ visible, onClose, itemCount, onSaved }: SessionCurveSettingsModalProps): JSX.Element {
  const [draft, setDraft] = useState<SessionCurveConfig>(() => readSessionCurveConfig());
  // 每次打开都重新读取，避免其他入口（设置页）修改后展示陈旧值
  useEffect(() => { if (visible) setDraft(readSessionCurveConfig()); }, [visible]);

  const update = (partial: Partial<SessionCurveConfig>): void => setDraft((current) => normalizeSessionCurveConfig({ ...current, ...partial }));
  const save = (): void => {
    writeSessionCurveConfig(draft);
    onSaved?.(draft);
    onClose();
  };

  const baselineAppearances = itemCount ? itemCount * draft.loops : 0;

  return (
    <Modal
      title="背诵记忆曲线设置"
      visible={visible}
      onCancel={onClose}
      autoFocus={false}
      style={{ width: 620 }}
      footer={[
        <Button key="reset" icon={<RotateCcw size={14} />} style={{ marginRight: 'auto' }} onClick={() => setDraft({ ...DEFAULT_SESSION_CURVE_CONFIG })}>恢复推荐默认</Button>,
        <Button key="cancel" onClick={onClose}>取消</Button>,
        <Button key="save" type="primary" onClick={save}>保存</Button>
      ]}
    >
      <Typography.Text type="secondary" style={{ display: 'block', marginBottom: 8 }}>
        短周期记忆曲线：所选条目整体循环出现多遍，答错（不会）的条目按策略追加重复。仅影响背题 / 背知识点的单次会话，与跨天复习方案（SRS）相互独立。
      </Typography.Text>
      <Space wrap style={{ marginBottom: 12 }}>
        {SESSION_CURVE_PRESETS.map((preset) => {
          const active = preset.config.loops === draft.loops && preset.config.strategy === draft.strategy
            && preset.config.maxRepeats === draft.maxRepeats && preset.config.nextRoundOrder === draft.nextRoundOrder
            && preset.config.groupSize === draft.groupSize;
          return (
            <Button key={preset.key} size="small" type={active ? 'primary' : 'secondary'} onClick={() => setDraft({ ...preset.config })} title={preset.description}>
              {preset.name}
            </Button>
          );
        })}
      </Space>
      <SettingRow title="启用循环背诵" description="关闭后按所选顺序单轮过一遍，答错不再重现。">
        <Switch checked={draft.enabled} onChange={(checked) => update({ enabled: checked })} />
      </SettingRow>
      <SettingRow title="循环轮数" description="所选条目整体循环出现几遍（推荐 3 遍）。">
        <InputNumber value={draft.loops} onChange={(value) => value != null && update({ loops: value })} min={1} max={10} disabled={!draft.enabled} style={{ width: 120 }} suffix="轮" />
      </SettingRow>
      <SettingRow title="错题重复策略" description="百词斩式组末复习：每轮分组，错词集中到本组末尾马上重现；也可选整轮末尾或延迟重现。">
        <Radio.Group type="button" size="small" value={draft.strategy} onChange={(value) => update({ strategy: value as SessionCurveConfig['strategy'] })} disabled={!draft.enabled}>
          <Radio value="group">组末复习</Radio>
          <Radio value="tail">本轮末尾</Radio>
          <Radio value="gap">延迟重现</Radio>
        </Radio.Group>
      </SettingRow>
      {draft.strategy === 'group' && (
        <SettingRow title="每组题数" description="每轮按此数量分组，答错插入所在组末尾（推荐 10）。">
          <InputNumber value={draft.groupSize} onChange={(value) => value != null && update({ groupSize: value })} min={2} max={100} disabled={!draft.enabled} style={{ width: 120 }} suffix="题/组" />
        </SettingRow>
      )}
      {draft.strategy === 'gap' && (
        <SettingRow title="重现间隔" description="答错后隔多少个条目再次出现（推荐 3）。">
          <InputNumber value={draft.gap} onChange={(value) => value != null && update({ gap: value })} min={1} max={50} disabled={!draft.enabled} style={{ width: 120 }} suffix="题" />
        </SettingRow>
      )}
      <SettingRow title="单条额外重复上限" description="基线轮次之外，一条最多额外重现几次；填 0 表示不限（直到会为止）。达到上限仍未记住会标记「本轮未记住」。">
        <InputNumber value={draft.maxRepeats} onChange={(value) => value != null && update({ maxRepeats: value })} min={0} max={99} disabled={!draft.enabled} style={{ width: 120 }} suffix="次" />
      </SettingRow>
      <SettingRow title="过关连对次数" description="连续答对（会）多少次才算记住；设为 2 及以上可让答对过的条目在本轮内再巩固一次。">
        <InputNumber value={draft.passStreak} onChange={(value) => value != null && update({ passStreak: value })} min={1} max={10} disabled={!draft.enabled} style={{ width: 120 }} suffix="次" />
      </SettingRow>
      <SettingRow title="过关后跳过后续轮次" description="开启后，已过关的条目不再出现在之后的循环里，节省时间；关闭则每轮都完整过一遍（默认）。">
        <Switch checked={draft.skipPassed} onChange={(checked) => update({ skipPassed: checked })} disabled={!draft.enabled} />
      </SettingRow>
      <SettingRow title="下一轮出场顺序" description="默认保持你编排的顺序；选「错题优先」时，上一轮最后答错的条目会排到下一轮开头（百词斩总复习式）。">
        <Radio.Group type="button" size="small" value={draft.nextRoundOrder} onChange={(value) => update({ nextRoundOrder: value as SessionCurveConfig['nextRoundOrder'] })} disabled={!draft.enabled || draft.loops <= 1}>
          <Radio value="original">保持原序</Radio>
          <Radio value="wrongFirst">错题优先</Radio>
          <Radio value="random">随机</Radio>
        </Radio.Group>
      </SettingRow>
      {itemCount ? (
        <div style={{ marginTop: 8 }}>
          <Tag color="arcoblue">预计出场 ≥ {baselineAppearances} 次</Tag>
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>（{itemCount} 个条目 × {draft.loops} 轮；答错还会在末尾追加重复）</Typography.Text>
        </div>
      ) : null}
    </Modal>
  );
}
