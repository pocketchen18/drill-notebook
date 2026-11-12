import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import http from 'node:http';
import { createRequire } from 'node:module';
import { spawn, spawnSync } from 'node:child_process';
import { once } from 'node:events';
import { randomUUID } from 'node:crypto';
import { setTimeout as delay } from 'node:timers/promises';
import test, { before, after } from 'node:test';
import { root, hash, put } from './fixtures/update-fixtures.mjs';
import { generateManifest, generateChecksum } from './generate-update-metadata.mjs';

const require = createRequire(import.meta.url);
const { build, Platform, Arch } = require('electron-builder');
const asar = require('@electron/asar');
const { ZipFile } = require('yazl');
const { writeAtomicJson, fileDigest } = require('../electron-dist/program-manifest.js');
const native = { skip: process.platform !== 'win32', timeout: 140_000 };
const read = file => { try { return JSON.parse(fs.readFileSync(file, 'utf8')); } catch { return undefined; } };
const alive = pid => { if (!pid) return false; try { process.kill(pid, 0); return true; } catch { return false; } };
async function waitFor(predicate, explanation, timeout = 80_000) {
  const deadline = Date.now() + timeout;
  while (Date.now() < deadline) { const value = await predicate(); if (value) return value; await delay(100); }
  throw new Error(`Timed out: ${typeof explanation === 'function' ? explanation() : explanation}`);
}
let directory;
let base;
let packages;
const desktopTest = (name, options, run) => test(name, options, async t => {
  try { await run(t); } catch (error) { t.diagnostic(error.stack ?? String(error)); throw error; }
});
const noteContent = text => ({ type: 'doc', content: [{ type: 'paragraph', content: [{ type: 'text', text }] }] });
async function updated(h, phase = 'complete') {
  return waitFor(() => {
    const id = h.terminal(phase); if (id) return id;
    const failed = h.transactionStatus().find(status => status.phase === 'recovery-required' || (phase === 'complete' && status.phase === 'rolled-back'));
    if (failed) throw new Error(`Portable update ${failed.phase}: ${failed.error ?? 'update failed'}`);
    if (h.state()?.state.progress.phase === 'failed') throw new Error(h.state().state.progress.message);
    return false;
  }, () => JSON.stringify(h.state()), 100_000);
}

async function archive(baseRoot, oldManifest, newAsar, destination, faulty) {
  const jar = Buffer.from('intentional invalid JAR for recovery validation');
  const manifest = { format: 1, version: '0.8.0', files: await Promise.all(oldManifest.files.map(async file => {
    if (file.path === 'resources/app.asar') return { ...file, size: fs.statSync(newAsar).size, sha256: await fileDigest(newAsar) };
    if (faulty && file.path === 'resources/backend/app.jar') return { ...file, size: jar.length, sha256: hash(jar) };
    return file;
  })) };
  fs.mkdirSync(destination, { recursive: true });
  const filename = path.join(destination, 'Drill-Notebook-0.8.0-win-x64-portable.zip');
  const zip = new ZipFile();
  const output = fs.createWriteStream(filename);
  const completed = once(output, 'close');
  zip.outputStream.pipe(output);
  for (const file of manifest.files) {
    if (faulty && file.path === 'resources/backend/app.jar') zip.addBuffer(jar, file.path);
    else zip.addFile(file.path === 'resources/app.asar' ? newAsar : path.join(baseRoot, file.path), file.path);
  }
  zip.addBuffer(Buffer.from(JSON.stringify(manifest)), 'drill-program-manifest.json');
  zip.end(); await completed;
  await generateChecksum(filename);
  return { filename, size: fs.statSync(filename).size, checksum: fs.readFileSync(filename + '.sha256', 'utf8') };
}

