import { get, put } from './api';

export const deletionTools = [
  { name: 'delete_memory', label: '删除记忆', description: '删除指定的长期记忆。' },
  { name: 'delete_session', label: '删除会话', description: '删除指定的历史会话及其消息。' },
  { name: 'delete_material', label: '删除学习资料', description: '删除笔记本、笔记页、知识点、题库或题目。' },
  { name: 'remove_wrong_question', label: '移出错题本', description: '将题目移出待巩固，保留原题和作答记录。' }
] as const;
export type DeletionToolName = typeof deletionTools[number]['name'];
export interface AiToolSettingsView {
  revision: number;
  tools: Array<{ name: DeletionToolName; group: 'delete'; enabled: boolean; defaultEnabled: boolean }>;
}
export const getToolSettings = () => get<AiToolSettingsView>('/api/ai/tool-settings');
export const saveToolSettings = (revision: number, enabled: Partial<Record<DeletionToolName, boolean>>) =>
  put<AiToolSettingsView>('/api/ai/tool-settings', { revision, enabled });
