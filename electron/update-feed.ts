import semver from 'semver';
import type { NotesRange, UpdateAsset, UpdateRelease } from './update-types';

export const REPOSITORY = 'pocketchen18/drill-notebook';
export const REPOSITORY_URL = `https://github.com/${REPOSITORY}`;
export const RELEASES_API = `https://api.github.com/repos/${REPOSITORY}/releases`;
export type UpdateFetch = (url: string, init?: RequestInit) => Promise<Response>;

export function versionOf(value: unknown): string | undefined {
  if (typeof value !== 'string' || value.length > 100) return undefined;
  return semver.valid(value.replace(/^v/, '')) ?? undefined;
}

export function compareVersions(a: string, b: string): number { return semver.compare(a, b); }

export function assetUrlAllowed(value: string, tag?: string): boolean {
  try {
    const url = new URL(value);
    if (url.protocol !== 'https:' || url.username || url.password || url.port) return false;
    const prefix = `/${REPOSITORY}/releases/download/`;
    if (url.hostname !== 'github.com' || !url.pathname.startsWith(prefix)) return false;
    return !tag || decodeURIComponent(url.pathname.slice(prefix.length).split('/')[0]) === tag;
  } catch { return false; }
}

export function downloadDestinationAllowed(value: string): boolean {
  try {
    const url = new URL(value);
    return url.protocol === 'https:' && !url.username && !url.password && !url.port
      && ['github.com', 'release-assets.githubusercontent.com', 'objects.githubusercontent.com'].includes(url.hostname);
  } catch { return false; }
}

function assetOf(raw: unknown, name: string, tag: string): UpdateAsset | undefined {
  if (!raw || typeof raw !== 'object') return undefined;
  const a = raw as Record<string, unknown>;
  if (a.name !== name || typeof a.browser_download_url !== 'string' || !assetUrlAllowed(a.browser_download_url, tag)
    || typeof a.size !== 'number' || !Number.isSafeInteger(a.size) || a.size <= 0 || a.size > 1024 ** 3) return undefined;
  const digest = typeof a.digest === 'string' && /^sha256:[a-f0-9]{64}$/i.test(a.digest) ? a.digest.slice(7).toLowerCase() : undefined;
  return { name, url: a.browser_download_url, size: a.size, ...(digest ? { digest } : {}) };
}

/** Do not use /latest: the project's v0.x releases are all prereleases. */
export function releaseOf(raw: unknown): UpdateRelease | undefined {
  if (!raw || typeof raw !== 'object') return undefined;
  const r = raw as Record<string, unknown>;
  const version = versionOf(r.tag_name);
  if (!version || r.draft === true || typeof r.tag_name !== 'string') return undefined;
  const tag = r.tag_name;
  const zip = `Drill-Notebook-${version}-win-x64-portable.zip`;
  const assets = Array.isArray(r.assets) ? r.assets : [];
  const asset = assets.map(a => assetOf(a, zip, tag)).find(Boolean);
  const checksum = assets.map(a => assetOf(a, `${zip}.sha256`, tag)).find(Boolean);
  return {
    version, tag, name: typeof r.name === 'string' ? r.name : tag,
    body: typeof r.body === 'string' ? r.body : '',
    publishedAt: typeof r.published_at === 'string' ? r.published_at : '',
    url: `${REPOSITORY_URL}/releases/tag/${encodeURIComponent(tag)}`,
    prerelease: r.prerelease === true || semver.major(version) === 0 || semver.prerelease(version) !== null,
    ...(asset ? { asset } : {}), ...(checksum ? { checksum } : {})
  };
}

export function orderReleases(releases: UpdateRelease[]): UpdateRelease[] {
  const unique = new Map<string, UpdateRelease>();
  for (const release of releases) unique.set(release.version, release);
  return [...unique.values()].sort((a, b) => compareVersions(b.version, a.version));
}

export function selectNotes(releases: UpdateRelease[], installed: string, range: NotesRange,
  since?: string, target?: string): UpdateRelease[] {
  if (!versionOf(installed)) return [];
  const ordered = orderReleases(releases);
  if (typeof range === 'number') return ordered.filter(r => compareVersions(r.version, installed) <= 0).slice(0, range + 1);
  const previous = since && versionOf(since) && compareVersions(since, installed) < 0 ? since : undefined;
  const upper = target && versionOf(target) && compareVersions(target, installed) > 0 ? target : installed;
  return ordered.filter(r => compareVersions(r.version, upper) <= 0
    && (previous ? compareVersions(r.version, previous) > 0 : compareVersions(r.version, installed) >= 0));
}

export function selectPending(releases: UpdateRelease[], installed: string, target: string): UpdateRelease[] {
  return orderReleases(releases).filter(r => compareVersions(r.version, installed) > 0 && compareVersions(r.version, target) <= 0);
}

export async function fetchReleases(fetcher: UpdateFetch): Promise<UpdateRelease[]> {
  const all: UpdateRelease[] = [];
  // Bounded pagination also makes the 100-version history range meaningful.
  for (let page = 1; page <= 10; page++) {
    const response = await fetcher(`${RELEASES_API}?per_page=100&page=${page}`, {
      headers: { Accept: 'application/vnd.github+json', 'User-Agent': 'Drill-Notebook-Updater', 'X-GitHub-Api-Version': '2022-11-28' },
      credentials: 'omit', signal: AbortSignal.timeout(20_000)
    });
    if (!response.ok) {
      if (response.status === 403 || response.status === 429) throw new Error('GitHub 请求受到限流，请稍后再试。');
      throw new Error(`GitHub 更新信息请求失败（HTTP ${response.status}）。`);
    }
    const source = await readLimitedText(response, 16 * 1024 ** 2);
    let raw: unknown;
    try { raw = JSON.parse(source); } catch { throw new Error('GitHub 返回的更新信息格式无效。'); }
    if (!Array.isArray(raw)) throw new Error('GitHub 返回的更新列表无效。');
    all.push(...raw.map(releaseOf).filter((r): r is UpdateRelease => !!r));
    if (raw.length < 100) return orderReleases(all);
  }
  throw new Error('发布列表超过安全读取上限，请到 GitHub 查看完整记录。');
}

export async function readLimitedText(response: Response, limit: number): Promise<string> {
  if (!response.body) throw new Error('服务器返回了空响应。');
  const reader = response.body.getReader();
  const parts: Uint8Array[] = [];
  let length = 0;
  try {
    for (;;) {
      const { done, value } = await reader.read();
      if (done) break;
      length += value.byteLength;
      if (length > limit) throw new Error('服务器返回的内容超过安全大小限制。');
      parts.push(value);
    }
  } finally { await reader.cancel().catch(() => undefined); }
  return Buffer.concat(parts).toString('utf8');
}
