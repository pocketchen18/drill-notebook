import { app } from 'electron';
import fs from 'node:fs';
import path from 'node:path';

export interface PortablePaths {
  root: string;
  data: string;
  electronUserData: string;
  imports: string;
  backups: string;
  config: string;
  cache: string;
  logs: string;
  runtime: string;
  tmp: string;
  pid: string;
  javaHome: string;
  database: string;
}

function workspaceRoot(): string {
  return path.resolve(__dirname, '..');
}

export function getAppRoot(): string {
  const explicit = process.env.APP_ROOT?.trim();
  if (explicit) return path.resolve(explicit);
  if (app.isPackaged) return path.dirname(process.execPath);
  return path.join(workspaceRoot(), 'runtime-portable');
}

export function getPortablePaths(root = getAppRoot()): PortablePaths {
  const data = path.join(root, 'data');
  const runtime = path.join(root, 'runtime');
  return {
    root,
    data,
    electronUserData: path.join(data, 'electron-userdata'),
    imports: path.join(data, 'imports'),
    backups: path.join(data, 'backups'),
    config: path.join(root, 'config'),
    cache: path.join(root, 'cache'),
    logs: path.join(root, 'logs'),
    runtime,
    tmp: path.join(runtime, 'tmp'),
    pid: path.join(runtime, 'pid'),
    javaHome: path.join(runtime, 'java-home'),
    database: path.join(data, 'study.db')
  };
}

export function setupPortablePaths(): PortablePaths {
  const paths = getPortablePaths();
  for (const directory of [
    paths.data,
    paths.electronUserData,
    paths.imports,
    paths.backups,
    paths.config,
    paths.cache,
    paths.logs,
    paths.runtime,
    paths.tmp,
    paths.pid,
    paths.javaHome
  ]) {
    fs.mkdirSync(directory, { recursive: true });
  }

  const flag = path.join(paths.root, 'portable.flag');
  if (!fs.existsSync(flag)) fs.writeFileSync(flag, 'drill-notebook\n', 'utf8');
  const appConfig = path.join(paths.config, 'app-config.json');
  if (!fs.existsSync(appConfig)) fs.writeFileSync(appConfig, '{\n  "theme": "light"\n}\n', 'utf8');

  app.setPath('userData', paths.electronUserData);
  app.setPath('sessionData', paths.electronUserData);
  app.setPath('cache', paths.cache);
  app.setPath('temp', paths.tmp);
  process.env.APP_ROOT = paths.root;
  process.env.TEMP = paths.tmp;
  process.env.TMP = paths.tmp;
  process.env.TMPDIR = paths.tmp;
  return paths;
}

export function clearPortableTemp(paths: PortablePaths): void {
  if (!fs.existsSync(paths.tmp)) return;
  for (const entry of fs.readdirSync(paths.tmp)) {
    try {
      fs.rmSync(path.join(paths.tmp, entry), { recursive: true, force: true });
    } catch (error) {
      // On Windows, files still held by a just-killed backend process or by
      // Electron itself report EBUSY/EPERM. They are harmless leftovers that
      // will be recreated on next startup, so swallow these errors instead
      // of crashing the app on quit.
      if (error && typeof error === 'object' && 'code' in error) {
        const code = (error as NodeJS.ErrnoException).code;
        if (code === 'EBUSY' || code === 'EPERM' || code === 'ENOENT') continue;
      }
      throw error;
    }
  }
}
