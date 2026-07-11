import { ChildProcess, spawn, execFileSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { app } from 'electron';
import type { PortablePaths } from './paths';

export interface BackendHandle {
  process: ChildProcess;
  port: number;
  baseUrl: string;
  pidFile: string;
}

function isDev(): boolean {
  return !app.isPackaged;
}

function findBackendJar(paths: PortablePaths): string {
  const explicit = process.env.BACKEND_JAR?.trim();
  if (explicit && fs.existsSync(explicit)) return path.resolve(explicit);

  const packaged = path.join(paths.root, 'backend', 'app.jar');
  if (fs.existsSync(packaged)) return packaged;
  const packagedResource = path.join(process.resourcesPath ?? '', 'backend', 'app.jar');
  if (packagedResource && fs.existsSync(packagedResource)) return packagedResource;

  const workspaceBackend = path.resolve(__dirname, '..', 'backend', 'target');
  const preferred = path.join(workspaceBackend, 'drill-notebook-backend-0.1.0.jar');
  if (fs.existsSync(preferred)) return preferred;

  if (fs.existsSync(workspaceBackend)) {
    const jar = fs.readdirSync(workspaceBackend).find((name) => name.endsWith('.jar') && !name.endsWith('.original'));
    if (jar) return path.join(workspaceBackend, jar);
  }
  throw new Error(`Backend jar not found. Build backend first: ${workspaceBackend}`);
}

function javaBinary(paths: PortablePaths): string {
  const binaryName = process.platform === 'win32' ? 'java.exe' : 'java';
  const bundledCandidates = [
    path.join(paths.root, 'jre', 'bin', binaryName),
    path.join(process.resourcesPath ?? '', 'jre', 'bin', binaryName)
  ];
  const bundled = bundledCandidates.find((candidate) => candidate && fs.existsSync(candidate));
  if (bundled) return bundled;
  if (!isDev()) throw new Error('Packaged runtime is missing jre/bin/java.exe. See docs/jlink.md.');
  console.warn('[portable] bundled JRE missing; using development java from PATH');
  return process.platform === 'win32' ? 'java.exe' : 'java';
}

function readPid(pidFile: string): number | undefined {
  if (!fs.existsSync(pidFile)) return undefined;
  const value = Number(fs.readFileSync(pidFile, 'utf8').trim());
  return Number.isInteger(value) && value > 0 ? value : undefined;
}

function processExists(pid: number): boolean {
  try {
    process.kill(pid, 0);
    return true;
  } catch {
    return false;
  }
}

function killTree(pid: number): void {
  if (pid === process.pid || !processExists(pid)) return;
  if (process.platform === 'win32') {
    try {
      execFileSync('taskkill.exe', ['/PID', String(pid), '/T', '/F'], { stdio: 'ignore', windowsHide: true });
    } catch {
      // The process may have exited between the liveness check and taskkill.
    }
  } else {
    try {
      process.kill(pid, 'SIGTERM');
    } catch {
      // The process may have exited already.
    }
  }
}

async function waitForHealth(baseUrl: string, portFile: string): Promise<number> {
  for (let attempt = 0; attempt < 60; attempt += 1) {
    let port = Number(fs.existsSync(portFile) ? fs.readFileSync(portFile, 'utf8').trim() : 0);
    if (!Number.isInteger(port) || port <= 0) port = 0;
    if (port > 0) {
      try {
        const response = await fetch(`${baseUrl.replace(':0', `:${port}`)}/api/health`);
        if (response.ok) return port;
      } catch {
        // Backend is still starting.
      }
    }
    await new Promise((resolve) => setTimeout(resolve, 500));
  }
  throw new Error('Backend did not become healthy within 30 seconds.');
}

export async function startBackend(paths: PortablePaths): Promise<BackendHandle> {
  const pidFile = path.join(paths.pid, 'backend.pid');
  const oldPid = readPid(pidFile);
  if (oldPid) killTree(oldPid);
  fs.rmSync(pidFile, { force: true });
  fs.rmSync(path.join(paths.runtime, 'backend.port'), { force: true });

  const jar = findBackendJar(paths);
  const java = javaBinary(paths);
  const child = spawn(
    java,
    [
      '-Dapp.root=' + paths.root,
      '-Duser.home=' + paths.javaHome,
      '-Djava.io.tmpdir=' + paths.tmp,
      '-Dfile.encoding=UTF-8',
      '-Xmx512m',
      '-jar',
      jar,
      '--server.address=127.0.0.1',
      '--server.port=0'
    ],
    {
      cwd: paths.root,
      env: { ...process.env, APP_ROOT: paths.root, TEMP: paths.tmp, TMP: paths.tmp, TMPDIR: paths.tmp },
      stdio: ['ignore', 'pipe', 'pipe'],
      windowsHide: true
    }
  );

  fs.writeFileSync(pidFile, String(child.pid ?? ''), 'utf8');
  child.stdout?.on('data', (data: Buffer) => console.log(`[backend] ${data.toString().trimEnd()}`));
  child.stderr?.on('data', (data: Buffer) => console.error(`[backend] ${data.toString().trimEnd()}`));
  child.on('exit', () => {
    if (fs.existsSync(pidFile) && readPid(pidFile) === child.pid) fs.rmSync(pidFile, { force: true });
  });

  const port = await waitForHealth('http://127.0.0.1:0', path.join(paths.runtime, 'backend.port'));
  return { process: child, port, baseUrl: `http://127.0.0.1:${port}`, pidFile };
}

export function stopBackend(handle: BackendHandle | undefined): void {
  if (!handle) return;
  if (handle.process.pid) killTree(handle.process.pid);
  fs.rmSync(handle.pidFile, { force: true });
}
