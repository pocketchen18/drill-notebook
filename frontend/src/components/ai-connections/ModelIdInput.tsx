import { useId, useState } from 'react';
import { AutoComplete } from '@arco-design/web-react';
import type { ModelPreset } from './types';
import { effortLabel } from './modelPresets';

interface Props {
  id: string; value: string; presets: ModelPreset[]; loading?: boolean; disabled?: boolean;
  onChange: (id: string) => void;
  onSelect: (id: string, preset: ModelPreset) => void;
}

export function ModelIdInput({ id, value, presets, loading, disabled, onChange, onSelect }: Props) {
  const listId = useId();
  const [focused, setFocused] = useState(false);
  const search = value.trim().toLowerCase();
  const candidates = presets.flatMap(preset => [...new Set([preset.id, ...preset.aliases])].map(modelId => ({ modelId, preset })))
    .filter(({ modelId, preset }) => `${modelId} ${preset.name}`.toLowerCase().includes(search));
  // 精确 ID/别名优先，键盘默认候选保持用户输入的真实上游身份。
  candidates.sort((a, b) => Number(b.modelId.toLowerCase() === search) - Number(a.modelId.toLowerCase() === search));
  const unique = candidates.filter((item, index) => candidates.findIndex(other => other.modelId === item.modelId) === index);
  return <div className="connection-model-id-input">
    <AutoComplete value={value} disabled={disabled} loading={loading} filterOption={false}
      placeholder="输入模型 ID，或选择预设" onFocus={() => setFocused(true)} onBlur={() => setFocused(false)}
      inputProps={{ id, maxLength: 240, role: 'combobox', autoComplete: 'off', 'aria-autocomplete': 'list',
        'aria-controls': listId, 'aria-expanded': focused && unique.length > 0,
        onKeyDownCapture: event => { if (event.nativeEvent.isComposing || event.keyCode === 229) event.stopPropagation(); },
        onKeyDown: event => { if (event.key === 'Escape') { event.preventDefault(); event.stopPropagation(); } }
      }}
      onChange={(modelId, option) => {
        const selected = option && unique.find(item => item.modelId === modelId);
        if (selected) onSelect(modelId, selected.preset); else onChange(modelId);
      }}
      dropdownRender={menu => <div id={listId} className="connection-model-presets" role="listbox" aria-label="预设模型">{menu}</div>}
      virtualListProps={{ height: 240, isStaticItemHeight: false }}>
      {unique.map(({ modelId, preset }) => <AutoComplete.Option key={modelId} value={modelId}>
        <div className="connection-model-preset"><strong>{modelId}</strong><small>{preset.name}{preset.reasoningEfforts.length > 0 ? ` · ${effortLabel(preset.reasoningEfforts)}` : ''}</small></div>
      </AutoComplete.Option>)}
    </AutoComplete>
    {focused && search && !loading && !unique.length && <small className="connection-hint">未找到匹配预设，可使用自定义模型 ID。</small>}
  </div>;
}
