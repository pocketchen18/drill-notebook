import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import test from 'node:test';
import { root, sourceModule, scratch, put, hash, portableFiles, manifestFor, zipBytes, releaseRaw } from './fixtures/update-fixtures.mjs';

const { AppUpdater, DEFAULT_UPDATE_SETTINGS } = sourceModule('app-updater');
const { releaseOf, fetchReleases, selectNotes, selectPending, assetUrlAllowed } = sourceModule('update-feed');
const { validateManifest, safeRelativePath, assertNoLinks, writeAtomicJson } = sourceModule('program-manifest');
const { extractUpdateArchive } = sourceModule('update-archive');
const { AssistantDraftStore } = sourceModule('assistant-drafts');
const tick = () => new Promise(resolve => setImmediate(resolve));

function fixture(t, options = {}) {
  const directory = options.directory ?? scratch(t);
  fs.mkdirSync(path.join(directory, 'runtime'), { recursive: true });
  const states = [];
  let now = options.now ?? 1_700_000_000_000;
  let feed = options.feed ?? [releaseRaw('0.8.0'), releaseRaw('0.7.3'), releaseRaw('0.7.2')];
  let failure;
  const requests = [];
  const updater = new AppUpdater({
    paths: { root: directory }, currentVersion: options.version ?? '0.7.3', packaged: options.packaged ?? true,
    platform: 'win32', arch: 'x64', executable: path.join(directory, 'Drill Notebook.exe'),
    helper: put(directory, 'resources/updater/drill-updater.exe', 'helper'),
    now: () => now, onState: state => { states.push(state); options.onState?.(state, updater); },
    prepare: options.prepare ?? (async () => ({ ok: true })), commit: options.commit ?? (async () => {}),
    processIds: () => ({ main: 12345, backend: 54321 }),
    fetch: async (url, init) => {
      requests.push(url);
      if (url.includes('api.github.com')) {
        if (failure) throw failure;
        if (options.feedWait) await options.feedWait;
        return Response.json(feed);
      }
      if (options.assetResponse) return options.assetResponse(url, init);
      return new Response(options.bytes);
    },
  });
  t.after(() => updater.dispose());
  return { updater, directory, requests, states, setNow: value => { now = value; },
    setFeed: value => { feed = value; }, fail: value => { failure = value; } };
}

test('语义版本选择兼容 v0 预发行、数字排序，排除 draft 与错误平台资产', async () => {
  const releases = await fetchReleases(async () => Response.json([
    releaseRaw('0.7.9'), releaseRaw('0.7.10'), releaseRaw('0.8.0-rc.1'), releaseRaw('0.8.0', null, { draft: true }),
    { tag_name: 'nightly' },
  ]));
  assert.deepEqual(releases.map(r => r.version), ['0.8.0-rc.1', '0.7.10', '0.7.9']);
  assert.ok(releases.every(r => r.prerelease));
  const wrong = releaseRaw('0.8.0', Buffer.from('asset'));
  wrong.assets[0].name = 'Drill-Notebook-0.8.0-linux-x64.zip';
  assert.equal(releaseOf(wrong).asset, undefined);
  assert.equal(assetUrlAllowed('https://github.com/qilin-software/drill-notebook/releases/download/v0.8.0/a.zip', 'v0.8.0'), true);
  assert.equal(assetUrlAllowed('https://github.com/another/repo/releases/download/v0.8.0/a.zip', 'v0.8.0'), false);
  assert.equal(assetUrlAllowed('https://github.com/qilin-software/drill-notebook/releases/download/v0.7.3/a.zip', 'v0.8.0'), false);
});

test('Release 分页读取并保留每版原正文，限流与错误响应不冒充最新版', async () => {
  let pages = 0;
  const result = await fetchReleases(async url => {
    pages++;
    assert.equal(new URL(url).pathname, '/repos/qilin-software/drill-notebook/releases');
    assert.match(url, /per_page=100&page=/);
    return Response.json(pages === 1 ? Array.from({ length: 100 }, (_, i) => releaseRaw(`0.6.${i}`)) : [releaseRaw('0.7.3')]);
  });
  assert.equal(pages, 2); assert.equal(result.length, 101); assert.match(result[0].body, /完整的更新内容/);
  await assert.rejects(fetchReleases(async () => new Response('', { status: 429 })), /限流/);
  await assert.rejects(fetchReleases(async () => Response.json({ unexpected: true })), /列表无效/);
});

