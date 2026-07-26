import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { AiSummaryModal } from './AiSummaryModal';

const renderWithClient = (ui: React.ReactElement) => {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(<QueryClientProvider client={queryClient}>{ui}</QueryClientProvider>);
};

const baseProps = {
  visible: true,
  bankId: 1,
  onPickFile: vi.fn(),
  onClose: vi.fn(),
  activeTask: null as null | 'import' | 'summarize' | 'resummarize',
  onRunImport: vi.fn(),
  onRunSummarize: vi.fn(),
  onRunResummarize: vi.fn(),
};

describe('AiSummaryModal', () => {
  beforeEach(() => vi.clearAllMocks());

  it('renders three action cards when visible', () => {
    renderWithClient(<AiSummaryModal {...baseProps} bankHasSummary={false} bankHasContent={false} />);
    expect(screen.getByText('总结并导入')).toBeInTheDocument();
    expect(screen.getByText('总结当前知识库')).toBeInTheDocument();
    expect(screen.getByText('重新总结')).toBeInTheDocument();
  });

  it('disables 重新总结 button when bankHasSummary=false', () => {
    renderWithClient(<AiSummaryModal {...baseProps} bankHasSummary={false} bankHasContent={false} />);
    const resummarizeBtn = screen.getByText('重新总结').closest('button');
    expect(resummarizeBtn).toBeDisabled();
  });

  it('calls onRunImport when 总结并导入 clicked', async () => {
    const onPickFile = vi.fn().mockResolvedValue('原文内容');
    const onRunImport = vi.fn();
    renderWithClient(<AiSummaryModal {...baseProps} onPickFile={onPickFile} onRunImport={onRunImport} bankHasSummary={false} bankHasContent={true} />);
    fireEvent.click(screen.getByText('总结并导入'));
    // Popconfirm opened — click 确定 to confirm
    const confirmBtn = await screen.findByText('确定');
    fireEvent.click(confirmBtn);
    await waitFor(() => expect(onPickFile).toHaveBeenCalled());
    await waitFor(() => expect(onRunImport).toHaveBeenCalledWith('原文内容'));
  });

  it('calls onRunSummarize when 总结当前知识库 clicked and not yet summarized', async () => {
    const onRunSummarize = vi.fn();
    renderWithClient(<AiSummaryModal {...baseProps} onRunSummarize={onRunSummarize} bankHasSummary={false} bankHasContent={true} />);
    fireEvent.click(screen.getByText('总结当前知识库'));
    await waitFor(() => expect(onRunSummarize).toHaveBeenCalled());
  });

  it('calls onRunResummarize when 总结当前知识库 clicked and already summarized', async () => {
    const onRunResummarize = vi.fn();
    renderWithClient(<AiSummaryModal {...baseProps} onRunResummarize={onRunResummarize} bankHasSummary={true} bankHasContent={true} />);
    fireEvent.click(screen.getByText('总结当前知识库'));
    // Popconfirm opened — click 确定 to confirm
    const confirmBtn = await screen.findByText('确定');
    fireEvent.click(confirmBtn);
    await waitFor(() => expect(onRunResummarize).toHaveBeenCalled());
  });

  it('shows loading text on active button and disables others', () => {
    renderWithClient(<AiSummaryModal {...baseProps} activeTask="summarize" bankHasSummary={true} bankHasContent={true} />);
    // 被点中的按钮文案变「正在总结…」
    expect(screen.getByText('正在总结…')).toBeInTheDocument();
    // 其他两个按钮禁用
    expect(screen.getByText('总结并导入').closest('button')).toBeDisabled();
    expect(screen.getByText('重新总结').closest('button')).toBeDisabled();
    // 取消按钮变文案 + 禁用（用 getByRole 拿真正的 button 元素，arco Button 内部文案 span 不直接挂 disabled）
    expect(screen.getByRole('button', { name: '后台继续运行' })).toBeDisabled();
  });
});
