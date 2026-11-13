import { useCallback, useContext, useEffect, useRef, useState } from 'react';
import { UNSAFE_DataRouterContext } from 'react-router-dom';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { Button, Dropdown, Input, Menu, Message, Modal, Spin, Tag } from '@arco-design/web-react';
import { MoreHorizontal, Plus, Server } from 'lucide-react';
import { apiRequest, get } from '../../lib/api';
import { ModelEditor, ProviderEditor } from './ConnectionEditors';
import { DiscoveryDialog, TestDialog } from './ConnectionDialogs';
import { ConnectionModelList } from './ConnectionModelList';
import { ConnectionNavigationGuard } from './ConnectionNavigationGuard';
import { VerificationDialog } from './ModelVerification';
import { formatName, modelName, type ConnectionModel, type ConnectionProvider, type Connections, type EditorHandle, type VerificationResponse } from './types';
import './connections.css';
import { pageContextRows, usePageContextSection } from '../PageContextProvider';

export type SettingsLeaveGuard = (go: () => void) => void;
type Editor = { kind: 'provider' | 'model'; providerId?: string; model?: ConnectionModel; revision: number };
export function AiConnectionsPanel({ registerGuard }: { registerGuard?: (guard: SettingsLeaveGuard | null) => void }) {
  const client = useQueryClient();
  const router = useContext(UNSAFE_DataRouterContext);
  const query = useQuery({ queryKey: ['ai-connections'], queryFn: async () => {
    const value = await get<Connections>('/api/ai/connections');
    const current = client.getQueryData<Connections>(['ai-connections']);
    return current && current.revision > value.revision ? current : value;
  }, staleTime: Infinity, refetchOnWindowFocus: false });
  const data = query.data;
  const currentRevision = () => client.getQueryData<Connections>(['ai-connections'])?.revision ?? data?.revision ?? 0;
  const [selected, setSelected] = useState<string>();
  const [providerSearch, setProviderSearch] = useState('');
  const [search, setSearch] = useState('');
  const [collapsed, setCollapsed] = useState<string[]>([]);
  const [editor, setEditor] = useState<Editor>();
  const editorState = useRef<EditorHandle | null>(null);
  const busyRef = useRef(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [pending, setPending] = useState<{ go: () => void; stay?: () => void }>();
  const [discover, setDiscover] = useState<ConnectionProvider>();
  const [testing, setTesting] = useState<{ provider: ConnectionProvider; model: ConnectionModel }>();
  const [verification, setVerification] = useState<{ provider: ConnectionProvider; models: Record<string, unknown>[]; password?: string; fromEditor?: boolean }>();
  const verificationBusy = useCallback((value: boolean) => { busyRef.current = value; setBusy(value); }, []);
  const [deleting, setDeleting] = useState<{ provider: ConnectionProvider; model?: ConnectionModel }>();
  useEffect(() => {
    if (!editor || pending || testing || discover || deleting || verification) return;
    const escape = (event: KeyboardEvent) => {
      if (event.key !== 'Escape' || event.defaultPrevented || document.querySelector('.arco-trigger-popup')) return;
      event.preventDefault();
      guard(finishEditor);
    };
    window.addEventListener('keydown', escape);
    return () => window.removeEventListener('keydown', escape);
  });
  const panel = useRef<HTMLDivElement>(null);
  const returnFocus = useRef<string>();
  const listScroll = useRef<number>();
  const onEditorState = useCallback((value: EditorHandle | null) => { editorState.current = value; }, []);
  const guard = useCallback((go: () => void, stay?: () => void) => {
    if (busyRef.current) { stay?.(); return; }
    if (editorState.current?.dirty) setPending(current => current ?? { go, stay }); else go();
  }, []);
  useEffect(() => { registerGuard?.(guard); return () => registerGuard?.(null); }, [guard, registerGuard]);
  useEffect(() => {
    const handler = (event: BeforeUnloadEvent) => { if (editorState.current?.dirty || busyRef.current) { event.preventDefault(); event.returnValue = ''; } };
    window.addEventListener('beforeunload', handler); return () => window.removeEventListener('beforeunload', handler);
  }, []);
  const provider = data?.providers.find(value => value.id === selected) ?? data?.providers.find(value => value.models.some(model => model.id === data.importModelId)) ?? data?.providers[0];
  const finishEditor = () => {
    editorState.current = null; setEditor(undefined);
    const focus = returnFocus.current;
    const scroll = listScroll.current;
    returnFocus.current = undefined; listScroll.current = undefined;
    requestAnimationFrame(() => {
      if (focus) document.getElementById(focus)?.focus({ preventScroll: true });
      const scroller = panel.current?.closest('.arco-layout-content');
      if (scroller && scroll !== undefined) scroller.scrollTop = scroll;
    });
  };
  const save = async (path: string, method: string, body: object, revision = editor?.revision ?? data?.revision) => {
    if (busyRef.current) return false;
    if (method === 'POST' && /\/providers\/[^/]+\/models$/.test(path) && provider) {
      setVerification({ provider, models: [body as Record<string, unknown>], fromEditor: true });
      return false;
    }
    busyRef.current = true; setBusy(true); setError('');
    try {
      const value = await apiRequest<Connections>(`/api/ai/connections${path}`, { method, body: JSON.stringify({ ...body, revision }) });
      client.setQueryData(['ai-connections'], value);
      void client.invalidateQueries({ queryKey: ['ai-config'] });
      void client.invalidateQueries({ queryKey: ['ai-memory-settings'] });
      return true;
    } catch (error) { setError(error instanceof Error ? error.message : '保存失败，请重试'); return false; }
    finally { busyRef.current = false; setBusy(false); }
  };
  const impact = (id?: string, owner?: ConnectionProvider) => [
    data?.chatModelId && (data.chatModelId === id || owner?.models.some(model => model.id === data.chatModelId)) ? 'AI 助手' : '',
    data?.importModelId && (data.importModelId === id || owner?.models.some(model => model.id === data.importModelId)) ? '导入模型' : ''
  ].filter(Boolean).join(' / ');
  const locate = (id: string | null) => guard(() => {
    finishEditor(); setProviderSearch(''); setSearch(''); setCollapsed([]);
    const owner = data?.providers.find(value => value.models.some(model => model.id === id));
    if (owner) setSelected(owner.id);
    requestAnimationFrame(() => document.getElementById(`connection-settings-${id}`)?.focus());
  });
  const label = (id: string | null | undefined) => {
    const owner = data?.providers.find(value => value.models.some(model => model.id === id));
    const model = owner?.models.find(value => value.id === id);
    return owner && model ? `${owner.name} / ${modelName(model)}` : '未设置';
  };
  const editModel = (model?: ConnectionModel) => { if (provider && data) guard(() => {
    listScroll.current = panel.current?.closest('.arco-layout-content')?.scrollTop;
    returnFocus.current = model ? `connection-settings-${model.id}` : undefined;
    setEditor({ kind: 'model', providerId: provider.id, model, revision: currentRevision() }); setError('');
  }); };
  const selectImport = async (id: string | null) => {
    const ok = await save('/bindings', 'PUT', { purpose: 'import', modelId: id }, data?.revision);
    if (ok) Message.success(id ? `导入模型已设为：${label(id)}` : '导入模型已恢复跟随 AI 助手');
  };
  usePageContextSection('connection-summary', pageContextRows('AI 连接', [
    ['加载状态', query.isPending ? '正在加载 AI 连接…' : data ? '已加载' : '加载失败'],
    ['导入模型', data ? data.importModelId ? label(data.importModelId) : '跟随 AI 助手' : undefined],
    ['供应商', data?.providers.filter(value => `${value.name} ${value.endpoint}`.toLowerCase().includes(providerSearch.toLowerCase()))
      .map(value => `${value.name}（${value.models.length} 个模型）`).join('、') || (data ? '暂无匹配的供应商' : undefined)],
    ['当前供应商', provider?.name], ['API 格式', provider ? formatName(provider) : undefined],
    ['连接地址', provider ? provider.endpoint ? '已配置' : '未填写' : undefined],
    ['认证', provider ? provider.authMode === 'none' ? '无需密钥' : provider.hasKey ? '密钥已配置' : '未配置密钥' : undefined],
    ['操作', '添加供应商、编辑连接、添加模型、选择导入模型；对话模型在 AI 助手中选择']
  ]));
  if (query.isPending) return <div className="connection-empty" role="status"><Spin /> 正在加载 AI 连接…</div>;
  if (!data) return <div className="connection-empty" role="alert"><p>{query.error instanceof Error ? query.error.message : 'AI 连接加载失败'}</p><Button onClick={() => void query.refetch()}>重试</Button></div>;
  return <div className="ai-connections" ref={panel}>
    {router && <ConnectionNavigationGuard dirty={() => !!editorState.current?.dirty || busyRef.current} request={guard} />}
    <div className="connection-current"><div><span>导入模型</span>{data.importModelId ? <><button onClick={() => locate(data.importModelId)}>{label(data.importModelId)}</button><Button type="text" size="mini" disabled={busy || !!editor} onClick={() => void selectImport(null)}>恢复跟随</Button></> : <strong>跟随 AI 助手</strong>}</div></div>
    {error && <div className="connection-error-banner" role="alert"><span>{error}</span><Button size="small" disabled={busy} onClick={() => void query.refetch().then(result => { if (result.data) setError('已重载最新配置。草稿仍保留；取消编辑后重新打开，以核对最新内容。'); })}>重载配置</Button></div>}
    <div className="connection-layout">
      <aside className="connection-providers" aria-label="供应商"><h3>供应商 <small>{data.providers.length}</small></h3><Input aria-label="搜索供应商" placeholder="搜索供应商" value={providerSearch} onChange={setProviderSearch} allowClear />
        <div className="connection-provider-list">{data.providers.filter(value => `${value.name} ${value.endpoint}`.toLowerCase().includes(providerSearch.toLowerCase())).map(value => <button key={value.id} className={`connection-provider ${provider?.id === value.id ? 'selected' : ''}`} aria-pressed={provider?.id === value.id} onClick={() => guard(() => { finishEditor(); setSelected(value.id); setSearch(''); setError(''); })} disabled={busy}><Server size={16} /><span><strong title={value.name}>{value.name}</strong><small>{value.models.length} 个模型</small></span>{value.models.some(model => model.id === data.importModelId) && <Tag size="small">导入</Tag>}</button>)}</div>
        <Button long icon={<Plus size={15} />} disabled={busy} onClick={() => guard(() => { finishEditor(); setEditor({ kind: 'provider', revision: currentRevision() }); setError(''); })}>添加供应商</Button>
      </aside>
      <main className="connection-detail">
        {editor?.kind === 'model' && provider ? <ModelEditor key={editor.model?.id ?? `new-${provider.id}`} provider={provider} model={editor.model} onState={onEditorState} save={save} leave={() => guard(finishEditor)} done={finishEditor} busy={busy} impact={impact(editor.model?.id)} test={() => editor.model && setTesting({ provider, model: editor.model })} /> : editor?.kind === 'provider' && editor.providerId && provider ? <ProviderEditor key={provider.id} provider={provider} onState={onEditorState} save={save} leave={() => guard(finishEditor)} done={finishEditor} busy={busy} impact={impact(undefined, provider)} /> : provider ? <>
          <div className="connection-detail-heading">
            <div><h2>{provider.name}</h2><p className="connection-hint">{formatName(provider)} · {provider.models.length} 个模型</p></div>
            <Dropdown trigger="click" droplist={<Menu onClickMenuItem={key => {
              setError('');
              if (key === 'edit') {
                listScroll.current = panel.current?.closest('.arco-layout-content')?.scrollTop;
                returnFocus.current = `connection-provider-actions-${provider.id}`;
                setEditor({ kind: 'provider', providerId: provider.id, revision: currentRevision() });
              } else if (key === 'delete') setDeleting({ provider });
            }}>
              <Menu.Item key="edit">编辑连接</Menu.Item>
              <Menu.Item key="delete">删除供应商</Menu.Item>
            </Menu>}>
              <Button id={`connection-provider-actions-${provider.id}`} aria-label="供应商更多操作" icon={<MoreHorizontal size={18} />} disabled={busy || !!editor} />
            </Dropdown>
          </div>
          <div className="connection-summary"><div><strong>连接信息</strong><p title={provider.endpoint}>{provider.endpoint || '待补全 Base URL'}</p><span>{provider.endpoint === 'mock://local' ? '本地演示连接' : provider.authMode === 'none' ? '无需密钥' : provider.hasKey ? '密钥已配置' : '待补全密钥'}</span></div></div>
          <ConnectionModelList key={`models-${provider.id}`} provider={provider} data={data} busy={busy} disabled={!!editor} search={search} setSearch={setSearch} collapsed={collapsed} setCollapsed={setCollapsed} edit={editModel} test={model => setTesting({ provider, model })} remove={model => setDeleting({ provider, model })} selectImport={id => void selectImport(id)} discover={() => setDiscover(provider)} />
        </> : <div className="connection-empty"><Server size={32} /><h2>添加你的第一个供应商</h2><p>保存连接，再添加模型。</p><Button type="primary" onClick={() => setEditor({ kind: 'provider', revision: currentRevision() })}>添加供应商</Button></div>}
      </main>
    </div>
    {editor?.kind === 'provider' && !editor.providerId && <Modal visible title="添加供应商" footer={null} maskClosable={false} onCancel={() => guard(finishEditor)}><ProviderEditor onState={onEditorState} save={save} leave={() => guard(finishEditor)} done={() => { finishEditor(); const latest = client.getQueryData<Connections>(['ai-connections']); const added = latest?.providers.at(-1); setSelected(added?.id); setDiscover(added); }} busy={busy} />{error && <p role="alert" className="connection-error">{error}</p>}</Modal>}
    {discover && <DiscoveryDialog provider={discover} close={() => setDiscover(undefined)} busy={busy} saveError={error} add={(models, password) => { setVerification({ provider: discover, models, password }); setDiscover(undefined); }} />}
    {verification && <VerificationDialog provider={verification.provider} models={verification.models} password={verification.password} onBusy={verificationBusy} refresh={() => { void query.refetch(); }} close={() => {
      const latest = client.getQueryData<Connections>(['ai-connections']);
      const saved = verification.fromEditor && latest?.providers.find(item => item.id === verification.provider.id)?.models.some(item => item.modelId === verification.models[0].modelId);
      setVerification(undefined); void query.refetch();
      if (saved) { finishEditor(); if (pending) { setPending(undefined); pending.go(); } }
    }} verify={async (model, masterPassword, requestId, signal) => {
      const value = await apiRequest<VerificationResponse>(`/api/ai/connections/providers/${verification.provider.id}/verify-add`, { method: 'POST', signal, body: JSON.stringify({ model, masterPassword, requestId, revision: currentRevision(), connectionVersion: verification.provider.connectionVersion }) });
      if (!signal.aborted && value.connections) {
        const next = value.connections;
        client.setQueryData<Connections>(['ai-connections'], current => current && current.revision > next.revision ? current : next);
        if (verification.fromEditor && value.added) finishEditor();
      }
      return value;
    }} />}
    {testing && <TestDialog provider={testing.provider} model={testing.model} close={() => setTesting(undefined)} />}
    {deleting && <Modal visible title={deleting.model ? '删除模型' : '删除供应商'} onCancel={() => !busy && setDeleting(undefined)} maskClosable={false} footer={<div className="connection-actions end"><Button disabled={busy} onClick={() => setDeleting(undefined)}>取消</Button><Button status="danger" loading={busy} onClick={() => void save(deleting.model ? `/models/${deleting.model.id}` : `/providers/${deleting.provider.id}`, 'DELETE', {}, data.revision).then(ok => { if (ok) setDeleting(undefined); })}>确认删除</Button></div>}><p>删除「{deleting.model ? modelName(deleting.model) : deleting.provider.name}」？{!deleting.model && `将移除此连接的密钥和 ${deleting.provider.models.length} 个模型。`}</p>{impact(deleting.model?.id, deleting.model ? undefined : deleting.provider) && <p className="connection-error">此配置仍被使用，请在 AI 助手中切换对话模型，或更换导入模型。</p>}{error && <p role="alert" className="connection-error">{error}</p>}</Modal>}
    {pending && !verification && <Modal visible title="有未保存的修改" maskClosable={false} onCancel={() => { pending.stay?.(); setPending(undefined); }} footer={<div className="connection-actions end"><Button disabled={busy} onClick={() => { pending.stay?.(); setPending(undefined); }}>继续编辑</Button><Button disabled={busy} onClick={() => { const go = pending.go; setPending(undefined); finishEditor(); go(); }}>放弃修改</Button><Button type="primary" loading={busy} onClick={() => void editorState.current?.save().then(ok => { if (ok) { const go = pending.go; setPending(undefined); go(); } })}>保存并继续</Button></div>}><p>保存后再离开，或放弃本次修改。</p>{error && <p role="alert" className="connection-error">{error}</p>}</Modal>}
  </div>;
}