test('历史 N 范围始终以安装版为上限、最多 N+1，since 与待安装列表分别筛选', () => {
  const releases = Array.from({ length: 120 }, (_, i) => releaseOf(releaseRaw(`0.7.${i}`)));
  assert.deepEqual(selectNotes(releases, '0.7.100', 10).map(r => r.version), Array.from({ length: 11 }, (_, i) => `0.7.${100 - i}`));
  assert.equal(selectNotes(releases, '0.7.100', 100).length, 101);
  assert.deepEqual(selectNotes(releases, '0.7.100', 'since', '0.7.98', '0.7.102').map(r => r.version), ['0.7.102', '0.7.101', '0.7.100', '0.7.99']);
  assert.deepEqual(selectNotes(releases, '0.7.100', 'since').map(r => r.version), ['0.7.100']);
  assert.deepEqual(selectPending(releases, '0.7.100', '0.7.102').map(r => r.version), ['0.7.102', '0.7.101']);
});

test('默认设置与保存校验、立即改间隔保留真实检查和提醒时间', async t => {
  const h = fixture(t);
  assert.deepEqual(h.updater.getState().settings, DEFAULT_UPDATE_SETTINGS);
  await h.updater.check();
  const before = h.updater.getState();
  h.updater.setSettings({ intervalHours: 6, automaticUpdate: true });
  assert.equal(h.updater.getState().lastCheckAt, before.lastCheckAt);
  assert.equal(h.updater.getState().lastReminderAt, before.lastReminderAt);
  for (const intervalHours of [0, 169, 1.5, '24', null]) assert.throws(() => h.updater.setSettings({ intervalHours }), /整数小时/);
  assert.throws(() => h.updater.setSettings({ unknown: true }), /未知字段/);
  assert.equal(h.requests.length, 1, '运行中开启自动更新只保存偏好');
  const settings = JSON.parse(fs.readFileSync(path.join(h.directory, 'config/update-settings.json'), 'utf8'));
  assert.equal(settings.settings.automaticUpdate, true);
});

test('手动与后台并发检查合并，手动发现新版展示一次说明', async t => {
  let release;
  const h = fixture(t, { feedWait: new Promise(resolve => { release = resolve; }) });
  const checks = [h.updater.check('background'), h.updater.check(), h.updater.check()];
  assert.equal(h.requests.length, 1); release(); await Promise.all(checks);
  assert.equal(h.updater.getState().presentation.kind, 'available');
  assert.equal(h.updater.getState().updateVisible, true);
});

test('关闭说明保留顶部入口、跳过持久化、手动忽略跳过、恢复提醒', async t => {
  const h = fixture(t);
  await h.updater.check();
  h.updater.closePresentation(h.updater.getState().presentation.id);
  assert.equal(h.updater.getState().updateVisible, true);
  h.updater.skip('0.8.0');
  assert.equal(h.updater.getState().updateVisible, false);
  await h.updater.check();
  assert.equal(h.updater.getState().presentation.kind, 'available');
  assert.equal(h.updater.getState().skippedVersion, '0.8.0');
  assert.equal(h.updater.getState().updateVisible, false);
  h.updater.restoreReminder(); assert.equal(h.updater.getState().updateVisible, true);
  h.setFeed([releaseRaw('0.8.1'), releaseRaw('0.8.0')]);
  h.updater.skip('0.8.0'); await h.updater.check('background');
  assert.equal(h.updater.getState().target.version, '0.8.1'); assert.equal(h.updater.getState().updateVisible, true);
});

test('启动提醒和检查共享间隔，短时间重复启动不弹，满间隔后再提醒', async t => {
  const h = fixture(t); await h.updater.startup();
  const first = h.updater.getState(); assert.equal(first.presentation.kind, 'available'); h.updater.dispose();
  const short = fixture(t, { directory: h.directory, now: first.lastReminderAt + 5 * 60_000 });
  await short.updater.startup();
  assert.equal(short.requests.length, 0); assert.equal(short.updater.getState().presentation, undefined);
  assert.equal(short.updater.getState().updateVisible, true); short.updater.dispose();
  const due = fixture(t, { directory: h.directory, now: first.lastReminderAt + 24 * 3600_000 });
  await due.updater.startup(); assert.equal(due.requests.length, 1); assert.equal(due.updater.getState().presentation.kind, 'available');
});

test('后台周期检查只显示图标，关闭自动检查暂停启动提示，手动仍可检查', async t => {
  const h = fixture(t); h.updater.setSettings({ automaticCheck: false }); await h.updater.startup();
  assert.equal(h.requests.length, 0); assert.equal(h.updater.getState().presentation, undefined);
  await h.updater.check('background'); assert.equal(h.updater.getState().presentation, undefined); assert.equal(h.updater.getState().updateVisible, true);
  await h.updater.check(); assert.equal(h.updater.getState().presentation.kind, 'available');
});

