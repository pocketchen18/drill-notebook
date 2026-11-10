import { useRef, useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { Button, Switch } from '@arco-design/web-react';
import { RotateCcw } from 'lucide-react';
import { deletionTools, getToolSettings, saveToolSettings } from '../lib/aiToolSettings';
import type { DeletionToolName } from '../lib/aiToolSettings';
import './ai-tool-settings.css';

/** 即点即存；只有服务端确认后才显示新状态，冲突重新读取后由用户再次选择。 */
export function AiToolSettingsPanel(): JSX.Element {
  const client = useQueryClient();
  const query = useQuery({ queryKey: ['ai-tool-settings'], queryFn: getToolSettings });
  const saving = useRef(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const save = async (enabled: Partial<Record<DeletionToolName, boolean>>) => {
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
  return <section className="panel ai-tool-settings" aria-label="工具设置">
    <div className="panel-header"><h2>工具</h2>
      <Button type="text" size="small" icon={<RotateCcw size={14} />} disabled={busy || allDefault}
        onClick={() => void save(Object.fromEntries(deletionTools.map(tool => [tool.name, false])))}>恢复默认</Button>
    </div>
    <div className="panel-body">
      <p className="ai-tool-settings-description">删除工具默认关闭；开启后 AI 仅按你的明确要求删除，删除后无法恢复。</p>
      {query.isPending ? <p role="status">正在读取工具设置…</p> : !query.data ? <p role="alert">读取失败。<Button onClick={() => void query.refetch()}>重试</Button></p> : <>
        <h3>删除</h3>
        {deletionTools.map(tool => {
          const value = query.data?.tools.find(row => row.name === tool.name);
          return <div className="ai-tool-setting-row" key={tool.name}>
            <div><label id={`tool-${tool.name}`}>{tool.label}</label><p id={`tool-help-${tool.name}`}>{tool.description}</p></div>
            <Switch aria-labelledby={`tool-${tool.name}`} aria-describedby={`tool-help-${tool.name}`}
              checked={value?.enabled ?? false} disabled={busy || !value} onChange={enabled => void save({ [tool.name]: enabled })} />
          </div>;
        })}
        <p className="ai-tool-settings-description">开启从下一轮对话生效；关闭会阻止尚未执行的调用。</p>
      </>}
      {error && <p role="alert" className="ai-tool-settings-error">{error}</p>}
    </div>
  </section>;
}
