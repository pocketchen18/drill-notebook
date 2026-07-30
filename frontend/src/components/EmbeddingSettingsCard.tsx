import { useEffect, useMemo, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Button, Checkbox, Empty, Form, Input, InputNumber, Message, Popconfirm, Progress, Select, Space, Spin, Tag, Typography } from '@arco-design/web-react';
import { Download, RefreshCw, Trash2 } from 'lucide-react';
import { get } from '../lib/api';
import { friendlyMessage } from '../lib/errors';
import type { AiConfig, EmbeddingCatalogModel, RetrievalStatus } from '../lib/types';
import {
  activateEmbeddingModel,
  cancelEmbeddingDownload,
  disableEmbeddingModel,
  downloadEmbeddingModel,
  downloadPercent,
  formatModelSize,
  getEmbeddingCatalog,
  getRetrievalStatus,
  refreshEmbeddingCatalog,
  reindexEmbeddings,
  retryFailedEmbeddings,
  saveEmbeddingConfig,
  testEmbeddingEndpoint,
  uninstallEmbeddingModel
} from '../lib/embeddingApi';

const PROVIDER_OPTIONS = [
  { value: 'disabled', label: '禁用' },
  { value: 'local', label: '本地（离线）' },
  { value: 'openai', label: 'OpenAI-compatible' },
  { value: 'ollama', label: 'Ollama' }
];

const LANGUAGE_LABELS: Record<string, string> = { zh: '中文', en: '英文' };

function isRemoteProvider(provider: string): boolean {
  return provider === 'openai' || provider === 'ollama';
}

/**
 * Embedding 设置卡片（Task 13）：独立于主模型/导入模型，含 provider 选择、
 * 远程授权表单、本地模型目录状态机与向量索引状态。所有样式走主题变量。
 */
