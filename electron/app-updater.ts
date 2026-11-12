import fs from './update-fs';
import path from 'node:path';
import { randomUUID } from 'node:crypto';
import { pipeline } from 'node:stream/promises';
import { Readable, Transform } from 'node:stream';
import type { PortablePaths } from './paths';
import { assetUrlAllowed, compareVersions, downloadDestinationAllowed, fetchReleases, orderReleases, readLimitedText,
  REPOSITORY_URL, selectNotes, selectPending, versionOf, type UpdateFetch } from './update-feed';
import { APP_EXECUTABLE, assertNoLinks, fileDigest, PROGRAM_MANIFEST, validateManifest, writeAtomicJson, type ProgramManifest } from './program-manifest';
import { extractUpdateArchive } from './update-archive';
import type { AppUpdateState, NotesRange, UpdateNotes, UpdateRelease, UpdateSettings } from './update-types';

export const DEFAULT_UPDATE_SETTINGS: UpdateSettings = {
  automaticUpdate: false, automaticCheck: true, intervalHours: 24, startupReminder: true
};
interface Preferences {
  format: 1;
  settings: UpdateSettings;
  lastCheckAt?: number;
  lastReminderAt?: number;
  skippedVersion?: string;
  lastRunVersion?: string;
  latestVersion?: string;
  failedVersion?: string;
  failure?: string;
}
export interface UpdateTransaction {
  format: 1;
  id: string;
  appRoot: string;
  fromVersion: string;
  toVersion: string;
  parentPid: number;
  backendPid: number;
  bootTimeoutSeconds: number;
  files: Array<{ path: string; size: number; sha256: string }>;
  remove: string[];
}
export interface UpdaterOptions {
  paths: PortablePaths;
  currentVersion: string;
  packaged: boolean;
  platform: string;
  arch: string;
  executable: string;
  helper: string;
  fetch: UpdateFetch;
  onState(state: AppUpdateState): void;
  prepare(): Promise<{ ok: boolean; reason?: string }>;
  commit(transactionFile: string): Promise<void>;
  processIds(): { main: number; backend: number };
  now?: () => number;
  bootGuard?: boolean;
}
const activePhases = new Set(['downloading', 'verifying', 'preparing', 'blocked', 'restarting']);
export const transactionIdValid = (id: unknown): id is string => typeof id === 'string'
  && /^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/.test(id);
const timeOf = (value: unknown): number | undefined => typeof value === 'number' && Number.isSafeInteger(value) && value > 0 ? value : undefined;
const errorText = (error: unknown): string => error instanceof Error ? error.message : '更新失败，请稍后重试。';
const readJson = (file: string): unknown => JSON.parse(fs.readFileSync(file, 'utf8'));

/** Only this main-process service owns checks, preferences, the presentation, and the install transaction. */
export class AppUpdater {
  private preferences: Preferences;
  private releases: UpdateRelease[] = [];
  private cacheAt = 0;
  private state: AppUpdateState;
  private feedTask?: Promise<UpdateRelease[]>;
  private checkTask?: Promise<void>;
  private installTask?: Promise<void>;
  private manualOpen = false;
  private reminderShown = false;
  private timer?: ReturnType<typeof setTimeout>;
  private retryAt = 0;
  private download?: AbortController;
  private disposed = false;
  private presentationSequence = 0;
  private wakePreparation?: () => void;
  private startupFinished = false;
  private readonly settingsFile: string;
  private readonly cacheFile: string;
  private readonly clock: () => number;

