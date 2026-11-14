import { useRef, useState } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { Button, Message, Tooltip } from '@arco-design/web-react';
import { Globe } from 'lucide-react';
import { useAiToolSettings, useWebSearchSettings } from '../../hooks/useAiToolSettings';
import { saveToolSettings } from '../../lib/aiToolSettings';
import { webModeText } from '../../lib/webSearchSettings';
import type { ConnectionModel } from '../ai-connections/types';

export function WebSearchButton({ enabled = true, model }: { enabled?: boolean; model?: ConnectionModel }): JSX.Element {
  const query = useAiToolSettings(enabled);
  const web = useWebSearchSettings(enabled);
  const client = useQueryClient();
  const saving = useRef(false);
  const [busy, setBusy] = useState(false);
  const tools = Array.isArray(query.data?.tools) ? query.data.tools : [];
  const search = tools.find(tool => tool.name === 'search_web');
  const fetch = tools.find(tool => tool.name === 'fetch_web');
  const both = !!search?.enabled && !!fetch?.enabled;
  const some = !!search?.enabled || !!fetch?.enabled;
  const mixed = some && !both;
  const state = both ? '联网已开启' : mixed ? `联网部分开启 · 仅${search?.enabled ? '搜索' : '网页读取'}` : '联网已关闭';
  const native = web.data?.preferNative ? model?.nativeWeb?.search || model?.nativeWeb?.fetch ? ' · 模型内置优先'
    : ' · 当前模型内置联网不支持，使用已选服务' : '';
  const unavailable = !search || !fetch;
  const hint = busy ? '正在保存联网设置' : unavailable ? query.isPending ? '正在读取联网设置' : '联网设置暂不可读取，请在工具设置中重试'
    : `${state}${some ? ` · ${webModeText(web.data)}${native}` : ''}${model && !model.tools ? ' · 当前模型未启用工具调用' : ''}`;
  const toggle = async (): Promise<void> => {
    if (unavailable || saving.current || !query.data) return;
    saving.current = true; setBusy(true);
    try {
      const value = await saveToolSettings(query.data.revision, { search_web: !both, fetch_web: !both });
      client.setQueryData(['ai-tool-settings'], value);
    } catch (failure) {
      Message.error(failure instanceof Error ? failure.message : '联网设置未保存');
      await query.refetch();
    } finally { saving.current = false; setBusy(false); }
  };
  return <Tooltip content={hint} trigger={['hover', 'focus']}>
    <Button type="secondary" className={`ai-compose-web${both ? ' is-on' : mixed ? ' is-mixed' : ''}`}
      icon={<Globe size={16} aria-hidden="true" />} disabled={!enabled || unavailable || busy}
      aria-label={hint} aria-pressed={mixed ? 'mixed' : both} title={hint} onClick={() => void toggle()} />
  </Tooltip>;
}