export function EmbeddingSettingsCard(): JSX.Element {
  const queryClient = useQueryClient();
  const configQuery = useQuery({ queryKey: ['ai-config'], queryFn: () => get<AiConfig>('/api/ai/config') });
  const embedding = configQuery.data?.embedding;

  const [provider, setProvider] = useState('disabled');
  const [endpoint, setEndpoint] = useState('');
  const [model, setModel] = useState('');
  const [dimensions, setDimensions] = useState(512);
  const [apiKey, setApiKey] = useState('');
  const [consent, setConsent] = useState(false);

  useEffect(() => {
    if (!embedding) return;
    setProvider(embedding.provider || 'disabled');
    setEndpoint(embedding.endpoint || '');
    setModel(embedding.model || '');
    setDimensions(embedding.dimensions || 512);
  }, [embedding]);

  const catalogQuery = useQuery({ queryKey: ['embedding-catalog'], queryFn: getEmbeddingCatalog });

  const activeDownload = useMemo(
    () => (catalogQuery.data?.models ?? []).find((m) =>
      m.installationState === 'DOWNLOADING' || m.installationState === 'VERIFYING' || m.installationState === 'UNINSTALLING'),
    [catalogQuery.data]
  );

  const statusQuery = useQuery({ queryKey: ['retrieval-status'], queryFn: () => getRetrievalStatus('all') });
  const status = statusQuery.data;
  const indexing = status != null && (status.indexState === 'REBUILDING' || status.queuedJobs > 0);

  // 轮询仅在下载/索引活跃时运行；组件卸载即停止（observer 移除）。
  const polling = activeDownload != null || indexing;
  useEffect(() => {
    if (!polling) return;
    const timer = setInterval(() => {
      void queryClient.invalidateQueries({ queryKey: ['embedding-catalog'] });
      void queryClient.invalidateQueries({ queryKey: ['retrieval-status'] });
    }, 2000);
    return () => clearInterval(timer);
  }, [polling, queryClient]);

  const remote = isRemoteProvider(provider);

  const saveMutation = useMutation({
    mutationFn: () => saveEmbeddingConfig({
      provider: provider as 'disabled' | 'local' | 'openai' | 'ollama',
      endpoint: remote ? endpoint : undefined,
      model: remote ? model : undefined,
      dimensions: remote ? dimensions : undefined,
      apiKey: apiKey || undefined,
      remoteContentConsent: remote && consent ? true : undefined
    }),
    onSuccess: (slot) => {
      setApiKey('');
      void queryClient.invalidateQueries({ queryKey: ['ai-config'] });
      void queryClient.invalidateQueries({ queryKey: ['retrieval-status'] });
      if (slot.code === 'CONSENT_REQUIRED') {
        Message.warning('配置已保存但未授权：勾选确认后才会开始索引');
      } else {
        Message.success('Embedding 配置已保存');
      }
    },
    onError: (error) => Message.error(friendlyMessage(error, 'Embedding 配置保存失败'))
  });

  const testMutation = useMutation({
    mutationFn: testEmbeddingEndpoint,
    onSuccess: (result) => {
      if (result.ok) Message.success(`连接成功：${result.dimensions} 维，${result.latencyMs}ms`);
      else Message.error(result.message || '连接测试失败');
    },
    onError: (error) => Message.error(friendlyMessage(error, '连接测试失败'))
  });

  const refreshModelQueries = (): void => {
    void queryClient.invalidateQueries({ queryKey: ['embedding-catalog'] });
    void queryClient.invalidateQueries({ queryKey: ['retrieval-status'] });
  };

  const downloadMutation = useMutation({
    mutationFn: (id: string) => downloadEmbeddingModel(id, true),
    onSuccess: refreshModelQueries,
    onError: (error) => Message.error(friendlyMessage(error, '下载启动失败'))
  });
  const cancelMutation = useMutation({
    mutationFn: (jobId: string) => cancelEmbeddingDownload(jobId),
    onSuccess: refreshModelQueries,
    onError: (error) => Message.error(friendlyMessage(error, '取消下载失败'))
  });
  const activateMutation = useMutation({
    mutationFn: (id: string) => activateEmbeddingModel(id),
    onSuccess: () => { refreshModelQueries(); Message.success('已启用，正在后台构建向量索引'); },
    onError: (error) => Message.error(friendlyMessage(error, '启用失败'))
  });
  const disableMutation = useMutation({
    mutationFn: (id: string) => disableEmbeddingModel(id),
    onSuccess: refreshModelQueries,
    onError: (error) => Message.error(friendlyMessage(error, '停用失败'))
  });
  const uninstallMutation = useMutation({
    mutationFn: (id: string) => uninstallEmbeddingModel(id),
    onSuccess: () => { refreshModelQueries(); Message.success('已开始彻底卸载'); },
    onError: (error) => Message.error(friendlyMessage(error, '卸载失败'))
  });
  const retryMutation = useMutation({
    mutationFn: () => retryFailedEmbeddings('all'),
    onSuccess: (result) => { refreshModelQueries(); Message.success(`已重新排队 ${result.requeued} 个失败任务`); },
    onError: (error) => Message.error(friendlyMessage(error, '重试失败'))
  });
  const reindexMutation = useMutation({
    mutationFn: () => reindexEmbeddings('full', 'all'),
    onSuccess: () => { refreshModelQueries(); Message.success('已开始重建向量索引'); },
    onError: (error) => Message.error(friendlyMessage(error, '重建失败'))
  });
  const refreshCatalogMutation = useMutation({
    mutationFn: refreshEmbeddingCatalog,
    onSuccess: (data) => {
      void queryClient.invalidateQueries({ queryKey: ['embedding-catalog'] });
      if (data.onlineError) {
        Message.warning(`在线目录获取失败：${data.onlineError}`);
      } else {
        Message.success('模型目录已刷新');
      }
    },
    onError: (error) => Message.error(friendlyMessage(error, '刷新目录失败（需联网）'))
  });

  const busy = downloadMutation.isPending || cancelMutation.isPending || activateMutation.isPending
    || disableMutation.isPending || uninstallMutation.isPending;

  return (
    <section className="panel settings-ai-panel">
      <div className="panel-header"><h2>Embedding（向量索引）</h2></div>
      <div className="panel-body form-stack">
        <div>
          <Typography.Text bold>笔记向量检索</Typography.Text>
          <br />
          <Typography.Text type="secondary">
            为 AI 侧栏的混合检索提供向量召回，独立于主模型/导入模型配置。
          </Typography.Text>
        </div>
        <Form layout="vertical">
          <Form.Item label="Provider">
            <Select value={provider} onChange={(value) => { setProvider(value); setConsent(false); }}>
              {PROVIDER_OPTIONS.map((opt) => <Select.Option key={opt.value} value={opt.value}>{opt.label}</Select.Option>)}
            </Select>
          </Form.Item>

          {remote && (
            <>
              <Form.Item label="Endpoint">
                <Input value={endpoint} onChange={(value) => { setEndpoint(value); setConsent(false); }} placeholder="https://api.example.com/v1 或 http://localhost:11434" />
              </Form.Item>
              <Form.Item label="Model">
                <Input value={model} onChange={(value) => { setModel(value); setConsent(false); }} placeholder={provider === 'ollama' ? 'nomic-embed-text' : 'text-embedding-3-small'} />
              </Form.Item>
              <Form.Item label="Dimensions">
                <InputNumber value={dimensions} onChange={(value) => value != null && setDimensions(value)} min={1} max={8192} />
              </Form.Item>
              <Form.Item label="API Key">
                <Input.Password value={apiKey} onChange={setApiKey} placeholder={embedding?.hasKey ? '已配置，留空表示不修改' : '输入 API Key（Ollama 可留空）'} />
              </Form.Item>
              <Checkbox checked={consent} onChange={setConsent}>
                我理解全部当前与未来的笔记本分块将发送到上述端点用于索引
              </Checkbox>
              <Typography.Text type="secondary" style={{ display: 'block', marginTop: 4 }}>
                索引范围：全部笔记本分块；目标端点以保存时规范化结果为准。更改 provider/endpoint/model 后需重新确认。
              </Typography.Text>
              <Space wrap style={{ marginTop: 8 }}>
                <Button type="primary" loading={saveMutation.isPending} onClick={() => saveMutation.mutate()}>保存远程配置</Button>
                <Button loading={testMutation.isPending} onClick={() => testMutation.mutate()}>测试连接</Button>
              </Space>
            </>
          )}

          {!remote && (
            <Space wrap>
              <Button type="primary" loading={saveMutation.isPending} onClick={() => saveMutation.mutate()}>
                {provider === 'local' ? '切换到本地模型' : '禁用 Embedding'}
              </Button>
            </Space>
          )}
        </Form>

        {/* 本地模型目录 */}
        <div style={{ borderTop: '1px solid var(--line)', paddingTop: 16, display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
          <div>
            <Typography.Text bold>本地模型目录</Typography.Text>
            <br />
            <Typography.Text type="secondary">离线运行，笔记不出本机。仅在点击后下载。</Typography.Text>
          </div>
          <Button
            size="small"
            icon={<RefreshCw size={14} />}
            loading={refreshCatalogMutation.isPending}
            onClick={() => refreshCatalogMutation.mutate()}
          >
            刷新目录
          </Button>
        </div>
        {catalogQuery.isLoading ? <Spin /> : (catalogQuery.data?.models ?? []).length === 0 ? (
          <Empty description="暂无可用模型" />
        ) : (
          <div className="form-stack">
            {(catalogQuery.data?.models ?? []).map((m) => (
              <ModelCatalogItem
                key={m.id}
                model={m}
                status={status}
                busy={busy}
                downloading={downloadMutation.isPending}
                onDownload={() => downloadMutation.mutate(m.id)}
                onCancel={() => { const jobId = m.downloadProgress?.jobId; if (jobId) cancelMutation.mutate(jobId); }}
                onActivate={() => activateMutation.mutate(m.id)}
                onDisable={() => disableMutation.mutate(m.id)}
                onUninstall={() => uninstallMutation.mutate(m.id)}
              />
            ))}
          </div>
        )}

        {/* 索引状态 */}
        {status != null && status.indexState !== 'DISABLED' && (
          <div style={{ borderTop: '1px solid var(--line)', paddingTop: 16 }}>
            <Typography.Text bold>向量索引状态</Typography.Text>
            <div style={{ marginTop: 8 }}>
              <Progress
                percent={Math.round(status.coverage * 100)}
                status={status.indexState === 'ACTIVE' ? 'success' : 'normal'}
              />
            </div>
            <Space wrap style={{ marginTop: 8 }}>
              <Tag color="arcoblue">状态 {status.indexState}</Tag>
              <Tag>已索引 {status.indexedChunks}/{status.totalChunks}</Tag>
              <Tag color="orange">排队 {status.queuedJobs}</Tag>
              <Tag color="red">失败 {status.failedJobs}</Tag>
            </Space>
            <Space wrap style={{ marginTop: 8 }}>
              <Button size="small" icon={<RefreshCw size={14} />} loading={retryMutation.isPending} onClick={() => retryMutation.mutate()}>重试失败任务</Button>
              <Popconfirm title="全量重建向量索引？" onOk={() => reindexMutation.mutate()}>
                <Button size="small" icon={<RefreshCw size={14} />} loading={reindexMutation.isPending}>重建向量索引</Button>
              </Popconfirm>
            </Space>
          </div>
        )}
      </div>
    </section>
  );
}

interface ModelCatalogItemProps {
  model: EmbeddingCatalogModel;
  status?: RetrievalStatus;
  busy: boolean;
  downloading: boolean;
  onDownload: () => void;
  onCancel: () => void;
  onActivate: () => void;
  onDisable: () => void;
  onUninstall: () => void;
}

function ModelCatalogItem({ model, status, busy, downloading, onDownload, onCancel, onActivate, onDisable, onUninstall }: ModelCatalogItemProps): JSX.Element {
  const state = model.installationState;
  const percent = downloadPercent(model);
  const isLocalSelected = status?.provider === 'local-rust';

  return (
    <div className="embedding-model-item">
      <div className="embedding-model-main">
        <div>
          <Space wrap size={6}>
            <Typography.Text bold>{model.displayName}</Typography.Text>
            <Tag size="small">{formatModelSize(model.inventorySizeBytes)}</Tag>
            <Tag size="small" color="arcoblue">推荐</Tag>
            <Tag size="small">{model.license}</Tag>
            {model.languages.map((lang) => (
              <Tag size="small" key={lang}>{LANGUAGE_LABELS[lang] ?? lang}</Tag>
            ))}
          </Space>
          <div className="muted" style={{ marginTop: 4 }}>
            {model.dimensions} 维 · {model.providerModelId}
            {model.downloadError ? <span style={{ color: 'var(--danger, #f53f3f)' }}> · {model.downloadError}</span> : null}
          </div>
          {state === 'READY' && isLocalSelected && (
            <div style={{ marginTop: 4 }}>
              {status?.indexState === 'ACTIVE' && <Tag size="small" color="green">已启用</Tag>}
              {status?.indexState === 'REBUILDING' && <Tag size="small" color="orange">索引构建中</Tag>}
              {status?.indexState === 'DISABLED' && <Tag size="small">未启用</Tag>}
            </div>
          )}
        </div>
        <Space wrap size={4}>
          {(state === 'AVAILABLE' || state === 'PAUSED' || state === 'FAILED') && (
            <Button size="small" type="primary" icon={<Download size={14} />} disabled={busy} loading={downloading} onClick={onDownload}>下载并启用</Button>
          )}
          {state === 'DOWNLOADING' && (
            <Button size="small" status="danger" disabled={busy} onClick={onCancel}>取消</Button>
          )}
          {state === 'VERIFYING' && <Button size="small" disabled>校验中…</Button>}
          {state === 'UNINSTALLING' && <Button size="small" disabled>卸载中…</Button>}
          {state === 'READY' && (
            <>
              <Button size="small" type="primary" disabled={busy} onClick={onActivate}>启用</Button>
              <Button size="small" disabled={busy} onClick={onDisable}>停用</Button>
              <Popconfirm
                title="彻底卸载将删除模型文件与全部向量（保留 BM25 关键词索引），确认继续？"
                onOk={onUninstall}
              >
                <Button size="small" status="danger" icon={<Trash2 size={14} />} disabled={busy}>彻底卸载</Button>
              </Popconfirm>
            </>
          )}
        </Space>
      </div>
      {state === 'DOWNLOADING' && (
        <div style={{ marginTop: 8 }}>
          <Progress percent={percent} />
        </div>
      )}
    </div>
  );
}
