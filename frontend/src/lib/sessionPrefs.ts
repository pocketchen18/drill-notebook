/** localStorage keys for session-end and study preferences. */

export const LS_ENROLL_DEFAULT = 'session.enrollDefault';
export const LS_PLAN_DEFAULT = 'session.planDefault';
/** When true, same-day correct completions advance SRS again (skip extra short-circuit). */
export const LS_FORCE_ADVANCE = 'study.forceAdvance';

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
