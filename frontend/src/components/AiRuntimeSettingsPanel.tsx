import { useCallback, useContext, useEffect, useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { Button, Message, Modal } from '@arco-design/web-react';
import { RotateCcw } from 'lucide-react';
import { DEFAULT_RETRY_SETTINGS, getRuntimeSettings, runtimeFields, runtimeRetrySettings, saveRuntimeSettings, validateRuntimeSettings } from '../lib/aiRuntimeSettings';
import type { AiRuntimeSettings, AiRuntimeSettingsView } from '../lib/aiRuntimeSettings';
import type { SettingsLeaveGuard } from './ai-connections/AiConnectionsPanel';
import { ConnectionNavigationGuard } from './ai-connections/ConnectionNavigationGuard';
import { UNSAFE_DataRouterContext } from 'react-router-dom';
import { pageContextRows, usePageContextSection } from './PageContextProvider';
import './ai-runtime-settings.css';

/** 全局设置草稿与模型编辑独立，后台刷新不能覆盖尚未保存的输入。 */
export function AiRuntimeSettingsPanel({ registerGuard }: { registerGuard?: (guard: SettingsLeaveGuard | null) => void }): JSX.Element {
  const client = useQueryClient();
  const query = useQuery({ queryKey: ['ai-runtime-settings'], queryFn: getRuntimeSettings });
  const [draft, setDraft] = useState<AiRuntimeSettings | null>(null);
  const [baseline, setBaseline] = useState<AiRuntimeSettingsView | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const router = useContext(UNSAFE_DataRouterContext);
  const current = baseline ?? query.data;
  const values = draft ?? current?.values;
  const dirty = !!draft && JSON.stringify(draft) !== JSON.stringify(current?.values);
  const errors = values ? validateRuntimeSettings(values) : {};
  const retry = values ? runtimeRetrySettings(values) : null;
  usePageContextSection('runtime', [
    pageContextRows('运行限制', values ? runtimeFields.map(field => [field.label,
      Number.isFinite(values[field.key]) ? `${values[field.key]}${field.unit ? ` ${field.unit}` : ''}` : '输入无效'
    ]) : [['状态', query.isPending ? '正在读取 AI 对话设置…' : '读取失败']]),
    retry ? pageContextRows('失败重连', [
      ['请求重试次数', Number.isFinite(retry.httpRetries) ? retry.httpRetries : '输入无效'],
      ['回答重连次数', Number.isFinite(retry.streamRetries) ? retry.streamRetries : '输入无效'],
      ['断网等待', retry.networkPersistent ? '持续等待网络恢复' : '按次数上限重试'],
      ['断网重试次数', retry.networkPersistent ? undefined : Number.isFinite(retry.networkRetries) ? retry.networkRetries : '输入无效'],
      ['等待策略', '请求与回答从 400 毫秒翻倍等待；断网按 5、10、20、40、60 秒等待，之后保持 60 秒。供应商的有效等待建议优先。'],
      ['保存状态', dirty ? '有未保存的修改' : '已保存；从下一轮对话开始生效']
    ]) : ''
  ].filter(Boolean).join('\n\n'), dirty);
  const guard = useCallback((go: () => void, stay?: () => void) => {
    if (busy) { stay?.(); return; }
    if (!dirty) { go(); return; }
    Modal.confirm({ title: '有未保存的 AI 对话设置', content: '离开将放弃本次修改。', okText: '放弃修改并离开', cancelText: '继续编辑', onOk: go, onCancel: stay });
  }, [dirty, busy]);
  useEffect(() => { registerGuard?.(guard); return () => registerGuard?.(null); }, [guard, registerGuard]);
  useEffect(() => {
    const warn = (event: BeforeUnloadEvent) => { if (dirty || busy) { event.preventDefault(); event.returnValue = ''; } };
    window.addEventListener('beforeunload', warn);
    return () => window.removeEventListener('beforeunload', warn);
  }, [dirty, busy]);
  const edit = (next: AiRuntimeSettings) => {
    if (!current) return;
    setBaseline(current);
    setDraft(next);
    setError('');
  };
  const save = async () => {
    if (!current || !values || busy || Object.keys(errors).length) return;
    setBusy(true); setError('');
    try {
      const saved = await saveRuntimeSettings(current.revision, values);
      client.setQueryData(['ai-runtime-settings'], saved);
      setBaseline(null); setDraft(null);
      Message.success('已保存，从下一轮对话开始生效');
    } catch (failure) { setError(failure instanceof Error ? failure.message : '设置未保存，请重试'); }
    finally { setBusy(false); }
  };
  const cancel = () => { setDraft(null); setBaseline(null); setError(''); };
  return <section className="panel ai-runtime-settings" aria-label="AI 对话设置">
    {router && <ConnectionNavigationGuard dirty={() => dirty || busy} request={guard} />}
    <div className="panel-header"><h2>运行限制</h2>
      <Button type="text" size="small" icon={<RotateCcw size={14} />} disabled={!current || busy}
        onClick={() => current && edit({ ...current.defaults })}>恢复默认</Button>
    </div>
    <div className="panel-body">
      <p className="ai-runtime-description">对所有聊天模型生效，保存后从下一轮开始使用。</p>
      {query.isPending ? <p role="status">正在读取 AI 对话设置…</p> : !values ? <p role="alert">读取失败。<Button onClick={() => void query.refetch()}>重试</Button></p> : <form onSubmit={event => { event.preventDefault(); void save(); }}>
        <div className="ai-runtime-fields">{runtimeFields.map(field => <div className="ai-runtime-field" key={field.key}>
          <label htmlFor={`runtime-${field.key}`}>{field.label}</label>
          <div className="ai-runtime-input"><input id={`runtime-${field.key}`} type="number" min={field.min} max={field.max} step="1"
            value={Number.isFinite(values[field.key]) ? values[field.key] : ''} disabled={busy} aria-invalid={!!errors[field.key]}
            aria-describedby={`runtime-help-${field.key}`} onChange={event => edit({ ...values, [field.key]: event.target.value === '' ? NaN : Number(event.target.value) })} />
            {field.unit && <span>{field.unit}</span>}
          </div>
          <p id={`runtime-help-${field.key}`} className={errors[field.key] ? 'ai-runtime-error' : ''}>{errors[field.key] ?? field.description}</p>
        </div>)}</div>
        {retry && <fieldset className="ai-runtime-retry"><legend>失败重连</legend>
          <div className="ai-runtime-fields">{([
            ['httpRetries', '请求重试次数', '初始请求之外的重试上限，默认 4 次；设为 0 关闭。'],
            ['streamRetries', '回答重连次数', '回答中断后的重连上限，默认 5 次；设为 0 关闭。']
          ] as const).map(([key, label, help]) => <div className="ai-runtime-field" key={key}>
            <label htmlFor={`runtime-${key}`}>{label}</label>
            <div className="ai-runtime-input"><input id={`runtime-${key}`} type="number" min={0} max={100} step={1}
              value={Number.isFinite(retry[key]) ? retry[key] : ''} disabled={busy} aria-invalid={!!errors[key]}
              aria-describedby={`runtime-help-${key}`} onChange={event => edit({ ...values, retry: { ...retry,
                [key]: event.target.value === '' ? NaN : Number(event.target.value) } })} /><span>次</span></div>
            <p id={`runtime-help-${key}`} className={errors[key] ? 'ai-runtime-error' : ''}>{errors[key] ?? help}</p>
          </div>)}
          <div className="ai-runtime-field"><label htmlFor="runtime-networkPersistent">断网等待</label>
            <select id="runtime-networkPersistent" disabled={busy} value={retry.networkPersistent ? 'persistent' : 'limited'}
              onChange={event => edit({ ...values, retry: { ...retry, networkPersistent: event.target.value === 'persistent',
                networkRetries: Number.isInteger(retry.networkRetries) && retry.networkRetries >= 0 && retry.networkRetries <= 100
                  ? retry.networkRetries : DEFAULT_RETRY_SETTINGS.networkRetries } })}>
              <option value="persistent">持续等待网络恢复</option><option value="limited">按次数上限重试</option>
            </select><p>默认持续等待，可随时停止；仍受整轮最长时间限制。</p>
          </div>
          {!retry.networkPersistent && <div className="ai-runtime-field"><label htmlFor="runtime-networkRetries">断网重试次数</label>
            <div className="ai-runtime-input"><input id="runtime-networkRetries" type="number" min={0} max={100} step={1}
              value={Number.isFinite(retry.networkRetries) ? retry.networkRetries : ''} disabled={busy} aria-invalid={!!errors.networkRetries}
              aria-describedby="runtime-help-networkRetries" onChange={event => edit({ ...values, retry: { ...retry,
                networkRetries: event.target.value === '' ? NaN : Number(event.target.value) } })} /><span>次</span></div>
            <p id="runtime-help-networkRetries" className={errors.networkRetries ? 'ai-runtime-error' : ''}>{errors.networkRetries ?? '设为 0 关闭断网重试。'}</p>
          </div>}</div>
          <p className="ai-runtime-description">请求与回答重连从 400 毫秒开始翻倍等待；断网按 5、10、20、40、60 秒等待，之后保持 60 秒。供应商的有效等待建议优先。</p>
        </fieldset>}
        {error && <p role="alert" className="ai-runtime-error">{error} <Button type="text" size="mini" disabled={busy}
          onClick={() => void query.refetch().then(result => { if (result.isSuccess) cancel(); })}>放弃草稿并重新载入</Button></p>}
        <div className="ai-runtime-actions"><span>{dirty ? '有未保存的修改' : '已保存的设置'}</span>
          <Button disabled={!dirty || busy} onClick={cancel}>取消修改</Button>
          <Button type="primary" htmlType="submit" loading={busy} disabled={!dirty || Object.keys(errors).length > 0}>保存</Button>
        </div>
      </form>}
    </div>
  </section>;
}
