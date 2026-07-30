import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { existsSync, readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { EmbeddingSettingsCard } from './EmbeddingSettingsCard';
import {
  activateEmbeddingModel,
  cancelEmbeddingDownload,
  disableEmbeddingModel,
  downloadEmbeddingModel,
  formatModelSize,
  getEmbeddingCatalog,
  getRetrievalStatus,
  uninstallEmbeddingModel
} from '../lib/embeddingApi';
import type { EmbeddingCatalog, EmbeddingCatalogModel, RetrievalStatus } from '../lib/types';

// ai-config 走 ../lib/api 的 get；其余 embedding API 全部 mock。
const { aiGet } = vi.hoisted(() => ({ aiGet: vi.fn() }));

vi.mock('../lib/api', () => ({
  get: (...args: unknown[]) => aiGet(...args),
  put: vi.fn(),
  post: vi.fn(),
  del: vi.fn()
}));

vi.mock('../lib/embeddingApi', async () => {
  const actual = await vi.importActual<typeof import('../lib/embeddingApi')>('../lib/embeddingApi');
  return {
    ...actual,
    getEmbeddingCatalog: vi.fn(),
    getRetrievalStatus: vi.fn(),
    saveEmbeddingConfig: vi.fn(),
    testEmbeddingEndpoint: vi.fn(),
    downloadEmbeddingModel: vi.fn(),
    cancelEmbeddingDownload: vi.fn(),
    activateEmbeddingModel: vi.fn(),
    disableEmbeddingModel: vi.fn(),
    uninstallEmbeddingModel: vi.fn(),
    reindexEmbeddings: vi.fn(),
    retryFailedEmbeddings: vi.fn()
  };
});

// 与 backend/src/main/resources/embedding-model-catalog-v1.json 保持一致。
const bgeModel: EmbeddingCatalogModel = {
  id: 'bge-small-zh-v1.5',
  providerModelId: 'Qdrant/bge-small-zh-v1.5',
  artifactRevision: '46fbe35fd4374a00fee7de77dfddaeb6dd6a2c59',
  displayName: 'BGE Small 中文 v1.5',
  license: 'MIT',
  languages: ['zh'],
  dimensions: 512,
  inventorySizeBytes: 95332206,
  installationState: 'AVAILABLE',
  downloadError: null,
  downloadProgress: null
};

const catalog = (overrides: Partial<EmbeddingCatalogModel> = {}): EmbeddingCatalog => ({
  catalogVersion: 1,
  models: [{ ...bgeModel, ...overrides }]
});

const idleStatus: RetrievalStatus = {
  scope: 'all',
  notebookId: null,
  totalPages: 10,
  totalChunks: 100,
  indexedChunks: 100,
  staleChunks: 0,
  queuedJobs: 0,
  failedJobs: 0,
  coverage: 1,
  indexState: 'DISABLED',
  embeddingSpaceId: null,
  provider: null
};

const renderCard = () => {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <EmbeddingSettingsCard />
    </QueryClientProvider>
  );
};