  constructor(private readonly options: UpdaterOptions) {
    this.clock = options.now ?? Date.now;
    const root = options.paths.root;
    this.settingsFile = assertNoLinks(root, 'config/update-settings.json');
    this.cacheFile = assertNoLinks(root, 'cache/updates/releases.json');
    fs.mkdirSync(path.dirname(this.settingsFile), { recursive: true });
    fs.mkdirSync(path.dirname(this.cacheFile), { recursive: true });
    this.preferences = this.readPreferences();
    this.readCache();
    const current = versionOf(options.currentVersion);
    const previous = this.preferences.lastRunVersion;
    const target = this.releases.find(r => r.version === this.preferences.latestVersion && current && compareVersions(r.version, current) > 0);
    this.state = {
      currentVersion: options.currentVersion, development: !options.packaged,
      installSupported: options.packaged && options.platform === 'win32' && options.arch === 'x64'
        && path.resolve(path.dirname(options.executable)).toLowerCase() === path.resolve(root).toLowerCase(),
      settings: { ...this.preferences.settings }, checking: false,
      checkStatus: target ? 'available' : 'unchecked', target,
      lastCheckAt: this.preferences.lastCheckAt, lastReminderAt: this.preferences.lastReminderAt,
      skippedVersion: this.preferences.skippedVersion, failedVersion: this.preferences.failedVersion, failure: this.preferences.failure,
      sinceVersion: current && previous && compareVersions(previous, current) < 0 ? previous : undefined,
      updateVisible: !!target && target.version !== this.preferences.skippedVersion,
      startupBusy: options.bootGuard === true, progress: { phase: 'idle', received: 0, bytesPerSecond: 0 }
    };
    this.importRecoveryResult();
  }

  getState(): AppUpdateState { return structuredClone(this.state); }
  setStartupBusy(busy: boolean): void { this.state.startupBusy = busy; this.schedule(); this.emit(); }
  private emit(): void { this.options.onState(this.getState()); }
  private persist(): void { assertNoLinks(this.options.paths.root, 'config/update-settings.json'); writeAtomicJson(this.settingsFile, this.preferences); }
  private setPresentation(kind: 'available' | 'progress' | 'history', version?: string): void {
    this.state.presentation = { id: ++this.presentationSequence, kind, ...(version ? { version } : {}) };
    this.emit();
  }
  closePresentation(id: number): void {
    if (this.state.presentation?.id !== id) return;
    this.state.presentation = undefined;
    this.emit();
  }
  private readPreferences(): Preferences {
    const fallback: Preferences = { format: 1, settings: { ...DEFAULT_UPDATE_SETTINGS } };
    try {
      const raw = readJson(this.settingsFile) as Partial<Preferences>;
      if (!raw || raw.format !== 1) return fallback;
      const settings: Partial<UpdateSettings> = raw.settings ?? {};
      fallback.settings = {
        automaticUpdate: typeof settings.automaticUpdate === 'boolean' ? settings.automaticUpdate : false,
        automaticCheck: typeof settings.automaticCheck === 'boolean' ? settings.automaticCheck : true,
        startupReminder: typeof settings.startupReminder === 'boolean' ? settings.startupReminder : true,
        intervalHours: typeof settings.intervalHours === 'number' && Number.isInteger(settings.intervalHours) && settings.intervalHours >= 1 && settings.intervalHours <= 168 ? settings.intervalHours : 24
      };
      fallback.lastCheckAt = timeOf(raw.lastCheckAt);
      fallback.lastReminderAt = timeOf(raw.lastReminderAt);
      for (const name of ['skippedVersion', 'lastRunVersion', 'latestVersion', 'failedVersion'] as const) fallback[name] = versionOf(raw[name]);
      if (typeof raw.failure === 'string') fallback.failure = raw.failure.slice(0, 2000);
    } catch { /* First run / malformed preferences: automatic installation stays off. */ }
    return fallback;
  }
  private readCache(): void {
    try {
      if (fs.statSync(this.cacheFile).size > 128 * 1024 ** 2) return;
      const raw = readJson(this.cacheFile) as { format?: number; at?: number; releases?: UpdateRelease[] };
      if (raw.format !== 1 || !Array.isArray(raw.releases) || raw.releases.length > 1000) return;
      this.releases = orderReleases(raw.releases.filter(r => versionOf(r.version) === r.version
        && versionOf(r.tag) === r.version && typeof r.body === 'string' && typeof r.name === 'string'
        && typeof r.publishedAt === 'string' && r.url === `${REPOSITORY_URL}/releases/tag/${encodeURIComponent(r.tag)}`
        && (!r.asset || this.validCachedAsset(r.asset, `Drill-Notebook-${r.version}-win-x64-portable.zip`, r.tag))
        && (!r.checksum || this.validCachedAsset(r.checksum, `Drill-Notebook-${r.version}-win-x64-portable.zip.sha256`, r.tag))));
      this.cacheAt = timeOf(raw.at) ?? 0;
    } catch { /* Cache failure never becomes a successful check. */ }
  }
  private validCachedAsset(asset: UpdateRelease['asset'], name: string, tag: string): boolean {
    return !!asset && asset.name === name && assetUrlAllowed(asset.url, tag)
      && Number.isSafeInteger(asset.size) && asset.size > 0 && asset.size <= 1024 ** 3
      && (!asset.digest || /^[a-f0-9]{64}$/.test(asset.digest));
  }
  private async feed(): Promise<UpdateRelease[]> {
    if (!this.feedTask) {
      this.feedTask = fetchReleases(this.options.fetch).finally(() => { this.feedTask = undefined; });
    }
    return this.feedTask;
  }
  private saveCache(releases: UpdateRelease[]): void {
    this.releases = orderReleases(releases);
    this.cacheAt = this.clock();
    assertNoLinks(this.options.paths.root, 'cache/updates/releases.json');
    writeAtomicJson(this.cacheFile, { format: 1, at: this.cacheAt, releases: this.releases });
  }