test('上次成功运行版只在成功启动推进，历史入口和范围切换不推进检查或提醒时间', async t => {
  const h = fixture(t); await h.updater.check(); h.updater.recordSuccessfulBoot(); h.updater.dispose();
  const newer = fixture(t, { directory: h.directory, version: '0.8.0', now: 1_700_000_001_000 });
  assert.equal(newer.updater.getState().sinceVersion, '0.7.3');
  const before = newer.updater.getState(); newer.updater.openHistory();
  const notes = await newer.updater.notes('since'); assert.deepEqual(notes.releases.map(r => r.version), ['0.8.0']);
  await newer.updater.notes(10, undefined, true);
  assert.equal(newer.updater.getState().lastCheckAt, before.lastCheckAt);
  assert.equal(newer.updater.getState().lastReminderAt, before.lastReminderAt);
});

test('检查失败保留缓存和真实错误，不能自动弹出过期内容', async t => {
  const h = fixture(t); await h.updater.check(); h.updater.dispose();
  const failed = fixture(t, { directory: h.directory, now: 1_700_100_000_000 }); failed.fail(new Error('offline'));
  await failed.updater.startup();
  assert.equal(failed.updater.getState().checkStatus, 'error'); assert.equal(failed.updater.getState().presentation, undefined);
  const notes = await failed.updater.notes(10, undefined, true);
  assert.equal(notes.cached, true); assert.equal(notes.error, 'offline'); assert.ok(notes.releases.every(r => r.version !== '0.8.0'));
  assert.equal(failed.updater.getState().lastCheckAt, 1_700_000_000_000);
});

test('被篡改的资产缓存不能将下载文件写出缓存目录', async t => {
  const directory = scratch(t);
  const r = releaseOf(releaseRaw('0.8.0', Buffer.from('zip'))); r.asset.name = '../../data/study.db';
  put(directory, 'cache/updates/releases.json', JSON.stringify({ format: 1, at: 1_700_000_000_000, releases: [r] }));
  put(directory, 'config/update-settings.json', JSON.stringify({ format: 1, settings: DEFAULT_UPDATE_SETTINGS, latestVersion: '0.8.0' }));
  const h = fixture(t, { directory }); assert.equal(h.updater.getState().target, undefined);
});

test('程序文件清单拒绝用户数据、Win32别名、重复和文件目录冲突', () => {
  for (const name of ['../data/study.db', 'resources/../data/study.db', 'resources/NUL.txt', 'resources/app.asar:ads', 'resources/a.', 'resources\\app.asar']) assert.equal(safeRelativePath(name), false, name);
  const files = portableFiles();
  for (const name of ['data/study.db', 'config/update-settings.json', 'resources/app.asar/child']) {
    assert.throws(() => validateManifest(manifestFor({ ...files, [name]: Buffer.from('wrong') })), /安全|冲突/);
  }
  assert.throws(() => validateManifest(manifestFor({ ...files, 'resources/App.asar': Buffer.from('duplicate') })), /重复|冲突/);
  assert.throws(() => validateManifest(manifestFor(files), '0.8.1'), /版本/);
});

test('真实 ZIP 双重校验与逐文件 SHA-256 解压，清单外内容不写入暂存', async t => {
  const directory = scratch(t); const files = portableFiles(); const zip = await zipBytes(files);
  const archive = put(directory, 'update.zip', zip); const stage = path.join(directory, 'staged');
  const manifest = await extractUpdateArchive(archive, stage, '0.8.0'); assert.equal(manifest.files.length, 6);
  assert.equal(fs.readFileSync(path.join(stage, 'resources/app.asar'), 'utf8'), 'new renderer');
  const extra = await zipBytes(files, { extra: [['resources/unknown.txt', 'unlisted']] });
  const badStage = path.join(directory, 'bad-staged');
  await assert.rejects(extractUpdateArchive(put(directory, 'extra.zip', extra), badStage, '0.8.0'), /清单不一致/);
  assert.equal(fs.existsSync(badStage), false);
});