describe('EmbeddingSettingsCard', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    aiGet.mockResolvedValue({
      embedding: { provider: 'disabled', endpoint: '', model: '', dimensions: 512, hasKey: false, enabled: false, consent: false }
    });
    vi.mocked(getEmbeddingCatalog).mockResolvedValue(catalog());
    vi.mocked(getRetrievalStatus).mockResolvedValue(idleStatus);
  });

  it('默认打开设置不发下载请求，推荐模型显示名称/MB/license/语言', async () => {
    renderCard();
    expect(await screen.findByText('BGE Small 中文 v1.5')).toBeInTheDocument();
    expect(screen.getByText(formatModelSize(95332206))).toBeInTheDocument(); // 90.92 MiB
    expect(screen.getByText('推荐')).toBeInTheDocument();
    expect(screen.getByText('MIT')).toBeInTheDocument();
    expect(screen.getByText('中文')).toBeInTheDocument();
    expect(screen.getByText(/512 维/)).toBeInTheDocument();
    expect(downloadEmbeddingModel).not.toHaveBeenCalled();
  });

  it('AVAILABLE 状态显示「下载并启用」，点击以 activateAfterDownload=true 调用下载', async () => {
    vi.mocked(downloadEmbeddingModel).mockResolvedValue({ jobId: 'j1', state: 'DOWNLOADING' });
    renderCard();
    fireEvent.click(await screen.findByText('下载并启用'));
    await waitFor(() => expect(downloadEmbeddingModel).toHaveBeenCalledWith('bge-small-zh-v1.5', true));
  });

  it('DOWNLOADING 状态显示取消按钮，点击以 jobId 调用取消 API', async () => {
    vi.mocked(getEmbeddingCatalog).mockResolvedValue(catalog({
      installationState: 'DOWNLOADING',
      downloadProgress: { jobId: 'job-9', totalBytes: 95332206, files: { 'model_optimized.onnx': 47000000 } }
    }));
    renderCard();
    fireEvent.click(await screen.findByText('取消'));
    await waitFor(() => expect(cancelEmbeddingDownload).toHaveBeenCalledWith('job-9'));
  });

  it('VERIFYING / UNINSTALLING 仅显示禁用占位按钮，无下载按钮', async () => {
    vi.mocked(getEmbeddingCatalog).mockResolvedValue(catalog({ installationState: 'VERIFYING' }));
    const { unmount } = renderCard();
    expect(await screen.findByText('校验中…')).toBeInTheDocument();
    expect(screen.queryByText('下载并启用')).not.toBeInTheDocument();
    unmount();

    vi.mocked(getEmbeddingCatalog).mockResolvedValue(catalog({ installationState: 'UNINSTALLING' }));
    renderCard();
    expect(await screen.findByText('卸载中…')).toBeInTheDocument();
    expect(screen.queryByText('下载并启用')).not.toBeInTheDocument();
  });

  it('READY 状态显示启用/停用/彻底卸载，卸载需二次确认且文案为全删语义', async () => {
    vi.mocked(getEmbeddingCatalog).mockResolvedValue(catalog({ installationState: 'READY' }));
    vi.mocked(activateEmbeddingModel).mockResolvedValue({ embeddingSpaceId: 's', reindexJobId: 'r', state: 'REBUILDING' });
    vi.mocked(disableEmbeddingModel).mockResolvedValue({ state: 'READY' });
    vi.mocked(uninstallEmbeddingModel).mockResolvedValue({ jobId: 'u', state: 'UNINSTALLING' });
    renderCard();

    fireEvent.click(await screen.findByText('启用'));
    await waitFor(() => expect(activateEmbeddingModel).toHaveBeenCalledWith('bge-small-zh-v1.5'));

    fireEvent.click(screen.getByText('停用'));
    await waitFor(() => expect(disableEmbeddingModel).toHaveBeenCalledWith('bge-small-zh-v1.5'));

    fireEvent.click(screen.getByText('彻底卸载'));
    // 弹窗文案明确「删除模型文件与全部向量（保留 BM25 关键词索引）」
    expect(await screen.findByText(/删除模型文件与全部向量/)).toBeInTheDocument();
    fireEvent.click(screen.getByText('确定'));
    await waitFor(() => expect(uninstallEmbeddingModel).toHaveBeenCalledWith('bge-small-zh-v1.5'));
  });

  it('远程授权勾选后，更改 endpoint 使确认自动失效', async () => {
    aiGet.mockResolvedValue({
      embedding: { provider: 'openai', endpoint: 'https://api.example.com/v1', model: 'text-embedding-3-small', dimensions: 512, hasKey: false, enabled: false, consent: false }
    });
    renderCard();
    const checkbox = await screen.findByRole('checkbox');
    fireEvent.click(checkbox);
    await waitFor(() => expect(screen.getByRole('checkbox')).toBeChecked());

    fireEvent.change(screen.getByPlaceholderText(/api\.example\.com/), { target: { value: 'https://other.example.com/v1' } });
    await waitFor(() => expect(screen.getByRole('checkbox')).not.toBeChecked());
  });

  it('模型条目使用主题变量样式（无硬编码 white/black），窄屏可 wrap', async () => {
    const { container } = renderCard();
    expect(await screen.findByText('BGE Small 中文 v1.5')).toBeInTheDocument();
    expect(container.querySelector('.embedding-model-item')).not.toBeNull();
    expect(container.querySelector('.embedding-model-main')).not.toBeNull();

    const cssPath = existsSync(resolve(process.cwd(), 'src/styles/app.css'))
      ? resolve(process.cwd(), 'src/styles/app.css')
      : resolve(process.cwd(), 'frontend/src/styles/app.css');
    const css = readFileSync(cssPath, 'utf8');
    const block = css.slice(css.indexOf('.embedding-model-item'), css.indexOf('/* AI floating assistant */'));
    expect(block).toContain('var(--line)');
    expect(block).toContain('var(--panel-bg)');
    expect(block).toContain('flex-wrap: wrap');
    expect(block.toLowerCase()).not.toMatch(/\bwhite\b/);
    expect(block.toLowerCase()).not.toMatch(/\bblack\b/);
  });
});