  setSettings(patch: unknown): AppUpdateState {
    if (!patch || typeof patch !== 'object' || Array.isArray(patch)) throw new Error('更新设置格式无效。');
    const next = { ...this.preferences.settings };
    for (const [key, value] of Object.entries(patch)) {
      if (key === 'intervalHours') {
        if (typeof value !== 'number' || !Number.isInteger(value) || value < 1 || value > 168) throw new Error('间隔必须为 1–168 的整数小时。');
        next.intervalHours = value;
      } else if (['automaticUpdate', 'automaticCheck', 'startupReminder'].includes(key) && typeof value === 'boolean') {
        next[key as 'automaticUpdate' | 'automaticCheck' | 'startupReminder'] = value;
      } else throw new Error('更新设置包含未知字段或无效值。');
    }
    this.preferences.settings = next;
    this.persist();
    this.state.settings = { ...next };
    this.schedule();
    this.emit();
    return this.getState();
  }

  async check(reason: 'manual' | 'startup' | 'background' = 'manual'): Promise<AppUpdateState> {
    if (reason === 'manual') this.manualOpen = true;
    if (!this.checkTask) {
      this.state.checking = true;
      this.state.checkError = undefined;
      this.emit();
      this.checkTask = (async () => {
        try {
          const installed = versionOf(this.state.currentVersion);
          if (!installed) throw new Error('无法识别当前运行版本，请通过 GitHub 查看更新。');
          const releases = await this.feed();
          if (!releases.length) throw new Error('GitHub 暂无可识别的发布版本。');
          this.saveCache(releases);
          this.state.target = releases.find(r => compareVersions(r.version, installed) > 0);
          this.state.checkStatus = this.state.target ? 'available' : 'current';
          this.state.lastCheckAt = this.preferences.lastCheckAt = this.clock();
          this.preferences.latestVersion = this.state.target?.version;
          this.state.updateVisible = !!this.state.target && this.state.target.version !== this.preferences.skippedVersion;
          this.retryAt = 0;
          this.persist();
        } catch (error) {
          this.state.checkStatus = 'error';
          this.state.checkError = errorText(error);
          this.retryAt = this.clock() + 15 * 60_000;
        } finally {
          this.state.checking = false;
          this.checkTask = undefined;
          const manual = this.manualOpen;
          this.manualOpen = false;
          this.schedule();
          this.emit();
          if (manual && this.state.checkStatus === 'available' && this.state.target) this.openAvailable();
        }
      })();
    }
    await this.checkTask;
    return this.getState();
  }

