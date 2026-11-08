import { useEffect, useRef, useState } from 'react';
import { Button, Checkbox, Input, Modal, Spin } from '@arco-design/web-react';
import { apiRequest } from '../../lib/api';
import { modelName, type ConnectionModel, type ConnectionProvider } from './types';
import { effortLabel, matchPreset, useModelPresets } from './modelPresets';
import { ModelIdInput } from './ModelIdInput';

interface Props { provider: ConnectionProvider; close: () => void }
export function DiscoveryDialog({ provider, close, add, busy, saveError }: Props & { add: (ids: string[], password: string) => void; busy: boolean; saveError?: string }) {
  const presets = useModelPresets(provider);
  const [ids, setIds] = useState<string[] | null>(null);
  const [selected, setSelected] = useState<string[]>([]);
  const [search, setSearch] = useState('');
  const [manual, setManual] = useState('');
  const [password, setPassword] = useState('');
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const request = useRef<AbortController>();
  const fetchModels = async () => {
    request.current?.abort();
    const controller = new AbortController(); request.current = controller;
    setLoading(true); setError('');
    try {
      const value = await apiRequest<string[]>(`/api/ai/connections/providers/${provider.id}/discover`, {
        method: 'POST', body: JSON.stringify({ masterPassword: password }), signal: controller.signal
      });
      if (!controller.signal.aborted) setIds([...new Set(value)]);
    } catch (error) { if (!controller.signal.aborted) setError(error instanceof Error ? error.message : '获取失败，可手动添加模型'); }
    finally { if (!controller.signal.aborted) setLoading(false); }
  };
  useEffect(() => {
    const timer = window.setTimeout(() => { if (!provider.needsPassword) void fetchModels(); }, 0);
    return () => { window.clearTimeout(timer); request.current?.abort(); };
  }, []);
  const filtered = ids?.filter(id => id.toLowerCase().includes(search.toLowerCase())) ?? [];
  return <Modal visible title={`获取模型 · ${provider.name}`} onCancel={() => { if (!busy) close(); }} maskClosable={false} footer={
    <div className="connection-actions end"><Button disabled={busy} onClick={close}>稍后添加</Button><Button type="primary" loading={busy} disabled={!selected.length || selected.length > 20 || loading || (provider.needsPassword && !password)} onClick={() => add(selected, password)}>验证并添加（{selected.length}）</Button></div>
  }>
    <p className="connection-hint">每个模型最多发送三次短请求，可能产生少量用量；仅使用测试文字、图片和工具。一次最多选择 20 个模型。</p>
    {provider.needsPassword && <div className="connection-field"><label htmlFor="discover-password">此连接主密码</label><Input.Password id="discover-password" value={password} onChange={setPassword} /><Button disabled={!password || loading} onClick={() => void fetchModels()}>获取模型</Button></div>}
    {loading && <div role="status"><Spin /> 正在获取模型…</div>}
    {saveError && <p role="alert" className="connection-error">{saveError}</p>}
    {error && <div role="alert" className="connection-error">{error}<p>也可在下方手动填写模型 ID。</p><Button onClick={() => void fetchModels()}>重试</Button></div>}
    {ids && <><Input aria-label="搜索获取的模型" placeholder="搜索模型 ID" value={search} onChange={setSearch} allowClear />
      <div className="connection-discovery" aria-label="可添加的模型">
        {filtered.map(id => { const exists = provider.models.some(model => model.modelId === id), preset = matchPreset(presets.data, id); return <div key={id}><Checkbox disabled={exists || busy} checked={exists || selected.includes(id)} onChange={checked => setSelected(current => checked ? [...current, id] : current.filter(value => value !== id))}><span>{id}</span><small className="connection-preset-match">{exists ? '已添加' : preset ? `已匹配预设 · ${preset.name}${preset.reasoningEfforts.length ? ` · ${effortLabel(preset.reasoningEfforts)}` : ''}` : '未匹配预设，可手动配置'}</small></Checkbox></div>; })}
        {!filtered.length && <p className="connection-hint">{ids.length ? '没有匹配模型' : '此服务未提供模型列表，可手动添加模型 ID。'}</p>}
      </div></>}
    <div className="connection-field"><label htmlFor="discover-manual-id">手动模型 ID</label><div className="connection-actions"><ModelIdInput id="discover-manual-id" value={manual} presets={presets.data} loading={presets.isPending} disabled={busy} onChange={setManual} onSelect={setManual} /><Button disabled={!manual.trim() || busy || selected.length >= 20} onClick={() => { const id = manual.trim(); if (provider.models.some(model => model.modelId === id)) { setError('此模型已添加'); return; } setIds(current => [...new Set([...(current ?? []), id])]); setSelected(current => [...new Set([...current, id])]); setSearch(''); setManual(''); }}>加入待选</Button></div>{matchPreset(presets.data, manual) && <p className="connection-hint">已匹配预设 · {matchPreset(presets.data, manual)!.name}，添加时自动填充设置。</p>}</div>
    {selected.length > 20 && <p role="alert" className="connection-error">一次最多验证 20 个模型，请减少选择。</p>}
  </Modal>;
}

