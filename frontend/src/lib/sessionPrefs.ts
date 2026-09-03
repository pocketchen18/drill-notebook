/** localStorage keys for session-end and study preferences. */

export const LS_ENROLL_DEFAULT = 'session.enrollDefault';
export const LS_PLAN_DEFAULT = 'session.planDefault';
/** When true, same-day correct completions advance SRS again (skip extra short-circuit). */
export const LS_FORCE_ADVANCE = 'study.forceAdvance';
/** AI 侧栏「检索笔记」开关（Task 14，不含敏感内容）。 */
export const LS_RETRIEVE_NOTES = 'ai.retrieveNotes';
/** AI 侧栏检索范围偏好：'current' | 'all'。 */
export const LS_RETRIEVAL_SCOPE = 'ai.retrievalScope';
/** 是否记住上次停留页面与各页选择/切换/筛选（默认开启）。 */
export const LS_REMEMBER_VIEW_STATE = 'ui.rememberViewState';
/** 是否显示右下角 AI 悬浮球（默认显示；隐藏不影响快捷键唤出）。 */
export const LS_SHOW_AI_FAB = 'ui.showAiFab';

export function readBoolPref(key: string, fallback: boolean): boolean {
  try {
    const raw = localStorage.getItem(key);
    if (raw === null) return fallback;
    return raw === 'true' || raw === '1';
  } catch {
    return fallback;
  }
}

export function writeBoolPref(key: string, value: boolean): void {
  try {
    localStorage.setItem(key, value ? 'true' : 'false');
  } catch {
    /* ignore quota / private mode */
  }
}

export function readStringPref(key: string, fallback: string): string {
  try {
    return localStorage.getItem(key) ?? fallback;
  } catch {
    return fallback;
  }
}

export function writeStringPref(key: string, value: string): void {
  try {
    localStorage.setItem(key, value);
  } catch {
    /* ignore quota / private mode */
  }
}
