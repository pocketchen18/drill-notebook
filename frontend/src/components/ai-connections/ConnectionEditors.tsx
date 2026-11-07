import { useEffect, useLayoutEffect, useRef, useState, type ElementRef } from 'react';
import { Button, Input, Select, Switch } from '@arco-design/web-react';
import { ArrowLeft, Copy } from 'lucide-react';
import { defaultTools, modelName, type ConnectionModel, type ConnectionProvider, type EditorHandle, type SaveConnection } from './types';
import { matchPreset, presetFields, reasoningDraft, supportedEfforts, useModelPresets, type ModelDraft } from './modelPresets';
import { ReasoningSettings } from './ReasoningSettings';
import { VerificationDetails } from './ModelVerification';

interface EditorProps {
  provider?: ConnectionProvider;
  onState: (value: EditorHandle | null) => void;
  save: SaveConnection;
  leave: () => void;
  done: () => void;
  busy: boolean;
  impact?: string;
}

export function ProviderEditor({ provider, onState, save, leave, done, busy, impact }: EditorProps) {
  const [initial] = useState(() => ({ name: provider?.name ?? '', endpoint: provider?.endpoint ?? '', apiFormat: provider?.apiFormat ?? 'chat_completions',
    authMode: provider?.authMode ?? 'api_key', apiKey: '', changeKey: !provider?.hasKey }));
  const [draft, setDraft] = useState(initial);
  const [error, setError] = useState('');
  const nameRef = useRef<ElementRef<typeof Input>>(null);
  const heading = useRef<HTMLHeadingElement>(null);
  useEffect(() => { if (heading.current) heading.current.focus(); else nameRef.current?.focus(); }, []);
  const dirty = JSON.stringify(draft) !== JSON.stringify(initial);
  const patch = (value: Partial<typeof draft>) => { setDraft(current => ({ ...current, ...value })); setError(''); };
  const submit = async () => {
    if (!draft.name.trim() || !draft.endpoint.trim()) { setError('请填写供应商名称和 Base URL'); return false; }
    if (draft.authMode === 'api_key' && draft.changeKey && !draft.apiKey.trim()) { setError('请输入新密钥，或取消更换'); return false; }
    try {
      const url = new URL(draft.endpoint.trim());
      if (draft.endpoint !== 'mock://local' || provider?.endpoint !== 'mock://local') {
        if (!['http:', 'https:'].includes(url.protocol) || url.username || url.password || url.search || url.hash) throw new Error();
      }
    } catch { setError('Base URL 须为不含账号密码、查询参数或片段的 HTTP(S) 地址'); return false; }
    const ok = await save(`/providers${provider ? `/${provider.id}` : ''}`, provider ? 'PUT' : 'POST', {
      name: draft.name.trim(), endpoint: draft.endpoint.trim(), apiFormat: draft.apiFormat, authMode: draft.authMode,
      ...(draft.authMode === 'api_key' && draft.changeKey ? { apiKey: draft.apiKey.trim() } : {})
    });
    if (ok) done();
    return ok;
  };
  useLayoutEffect(() => { onState({ dirty, save: submit }); });
  useLayoutEffect(() => () => onState(null), [onState]);
  return <div className={`connection-form${provider ? ' connection-editor' : ''}`}>
    {provider ? <>
      <Button type="text" icon={<ArrowLeft size={16} />} onClick={leave} disabled={busy}>返回模型列表</Button>
      <h2 tabIndex={-1} ref={heading}>编辑连接</h2>
      <p className="connection-hint">{provider.name}</p>
    </> : <h3>添加供应商</h3>}
    <div className="connection-field"><label htmlFor="provider-name">供应商名称</label><Input ref={nameRef} id="provider-name" maxLength={60} value={draft.name} onChange={name => patch({ name })} placeholder="例如：学习网关" disabled={busy} /></div>
    <div className="connection-field"><span id="provider-format-label">API 格式</span><Select aria-labelledby="provider-format-label" value={draft.apiFormat} onChange={apiFormat => patch({ apiFormat })} disabled={busy} options={[{ value: 'chat_completions', label: 'OpenAI Chat Completions' }, { value: 'anthropic', label: 'Anthropic Messages' }]} /></div>
    <div className="connection-field"><label htmlFor="provider-endpoint">Base URL</label><Input id="provider-endpoint" value={draft.endpoint} maxLength={2048} onChange={endpoint => patch({ endpoint })} placeholder="https://example.com/v1" disabled={busy} /></div>
    <div className="connection-field"><span id="provider-auth-label">认证方式</span><Select aria-labelledby="provider-auth-label" value={draft.authMode} onChange={authMode => patch({ authMode })} disabled={busy} options={[{ value: 'api_key', label: 'API Key' }, { value: 'none', label: '无需密钥' }]} /></div>
    {draft.authMode === 'api_key' && <div className="connection-field">
      <label htmlFor="provider-key">API Key</label>
      {!draft.changeKey ? <div className="connection-actions"><span>已配置</span><Button size="small" onClick={() => patch({ changeKey: true })}>更换</Button></div> : <>
        <Input.Password id="provider-key" value={draft.apiKey} maxLength={8192} autoComplete="new-password" onChange={apiKey => patch({ apiKey })} disabled={busy} placeholder="输入密钥" />
        {provider?.hasKey && <Button type="text" onClick={() => patch({ apiKey: '', changeKey: false })}>取消更换</Button>}
      </>}
    </div>}
    {draft.authMode === 'none' && provider?.hasKey && <p className="connection-hint">保存后将移除此连接的密钥。</p>}
    {impact && <p className="connection-hint">影响{impact}；从下一次请求生效。</p>}
    {error && <p role="alert" className="connection-error">{error}</p>}
    <div className="connection-actions end"><Button disabled={busy} onClick={leave}>取消</Button><Button type="primary" loading={busy} onClick={() => void submit()}>保存连接</Button></div>
  </div>;
}

