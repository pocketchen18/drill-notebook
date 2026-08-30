import { app, BrowserWindow, dialog, ipcMain, net, session, shell } from 'electron';
import fs from 'node:fs';
import path from 'node:path';
import { pathToFileURL } from 'node:url';
import { clearPortableTemp, getPortablePaths, setupPortablePaths, type PortablePaths } from './paths';
import { startBackend, stopBackend, type BackendHandle } from './java-bridge';

let portablePaths: PortablePaths;
let backend: BackendHandle | undefined;
let mainWindow: BrowserWindow | undefined;

// This must run before ready so Electron never initializes a system profile first.
portablePaths = setupPortablePaths();

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
      if (raw.theme === 'dark') return '#17181a';
    }
  } catch {
    /* ignore */
  }
  return '#f5f7fa';
}

function createWindow(): void {
  mainWindow = new BrowserWindow({
    width: 1440,
    height: 920,
    minWidth: 1100,
    minHeight: 720,
    backgroundColor: windowBackgroundColor(),
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

  const entry = rendererEntry();
  if (/^https?:\/\//.test(entry)) {
    void mainWindow.loadURL(entry);
  } else {
    void mainWindow.loadFile(entry);
  }
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

ipcMain.handle('backend:get-base-url', () => backend?.baseUrl ?? 'http://127.0.0.1:18080');
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

app.whenReady().then(async () => {
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

  try {
    backend = await startBackend(portablePaths);
  } catch (error) {
    console.error('[backend] startup failed', error);
    await dialog.showMessageBox({
      type: 'warning',
      title: '后端未启动',
      message: '后端服务未能启动，界面仍会打开。请先构建 backend，再重启应用。',
      detail: error instanceof Error ? error.message : String(error)
    });
  }
  createWindow();
});

app.on('window-all-closed', () => {
  if (process.platform !== 'darwin') app.quit();
});

app.on('before-quit', () => {
  stopBackend(backend);
  clearPortableTemp(getPortablePaths(portablePaths.root));
});
