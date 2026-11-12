import { app, BrowserWindow, dialog, ipcMain, net, screen, session, shell } from 'electron';
import fs from 'node:fs';
import path from 'node:path';
import { pathToFileURL } from 'node:url';
import { clearPortableTemp, getPortablePaths, setupPortablePaths, type PortablePaths } from './paths';
import { startBackend, stopBackend, type BackendHandle } from './java-bridge';
import { PortableUpdateHost } from './update-host';

let portablePaths: PortablePaths;
let backend: BackendHandle | undefined;
// 启动更新先展示窗口；此时助手快捷键仍可能触发 API，不能让渲染进程缓存尚未就绪的备用地址。
let settleBackendStartup: () => void;
const backendStartup = new Promise<void>(resolve => { settleBackendStartup = resolve; });
let updateHost: PortableUpdateHost | undefined;
let updateQuit = false;
let mainWindow: BrowserWindow | undefined;
let pendingActivation = false;
/** AI 助手窗口：整个应用只有一个，关闭只是收起，随主窗口一起退出。 */
let assistantWindow: BrowserWindow | undefined;
let quitting = false;
let assistantActivity = { running: 0, pending: 0 };
/** 助手窗口最近一次失去焦点的时刻：点击主窗口入口会先把焦点抢走，靠它判断点击前助手是否在前台。 */
let assistantBlurAt = 0;
const ASSISTANT_FRONT_GRACE_MS = 700;

// This must run before ready so Electron never initializes a system profile first.
portablePaths = setupPortablePaths();
// 必须先重定向 userData，再申请锁：同一便携目录只允许一套窗口和后端。
const ownsSingleInstanceLock = app.requestSingleInstanceLock();

function activateMainWindow(): void {
  if (!mainWindow || mainWindow.isDestroyed()) {
    // 后端仍在异步启动时，等主窗口创建后再兑现唤起请求。
    pendingActivation = true;
    return;
  }
  pendingActivation = false;
  if (mainWindow.isMinimized()) mainWindow.restore();
  if (!mainWindow.isVisible()) mainWindow.show();
  mainWindow.focus();
}

function rendererEntry(): string {
  const devUrl = process.env.ELECTRON_RENDERER_URL;
  if (devUrl) return devUrl;
  return path.join(__dirname, '..', 'frontend', 'dist', 'index.html');
}

function windowBackgroundColor(): string {
  try {
    const configPath = path.join(portablePaths.config, 'app-config.json');
    if (fs.existsSync(configPath)) {
      const raw = JSON.parse(fs.readFileSync(configPath, 'utf-8')) as { theme?: string };
      // 与 frontend/src/styles/app.css 的 --page-bg 保持一致，避免首帧闪色
      if (raw.theme === 'dark') return '#131519';
    }
  } catch {
    /* ignore */
  }
  return '#f4f6fa';
}

/** 窗口图标：开发态读仓库 resources/icon，打包后由 extraResources 拷到 resources/icon；缺失时交给系统默认。 */
function appIconPath(): string | undefined {
  const candidate = app.isPackaged
    ? path.join(process.resourcesPath, 'icon', 'icon.ico')
    : path.join(__dirname, '..', 'resources', 'icon', 'icon.ico');
  return fs.existsSync(candidate) ? candidate : undefined;
}

interface AssistantWindowState { x?: number; y?: number; width: number; height: number; maximized: boolean; fixed: boolean }
const ASSISTANT_DEFAULT = { width: 980, height: 760 };
const BUS_TYPES = new Set(['page-context', 'context-request', 'navigate', 'tool-mutation', 'invalidate', 'assistant-activity']);

function assistantStateFile(): string {
  return path.join(portablePaths.config, 'assistant-window.json');
}