test('ZIP 拒绝大小写冲突、符号链接、缺失清单与文件损坏', async t => {
  const directory = scratch(t); const files = portableFiles();
  const cases = [
    await zipBytes(files, { extra: [['resources/App.asar', 'duplicate']] }),
    await zipBytes(files, { extra: [['resources/link', 'target', { mode: 0o120777 }]] }),
    await zipBytes(files, { manifest: null }),
    await zipBytes({ ...files, 'resources/app.asar': Buffer.from('bad renderer') }, { manifest: manifestFor(files) }),
  ];
  for (let i = 0; i < cases.length; i++) await assert.rejects(extractUpdateArchive(put(directory, `invalid-${i}.zip`, cases[i]), path.join(directory, `stage-${i}`), '0.8.0'));
});

test('本地链接路径与原子配置暂存不能污染其他文件', t => {
  const directory = scratch(t); const outside = put(directory, 'untouched.txt', 'keep');
  fs.mkdirSync(path.join(directory, 'config'));
  fs.symlinkSync(directory, path.join(directory, 'config/link'), process.platform === 'win32' ? 'junction' : 'dir');
  assert.throws(() => assertNoLinks(directory, 'config/link/untouched.txt'), /链接|联接/);
  fs.linkSync(outside, path.join(directory, 'config/prefs.json.new'));
  writeAtomicJson(path.join(directory, 'config/prefs.json'), { good: true });
  assert.equal(fs.readFileSync(outside, 'utf8'), 'keep');
});

test('一次操作下载、校验、等待保存并交接自动重启，目标冻结、关闭进度不取消', async t => {
  const bytes = await zipBytes(portableFiles()); let commitFile; let prepares = 0;
  const h = fixture(t, { bytes, feed: [releaseRaw('0.8.0', bytes)],
    prepare: async () => ({ ok: ++prepares > 1, reason: '保存尚未完成' }),
    onState: (state, updater) => {
      if (state.progress.phase === 'downloading' && state.presentation) updater.closePresentation(state.presentation.id);
      if (state.progress.phase === 'blocked') queueMicrotask(() => updater.resumeInstall());
    }, commit: async file => { commitFile = file; } });
  put(h.directory, 'data/study.db', 'private database'); put(h.directory, 'data/models/model.onnx', 'private model');
  await h.updater.check('background'); await h.updater.install('0.8.0');
  assert.equal(prepares, 2); assert.equal(h.updater.getState().progress.phase, 'restarting'); assert.equal(h.updater.getState().presentation, undefined);
  const transaction = JSON.parse(fs.readFileSync(commitFile, 'utf8'));
  assert.equal(transaction.toVersion, '0.8.0'); assert.ok(transaction.files.every(file => !file.path.startsWith('data/') && !file.path.startsWith('config/')));
  assert.equal(fs.readFileSync(path.join(h.directory, 'data/study.db'), 'utf8'), 'private database');
  assert.equal(fs.readFileSync(path.join(h.directory, 'data/models/model.onnx'), 'utf8'), 'private model');
});

test('最后退出检查遇到新任务时等待并重试交接，不重新下载', async t => {
  const bytes = await zipBytes(portableFiles()); let commits = 0;
  const h = fixture(t, { bytes, feed: [releaseRaw('0.8.0', bytes)], commit: async () => {
    if (++commits === 1) throw Object.assign(new Error('新任务刚开始'), { code: 'UPDATE_BUSY' });
  }, onState: (state, updater) => { if (state.progress.phase === 'blocked') queueMicrotask(() => updater.resumeInstall()); } });
  await h.updater.check('background'); await h.updater.install('0.8.0');
  assert.equal(commits, 2); assert.equal(h.requests.filter(url => !url.includes('api.github.com')).length, 1);
  assert.equal(h.updater.getState().progress.phase, 'restarting');
});

test('取消下载停止事务与重启、清理自己创建的暂存', async t => {
  const bytes = await zipBytes(portableFiles()); let committed = false;
  const h = fixture(t, { feed: [releaseRaw('0.8.0', bytes)], commit: async () => { committed = true; },
    assetResponse: async (_url, init) => {
      queueMicrotask(() => h.updater.cancelDownload());
      return new Promise((_resolve, reject) => init.signal.addEventListener('abort', () => reject(new Error('cancelled')), { once: true }));
    } });
  await h.updater.check('background'); await h.updater.install('0.8.0');
  assert.equal(committed, false); assert.equal(h.updater.getState().progress.phase, 'idle');
  assert.equal(fs.readdirSync(path.join(h.directory, 'cache/updates')).filter(name => name !== 'releases.json').length, 0);
  assert.equal(fs.existsSync(path.join(h.directory, 'runtime/updater/active.json')), false);
});