  /** Called once before restoring business UI if automatic installation is enabled. */
  async startup(bootingUpdate = false): Promise<boolean> {
    if (this.startupFinished) return false;
    this.startupFinished = true;
    this.cleanupOwnedArtifacts();
    if (bootingUpdate) return false; // A health acknowledgement must precede any further update attempt.
    const settings = this.preferences.settings;
    if (settings.automaticUpdate) {
      this.state.startupBusy = true;
      this.emit();
      await this.check('startup');
      const target = this.state.target;
      if (this.state.checkStatus === 'available' && target && target.version !== this.preferences.skippedVersion
        && target.version !== this.preferences.failedVersion && this.state.installSupported) {
        await this.install(target.version);
        if (this.state.progress.phase === 'restarting') return true;
      }
      this.schedule();
      return false;
    }
    if (!settings.automaticCheck) return false;
    const due = !this.preferences.lastCheckAt || this.clock() - this.preferences.lastCheckAt >= settings.intervalHours * 3600_000;
    if (due) await this.check('startup');
    // An earlier successful check is usable within its interval, but a failed check cannot open a stale automatic reminder.
    const fresh = this.preferences.lastCheckAt && this.clock() - this.preferences.lastCheckAt < settings.intervalHours * 3600_000;
    const remindDue = !this.preferences.lastReminderAt || this.clock() - this.preferences.lastReminderAt >= settings.intervalHours * 3600_000;
    if (fresh && this.state.checkStatus !== 'error' && settings.startupReminder && remindDue && this.state.updateVisible && !this.reminderShown) {
      this.reminderShown = true;
      this.openAvailable();
    }
    this.schedule();
    return false;
  }

  private schedule(): void {
    if (this.timer) clearTimeout(this.timer);
    this.timer = undefined;
    if (this.disposed || !this.startupFinished || !this.preferences.settings.automaticCheck || this.state.startupBusy || activePhases.has(this.state.progress.phase)) return;
    const nextCheck = (this.preferences.lastCheckAt ?? 0) + this.preferences.settings.intervalHours * 3600_000;
    const delay = Math.max(1000, Math.max(nextCheck, this.retryAt) - this.clock());
    this.timer = setTimeout(() => { void this.check('background'); }, Math.min(delay, 2 ** 31 - 1));
    this.timer.unref();
  }

  openAvailable(): void {
    if (activePhases.has(this.state.progress.phase)) { this.setPresentation('progress', this.state.progress.version); return; }
    if (!this.state.target) return;
    this.preferences.lastReminderAt = this.state.lastReminderAt = this.clock();
    this.persist();
    this.setPresentation('available', this.state.target.version);
  }
  openHistory(): void { this.setPresentation('history'); }
  async notes(range: NotesRange, version?: string, refresh = false): Promise<UpdateNotes> {
    if (!['since', 10, 20, 50, 100].includes(range)) throw new Error('更新内容范围无效。');
    const installed = versionOf(this.state.currentVersion);
    if (!installed) return { releases: [], range, cached: false, error: '无法识别当前版本。' };
    if (version && (!versionOf(version) || compareVersions(version, installed) <= 0 || !this.releases.some(r => r.version === version))) throw new Error('更新目标版本无效。');
    let error: string | undefined;
    let cached = false;
    if (refresh || !this.releases.length || this.clock() - this.cacheAt >= 5 * 60_000) {
      try { this.saveCache(await this.feed()); }
      catch (cause) { error = errorText(cause); cached = true; }
    }
    if (this.state.checkStatus === 'error' && (version || range === 'since')) {
      cached = true;
      error ??= this.state.checkError;
    }
    const releases = version ? selectPending(this.releases, installed, version)
      : selectNotes(this.releases, installed, range, this.state.sinceVersion, range === 'since' ? this.state.target?.version : undefined);
    return { range, releases, cached, ...(error ? { error } : {}) };
  }
  skip(version: string): void {
    if (activePhases.has(this.state.progress.phase)) throw new Error('正在更新，不能跳过已开始的事务。');
    if (!this.releases.some(r => r.version === version) || !versionOf(version) || compareVersions(version, this.state.currentVersion) <= 0) throw new Error('不能跳过此版本。');
    this.preferences.skippedVersion = this.state.skippedVersion = version;
    this.state.updateVisible = !!this.state.target && this.state.target.version !== version;
    this.state.presentation = undefined;
    this.persist(); this.emit();
  }
  restoreReminder(): void {
    this.preferences.skippedVersion = this.state.skippedVersion = undefined;
    this.state.updateVisible = !!this.state.target;
    this.persist(); this.emit();
  }
  cancelDownload(): void { if (this.state.progress.phase === 'downloading') this.download?.abort(); }
  resumeInstall(): void { this.wakePreparation?.(); }