/** 位置、尺寸与「固定」（窗口保持在其他窗口之上）保存在 APP_ROOT/config；内容损坏时回到默认值。 */
function readAssistantState(): AssistantWindowState {
  const fallback: AssistantWindowState = { ...ASSISTANT_DEFAULT, maximized: false, fixed: false };
  try {
    if (!fs.existsSync(assistantStateFile())) return fallback;
    const raw = JSON.parse(fs.readFileSync(assistantStateFile(), 'utf8')) as Record<string, unknown>;
    const size = (value: unknown, min: number, base: number): number =>
      typeof value === 'number' && Number.isFinite(value) ? Math.max(min, Math.min(8000, Math.round(value))) : base;
    const point = (value: unknown): number | undefined =>
      typeof value === 'number' && Number.isFinite(value) ? Math.round(value) : undefined;
    return { x: point(raw.x), y: point(raw.y), width: size(raw.width, 420, fallback.width), height: size(raw.height, 520, fallback.height),
      maximized: raw.maximized === true, fixed: raw.fixed === true };
  } catch {
    return fallback;
  }
}

function saveAssistantState(): void {
  if (!assistantWindow || assistantWindow.isDestroyed()) return;
  try {
    const bounds = assistantWindow.getNormalBounds();
    const state: AssistantWindowState = { ...bounds, maximized: assistantWindow.isMaximized(), fixed: assistantWindow.isAlwaysOnTop() };
    fs.writeFileSync(assistantStateFile(), JSON.stringify(state, null, 2) + '\n', 'utf8');
  } catch (error) {
    console.error('[assistant] window state not saved', error);
  }
}

/** 记住的位置必须仍落在某块显示器上；否则放到主窗口所在显示器的右侧。 */
function assistantBounds(state: AssistantWindowState): { x: number; y: number; width: number; height: number } {
  const { x, y } = state;
  if (x !== undefined && y !== undefined && screen.getAllDisplays().some(({ workArea }) =>
    x + state.width > workArea.x + 120 && x < workArea.x + workArea.width - 120
    && y >= workArea.y - 8 && y < workArea.y + workArea.height - 80)) {
    return { x, y, width: state.width, height: state.height };
  }
  const display = mainWindow && !mainWindow.isDestroyed()
    ? screen.getDisplayMatching(mainWindow.getBounds()) : screen.getPrimaryDisplay();
  const area = display.workArea;
  const width = Math.min(state.width, area.width);
  const height = Math.min(state.height, area.height);
  return { x: area.x + Math.max(0, area.width - width - 24), y: area.y + Math.max(0, Math.round((area.height - height) / 2)), width, height };
}

function assistantVisible(): boolean {
  return !!assistantWindow && !assistantWindow.isDestroyed() && assistantWindow.isVisible();
}

function assistantState(): { visible: boolean; running: number; pending: number; fixed: boolean } {
  return { visible: assistantVisible(), running: assistantActivity.running, pending: assistantActivity.pending,
    fixed: !!assistantWindow && !assistantWindow.isDestroyed() && assistantWindow.isAlwaysOnTop() };
}

function broadcastAssistantState(): void {
  const state = assistantState();
  for (const target of [mainWindow, assistantWindow]) {
    if (target && !target.isDestroyed()) target.webContents.send('assistant:state', state);
  }
}

function createAssistantWindow(): BrowserWindow {
  const state = readAssistantState();
  const created = new BrowserWindow({
    ...assistantBounds(state),
    minWidth: 420,
    minHeight: 520,
    show: false,
    title: 'AI 助手',
    // 「固定」：让助手浮在主窗口上方，方便一边看回答一边记笔记。
    alwaysOnTop: state.fixed,
    backgroundColor: windowBackgroundColor(),
    icon: appIconPath(),
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'),
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: true,
      webSecurity: true,
      // 收起后仍要接收运行状态，后台回复不能因为窗口不可见而停止更新。
      backgroundThrottling: false
    }
  });
  assistantWindow = created;
  created.webContents.setWindowOpenHandler(() => ({ action: 'deny' }));
  created.webContents.on('will-navigate', (event, url) => {
    if (!isTrustedRendererUrl(url)) event.preventDefault();
  });
  created.once('ready-to-show', () => {
    if (state.maximized) created.maximize();
    created.show();
    created.focus();
  });
  // 关闭只是收起：渲染进程保留会话、草稿与阅读位置，后台任务继续。
  created.on('close', (event) => {
    saveAssistantState();
    if (quitting) return;
    event.preventDefault();
    created.hide();
  });
  created.on('show', broadcastAssistantState);
  created.on('hide', broadcastAssistantState);
  created.on('focus', () => created.flashFrame(false));
  created.on('blur', () => { assistantBlurAt = Date.now(); });
  created.on('closed', () => {
    if (assistantWindow === created) assistantWindow = undefined;
    assistantActivity = { running: 0, pending: 0 };
  });
  const entry = rendererEntry();
  if (/^https?:\/\//.test(entry)) void created.loadURL(`${entry.replace(/#.*$/, '')}#/assistant`);
  else void created.loadFile(entry, { hash: '/assistant' });
  return created;
}

