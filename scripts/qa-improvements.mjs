import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const workspace = fileURLToPath(new URL('..', import.meta.url));
const port = Number(process.argv[2] || 9224);
const evidenceDir = path.join(workspace, '.omo', 'evidence');
fs.mkdirSync(evidenceDir, { recursive: true });

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
async function getPage() {
  for (let attempt = 0; attempt < 30; attempt += 1) {
    try {
      const targets = await (await fetch(`http://127.0.0.1:${port}/json`)).json();
      const page = targets.find((item) => item.type === 'page' && item.webSocketDebuggerUrl);
      if (page) return page;
    } catch { }
    await sleep(500);
  }
  throw new Error(`No Electron page found on port ${port}`);
}

const target = await getPage();
const socket = new WebSocket(target.webSocketDebuggerUrl);
const pending = new Map();
let messageId = 0;
socket.addEventListener('message', (event) => {
  const message = JSON.parse(event.data);
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
    pending.set(id, (message) => message.error ? reject(new Error(message.error.message)) : resolve(message));
    socket.send(JSON.stringify({ id, method, params }));
  });
}

async function evaluate(expression) {
  const result = await command('Runtime.evaluate', { expression, returnByValue: true, awaitPromise: true });
  if (result.result?.exceptionDetails) throw new Error(result.result.exceptionDetails.text || 'evaluation failed');
  return result.result?.result?.value;
}

async function clickText(text) {
  return evaluate(`(() => { const node = [...document.querySelectorAll('button')].find((item) => item.textContent?.replace(/\\s/g, '').includes(${JSON.stringify(text)})); node?.click(); return Boolean(node); })()`);
}

async function setValue(selector, value) {
  return evaluate(`(() => { const node = document.querySelector(${JSON.stringify(selector)}); if (!node) return false; const prototype = Object.getPrototypeOf(node); const descriptor = Object.getOwnPropertyDescriptor(prototype, 'value') || Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value') || Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype, 'value'); descriptor?.set?.call(node, ${JSON.stringify(value)}); node.dispatchEvent(new Event('input', { bubbles: true })); node.dispatchEvent(new Event('change', { bubbles: true })); return true; })()`);
}

async function setValueAt(selector, index, value) {
  return evaluate(`(() => { const node = [...document.querySelectorAll(${JSON.stringify(selector)})][${index}]; if (!node) return false; const prototype = Object.getPrototypeOf(node); const descriptor = Object.getOwnPropertyDescriptor(prototype, 'value') || Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype, 'value'); descriptor?.set?.call(node, ${JSON.stringify(value)}); node.dispatchEvent(new Event('input', { bubbles: true })); node.dispatchEvent(new Event('change', { bubbles: true })); return true; })()`);
}

await command('Runtime.enable');
await command('Page.enable');
await command('Page.reload', { ignoreCache: true });
await sleep(1200);

await evaluate("document.querySelector('[role=menuitem]')?.click()");
await evaluate("(() => { const item = [...document.querySelectorAll('[role=menuitem], .arco-menu-item')].find((node) => node.textContent?.replace(/\\s/g, '').includes('AI')); item?.click(); return Boolean(item); })()");
await sleep(700);

const inputs = await evaluate("[...document.querySelectorAll('input:not([type=file])')].map((node) => ({ type: node.type, value: node.value }))");
const inputSet = await evaluate(`(() => { const nodes = [...document.querySelectorAll('input:not([type=file])')]; return nodes.length >= 4; })()`);
if (inputSet) {
  await setValue('input[placeholder="custom"]', 'custom');
  await setValue('input[placeholder="https://api.example.com/v1"]', 'mock://local');
  await setValue('input[placeholder="模型名称"]', 'local-demo');
  await setValue('input[type=password]', 'qa-key');
  await clickText('保存配置');
  await sleep(700);
}
await setValue('textarea', '**快捷 Markdown** $E=mc^2$');
await evaluate("document.querySelector('button[aria-label=发送]')?.click()");
for (let attempt = 0; attempt < 20; attempt += 1) {
  if (await evaluate("Boolean(document.querySelector('.chat-message.assistant'))")) break;
  await sleep(300);
}
await setValueAt('textarea', 1, '**总结重点** $x^2$');
const summaryInputState = await evaluate("[...document.querySelectorAll('textarea')].map((node) => ({ value: node.value, placeholder: node.placeholder }))");
await sleep(250);
await clickText('生成总结');
for (let attempt = 0; attempt < 20; attempt += 1) {
  if (await evaluate("Boolean(document.querySelector('.ai-summary-result .katex'))")) break;
  await sleep(300);
}
const aiCheck = {
  fileInput: await evaluate("Boolean(document.querySelector('input[type=file]'))"),
  contextPicker: await evaluate("Boolean(document.querySelector('.ai-context-picker')) && document.body.innerText.includes('从已有内容载入')"),
  userMarkdown: await evaluate("Boolean(document.querySelector('.chat-message.user strong'))"),
  userLatex: await evaluate("Boolean(document.querySelector('.chat-message.user .katex'))"),
  assistantReply: await evaluate("Boolean(document.querySelector('.chat-message.assistant'))"),
  summaryMarkdown: await evaluate("Boolean(document.querySelector('.ai-summary-result strong'))"),
  summaryLatex: await evaluate("Boolean(document.querySelector('.ai-summary-result .katex'))"),
  summaryInputState,
  inputCount: inputs.length
};

const baseUrl = await evaluate("window.api?.backend.getBaseUrl()");
const bank = await evaluate(`(async () => { const base = ${JSON.stringify(baseUrl)}; const bank = await fetch(base + '/api/banks', { method: 'POST', headers: {'Content-Type': 'application/json'}, body: JSON.stringify({ name: 'QA shortcuts', sourceType: 'manual' }) }).then((response) => response.json()); await fetch(base + '/api/banks/' + bank.id + '/questions', { method: 'POST', headers: {'Content-Type': 'application/json'}, body: JSON.stringify({ type: 'single', stem: '**快捷键题** $x$', options: [{key:'A',text:'错误'}, {key:'B',text:'正确'}], answer: 'B', analysis: '测试快捷键' }) }); return bank; })()`);
await evaluate(`window.location.hash = '#/quiz?bankId=${bank.id}'`);
await sleep(700);
await clickText('开始练习');
await sleep(700);
await evaluate("window.dispatchEvent(new KeyboardEvent('keydown', { key: '2', bubbles: true }))");
await sleep(200);
const optionSelected = await evaluate("Boolean([...document.querySelectorAll('.quiz-option.selected')].some((node) => node.textContent?.includes('B')))");
await evaluate("window.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true }))");
await sleep(700);
const quizCheck = {
  shortcutHint: (await evaluate('document.body.innerText')).includes('Enter 提交'),
  optionSelected,
  submitted: await evaluate("Boolean(document.querySelector('.feedback'))")
};

const report = { aiCheck, quizCheck, passed: Object.values(aiCheck).every(Boolean) && Object.values(quizCheck).every(Boolean) };
fs.writeFileSync(path.join(evidenceDir, 'task-14-improvements-ui.txt'), JSON.stringify(report, null, 2) + '\n', 'utf8');
console.log(JSON.stringify(report, null, 2));
socket.close();
if (!report.passed) process.exitCode = 1;