  async install(version: string): Promise<void> {
    if (this.installTask) return this.installTask;
    const release = this.releases.find(r => r.version === version);
    if (!release || !versionOf(version) || compareVersions(version, this.state.currentVersion) <= 0) throw new Error('更新目标版本无效。');
    this.installTask = this.performInstall(release).finally(() => { this.installTask = undefined; this.schedule(); });
    return this.installTask;
  }
  private async performInstall(release: UpdateRelease): Promise<void> {
    const id = randomUUID();
    const root = this.options.paths.root;
    const work = assertNoLinks(root, `runtime/updater/${id}`);
    const cache = assertNoLinks(root, `cache/updates/${id}`);
    const backup = assertNoLinks(root, `backups/updates/${id}`);
    const stage = path.join(work, 'staged');
    let handedOff = false;
    try {
      if (!this.state.installSupported) throw new Error(this.state.development ? '开发模式可检查和阅读更新内容；请使用正式便携包进行更新。' : '当前系统、架构或 APP_ROOT 与程序目录不支持便携替换。');
      if (!release.asset) throw new Error('此版本没有匹配的 Windows x64 便携包，请到 GitHub 查看。');
      if (!release.asset.digest && !release.checksum) throw new Error('此版本尚未提供 SHA-256 校验材料，请通过 GitHub 手动更新。');
      if (!fs.existsSync(this.options.helper)) throw new Error('当前程序缺少便携更新组件，请通过 GitHub 手动更新。');
      this.rejectProgramOverrides();
      this.requireSpace(release.asset.size * 5 + 64 * 1024 ** 2);
      for (const directory of [work, cache, backup]) {
        fs.mkdirSync(directory, { recursive: true });
        writeAtomicJson(path.join(directory, '.drill-update-owned.json'), { format: 1, id, at: this.clock() });
      }
      this.preferences.failedVersion = this.state.failedVersion = undefined;
      this.preferences.failure = this.state.failure = undefined;
      this.clearRecoveryFailure(release.version);
      this.persist();
      this.state.progress = { phase: 'downloading', version: release.version, received: 0, total: release.asset.size, bytesPerSecond: 0 };
      this.setPresentation('progress', release.version);
      this.download = new AbortController();
      const zip = path.join(cache, release.asset.name);
      const expected = await this.expectedDigest(release, this.download.signal);
      await this.downloadAsset(release, zip, this.download.signal);
      if (this.download.signal.aborted || this.disposed) throw new Error('UPDATE_CANCELLED');
      this.state.progress = { ...this.state.progress, phase: 'verifying', bytesPerSecond: 0, message: '正在校验并准备便携更新包…' };
      this.emit();
      if (await fileDigest(zip) !== expected) throw new Error('下载的更新包 SHA-256 校验失败，原程序未被修改。');
      const manifest = await extractUpdateArchive(zip, stage, release.version);
      const transaction = await this.createTransaction(id, stage, manifest);
      const totalBackup = transaction.files.concat(transaction.remove.map(name => ({ path: name, size: 0, sha256: '' })))
        .reduce((bytes, file) => { const old = assertNoLinks(root, file.path); return bytes + (fs.existsSync(old) ? fs.statSync(old).size : 0); }, 0);
      const databaseBytes = ['data/study.db', 'data/study.db-wal', 'data/study.db-shm']
        .reduce((bytes, file) => { const old = assertNoLinks(root, file); return bytes + (fs.existsSync(old) ? fs.statSync(old).size : 0); }, 0);
      this.requireSpace(totalBackup + databaseBytes + 64 * 1024 ** 2);
      const transactionFile = path.join(work, 'transaction.json');
      writeAtomicJson(transactionFile, transaction);
      fs.copyFileSync(this.options.helper, path.join(work, 'drill-updater.exe'));
      while (!this.disposed) {
        this.state.progress = { ...this.state.progress, phase: 'preparing', message: '正在保存当前工作并准备自动重启…' };
        this.emit();
        const prepared = await this.options.prepare();
        if (prepared.ok) {
          const processes = this.options.processIds();
          transaction.parentPid = processes.main;
          transaction.backendPid = processes.backend;
          writeAtomicJson(transactionFile, transaction);
          writeAtomicJson(path.join(work, 'status.json'), { phase: 'prepared', toVersion: release.version, fromVersion: this.state.currentVersion });
          writeAtomicJson(assertNoLinks(root, 'runtime/updater/active.json'), { format: 1, id });
          this.state.progress = { ...this.state.progress, phase: 'restarting', message: '退出后将替换程序并自动重新打开…' };
          this.emit();
          try {
            await this.options.commit(transactionFile);
            handedOff = true;
            break;
          } catch (error) {
            if ((error as { code?: string }).code !== 'UPDATE_BUSY') throw error;
            prepared.ok = false; prepared.reason = errorText(error);
          }
        }
        this.state.progress = { ...this.state.progress, phase: 'blocked', message: prepared.reason ?? '请先完成当前操作，更新随后会自动继续。' };
        this.emit();
        await new Promise<void>(resolve => {
          const timer = setTimeout(() => { this.wakePreparation = undefined; resolve(); }, 5000);
          this.wakePreparation = () => { clearTimeout(timer); this.wakePreparation = undefined; resolve(); };
        });
      }
      if (this.disposed) throw new Error('UPDATE_CANCELLED');
    } catch (error) {
      const cancelled = this.download?.signal.aborted || errorText(error) === 'UPDATE_CANCELLED';
      if (cancelled) {
        this.state.progress = { phase: 'idle', received: 0, bytesPerSecond: 0 };
        if (!this.disposed) this.openAvailable();
      } else {
        this.state.progress = { ...this.state.progress, phase: 'failed', version: release.version, bytesPerSecond: 0, message: errorText(error) };
        this.preferences.failedVersion = this.state.failedVersion = release.version;
        this.preferences.failure = this.state.failure = errorText(error);
        this.persist();
        if (this.state.presentation?.kind !== 'progress') this.setPresentation('progress', release.version);
        this.emit();
      }
    } finally {
      this.download = undefined;
      if (!handedOff) {
        for (const directory of [work, cache, backup]) this.removeOwnedDirectory(directory, id);
        try {
          const activeFile = assertNoLinks(root, 'runtime/updater/active.json');
          if ((readJson(activeFile) as { id?: string }).id === id) fs.rmSync(activeFile, { force: true });
        } catch { /* No active transaction, or another transaction owns it. */ }
      }
    }
  }

