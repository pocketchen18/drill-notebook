import { useEffect, useMemo, useRef, useState } from 'react';
import { Check, ChevronDown, Search } from 'lucide-react';
import type { ConnectionModel, ConnectionProvider, Connections } from '../ai-connections/types';
import './model-picker.css';

export interface ModelChoice { modelConfigId: string | null; reasoningEffort: string | null }

interface Props {
  connections?: Connections;
  value: ModelChoice;
  /** 回复期间仍可改选，只影响之后提交的消息。 */
  busy?: boolean;
  disabled?: boolean;
  onChange: (next: ModelChoice) => void;
  onManage: () => void;
}

const find = (connections: Connections | undefined, id: string | null): { provider: ConnectionProvider; model: ConnectionModel } | undefined => {
  for (const provider of connections?.providers ?? []) {
    const model = provider.models.find((item) => item.id === id);
    if (model) return { provider, model };
  }
  return undefined;
};
/** 缺少容量的模型不能用于对话，需先在 AI 连接里补全。 */
const usable = (model: ConnectionModel): boolean => !!model.contextWindow && !!model.maxOutputTokens;
const effortsOf = (model?: ConnectionModel): string[] =>
  model && (model.reasoningControl ?? 'effort') === 'effort' ? model.reasoningEfforts ?? [] : [];

/** 输入区的模型与思考档位：选择属于当前会话，不改主模型，也不影响其他会话。 */
export function ModelPicker({ connections, value, busy, disabled, onChange, onManage }: Props): JSX.Element {
  const [open, setOpen] = useState<'model' | 'effort' | null>(null);
  const [query, setQuery] = useState('');
  const root = useRef<HTMLDivElement>(null);
  const search = useRef<HTMLInputElement>(null);
  const main = find(connections, connections?.chatModelId ?? null);
  const chosen = find(connections, value.modelConfigId);
  const missing = value.modelConfigId !== null && !chosen && !!connections;
  const active = chosen ?? main;
  const efforts = effortsOf(active?.model);
  const effort = value.reasoningEffort && efforts.includes(value.reasoningEffort) ? value.reasoningEffort : null;
  const needle = query.trim().toLowerCase();
  const groups = useMemo(() => (connections?.providers ?? []).map((provider) => ({ provider,
    models: provider.models.filter((model) => !needle || `${model.modelId} ${model.name} ${provider.name}`.toLowerCase().includes(needle)) }))
    .filter((group) => group.models.length), [connections, needle]);

  useEffect(() => {
    if (!open) return;
    if (open === 'model') search.current?.focus();
    const outside = (event: PointerEvent): void => { if (!root.current?.contains(event.target as Node)) setOpen(null); };
    const escape = (event: KeyboardEvent): void => {
      if (event.key !== 'Escape') return;
      event.preventDefault(); event.stopPropagation(); setOpen(null);
      root.current?.querySelector<HTMLButtonElement>(`[data-trigger="${open}"]`)?.focus();
    };
    document.addEventListener('pointerdown', outside);
    document.addEventListener('keydown', escape, true);
    return () => { document.removeEventListener('pointerdown', outside); document.removeEventListener('keydown', escape, true); };
  }, [open]);

  const pick = (modelConfigId: string | null): void => {
    const target = find(connections, modelConfigId ?? connections?.chatModelId ?? null)?.model;
    // 换到不支持当前档位的模型时回到模型默认。
    const keep = value.reasoningEffort && effortsOf(target).includes(value.reasoningEffort) ? value.reasoningEffort : null;
    onChange({ modelConfigId, reasoningEffort: keep });
    setOpen(null); setQuery('');
  };
  const label = missing ? '模型已删除' : active ? active.model.modelId : '未配置模型';

  return <div className="model-picker" ref={root}>
    <button type="button" className={`model-picker-trigger${missing ? ' is-warning' : ''}`} data-trigger="model" disabled={disabled}
      aria-haspopup="dialog" aria-expanded={open === 'model'} aria-label={`模型：${label}`}
      title={active ? `${active.provider.name} · ${active.model.modelId}${chosen ? '' : '（跟随主模型）'}` : undefined}
      onClick={() => setOpen(open === 'model' ? null : 'model')}><span>{label}</span><ChevronDown size={13} /></button>
    {efforts.length ? <button type="button" className="model-picker-trigger" data-trigger="effort" disabled={disabled}
      aria-haspopup="menu" aria-expanded={open === 'effort'} aria-label={`思考档位：${effort ?? '模型默认'}`}
      onClick={() => setOpen(open === 'effort' ? null : 'effort')}><span>{effort ?? '默认档位'}</span><ChevronDown size={13} /></button> : null}

    {open === 'model' ? <div className="model-picker-pop" role="dialog" aria-label="选择模型">
      <label className="model-picker-search"><Search size={14} aria-hidden="true" />
        <input ref={search} value={query} placeholder="查找模型或供应商" aria-label="查找模型" onChange={(event) => setQuery(event.target.value)} /></label>
      <div className="model-picker-list">
        {!needle ? <button type="button" role="menuitemradio" aria-checked={value.modelConfigId === null} className="model-picker-option" onClick={() => pick(null)}>
          <span className="model-picker-mark">{value.modelConfigId === null ? <Check size={14} /> : null}</span>
          <span className="model-picker-name">跟随主模型{main ? `（${main.provider.name} · ${main.model.modelId}）` : '（尚未应用主模型）'}</span>
        </button> : null}
        {groups.map(({ provider, models }) => <div key={provider.id} role="group" aria-label={provider.name}>
          <div className="model-picker-group">{provider.name}</div>
          {models.map((model) => <button type="button" key={model.id} role="menuitemradio" aria-checked={value.modelConfigId === model.id}
            className="model-picker-option" disabled={!usable(model)} onClick={() => pick(model.id)}>
            <span className="model-picker-mark">{value.modelConfigId === model.id ? <Check size={14} /> : null}</span>
            <span className="model-picker-name">{model.modelId}</span>
            {!usable(model) ? <small>需先补全容量</small> : <>{model.tools ? <small>工具</small> : null}{model.visionVerified ? <small>视觉</small> : null}</>}
          </button>)}
        </div>)}
        {needle && !groups.length ? <p className="model-picker-empty">没有匹配的模型</p> : null}
      </div>
      <div className="model-picker-foot"><span>{busy ? '从下一条消息开始使用' : '仅对当前会话生效'}</span>
        <button type="button" className="model-picker-link" onClick={() => { setOpen(null); onManage(); }}>管理 AI 连接 ↗</button></div>
    </div> : null}

    {open === 'effort' ? <div className="model-picker-pop is-menu" role="menu" aria-label="思考档位">
      {[null, ...efforts].map((item) => <button type="button" key={item ?? 'default'} role="menuitemradio" aria-checked={effort === item}
        className="model-picker-option" onClick={() => { onChange({ modelConfigId: value.modelConfigId, reasoningEffort: item }); setOpen(null); }}>
        <span className="model-picker-mark">{effort === item ? <Check size={14} /> : null}</span>
        <span className="model-picker-name">{item ?? '模型默认'}</span>
      </button>)}
    </div> : null}
  </div>;
}
