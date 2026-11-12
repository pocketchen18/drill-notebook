import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { spawn, spawnSync } from 'node:child_process';
import { once } from 'node:events';
import { randomUUID } from 'node:crypto';
import { createRequire } from 'node:module';
import { setTimeout as delay } from 'node:timers/promises';
import test from 'node:test';
import { root, scratch, put, hash, manifestFor, portableFiles, zipBytes } from './fixtures/update-fixtures.mjs';

const native = { skip: process.platform !== 'win32', timeout: 45_000 };
const helper = path.join(root, 'updater/target/release/drill-updater.exe');
const require = createRequire(import.meta.url);
async function waitFor(predicate, explanation, milliseconds = 15_000) {
  const deadline = Date.now() + milliseconds;
  while (Date.now() < deadline) { if (predicate()) return; await delay(25); }
  assert.fail(`Timed out: ${typeof explanation === 'function' ? explanation() : explanation}`);
}
const read = file => { try { return JSON.parse(fs.readFileSync(file, 'utf8')); } catch { return undefined; } };

test('真实 Electron 文件系统把 ASAR 作为原始程序包解压和校验，不误当虚拟目录', native, async t => {
  const appRoot = scratch(t, 'native-asar-');
  const zip = put(appRoot, 'update.zip', await zipBytes(portableFiles()));
  const env = { ...process.env, APP_ROOT: appRoot, TEMP: appRoot, TMP: appRoot }; delete env.ELECTRON_RUN_AS_NODE;
  const result = spawnSync(require('electron'), [path.join(root, 'scripts/fixtures/update-fs-native.cjs'), zip],
    { cwd: root, env, encoding: 'utf8', windowsHide: true, timeout: 15_000 });
  assert.ifError(result.error); assert.equal(result.status, 0, result.stdout + result.stderr);
  assert.ok(result.stdout.includes('RAW-ASAR-OK:0.8.0'), result.stdout);
});