export function TestDialog({ provider, model, close }: Props & { model: ConnectionModel }) {
  const [prompt, setPrompt] = useState('你好，请回复 pong。');
  const [password, setPassword] = useState('');
  const [status, setStatus] = useState<'idle' | 'loading' | 'done' | 'error' | 'cancelled'>('idle');
  const [result, setResult] = useState('');
  const request = useRef<AbortController>();
  const requestId = useRef<string>();
  const stop = () => {
    request.current?.abort();
    if (requestId.current) void apiRequest(`/api/ai/connections/tests/${requestId.current}/cancel`, { method: 'POST' }).catch(() => undefined);
    requestId.current = undefined;
  };
  useEffect(() => stop, []);
  const cancel = () => { stop(); setStatus('cancelled'); setResult('已取消测试；本次结果不再展示。'); };
  const test = async () => {
    request.current?.abort();
    const controller = new AbortController(); request.current = controller;
    const id = crypto.randomUUID(); requestId.current = id;
    setStatus('loading'); setResult('');
    try {
      const value = await apiRequest<{ reply: string; elapsedMs: number }>(`/api/ai/connections/models/${model.id}/test`, {
        method: 'POST', body: JSON.stringify({ prompt, masterPassword: password, requestId: id }), signal: controller.signal
      });
      if (!controller.signal.aborted) { setResult(`基础连接通过 · ${value.elapsedMs} ms\n${value.reply}`); setStatus('done'); }
    } catch (error) { if (!controller.signal.aborted) { setResult(error instanceof Error ? error.message : '连接失败，请重试'); setStatus('error'); } }
    finally { if (requestId.current === id) requestId.current = undefined; }
  };
  return <Modal visible title={`测试连接 · ${modelName(model)}`} onCancel={close} maskClosable={false} footer={
    <div className="connection-actions end"><Button onClick={close}>关闭</Button>{status === 'loading' ? <Button onClick={cancel}>取消测试</Button> : <Button type="primary" disabled={!prompt.trim() || (provider.needsPassword && !password)} onClick={() => void test()}>开始测试</Button>}</div>
  }>
    <p className="connection-hint">发送一次短请求，可能产生少量用量。仅测试基础连接，不发送会话、笔记、记忆或工具。</p>
    <div className="connection-field"><label htmlFor="connection-test-prompt">测试提示词</label><Input.TextArea id="connection-test-prompt" value={prompt} maxLength={2000} onChange={setPrompt} disabled={status === 'loading'} /></div>
    {provider.needsPassword && <div className="connection-field"><label htmlFor="test-password">此连接主密码</label><Input.Password id="test-password" value={password} onChange={setPassword} disabled={status === 'loading'} /></div>}
    {status === 'loading' && <div role="status"><Spin /> 测试中…</div>}
    {result && <pre role={status === 'error' ? 'alert' : 'status'} className={`connection-result ${status === 'error' ? 'connection-error' : ''}`}>{result}</pre>}
  </Modal>;
}
