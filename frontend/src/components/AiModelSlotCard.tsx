import { useEffect, useMemo, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Button, Empty, Input, Message, Modal, Select, Space, Switch, Tag, Typography } from '@arco-design/web-react';
import { get, post, put } from '../lib/api';
import { friendlyMessage } from '../lib/errors';
import type { AiConfig, AiModelSlot } from '../lib/types';
import { RefreshCw, FlaskConical } from 'lucide-react';

const API_FORMAT_OPTIONS = [
  { label: 'OpenAI (Chat Completions)', value: 'chat_completions' },
  { label: 'Anthropic (Messages)', value: 'anthropic' }
];

interface TestOutcome {
  ok: boolean;
  reply: string;
  latencyMs: number;
}

/** 模糊匹配评分：exact prefix > substring > subsequence；返回 -1 表示不匹配。 */
function fuzzyScore(query: string, target: string): number {
  if (!query) return 1; // 空查询不过滤
  const q = query.toLowerCase();
  const t = target.toLowerCase();
  if (t.startsWith(q)) return 100 - t.length; // 前缀优先，更短的名字排前
  const subIdx = t.indexOf(q);
  if (subIdx >= 0) return 50 - subIdx; // 子串次之
  // 子序列匹配（按字符顺序出现）
  let qi = 0;
  let matched = 0;
  for (let ti = 0; ti < t.length && qi < q.length; ti++) {
    if (t[ti] === q[qi]) {
      qi++;
      matched++;
    }
  }
  if (qi === q.length) return 10 - (t.length - matched); // 所有查询字符都出现
  return -1;
}

function filterAndRank(models: string[], query: string): string[] {
  if (!query.trim()) return models;
  return models
    .map((name) => ({ name, score: fuzzyScore(query, name) }))
    .filter((entry) => entry.score >= 0)
    .sort((a, b) => b.score - a.score)
    .map((entry) => entry.name);
}

/**
 * 单套模型连接卡片：API 格式（chat_completions|anthropic）+ Base URL + 模型 + Key
 * + 流式开关 + 一键获取模型列表（弹窗） + 逐模型测活（自定义探活提示词）。
 * purpose: 'chat' | 'import'。
 */