interface ModelProps extends EditorProps { provider: ConnectionProvider; model?: ConnectionModel; test?: () => void }
export function ModelEditor({ provider, model, onState, save, leave, done, busy, impact, test }: ModelProps) {
  const [initial] = useState<ModelDraft>(() => ({ modelId: model?.modelId ?? '', name: model?.name ?? '', group: model?.group ?? '', streaming: model?.streaming ?? true,
    tools: model?.tools ?? defaultTools(provider, ''), contextWindow: String(model?.contextWindow ?? ''), maxInputTokens: String(model?.maxInputTokens ?? ''), maxOutputTokens: String(model?.maxOutputTokens ?? ''), ...reasoningDraft(model) }));
  const [draft, setDraft] = useState(initial);
  const presets = useModelPresets(provider);
  const preset = matchPreset(presets.data, draft.modelId) ?? model?.preset ?? undefined;
  const touched = useRef(new Set<keyof ModelDraft>());
  const [toolsTouched, setToolsTouched] = useState(false);
  const [error, setError] = useState('');
  const heading = useRef<HTMLHeadingElement>(null);
  const dirty = JSON.stringify(initial) !== JSON.stringify(draft);
  useEffect(() => { heading.current?.focus(); }, []);
  const patch = (value: Partial<ModelDraft>) => { for (const field of Object.keys(value)) touched.current.add(field as keyof ModelDraft); setDraft(current => ({ ...current, ...value })); setError(''); };
  useEffect(() => {
    if (model || presets.isPending) return;
    setDraft(current => {
      const next = { ...current };
      for (const [field, value] of Object.entries(presetFields(preset))) {
        if (!touched.current.has(field as keyof ModelDraft)) Object.assign(next, { [field]: value });
      }
      if (!next.reasoningEfforts.includes(next.defaultReasoningEffort)) next.defaultReasoningEffort = '';
      return next;
    });
  }, [preset, model, presets.isPending]);
  const restorePreset = () => {
    if (!preset) return;
    const values = presetFields(preset);
    delete values.name;
    patch({ ...values, defaultReasoningEffort: preset.reasoningEfforts.includes(draft.defaultReasoningEffort) ? draft.defaultReasoningEffort : '' });
  };
  const customized = preset && (draft.contextWindow !== String(preset.contextWindow ?? '') || draft.maxOutputTokens !== String(preset.maxOutputTokens ?? '') || draft.reasoningControl !== preset.reasoningControl || JSON.stringify(draft.reasoningEfforts) !== JSON.stringify(preset.reasoningEfforts));
  const submit = async () => {
    if (!draft.modelId.trim()) { setError('请填写模型 ID'); return false; }
    const limits: Record<string, number | null> = {};
    for (const field of ['contextWindow', 'maxInputTokens', 'maxOutputTokens'] as const) {
      const raw = draft[field].trim();
      if (raw && (!/^\d+$/.test(raw) || Number(raw) < 1 || Number(raw) > 2147483647)) { setError('Token 上限须为 1–2147483647 的整数，留空使用应用策略'); return false; }
      limits[field] = raw ? Number(raw) : null;
    }
    if (limits.contextWindow && ((limits.maxInputTokens ?? 0) > limits.contextWindow || (limits.maxOutputTokens ?? 0) > limits.contextWindow)) {
      setError('输入与输出上限分别不得大于上下文窗口'); return false;
    }
    if (draft.reasoningControl === 'effort' && draft.reasoningEfforts.some(level => !supportedEfforts(provider.apiFormat).includes(level))) { setError('当前协议不支持所选思考等级，请取消不支持的等级'); return false; }
    if (draft.defaultReasoningEffort && !draft.reasoningEfforts.includes(draft.defaultReasoningEffort)) { setError('默认等级必须属于已勾选的思考等级'); return false; }
    const budget = draft.reasoningControl === 'budget' ? draft.thinkingBudgetTokens.trim() : '';
    if (budget && (!/^\d+$/.test(budget) || Number(budget) < 1024 || Number(budget) >= 2147483647 || (limits.maxOutputTokens && Number(budget) >= limits.maxOutputTokens))) { setError('思考预算须为至少 1024 的整数，且小于最大输出 Token'); return false; }
    const payload: Record<string, unknown> = {
      ...draft, modelId: draft.modelId.trim(), name: draft.name.trim(), group: draft.group.trim(), ...limits, thinkingBudgetTokens: budget ? Number(budget) : null
    };
    // 新模型由服务端补齐尚未加载的预设；显式编辑过的字段始终优先。
    if (!model && (presets.isPending || presets.isError)) {
      for (const field of Object.keys(presetFields())) if (!touched.current.has(field as keyof ModelDraft)) delete payload[field];
    }
    const ok = await save(model ? `/models/${model.id}` : `/providers/${provider.id}/models`, model ? 'PUT' : 'POST', payload);
    if (ok) done();
    return ok;
  };
  useLayoutEffect(() => { onState({ dirty, save: submit }); });
  useLayoutEffect(() => () => onState(null), [onState]);
  return <div className="connection-form connection-editor">
    <Button type="text" icon={<ArrowLeft size={16} />} onClick={leave} disabled={busy}>返回模型列表</Button>
    <h2 tabIndex={-1} ref={heading}>{model ? modelName(model) : '添加模型'}</h2>
    <p className="connection-hint">{provider.name}{impact ? ` · ${impact}` : ''}</p>
    <p className="connection-hint" role="status">{preset ? `参考预设：${preset.name}${customized ? ' · 已自定义' : ''}` : presets.isPending ? '正在读取预设资料…' : '未匹配预设，可手动配置'}{presets.isError && '（预设资料加载失败）'}</p>
    <h3>基本信息</h3>
    <div className="connection-field"><label htmlFor="model-id">模型 ID</label><div className="connection-actions"><Input id="model-id" maxLength={240} readOnly={!!model} value={draft.modelId} onChange={modelId => patch({ modelId, ...(!model && !toolsTouched ? { tools: defaultTools(provider, modelId) } : {}) })} placeholder="填写供应商提供的模型 ID" disabled={busy} />{model && <Button aria-label="复制模型 ID" icon={<Copy size={15} />} onClick={() => void navigator.clipboard.writeText(model.modelId).catch(() => setError('复制失败，请手动选中复制'))} />}</div></div>
    <div className="connection-field"><label htmlFor="model-name">模型名称</label><Input id="model-name" value={draft.name} maxLength={80} onChange={name => patch({ name })} placeholder="留空使用模型 ID" disabled={busy} /></div>
    <div className="connection-field"><label htmlFor="model-group">分组名称</label><Input id="model-group" list="connection-groups" value={draft.group} maxLength={40} onChange={group => patch({ group })} placeholder="未分组" disabled={busy} /><datalist id="connection-groups">{[...new Set(provider.models.map(value => value.group).filter(Boolean))].map(value => <option key={value} value={value} />)}</datalist></div>
    <ReasoningSettings draft={draft} patch={patch} format={provider.apiFormat} busy={busy} preset={preset} restore={restorePreset} />
    {model?.verification && <details className="connection-limits"><summary>最近验证结果</summary><VerificationDetails value={model.verification} /><p className="connection-hint">结果来自添加时的检测，不保证后续服务一直可用。</p></details>}
    <h3>输出与工具</h3>
    <div className="connection-setting"><div><strong id="stream-label">流式输出</strong><p id="stream-help">开启后逐步显示回答。</p></div><Switch aria-labelledby="stream-label" aria-describedby="stream-help" checked={draft.streaming} onChange={streaming => patch({ streaming })} disabled={busy} /></div>
    <div className="connection-setting"><div><strong id="tools-label">允许工具调用</strong><p id="tools-help">{model ? '允许模型使用应用已提供并获授权的工具。' : '添加时根据验证结果自动设置，添加后可调整。'}</p>{impact?.includes('导入') && <p>仅对主模型对话生效，导入解析不调用记忆工具。</p>}</div><Switch aria-labelledby="tools-label" aria-describedby="tools-help" checked={model ? draft.tools : false} onChange={tools => { setToolsTouched(true); patch({ tools }); }} disabled={busy || !model} /></div>
    <details className="connection-limits"><summary>上下文与输出上限（可选）</summary><p className="connection-hint">自定义上限不会突破应用的任务限制。留空沿用应用策略；容量按保守估算检查。</p>
      {([['contextWindow', '上下文窗口'], ['maxInputTokens', '最大输入 Token'], ['maxOutputTokens', '最大输出 Token']] as const).map(([field, label]) => <div className="connection-field" key={field}><label htmlFor={`model-${field}`}>{label}</label><Input id={`model-${field}`} inputMode="numeric" value={draft[field]} onChange={value => patch({ [field]: value })} placeholder="未指定" disabled={busy} /></div>)}
    </details>
    {impact && <p className="connection-hint">保存后影响{impact}，从下一次请求生效。</p>}
    {error && <p role="alert" className="connection-error">{error}</p>}
    {!model && <p className="connection-hint">添加前自动验证文本、视觉与工具，可能产生少量用量；工具通过后自动开启。</p>}
    <div className="connection-actions end">{model && <Button disabled={dirty || busy} onClick={test}>测试连接</Button>}<Button onClick={leave} disabled={busy}>取消</Button><Button type="primary" loading={busy} onClick={() => void submit()}>{model ? '保存模型设置' : '验证并添加模型'}</Button></div>
    {dirty && model && <p className="connection-hint">先保存或取消修改，再测试连接。</p>}
  </div>;
}
