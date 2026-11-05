import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { once } from 'node:events';
import fs from 'node:fs';
import path from 'node:path';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';
import { setTimeout as delay } from 'node:timers/promises';
import test from 'node:test';

const require = createRequire(import.meta.url);
const root = fileURLToPath(new URL('../', import.meta.url));
const fixture = path.join(root, 'scripts/fixtures/electron-single-instance.cjs');

// 需要 Windows 桌面会话和 npm run build:electron；不运行 Java，不构建发行包。
test('原生锁：重复进程退出，启动中/最小化/隐藏窗口复用，退出后可重开', {
  skip: process.platform !== 'win32', timeout: 60_000,
}, async () => {
  const tempParent = path.join(root, 'tmp');
  fs.mkdirSync(tempParent, { recursive: true });
  const appRoot = fs.mkdtempSync(path.join(tempParent, 'single-instance-'));
  const runtimeTmp = path.join(appRoot, 'runtime/tmp');
  fs.mkdirSync(runtimeTmp, { recursive: true });
  const children = [];
  function launch(targetRoot = appRoot) {
    const targetTmp = path.join(targetRoot, 'runtime/tmp');
    fs.mkdirSync(targetTmp, { recursive: true });
    const env = { ...process.env, APP_ROOT: targetRoot, TEMP: targetTmp, TMP: targetTmp, TMPDIR: targetTmp };
    delete env.ELECTRON_RUN_AS_NODE;
    delete env.ELECTRON_RENDERER_URL;
    const child = spawn(require('electron'), [fixture], {
      cwd: root, env, windowsHide: true, stdio: ['ignore', 'pipe', 'pipe', 'ipc'],
    });
    const events = [];
    let output = '';
    let nextId = 0;
    const pending = new Map();
    child.stdout.on('data', data => { output += data; });
    child.stderr.on('data', data => { output += data; });
    child.on('message', message => {
      events.push(message);
      pending.get(message.id)?.(message.state);
      pending.delete(message.id);
    });
    const exited = once(child, 'exit');
    const client = {
      child, events, exited,
      get output() { return output; },
      async request(command) {
        const id = ++nextId;
        return new Promise((resolve, reject) => {
          const timeout = setTimeout(() => {
            pending.delete(id);
            reject(new Error(`Electron request timed out: ${command}\n${output}`));
          }, 5000);
          pending.set(id, value => { clearTimeout(timeout); resolve(value); });
          child.send({ id, command }, error => {
            if (error) { clearTimeout(timeout); pending.delete(id); reject(error); }
          });
        });
      },
      async waitFor(predicate) {
        const deadline = Date.now() + 10_000;
        while (Date.now() < deadline) {
          assert.equal(child.exitCode, null, `Primary exited unexpectedly\n${output}`);
          const state = await client.request('snapshot');
          if (predicate(state)) return state;
          await delay(50);
        }
        assert.fail(`Native Electron state timed out\n${output}`);
      },
    };
    children.push(client);
    return client;
  }
  async function duplicate(primary, expectedActivations) {
    const second = launch();
    assert.deepEqual(await second.exited, [0, null], second.output);
    assert.equal(second.events.some(event => event.event === 'backend-started' || event.event === 'backend-stopped'), false);
    const state = await primary.waitFor(value => value.activationCount === expectedActivations);
    assert.equal(state.starts, 1);
    assert.equal(state.stops, 0);
    assert.equal(fs.readFileSync(path.join(runtimeTmp, 'primary-sentinel'), 'utf8'), String(primary.child.pid));
    return state;
  }
  try {
    const primary = launch();
    const starting = await primary.waitFor(state => state.starts === 1);
    assert.equal(starting.ownsLock, true);
    for (const directory of Object.values(starting.paths)) {
      assert.ok(directory.startsWith(appRoot + path.sep), `Path escaped APP_ROOT: ${directory}`);
    }
    assert.equal((await duplicate(primary, 1)).windowCount, 0);
    await primary.request('complete-startup');
    const opened = await primary.waitFor(state => state.window?.visible && state.window.focused);
    assert.equal(opened.windowCount, 1);
    await primary.request('minimize');
    await primary.waitFor(state => state.window.minimized);
    await duplicate(primary, 2);
    const restored = await primary.waitFor(state => !state.window.minimized && state.window.focused);
    assert.equal(restored.window.id, opened.window.id);
    await primary.request('hide');
    await primary.waitFor(state => !state.window.visible);
    await duplicate(primary, 3);
    const shown = await primary.waitFor(state => state.window.visible && state.window.focused);
    assert.equal(shown.window.id, opened.window.id);
    assert.equal(shown.windowCount, 1);
    const independent = launch(path.join(appRoot, 'other-root'));
    assert.equal((await independent.waitFor(state => state.starts === 1)).ownsLock, true);
    await independent.request('complete-startup');
    await independent.waitFor(state => state.windowCount === 1);
    independent.child.send({ command: 'quit' });
    assert.deepEqual(await independent.exited, [0, null]);
    assert.equal(fs.readFileSync(path.join(runtimeTmp, 'primary-sentinel'), 'utf8'), String(primary.child.pid));
    primary.child.send({ command: 'quit' });
    assert.deepEqual(await primary.exited, [0, null]);
    assert.equal(primary.events.filter(event => event.event === 'backend-stopped').length, 1);
    assert.equal(fs.existsSync(path.join(runtimeTmp, 'primary-sentinel')), false);
    const reopened = launch();
    assert.equal((await reopened.waitFor(state => state.starts === 1)).ownsLock, true);
    await reopened.request('complete-startup');
    await reopened.waitFor(state => state.windowCount === 1);
    reopened.child.send({ command: 'quit' });
    assert.deepEqual(await reopened.exited, [0, null]);
  } finally {
    for (const { child, exited } of children) {
      if (child.exitCode === null && child.signalCode === null) child.kill();
      await exited;
    }
    // 仅清理本用例通过 mkdtemp 创建的工作区目录；Chromium 子进程可能略晚释放文件。
    assert.equal(path.dirname(appRoot), tempParent);
    fs.rmSync(appRoot, { recursive: true, force: true, maxRetries: 10, retryDelay: 100 });
  }
});
