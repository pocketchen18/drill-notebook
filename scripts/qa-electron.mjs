import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const workspace = fileURLToPath(new URL('..', import.meta.url));
const port = Number(process.argv[2] || 9222);
const evidenceDir = path.join(workspace, '.omo', 'evidence');
fs.mkdirSync(evidenceDir, { recursive: true });

const delay = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

async function findPage() {
  for (let attempt = 0; attempt < 30; attempt += 1) {
    try {
      const targets = await (await fetch(`http://127.0.0.1:${port}/json`)).json();
      const page = targets.find((target) => target.type === 'page' && target.webSocketDebuggerUrl);
      if (page) return page;
    } catch {
      // Electron is still starting.
    }
    await delay(500);
  }
  throw new Error(`No Electron page found on port ${port}`);
}

const target = await findPage();
const socket = new WebSocket(target.webSocketDebuggerUrl);
let messageId = 0;
const pending = new Map();
const exceptions = [];
const consoleEvents = [];

socket.addEventListener('message', (event) => {
  const message = JSON.parse(event.data);
  if (message.method === 'Runtime.exceptionThrown') exceptions.push(message.params.exceptionDetails?.text || 'runtime exception');
  if (message.method === 'Runtime.consoleAPICalled') consoleEvents.push(message.params.args?.map((item) => item.value ?? item.description).join(' '));
  if (message.id && pending.has(message.id)) {
    pending.get(message.id)(message);
    pending.delete(message.id);
  }
});

await new Promise((resolve, reject) => {
  socket.addEventListener('open', resolve, { once: true });
  socket.addEventListener('error', reject, { once: true });
});

function command(method, params = {}) {
  const id = ++messageId;
  return new Promise((resolve, reject) => {
    pending.set(id, (message) => message.error ? reject(new Error(message.error.message)) : resolve(message));
    socket.send(JSON.stringify({ id, method, params }));
  });
}

async function evaluate(expression) {
  const result = await command('Runtime.evaluate', { expression, returnByValue: true, awaitPromise: true });
  if (result.result?.exceptionDetails) throw new Error(result.result.exceptionDetails.exception?.description || result.result.exceptionDetails.text || 'evaluation failed');
  return result.result?.result?.value;
}

await command('Runtime.enable');
await command('Page.enable');
await command('Page.reload', { ignoreCache: true });
await delay(1500);
const checks = [];
const navigation = [
  ['题库', '/banks'],
  ['刷题', '/quiz'],
  ['错题', '/wrong'],
  ['笔记本', '/notebooks'],
  ['AI', '/ai'],
  ['设置', '/settings']
];

for (const [label, route] of navigation) {
  const clicked = await evaluate(`(() => {
    const item = [...document.querySelectorAll('.arco-menu-item, [role="menuitem"]')].find((node) => node.textContent?.replace(/\\s/g, '').includes(${JSON.stringify(label)}));
    if (!item) return { ok: false, body: document.body.innerText.slice(0, 800), menu: [...document.querySelectorAll('[class*="menu-item"], [role="menuitem"]')].map((node) => node.textContent) };
    item.click();
    return { ok: true };
  })()`);
  if (!clicked?.ok) {
    checks.push({ label, route, title: '', hasLabel: false, hasError: true, click: clicked });
    continue;
  }
  await delay(500);
  const title = await evaluate("document.querySelector('.topbar-title')?.textContent?.trim() || ''");
  const body = await evaluate('document.body.innerText');
  checks.push({ label, route, title, hasLabel: body.includes(label), hasError: body.includes('服务暂时不可用') });
}

const screenshot = await command('Page.captureScreenshot', { format: 'png' });
fs.writeFileSync(path.join(evidenceDir, 'task-5-nav.png'), Buffer.from(screenshot.result.data, 'base64'));
const notebookOpen = await evaluate(`(() => {
  const item = [...document.querySelectorAll('.arco-menu-item, [role="menuitem"]')].find((node) => node.textContent?.replace(/\\s/g, '').includes('笔记本'));
  item?.click();
  return Boolean(item);
})()`);
await delay(1000);
const editorReady = await evaluate("Boolean(document.querySelector('.ProseMirror'))");
const formulaInserted = await evaluate(`(() => {
  const button = [...document.querySelectorAll('button')].find((node) => node.textContent?.replace(/\\s/g, '').includes('公式'));
  button?.click();
  return Boolean(button);
})()`);
await delay(900);
const chartInserted = await evaluate(`(() => {
  const button = [...document.querySelectorAll('button')].find((node) => node.textContent?.replace(/\\s/g, '').includes('图表'));
  button?.click();
  return Boolean(button);
})()`);
await delay(1500);
const editorCheck = {
  notebookOpen,
  editorReady,
  formulaInserted,
  chartInserted,
  mathBlockCount: await evaluate("document.querySelectorAll('.math-block').length"),
  mathBlockHtml: await evaluate("document.querySelector('.math-block')?.outerHTML?.slice(0, 500) || ''"),
  katexRendered: await evaluate("Boolean(document.querySelector('.math-block .katex, [data-math-inline] .katex'))"),
  mermaidRendered: await evaluate("Boolean(document.querySelector('.mermaid-block svg'))")
};
const editorScreenshot = await command('Page.captureScreenshot', { format: 'png' });
fs.writeFileSync(path.join(evidenceDir, 'task-8-editor.png'), Buffer.from(editorScreenshot.result.data, 'base64'));
const report = { checks, editorCheck, exceptions, consoleEvents, passed: exceptions.length === 0 && checks.every((item) => item.title === item.label && item.hasLabel && !item.hasError) && Object.values(editorCheck).every(Boolean) };
fs.writeFileSync(path.join(evidenceDir, 'task-5-nav.txt'), JSON.stringify(report, null, 2) + '\n', 'utf8');
console.log(JSON.stringify(report, null, 2));
socket.close();
if (!report.passed) process.exitCode = 1;
