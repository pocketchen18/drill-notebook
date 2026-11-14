export function canOpenExternally(href: string): boolean {
  try {
    const { protocol } = new URL(href);
    return protocol === 'http:' || protocol === 'https:';
  } catch {
    return false;
  }
}

/** 链接只在系统浏览器中打开，协议规则与主进程一致。 */
export function openExternalLink(href: string): boolean {
  if (!canOpenExternally(href)) return false;
  const url = new URL(href).href;
  if (window.api?.shell?.openExternal) void window.api.shell.openExternal(url);
  else window.open(url, '_blank', 'noopener,noreferrer');
  return true;
}
