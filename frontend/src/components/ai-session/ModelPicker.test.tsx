import { describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, within } from '@testing-library/react';
import { ModelPicker } from './ModelPicker';
import type { Connections } from '../ai-connections/types';

const model = (id: string, modelId: string, extra: Record<string, unknown> = {}) => ({ id, modelId, name: modelId, group: '',
  streaming: true, tools: true, contextWindow: 128000, maxOutputTokens: 8000, reasoningControl: 'effort' as const, reasoningEfforts: [], ...extra });
const connections: Connections = { revision: 3, chatModelId: 'm-chat', importModelId: null, providers: [
  { id: 'p1', name: 'DeepSeek', apiFormat: 'chat_completions', endpoint: 'https://api.deepseek.com', authMode: 'api_key', hasKey: true, needsPassword: false,
    models: [model('m-chat', 'deepseek-chat'), model('m-deep', 'deepseek-reasoner', { reasoningEfforts: ['low', 'high'] })] },
  { id: 'p2', name: '本地 Ollama', apiFormat: 'chat_completions', endpoint: 'http://127.0.0.1:11434', authMode: 'none', hasKey: false, needsPassword: false,
    models: [model('m-local', 'qwen3:14b', { contextWindow: null, tools: false })] }
] };
function mount(value = { modelConfigId: 'm-chat' as string | null, reasoningEffort: null as string | null }, busy = false) {
  const onChange = vi.fn(); const onManage = vi.fn();
  render(<ModelPicker connections={connections} value={value} busy={busy} onChange={onChange} onManage={onManage} />);
  return { onChange, onManage };
}

describe('助手全局模型与思考档位选择', () => {
  it('直接回显已保存的模型；可查找其他供应商的模型，缺容量的模型不可选', () => {
    const { onChange, onManage } = mount();
    const trigger = screen.getByRole('button', { name: '模型：deepseek-chat' });
    expect(screen.queryByRole('button', { name: /思考档位/ })).toBeNull();
    fireEvent.click(trigger);
    const dialog = screen.getByRole('dialog', { name: '选择模型' });
    expect(within(dialog).getByRole('menuitemradio', { name: /^deepseek-chat/ })).toHaveAttribute('aria-checked', 'true');
    expect(within(dialog).queryByText(/跟随主模型/)).toBeNull();
    expect(within(dialog).getByRole('menuitemradio', { name: /qwen3:14b/ })).toBeDisabled();
    expect(within(dialog).getByText('需先补全容量')).toBeInTheDocument();
    expect(within(dialog).getByText('跨会话自动保存')).toBeInTheDocument();
    fireEvent.change(within(dialog).getByLabelText('查找模型'), { target: { value: 'reason' } });
    expect(within(dialog).queryByRole('menuitemradio', { name: /跟随主模型/ })).toBeNull();
    expect(within(dialog).getAllByRole('menuitemradio')).toHaveLength(1);
    fireEvent.change(within(dialog).getByLabelText('查找模型'), { target: { value: 'ollama' } });
    expect(within(dialog).getByRole('menuitemradio', { name: /qwen3:14b/ })).toBeInTheDocument();
    fireEvent.change(within(dialog).getByLabelText('查找模型'), { target: { value: '不存在' } });
    expect(within(dialog).getByText('没有匹配的模型')).toBeInTheDocument();
    fireEvent.change(within(dialog).getByLabelText('查找模型'), { target: { value: 'reasoner' } });
    fireEvent.click(within(dialog).getByRole('menuitemradio', { name: /deepseek-reasoner/ }));
    expect(onChange).toHaveBeenCalledWith({ modelConfigId: 'm-deep', reasoningEffort: null });
    expect(screen.queryByRole('dialog')).toBeNull();
    fireEvent.click(trigger);
    fireEvent.click(screen.getByRole('button', { name: '管理 AI 连接 ↗' }));
    expect(onManage).toHaveBeenCalledTimes(1);
  });

  it('只提供模型声明支持的档位；换到不支持的模型时回到模型默认', () => {
    const { onChange } = mount({ modelConfigId: 'm-deep', reasoningEffort: 'high' }, true);
    fireEvent.click(screen.getByRole('button', { name: '思考档位：high' }));
    const menu = screen.getByRole('menu', { name: '思考档位' });
    expect(within(menu).getAllByRole('menuitemradio').map((item) => item.textContent)).toEqual(['模型默认', 'low', 'high']);
    fireEvent.click(within(menu).getByRole('menuitemradio', { name: 'low' }));
    expect(onChange).toHaveBeenLastCalledWith({ modelConfigId: 'm-deep', reasoningEffort: 'low' });
    fireEvent.click(screen.getByRole('button', { name: '模型：deepseek-reasoner' }));
    expect(screen.getByText('从下一条消息开始使用')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('menuitemradio', { name: /^deepseek-chat/ }));
    expect(onChange).toHaveBeenLastCalledWith({ modelConfigId: 'm-chat', reasoningEffort: null });
  });

  it('已保存的模型被删除后明确提示，Esc 关闭面板并把焦点还给触发按钮', () => {
    mount({ modelConfigId: 'm-gone', reasoningEffort: null });
    const trigger = screen.getByRole('button', { name: '模型：模型已删除' });
    fireEvent.click(trigger);
    fireEvent.keyDown(document, { key: 'Escape' });
    expect(screen.queryByRole('dialog')).toBeNull();
    expect(trigger).toHaveFocus();
  });

  it('未选择时只展示实际模型，不再提供跟随主模型入口', () => {
    mount({ modelConfigId: null, reasoningEffort: null });
    fireEvent.click(screen.getByRole('button', { name: '模型：选择模型' }));
    expect(screen.getAllByRole('menuitemradio')).toHaveLength(3);
    expect(screen.getAllByRole('menuitemradio').every(item => item.getAttribute('aria-checked') === 'false')).toBe(true);
    expect(screen.queryByText(/跟随主模型/)).toBeNull();
  });
});
