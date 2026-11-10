import { get, put } from './api';
import type { ToolName } from './toolActivity';

export type BuiltinToolName = Exclude<ToolName, 'unknown_tool'>;
export const TOOL_DESCRIPTIONS: Record<BuiltinToolName, string> = {
  read_current_page: '读取当前页面已展示的内容和未保存的笔记草稿。',
  search_materials: '按关键词查找笔记、知识点、题库和题目。',
  read_notebook: '查看笔记本目录与笔记页正文。',
  read_knowledge: '查看知识点的当前内容、原文或总结。',
  read_question_bank: '查看题库目录与题目详情。',
  read_wrong_questions: '查看错题列表、题目和作答统计。',
  create_material: '新建学习资料，或将题目加入错题本。',
  update_material: '修改已有笔记、知识点、题库或题目。',
  search_memories: '按关键词检索已保存的长期记忆。',
  list_memories: '查看当前会话可用的记忆标题。',
  create_memory: '保存你要求记住的长期信息。',
  update_memory: '修正或补充已有的长期记忆。',
  list_sessions: '按标题查找其它会话。',
  read_session: '读取指定会话的历史消息和运行状态。',
  create_session: '按你的要求新建会话，发送首条消息并开始回复。',
  send_message: '向已有会话发送消息，正在回复时排队接续。',
  delete_memory: '删除指定的长期记忆。',
  delete_session: '删除指定的历史会话及其消息。',
  delete_material: '删除笔记本、笔记页、知识点、题库或题目。',
  remove_wrong_question: '将题目移出待巩固，保留原题和作答记录。'
};
export const toolGroups = [
  { id: 'material', label: '学习资料', description: '', tools: [
    'read_current_page', 'search_materials', 'read_notebook', 'read_knowledge',
    'read_question_bank', 'read_wrong_questions', 'create_material', 'update_material'
  ] },
  { id: 'memory', label: '记忆', description: '还需在记忆设置中启用记忆功能。', tools: [
    'search_memories', 'list_memories', 'create_memory', 'update_memory'
  ] },
  { id: 'session', label: '会话', description: '', tools: [
    'list_sessions', 'read_session', 'create_session', 'send_message'
  ] },
  { id: 'delete', label: '删除', description: '默认关闭；开启后 AI 仅按你的明确要求删除，删除后无法恢复。', tools: [
    'delete_memory', 'delete_session', 'delete_material', 'remove_wrong_question'
  ] }
] as const satisfies ReadonlyArray<{ id: string; label: string; description: string; tools: readonly BuiltinToolName[] }>;
export type ToolGroup = typeof toolGroups[number]['id'];
export interface AiToolSettingsView {
  revision: number;
  tools: Array<{ name: BuiltinToolName; group: ToolGroup; enabled: boolean; defaultEnabled: boolean }>;
}
export const getToolSettings = () => get<AiToolSettingsView>('/api/ai/tool-settings');
export const saveToolSettings = (revision: number, enabled: Partial<Record<BuiltinToolName, boolean>>) =>
  put<AiToolSettingsView>('/api/ai/tool-settings', { revision, enabled });
