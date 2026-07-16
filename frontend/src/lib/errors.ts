import { ApiError } from './api';

/**
 * 对外统一的简要报错文案。error.code 优先，缺失时回退到 error.message。
 * 内部堆栈与原始 cause 由后端 logger 记录，前端只展示用户可读信息。
 */
const FRIENDLY_MESSAGES: Record<string, string> = {
  invalid_request: '请求参数无效，请检查后重试',
  request_failed: '请求失败，请稍后重试',
  internal_error: '服务暂时不可用，请稍后重试',
  ai_unavailable: 'AI 服务暂时不可用，请稍后重试',
  ai_parse_failed: 'AI 解析失败，请稍后重试',
  import_failed: '导入失败，请稍后重试',
  not_found: '资源不存在',
  unauthorized: '未授权，请重新登录',
};

/**
 * 把任意错误转成对外展示的简要 message。
 * - ApiError：优先用 error.code 对应的简要文案，缺失时回退到 error.message
 * - 其他 Error：返回 friendlyFallback
 * - 非错误：返回 friendlyFallback
 *
 * 同时把完整错误写入 console.error，便于开发者排查。
 */
export function friendlyMessage(error: unknown, friendlyFallback: string): string {
  if (error instanceof ApiError) {
    const fromCode = FRIENDLY_MESSAGES[error.code];
    const message = fromCode ?? (error.message || friendlyFallback);
    console.error('[api] error detail', { code: error.code, status: error.status, message: error.message, cause: error });
    return message;
  }
  if (error instanceof Error) {
    console.error('[api] error detail', { name: error.name, message: error.message, cause: error });
    return error.message || friendlyFallback;
  }
  console.error('[api] unknown error', error);
  return friendlyFallback;
}