function sandbox(t, failed = false) {
  const processes = [];
  t.after(async () => { for (const { child, exited } of processes) { if (child.exitCode === null && child.signalCode === null) child.kill(); await exited; } });
  const directory = scratch(t, 'native-updater-');
  const appRoot = path.join(directory, 'Drill Notebook 中文目录');
  fs.mkdirSync(appRoot);
  const csc = path.join(process.env.WINDIR ?? 'C:\\Windows', 'Microsoft.NET/Framework64/v4.0.30319/csc.exe');
  for (const [name, define] of [['old.exe', []], ['new.exe', ['/define:NEW_BUILD']]]) {
    const result = spawnSync(csc, ['/nologo', '/target:winexe', '/platform:x64', `/out:${path.join(directory, name)}`, ...define,
      path.join(root, 'scripts/fixtures/portable-update-fixture.cs')], { windowsHide: true, encoding: 'utf8', timeout: 15_000,
      env: { ...process.env, TEMP: directory, TMP: directory } });
    assert.ifError(result.error); assert.equal(result.status, 0, result.stdout + result.stderr);
  }
  const old = portableFiles(fs.readFileSync(path.join(directory, 'old.exe')));
  old['resources/app.asar'] = Buffer.from('old renderer');
  old['resources/backend/app.jar'] = Buffer.from('old backend');
  old['resources/jre/bin/java.exe'] = fs.readFileSync(path.join(directory, 'old.exe'));
  old['resources/updater/drill-updater.exe'] = fs.readFileSync(helper);
  old['resources/obsolete.dll'] = Buffer.from('old obsolete program');
  for (const [name, value] of Object.entries(old)) put(appRoot, name, value);
  const oldManifest = manifestFor(old, '0.7.3'); put(appRoot, 'drill-program-manifest.json', JSON.stringify(oldManifest));
  const next = portableFiles(fs.readFileSync(path.join(directory, 'new.exe')));
  next['resources/jre/bin/java.exe'] = old['resources/jre/bin/java.exe'];
  next['resources/updater/drill-updater.exe'] = old['resources/updater/drill-updater.exe'];
  const id = randomUUID(); const work = path.join(appRoot, 'runtime/updater', id);
  for (const [name, value] of Object.entries(next)) put(work, `staged/${name}`, value);
  const manifest = manifestFor(next); const manifestBytes = Buffer.from(JSON.stringify(manifest)); put(work, 'staged/drill-program-manifest.json', manifestBytes);
  fs.copyFileSync(helper, path.join(work, 'drill-updater.exe'));
  const transaction = { format: 1, id, appRoot, fromVersion: '0.7.3', toVersion: '0.8.0', parentPid: 0, backendPid: 0, bootTimeoutSeconds: 10,
    files: [...manifest.files, { path: 'drill-program-manifest.json', size: manifestBytes.length, sha256: hash(manifestBytes) }], remove: ['resources/obsolete.dll'] };
  const transactionFile = put(work, 'transaction.json', JSON.stringify(transaction));
  for (const [name, value] of Object.entries({ 'data/study.db': 'old-schema-data', 'data/attachments/photo.png': 'photo',
    'data/models/embeddings/model.onnx': 'model', 'config/user-setting.json': 'keep config', 'custom-user-file.txt': 'keep custom' })) put(appRoot, name, value);
  if (failed) put(appRoot, 'config/fixture-fail-new-boot', 'yes');
  const env = { ...process.env, APP_ROOT: appRoot, TEMP: put(appRoot, 'runtime/tmp/.keep', ''), TMP: path.join(appRoot, 'runtime/tmp') };
  env.TEMP = path.join(appRoot, 'runtime/tmp');
  const start = (executable, args) => {
    const child = spawn(executable, args, { cwd: appRoot, env, windowsHide: true, stdio: 'ignore' });
    const exited = once(child, 'exit'); processes.push({ child, exited });
    return { child, exited };
  };
  const launchHelper = (recovery = false) => start(path.join(work, 'drill-updater.exe'), recovery ? ['--recover', transactionFile, '0'] : ['--transaction', transactionFile]);
  const assertPrivateFiles = () => {
    for (const [name, value] of Object.entries({ 'data/attachments/photo.png': 'photo', 'data/models/embeddings/model.onnx': 'model', 'config/user-setting.json': 'keep config', 'custom-user-file.txt': 'keep custom' })) assert.equal(fs.readFileSync(path.join(appRoot, name), 'utf8'), value);
  };
  return { appRoot, directory, work, id, old, next, transaction, transactionFile, launchHelper, start, assertPrivateFiles };
}

test('原生替换等待主程序和 JVM 退出，替换程序、清理废弃程序并自动启动新版', native, async t => {
  const h = sandbox(t);
  const main = h.start(path.join(h.appRoot, 'Drill Notebook.exe'), ['hold', 'main']);
  const java = h.start(path.join(h.appRoot, 'resources/jre/bin/java.exe'), ['hold', 'java']);
  await waitFor(() => fs.existsSync(path.join(h.appRoot, 'runtime/main.ready')) && fs.existsSync(path.join(h.appRoot, 'runtime/java.ready')), 'old main and backend ready');
  h.transaction.parentPid = main.child.pid; h.transaction.backendPid = java.child.pid; put(h.work, 'transaction.json', JSON.stringify(h.transaction));
  const update = h.launchHelper(); await waitFor(() => read(path.join(h.work, 'ready.json'))?.pid === update.child.pid, 'helper verified stage');
  t.diagnostic('旧主程序与 JVM 已就绪，替换程序已校验暂存包');
  assert.equal(fs.readFileSync(path.join(h.appRoot, 'resources/app.asar'), 'utf8'), 'old renderer');
  put(h.appRoot, 'runtime/main.stop', 'stop'); await main.exited; await delay(100);
  assert.equal(fs.readFileSync(path.join(h.appRoot, 'resources/app.asar'), 'utf8'), 'old renderer', 'JVM must also exit before replacement');
  put(h.appRoot, 'runtime/java.stop', 'stop'); await java.exited;
  t.diagnostic('旧主程序与 JVM 均已正常退出');
  await waitFor(() => update.child.exitCode !== null, () => `helper exit: ${JSON.stringify(read(path.join(h.work, 'status.json')))}`);
  assert.deepEqual(await update.exited, [0, null]); assert.equal(read(path.join(h.work, 'status.json')).phase, 'complete');
  assert.equal(fs.readFileSync(path.join(h.appRoot, 'resources/app.asar'), 'utf8'), 'new renderer');
  assert.equal(fs.existsSync(path.join(h.appRoot, 'resources/obsolete.dll')), false);
  assert.equal(fs.readFileSync(path.join(h.appRoot, 'runtime/new-starts.txt'), 'utf8'), 'new\n');
  assert.equal(fs.readFileSync(path.join(h.appRoot, 'data/study.db'), 'utf8'), 'new-schema-data');
  assert.equal(fs.readFileSync(path.join(h.appRoot, 'backups/updates', h.id, 'database/data/study.db'), 'utf8'), 'old-schema-data');
  h.assertPrivateFiles();
});

