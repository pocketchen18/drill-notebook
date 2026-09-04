// 用 Electron 自带的 Chromium 把 resources/icon/icon.svg 渲染成应用图标（无需额外依赖）：
//   resources/icon/icon.png  —— 512×512（electron-builder / 文档预览）
//   resources/icon/icon.ico  —— 16…256 多尺寸（窗口图标 + 便携 exe 图标）
// 用法：npm run build:icons（= electron scripts/build-icons.mjs）。改了 icon.svg 后重新运行并提交产物。
// 实现：隐藏窗口内把 SVG 按目标尺寸逐个画到 <canvas>（Chromium 会按目标尺寸重新光栅化，小图标依然锐利），
// 再把 PNG 数据传回主进程打包；不依赖 capturePage（隐藏窗口下可能永不出帧）。
import { app, BrowserWindow } from 'electron';
import { spawn } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = fileURLToPath(new URL('..', import.meta.url));
const iconDir = path.join(root, 'resources', 'icon');
const svgPath = path.join(iconDir, 'icon.svg');
const scratch = path.join(root, 'runtime-portable', 'icon-build');
const PNG_SIZE = 512;
const ICO_SIZES = [16, 20, 24, 32, 40, 48, 64, 128, 256];

// 绿色便携硬约束：Electron 的用户数据 / 缓存 / 临时目录一律指向仓库内，不写系统盘。
fs.mkdirSync(scratch, { recursive: true });
app.setPath('userData', path.join(scratch, 'userdata'));
app.setPath('sessionData', path.join(scratch, 'userdata'));
app.setPath('cache', path.join(scratch, 'cache'));
app.setPath('temp', path.join(scratch, 'tmp'));
app.disableHardwareAcceleration();

// 看门狗：渲染异常卡住时不要挂成僵尸进程
const watchdog = setTimeout(() => {
  console.error('[build-icons] timed out after 60s');
  app.exit(1);
}, 60_000);

/** ICO 容器：每个条目直接内嵌 PNG（Vista+ 支持，electron-builder 也按此解析）。 */
function buildIco(entries) {
  const header = Buffer.alloc(6);
  header.writeUInt16LE(0, 0);
  header.writeUInt16LE(1, 2);
  header.writeUInt16LE(entries.length, 4);
  const directory = Buffer.alloc(16 * entries.length);
  let offset = header.length + directory.length;
  entries.forEach(({ size, png }, index) => {
    const at = index * 16;
    directory.writeUInt8(size >= 256 ? 0 : size, at);
    directory.writeUInt8(size >= 256 ? 0 : size, at + 1);
    directory.writeUInt8(0, at + 2);
    directory.writeUInt8(0, at + 3);
    directory.writeUInt16LE(1, at + 4);
    directory.writeUInt16LE(32, at + 6);
    directory.writeUInt32LE(png.length, at + 8);
    directory.writeUInt32LE(offset, at + 12);
    offset += png.length;
  });
  return Buffer.concat([header, directory, ...entries.map((entry) => entry.png)]);
}

/** 在渲染进程里把 SVG 画成各尺寸 PNG，返回 { size: base64 }。 */
async function rasterize(sizes) {
  const svg = fs.readFileSync(svgPath, 'utf8');
  const html = '<!doctype html><html><head><meta charset="utf-8"></head><body>' +
    `<img id="icon" src="data:image/svg+xml;base64,${Buffer.from(svg, 'utf8').toString('base64')}"></body></html>`;
  const win = new BrowserWindow({
    width: 640,
    height: 640,
    show: false,
    webPreferences: { backgroundThrottling: false, sandbox: true }
  });
  try {
    console.error('[build-icons] window created, loading svg…');
    await win.loadURL(`data:text/html;charset=utf-8,${encodeURIComponent(html)}`);
    console.error('[build-icons] page loaded, rasterizing…');
    return await win.webContents.executeJavaScript(`(async () => {
      const img = document.getElementById('icon');
      if (!img.complete) await new Promise((resolve) => { img.onload = resolve; img.onerror = resolve; });
      // 隐藏窗口下 decode() 可能不回调，最多等 2s 后直接绘制
      await Promise.race([img.decode().catch(() => undefined), new Promise((resolve) => setTimeout(resolve, 2000))]);
      if (!img.naturalWidth) throw new Error('svg failed to load');
      const out = {};
      for (const size of ${JSON.stringify(sizes)}) {
        const canvas = document.createElement('canvas');
        canvas.width = size;
        canvas.height = size;
        const ctx = canvas.getContext('2d');
        ctx.imageSmoothingEnabled = true;
        ctx.imageSmoothingQuality = 'high';
        ctx.drawImage(img, 0, 0, size, size);
        out[size] = canvas.toDataURL('image/png').split(',')[1];
      }
      return out;
    })()`);
  } finally {
    win.destroy();
  }
}

// ESM 主入口不能顶层 await：Electron 会等模块求值结束才触发 ready，顶层 await whenReady() 会死锁。
async function main() {
  try {
    console.error('[build-icons] app ready');
    const sizes = [...new Set([PNG_SIZE, ...ICO_SIZES])];
    const rendered = await rasterize(sizes);
    const pngOf = (size) => Buffer.from(rendered[size], 'base64');
    fs.writeFileSync(path.join(iconDir, 'icon.png'), pngOf(PNG_SIZE));
    fs.writeFileSync(path.join(iconDir, 'icon.ico'), buildIco(ICO_SIZES.map((size) => ({ size, png: pngOf(size) }))));
    console.log(`icons written: ${path.relative(root, iconDir)}/icon.png (${PNG_SIZE}), icon.ico (${ICO_SIZES.join('/')})`);
  } catch (error) {
    console.error('[build-icons] failed', error);
    process.exitCode = 1;
  } finally {
    clearTimeout(watchdog);
    app.quit();
  }
}

// 用户数据目录（DIPS 等 sqlite 文件）直到进程退出都被占用：quit 时先试删，失败则交给退出后的独立清理进程。
app.once('quit', () => {
  try {
    fs.rmSync(scratch, { recursive: true, force: true, maxRetries: 5, retryDelay: 200 });
  } catch {
    spawn('cmd.exe', ['/d', '/c', `ping -n 3 127.0.0.1 >nul & rmdir /s /q "${scratch}"`], { detached: true, stdio: 'ignore', windowsHide: true }).unref();
  }
});

app.whenReady().then(main);
