// 仅用于原生 Electron 冒烟：真实入口/路径/窗口/单实例锁，隔离 Java 与业务页面。
const { app, BrowserWindow } = require('electron');
const fs = require('node:fs');
const path = require('node:path');
const testRoot = path.resolve(__dirname, '../../tmp');
if (!process.env.APP_ROOT || !path.resolve(process.env.APP_ROOT).startsWith(testRoot + path.sep)) {
  throw new Error('This fixture requires an isolated APP_ROOT inside the workspace tmp directory.');
}
const bridgePath = require.resolve('../../electron-dist/java-bridge.js');
let completeStartup;
let starts = 0;
let stops = 0;
let windowCount = 0;
let activationCount = 0;
const send = (message) => process.connected && process.send(message);

require.cache[bridgePath] = {
  id: bridgePath, filename: bridgePath, loaded: true,
  exports: {
    startBackend(paths) {
      starts++;
      // 重复实例若误启动后端或清理 temp，控制进程可直接发现哨兵被覆盖/删除。
      fs.writeFileSync(path.join(paths.tmp, 'primary-sentinel'), String(process.pid));
      send({ event: 'backend-started', pid: process.pid });
      return new Promise(resolve => {
        completeStartup = () => resolve({ baseUrl: 'http://127.0.0.1:54321' });
      });
    },
    stopBackend() { stops++; send({ event: 'backend-stopped', pid: process.pid }); },
  },
};
BrowserWindow.prototype.loadFile = function () {
  return this.loadURL('data:text/html;charset=utf-8,<title>Single instance smoke test</title><p>Isolated test window</p>');
};
app.on('browser-window-created', () => { windowCount++; });
app.on('second-instance', () => { activationCount++; });

function snapshot() {
  const window = BrowserWindow.getAllWindows()[0];
  return {
    pid: process.pid, starts, stops, windowCount, activationCount,
    ownsLock: app.hasSingleInstanceLock(),
    paths: Object.fromEntries(['userData', 'sessionData', 'cache', 'temp'].map(key => [key, app.getPath(key)])),
    window: window && {
      id: window.id, minimized: window.isMinimized(), visible: window.isVisible(), focused: window.isFocused(),
    },
  };
}

process.on('message', ({ id, command }) => {
  const window = BrowserWindow.getAllWindows()[0];
  if (command === 'complete-startup') completeStartup?.();
  if (command === 'minimize') window?.minimize();
  if (command === 'hide') window?.hide();
  if (command === 'quit') {
    app.quit();
    return;
  }
  send({ id, state: snapshot() });
});

require('../../electron-dist/main.js');
