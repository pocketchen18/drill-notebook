import type { Dispatch, SetStateAction } from 'react';
import { Button, Dropdown, Input, Menu, Tag } from '@arco-design/web-react';
import { ChevronDown, MoreHorizontal, Plus, Settings2 } from 'lucide-react';
import { incomplete, modelName, type ConnectionModel, type ConnectionProvider, type Connections } from './types';

interface Props {
  provider: ConnectionProvider; data: Connections; busy: boolean; disabled: boolean;
  search: string; setSearch: (value: string) => void;
  edit: (model?: ConnectionModel) => void; test: (model: ConnectionModel) => void; remove: (model: ConnectionModel) => void;
  selectImport: (id: string) => void; discover: () => void;
  collapsed: string[]; setCollapsed: Dispatch<SetStateAction<string[]>>;
}
export function ConnectionModelList({ provider, data, busy, disabled, search, setSearch, edit, test, remove, selectImport, discover, collapsed, setCollapsed }: Props) {
  const models = provider.models.filter(model => `${modelName(model)} ${model.modelId} ${model.group}`.toLowerCase().includes(search.toLowerCase()));
  const groups = [...new Set(models.map(model => model.group || ''))].sort((a, b) => !a ? 1 : !b ? -1 : a.localeCompare(b, 'zh-CN'));
  return <section className="connection-models" aria-label="模型列表">
    <div className="connection-model-toolbar"><h3>模型 <small>{provider.models.length}</small></h3><Input aria-label="搜索名称或 ID" placeholder="搜索名称、ID 或分组" value={search} onChange={setSearch} allowClear />
      <Button onClick={discover} disabled={disabled || busy || incomplete(provider) || provider.endpoint === 'mock://local'}>获取模型</Button><Button icon={<Plus size={15} />} onClick={() => edit()} disabled={disabled || busy}>添加模型</Button></div>
    <p className="connection-hint">{disabled ? '先保存或取消连接修改。' : '对话模型在 AI 助手中选择。'}</p>
    {!models.length && <div className="connection-empty">{provider.models.length ? <><p>没有匹配的模型</p><Button onClick={() => setSearch('')}>清空搜索</Button></> : <><p>添加模型，或从供应商获取。</p><Button onClick={() => edit()} disabled={disabled}>添加模型</Button></>}</div>}
    {groups.map(group => <div className="connection-group" key={group}>
      <button className="connection-group-title" aria-expanded={!!search || !collapsed.includes(group)} onClick={() => setCollapsed(current => current.includes(group) ? current.filter(value => value !== group) : [...current, group])}>
        <ChevronDown size={14} className={!search && collapsed.includes(group) ? 'closed' : ''} />{group || '未分组'}<span>{models.filter(model => (model.group || '') === group).length}</span>
      </button>
      {(!!search || !collapsed.includes(group)) && models.filter(model => (model.group || '') === group).map(model => <div className="connection-model-row" id={`connection-model-${model.id}`} key={model.id}>
        <button className="connection-model-name" disabled={disabled || busy} onClick={() => edit(model)} title={`${modelName(model)} · ${model.modelId}`}><strong>{modelName(model)}</strong>{model.name && model.name !== model.modelId && <code>{model.modelId}</code>}</button>
        <div className="connection-model-badges">{model.streaming && <span>流式</span>}{model.tools && <span>工具</span>}{data.importModelId === model.id && <Tag>导入</Tag>}{incomplete(provider, model) && <span>待补全</span>}</div>
        <div className="connection-actions">
          <Button size="small" aria-label={`用于导入：${modelName(model)}`} type={data.importModelId === model.id ? 'secondary' : 'primary'} disabled={disabled || busy || data.importModelId === model.id || incomplete(provider, model)} onClick={() => selectImport(model.id)}>{data.importModelId === model.id ? '已用于导入' : '用于导入'}</Button>
          <Button size="small" icon={<Settings2 size={14} />} aria-label={`设置：${modelName(model)}`} id={`connection-settings-${model.id}`} onClick={() => edit(model)} disabled={disabled || busy}>设置</Button>
          <Dropdown trigger="click" droplist={<Menu onClickMenuItem={key => key === 'test' ? test(model) : remove(model)}><Menu.Item key="test" disabled={incomplete(provider, model)}>测试连接</Menu.Item><Menu.Item key="delete">删除模型</Menu.Item></Menu>}><Button size="small" aria-label={`更多：${modelName(model)}`} disabled={disabled || busy} icon={<MoreHorizontal size={15} />} /></Dropdown>
        </div>
      </div>)}
    </div>)}
  </section>;
}
