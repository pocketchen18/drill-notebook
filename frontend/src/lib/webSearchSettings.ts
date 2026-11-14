import { get, post, put } from './api';
import { publish } from './windowBus';
import type { WebProvider, WebRouteStep } from './toolActivity';

export type WebService = 'auto' | 'tavily' | 'tinyfish';
export type CustomWebService = Exclude<WebService, 'auto'>;
export interface WebSearchSettingsView {
  revision: number;
  searchProvider: WebService;
  fetchProvider: WebService;
  preferNative: boolean;
  providers: Record<CustomWebService, { hasKey: boolean }>;
}
export interface WebSettingsEdit {
  searchProvider?: WebService;
  fetchProvider?: WebService;
  preferNative?: boolean;
  keys?: Partial<Record<CustomWebService, { apiKey?: string; clear?: boolean }>>;
}
export interface WebConnectionCheck {
  capability: 'search' | 'fetch'; status: string; count: number; webProvider?: WebProvider; webRoute: WebRouteStep[];
}
export const WEB_SERVICE_NAMES: Record<WebService, string> = { auto: '内置自动', tavily: 'Tavily', tinyfish: 'TinyFish' };
export const getWebSettings = () => get<WebSearchSettingsView>('/api/ai/web-settings');
export async function saveWebSettings(revision: number, changes: WebSettingsEdit): Promise<WebSearchSettingsView> {
  const saved = await put<WebSearchSettingsView>('/api/ai/web-settings', { revision, ...changes });
  publish({ type: 'invalidate', keys: [['ai-web-settings']] });
  return saved;
}
export const testWebSettings = (changes: WebSettingsEdit) => post<{ checks: WebConnectionCheck[] }>('/api/ai/web-settings/test', changes);

export function webModeText(value?: WebSearchSettingsView): string {
  if (!value) return '服务配置读取中';
  return value.searchProvider === value.fetchProvider ? WEB_SERVICE_NAMES[value.searchProvider]
    : `搜索 ${WEB_SERVICE_NAMES[value.searchProvider]} · 读取 ${WEB_SERVICE_NAMES[value.fetchProvider]}`;
}
