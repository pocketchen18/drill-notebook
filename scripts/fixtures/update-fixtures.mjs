import fs from 'node:fs';
import path from 'node:path';
import vm from 'node:vm';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';
import { createHash } from 'node:crypto';
import ts from 'typescript';

export const root = fileURLToPath(new URL('../../', import.meta.url));
const require = createRequire(import.meta.url);
const modules = new Map();
export function sourceModule(name) {
  const filename = path.join(root, 'electron', `${name}.ts`);
  if (modules.has(filename)) return modules.get(filename).exports;
  const module = { exports: {} }; modules.set(filename, module);
  const code = ts.transpileModule(fs.readFileSync(filename, 'utf8'), { compilerOptions: {
    target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.CommonJS, esModuleInterop: true,
  } }).outputText;
  const wrapper = vm.runInThisContext(`(function(require,module,exports,__dirname){${code}\n})`, { filename });
  wrapper(id => id.startsWith('./') ? sourceModule(id.slice(2)) : require(id), module, module.exports, path.dirname(filename));
  return module.exports;
}
export function scratch(t, prefix = 'app-updater-') {
  const parent = path.join(root, 'temp'); fs.mkdirSync(parent, { recursive: true });
  const directory = fs.mkdtempSync(path.join(parent, prefix));
  t.after(() => {
    if (path.dirname(directory) !== parent) throw new Error('Unsafe test cleanup');
    fs.rmSync(directory, { recursive: true, force: true, maxRetries: 10, retryDelay: 100 });
  });
  return directory;
}
export const hash = value => createHash('sha256').update(value).digest('hex');
export const put = (root, name, value) => { const file = path.join(root, name); fs.mkdirSync(path.dirname(file), { recursive: true }); fs.writeFileSync(file, value); return file; };
export function portableFiles(executable = Buffer.from('new executable')) {
  return {
    'Drill Notebook.exe': executable,
    'resources/app.asar': Buffer.from('new renderer'),
    'resources/backend/app.jar': Buffer.from('new backend'),
    'resources/jre/bin/java.exe': Buffer.from('new java'),
    'resources/updater/drill-updater.exe': Buffer.from('new helper'),
    'locales/zh-CN.pak': Buffer.from('locale'),
  };
}
export function manifestFor(files, version = '0.8.0') {
  return { format: 1, version, files: Object.entries(files).map(([name, value]) => ({ path: name, size: Buffer.byteLength(value), sha256: hash(value) })) };
}
export async function zipBytes(files, { version = '0.8.0', manifest = manifestFor(files, version), extra = [] } = {}) {
  const { ZipFile } = require('yazl');
  const zip = new ZipFile();
  const chunks = [];
  const done = new Promise((resolve, reject) => { zip.outputStream.on('data', chunk => chunks.push(chunk)); zip.outputStream.on('error', reject); zip.outputStream.on('end', () => resolve(Buffer.concat(chunks))); });
  for (const [name, value] of Object.entries(files)) zip.addBuffer(Buffer.from(value), name);
  if (manifest) zip.addBuffer(Buffer.from(JSON.stringify(manifest)), 'drill-program-manifest.json');
  for (const [name, value, options] of extra) zip.addBuffer(Buffer.from(value), name, options);
  zip.end(); return done;
}
export function releaseRaw(version = '0.8.0', assetBytes, options = {}) {
  const name = `Drill-Notebook-${version}-win-x64-portable.zip`;
  return { tag_name: `v${version}`, name: `v${version}`, draft: false, prerelease: true, published_at: '2026-11-12T00:00:00Z',
    body: `## v${version}\n完整的更新内容\n\n- 修复与改进`,
    assets: assetBytes ? [{ name, size: assetBytes.length, browser_download_url: `https://github.com/qilin-software/drill-notebook/releases/download/v${version}/${name}`,
      digest: `sha256:${hash(assetBytes)}` }] : [], ...options };
}
