// This entry point is injected into a newly created QA app.asar only; it is never part of a release.
const fs = require('node:fs');
const path = require('node:path');
const { app, net } = require('electron');
const root = process.env.DRILL_QA_ROOT;
const feed = process.env.DRILL_QA_FEED;
if (!root || root !== process.env.APP_ROOT || path.dirname(process.execPath) !== root || !/^http:\/\/127\.0\.0\.1:\d+$/.test(feed ?? '')) {
  throw new Error('A workspace-local packaged QA root and feed are required');
}
const directory = path.join(root, 'runtime/qa');
fs.mkdirSync(directory, { recursive: true });
const { writeAtomicJson } = require('./electron-dist/program-manifest.js');
const stateFile = path.join(directory, 'state.json');
const eventsFile = path.join(directory, 'events.jsonl');
const report = (event, detail = {}) => fs.appendFileSync(eventsFile, JSON.stringify({ event, version: app.getVersion(), pid: process.pid, at: Date.now(), ...detail }) + '\n');
report('started');
const fetchOriginal = net.fetch.bind(net);
net.fetch = async (url, init) => {
  const value = String(url);
  const api = value.startsWith('https://api.github.com/repos/qilin-software/drill-notebook/releases?');
  const asset = value.startsWith('https://github.com/qilin-software/drill-notebook/releases/download/');
  if (!api && !asset) return fetchOriginal(url, init);
  const target = api ? '/releases' : value.endsWith('.sha256') ? '/checksum' : '/archive';
  const response = await fetch(feed + target, init);
  Object.defineProperty(response, 'url', { value });
  return response;
};
let host;
let options;
const moduleHost = require('./electron-dist/update-host.js');
const Host = moduleHost.PortableUpdateHost;
moduleHost.PortableUpdateHost = class extends Host {
  constructor(value) { super(value); host = this; options = value; }
};
require('./electron-dist/main.js');
let lastControl = '';
let inControl = false;
let lastState = '';
let backendReported = false;
const interval = setInterval(async () => {
  if (!host) return;
  const backend = options.backend();
  if (backend && !backendReported) { backendReported = true; report('backend-ready', { backendPid: backend.process.pid }); }
  const state = { version: app.getVersion(), pid: process.pid, packaged: app.isPackaged, backendPid: backend?.process.pid ?? 0,
    backendUrl: backend?.baseUrl, state: host.updater.getState() };
  const serialized = JSON.stringify(state);
  if (serialized !== lastState) { lastState = serialized; writeAtomicJson(stateFile, state); }
  if (inControl) return;
  let control;
  try { control = JSON.parse(fs.readFileSync(path.join(directory, 'control.json'), 'utf8')); } catch { return; }
  if (typeof control.id !== 'string' || control.id === lastControl || control.pid !== process.pid || control.version !== app.getVersion()) return;
  lastControl = control.id;
  inControl = true;
  try {
    let result;
    switch (control.command) {
      case 'check': await host.updater.check(); result = host.updater.getState(); break;
      case 'settings': result = host.updater.setSettings(control.argument); break;
      case 'open-available': host.updater.openAvailable(); break;
      case 'open-history': host.updater.openHistory(); break;
      case 'close': host.updater.closePresentation(control.argument); break;
      case 'notes': result = await host.updater.notes(control.argument.range, control.argument.version, control.argument.refresh); break;
      case 'skip': host.updater.skip(control.argument); break;
      case 'restore': host.updater.restoreReminder(); break;
      case 'cancel': host.updater.cancelDownload(); break;
      case 'resume': host.updater.resumeInstall(); break;
      case 'save-drafts': host.drafts.save(control.argument); break;
      case 'read-drafts': result = host.drafts.read(); break;
      case 'install':
        writeAtomicJson(path.join(directory, 'reply.json'), { id: control.id, ok: true });
        await host.updater.install(control.argument); break;
      case 'quit':
        writeAtomicJson(path.join(directory, 'reply.json'), { id: control.id, ok: true });
        app.quit(); return;
      default: throw new Error('Unknown QA command');
    }
    writeAtomicJson(path.join(directory, 'reply.json'), { id: control.id, ok: true, result });
  } catch (error) {
    writeAtomicJson(path.join(directory, 'reply.json'), { id: control.id, ok: false, error: String(error.message ?? error) });
  } finally { inControl = false; }
}, 100);
app.on('before-quit', () => { clearInterval(interval); report('quitting'); });
