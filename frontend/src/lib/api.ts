const FALLBACK_BASE_URL = 'http://127.0.0.1:18081';

let baseUrlPromise: Promise<string> | undefined;

async function baseUrl(): Promise<string> {
  if (!baseUrlPromise) {
    baseUrlPromise = window.api?.backend.getBaseUrl().catch(() => FALLBACK_BASE_URL) ?? Promise.resolve(FALLBACK_BASE_URL);
  }
  return baseUrlPromise;
}

/**
 * API 错误：对外暴露稳定的 code 与面向用户的 message；
 * 原始堆栈仅在 console 中记录，不展示给用户。
 */
export class ApiError extends Error {
  readonly code: string;
  readonly status: number;
  /** 记忆 REST 的 {retryable?,details?}：409 的 details 带服务端最新 metadata，界面要拿它跟草稿对照。 */
  readonly retryable?: boolean;
  readonly details?: unknown;

  constructor(code: string, message: string, status: number, extra: { retryable?: unknown; details?: unknown } = {}) {
    super(message);
    this.name = 'ApiError';
    this.code = code;
    this.status = status;
    if (typeof extra.retryable === 'boolean') this.retryable = extra.retryable;
    if (extra.details !== undefined) this.details = extra.details;
  }
}

function readField(payload: unknown, key: string): unknown {
  if (payload && typeof payload === 'object' && key in payload) {
    return (payload as Record<string, unknown>)[key];
  }
  return undefined;
}

function extractCode(payload: unknown): string {
  if (payload && typeof payload === 'object' && 'errorCode' in payload) {
    return String((payload as { errorCode: unknown }).errorCode);
  }
  if (payload && typeof payload === 'object' && 'error' in payload) {
    return String((payload as { error: unknown }).error);
  }
  // 记忆 REST 走 {code,message,retryable?,details?}，不经过全局异常处理器，键名与聊天侧不同
  if (payload && typeof payload === 'object' && 'code' in payload) {
    return String((payload as { code: unknown }).code);
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
  // FormData 交给浏览器自动设置 Content-Type（含 multipart boundary），不能手动覆盖
  const isFormData = typeof FormData !== 'undefined' && init.body instanceof FormData;
  const response = await fetch(`${await baseUrl()}${path}`, {
    ...init,
    headers: {
      Accept: 'application/json',
      ...(init.body && !isFormData ? { 'Content-Type': 'application/json' } : {}),
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
    console.warn('[api] request failed', { path, code, status: response.status, message });
    throw new ApiError(code, message, response.status, {
      retryable: readField(payload, 'retryable'),
      details: readField(payload, 'details')
    });
  }
  return payload as T;
}

export const get = <T>(path: string) => apiRequest<T>(path);
export const post = <T>(path: string, body: unknown) => apiRequest<T>(path, { method: 'POST', body: JSON.stringify(body) });
export const put = <T>(path: string, body: unknown) => apiRequest<T>(path, { method: 'PUT', body: JSON.stringify(body) });
export const patch = <T>(path: string, body: unknown) => apiRequest<T>(path, { method: 'PATCH', body: JSON.stringify(body) });
export const del = <T>(path: string) => apiRequest<T>(path, { method: 'DELETE' });

/** 带请求体的 DELETE：删除凭据与 expectedVersion 之类不能进 URL 的字段（记忆条目即此类）。 */
export const delJson = <T>(path: string, body: unknown) =>
  apiRequest<T>(path, { method: 'DELETE', body: JSON.stringify(body) });

/** multipart 上传（如备份导入）；Content-Type 由浏览器按 FormData 自动设置。 */
export function postForm<T>(path: string, form: FormData): Promise<T> {
  return apiRequest<T>(path, { method: 'POST', body: form });
}

/**
 * 兜底冲刷：在 beforeunload / 页面隐藏时把未保存内容尽力送达。
 * keepalive 让请求脱离页面生命周期交给网络栈，窗口关闭后仍有机会完成（尽力而为，非可靠传输）。
 */
export async function flushRequest(path: string, body: unknown): Promise<void> {
  const response = await fetch(`${await baseUrl()}${path}`, {
    method: 'PUT',
    headers: { Accept: 'application/json', 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
    keepalive: true
  });
  if (!response.ok) throw new ApiError('save_failed', '笔记保存失败，请稍后重试', response.status);
}
