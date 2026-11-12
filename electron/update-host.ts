import { app, ipcMain, net, type BrowserWindow } from 'electron';
import fs from 'node:fs';
import path from 'node:path';
import { spawn } from 'node:child_process';
import { randomUUID } from 'node:crypto';
import { AppUpdater, transactionIdValid, type UpdateTransaction } from './app-updater';
import { assertNoLinks, writeAtomicJson } from './program-manifest';
import { AssistantDraftStore } from './assistant-drafts';
import { backendReadyForUpdate, shutdownBackendForUpdate, type BackendHandle } from './java-bridge';
import type { PortablePaths } from './paths';
import type { NotesRange, UpdatePreparationResult } from './update-types';

interface HostOptions {
  paths: PortablePaths;
  mainWindow(): BrowserWindow | undefined;
  assistantWindow(): BrowserWindow | undefined;
  backend(): BackendHandle | undefined;
  trusted(event: Electron.IpcMainInvokeEvent): boolean;
  quitForUpdate(): void;
}
interface PendingPreparation {
  id: string;
  waiting: Set<number>;
  failures: string[];
  finish(result: { ok: boolean; reason?: string }): void;
}
const pause = (ms: number) => new Promise<void>(resolve => setTimeout(resolve, ms));
const readJson = <T>(file: string): T => JSON.parse(fs.readFileSync(file, 'utf8')) as T;
const alive = (pid?: number): boolean => { if (!pid) return false; try { process.kill(pid, 0); return true; } catch { return false; } };

export class PortableUpdateHost {
  readonly updater: AppUpdater;
  readonly bootingUpdate: boolean;
  get awaitingBoot(): boolean { return !!this.bootId; }
  private mainReady = false;
  private backendStarted = false;
  private checksStarted = false;
  private pending?: PendingPreparation;
  private bootId?: string;
  private readonly drafts: AssistantDraftStore;

