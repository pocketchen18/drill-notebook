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
  const handlers = new Map();
  const written = new Map();
  const quitDialog = { answer: 1, shown: [] };
  const ready = deferred();
  const backendReady = deferred();
  const warningClosed = deferred();
  const paths = { root: path.join(root, 'temp', 'mock-app'), config: path.join(root, 'temp', 'mock-app', 'config') };
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
      this.sent = [];
      this.webContents = Object.assign(new EventEmitter(), { setWindowOpenHandler() {}, send: (channel, value) => this.sent.push([channel, value]) });
      windows.push(this);
      calls.push('window');
    }
    isDestroyed() { return this.destroyed; }
    isMinimized() { return this.minimized; }
    isVisible() { return this.visible; }
    restore() { this.actions.push('restore'); this.minimized = false; }
    show() { this.actions.push('show'); this.visible = true; }
    focus() { this.actions.push('focus'); this.focused = true; this.emit('focus'); }
    loadFile() { this.actions.push('load'); return Promise.resolve(); }
    loadURL() { this.actions.push('load'); return Promise.resolve(); }
    hide() { this.actions.push('hide'); this.visible = false; this.emit('hide'); }
    maximize() { this.actions.push('maximize'); }
    isMaximized() { return false; }
    isFocused() { return this.focused === true; }
    blur() { this.focused = false; this.emit('blur'); }
    flashFrame(value) { this.actions.push(value ? 'flash' : 'unflash'); }
    getBounds() { return { x: 0, y: 0, width: 1440, height: 920 }; }
    getNormalBounds() { return { x: 100, y: 80, width: 980, height: 760 }; }
    close() {
      let prevented = false;
      this.emit('close', { preventDefault() { prevented = true; } });
      if (prevented) return;
      this.destroyed = true; this.emit('closed');
    }
  }
  const modules = {
    electron: {
      app, BrowserWindow, ipcMain: { handle(channel, handler) { handlers.set(channel, handler); } }, net: {}, shell: {},
      screen: { getAllDisplays: () => [{ workArea: { x: 0, y: 0, width: 1920, height: 1040 } }],
        getDisplayMatching: () => ({ workArea: { x: 0, y: 0, width: 1920, height: 1040 } }),
        getPrimaryDisplay: () => ({ workArea: { x: 0, y: 0, width: 1920, height: 1040 } }) },
      session: { defaultSession: { webRequest: { onHeadersReceived() {}, onBeforeSendHeaders() {} } } },
      dialog: { showMessageBox() { calls.push('warning'); return warningClosed.promise; },
        showMessageBoxSync(_window, options) { quitDialog.shown.push(options); return quitDialog.answer; } },
    },
    'node:fs': { existsSync(file) { return written.has(file); }, readFileSync(file) { return written.get(file); },
      writeFileSync(file, content) { written.set(file, content); } },
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
  // 模拟渲染进程调用：sender 即发起窗口，来源页面按受信入口处理。
  const entryUrl = new URL('../frontend/dist/index.html', import.meta.url).href;
  const invoke = (channel, sender, ...args) => handlers.get(channel)({ sender: sender.webContents, senderFrame: { url: entryUrl + '#/x' } }, ...args);
  return { app, calls, windows, ready, backendReady, warningClosed, handle, invoke, written, quitDialog };
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

test('AI 助手是唯一的独立窗口：重复打开只唤回，关闭只是收起，任务状态保留', async () => {
  const h = await launch();
  const main = h.windows[0];
  await h.invoke('assistant:open', main);
  assert.equal(h.windows.length, 2);
  const assistant = h.windows[1];
  assert.equal(assistant.options.title, 'AI 助手');
  assert.equal(assistant.options.show, false);
  assert.equal('alwaysOnTop' in assistant.options, false, '助手窗口不保持在最前');
  assert.deepEqual([assistant.options.minWidth, assistant.options.minHeight], [420, 520]);
  assert.equal(assistant.options.webPreferences.sandbox, true);
  assert.equal(assistant.options.webPreferences.backgroundThrottling, false);
  // 首次打开落在主窗口所在显示器内
  assert.ok(assistant.options.x >= 0 && assistant.options.x + assistant.options.width <= 1920);
  assistant.emit('ready-to-show');
  assert.deepEqual(assistant.actions, ['load', 'show', 'focus']);

  assistant.close();
  assert.equal(assistant.isDestroyed(), false, '关闭只是收起');
  assert.equal(assistant.visible, false);
  assert.equal((await h.invoke('assistant:get-state', main)).visible, false);
  assert.equal(main.sent.at(-1)[0], 'assistant:state');

  await h.invoke('assistant:open', main);
  assert.equal(h.windows.length, 2, '再次打开复用同一个窗口');
  assert.equal(assistant.visible, true);
  assert.equal(assistant.sent.at(-1)[0], 'assistant:activated');
  assert.equal(h.calls.filter(call => call === 'backend').length, 1);
});

test('窗口位置保存到便携配置目录', async () => {
  const h = await launch();
  const main = h.windows[0];
  await h.invoke('assistant:open', main);
  h.windows[1].close();
  const [file, content] = [...h.written.entries()][0];
  assert.ok(file.split(path.sep).join('/').endsWith('temp/mock-app/config/assistant-window.json'), file);
  assert.deepEqual(JSON.parse(content), { x: 100, y: 80, width: 980, height: 760, maximized: false });
});

test('入口切换：助手在前台时收起，在后台、最小化或已收起时唤到前台', async () => {
  const h = await launch();
  const main = h.windows[0];
  await h.invoke('assistant:toggle', main);
  assert.equal(h.windows.length, 2, '尚未打开时创建窗口');
  const assistant = h.windows[1];
  assistant.emit('ready-to-show');
  assert.equal(assistant.focused, true);

  // 点击主窗口入口会先让助手失去焦点：刚失焦仍按「在前台」处理，收起它。
  assistant.blur();
  await h.invoke('assistant:toggle', main);
  assert.equal(assistant.visible, false, '在前台时点击入口是收起');
  assert.equal(assistant.isDestroyed(), false);

  await h.invoke('assistant:toggle', main);
  assert.equal(assistant.visible, true, '已收起时点击入口是唤起');
  assert.equal(assistant.focused, true);

  // 助手早已在后台（被主窗口盖住）：点击入口把它切到前台，而不是收起。
  assistant.blur();
  await new Promise(resolve => setTimeout(resolve, 800));
  const before = assistant.actions.length;
  await h.invoke('assistant:toggle', main);
  assert.equal(assistant.visible, true);
  assert.deepEqual(assistant.actions.slice(before), ['focus']);

  assistant.blur();
  await new Promise(resolve => setTimeout(resolve, 800));
  assistant.minimized = true;
  await h.invoke('assistant:toggle', main);
  assert.deepEqual(assistant.actions.slice(-2), ['restore', 'focus'], '最小化时还原并聚焦');
  assert.equal(h.windows.length, 2);
});

test('跨窗口消息只转发约定类型且只发给另一个窗口', async () => {
  const h = await launch();
  const main = h.windows[0];
  await h.invoke('assistant:open', main);
  const assistant = h.windows[1];
  await h.invoke('bus:publish', assistant, { type: 'navigate', route: '/notebooks?pageId=3' });
  assert.deepEqual(main.sent.at(-1), ['bus:message', { type: 'navigate', route: '/notebooks?pageId=3' }]);
  const before = assistant.sent.length;
  await h.invoke('bus:publish', main, { type: 'page-context', context: { kind: 'note', title: '导数', markdown: '' } });
  assert.equal(assistant.sent.length, before + 1);
  assert.equal(assistant.sent.at(-1)[1].type, 'page-context');
  const mainCount = main.sent.length;
  await h.invoke('bus:publish', assistant, { type: 'run-shell', command: 'x' });
  await h.invoke('bus:publish', assistant, null);
  await h.invoke('bus:publish', main, { type: 'assistant-activity', running: 9, pending: 9 });
  assert.equal(main.sent.length, mainCount, '未知类型与伪造的运行计数都不转发');
  assert.equal((await h.invoke('assistant:get-state', main)).running, 0);
});

test('仍有回复或待发时关闭主窗口先确认；确认后两个窗口一起退出', async () => {
  const h = await launch();
  const main = h.windows[0];
  await h.invoke('assistant:open', main);
  const assistant = h.windows[1];
  await h.invoke('bus:publish', assistant, { type: 'assistant-activity', running: 2, pending: 1 });
  // 状态对象来自沙箱上下文，按序列化后的值比较。
  assert.equal(JSON.stringify(main.sent.at(-1)), JSON.stringify(['assistant:state', { visible: true, running: 2, pending: 1 }]));

  h.quitDialog.answer = 0;
  main.close();
  assert.equal(main.isDestroyed(), false, '取消后应用继续运行');
  assert.equal(h.quitDialog.shown[0].message, '仍有 2 个会话正在回复');
  assert.equal(h.quitDialog.shown[0].buttons.join('|'), '取消|退出应用');

  h.quitDialog.answer = 1;
  main.close();
  assert.equal(main.isDestroyed(), true);
  assert.equal(assistant.isDestroyed(), true, '助手窗口不单独留下');
});

test('没有任务时关闭主窗口不询问；助手窗口随之关闭', async () => {
  const h = await launch();
  const main = h.windows[0];
  await h.invoke('assistant:open', main);
  h.windows[1].close();
  main.close();
  assert.equal(h.quitDialog.shown.length, 0);
  assert.equal(h.windows[1].isDestroyed(), true);
});
