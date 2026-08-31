import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const workspace = fileURLToPath(new URL('..', import.meta.url));
const port = Number(process.argv[2] || 9240);
const mode = process.argv[3] || 'baseline';
const backendBase = process.argv[4] || 'http://127.0.0.1:18081';
const evidenceRoot = path.join(
  workspace,
  '.omo',
  'evidence',
  'notebook-bank-workspace-redesign',
  mode === 'baseline' ? 'task-1-baseline' : 'task-2-design-system',
  mode === 'baseline' ? 'electron' : 'geometry',
);
fs.mkdirSync(evidenceRoot, { recursive: true });

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

// 全局看门狗：任何 CDP 命令/求值挂死都不允许脚本无限运行
setTimeout(() => {
  console.error(JSON.stringify({ mode, passed: false, fatal: 'watchdog: script exceeded 150s, aborting' }, null, 2));
  process.exit(2);
}, 150000).unref();

async function findPage() {
  for (let attempt = 0; attempt < 60; attempt += 1) {
    try {
      const targets = await (await fetch(`http://127.0.0.1:${port}/json`)).json();
      const page = targets.find((item) => item.type === 'page' && item.webSocketDebuggerUrl);
      if (page) return page;
    } catch (error) {
      if (!(error instanceof TypeError)) throw error;
    }
    await sleep(500);
  }
  throw new Error(`No Electron page found on port ${port}`);
}

const target = await findPage();
const socket = new WebSocket(target.webSocketDebuggerUrl);
const pending = new Map();
const exceptions = [];
let messageId = 0;

socket.addEventListener('message', (event) => {
  const message = JSON.parse(event.data);
  if (message.method === 'Runtime.exceptionThrown') {
    exceptions.push(message.params.exceptionDetails?.text || 'runtime exception');
  }
  const resolve = pending.get(message.id);
  if (resolve) {
    pending.delete(message.id);
    resolve(message);
  }
});
await new Promise((resolve, reject) => {
  socket.addEventListener('open', resolve, { once: true });
  socket.addEventListener('error', reject, { once: true });
});

function command(method, params = {}) {
  const id = ++messageId;
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => {
      pending.delete(id);
      reject(new Error(`CDP command timed out after 20s: ${method}`));
    }, 20000);
    pending.set(id, (message) => {
      clearTimeout(timer);
      if (message.error) reject(new Error(message.error.message));
      else resolve(message);
    });
    socket.send(JSON.stringify({ id, method, params }));
  });
}

async function evaluate(expression) {
  const response = await command('Runtime.evaluate', {
    expression,
    returnByValue: true,
    awaitPromise: true,
  });
  if (response.result?.exceptionDetails) {
    throw new Error(response.result.exceptionDetails.exception?.description || response.result.exceptionDetails.text || 'evaluation failed');
  }
  return response.result?.result?.value;
}

async function setViewport(width, height) {
  await command('Emulation.setDeviceMetricsOverride', {
    width,
    height,
    deviceScaleFactor: 1,
    mobile: false,
  });
}

async function waitForText(text) {
  for (let attempt = 0; attempt < 40; attempt += 1) {
    if (await evaluate(`document.body.innerText.includes(${JSON.stringify(text)})`)) return;
    await sleep(250);
  }
  throw new Error(`Timed out waiting for ${text}`);
}

async function setTheme(theme) {
  const current = await evaluate("document.documentElement.dataset.theme || 'light'");
  if (current !== theme) {
    await evaluate("document.querySelector('[aria-label=\"切换主题\"]')?.click(); true");
    await sleep(250);
  }
}

async function ensureFixtures() {
  await evaluate(`(async () => {
    const base = window.api?.backend ? await window.api.backend.getBaseUrl() : ${JSON.stringify(backendBase)};
    const notebooks = await fetch(base + '/api/notebooks').then((response) => response.json());
    const notebook = notebooks[0];
    const pages = await fetch(base + '/api/notebooks/' + notebook.id + '/pages').then((response) => response.json());
    for (const title of ['操作系统复习', '调度算法整理', '同步与互斥']) {
      if (!pages.some((page) => page.title === title)) {
        await fetch(base + '/api/notebooks/' + notebook.id + '/pages', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ title, content: { type: 'doc', content: [{ type: 'paragraph', content: [{ type: 'text', text: title + '：记录关键概念与例题。' }] }] } }),
        });
      }
    }
    let banks = await fetch(base + '/api/banks').then((response) => response.json());
    let bank = banks.find((item) => item.name === '操作系统');
    if (!bank) {
      bank = await fetch(base + '/api/banks', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ name: '操作系统', description: '进程、内存与文件系统', sourceType: 'manual' }),
      }).then((response) => response.json());
    }
    const questions = await fetch(base + '/api/banks/' + bank.id + '/questions').then((response) => response.json());
    if (questions.length === 0) {
      for (const [stem, answer] of [['以下哪项属于进程调度算法？', 'B'], ['分页存储管理的主要目的是什么？', 'A'], ['临界区互斥需要满足哪些条件？', 'C']]) {
        await fetch(base + '/api/banks/' + bank.id + '/questions', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ type: 'single', stem, options: [{ key: 'A', text: '提高内存利用率' }, { key: 'B', text: '时间片轮转' }, { key: 'C', text: '互斥、进步与有限等待' }], answer, chapter: '操作系统基础', analysis: '基线夹具' }),
        });
      }
    }
    return true;
  })()`);
}

async function routeTo(hash, expectedText) {
  await evaluate(`window.location.hash = ${JSON.stringify(hash)}; true`);
  await waitForText(expectedText);
  await sleep(400);
}