  constructor(private readonly options: HostOptions) {
    this.bootId = transactionIdValid(process.env.DRILL_UPDATE_TRANSACTION) ? process.env.DRILL_UPDATE_TRANSACTION : undefined;
    this.bootingUpdate = !!this.bootId;
    this.drafts = new AssistantDraftStore(options.paths.root);
    this.updater = new AppUpdater({
      paths: options.paths, currentVersion: app.getVersion(), packaged: app.isPackaged,
      platform: process.platform, arch: process.arch, executable: process.execPath,
      helper: app.isPackaged ? path.join(process.resourcesPath, 'updater', 'drill-updater.exe') : path.join(__dirname, '..', 'updater', 'target', 'release', 'drill-updater.exe'),
      fetch: (url, init) => net.fetch(url, init),
      onState: state => this.broadcast('updater:state', state),
      prepare: () => this.prepare(), commit: file => this.commit(file),
      processIds: () => ({ main: process.pid, backend: options.backend()?.process.pid ?? 0 }),
      bootGuard: this.bootingUpdate
    });
    this.registerIpc();
  }
  private windows(): BrowserWindow[] {
    return [this.options.mainWindow(), this.options.assistantWindow()].filter((w): w is BrowserWindow => !!w && !w.isDestroyed());
  }
  private broadcast(channel: string, value?: unknown): void { for (const window of this.windows()) window.webContents.send(channel, value); }
  private authorized(event: Electron.IpcMainInvokeEvent, mainOnly = false): boolean {
    return this.options.trusted(event) && (!mainOnly || event.sender === this.options.mainWindow()?.webContents);
  }
  private registerIpc(): void {
    const action = (channel: string, mainOnly: boolean, run: (event: Electron.IpcMainInvokeEvent, ...args: any[]) => unknown) => {
      ipcMain.handle(channel, (event, ...args) => {
        if (!this.authorized(event, mainOnly)) throw new Error('更新请求来源无效。');
        return run(event, ...args);
      });
    };
    action('updater:get-state', false, () => this.updater.getState());
    action('updater:set-settings', true, (_event, patch) => this.updater.setSettings(patch));
    action('updater:check', true, () => this.updater.check());
    action('updater:open-available', true, () => this.updater.openAvailable());
    action('updater:open-history', true, () => this.updater.openHistory());
    action('updater:close-presentation', true, (_event, id: number) => this.updater.closePresentation(id));
    action('updater:notes', true, (_event, range: NotesRange, version?: string, refresh?: boolean) => this.updater.notes(range, version, refresh === true));
    action('updater:skip', true, (_event, version: string) => this.updater.skip(version));
    action('updater:restore-reminder', true, () => this.updater.restoreReminder());
    action('updater:install', true, (_event, version: string) => this.updater.install(version));
    action('updater:cancel-download', true, () => this.updater.cancelDownload());
    action('updater:resume-install', true, () => this.updater.resumeInstall());
    action('updater:prepared', false, (event, result: UpdatePreparationResult) => this.prepared(event, result));
    action('updater:renderer-ready', false, event => {
      if (event.sender !== this.options.mainWindow()?.webContents) return;
      this.mainReady = true;
      this.finishStartup();
    });
    action('updater:read-assistant-drafts', false, () => this.drafts.read());
    action('updater:save-assistant-drafts', false, (_event, value: unknown) => this.drafts.save(value));
  }
  backendReady(): void { this.backendStarted = true; this.finishStartup(); }
  backendUnavailable(): void { this.updater.setStartupBusy(false); }
  private finishStartup(): void {
    if (!this.mainReady || !this.backendStarted) return;
    if (this.bootId) {
      const file = assertNoLinks(this.options.paths.root, `runtime/updater/${this.bootId}/transaction.json`);
      const transaction = readJson<UpdateTransaction>(file);
      if (transaction.toVersion !== app.getVersion() || transaction.appRoot !== this.options.paths.root) throw new Error('新版本启动事务不匹配。');
      writeAtomicJson(path.join(path.dirname(file), 'health.json'), {
        phase: 'healthy', version: app.getVersion(), pid: process.pid, backendPid: this.options.backend()?.process.pid ?? 0
      });
      this.bootId = undefined;
      delete process.env.DRILL_UPDATE_TRANSACTION;
    }
    this.updater.recordSuccessfulBoot();
    this.updater.setStartupBusy(false);
    if (!this.checksStarted) {
      this.checksStarted = true;
      void this.updater.startup(this.bootingUpdate);
    }
    const cleanup = setTimeout(() => this.updater.cleanupOwnedArtifacts(), 10_000);
    cleanup.unref();
  }
  failBoot(reason: string): void {
    if (!this.bootId) return;
    writeAtomicJson(assertNoLinks(this.options.paths.root, `runtime/updater/${this.bootId}/health.json`), {
      phase: 'failed', version: app.getVersion(), pid: process.pid, backendPid: this.options.backend()?.process.pid ?? 0, error: reason
    });
  }
  async recoverIfNeeded(): Promise<boolean> {
    const root = this.options.paths.root;
    let active: { id?: string };
    try { active = readJson(assertNoLinks(root, 'runtime/updater/active.json')); } catch { return false; }
    if (!transactionIdValid(active.id)) return false;
    const work = assertNoLinks(root, `runtime/updater/${active.id}`);
    const transaction = readJson<UpdateTransaction>(path.join(work, 'transaction.json'));
    if (this.bootId === active.id && transaction.toVersion === app.getVersion()) {
      writeAtomicJson(path.join(work, 'health.json'), { phase: 'starting', version: app.getVersion(), pid: process.pid, backendPid: 0 });
      return false;
    }
    const status = readJson<{ phase: string; helperPid?: number }>(path.join(work, 'status.json'));
    if (['complete', 'rolled-back'].includes(status.phase)) return false;
    try {
      const health = readJson<{ phase: string; version: string }>(path.join(work, 'health.json'));
      if (health.phase === 'healthy' && health.version === transaction.toVersion) {
        writeAtomicJson(path.join(work, 'status.json'), { ...status, phase: 'complete' });
        return false;
      }
    } catch { /* No commit acknowledgement. */ }
    if (!alive(status.helperPid)) {
      const child = spawn(path.join(work, 'drill-updater.exe'), ['--recover', path.join(work, 'transaction.json'), String(process.pid)], {
        cwd: root, detached: true, windowsHide: true, stdio: 'ignore', env: { ...process.env, APP_ROOT: root }
      });
      await new Promise<void>((resolve, reject) => { child.once('spawn', resolve); child.once('error', reject); });
      child.unref();
    }
    this.options.quitForUpdate();
    return true;
  }
  private prepared(event: Electron.IpcMainInvokeEvent, result: UpdatePreparationResult): void {
    const pending = this.pending;
    if (!pending || !result || result.id !== pending.id || typeof result.ok !== 'boolean' || !pending.waiting.has(event.sender.id)) return;
    pending.waiting.delete(event.sender.id);
    if (!result.ok) pending.failures.push(typeof result.reason === 'string' ? result.reason.slice(0, 1000) : '有未保存的内容，请先保存后继续更新。');
    if (!pending.waiting.size) pending.finish({ ok: !pending.failures.length, reason: pending.failures.join('\n') || undefined });
  }
  private async prepare(): Promise<{ ok: boolean; reason?: string }> {
    const backend = await backendReadyForUpdate(this.options.backend()).catch(() => ({ ok: false, reason: '暂时无法核对后端保存状态，请稍候。' }));
    if (!backend.ok) return backend;
    const windows = this.windows();
    if (!windows.length) return { ok: true };
    if (!this.mainReady) return { ok: false, reason: '正在等待界面就绪，随后会自动继续更新。' };
    const result = await new Promise<{ ok: boolean; reason?: string }>(resolve => {
      const id = randomUUID();
      const timer = setTimeout(() => { this.pending = undefined; resolve({ ok: false, reason: '窗口暂未响应保存请求，请先完成当前操作。' }); }, 15_000);
      this.pending = { id, waiting: new Set(windows.map(window => window.webContents.id)), failures: [], finish: result => {
        clearTimeout(timer); this.pending = undefined; resolve(result);
      } };
      this.broadcast('updater:prepare', { id });
    });
    if (!result.ok) this.broadcast('updater:preparation-end');
    return result;
  }
  private async commit(transactionFile: string): Promise<void> {
    const work = path.dirname(transactionFile);
    const child = spawn(path.join(work, 'drill-updater.exe'), ['--transaction', transactionFile], {
      cwd: this.options.paths.root, detached: true, windowsHide: true, stdio: 'ignore',
      env: { ...process.env, APP_ROOT: this.options.paths.root }
    });
    let handedOff = false;
    try {
      await new Promise<void>((resolve, reject) => { child.once('spawn', resolve); child.once('error', reject); });
      const deadline = Date.now() + 30_000;
      let ready = false;
      while (Date.now() < deadline && child.exitCode === null) {
        try {
          const ack = readJson<{ pid?: number }>(path.join(work, 'ready.json'));
          if (ack.pid === child.pid) { ready = true; break; }
        } catch { /* The helper is validating staged files. */ }
        await pause(100);
      }
      if (!ready) throw new Error('便携替换程序未能准备就绪，原程序未被修改。');
      await shutdownBackendForUpdate(this.options.backend());
      handedOff = true;
      child.unref();
      this.options.quitForUpdate();
    } finally {
      if (!handedOff) {
        // Only our waiting helper is stopped. Application and AI tasks continue normally.
        child.kill();
        this.broadcast('updater:preparation-end');
      }
    }
  }
  dispose(): void {
    this.updater.dispose();
    this.pending?.finish({ ok: false, reason: '应用正在退出。' });
    this.broadcast('updater:preparation-end');
  }
}
