import { describe, expect, it, vi, beforeEach } from 'vitest';
import { uploadAttachment, listAttachments, deleteAttachment, attachmentContentUrl } from './attachments';

const mockFetch = vi.fn();
vi.stubGlobal('fetch', mockFetch);
vi.stubGlobal('window', { api: { backend: { getBaseUrl: () => Promise.resolve('http://127.0.0.1:18080') } } });

beforeEach(() => { mockFetch.mockReset(); });

describe('attachments api', () => {
  it('uploadAttachment posts multipart and returns parsed record', async () => {
    mockFetch.mockResolvedValueOnce({
      ok: true,
      text: () => Promise.resolve(JSON.stringify({ id: 42, pageId: 1, fileName: 'a.png', storagePath: 'attachments/1/x.png', mimeType: 'image/png', fileSize: 100, sha256: 'abc', createdAt: '2026-07-26' }))
    });
    const result = await uploadAttachment(1, new File(['x'], 'a.png'));
    expect(result.id).toBe(42);
    const sentBody = mockFetch.mock.calls[0][1].body;
    expect(sentBody).toBeInstanceOf(FormData);
  });

  it('listAttachments calls GET endpoint', async () => {
    mockFetch.mockResolvedValueOnce({ ok: true, text: () => Promise.resolve('[]') });
    await listAttachments(1);
    expect(mockFetch).toHaveBeenCalledOnce();
  });

  it('deleteAttachment calls DELETE endpoint', async () => {
    mockFetch.mockResolvedValueOnce({ ok: true, text: () => Promise.resolve('') });
    await deleteAttachment(5);
    expect(mockFetch.mock.calls[0][1].method).toBe('DELETE');
  });

  it('attachmentContentUrl builds correct path', async () => {
    expect(await attachmentContentUrl(7)).toBe('http://127.0.0.1:18080/api/attachments/7/content');
  });
});