test('新版本启动失败恢复旧程序和旧数据库，保留迁移后的失败数据并自动重开旧版', native, async t => {
  const h = sandbox(t, true); const update = h.launchHelper();
  assert.deepEqual(await update.exited, [0, null]);
  await waitFor(() => fs.existsSync(path.join(h.appRoot, 'runtime/old-starts.txt')), 'restored application restarted');
  assert.equal(read(path.join(h.work, 'status.json')).phase, 'rolled-back');
  assert.equal(fs.readFileSync(path.join(h.appRoot, 'resources/app.asar'), 'utf8'), 'old renderer');
  assert.equal(fs.readFileSync(path.join(h.appRoot, 'data/study.db'), 'utf8'), 'old-schema-data');
  assert.equal(fs.readFileSync(path.join(h.appRoot, 'backups/updates', h.id, 'failed-data/data/study.db'), 'utf8'), 'new-schema-data');
  assert.equal(read(path.join(h.appRoot, 'config/update-failure.json')).version, '0.8.0');
  h.assertPrivateFiles();
});

test('已确认健康的更新是提交点，再次恢复不能回滚后续编辑', native, async t => {
  const h = sandbox(t); const update = h.launchHelper(); assert.deepEqual(await update.exited, [0, null]);
  await delay(600);
  put(h.appRoot, 'data/study.db', 'user-edited-after-update');
  const journal = read(path.join(h.work, 'status.json')); journal.phase = 'starting'; journal.helperPid = 0; put(h.work, 'status.json', JSON.stringify(journal));
  const recover = h.launchHelper(true); assert.deepEqual(await recover.exited, [0, null]);
  assert.equal(read(path.join(h.work, 'status.json')).phase, 'complete');
  assert.equal(fs.readFileSync(path.join(h.appRoot, 'data/study.db'), 'utf8'), 'user-edited-after-update');
  assert.equal(fs.readFileSync(path.join(h.appRoot, 'resources/app.asar'), 'utf8'), 'new renderer'); h.assertPrivateFiles();
});

test('替换事务被中断时使用完整基线恢复，恢复流程可重复执行', native, async t => {
  const h = sandbox(t); const update = h.launchHelper(); assert.deepEqual(await update.exited, [0, null]); await delay(600);
  fs.rmSync(path.join(h.work, 'health.json'));
  const journal = read(path.join(h.work, 'status.json')); journal.phase = 'installing'; journal.newPid = 0; journal.newBackendPid = 0; journal.helperPid = 0;
  put(h.work, 'status.json', JSON.stringify(journal));
  const recover = h.launchHelper(true); assert.deepEqual(await recover.exited, [0, null]);
  await waitFor(() => fs.existsSync(path.join(h.appRoot, 'runtime/old-starts.txt')), 'old app after interrupted transaction');
  assert.equal(fs.readFileSync(path.join(h.appRoot, 'data/study.db'), 'utf8'), 'old-schema-data');
  assert.equal(fs.readFileSync(path.join(h.appRoot, 'resources/app.asar'), 'utf8'), 'old renderer');
  const again = h.launchHelper(true); assert.deepEqual(await again.exited, [0, null]);
  assert.equal(fs.readFileSync(path.join(h.appRoot, 'data/study.db'), 'utf8'), 'old-schema-data'); h.assertPrivateFiles();
});
