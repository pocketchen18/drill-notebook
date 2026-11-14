const SCHEME = /^([a-z][a-z0-9+-]*):/i;
const ALLOWED_SCHEMES = new Set(['http', 'https', 'mailto']);

export { canOpenExternally, openExternalLink } from '../../lib/externalLink';

/** 允许相对地址与无协议地址；显式写出的协议只能是网页或邮件。 */
export function isAllowedLinkUri(url: string): boolean {
  const scheme = SCHEME.exec(url.trim())?.[1]?.toLowerCase();
  return !scheme || ALLOWED_SCHEMES.has(scheme);
}

/** 补全输入的地址：裸域名补 https，裸邮箱补 mailto。 */
export function normalizeHref(raw: string): string {
  const value = raw.trim();
  if (!value) return '';
  if (SCHEME.test(value)) return value;
  if (/^[^\s@/]+@[^\s@/]+\.[^\s@/]+$/.test(value)) return `mailto:${value}`;
  return `https://${value.replace(/^\/+/, '')}`;
}