before(async () => {
  if (process.platform !== 'win32') return;
  const parent = path.join(root, 'temp'); fs.mkdirSync(parent, { recursive: true });
  directory = fs.mkdtempSync(path.join(parent, 'update-desktop-'));
  const savedEnv = new Map(['ELECTRON_BUILDER_CACHE', 'ELECTRON_CACHE', 'TEMP', 'TMP', 'CSC_IDENTITY_AUTO_DISCOVERY'].map(key => [key, process.env[key]]));
  try {
    process.env.ELECTRON_BUILDER_CACHE = path.join(root, '.tooling/electron-builder');
    process.env.ELECTRON_CACHE = path.join(root, '.tooling/electron-cache');
    process.env.TEMP = process.env.TMP = path.join(directory, 'tool-temp');
    process.env.CSC_IDENTITY_AUTO_DISCOVERY = 'false';
    fs.mkdirSync(process.env.TEMP, { recursive: true });
    console.log('Building a newly isolated portable directory with the real Electron entry, renderer, JAR and JRE.');
    await build({ projectDir: root, targets: Platform.WINDOWS.createTarget('dir', Arch.x64), publish: 'never', config: {
      directories: { output: path.join(directory, 'package') }, extraMetadata: { version: '0.7.3' },
      electronDist: path.join(root, 'node_modules/electron/dist'), win: { signAndEditExecutable: false }
    } });
  } finally { for (const [key, value] of savedEnv) { if (value === undefined) delete process.env[key]; else process.env[key] = value; } }
  base = path.join(directory, 'package/win-unpacked');
  // The npm Electron distribution adds this installer marker; it is absent from release ZIPs and is not a managed program.
  if (fs.existsSync(path.join(base, 'version'))) fs.unlinkSync(path.join(base, 'version'));
  const source = path.join(directory, 'asar-source');
  assert.equal(asar.listPackage(path.join(base, 'resources/app.asar')).some(name => name.replaceAll('\\', '/').endsWith('/update-desktop-driver.cjs')), false,
    'The production package must not include the QA control entry point');
  asar.extractAll(path.join(base, 'resources/app.asar'), source);
  fs.copyFileSync(path.join(root, 'scripts/fixtures/update-desktop-driver.cjs'), path.join(source, 'update-desktop-driver.cjs'));
  const metadata = read(path.join(source, 'package.json'));
  metadata.main = 'update-desktop-driver.cjs'; metadata.version = '0.7.3';
  fs.writeFileSync(path.join(source, 'package.json'), JSON.stringify(metadata));
  await asar.createPackage(source, path.join(base, 'resources/app.asar'));
  const oldManifest = await generateManifest(base, '0.7.3');
  metadata.version = '0.8.0'; fs.writeFileSync(path.join(source, 'package.json'), JSON.stringify(metadata));
  const newAsar = path.join(directory, 'new-app.asar'); await asar.createPackage(source, newAsar);
  console.log(`Package ready: ${oldManifest.files.length} program files; generating SHA-256 test ZIPs.`);
  packages = { good: await archive(base, oldManifest, newAsar, path.join(directory, 'good'), false),
    faulty: await archive(base, oldManifest, newAsar, path.join(directory, 'faulty'), true) };
});

