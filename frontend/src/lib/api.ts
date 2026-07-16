const FALLBACK_BASE_URL = 'http://127.0.0.1:18080';

let baseUrlPromise: Promise<string> | undefined;

async function baseUrl(): Promise<string> {
  if (!baseUrlPromise) {
    baseUrlPromise = window.api?.backend.getBaseUrl().catch(() => FALLBACK_BASE_URL) ?? Promise.resolve(FALLBACK_BASE_URL);
  }
  return baseUrlPromise;
}

/**
 * API 错误：对外暴露稳定的 code 与面向用户的 message；
 * 原始堆栈/details 仅在 console 中记录，不展示给用户。
 */
export class ApiError extends Error {
  readonly code: string;
  readonly status: number;

  constructor(code: string, message: string, status: number) {
    super(message);
    this.name = 'ApiError';
    this.code = code;
    this.status = status;
  }
}

function extractCode(payload: unknown): string {
  if (payload && typeof payload === 'object' && 'errorCode' in payload) {
    return String((payload as { errorCode: unknown }).errorCode);
  }
  if (payload && typeof payload === 'object' && 'error' in payload) {
    return String((payload as { error: unknown }).error);
  }
  return 'unknown';
}

function extractMessage(payload: unknown, status: number): string {
  if (payload && typeof payload === 'object' && 'message' in payload) {
    const value = (payload as { message: unknown }).message;
    if (typeof value === 'string' && value.trim()) return value.trim();
  }
  return `请求失败（${status}）`;
}

export async function apiRequest<T>(path: string, init: RequestInit = {}): Promise<T> {
  const response = await fetch(`${await baseUrl()}${path}`, {
    ...init,
    headers: {
      Accept: 'application/json',
      ...(init.body ? { 'Content-Type': 'application/json' } : {}),
      ...init.headers
    }
  });
  const raw = await response.text();
  let payload: unknown = undefined;
  try {
    payload = raw ? JSON.parse(raw) : undefined;
  } catch {
    payload = raw;
  }
  if (!response.ok) {
    const code = extractCode(payload);
    const message = extractMessage(payload, response.status);
    // 内部日志：保留 code/message/status，便于排查
    console.warn('[api] request failed', { path, code, status, message });
    throw new ApiError(code, message, response.status);
  }
  return payload as T;
}

export const get = <T>(path: string) => apiRequest<T>(path);
export const post = <T>(path: string, body: unknown) => apiRequest<T>(path, { method: 'POST', body: JSON.stringify(body) });
export const put = <T>(path: string, body: unknown) => apiRequest<T>(path, { method: 'PUT', body: JSON.stringify(body) });
export const del = <T>(path: string) => apiRequest<T>(path, { method: 'DELETE' });
