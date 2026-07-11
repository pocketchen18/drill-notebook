const FALLBACK_BASE_URL = 'http://127.0.0.1:18080';

let baseUrlPromise: Promise<string> | undefined;

async function baseUrl(): Promise<string> {
  if (!baseUrlPromise) {
    baseUrlPromise = window.api?.backend.getBaseUrl().catch(() => FALLBACK_BASE_URL) ?? Promise.resolve(FALLBACK_BASE_URL);
  }
  return baseUrlPromise;
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
    const message = typeof payload === 'object' && payload && 'message' in payload ? String(payload.message) : `请求失败（${response.status}）`;
    throw new Error(message);
  }
  return payload as T;
}

export const get = <T>(path: string) => apiRequest<T>(path);
export const post = <T>(path: string, body: unknown) => apiRequest<T>(path, { method: 'POST', body: JSON.stringify(body) });
export const put = <T>(path: string, body: unknown) => apiRequest<T>(path, { method: 'PUT', body: JSON.stringify(body) });
export const del = <T>(path: string) => apiRequest<T>(path, { method: 'DELETE' });
