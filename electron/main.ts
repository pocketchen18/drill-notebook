import { app, BrowserWindow, dialog, ipcMain, session } from 'electron';
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

function createWindow(): void {
  mainWindow = new BrowserWindow({
    width: 1440,
    height: 920,
    minWidth: 1100,
    minHeight: 720,
    backgroundColor: '#f5f7fa',
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

app.whenReady().then(async () => {
  session.defaultSession.webRequest.onHeadersReceived((details, callback) => {
    callback({
      responseHeaders: {
        ...details.responseHeaders,
        'Content-Security-Policy': [
          "default-src 'self' http://127.0.0.1:*; connect-src 'self' http://127.0.0.1:*; img-src 'self' data:; style-src 'self' 'unsafe-inline'; font-src 'self' data:"
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
