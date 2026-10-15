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
  // 记忆 REST 的 code 词表（后端 message 已是可读中文，这里只兜 message 缺失的情况）
  MEMORY_LOCKED: '记忆库已锁定，解锁后才能查看或修改',
  MEMORY_UNLOCK_FAILED: '主密码不对，未解锁任何内容',
  MEMORY_VERSION_CONFLICT: '这条记忆刚被别人改过，请对照最新值再决定',
  MEMORY_SETTINGS_CONFLICT: '记忆设置刚被改过，请重试',
  MEMORY_CONSENT_STALE: '模型或授权已变更，需要重新授权',
  MEMORY_TARGET_MISSING: '还没有可用的维护模型，请先在 AI 连接里配置',
};

/**
 * 把任意错误转成对外展示的简要 message。
 * - ApiError：优先展示后端返回的具体 message；仅在 message 为空时回退 code 映射
 * - 其他 Error：返回 error.message 或 friendlyFallback
 *
 * 同时把完整错误写入 console.error，便于开发者排查。
 */
export function friendlyMessage(error: unknown, friendlyFallback: string): string {
  if (error instanceof ApiError) {
    const specific = error.message?.trim();
    // 后端已给出具体原因时直接展示，避免把「max_tokens 截断/超时」盖成笼统「请求参数无效」
    if (specific && specific !== '请求参数无效' && !specific.startsWith('请求失败（')) {
      console.error('[api] error detail', { code: error.code, status: error.status, message: error.message, cause: error });
      return specific;
    }
    const fromCode = FRIENDLY_MESSAGES[error.code];
    const message = fromCode ?? (specific || friendlyFallback);
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