async function inspectRoute(route, theme) {
  return evaluate(`(() => {
    const selectors = ['.app-sider', '.topbar', '.page', '.page-heading', '.content-grid', '.note-layout', '.panel', '.editor-shell', '.arco-layout-content'];
    const rects = Object.fromEntries(selectors.map((selector) => {
      const node = document.querySelector(selector);
      if (!node) return [selector, null];
      const rect = node.getBoundingClientRect();
      const style = getComputedStyle(node);
      return [selector, { x: rect.x, y: rect.y, width: rect.width, height: rect.height, overflowY: style.overflowY, display: style.display }];
    }));
    const commands = [...document.querySelectorAll('button, input, [role=button]')]
      .filter((node) => {
        const rect = node.getBoundingClientRect();
        return rect.width > 0 && rect.height > 0;
      })
      .map((node) => ({
        text: (node.textContent || '').trim().replace(/\\s+/g, ' ').slice(0, 80),
        aria: node.getAttribute('aria-label'),
        title: node.getAttribute('title'),
        rect: (() => { const value = node.getBoundingClientRect(); return { x: value.x, y: value.y, width: value.width, height: value.height }; })(),
      }));
    return {
      route: ${JSON.stringify(route)},
      theme: ${JSON.stringify(theme)},
      viewport: { width: innerWidth, height: innerHeight },
      document: { clientWidth: document.documentElement.clientWidth, scrollWidth: document.documentElement.scrollWidth },
      rects,
      commands,
    };
  })()`);
}

async function captureBaseline() {
  await setViewport(1896, 1063);
  await ensureFixtures();
  const captures = [];
  for (const item of [
    { name: 'notebook', hash: '#/notebooks', text: '笔记本' },
    { name: 'bank', hash: '#/banks', text: '题库' },
  ]) {
    await routeTo(item.hash, item.text);
    for (const theme of ['light', 'dark']) {
      await setTheme(theme);
      const geometry = await inspectRoute(item.name, theme);
      const screenshot = await command('Page.captureScreenshot', { format: 'png', captureBeyondViewport: false });
      fs.writeFileSync(path.join(evidenceRoot, `${item.name}-${theme}.png`), Buffer.from(screenshot.result.data, 'base64'));
      fs.writeFileSync(path.join(evidenceRoot, `${item.name}-${theme}.geometry.json`), `${JSON.stringify(geometry, null, 2)}\n`, 'utf8');
      captures.push(geometry);
    }
  }
  const report = { mode: 'baseline', captures: captures.length, exceptions, passed: captures.length === 4 && exceptions.length === 0 };
  fs.writeFileSync(path.join(evidenceRoot, 'report.json'), `${JSON.stringify(report, null, 2)}\n`, 'utf8');
  return report;
}

async function inspectContract(route, width) {
  await setViewport(width, 900);
  await routeTo(route === 'notebook' ? '#/notebooks' : '#/banks', route === 'notebook' ? '笔记本' : '题库');
  return evaluate(`(() => {
    const required = ['.route-workspace', '.route-command-row', '.route-workspace__body', '.local-explorer', '.local-explorer__header', '.local-explorer__list', '.route-workspace__content', '.dense-content-row'];
    const missing = required.filter((selector) => !document.querySelector(selector));
    const body = document.querySelector('.route-workspace__body');
    const explorer = document.querySelector('.local-explorer');
    const list = document.querySelector('.local-explorer__list');
    const commandRow = document.querySelector('.route-command-row');
    const routeScroll = document.querySelector('.app-shell .arco-layout-content');
    const result = { route: ${JSON.stringify(route)}, width: ${width}, missing, failures: [] };
    if (missing.length) result.failures.push('Missing approved workspace primitives: ' + missing.join(', '));
    if (body && explorer && list && commandRow && routeScroll) {
      const explorerRect = explorer.getBoundingClientRect();
      const listStyle = getComputedStyle(list);
      const rowRect = commandRow.getBoundingClientRect();
      const routeStyle = getComputedStyle(routeScroll);
      if (${width} >= 760 && Math.abs(explorerRect.width - 232) > 1) result.failures.push('Explorer must be 232px at split widths');
      if (${width} < 760 && Number.parseFloat(listStyle.maxHeight) > 240) result.failures.push('Explorer list must cap at 240px below 760px');
      if (rowRect.height < 44) result.failures.push('Command row must be at least 44px tall');
      if (!['auto', 'scroll'].includes(routeStyle.overflowY)) result.failures.push('Arco route content must own vertical scrolling');
      if (document.documentElement.scrollWidth > document.documentElement.clientWidth) result.failures.push('Primary content has horizontal overflow');
    }
    return result;
  })()`);
}

async function captureContract() {
  const checks = [];
  for (const width of [375, 759, 760, 761, 768, 1280]) {
    checks.push(await inspectContract('notebook', width));
    checks.push(await inspectContract('bank', width));
  }
  const failures = checks.flatMap((check) => check.failures.map((failure) => `${check.route}@${check.width}: ${failure}`));
  const report = { mode: 'contract', checks, failures, exceptions, passed: failures.length === 0 && exceptions.length === 0 };
  fs.writeFileSync(path.join(evidenceRoot, 'report.json'), `${JSON.stringify(report, null, 2)}\n`, 'utf8');
  return report;
}

await command('Runtime.enable');
await command('Page.enable');
const report = mode === 'contract' ? await captureContract() : await captureBaseline();
console.log(JSON.stringify(report, null, 2));
socket.close();
if (!report.passed) process.exitCode = 1;