function openAssistantWindow(): void {
  if (!assistantWindow || assistantWindow.isDestroyed()) {
    createAssistantWindow();
    return;
  }
  if (assistantWindow.isMinimized()) assistantWindow.restore();
  if (!assistantWindow.isVisible()) assistantWindow.show();
  assistantWindow.focus();
  assistantWindow.webContents.send('assistant:activated');
}

/**
 * 主窗口入口与快捷键的语义：助手在前台就收起，在后台（隐藏、最小化或被其他窗口盖住）就唤到前台。
 * 「在前台」= 可见且未最小化，并且持有焦点、已固定，或刚刚因为这次点击才失去焦点。
 */
function toggleAssistantWindow(): void {
  const target = assistantWindow;
  if (!target || target.isDestroyed()) {
    createAssistantWindow();
    return;
  }
  const front = target.isVisible() && !target.isMinimized()
    && (target.isFocused() || target.isAlwaysOnTop() || Date.now() - assistantBlurAt < ASSISTANT_FRONT_GRACE_MS);
  if (front) target.close();
  else openAssistantWindow();
}

/** 主窗口关闭即退出；仍有回复或待发消息时先确认。 */
function confirmQuit(): boolean {
  const { running, pending } = assistantActivity;
  if (!mainWindow || (running <= 0 && pending <= 0)) return true;
  const choice = dialog.showMessageBoxSync(mainWindow, {
    type: 'warning',
    buttons: ['取消', '退出应用'],
    defaultId: 0,
    cancelId: 0,
    noLink: true,
    title: '退出 Drill Notebook',
    message: running > 0 ? `仍有 ${running} 个会话正在回复` : `仍有 ${pending} 条消息等待发送`,
    detail: '退出后这些回复会中断，已生成的内容会保留；待发送的消息下次打开时保持暂停。'
  });
  return choice === 1;
}

function createWindow(): void {
  mainWindow = new BrowserWindow({
    width: 1440,
    height: 920,
    minWidth: 1100,
    minHeight: 720,
    backgroundColor: windowBackgroundColor(),
    icon: appIconPath(),
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'),
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: true,
      webSecurity: true
    }
  });

  mainWindow.webContents.setWindowOpenHandler(() => ({ action: 'deny' }));
  mainWindow.webContents.on('will-navigate', (event, url) => {
    if (!isTrustedRendererUrl(url)) event.preventDefault();
  });
  mainWindow.webContents.on('did-fail-load', (_event, code, description) => {
    if (code === -3 || !updateHost?.awaitingBoot) return;
    updateHost.failBoot(`新版本界面无法加载：${description}`);
    quitting = true;
    app.quit();
  });
  mainWindow.on('close', (event) => {
    if (quitting) return;
    if (!confirmQuit()) {
      event.preventDefault();
      return;
    }
    quitting = true;
  });
  mainWindow.on('closed', () => {
    mainWindow = undefined;
    // 助手依赖主窗口提供当前页面与资料定位，不单独留下。
    quitting = true;
    if (assistantWindow && !assistantWindow.isDestroyed()) assistantWindow.close();
  });

  const entry = rendererEntry();
  if (/^https?:\/\//.test(entry)) {
    void mainWindow.loadURL(entry);
  } else {
    void mainWindow.loadFile(entry);
  }
  if (pendingActivation) activateMainWindow();
}

