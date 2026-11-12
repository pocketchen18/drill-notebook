import fs from './update-fs';
import path from 'node:path';
import { createHash } from 'node:crypto';
import { pipeline } from 'node:stream/promises';
import { Transform } from 'node:stream';
import yauzl, { type Entry, type ZipFile } from 'yauzl';
import { assertNoLinks, PROGRAM_MANIFEST, programPathAllowed, validateManifest, type ProgramManifest } from './program-manifest';

function openZip(filename: string): Promise<ZipFile> {
  return new Promise((resolve, reject) => yauzl.open(filename, {
    lazyEntries: true, autoClose: false, strictFileNames: true, validateEntrySizes: true
  }, (error, zip) => error || !zip ? reject(error ?? new Error('更新 ZIP 无法打开。')) : resolve(zip)));
}

function entryStream(zip: ZipFile, entry: Entry): Promise<NodeJS.ReadableStream> {
  return new Promise((resolve, reject) => zip.openReadStream(entry, (error, stream) =>
    error || !stream ? reject(error ?? new Error('更新 ZIP 内容无法读取。')) : resolve(stream)));
}

function walk(zip: ZipFile, visit: (entry: Entry) => Promise<void>): Promise<void> {
  return new Promise((resolve, reject) => {
    zip.on('error', reject);
    zip.on('end', resolve);
    zip.on('entry', (entry: Entry) => {
      void visit(entry).then(() => zip.readEntry(), error => { zip.close(); reject(error); });
    });
    zip.readEntry();
  });
}

function validateEntry(entry: Entry): { name: string; directory: boolean } {
  const directory = entry.fileName.endsWith('/');
  const name = directory ? entry.fileName.slice(0, -1) : entry.fileName;
  const fileType = (entry.externalFileAttributes >>> 16) & 0xf000;
  if (!programPathAllowed(name, directory) || entry.isEncrypted() || (fileType && fileType !== (directory ? 0x4000 : 0x8000))) {
    throw new Error('更新 ZIP 包含非法路径、符号链接或加密文件。');
  }
  if (!Number.isSafeInteger(entry.uncompressedSize) || entry.uncompressedSize > 2 * 1024 ** 3
    || (entry.uncompressedSize > 1024 ** 2 && entry.uncompressedSize > Math.max(1, entry.compressedSize) * 300)) {
    throw new Error('更新 ZIP 的解压大小或压缩比例异常。');
  }
  return { name, directory };
}

/** First inspect the whole central directory; no program is written until the manifest and all paths pass. */
export async function extractUpdateArchive(filename: string, stage: string, version: string): Promise<ProgramManifest> {
  const zip = await openZip(filename);
  let manifest: ProgramManifest | undefined;
  const entries = new Map<string, { name: string; size: number }>();
  const directories = new Set<string>();
  try {
    if (zip.entryCount > 30_000) throw new Error('更新 ZIP 文件数超过安全限制。');
    await walk(zip, async entry => {
      const { name, directory } = validateEntry(entry);
      const key = name.toLowerCase();
      if (directory) {
        if (entries.has(key)) throw new Error('更新 ZIP 的文件与目录冲突。');
        directories.add(key);
        return;
      }
      if (entries.has(key) || directories.has(key)) throw new Error('更新 ZIP 包含重复或大小写冲突路径。');
      entries.set(key, { name, size: entry.uncompressedSize });
      if (name === PROGRAM_MANIFEST) {
        if (entry.uncompressedSize > 4 * 1024 ** 2) throw new Error('程序文件清单过大。');
        const stream = await entryStream(zip, entry);
        const parts: Buffer[] = [];
        let bytes = 0;
        for await (const chunk of stream) {
          bytes += Buffer.byteLength(chunk);
          if (bytes > 4 * 1024 ** 2) throw new Error('程序文件清单过大。');
          parts.push(Buffer.from(chunk));
        }
        manifest = validateManifest(JSON.parse(Buffer.concat(parts).toString('utf8')), version);
      }
    });
  } finally { zip.close(); }
  if (!manifest) throw new Error('此便携包未提供程序文件清单，请通过 GitHub 手动更新。');
  const expected = new Map(manifest.files.map(file => [file.path.toLowerCase(), file]));
  if (entries.size !== expected.size + 1) throw new Error('更新 ZIP 与程序文件清单不一致。');
  for (const entry of entries.values()) {
    if (entry.name === PROGRAM_MANIFEST) continue;
    const file = expected.get(entry.name.toLowerCase());
    if (!file || file.path !== entry.name || file.size !== entry.size) throw new Error('更新 ZIP 与程序文件清单不一致。');
  }
  for (const [key, entry] of entries) {
    const pieces = entry.name.split('/');
    pieces.pop();
    while (pieces.length) {
      if (entries.has(pieces.join('/').toLowerCase())) throw new Error(`更新 ZIP 目录与文件冲突：${key}`);
      pieces.pop();
    }
  }
  assertNoLinks(stage);
  fs.mkdirSync(stage, { recursive: true });
  const content = await openZip(filename);
  try {
    await walk(content, async entry => {
      const { name, directory } = validateEntry(entry);
      if (directory) return;
      const target = assertNoLinks(stage, name);
      fs.mkdirSync(path.dirname(target), { recursive: true });
      assertNoLinks(stage, name);
      const hash = createHash('sha256');
      let bytes = 0;
      const stream = await entryStream(content, entry);
      await pipeline(stream, new Transform({ transform(chunk: Buffer, _encoding, callback) {
        bytes += chunk.length;
        if (bytes > entry.uncompressedSize) { callback(new Error('更新文件解压大小异常。')); return; }
        hash.update(chunk);
        callback(null, chunk);
      } }), fs.createWriteStream(target, { flags: 'wx' }));
      const file = expected.get(name.toLowerCase());
      if (bytes !== entry.uncompressedSize || (file && hash.digest('hex') !== file.sha256)) throw new Error(`更新文件完整性校验失败：${name}`);
    });
  } finally { content.close(); }
  return manifest;
}
