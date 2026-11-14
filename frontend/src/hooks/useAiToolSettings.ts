import { useQuery, useQueryClient } from '@tanstack/react-query';
import { getToolSettings } from '../lib/aiToolSettings';
import type { AiToolSettingsView } from '../lib/aiToolSettings';
import { getWebSettings } from '../lib/webSearchSettings';
import type { WebSearchSettingsView } from '../lib/webSearchSettings';

/** A GET started before a save cannot roll back a confirmed newer revision. */
export function useAiToolSettings(enabled = true) {
  const client = useQueryClient();
  return useQuery({ queryKey: ['ai-tool-settings'], enabled, queryFn: async () => {
    const fresh = await getToolSettings();
    const current = client.getQueryData<AiToolSettingsView>(['ai-tool-settings']);
    return current && current.revision > fresh.revision ? current : fresh;
  } });
}
export function useWebSearchSettings(enabled = true) {
  const client = useQueryClient();
  return useQuery({ queryKey: ['ai-web-settings'], enabled, queryFn: async () => {
    const fresh = await getWebSettings();
    const current = client.getQueryData<WebSearchSettingsView>(['ai-web-settings']);
    return current && current.revision > fresh.revision ? current : fresh;
  } });
}