function isTrustedRendererUrl(url: string): boolean {
  const expected = rendererEntry();
  try {
    const actualUrl = new URL(url);
    if (/^https?:\/\//.test(expected)) return actualUrl.origin === new URL(expected).origin;
    return actualUrl.href.split('#')[0] === pathToFileURL(expected).href;
  } catch {
    return false;
  }
}

function trustedSender(event: Electron.IpcMainInvokeEvent): boolean {
  return !!event.senderFrame && isTrustedRendererUrl(event.senderFrame.url);
}

ipcMain.handle('assistant:open', (event) => {
  if (trustedSender(event)) openAssistantWindow();
});
ipcMain.handle('assistant:toggle', (event) => {
  if (trustedSender(event)) toggleAssistantWindow();
});
ipcMain.handle('assistant:hide', (event) => {
  if (trustedSender(event) && assistantVisible()) assistantWindow!.close();
});
ipcMain.handle('assistant:get-state', () => assistantState());
ipcMain.handle('assistant:set-fixed', (event, fixed: unknown) => {
  if (!trustedSender(event) || !assistantWindow || assistantWindow.isDestroyed()) return;
  assistantWindow.setAlwaysOnTop(fixed === true);
  saveAssistantState();
  broadcastAssistantState();
});
// 回复结束而窗口不在前台时，只闪烁任务栏按钮，不抢焦点也不弹系统通知。
ipcMain.handle('assistant:attention', (event) => {
  if (trustedSender(event) && assistantVisible() && !assistantWindow!.isFocused()) assistantWindow!.flashFrame(true);
});
ipcMain.handle('window:focus-main', (event) => {
  if (trustedSender(event)) activateMainWindow();
});
/** 两个窗口之间的消息中转：只转发约定类型，发给另一个窗口；运行计数同时留给退出确认。 */
ipcMain.handle('bus:publish', (event, message: unknown) => {
  if (!trustedSender(event) || !message || typeof message !== 'object') return;
  const type = (message as { type?: unknown }).type;
  if (typeof type !== 'string' || !BUS_TYPES.has(type)) return;
  const fromAssistant = !!assistantWindow && !assistantWindow.isDestroyed() && event.sender === assistantWindow.webContents;
  if (type === 'assistant-activity') {
    if (!fromAssistant) return;
    const count = (value: unknown): number => typeof value === 'number' && Number.isFinite(value) ? Math.max(0, Math.min(999, Math.floor(value))) : 0;
    const next = { running: count((message as { running?: unknown }).running), pending: count((message as { pending?: unknown }).pending) };
    if (next.running !== assistantActivity.running || next.pending !== assistantActivity.pending) {
      assistantActivity = next;
      broadcastAssistantState();
    }
    return;
  }
  const target = fromAssistant ? mainWindow : assistantWindow;
  if (target && !target.isDestroyed()) target.webContents.send('bus:message', message);
});

ipcMain.handle('backend:get-base-url', async () => {
  await backendStartup;
  return backend?.baseUrl ?? 'http://127.0.0.1:18080';
});
ipcMain.handle('app:get-root', () => portablePaths.root);
ipcMain.handle('app:get-portable-info', () => ({ root: portablePaths.root, database: portablePaths.database, portable: true }));
ipcMain.handle('app:get-config', () => {
  const configFile = path.join(portablePaths.config, 'app-config.json');
  if (!fs.existsSync(configFile)) return { theme: 'light' };
  try {
    const value = JSON.parse(fs.readFileSync(configFile, 'utf8')) as { theme?: string };
    return { theme: value.theme === 'dark' ? 'dark' : 'light' };
  } catch {
    return { theme: 'light' };
  }
});
ipcMain.handle('app:set-config', (_event, value: unknown) => {
  const config = value && typeof value === 'object' && 'theme' in value && value.theme === 'dark' ? { theme: 'dark' } : { theme: 'light' };
  fs.writeFileSync(path.join(portablePaths.config, 'app-config.json'), JSON.stringify(config, null, 2) + '\n', 'utf8');
});
ipcMain.handle('dialog:open-text-file', async (_event, extensions?: string[]) => {
  const ext = extensions && extensions.length > 0 ? extensions : ['md', 'markdown', 'txt'];
  const filterName = ext.includes('json') ? 'JSON' : ext.includes('pdf') ? 'PDF' : 'Markdown';
  const result = await dialog.showOpenDialog({
    properties: ['openFile'],
    filters: [{ name: filterName, extensions: ext }]
  });
  if (result.canceled || result.filePaths.length === 0) return { canceled: true };
  const selected = result.filePaths[0];
  return { canceled: false, path: selected, content: fs.readFileSync(selected, 'utf8') };
});
ipcMain.handle('export:save', async (event, request: unknown) => {
  if (!event.senderFrame || !isTrustedRendererUrl(event.senderFrame.url)) throw new Error('Export request rejected from an untrusted page.');
  if (!request || typeof request !== 'object') throw new Error('Invalid export request.');
  const value = request as Record<string, unknown>;
  const format = value.format;
  if (format !== 'md' && format !== 'html' && format !== 'pdf') throw new Error('Unsupported export format.');
  if (typeof value.suggestedName !== 'string' || typeof value.content !== 'string' || typeof value.html !== 'string') throw new Error('Invalid export content.');
  const filters = format === 'md'
    ? [{ name: 'Markdown', extensions: ['md'] }]
    : format === 'html' ? [{ name: 'HTML', extensions: ['html'] }] : [{ name: 'PDF', extensions: ['pdf'] }];
  const options = { defaultPath: value.suggestedName, filters };
  const result = mainWindow ? await dialog.showSaveDialog(mainWindow, options) : await dialog.showSaveDialog(options);
  if (result.canceled || !result.filePath) return { canceled: true };
  if (format !== 'pdf') {
    fs.writeFileSync(result.filePath, value.content, 'utf8');
    return { canceled: false, path: result.filePath };
  }
  const exportWindow = new BrowserWindow({
    show: false,
    webPreferences: { contextIsolation: true, nodeIntegration: false, sandbox: true, webSecurity: true }
  });
  try {
    await exportWindow.loadURL(`data:text/html;charset=utf-8,${encodeURIComponent(value.html)}`);
    const pdf = await exportWindow.webContents.printToPDF({ printBackground: true, pageSize: 'A4' });
    fs.writeFileSync(result.filePath, pdf);
    return { canceled: false, path: result.filePath };
  } finally {
    exportWindow.destroy();
  }
});

ipcMain.handle('dialog:pick-files', async (event, filters: { name: string; extensions: string[] }[]) => {
  if (!event.senderFrame || !isTrustedRendererUrl(event.senderFrame.url)) throw new Error('File picker request rejected from an untrusted page.');
  if (!mainWindow) return null;
  // Windows 的 dialog.showOpenDialog 已知行为：filters 首项 extensions 含 '*' 时会被忽略，
  // 回退到下一个具体扩展项。因此检测到首项是「所有文件」时干脆不传 filters，让对话框显示全部文件。
  const normalized = filters && filters.length > 0 ? filters : [{ name: '所有文件', extensions: ['*'] }];
  const allFilesFirst = normalized[0]?.extensions.includes('*');
  const dialogFilters = allFilesFirst ? undefined : normalized;
  const result = await dialog.showOpenDialog(mainWindow, {
    properties: ['openFile', 'multiSelections'],
    filters: dialogFilters
  });
  if (result.canceled || result.filePaths.length === 0) return null;
  return result.filePaths.map((filePath) => {
    const stat = fs.statSync(filePath);
    return { path: filePath, name: path.basename(filePath), size: stat.size };
  });
});

ipcMain.handle('dialog:pick-directory', async (event) => {
  if (!event.senderFrame || !isTrustedRendererUrl(event.senderFrame.url)) throw new Error('Directory picker request rejected from an untrusted page.');
  if (!mainWindow) return null;
  const result = await dialog.showOpenDialog(mainWindow, {
    properties: ['openDirectory', 'createDirectory']
  });
  if (result.canceled || result.filePaths.length === 0) return null;
  return result.filePaths[0];
});

ipcMain.handle('file:read-file', async (event, filePath: string) => {
  if (!event.senderFrame || !isTrustedRendererUrl(event.senderFrame.url)) throw new Error('File read request rejected from an untrusted page.');
  const buffer = fs.readFileSync(filePath);
  return buffer.buffer.slice(buffer.byteOffset, buffer.byteOffset + buffer.byteLength);
});

// 在系统文件管理器中打开本地路径（备份目录/备份文件）。仅允许绝对路径，不做协议校验。
ipcMain.handle('shell:open-path', async (event, targetPath: string) => {
  if (!event.senderFrame || !isTrustedRendererUrl(event.senderFrame.url)) throw new Error('Open-path request rejected from an untrusted page.');
  if (!targetPath || !path.isAbsolute(targetPath)) throw new Error('仅允许打开绝对路径');
  const errorMessage = await shell.openPath(targetPath);
  if (errorMessage) throw new Error(errorMessage);
});

ipcMain.handle('shell:open-external', async (event, url: string) => {
  if (!event.senderFrame || !isTrustedRendererUrl(event.senderFrame.url)) throw new Error('Open-external request rejected from an untrusted page.');
  try {
    const parsed = new URL(url);
    if (parsed.protocol !== 'http:' && parsed.protocol !== 'https:') {
      throw new Error(`不允许的协议: ${parsed.protocol}`);
    }
    await shell.openExternal(url);
  } catch (error) {
    console.error('[shell:open-external] rejected', error);
  }
});

// 抓取网址视频的原始标题（YouTube oEmbed / Bilibili view 接口）。
// 渲染进程直接 fetch 会被 CORS 拦截，故由主进程的网络栈代为请求。
async function fetchVideoTitle(url: string): Promise<string | null> {
  try {
    const parsed = new URL(url);
    const host = parsed.hostname.replace(/^www\./, '');
    if (host === 'youtube.com' || host === 'm.youtube.com' || host === 'youtu.be') {
      const oembedUrl = `https://www.youtube.com/oembed?url=${encodeURIComponent(url)}&format=json`;
      const res = await net.fetch(oembedUrl);
      if (!res.ok) return null;
      const data = await res.json() as { title?: string };
      return typeof data.title === 'string' && data.title.trim() ? data.title.trim() : null;
    }
    if (host === 'bilibili.com' || host === 'm.bilibili.com') {
      const match = parsed.pathname.match(/\/video\/(BV[\w]+|av(\d+))/i);
      if (!match) return null;
      const apiUrl = match[1].toUpperCase().startsWith('BV')
        ? `https://api.bilibili.com/x/web-interface/view?bvid=${encodeURIComponent(match[1])}`
        : `https://api.bilibili.com/x/web-interface/view?aid=${encodeURIComponent(match[2] ?? '')}`;
      const res = await net.fetch(apiUrl);
      if (!res.ok) return null;
      const data = await res.json() as { code?: number; data?: { title?: string } };
      if (data.code === 0 && data.data && typeof data.data.title === 'string' && data.data.title.trim()) {
        return data.data.title.trim();
      }
      return null;
    }
    return null;
  } catch (error) {
    console.error('[video:fetch-title] failed', error);
    return null;
  }
}

ipcMain.handle('video:fetch-title', async (event, url: string) => {
  if (!event.senderFrame || !isTrustedRendererUrl(event.senderFrame.url)) throw new Error('Video title request rejected from an untrusted page.');
  if (typeof url !== 'string' || !url.trim()) return null;
  return fetchVideoTitle(url.trim());
});

async function startPrimaryInstance(): Promise<void> {
  await app.whenReady();
  // 开发模式（加载 vite dev server）下不注入 CSP：@vitejs/plugin-react 的 preamble 内联脚本会被拦截导致白屏
  const devMode = /^https?:\/\//.test(rendererEntry());
  session.defaultSession.webRequest.onHeadersReceived((details, callback) => {
    // 不要覆盖第三方 iframe 内容（B站/YouTube 等）的原始 CSP，
    // 否则我们的 default-src 会阻止它们的脚本加载
    if (devMode || !details.url.startsWith('http://127.0.0.1:') && !details.url.startsWith('http://localhost:') && !details.url.startsWith('file://')) {
      callback({ responseHeaders: details.responseHeaders });
      return;
    }
    callback({
      responseHeaders: {
        ...details.responseHeaders,
        'Content-Security-Policy': [
          "default-src 'self' http://127.0.0.1:*; connect-src 'self' http://127.0.0.1:* ws://127.0.0.1:*; img-src 'self' data: http://127.0.0.1:*; media-src 'self' http://127.0.0.1:* https: http:; frame-src 'self' http://127.0.0.1:* https://www.youtube.com https://player.bilibili.com; child-src 'self' http://127.0.0.1:* https://www.youtube.com https://player.bilibili.com; style-src 'self' 'unsafe-inline'; font-src 'self' data:"
        ]
      }
    });
  });

  // Bilibili 的 view 接口（用于主进程抓取视频标题）对缺失 Referer 的请求可能返回 -412，
  // 这里为主进程发起的 api.bilibili.com 请求补上格式良好的 Referer。
  // 注：YouTube 嵌入在 file:// 下无法稳定播放（缺 Referer 报 153、伪造 Referer 报 152），
  // 前端已改为直接显示「该网站暂不支持预览」提示卡片，故不再注入 YouTube 的 Referer。
  session.defaultSession.webRequest.onBeforeSendHeaders(
    { urls: ['https://api.bilibili.com/*'] },
    (details, callback) => {
      const headers = { ...details.requestHeaders };
      const hasReferer = Object.keys(headers).some((key) => key.toLowerCase() === 'referer');
      if (!hasReferer) {
        headers.Referer = 'https://www.bilibili.com/';
      }
      callback({ requestHeaders: headers });
    }
  );

  updateHost = new PortableUpdateHost({
    paths: portablePaths,
    mainWindow: () => mainWindow,
    assistantWindow: () => assistantWindow,
    backend: () => backend,
    trusted: trustedSender,
    quitForUpdate: () => { updateQuit = true; quitting = true; app.quit(); }
  });
  if (await updateHost.recoverIfNeeded()) return;
  const updateBeforeWork = updateHost.bootingUpdate || updateHost.updater.getState().settings.automaticUpdate;
  if (updateBeforeWork) {
    updateHost.updater.setStartupBusy(true);
    createWindow();
    if (await updateHost.updater.startup(updateHost.bootingUpdate)) return;
  }
  try {
    backend = await startBackend(portablePaths);
  } catch (error) {
    settleBackendStartup!();
    if (updateHost.awaitingBoot) {
      updateHost.failBoot(error instanceof Error ? error.message : String(error));
      quitting = true;
      app.quit();
      return;
    }
    updateHost.backendUnavailable();
    console.error('[backend] startup failed', error);
    await dialog.showMessageBox({
      type: 'warning',
      title: '后端未启动',
      message: '后端服务未能启动，界面仍会打开。请先构建 backend，再重启应用。',
      detail: error instanceof Error ? error.message : String(error)
    });
  }
  settleBackendStartup!();
  if (!mainWindow) createWindow();
  if (backend) updateHost.backendReady();
}

if (ownsSingleInstanceLock) {
  app.on('second-instance', activateMainWindow);
  app.on('window-all-closed', () => {
    if (process.platform !== 'darwin') app.quit();
  });
  app.on('before-quit', () => {
    updateHost?.dispose();
    if (!updateQuit) stopBackend(backend);
    clearPortableTemp(getPortablePaths(portablePaths.root));
  });
  void startPrimaryInstance();
} else {
  // 不注册退出清理，否则重复实例可能删除正在运行的主实例的临时文件。
  app.quit();
}
