const port = Number(process.argv[2] || 9230);
const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

async function findPage() {
  for (let attempt = 0; attempt < 30; attempt += 1) {
    try {
      const targets = await (await fetch(`http://127.0.0.1:${port}/json`)).json();
      const page = targets.find((item) => item.type === 'page' && item.webSocketDebuggerUrl);
      if (page) return page;
    } catch { }
    await sleep(300);
  }
  throw new Error('Electron page not found');
}

const target = await findPage();
const socket = new WebSocket(target.webSocketDebuggerUrl);
const pending = new Map();
let id = 0;
const exceptions = [];
socket.addEventListener('message', (event) => {
  const message = JSON.parse(event.data);
  if (message.method === 'Runtime.exceptionThrown') exceptions.push(message.params.exceptionDetails?.text || 'runtime exception');
  const resolver = pending.get(message.id);
  if (resolver) {
    pending.delete(message.id);
    resolver(message);
  }
});
await new Promise((resolve, reject) => {
  socket.addEventListener('open', resolve, { once: true });
  socket.addEventListener('error', reject, { once: true });
});

function command(method, params = {}) {
  const requestId = ++id;
  return new Promise((resolve, reject) => {
    pending.set(requestId, (message) => message.error ? reject(new Error(message.error.message)) : resolve(message));
    socket.send(JSON.stringify({ id: requestId, method, params }));
  });
}

async function evaluate(expression) {
  const response = await command('Runtime.evaluate', { expression, returnByValue: true, awaitPromise: true });
  if (response.result?.exceptionDetails) throw new Error(response.result.exceptionDetails.text || 'evaluation failed');
  return response.result?.result?.value;
}

await command('Runtime.enable');
await command('Page.enable');
const baseUrl = await evaluate('window.api?.backend.getBaseUrl()');
await evaluate("window.location.hash = '#/wrong'");
await sleep(1200);
const emptyWrongBody = await evaluate('document.body.innerText');
const emptyWrong = {
  hasWrongPage: emptyWrongBody.includes('待巩固题目'),
  hasEmptyState: emptyWrongBody.includes('还没有错题')
};
const setup = await evaluate(`(async () => {
  const base = ${JSON.stringify(baseUrl)};
  const bank = await fetch(base + '/api/banks', { method: 'POST', headers: {'Content-Type': 'application/json'}, body: JSON.stringify({ name: 'Wrong context QA', sourceType: 'manual' }) }).then((response) => response.json());
  const question = await fetch(base + '/api/banks/' + bank.id + '/questions', { method: 'POST', headers: {'Content-Type': 'application/json'}, body: JSON.stringify({ type: 'single', stem: '上下文循环测试', options: [{key:'A',text:'错误'}, {key:'B',text:'正确'}], answer: 'B', analysis: '测试错题上下文' }) }).then((response) => response.json());
  const session = await fetch(base + '/api/quiz/sessions', { method: 'POST', headers: {'Content-Type': 'application/json'}, body: JSON.stringify({ bankId: bank.id, shuffle: false, limit: 1 }) }).then((response) => response.json());
  await fetch(base + '/api/quiz/sessions/' + session.sessionId + '/submit', { method: 'POST', headers: {'Content-Type': 'application/json'}, body: JSON.stringify({ questionId: question.id, userAnswer: 'A', timeSpent: 1 }) });
  return { bankId: bank.id, questionId: question.id };
})()`);
await evaluate("window.location.hash = '#/wrong'; window.location.reload(); true");
await sleep(2200);
const routes = [
  { hash: '#/wrong', expected: '待巩固题目' },
  { hash: '#/quiz', expected: '练习设置' },
  { hash: '#/notebooks', expected: '笔记本' },
  { hash: '#/banks', expected: '我的题库' },
  { hash: '#/settings', expected: 'AI 连接' }
];
const routeReports = [];
for (const route of routes) {
  await evaluate(`window.location.hash = ${JSON.stringify(route.hash)}`);
  await sleep(route.hash === '#/notebooks' ? 2200 : 1200);
  const body = await evaluate('document.body.innerText');
  routeReports.push({
    route: route.hash,
    title: await evaluate("document.querySelector('.topbar-title')?.textContent?.trim() || ''"),
    expectedText: route.expected,
    hasExpectedText: body.includes(route.expected),
    body: body.slice(0, 700)
  });
}
const wrongReport = routeReports.find((route) => route.route === '#/wrong');
const report = {
  emptyWrong,
  setup,
  routes: routeReports,
  hasWrongPage: Boolean(wrongReport?.hasExpectedText),
  hasQuestion: Boolean(wrongReport?.body.includes('上下文循环测试')),
  exceptions,
  passed: exceptions.length === 0
    && emptyWrong.hasWrongPage
    && emptyWrong.hasEmptyState
    && routeReports.every((route) => route.hasExpectedText)
    && Boolean(wrongReport?.body.includes('上下文循环测试'))
};
console.log(JSON.stringify(report, null, 2));
socket.close();
if (!report.passed) process.exitCode = 1;
