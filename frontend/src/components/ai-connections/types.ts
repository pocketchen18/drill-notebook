export interface ConnectionModel {
  id: string;
  modelId: string;
  name: string;
  group: string;
  streaming: boolean;
  tools: boolean;
  contextWindow?: number | null;
  maxInputTokens?: number | null;
  maxOutputTokens?: number | null;
  reasoningControl?: ReasoningControl;
  reasoningEfforts?: string[];
  defaultReasoningEffort?: string | null;
  thinkingBudgetTokens?: number | null;
  preset?: ModelPreset | null;
  presetVersion?: string | null;
  visionVerified?: boolean;
  verification?: ModelVerification;
}
export interface VerificationCheck { status: 'passed' | 'failed' | 'skipped' | 'cancelled'; message: string; elapsedMs: number }
export interface ModelVerification { text: VerificationCheck; image: VerificationCheck; tools: VerificationCheck; checkedAt: string }
export interface VerificationResponse { added: boolean; existing?: boolean; cancelled?: boolean; modelId?: string; verification?: ModelVerification; connections?: Connections }
export type VerifyModel = (model: Record<string, unknown>, password: string, requestId: string, signal: AbortSignal) => Promise<VerificationResponse>;
export type ReasoningControl = 'effort' | 'budget' | 'none' | 'fixed' | 'unknown';
export interface ModelPreset {
  id: string; name: string; vendor: string; apiFormat: string; aliases: string[];
  contextWindow: number | null; maxOutputTokens: number | null;
  reasoningControl: ReasoningControl; reasoningEfforts: string[];
}
export interface ConnectionProvider {
  id: string;
  connectionVersion?: string;
  name: string;
  apiFormat: 'chat_completions' | 'anthropic';
  endpoint: string;
  authMode: 'api_key' | 'none';
  hasKey: boolean;
  needsPassword: boolean;
  models: ConnectionModel[];
}
export interface Connections {
  revision: number;
  providers: ConnectionProvider[];
  chatModelId: string | null;
  importModelId: string | null;
}
export interface EditorHandle {
  dirty: boolean;
  save: () => Promise<boolean>;
}
export type SaveConnection = (path: string, method: string, body: object) => Promise<boolean>;
export const modelName = (model: ConnectionModel) => model.name || model.modelId || '待补全模型';
export const formatName = (provider: ConnectionProvider) => provider.apiFormat === 'anthropic' ? 'Anthropic Messages' : 'OpenAI Chat Completions';
export const incomplete = (provider: ConnectionProvider, model?: ConnectionModel) => !provider.endpoint ||
  (provider.authMode === 'api_key' && !provider.hasKey) || (model !== undefined && !model.modelId);
export function defaultTools(provider: ConnectionProvider, id: string): boolean {
  try {
    const host = new URL(provider.endpoint).hostname;
    return ['api.openai.com', 'api.deepseek.com'].includes(host) ||
      (host === 'api.anthropic.com' && provider.apiFormat === 'anthropic' && id.toLowerCase().startsWith('claude'));
  } catch { return false; }
}
