import { app, BrowserWindow, dialog, ipcMain, session, shell } from 'electron';
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
  const result = await dialog.showOpenDialog(mainWindow, {
    properties: ['openFile', 'multiSelections'],
    filters: filters && filters.length > 0 ? filters : [{ name: '所有文件', extensions: ['*'] }]
  });
  if (result.canceled || result.filePaths.length === 0) return null;
  return result.filePaths.map((filePath) => {
    const stat = fs.statSync(filePath);
    return { path: filePath, name: path.basename(filePath), size: stat.size };
  });
});

ipcMain.handle('file:read-file', async (event, filePath: string) => {
  if (!event.senderFrame || !isTrustedRendererUrl(event.senderFrame.url)) throw new Error('File read request rejected from an untrusted page.');
  const buffer = fs.readFileSync(filePath);
  return buffer.buffer.slice(buffer.byteOffset, buffer.byteOffset + buffer.byteLength);
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

ipcMain.handle('window:set-full-screen', (event, flag: boolean) => {
  if (!event.senderFrame || !isTrustedRendererUrl(event.senderFrame.url)) throw new Error('Full-screen request rejected from an untrusted page.');
  if (mainWindow) mainWindow.setFullScreen(Boolean(flag));
});

ipcMain.handle('window:is-full-screen', (event) => {
  if (!event.senderFrame || !isTrustedRendererUrl(event.senderFrame.url)) throw new Error('Full-screen query rejected from an untrusted page.');
  return mainWindow?.isFullScreen() ?? false;
});

app.whenReady().then(async () => {
  session.defaultSession.webRequest.onHeadersReceived((details, callback) => {
    // 不要覆盖第三方 iframe 内容（B站/YouTube 等）的原始 CSP，
    // 否则我们的 default-src 会阻止它们的脚本加载
    if (!details.url.startsWith('http://127.0.0.1:') && !details.url.startsWith('http://localhost:') && !details.url.startsWith('file://')) {
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