async function sandbox(name, { automatic = false, faulty = false } = {}) {
  const appRoot = path.join(directory, name + ' 中文便携目录');
  fs.cpSync(base, appRoot, { recursive: true, dereference: false });
  const asset = packages[faulty ? 'faulty' : 'good'];
  const stats = { checks: 0, downloads: 0 };
  const stateFile = path.join(appRoot, 'runtime/qa/state.json');
  const state = () => read(stateFile);
  const events = () => { try { return fs.readFileSync(path.join(appRoot, 'runtime/qa/events.jsonl'), 'utf8').trim().split('\n').filter(Boolean).map(line => JSON.parse(line)); } catch { return []; } };
  let nextCommand = Promise.resolve();
  const command = (name, argument) => {
    const run = async () => {
      const snapshot = state(); assert.ok(snapshot, 'QA main process has not started');
      const id = randomUUID();
      writeAtomicJson(path.join(appRoot, 'runtime/qa/control.json'), { id, pid: snapshot.pid, version: snapshot.version, command: name, argument });
      const reply = await waitFor(() => { const value = read(path.join(appRoot, 'runtime/qa/reply.json')); return value?.id === id && value; }, `QA command ${name}`, 30_000);
      if (!reply.ok) throw new Error(reply.error);
      return reply;
    };
    const result = nextCommand.then(run); nextCommand = result.catch(() => undefined); return result;
  };
  const sendJson = (response, value, status = 200) => { response.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store' }); response.end(JSON.stringify(value)); };
  const notes = version => ({ tag_name: 'v' + version, name: 'v' + version, prerelease: true, draft: false,
    published_at: '2026-11-12T00:00:00Z', body: `## v${version} 更新内容\n\n- 完整保留每个版本的 Release 说明。\n- 自动下载安装并重启，保留笔记、附件和模型。`, assets: [] });
  const release = notes('0.8.0');
  const assetName = path.basename(asset.filename);
  const url = `https://github.com/qilin-software/drill-notebook/releases/download/v0.8.0/${assetName}`;
  release.assets = [{ name: assetName, browser_download_url: url, size: asset.size },
    { name: assetName + '.sha256', browser_download_url: url + '.sha256', size: Buffer.byteLength(asset.checksum) }];
  const releases = [release, notes('0.7.3'), notes('0.7.2'), notes('0.7.1')];
  const server = http.createServer(async (request, response) => {
    try {
      const pathname = new URL(request.url, 'http://127.0.0.1').pathname;
      if (pathname === '/releases') { stats.checks++; return sendJson(response, releases); }
      if (pathname === '/checksum') { response.writeHead(200, { 'Content-Type': 'text/plain' }); return response.end(asset.checksum); }
      if (pathname === '/archive') { stats.downloads++; response.writeHead(200, { 'Content-Type': 'application/zip', 'Content-Length': asset.size }); return fs.createReadStream(asset.filename).pipe(response); }
      sendJson(response, {}, 404);
    } catch (error) { if (!response.headersSent) sendJson(response, { ok: false, error: String(error.message ?? error) }, 500); else response.end(); }
  });
  server.listen(0, '127.0.0.1'); await once(server, 'listening');
  const feed = `http://127.0.0.1:${server.address().port}`;
  put(appRoot, 'config/update-settings.json', JSON.stringify({ format: 1, lastCheckAt: Date.now(), lastReminderAt: Date.now(), lastRunVersion: '0.7.3',
    settings: { automaticUpdate: automatic, automaticCheck: false, intervalHours: 24, startupReminder: false } }));
  const protectedFiles = { 'data/attachments/图片.bin': Buffer.from('keep attachment'), 'data/models/embeddings/custom/model.onnx': Buffer.from('keep embedding model'),
    'config/custom-user-setting.json': Buffer.from('{"keep":true}'), 'custom-user-file.txt': Buffer.from('keep user root file') };
  for (const [filename, value] of Object.entries(protectedFiles)) put(appRoot, filename, value);
  const env = { ...process.env, APP_ROOT: appRoot, DRILL_QA_ROOT: appRoot, DRILL_QA_FEED: feed,
    TEMP: path.join(appRoot, 'runtime/tmp'), TMP: path.join(appRoot, 'runtime/tmp') };
  for (const key of ['ELECTRON_RUN_AS_NODE', 'BACKEND_JAR', 'DRILL_EMBEDDING_WORKER_EXE', 'DRILL_UPDATE_TRANSACTION']) delete env[key];
  const child = spawn(path.join(appRoot, 'Drill Notebook.exe'), [], { cwd: appRoot, env, windowsHide: true, stdio: ['ignore', 'pipe', 'pipe'] });
  const exited = once(child, 'exit');
  const log = fs.createWriteStream(path.join(directory, name + '.log'));
  child.stdout.on('data', data => log.write(data)); child.stderr.on('data', data => log.write(data));
  const terminal = phase => {
    const active = read(path.join(appRoot, 'runtime/updater/active.json'));
    if (active?.id) return read(path.join(appRoot, 'runtime/updater', active.id, 'status.json'))?.phase === phase && active.id;
    const updater = path.join(appRoot, 'runtime/updater');
    if (!fs.existsSync(updater)) return false;
    for (const id of fs.readdirSync(updater)) if (read(path.join(updater, id, 'status.json'))?.phase === phase) return id;
    return false;
  };
  const transactionStatus = () => {
    const updater = path.join(appRoot, 'runtime/updater');
    if (!fs.existsSync(updater)) return [];
    return fs.readdirSync(updater).flatMap(id => {
      const status = read(path.join(updater, id, 'status.json'));
      return status ? [{ id, phase: status.phase, error: status.error, helperPid: status.helperPid }] : [];
    });
  };
  const protectedIntact = () => { for (const [filename, value] of Object.entries(protectedFiles)) assert.deepEqual(fs.readFileSync(path.join(appRoot, filename)), value, filename); };
  const cleanup = async () => {
    try { if (alive(state()?.pid)) { await command('quit'); await waitFor(() => !alive(state()?.pid), 'QA application exit', 15_000); } }
    finally {
      // Only the isolated app's recorded processes may be stopped after a failed test.
      for (const pid of [state()?.pid, state()?.backendPid, ...transactionStatus().map(status => status.helperPid)]) {
        if (!alive(pid)) continue;
        spawnSync('C:/Program Files/PowerShell/7/pwsh.exe', ['-NoProfile', '-File', path.join(root, 'scripts/fixtures/stop-update-test-process.ps1'),
          '-ProcessId', String(pid), '-AppRoot', appRoot], { windowsHide: true, stdio: 'ignore' });
      }
      if (child.exitCode === null && child.signalCode === null) child.kill();
      await exited; log.end(); server.closeAllConnections(); await new Promise(resolve => server.close(resolve));
      assert.equal(path.dirname(appRoot), directory); fs.rmSync(appRoot, { recursive: true, force: true, maxRetries: 15, retryDelay: 100 });
    }
  };
  return { appRoot, feed, state, events, command, terminal, transactionStatus, protectedIntact, cleanup, stats };
}

async function api(h, route, body) {
  const endpoint = h.state()?.backendUrl; assert.ok(endpoint, 'The real JAR must be healthy');
  const response = await fetch(endpoint + route, body === undefined ? undefined : { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) });
  assert.ok(response.ok, `${route}: ${response.status}`); return response.json();
}

