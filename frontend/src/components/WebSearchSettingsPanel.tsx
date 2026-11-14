import { useEffect, useRef, useState } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { Button, Input, Select, Switch } from '@arco-design/web-react';
import { useWebSearchSettings } from '../hooks/useAiToolSettings';
import { ApiError } from '../lib/api';
import { WEB_SERVICE_NAMES, saveWebSettings, testWebSettings } from '../lib/webSearchSettings';
import type { CustomWebService, WebConnectionCheck, WebSearchSettingsView, WebService, WebSettingsEdit } from '../lib/webSearchSettings';
import { WEB_FAILURE_TEXT, WEB_PROVIDERS, readWebRoute, webRouteText } from '../lib/toolActivity';
import { pageContextRows, usePageContextSection } from './PageContextProvider';

const SERVICES: WebService[] = ['auto', 'tavily', 'tinyfish'];
const CUSTOM: CustomWebService[] = ['tavily', 'tinyfish'];
const emptyKeys = () => ({ tavily: '', tinyfish: '' });

/** Immediate switches and the provider/key draft deliberately have independent save lifecycles. */
export function WebSearchSettingsPanel(): JSX.Element {
  const query = useWebSearchSettings();
  const client = useQueryClient();
  const [baseline, setBaseline] = useState<WebSearchSettingsView>();
  const [search, setSearch] = useState<WebService>('auto');
  const [fetch, setFetch] = useState<WebService>('auto');
  const [keys, setKeys] = useState(emptyKeys);
  const [cleared, setCleared] = useState<CustomWebService[]>([]);
  const [busy, setBusy] = useState<'save' | 'native' | 'test' | null>(null);
  const busyRef = useRef(false);
  const [error, setError] = useState('');
  const [checks, setChecks] = useState<WebConnectionCheck[]>([]);
  const dirty = !!baseline && (search !== baseline.searchProvider || fetch !== baseline.fetchProvider
    || CUSTOM.some(name => keys[name].trim()) || cleared.length > 0);
  const used = CUSTOM.filter(name => name === search || name === fetch);
  const visibleKeys = CUSTOM.filter(name => used.includes(name) || cleared.includes(name));
  const adopt = (value: WebSearchSettingsView): void => {
    setBaseline(value); setSearch(value.searchProvider); setFetch(value.fetchProvider); setKeys(emptyKeys()); setCleared([]);
  };
  useEffect(() => { if (query.data && !dirty) adopt(query.data); }, [query.data, dirty]);
  useEffect(() => {
    const guard = (event: BeforeUnloadEvent): void => { if (dirty || busyRef.current) { event.preventDefault(); event.returnValue = ''; } };
    window.addEventListener('beforeunload', guard);
    return () => window.removeEventListener('beforeunload', guard);
  }, [dirty]);

  // Only saved public fields are included. Password inputs and all credential drafts stay out of page snapshots.
  usePageContextSection('web-services', pageContextRows('联网服务', query.data ? [
    ['搜索服务', WEB_SERVICE_NAMES[query.data.searchProvider]], ['网页读取服务', WEB_SERVICE_NAMES[query.data.fetchProvider]],
    ['优先使用模型内置 Web 工具', query.data.preferNative ? '开启；实际支持时优先' : '关闭']
  ] : [['状态', query.isPending ? '正在读取' : '读取失败']]));

  const begin = (kind: typeof busy): boolean => {
    if (busyRef.current || !query.data || !baseline) return false;
    busyRef.current = true; setBusy(kind); setError(''); return true;
  };
  const end = (): void => { busyRef.current = false; setBusy(null); };
  const edit = (): WebSettingsEdit => {
    const changes: NonNullable<WebSettingsEdit['keys']> = {};
    for (const name of CUSTOM) {
      if (cleared.includes(name)) changes[name] = { clear: true };
      else if (keys[name].trim()) changes[name] = { apiKey: keys[name].trim() };
    }
    return { searchProvider: search, fetchProvider: fetch, keys: changes };
  };
  const validate = (): boolean => {
    const missing = used.find(name => cleared.includes(name) || (!baseline?.providers[name].hasKey && !keys[name].trim()));
    if (!missing) return true;
    setError(`请配置 ${WEB_SERVICE_NAMES[missing]} API Key。删除密钥前，请将使用它的服务改为内置自动。`); return false;
  };
  const save = async (): Promise<void> => {
    if (!validate() || !begin('save')) return;
    try {
      const value = await saveWebSettings(baseline!.revision, edit());
      client.setQueryData(['ai-web-settings'], value); adopt(value); setChecks([]);
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : '联网配置未保存');
      if (failure instanceof ApiError && failure.status === 409) {
        const fresh = await query.refetch();
        if (fresh.data) setBaseline(fresh.data); // retain the draft for an explicit second save or cancel
      }
    } finally { end(); }
  };
  const native = async (enabled: boolean): Promise<void> => {
    if (!begin('native')) return;
    try {
      const value = await saveWebSettings(query.data!.revision, { preferNative: enabled });
      client.setQueryData(['ai-web-settings'], value);
      if (baseline?.revision === query.data!.revision) setBaseline(current => current ? { ...current, revision: value.revision, preferNative: enabled } : value);
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : '内置联网偏好未保存');
      await query.refetch();
    } finally { end(); }
  };
  const test = async (): Promise<void> => {
    if (!validate() || !begin('test')) return;
    setChecks([]);
    try { setChecks((await testWebSettings(edit())).checks); }
    catch (failure) { setError(failure instanceof Error ? failure.message : '连接测试失败'); }
    finally { end(); }
  };
  const choose = (capability: 'search' | 'fetch', value: WebService): void => {
    const nextSearch = capability === 'search' ? value : search, nextFetch = capability === 'fetch' ? value : fetch;
    if (capability === 'search') setSearch(value); else setFetch(value);
    setKeys(current => Object.fromEntries(CUSTOM.map(name => [name, name === nextSearch || name === nextFetch ? current[name] : ''])) as ReturnType<typeof emptyKeys>);
    setError(''); setChecks([]);
  };

  if (query.isPending) return <p role="status" className="ai-web-help">正在读取联网服务…</p>;
  if (!query.data) return <p role="alert">联网服务读取失败。<Button size="small" onClick={() => void query.refetch()}>重试</Button></p>;
  return <div className="ai-web-settings" aria-label="联网服务配置">
    <div className="ai-tool-setting-row">
      <div><label id="web-native-label">优先使用模型内置 Web 工具</label><p id="web-native-help">支持时优先使用；不支持时使用已选服务。</p></div>
      <Switch aria-labelledby="web-native-label" aria-describedby="web-native-help" checked={query.data.preferNative}
        disabled={busy !== null || !baseline} onChange={value => void native(value)} />
    </div>
    <div className="ai-web-services">
      {(['search', 'fetch'] as const).map(capability => <div className="ai-web-field" key={capability}>
        <label id={`web-${capability}-label`}>{capability === 'search' ? '搜索服务' : '网页读取服务'}</label>
        <Select aria-labelledby={`web-${capability}-label`} value={capability === 'search' ? search : fetch} disabled={busy !== null || !baseline}
          onChange={value => choose(capability, value as WebService)} options={SERVICES.map(value => ({ value, label: value === 'auto'
            ? capability === 'search' ? '内置自动（免配置）' : '内置自动（本地优先）' : WEB_SERVICE_NAMES[value] }))} />
        <p className="ai-web-help">{(capability === 'search' ? search : fetch) === 'auto' ? capability === 'search'
          ? '优先 Exa，受限时尝试 Tavily。' : '优先本地正文提取，必要时使用内置服务。' : '使用你配置的 API Key。'}</p>
      </div>)}
    </div>
    {visibleKeys.map(name => <div className="ai-web-credential" key={name}>
      <div className="ai-web-credential-heading"><label htmlFor={`web-key-${name}`}>{WEB_SERVICE_NAMES[name]} API Key</label>
        <span className="ai-web-help">{used.includes(name) ? search === name && fetch === name ? '搜索与读取共用' : '供所选服务使用' : '删除待保存'}</span></div>
      <Input.Password id={`web-key-${name}`} autoComplete="new-password" value={keys[name]} maxLength={4096} disabled={busy !== null || cleared.includes(name)}
        placeholder={cleared.includes(name) ? '保存时删除已有密钥' : baseline?.providers[name].hasKey ? '已保存密钥；留空保留，输入新密钥替换' : '输入 API Key'}
        onChange={value => { setKeys(current => ({ ...current, [name]: value })); setError(''); setChecks([]); }} />
      {baseline?.providers[name].hasKey && <Button type="text" size="mini" disabled={busy !== null}
        onClick={() => { setCleared(current => current.includes(name) ? current.filter(value => value !== name) : [...current, name]); setKeys(current => ({ ...current, [name]: '' })); setChecks([]); }}>
        {cleared.includes(name) ? '撤销删除密钥' : '删除已保存密钥'}</Button>}
    </div>)}
    <div className="ai-web-actions">
      <Button type="secondary" size="small" loading={busy === 'test'} disabled={busy !== null && busy !== 'test' || !baseline}
        onClick={() => void test()} title="验证所选搜索和读取服务，不保存配置">测试连接</Button>
      {dirty && <><Button size="small" disabled={busy !== null} onClick={() => { adopt(query.data!); setError(''); setChecks([]); }}>取消</Button>
        <Button type="primary" size="small" loading={busy === 'save'} disabled={busy !== null && busy !== 'save'} onClick={() => void save()}>保存</Button></>}
    </div>
    {busy === 'test' && <p className="ai-web-help" role="status">正在分别验证搜索来源和网页正文…</p>}
    {checks.length > 0 && <div className="ai-web-test-results" role="status">{checks.map(check => <p key={check.capability}>
      {check.capability === 'search' ? '搜索' : '读取'}：{check.status === 'OK' && check.count > 0
        ? `${WEB_PROVIDERS[check.webProvider ?? 'local']} · ${check.count} ${check.capability === 'search' ? '个来源' : '个网页'}`
        : WEB_FAILURE_TEXT[check.status] ?? '未取得有效结果'}
      {readWebRoute(check.webRoute).filter(step => step.status !== 'OK').map((step, index) => <span className="ai-web-help" key={index}>{webRouteText(step)}</span>)}
    </p>)}</div>}
    {error && <p role="alert" className="ai-tool-settings-error">{error}</p>}
  </div>;
}
