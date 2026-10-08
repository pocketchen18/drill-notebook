import assert from 'node:assert/strict';
import { EventEmitter } from 'node:events';
import fs from 'node:fs';
import path from 'node:path';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';
import vm from 'node:vm';
import test from 'node:test';
import ts from 'typescript';

const require = createRequire(import.meta.url);
const root = fileURLToPath(new URL('../', import.meta.url));
// 在内存中执行真实入口；不依赖 electron-dist，不生成发行产物。
const source = ts.transpileModule(fs.readFileSync(path.join(root, 'electron/main.ts'), 'utf8'), {
  compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.CommonJS, esModuleInterop: true }
}).outputText;

function deferred() {
  let resolve;
  let reject;
  const promise = new Promise((ok, fail) => { resolve = ok; reject = fail; });
  return { promise, resolve, reject };
}

const flush = () => new Promise(resolve => setImmediate(resolve));

function boot({ ownsLock = true } = {}) {
  const calls = [];
  const windows = [];
  const ready = deferred();
  const backendReady = deferred();
  const warningClosed = deferred();
  const paths = { root: path.join(root, 'tmp', 'mock-app'), config: path.join(root, 'tmp', 'mock-app', 'config') };
  const handle = { baseUrl: 'http://127.0.0.1:54321' };
  const app = new EventEmitter();
  Object.assign(app, {
    isPackaged: false,
    requestSingleInstanceLock() { calls.push('lock'); return ownsLock; },
    whenReady() { calls.push('ready'); return ready.promise; },
    quit() { calls.push('quit'); app.emit('before-quit'); },
  });
  class BrowserWindow extends EventEmitter {
    constructor(options) {
      super();
      this.options = options;
      this.destroyed = false;
      this.minimized = false;
      this.visible = true;
      this.actions = [];
      this.webContents = Object.assign(new EventEmitter(), { setWindowOpenHandler() {} });
      windows.push(this);
      calls.push('window');
    }
    isDestroyed() { return this.destroyed; }
    isMinimized() { return this.minimized; }
    isVisible() { return this.visible; }
    restore() { this.actions.push('restore'); this.minimized = false; }
    show() { this.actions.push('show'); this.visible = true; }
    focus() { this.actions.push('focus'); }
    loadFile() { this.actions.push('load'); return Promise.resolve(); }
    loadURL() { this.actions.push('load'); return Promise.resolve(); }
    close() { this.destroyed = true; this.emit('closed'); }
  }
  const modules = {
    electron: {
      app, BrowserWindow, ipcMain: { handle() {} }, net: {}, shell: {},
      session: { defaultSession: { webRequest: { onHeadersReceived() {}, onBeforeSendHeaders() {} } } },
      dialog: { showMessageBox() { calls.push('warning'); return warningClosed.promise; } },
    },
    'node:fs': { existsSync() { return false; } },
    './paths': {
      setupPortablePaths() { calls.push('paths'); return paths; },
      getPortablePaths(rootPath) { assert.equal(rootPath, paths.root); return paths; },
      clearPortableTemp(value) { assert.equal(value, paths); calls.push('clear'); },
    },
    './java-bridge': {
      startBackend(value) { assert.equal(value, paths); calls.push('backend'); return backendReady.promise; },
      stopBackend(value) { calls.push(['stop', value]); },
    },
  };
  vm.runInNewContext(source, {
    exports: {}, __dirname: path.join(root, 'electron'),
    require(id) { return modules[id] ?? require(id); },
    process: { platform: 'win32', env: {} },
    console: { error() {} }, URL,
  }, { filename: 'electron/main.ts' });
  return { app, calls, windows, ready, backendReady, warningClosed, handle };
}

async function launch() {
  const harness = boot();
  harness.ready.resolve();
  await flush();
  harness.backendReady.resolve(harness.handle);
  await flush();
  assert.equal(harness.windows.length, 1);
  return harness;
}

test('便携路径先于锁，只有主实例在 ready 后启动一次后端', async () => {
  const h = boot();
  assert.deepEqual(h.calls, ['paths', 'lock', 'ready']);
  h.ready.resolve();
  await flush();
  assert.deepEqual(h.calls, ['paths', 'lock', 'ready', 'backend']);
  h.backendReady.resolve(h.handle);
  await flush();
  assert.equal(h.windows.length, 1);
});

test('未获锁的实例只退出，不启动后端/窗口，也不清理主实例资源', async () => {
  const h = boot({ ownsLock: false });
  h.ready.resolve();
  h.backendReady.resolve(h.handle);
  await flush();
  h.app.emit('before-quit');
  h.app.emit('second-instance');
  assert.deepEqual(h.calls, ['paths', 'lock', 'quit']);
  assert.equal(h.windows.length, 0);
});

test('重复启动恢复最小化窗口并聚焦，不重建窗口或后端', async () => {
  const h = await launch();
  const window = h.windows[0];
  window.actions = [];
  window.minimized = true;
  h.app.emit('second-instance');
  assert.deepEqual(window.actions, ['restore', 'focus']);
  assert.equal(h.windows.length, 1);
  assert.equal(h.calls.filter(call => call === 'backend').length, 1);
});

test('隐藏窗口会显示并聚焦，已显示窗口只聚焦', async () => {
  const h = await launch();
  const window = h.windows[0];
  window.actions = [];
  window.visible = false;
  h.app.emit('second-instance');
  h.app.emit('second-instance');
  assert.deepEqual(window.actions, ['show', 'focus', 'focus']);
  assert.equal(h.windows.length, 1);
});

test('后端启动期间重复启动，待主窗口创建后兑现唤起请求', async () => {
  const h = boot();
  h.ready.resolve();
  await flush();
  h.app.emit('second-instance');
  h.app.emit('second-instance');
  assert.equal(h.windows.length, 0);
  h.backendReady.resolve(h.handle);
  await flush();
  assert.equal(h.windows.length, 1);
  assert.deepEqual(h.windows[0].actions, ['load', 'focus']);
  assert.equal(h.calls.filter(call => call === 'backend').length, 1);
});

test('后端启动失败的提示关闭后仍打开并唤起唯一主窗口', async () => {
  const h = boot();
  h.ready.resolve();
  await flush();
  h.backendReady.reject(new Error('startup failed'));
  await flush();
  assert.ok(h.calls.includes('warning'));
  h.app.emit('second-instance');
  h.warningClosed.resolve({ response: 0 });
  await flush();
  assert.equal(h.windows.length, 1);
  assert.deepEqual(h.windows[0].actions, ['load', 'focus']);
});

test('窗口关闭后不再操作已销毁对象，主实例退出仍回收自己的资源', async () => {
  const h = await launch();
  const window = h.windows[0];
  window.close();
  window.actions = [];
  h.app.emit('second-instance');
  assert.deepEqual(window.actions, []);
  h.app.emit('window-all-closed');
  assert.deepEqual(h.calls.slice(-3), ['quit', ['stop', h.handle], 'clear']);
});
