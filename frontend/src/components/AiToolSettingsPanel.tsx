import { useRef, useState } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { Button, Switch } from '@arco-design/web-react';
import { RotateCcw } from 'lucide-react';
import { toolGroups, TOOL_DESCRIPTIONS, saveToolSettings } from '../lib/aiToolSettings';
import { useAiToolSettings } from '../hooks/useAiToolSettings';
import { WebSearchSettingsPanel } from './WebSearchSettingsPanel';
import type { BuiltinToolName } from '../lib/aiToolSettings';
import { TOOL_NAMES } from '../lib/toolActivity';
import { pageContextRows, usePageContextSection } from './PageContextProvider';
import './ai-tool-settings.css';

/** 即点即存；只有服务端确认后才显示新状态，冲突重新读取后由用户再次选择。 */
export function AiToolSettingsPanel(): JSX.Element {
  const client = useQueryClient();
  const query = useAiToolSettings();
  const saving = useRef(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const save = async (enabled: Partial<Record<BuiltinToolName, boolean>>) => {
    if (!query.data || saving.current) return;
    saving.current = true; setBusy(true); setError('');
    try {
      const saved = await saveToolSettings(query.data.revision, enabled);
      client.setQueryData(['ai-tool-settings'], saved);
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : '设置未保存，请重试');
      await query.refetch();
    } finally { saving.current = false; setBusy(false); }
  };
  const allDefault = query.data?.tools.every(tool => tool.enabled === tool.defaultEnabled) ?? true;
  usePageContextSection('tools', '## 工具设置\n选择 AI 可以使用的内置工具，对所有模型和会话生效。\n' + (
    query.data ? toolGroups.map(group => pageContextRows(group.label, group.tools.map(name => [
      TOOL_NAMES[name], `${query.data!.tools.find(tool => tool.name === name)?.enabled ? '开启' : '关闭'}；${TOOL_DESCRIPTIONS[name]}`
    ]))).join('\n\n') : query.isPending ? '正在读取工具设置…' : '工具设置读取失败'
  ));
  return <section className="panel ai-tool-settings" aria-label="工具设置">
    <div className="panel-header"><h2>工具</h2>
      <Button type="text" size="small" icon={<RotateCcw size={14} />} disabled={busy || allDefault}
        onClick={() => void save(Object.fromEntries((query.data?.tools ?? []).map(tool => [tool.name, tool.defaultEnabled])))}>恢复默认</Button>
    </div>
    <div className="panel-body">
      <p className="ai-tool-settings-description">选择 AI 可以使用的内置工具，对所有模型和会话生效。模型需支持并开启工具调用。</p>
      {query.isPending ? <p role="status">正在读取工具设置…</p> : !query.data ? <p role="alert">读取失败。<Button onClick={() => void query.refetch()}>重试</Button></p> : <>
        {toolGroups.map(group => <section key={group.id} className="ai-tool-settings-group" aria-labelledby={`tool-group-${group.id}`}>
          <h3 id={`tool-group-${group.id}`}>{group.label}</h3>
          {group.description && <p className="ai-tool-settings-group-description">{group.description}</p>}
          {group.tools.map(name => {
            const value = query.data?.tools.find(row => row.name === name);
            return <div className="ai-tool-setting-row" key={name}>
              <div><label id={`tool-${name}`}>{TOOL_NAMES[name]}</label><p id={`tool-help-${name}`}>{TOOL_DESCRIPTIONS[name]}</p></div>
              <Switch aria-labelledby={`tool-${name}`} aria-describedby={`tool-help-${name}`}
                checked={value?.enabled ?? false} disabled={busy || !value} onChange={enabled => void save({ [name]: enabled })} />
            </div>;
          })}
          {group.id === 'web' && <WebSearchSettingsPanel />}
        </section>)}
        <p className="ai-tool-settings-description">开启从下一轮对话生效；关闭会阻止尚未执行的调用。</p>
      </>}
      {error && <p role="alert" className="ai-tool-settings-error">{error}</p>}
    </div>
  </section>;
}
