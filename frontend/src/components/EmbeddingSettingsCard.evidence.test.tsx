import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { join, resolve } from 'node:path';
import { EmbeddingSettingsCard } from './EmbeddingSettingsCard';
import {
  activateEmbeddingModel,
  disableEmbeddingModel,
  getEmbeddingCatalog,
  getRetrievalStatus,
  uninstallEmbeddingModel
} from '../lib/embeddingApi';
import type { RetrievalStatus } from '../lib/types';

// 与 EmbeddingSettingsCard.test.tsx 相同的 mock 契约。
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

const evidenceDir = process.env.DRILL_EVIDENCE_DIR;
const describeEvidence = evidenceDir ? describe : describe.skip;

const bgeModel = {
  id: 'bge-small-zh-v1.5',
  providerModelId: 'Qdrant/bge-small-zh-v1.5',
  artifactRevision: '46fbe35fd4374a00fee7de77dfddaeb6dd6a2c59',
  displayName: 'BGE Small 中文 v1.5',
  license: 'MIT',
  languages: ['zh'],
  dimensions: 512,
  inventorySizeBytes: 95332206
};

function resolveFromCwd(relative: string): string {
  const candidates = [
    resolve(process.cwd(), relative),
    resolve(process.cwd(), '..', relative),
    resolve(process.cwd(), 'frontend', relative)
  ];
  const found = candidates.find((p) => existsSync(p));
  if (!found) throw new Error(`cannot resolve ${relative}; tried: ${candidates.join(', ')}`);
  return found;
}

/** 将渲染出的 body 内联真实 arco + app 主题样式，产出可截图的独立 HTML。 */
function wrapHtml(bodyInner: string, theme: 'light' | 'dark'): string {
  const arcoCss = readFileSync(resolveFromCwd(join('node_modules', '@arco-design', 'web-react', 'dist', 'css', 'arco.css')), 'utf8');
  const appCss = readFileSync(resolveFromCwd(join('src', 'styles', 'app.css')), 'utf8');
  return `<!doctype html>
<html data-theme="${theme}" lang="zh">
<head>
<meta charset="utf-8" />
<style>${arcoCss}</style>
<style>${appCss}</style>
<style>body { padding: 24px; max-width: 860px; margin: 0 auto; background: var(--page-bg); }</style>
</head>
<body>${bodyInner}</body>
</html>`;
}

const renderCard = () => {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <EmbeddingSettingsCard />
    </QueryClientProvider>
  );
};

describeEvidence('EmbeddingSettingsCard evidence (DRILL_EVIDENCE_DIR)', () => {
  it('task-13-model-ui: 本地模型 READY + 索引 ACTIVE（light）', async () => {
    aiGet.mockResolvedValue({ embedding: { provider: 'local', endpoint: '', model: '', dimensions: 512, hasKey: false, enabled: true, consent: false } });
    vi.mocked(getEmbeddingCatalog).mockResolvedValue({
      catalogVersion: 1,
      models: [{ ...bgeModel, installationState: 'READY', downloadError: null, downloadProgress: null }]
    });
    const status: RetrievalStatus = {
      scope: 'all', notebookId: null, totalPages: 12, totalChunks: 100, indexedChunks: 100,
      staleChunks: 0, queuedJobs: 0, failedJobs: 0, coverage: 1,
      indexState: 'ACTIVE', embeddingSpaceId: 'space-local', provider: 'local-rust'
    };
    vi.mocked(getRetrievalStatus).mockResolvedValue(status);
    vi.mocked(activateEmbeddingModel).mockResolvedValue({ embeddingSpaceId: 's', reindexJobId: 'r', state: 'ACTIVE' });
    vi.mocked(disableEmbeddingModel).mockResolvedValue({ state: 'READY' });
    vi.mocked(uninstallEmbeddingModel).mockResolvedValue({ jobId: 'u', state: 'UNINSTALLING' });

    renderCard();
    expect(await screen.findByText('BGE Small 中文 v1.5')).toBeInTheDocument();
    await screen.findByText('已启用');
    await screen.findByText('状态 ACTIVE');
    await waitFor(() => expect(document.querySelector('.embedding-model-item')).not.toBeNull());

    mkdirSync(evidenceDir as string, { recursive: true });
    writeFileSync(join(evidenceDir as string, '_task13-model-ui.html'), wrapHtml(document.body.innerHTML, 'light'), 'utf8');
  });

  it('task-13-consent-uninstall: 远程授权表单 + 本地模型卸载入口（dark）', async () => {
    aiGet.mockResolvedValue({
      embedding: { provider: 'openai', endpoint: 'https://api.example.com/v1', model: 'text-embedding-3-small', dimensions: 1536, hasKey: true, enabled: false, consent: false }
    });
    vi.mocked(getEmbeddingCatalog).mockResolvedValue({
      catalogVersion: 1,
      models: [{ ...bgeModel, installationState: 'READY', downloadError: null, downloadProgress: null }]
    });
    const status: RetrievalStatus = {
      scope: 'all', notebookId: null, totalPages: 12, totalChunks: 100, indexedChunks: 100,
      staleChunks: 0, queuedJobs: 0, failedJobs: 0, coverage: 1,
      indexState: 'ACTIVE', embeddingSpaceId: 'space-local', provider: 'local-rust'
    };
    vi.mocked(getRetrievalStatus).mockResolvedValue(status);
    vi.mocked(activateEmbeddingModel).mockResolvedValue({ embeddingSpaceId: 's', reindexJobId: 'r', state: 'ACTIVE' });
    vi.mocked(disableEmbeddingModel).mockResolvedValue({ state: 'READY' });
    vi.mocked(uninstallEmbeddingModel).mockResolvedValue({ jobId: 'u', state: 'UNINSTALLING' });

    renderCard();
    const checkbox = await screen.findByRole('checkbox');
    fireEvent.click(checkbox);
    await waitFor(() => expect(screen.getByRole('checkbox')).toBeChecked());
    await screen.findByText('彻底卸载');

    mkdirSync(evidenceDir as string, { recursive: true });
    writeFileSync(join(evidenceDir as string, '_task13-consent-uninstall.html'), wrapHtml(document.body.innerHTML, 'dark'), 'utf8');
  });
});