  private async expectedDigest(release: UpdateRelease, signal: AbortSignal): Promise<string> {
    if (release.asset?.digest) return release.asset.digest;
    if (!release.checksum) throw new Error('更新包没有 SHA-256 校验材料。');
    const response = await this.options.fetch(release.checksum.url, { credentials: 'omit', signal: AbortSignal.any([signal, AbortSignal.timeout(20_000)]) });
    if (!response.ok || (response.url && !downloadDestinationAllowed(response.url))) throw new Error('无法获取可信的更新校验文件。');
    const source = (await readLimitedText(response, 4096)).trim();
    const match = /^([a-f0-9]{64})(?:\s+\*?(.+))?$/i.exec(source);
    if (!match || (match[2] && match[2] !== release.asset?.name)) throw new Error('更新校验文件的内容与便携包不匹配。');
    return match[1].toLowerCase();
  }
  private async downloadAsset(release: UpdateRelease, destination: string, signal: AbortSignal): Promise<void> {
    const asset = release.asset!;
    const stalled = new AbortController();
    let timeout: ReturnType<typeof setTimeout>;
    const resetTimeout = (): void => { clearTimeout(timeout); timeout = setTimeout(() => stalled.abort(new Error('更新包下载长时间没有响应，请检查连接后重试。')), 30_000); };
    resetTimeout();
    const combinedSignal = AbortSignal.any([signal, stalled.signal]);
    try {
    const response = await this.options.fetch(asset.url, { credentials: 'omit', signal: combinedSignal });
    if (!response.ok || !response.body || (response.url && !downloadDestinationAllowed(response.url))) throw new Error(`更新包下载失败（HTTP ${response.status}）。`);
    const headerSize = Number(response.headers.get('content-length'));
    if (headerSize > 0 && headerSize !== asset.size) throw new Error('服务器返回的更新包大小与发布信息不符。');
    let received = 0;
    let lastAt = this.clock();
    let lastBytes = 0;
    const meter = new Transform({ transform: (chunk: Buffer, _encoding, callback) => {
      resetTimeout();
      received += chunk.length;
      if (received > asset.size) { callback(new Error('下载大小超过发布信息，已停止下载。')); return; }
      const now = this.clock();
      if (now - lastAt >= 250 || received === asset.size) {
        const speed = (received - lastBytes) / Math.max(0.001, (now - lastAt) / 1000);
        this.state.progress = { ...this.state.progress, received, bytesPerSecond: speed };
        lastAt = now; lastBytes = received;
        this.emit();
      }
      callback(null, chunk);
    } });
    await pipeline(Readable.fromWeb(response.body as Parameters<typeof Readable.fromWeb>[0]), meter,
      fs.createWriteStream(destination, { flags: 'wx' }), { signal: combinedSignal });
    if (received !== asset.size) throw new Error('更新包下载不完整，请重试。');
    this.state.progress.received = received;
    } catch (error) {
      if (stalled.signal.aborted) throw stalled.signal.reason;
      throw error;
    } finally { clearTimeout(timeout!); }
  }
  private requireSpace(bytes: number): void {
    fs.accessSync(this.options.paths.root, fs.constants.W_OK);
    const probe = assertNoLinks(this.options.paths.root, `runtime/updater-write-${randomUUID()}`);
    fs.writeFileSync(probe, '', { flag: 'wx' });
    fs.rmSync(probe);
    const disk = fs.statfsSync(this.options.paths.root);
    if (disk.bavail * disk.bsize < bytes) throw new Error('便携目录所在磁盘空间不足，请腾出空间后重试。');
  }
  private rejectProgramOverrides(): void {
    // Legacy developer overrides take precedence over resources in java-bridge; never silently replace user overrides.
    for (const directory of ['backend', 'jre', 'embedding-worker']) {
      if (fs.existsSync(assertNoLinks(this.options.paths.root, directory))) throw new Error(`便携目录存在自定义 ${directory} 运行组件，请通过 GitHub 手动更新。`);
    }
  }
  private async createTransaction(id: string, stage: string, manifest: ProgramManifest): Promise<UpdateTransaction> {
    const manifestPath = assertNoLinks(this.options.paths.root, PROGRAM_MANIFEST);
    let old: ProgramManifest | undefined;
    if (fs.existsSync(manifestPath)) old = validateManifest(readJson(manifestPath), this.state.currentVersion);
    const files = [...manifest.files, { path: PROGRAM_MANIFEST, size: fs.statSync(path.join(stage, PROGRAM_MANIFEST)).size,
      sha256: await fileDigest(path.join(stage, PROGRAM_MANIFEST)) }];
    const next = new Set(files.map(file => file.path.toLowerCase()));
    for (const file of files) assertNoLinks(this.options.paths.root, file.path);
    const remove = (old?.files ?? []).filter(file => !next.has(file.path.toLowerCase())).map(file => file.path);
    const processes = this.options.processIds();
    return { format: 1, id, appRoot: this.options.paths.root, fromVersion: this.state.currentVersion, toVersion: manifest.version,
      parentPid: processes.main, backendPid: processes.backend, bootTimeoutSeconds: 90, files, remove };
  }

