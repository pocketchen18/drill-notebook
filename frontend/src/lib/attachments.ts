import { get, del } from './api';
import type { NoteAttachment } from './types';

export interface ArchiveEntry {
  name: string;
  size: number;
  dir: boolean;
}

export interface PptxSlide {
  paragraphs: string[];
  images: string[];
}

async function baseUrl(): Promise<string> {
  return window.api?.backend.getBaseUrl().catch(() => 'http://127.0.0.1:18080') ?? 'http://127.0.0.1:18080';
}

export async function uploadAttachment(pageId: number, file: File): Promise<NoteAttachment> {
  const formData = new FormData();
  formData.append('file', file);
  const response = await fetch(`${await baseUrl()}/api/note-pages/${pageId}/attachments`, {
    method: 'POST',
    body: formData
  });
  const raw = await response.text();
  if (!response.ok) throw new Error(`上传失败 (${response.status}): ${raw}`);
  return JSON.parse(raw) as NoteAttachment;
}

export async function listAttachments(pageId: number): Promise<NoteAttachment[]> {
  return get<NoteAttachment[]>(`/api/note-pages/${pageId}/attachments`);
}

export async function deleteAttachment(id: number): Promise<void> {
  await del<void>(`/api/attachments/${id}`);
}

export async function attachmentContentUrl(id: number): Promise<string> {
  return `${await baseUrl()}/api/attachments/${id}/content`;
}

export async function listArchiveEntries(id: number): Promise<ArchiveEntry[]> {
  return get<ArchiveEntry[]>(`/api/attachments/${id}/entries`);
}

export async function listPptxSlides(id: number): Promise<PptxSlide[]> {
  return get<PptxSlide[]>(`/api/attachments/${id}/slides`);
}

export async function pptxMediaUrl(id: number, mediaPath: string): Promise<string> {
  return `${await baseUrl()}/api/attachments/${id}/media?path=${encodeURIComponent(mediaPath)}`;
}