export function AiModelSlotCard({ purpose, title, description }: {
  purpose: 'chat' | 'import';
  title: string;
  description: string;
}): JSX.Element {
  const queryClient = useQueryClient();
  const configQuery = useQuery({ queryKey: ['ai-config'], queryFn: () => fetchConfig() });
  const slot: AiModelSlot | undefined = purpose === 'chat' ? (configQuery.data?.chat ?? configQuery.data) : configQuery.data?.import;

  const [apiFormat, setApiFormat] = useState<'chat_completions' | 'anthropic'>('chat_completions');
  const [endpoint, setEndpoint] = useState('');
  const [model, setModel] = useState('');
  const [apiKey, setApiKey] = useState('');
  const [streaming, setStreaming] = useState(true);
  const [models, setModels] = useState<string[]>([]);
  const [testPrompt, setTestPrompt] = useState('你好，请回复 pong。');
  const [testModel, setTestModel] = useState('');
  const [testOutcomes, setTestOutcomes] = useState<Record<string, TestOutcome | 'testing'>>({});
  const [modelModalVisible, setModelModalVisible] = useState(false);
  const [modelQuery, setModelQuery] = useState('');

  useEffect(() => {
    if (!slot) return;
    if (slot.apiFormat === 'anthropic' || slot.apiFormat === 'chat_completions') setApiFormat(slot.apiFormat);
    setEndpoint(slot.endpoint || '');
    setModel(slot.model || '');
    if (typeof slot.streaming === 'boolean') setStreaming(slot.streaming);
  }, [slot]);

  const saveMutation = useMutation({
    mutationFn: () => fetchSave({
      purpose,
      endpoint,
      model,
      apiFormat,
      streaming,
      apiKey: apiKey || undefined
    }),
    onSuccess: () => {
      setApiKey('');
      void queryClient.invalidateQueries({ queryKey: ['ai-config'] });
      Message.success(`${title}已保存，密钥已加密存储`);
    },
    onError: (error) => Message.error(friendlyMessage(error, `${title}保存失败，请稍后重试`))
  });

  const fetchModelsMutation = useMutation({
    mutationFn: async () => {
      // 优先用表单当前值（保存前即可拉取）；key 留空则用已保存的 key
      const result = await post<{ models: string[] }>('/api/ai/models', {
        purpose,
        baseUrl: endpoint,
        apiKey: apiKey || undefined,
        apiFormat
      });
      return result.models;
    },
    onSuccess: (list) => {
      setModels(list);
      setTestModel('');
      setModelQuery('');
      setModelModalVisible(true);
      Message.success(`获取到 ${list.length} 个模型`);
    },
    onError: (error) => Message.error(friendlyMessage(error, '获取模型列表失败'))
  });

  const testOneMutation = useMutation({
    mutationFn: async (probeModel: string) => {
      return post<TestOutcome>('/api/ai/models/test', {
        purpose,
        baseUrl: endpoint,
        model: probeModel,
        apiKey: apiKey || undefined,
        apiFormat,
        prompt: testPrompt
      });
    },
    onMutate: (probeModel) => setTestOutcomes((current) => ({ ...current, [probeModel]: 'testing' })),
    onSuccess: (outcome, probeModel) => setTestOutcomes((current) => ({ ...current, [probeModel]: outcome })),
    onError: (error, probeModel) => setTestOutcomes((current) => ({
      ...current,
      [probeModel]: { ok: false, reply: friendlyMessage(error, '测活失败'), latencyMs: -1 }
    }))
  });

  const filteredModels = useMemo(() => filterAndRank(models, modelQuery), [models, modelQuery]);
  const busy = saveMutation.isPending || fetchModelsMutation.isPending;
  const isMock = endpoint.trim().toLowerCase() === 'mock://local';

  return (
    <div className="ai-slot-card">
      <div>
        <Typography.Text bold>{title}</Typography.Text>
        <br />
        <Typography.Text type="secondary">{description}</Typography.Text>
      </div>
      <div className="ai-slot-form">
        {/* 注意：不能用 <label> 包裹 Arco Select/Input —— 原生 label 会把点击转发给内部首个控件，
            与 Select 的 portal 下拉冲突导致“点了没反应”；统一用 div + span。 */}
        <div className="ai-slot-field">
          <span>API 格式</span>
          <Select
            value={apiFormat}
            onChange={(value) => setApiFormat(value as 'chat_completions' | 'anthropic')}
            options={API_FORMAT_OPTIONS}
            placeholder="选择 API 格式"
          />
        </div>
        <div className="ai-slot-field">
          <span>Base URL</span>
          <Input
            value={endpoint}
            onChange={setEndpoint}
            placeholder={apiFormat === 'anthropic' ? 'https://api.anthropic.com（可省 /v1）' : 'https://api.example.com/v1 或 mock://local'}
          />
        </div>
        <div className="ai-slot-field">
          <span>模型</span>
          <div className="ai-slot-model-row">
            <Input
              value={model}
              onChange={setModel}
              placeholder="输入或选择模型名，可点「获取模型」拉取列表"
              allowClear
            />
            <Button
              size="small"
              icon={<RefreshCw size={14} />}
              loading={fetchModelsMutation.isPending}
              disabled={!endpoint.trim() || isMock}
              onClick={() => fetchModelsMutation.mutate()}
            >获取模型</Button>
          </div>
        </div>
        <div className="ai-slot-field">
          <span>API Key</span>
          <Input.Password
            value={apiKey}
            onChange={setApiKey}
            placeholder={slot?.hasKey ? '已配置，留空表示不修改' : '输入 API Key'}
          />
        </div>
        <div className="ai-slot-field ai-slot-field-inline">
          <span>流式传输</span>
          <Switch checked={streaming} onChange={setStreaming} />
          <Typography.Text type="secondary">开启后支持流式输出与思考链展示</Typography.Text>
        </div>
      </div>

      <Space>
        <Button type="primary" loading={saveMutation.isPending} onClick={() => saveMutation.mutate()}>保存</Button>
        {models.length ? (
          <Button
            size="small"
            icon={<FlaskConical size={14} />}
            loading={testOneMutation.isPending}
            disabled={!model}
            onClick={() => testOneMutation.mutate(model)}
          >测活当前模型</Button>
        ) : null}
      </Space>

      <Modal
        title="选择模型"
        visible={modelModalVisible}
        onCancel={() => setModelModalVisible(false)}
        footer={null}
        unmountOnExit
        style={{ width: 640 }}
        className="ai-model-modal"
      >
        <div className="ai-model-modal-body">
          <div className="ai-model-modal-toolbar">
            <Input
              allowClear
              placeholder="搜索模型…"
              value={modelQuery}
              onChange={setModelQuery}
              autoFocus
            />
            <Input
              size="small"
              value={testPrompt}
              onChange={setTestPrompt}
              placeholder="测活提示词，默认「你好，请回复 pong。」"
              style={{ flex: 1, minWidth: 160 }}
            />
          </div>
          <div className="ai-model-modal-count">
            共 {models.length} 个模型 · 匹配 {filteredModels.length} 个
          </div>
          {filteredModels.length ? (
            <div className="ai-modal-model-list">
              {filteredModels.map((item) => {
                const outcome = testOutcomes[item];
                return (
                  <div key={item} className={`ai-modal-model-row${item === model ? ' selected' : ''}`}>
                    <button
                      type="button"
                      className="ai-modal-model-name"
                      onClick={() => { setModel(item); setModelModalVisible(false); }}
                    >{item}</button>
                    <Button
                      size="mini"
                      loading={outcome === 'testing'}
                      onClick={() => testOneMutation.mutate(item)}
                    >测活</Button>
                    {outcome && outcome !== 'testing' ? (
                      <Tag size="small" color={outcome.ok ? 'green' : 'red'}>
                        {outcome.ok ? `${outcome.latencyMs}ms` : '失败'}
                      </Tag>
                    ) : null}
                    {outcome && outcome !== 'testing' && outcome.reply ? (
                      <span className="ai-slot-model-reply" title={outcome.reply}>{outcome.reply}</span>
                    ) : null}
                  </div>
                );
              })}
            </div>
          ) : (
            <Empty
              description={
                <div>
                  <div>没有匹配的模型</div>
                  <Typography.Text type="secondary">试试更短的关键词，或清空搜索后从全部模型中挑选</Typography.Text>
                </div>
              }
            />
          )}
        </div>
      </Modal>
    </div>
  );
}

async function fetchConfig(): Promise<AiConfig> {
  return get<AiConfig>('/api/ai/config');
}

async function fetchSave(body: Record<string, unknown>): Promise<AiConfig> {
  return put<AiConfig>('/api/ai/config', body);
}