desktopTest('真实 Electron 与 JAR：一次操作下载、保存、正常退出、替换并自动重启，数据和草稿保留', native, async t => {
  const h = await sandbox('manual'); t.after(h.cleanup);
  await waitFor(() => h.state()?.backendUrl && !h.state().state.startupBusy, 'initial real JAR and renderer ready');
  assert.equal(h.state().packaged, true); assert.equal(h.state().state.installSupported, true);
  const notebook = await api(h, '/api/notebooks', { title: '更新保护测试笔记本' });
  const page = await api(h, `/api/notebooks/${notebook.id}/pages`, { title: '不能丢失的笔记', content: noteContent('保留全部笔记正文。') });
  const draft = { format: 1, selectedSessionId: null, choice: { modelConfigId: null, reasoningEffort: null }, titleDraft: null,
    drafts: [{ sessionId: null, message: '未发送的助手草稿', attachments: [], scroll: 0 }] };
  await h.command('save-drafts', draft);
  assert.equal((await h.command('check')).result.target.version, '0.8.0');
  await h.command('install', '0.8.0');
  const id = await updated(h);
  await waitFor(() => h.state()?.version === '0.8.0' && h.state().backendUrl && !h.state().state.startupBusy, 'new real application healthy');
  assert.equal(h.stats.downloads, 1); assert.equal(h.state().state.updateVisible, false);
  const restored = await api(h, `/api/note-pages/${page.id}`);
  assert.equal(restored.title, page.title); assert.deepEqual(restored.content, page.content);
  assert.deepEqual((await h.command('read-drafts')).result, draft); h.protectedIntact();
  assert.ok(fs.statSync(path.join(h.appRoot, 'backups/updates', id, 'database/data/study.db')).size > 0);
  assert.equal(h.events().filter(event => event.event === 'backend-ready').length, 2);
  t.diagnostic(`Real portable update committed: ${id}; notebook ${notebook.id}, page ${page.id} retained.`);
});

desktopTest('启动自动更新独立于检查开关和间隔，旧版本不启动 JVM，更新后自动启动新版', native, async t => {
  const h = await sandbox('automatic', { automatic: true }); t.after(h.cleanup);
  await updated(h);
  await waitFor(() => h.state()?.version === '0.8.0' && h.state().backendUrl && !h.state().state.startupBusy, 'automatic new application healthy');
  assert.equal(h.stats.checks, 1); assert.equal(h.stats.downloads, 1);
  assert.equal(h.events().some(event => event.event === 'backend-ready' && event.version === '0.7.3'), false);
  assert.equal(h.state().state.settings.automaticUpdate, true); assert.equal(h.state().state.settings.automaticCheck, false);
  h.protectedIntact();
});

desktopTest('真实新版本 JAR 启动失败时恢复旧程序和数据库，重新打开旧版并阻止自动更新循环', native, async t => {
  const h = await sandbox('failed-boot', { faulty: true }); t.after(h.cleanup);
  await waitFor(() => h.state()?.backendUrl && !h.state().state.startupBusy, 'initial old application healthy');
  const notebook = await api(h, '/api/notebooks', { title: '失败恢复测试' });
  const page = await api(h, `/api/notebooks/${notebook.id}/pages`, { title: '恢复后仍存在', content: noteContent('真实数据库恢复核对') });
  await h.command('settings', { automaticUpdate: true }); await h.command('check'); await h.command('install', '0.8.0');
  const id = await updated(h, 'rolled-back');
  await waitFor(() => h.state()?.version === '0.7.3' && h.state().backendUrl && !h.state().state.startupBusy && h.state().state.failedVersion === '0.8.0', 'restored old JAR healthy');
  assert.deepEqual((await api(h, `/api/note-pages/${page.id}`)).content, page.content);
  assert.equal(read(path.join(h.appRoot, 'config/update-failure.json')).version, '0.8.0');
  assert.equal(h.stats.downloads, 1); h.protectedIntact();
  assert.equal(h.events().filter(event => event.event === 'started' && event.version === '0.8.0').length, 1);
  assert.ok(fs.statSync(path.join(h.appRoot, 'backups/updates', id, 'database/data/study.db')).size > 0);
});

after(async () => {
  if (!directory) return;
  assert.equal(path.dirname(directory), path.join(root, 'temp'));
  fs.rmSync(directory, { recursive: true, force: true, maxRetries: 15, retryDelay: 100 });
});