test('包摘要错误、缺校验与开发模式均不替换，失败原程序保留', async t => {
  const bytes = await zipBytes(portableFiles());
  const wrong = releaseRaw('0.8.0', bytes); wrong.assets[0].digest = 'sha256:' + '0'.repeat(64);
  let committed = false;
  const h = fixture(t, { bytes, feed: [wrong], commit: async () => { committed = true; } });
  put(h.directory, 'Drill Notebook.exe', 'old executable'); await h.updater.check('background'); await h.updater.install('0.8.0');
  assert.equal(committed, false); assert.equal(h.updater.getState().progress.phase, 'failed'); assert.match(h.updater.getState().progress.message, /SHA-256/);
  assert.equal(fs.readFileSync(path.join(h.directory, 'Drill Notebook.exe'), 'utf8'), 'old executable');
  const dev = fixture(t, { bytes, feed: [releaseRaw('0.8.0', bytes)], packaged: false });
  await dev.updater.check('background'); await dev.updater.install('0.8.0');
  assert.match(dev.updater.getState().progress.message, /开发模式/);
  const unavailable = releaseRaw('0.8.0', bytes); delete unavailable.assets[0].digest;
  const missing = fixture(t, { bytes, feed: [unavailable] }); await missing.updater.check('background'); await missing.updater.install('0.8.0');
  assert.match(missing.updater.getState().progress.message, /SHA-256/);
});

test('启动自动更新忽略间隔与自动检查开关，跳过和失败版阻止自动重启循环', async t => {
  const bytes = await zipBytes(portableFiles()); let commits = 0;
  const h = fixture(t, { bytes, feed: [releaseRaw('0.8.0', bytes)], commit: async () => { commits++; } });
  await h.updater.check('background'); h.updater.setSettings({ automaticUpdate: true, automaticCheck: false });
  assert.equal(commits, 0); await h.updater.startup(); assert.equal(commits, 1); assert.equal(h.requests.filter(url => url.includes('api.github.com')).length, 2);
  h.updater.dispose();
  const skipped = fixture(t, { directory: h.directory, bytes, feed: [releaseRaw('0.8.0', bytes)], commit: async () => { commits++; } });
  skipped.updater.skip('0.8.0'); await skipped.updater.startup(); assert.equal(commits, 1); skipped.updater.dispose();
  put(h.directory, 'config/update-failure.json', JSON.stringify({ format: 1, id: '11111111-1111-4111-8111-111111111111', version: '0.8.0', error: 'new boot failed' }));
  const failed = fixture(t, { directory: h.directory, bytes, feed: [releaseRaw('0.8.0', bytes)], commit: async () => { commits++; } });
  failed.updater.restoreReminder(); await failed.updater.startup(); assert.equal(commits, 1); assert.equal(failed.updater.getState().failedVersion, '0.8.0');
  await failed.updater.install('0.8.0'); assert.equal(commits, 2); assert.equal(fs.existsSync(path.join(h.directory, 'config/update-failure.json')), false);
});

test('助手草稿加密落在 APP_ROOT，跨实例恢复文本与附件，损坏不覆盖原文件', t => {
  const directory = scratch(t); fs.mkdirSync(path.join(directory, 'config'));
  const draft = { format: 1, drafts: [{ message: '私密未发送提问', attachments: [{ value: '私人附件' }] }] };
  const store = new AssistantDraftStore(directory); assert.equal(store.read(), null); store.save(draft);
  const file = path.join(directory, 'config/assistant-drafts.json'); const source = fs.readFileSync(file, 'utf8');
  assert.equal(source.includes('私密'), false); assert.deepEqual(new AssistantDraftStore(directory).read(), draft);
  const invalid = JSON.parse(source); invalid.tag = Buffer.alloc(16).toString('base64'); fs.writeFileSync(file, JSON.stringify(invalid));
  assert.throws(() => store.read()); assert.equal(fs.readFileSync(file, 'utf8'), JSON.stringify(invalid));
});

test('发布程序清单和 ZIP 校验文件可供实际更新器读取', async t => {
  const directory = scratch(t); const { generateManifest, generateChecksum } = await import('./generate-update-metadata.mjs');
  const files = portableFiles(); for (const [name, value] of Object.entries(files)) put(directory, name, value);
  const manifest = await generateManifest(directory, '0.8.0'); validateManifest(manifest, '0.8.0');
  const archive = put(directory, 'Drill-Notebook-0.8.0-win-x64-portable.zip', await zipBytes(files));
  const checksum = await generateChecksum(archive); assert.match(checksum, /^[a-f0-9]{64}  Drill-Notebook-0.8.0-win-x64-portable.zip\n$/);
  assert.equal(checksum.slice(0, 64), hash(fs.readFileSync(archive)));
});
