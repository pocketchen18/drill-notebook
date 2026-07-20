import { useEffect, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Button, Card, Descriptions, Empty, Form, Input, InputNumber, Message, Modal, Popconfirm, Radio, Select, Space, Spin, Switch, Tag, Typography } from '@arco-design/web-react';
import { Edit3, FolderOpen, Plus, RefreshCw, Sparkles, Star, Trash2 } from 'lucide-react';
import { get, put } from '../lib/api';
import { friendlyMessage } from '../lib/errors';
import type { AiConfig } from '../lib/types';
import { useUiStore } from '../stores/uiStore';
import { listConfigs, createConfig, updateConfig, deleteConfig } from '../lib/review';
import type { SpacedRepetitionConfig } from '../lib/review';

interface Health { status: string; appRoot: string; dbPath: string; }

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

export function SettingsPage(): JSX.Element {
  const queryClient = useQueryClient();
  const theme = useUiStore((state) => state.theme);
  const toggleTheme = useUiStore((state) => state.toggleTheme);
  const setAiOpen = useUiStore((state) => state.setAiOpen);
  const [portable, setPortable] = useState<{ root: string; database: string; portable: boolean }>();
  const [health, setHealth] = useState<Health>();
  const [loading, setLoading] = useState(true);
  const [provider, setProvider] = useState('custom');
  const [endpoint, setEndpoint] = useState('');
  const [model, setModel] = useState('');
  const [apiKey, setApiKey] = useState('');

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

  const configQuery = useQuery({ queryKey: ['ai-config'], queryFn: () => get<AiConfig>('/api/ai/config') });

  const load = async (): Promise<void> => {
    setLoading(true);
    try {
      const [info, status] = await Promise.all([
        window.api?.backend.getPortableInfo() ?? Promise.resolve({ root: '浏览器开发模式', database: '由后端决定', portable: true }),
        get<Health>('/api/health')
      ]);
      setPortable(info);
      setHealth(status);
    } catch (error) {
      Message.error(friendlyMessage(error, '后端不可用'));
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { void load(); }, []);
  useEffect(() => {
    if (!configQuery.data) return;
    setProvider(configQuery.data.provider || 'custom');
    setEndpoint(configQuery.data.endpoint || '');
    setModel(configQuery.data.model || '');
  }, [configQuery.data]);

  const saveMutation = useMutation({
    mutationFn: () => put<AiConfig>('/api/ai/config', { provider, endpoint, model, apiKey: apiKey || undefined }),
    onSuccess: () => {
      setApiKey('');
      void queryClient.invalidateQueries({ queryKey: ['ai-config'] });
      Message.success('AI 配置已保存，密钥已加密存储');
    },
    onError: (error) => Message.error(friendlyMessage(error, 'AI 配置保存失败，请稍后重试'))
  });

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
      <div><h1>设置</h1><p>便携运行状态、主题与 AI 连接。日常对话请用右下角 AI 悬浮球或 Ctrl+J。</p></div>
      <Button type="text" icon={<RefreshCw size={16} />} onClick={() => void load()} aria-label="刷新状态" />
    </div>
    {loading ? <Spin /> : <div className="settings-grid">
      <section className="panel">
        <div className="panel-header"><h2>便携运行</h2></div>
        <div className="panel-body">
          <Descriptions column={1} border data={[
            { label: '模式', value: portable?.portable ? '绿色便携' : '标准模式' },
            { label: '应用根目录', value: <code className="path-code">{portable?.root}</code> },
            { label: '数据库', value: <code className="path-code">{portable?.database}</code> },
            { label: '后端状态', value: health?.status === 'UP' ? '运行中' : health?.status || '未知' }
          ]} />
          <div className="muted" style={{ marginTop: 14 }}><FolderOpen size={16} style={{ verticalAlign: 'text-bottom', marginRight: 6 }} />所有应用数据均保存在应用根目录下。</div>
        </div>
      </section>
      <section className="panel">
        <div className="panel-header"><h2>界面</h2></div>
        <div className="panel-body form-stack">
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
            <div><Typography.Text bold>深色主题</Typography.Text><br /><Typography.Text type="secondary">适合夜间整理笔记。</Typography.Text></div>
            <Switch checked={theme === 'dark'} onChange={toggleTheme} />
          </div>
          <Button type="outline" icon={<Sparkles size={16} />} onClick={() => setAiOpen(true)}>打开 AI 助手</Button>
        </div>
      </section>
      <section className="panel settings-ai-panel">
        <div className="panel-header"><h2>AI 连接</h2></div>
        <div className="panel-body form-stack">
          <Form layout="vertical">
            <Form.Item label="Provider"><Input value={provider} onChange={setProvider} placeholder="custom" /></Form.Item>
            <Form.Item label="Endpoint"><Input value={endpoint} onChange={setEndpoint} placeholder="https://api.example.com/v1 或 mock://local" /></Form.Item>
            <Form.Item label="Model"><Input value={model} onChange={setModel} placeholder="模型名称" /></Form.Item>
            <Form.Item label="API Key"><Input.Password value={apiKey} onChange={setApiKey} placeholder={configQuery.data?.hasKey ? '已配置，留空表示不修改' : '输入 API Key'} /></Form.Item>
            <Button type="primary" loading={saveMutation.isPending} onClick={() => saveMutation.mutate()}>保存配置</Button>
          </Form>
          <Typography.Text type="secondary">密钥经本地 Java 后端 Argon2id + AES-256-GCM 加密存储；对话从任意页面用悬浮助手唤出。</Typography.Text>
        </div>
      </section>

      {/* 复习方案 */}
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
    </div>}
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
