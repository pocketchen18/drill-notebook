import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Button, Card, Empty, Form, Input, InputNumber, Message, Modal, Popconfirm, Radio, Select, Space, Switch, Tag, Typography } from '@arco-design/web-react';
import { Edit3, Plus, Star, Trash2 } from 'lucide-react';
import { listConfigs, createConfig, updateConfig, deleteConfig } from '../lib/review';
import type { SpacedRepetitionConfig } from '../lib/review';

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

const DEFAULT_INTERVALS: Record<string, number> = {
  '1': 1, '2': 6, '3': 16, '4': 36, '5': 70,
};

export function ReviewConfigPage(): JSX.Element {
  const queryClient = useQueryClient();
  const configsQuery = useQuery({ queryKey: ['review-configs'], queryFn: () => listConfigs() });

  const [editorVisible, setEditorVisible] = useState(false);
  const [editing, setEditing] = useState<SpacedRepetitionConfig>();
  const [name, setName] = useState('');
  const [isDefault, setIsDefault] = useState(false);
  const [intervalsJson, setIntervalsJson] = useState(JSON.stringify(DEFAULT_INTERVALS, null, 2));
  const [initialEf, setInitialEf] = useState(2.5);
  const [minimumEf, setMinimumEf] = useState(1.3);
  const [maxIntervalDays, setMaxIntervalDays] = useState(365);
  const [wrongStrategy, setWrongStrategy] = useState<string>('reduce_half');
  const [wrongFixedDays, setWrongFixedDays] = useState(1.0);
  const [dailyNewLimit, setDailyNewLimit] = useState(20);
  const [dailyReviewLimit, setDailyReviewLimit] = useState(100);
  const [priorityMode, setPriorityMode] = useState<string>('due_first');

  const refresh = (): void => { void queryClient.invalidateQueries({ queryKey: ['review-configs'] }); };

  const saveMutation = useMutation({
    mutationFn: async () => {
      let intervals: Record<string, number>;
      try {
        intervals = JSON.parse(intervalsJson);
      } catch {
        throw new Error('间隔配置 JSON 格式无效');
      }
      const config: Partial<SpacedRepetitionConfig> = {
        name, isDefault, intervals, initialEf, minimumEf,
        maxIntervalDays, wrongStrategy: wrongStrategy as SpacedRepetitionConfig['wrongStrategy'],
        wrongFixedDays, dailyNewLimit, dailyReviewLimit,
        priorityMode: priorityMode as SpacedRepetitionConfig['priorityMode'],
      };
      if (editing) {
        await updateConfig(editing.id, config);
        Message.success('配置已更新');
      } else {
        await createConfig(config);
        Message.success('配置已创建');
      }
    },
    onSuccess: () => { refresh(); setEditorVisible(false); },
    onError: (error) => Message.error(error.message),
  });

  const deleteMutation = useMutation({
    mutationFn: (id: number) => deleteConfig(id),
    onSuccess: () => { refresh(); Message.success('配置已删除'); },
    onError: (error) => Message.error(error.message),
  });

  const openEditor = (config?: SpacedRepetitionConfig): void => {
    setEditing(config);
    setName(config?.name ?? '');
    setIsDefault(config?.isDefault ?? false);
    setIntervalsJson(config ? JSON.stringify(config.intervals, null, 2) : JSON.stringify(DEFAULT_INTERVALS, null, 2));
    setInitialEf(config?.initialEf ?? 2.5);
    setMinimumEf(config?.minimumEf ?? 1.3);
    setMaxIntervalDays(config?.maxIntervalDays ?? 365);
    setWrongStrategy(config?.wrongStrategy ?? 'reduce_half');
    setWrongFixedDays(config?.wrongFixedDays ?? 1.0);
    setDailyNewLimit(config?.dailyNewLimit ?? 20);
    setDailyReviewLimit(config?.dailyReviewLimit ?? 100);
    setPriorityMode(config?.priorityMode ?? 'due_first');
    setEditorVisible(true);
  };

  const configs = configsQuery.data ?? [];

  return (
    <main className="page">
      <div className="page-heading">
        <div>
          <h1>复习配置</h1>
          <p>管理间隔重复记忆方案，支持自定义复习周期、答错策略和每日学习量。</p>
        </div>
        <Button type="primary" icon={<Plus size={16} />} onClick={() => openEditor()}>
          新建配置方案
        </Button>
      </div>

      <section className="panel">
        <div className="panel-body">
          {configs.length === 0 ? (
            <Empty description="暂无配置方案，请新建一个" />
          ) : (
            <div className="knowledge-grid">
              {configs.map((config) => (
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
                      <Button type="text" size="mini" icon={<Edit3 size={14} />} onClick={() => openEditor(config)} />
                      {!config.isDefault && (
                        <Popconfirm title="删除此配置方案？" onOk={() => deleteMutation.mutate(config.id)}>
                          <Button type="text" status="danger" size="mini" icon={<Trash2 size={14} />} />
                        </Popconfirm>
                      )}
                    </Space>
                  </div>
                  <Typography.Paragraph type="secondary" style={{ margin: 0, marginTop: 8 }}>
                    间隔：{Object.entries(config.intervals).map(([k, v]) => `第${k}次→${v}天`).join('，')} | EF: {config.initialEf} | 最大间隔: {config.maxIntervalDays}天
                  </Typography.Paragraph>
                </Card>
              ))}
            </div>
          )}
        </div>
      </section>

      <Modal
        title={editing ? '编辑配置方案' : '新建配置方案'}
        visible={editorVisible}
        onCancel={() => setEditorVisible(false)}
        onOk={() => { if (!name.trim()) { Message.warning('请输入方案名称'); return; } saveMutation.mutate(); }}
        confirmLoading={saveMutation.isPending}
        style={{ width: 680 }}
        autoFocus={false}
      >
        <Form layout="vertical">
          <Form.Item label="方案名称" required>
            <Input value={name} onChange={setName} placeholder="例如：标准模式、考前突击" />
          </Form.Item>
          <Form.Item label="设为默认方案">
            <Switch checked={isDefault} onChange={setIsDefault} />
          </Form.Item>
          <Form.Item label="间隔配置（JSON，第N次通过后的天数）" required>
            <Input.TextArea value={intervalsJson} onChange={setIntervalsJson} autoSize={{ minRows: 3, maxRows: 8 }} />
          </Form.Item>
          <div className="form-row">
            <Form.Item label="初始难度系数 (EF)">
              <InputNumber value={initialEf} onChange={(v) => v != null && setInitialEf(v)} min={1.3} max={5} step={0.1} />
            </Form.Item>
            <Form.Item label="最低难度系数">
              <InputNumber value={minimumEf} onChange={(v) => v != null && setMinimumEf(v)} min={1.0} max={3} step={0.1} />
            </Form.Item>
            <Form.Item label="最大间隔（天）">
              <InputNumber value={maxIntervalDays} onChange={(v) => v != null && setMaxIntervalDays(v)} min={7} max={9999} />
            </Form.Item>
          </div>
          <div className="form-row">
            <Form.Item label="答错后策略">
              <Select value={wrongStrategy} onChange={setWrongStrategy}>
                {WRONG_STRATEGY_OPTIONS.map((opt) => <Select.Option key={opt.value} value={opt.value}>{opt.label}</Select.Option>)}
              </Select>
            </Form.Item>
            <Form.Item label="固定/重置天数">
              <InputNumber value={wrongFixedDays} onChange={(v) => v != null && setWrongFixedDays(v)} min={0.25} max={30} step={0.25} />
            </Form.Item>
          </div>
          <div className="form-row">
            <Form.Item label="每日新学上限">
              <InputNumber value={dailyNewLimit} onChange={(v) => v != null && setDailyNewLimit(v)} min={0} max={1000} />
            </Form.Item>
            <Form.Item label="每日复习上限">
              <InputNumber value={dailyReviewLimit} onChange={(v) => v != null && setDailyReviewLimit(v)} min={0} max={2000} />
            </Form.Item>
          </div>
          <Form.Item label="排序策略">
            <Radio.Group value={priorityMode} onChange={setPriorityMode} direction="horizontal">
              {PRIORITY_OPTIONS.map((opt) => <Radio key={opt.value} value={opt.value}>{opt.label}</Radio>)}
            </Radio.Group>
          </Form.Item>
        </Form>
      </Modal>
    </main>
  );
}
