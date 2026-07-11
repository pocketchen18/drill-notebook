import { useEffect, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Button, Descriptions, Form, Input, Message, Spin, Switch, Typography } from '@arco-design/web-react';
import { FolderOpen, RefreshCw, Sparkles } from 'lucide-react';
import { get, put } from '../lib/api';
import type { AiConfig } from '../lib/types';
import { useUiStore } from '../stores/uiStore';

interface Health { status: string; appRoot: string; dbPath: string; }

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
      Message.error(error instanceof Error ? error.message : '后端不可用');
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
    onError: (error) => Message.error(error.message)
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
    </div>}
  </main>;
}
