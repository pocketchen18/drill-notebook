import fs from './update-fs';
import path from 'node:path';
import { createHash, randomUUID } from 'node:crypto';
import { versionOf } from './update-feed';

export const PROGRAM_MANIFEST = 'drill-program-manifest.json';
export const APP_EXECUTABLE = 'Drill Notebook.exe';
const ENGINE_FILES = new Set([
  APP_EXECUTABLE.toLowerCase(), 'chrome_100_percent.pak', 'chrome_200_percent.pak', 'd3dcompiler_47.dll',
  'ffmpeg.dll', 'icudtl.dat', 'libegl.dll', 'libglesv2.dll', 'resources.pak', 'snapshot_blob.bin',
  'v8_context_snapshot.bin', 'vk_swiftshader.dll', 'vk_swiftshader_icd.json', 'vulkan-1.dll',
  'license.electron.txt', 'licenses.chromium.html', 'notification_helper.exe', PROGRAM_MANIFEST
]);
const REQUIRED_FILES = [APP_EXECUTABLE, 'resources/app.asar', 'resources/backend/app.jar',
  'resources/jre/bin/java.exe', 'resources/updater/drill-updater.exe'];

export interface ProgramFile { path: string; size: number; sha256: string; }
export interface ProgramManifest { format: 1; version: string; files: ProgramFile[]; }

/** Reject Win32 aliases (ADS, device names, trailing dots), traversal and separators before joining. */
export function safeRelativePath(value: string): boolean {
  if (!value || value.length > 240 || value.includes('\\') || value.startsWith('/') || /[\x00-\x1f:<>"|?*]/.test(value)) return false;
  return value.split('/').every(segment => !!segment && segment !== '.' && segment !== '..'
    && !/[. ]$/.test(segment) && !/^\s/.test(segment)
    && !/^(?:con|prn|aux|nul|com[1-9]|lpt[1-9])(?:\.|$)/i.test(segment));
}

export function programPathAllowed(value: string, directory = false): boolean {
  if (!safeRelativePath(value)) return false;
  const lower = value.toLowerCase();
  if (lower.startsWith('resources/')) return true;
  if (directory && (lower === 'resources' || lower === 'locales')) return true;
  if (/^locales\/[a-z0-9-]+\.pak$/i.test(value)) return true;
  return !value.includes('/') && ENGINE_FILES.has(lower);
}

export function validateManifest(raw: unknown, version?: string): ProgramManifest {
  if (!raw || typeof raw !== 'object') throw new Error('更新包缺少程序文件清单。');
  const m = raw as Record<string, unknown>;
  if (m.format !== 1 || !versionOf(m.version) || (version && m.version !== version)
    || !Array.isArray(m.files) || m.files.length === 0 || m.files.length > 20_000) throw new Error('程序文件清单的版本或格式无效。');
  const seen = new Set<string>();
  let total = 0;
  const files = m.files.map((rawFile: unknown): ProgramFile => {
    const file = rawFile as Partial<ProgramFile> | null;
    if (!file || typeof file.path !== 'string' || !programPathAllowed(file.path) || file.path === PROGRAM_MANIFEST
      || typeof file.size !== 'number' || !Number.isSafeInteger(file.size) || file.size < 0
      || typeof file.sha256 !== 'string' || !/^[a-f0-9]{64}$/.test(file.sha256)) throw new Error('更新包的程序文件清单不安全或不完整。');
    const key = file.path.toLowerCase();
    if (seen.has(key)) throw new Error('更新包包含重复或大小写冲突的文件。');
    seen.add(key);
    total += file.size;
    if (total > 2 * 1024 ** 3) throw new Error('更新包解压大小超过安全限制。');
    return { path: file.path, size: file.size, sha256: file.sha256 };
  });
  if (REQUIRED_FILES.some(name => !files.some(file => file.path === name))) throw new Error('更新包缺少必需的程序或便携更新组件。');
  for (const file of files) {
    const parents = file.path.split('/');
    parents.pop();
    while (parents.length) {
      if (seen.has(parents.join('/').toLowerCase())) throw new Error('更新包的文件与目录路径冲突。');
      parents.pop();
    }
  }
  return { format: 1, version: m.version as string, files };
}

export function assertNoLinks(root: string, relative = ''): string {
  const resolved = path.resolve(root);
  if (relative && !safeRelativePath(relative)) throw new Error('更新路径无效。');
  const target = relative ? path.join(resolved, ...relative.split('/')) : resolved;
  const difference = path.relative(resolved, target);
  if (difference.startsWith('..') || path.isAbsolute(difference)) throw new Error('更新路径超出了便携目录。');
  let cursor = resolved;
  for (const part of ['', ...(relative ? relative.split('/') : [])]) {
    if (part) cursor = path.join(cursor, part);
    try {
      if (fs.lstatSync(cursor).isSymbolicLink()) throw new Error('更新目录包含符号链接或目录联接，已停止替换。');
    } catch (error) {
      if ((error as NodeJS.ErrnoException).code === 'ENOENT') continue;
      throw error;
    }
  }
  return target;
}

export async function fileDigest(filename: string): Promise<string> {
  const digest = createHash('sha256');
  for await (const chunk of fs.createReadStream(filename)) digest.update(chunk);
  return digest.digest('hex');
}

export function writeAtomicJson(filename: string, value: unknown): void {
  fs.mkdirSync(path.dirname(filename), { recursive: true });
  const temporary = `${filename}.${randomUUID()}.new`;
  const handle = fs.openSync(temporary, 'wx', 0o600);
  try {
    fs.writeFileSync(handle, JSON.stringify(value, null, 2) + '\n', 'utf8');
    fs.fsyncSync(handle);
  } finally { fs.closeSync(handle); }
  try { fs.renameSync(temporary, filename); }
  catch (error) { fs.rmSync(temporary, { force: true }); throw error; }
}