  /** Record a successful run only after the backend and trusted renderer are both ready. */
  recordSuccessfulBoot(): void {
    const current = versionOf(this.state.currentVersion);
    if (!current) return;
    this.preferences.lastRunVersion = current;
    if (this.preferences.failedVersion && compareVersions(current, this.preferences.failedVersion) >= 0) {
      this.clearRecoveryFailure(this.preferences.failedVersion);
      this.preferences.failedVersion = this.state.failedVersion = undefined;
      this.preferences.failure = this.state.failure = undefined;
    }
    this.persist();
  }
  private clearRecoveryFailure(version: string): void {
    const file = assertNoLinks(this.options.paths.root, 'config/update-failure.json');
    try {
      const failure = readJson(file) as { format?: number; id?: string; version?: string };
      if (failure.format === 1 && transactionIdValid(failure.id) && failure.version === version) fs.rmSync(file);
    } catch { /* Only an updater-owned matching failure record can be removed. */ }
  }
  private importRecoveryResult(): void {
    try {
      const failure = readJson(assertNoLinks(this.options.paths.root, 'config/update-failure.json')) as { format?: number; version?: string; error?: string };
      if (failure.format === 1 && versionOf(failure.version) && typeof failure.error === 'string') {
        this.preferences.failedVersion = this.state.failedVersion = failure.version;
        this.preferences.failure = this.state.failure = `上次更新未完成，已恢复原版本。${failure.error}`;
        this.persist();
      }
    } catch { /* No failed transaction. */ }
    try {
      const activeFile = assertNoLinks(this.options.paths.root, 'runtime/updater/active.json');
      const active = readJson(activeFile) as { id?: string };
      if (!transactionIdValid(active.id)) return;
      const status = readJson(assertNoLinks(this.options.paths.root, `runtime/updater/${active.id}/status.json`)) as { phase?: string; toVersion?: string; error?: string };
      if (status.phase === 'rolled-back' && versionOf(status.toVersion)) {
        this.preferences.failedVersion = this.state.failedVersion = status.toVersion;
        this.preferences.failure = this.state.failure = typeof status.error === 'string' ? status.error : '上次更新未能完成，已恢复原版本。';
        this.persist();
      }
    } catch { /* There may be no previous transaction. */ }
  }
  private removeOwnedDirectory(directory: string, id: string): void {
    try {
      assertNoLinks(this.options.paths.root, path.relative(this.options.paths.root, directory).replaceAll('\\', '/'));
      const marker = readJson(path.join(directory, '.drill-update-owned.json')) as { format?: number; id?: string };
      if (marker.format === 1 && marker.id === id && transactionIdValid(id)) fs.rmSync(directory, { recursive: true, force: true });
    } catch { /* A busy helper or user-owned directory is retained. */ }
  }
  cleanupOwnedArtifacts(): void {
    const root = this.options.paths.root;
    let activeId: string | undefined;
    try { activeId = (readJson(assertNoLinks(root, 'runtime/updater/active.json')) as { id?: string }).id; } catch { /* none */ }
    const terminal: Array<{ id: string; at: number }> = [];
    const updaterDirectory = assertNoLinks(root, 'runtime/updater');
    if (!fs.existsSync(updaterDirectory)) return;
    for (const id of fs.readdirSync(updaterDirectory)) {
      if (!transactionIdValid(id)) continue;
      try {
        const work = assertNoLinks(root, `runtime/updater/${id}`);
        const marker = readJson(path.join(work, '.drill-update-owned.json')) as { id?: string; at?: number };
        const status = readJson(path.join(work, 'status.json')) as { phase?: string; helperPid?: number };
        if (marker.id !== id || !['complete', 'rolled-back'].includes(status.phase ?? '')) continue;
        if (status.helperPid) { try { process.kill(status.helperPid, 0); continue; } catch { /* helper exited */ } }
        if (activeId === id) {
          fs.rmSync(path.join(updaterDirectory, 'active.json'), { force: true });
          activeId = undefined;
        }
        terminal.push({ id, at: marker.at ?? 0 });
        this.removeOwnedDirectory(assertNoLinks(root, `cache/updates/${id}`), id);
      } catch { /* Incomplete transaction remains available for recovery. */ }
    }
    terminal.sort((a, b) => b.at - a.at);
    for (const item of terminal) {
      const work = assertNoLinks(root, `runtime/updater/${item.id}`);
      if (item.id !== activeId) this.removeOwnedDirectory(work, item.id);
    }
    // The two newest program/database backups remain useful for a manual recovery; older updater-owned backups are bounded.
    const backupDirectory = assertNoLinks(root, 'backups/updates');
    if (fs.existsSync(backupDirectory)) {
      const backups = fs.readdirSync(backupDirectory).filter(transactionIdValid).flatMap(id => {
        try { const marker = readJson(assertNoLinks(root, `backups/updates/${id}/.drill-update-owned.json`)) as { id?: string; at?: number };
          return marker.id === id ? [{ id, at: marker.at ?? 0 }] : []; } catch { return []; }
      }).sort((a, b) => b.at - a.at);
      for (const item of backups.slice(2)) if (item.id !== activeId) this.removeOwnedDirectory(assertNoLinks(root, `backups/updates/${item.id}`), item.id);
    }
  }
  dispose(): void {
    this.disposed = true;
    if (this.timer) clearTimeout(this.timer);
    this.download?.abort();
    this.wakePreparation?.();
  }
}
