import { get, put } from './api';

export interface AiRuntimeSettings {
  maxToolStages: number;
  maxToolExecutions: number;
  turnTimeoutMinutes: number;
  requestTimeoutMinutes: number;
  maxOutputTokens: number;
  requestOutputTokens: number;
  finalOutputTokens: number;
  repeatedToolLimit: number;
}
export interface AiRuntimeSettingsView {
  revision: number;
  values: AiRuntimeSettings;
  defaults: AiRuntimeSettings;
}
export const runtimeFields: Array<{ key: keyof AiRuntimeSettings; label: string; description: string; min: number; max: number; unit?: string }> = [
  { key: 'maxToolStages', label: '工具阶段上限', description: '模型发出一批工具调用计为一个阶段，最终回答另留一次请求。', min: 1, max: 500, unit: '轮' },
  { key: 'maxToolExecutions', label: '工具执行上限', description: '每个工具计一次，读取与写入共用此额度。', min: 1, max: 1000, unit: '次' },
  { key: 'turnTimeoutMinutes', label: '整轮最长时间', description: '包含模型思考、工具执行和最终回答。', min: 1, max: 240, unit: '分钟' },
  { key: 'requestTimeoutMinutes', label: '单次模型请求最长时间', description: '一次模型请求的等待上限，同时受整轮剩余时间约束。', min: 1, max: 120, unit: '分钟' },
  { key: 'maxOutputTokens', label: '整轮输出 Token 上限', description: '累计实际输出与厂商计入的思考量；用量未知时按分配额度保守计算。', min: 1024, max: 2000000 },
  { key: 'requestOutputTokens', label: '单次请求输出上限', description: '每次模型请求最多输出多少 Token，模型容量仍会收紧此值。', min: 256, max: 131072 },
  { key: 'finalOutputTokens', label: '最终回答预留', description: '从整轮额度中留出给最终回答，须小于整轮输出上限。', min: 256, max: 65536 },
  { key: 'repeatedToolLimit', label: '相同调用无新结果时停止', description: '相同参数连续返回相同结果时停止，设为 0 关闭此检查。', min: 0, max: 20, unit: '次' }
];

export function validateRuntimeSettings(values: AiRuntimeSettings): Partial<Record<keyof AiRuntimeSettings, string>> {
  const errors: Partial<Record<keyof AiRuntimeSettings, string>> = {};
  for (const field of runtimeFields) {
    if (!Number.isInteger(values[field.key]) || values[field.key] < field.min || values[field.key] > field.max) {
      errors[field.key] = `请输入 ${field.min}–${field.max} 之间的整数`;
    }
  }
  if (values.requestTimeoutMinutes > values.turnTimeoutMinutes) errors.requestTimeoutMinutes = '不能超过整轮最长时间';
  if (values.finalOutputTokens >= values.maxOutputTokens) errors.finalOutputTokens = '须小于整轮输出 Token 上限';
  return errors;
}
export const getRuntimeSettings = () => get<AiRuntimeSettingsView>('/api/ai/runtime-settings');
export const saveRuntimeSettings = (revision: number, values: AiRuntimeSettings) =>
  put<AiRuntimeSettingsView>('/api/ai/runtime-settings', { revision, values });
