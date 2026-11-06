import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { AiModelSlotCard } from './AiModelSlotCard';

const { aiGet, aiPut } = vi.hoisted(() => ({ aiGet: vi.fn(), aiPut: vi.fn() }));

vi.mock('../lib/api', () => ({
  get: (...args: unknown[]) => aiGet(...args),
  put: (...args: unknown[]) => aiPut(...args),
  post: vi.fn()
}));

function renderCard(purpose: 'chat' | 'import') {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <AiModelSlotCard purpose={purpose} title="模型" description="测试模型配置" />
    </QueryClientProvider>
  );
}

describe('AiModelSlotCard tools setting', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    aiGet.mockResolvedValue({
      chat: { provider: 'custom', endpoint: 'https://api.example.com/v1', model: 'chat-model', hasKey: false, tools: false },
      import: { provider: 'custom', endpoint: 'https://import.example.com/v1', model: 'import-model', hasKey: false }
    });
    aiPut.mockResolvedValue({});
  });

  it('saves enabled tools only for the chat slot', async () => {
    renderCard('chat');

    await screen.findByDisplayValue('https://api.example.com/v1');
    const toolsSwitch = await screen.findByRole('switch', { name: '工具调用' });
    fireEvent.click(toolsSwitch);
    fireEvent.click(screen.getByRole('button', { name: '保存' }));

    await waitFor(() => expect(aiPut).toHaveBeenCalledWith(
      '/api/ai/config',
      expect.objectContaining({ purpose: 'chat', tools: true })
    ));
  });

  it('does not expose tools for the import slot', async () => {
    renderCard('import');

    await screen.findByText('流式传输');
    expect(screen.queryByRole('switch', { name: '工具调用' })).not.toBeInTheDocument();
  });
});
