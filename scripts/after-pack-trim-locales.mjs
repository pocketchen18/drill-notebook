// electron-builder afterPack hook: strip unused Chromium locale .pak files.
// The app UI is Chinese + English only, but Electron ships ~55 locales (~42 MB) by default.
// Keep zh-CN.pak (primary) + en-US.pak (Chromium fallback locale); delete every other .pak.
// Runs for both the CI `--win portable` target and the local `--win dir` target, since both
// read the `build` config in package.json.
import { existsSync, readdirSync, statSync, unlinkSync } from 'node:fs';
import { join } from 'node:path';

const KEEP = new Set(['zh-CN.pak', 'en-US.pak']);

export default async function afterPack(context) {
  const localesDir = join(context.appOutDir, 'locales');
  if (!existsSync(localesDir)) {
    console.warn(`[trim-locales] locales dir not found (${localesDir}); nothing to trim`);
    return;
  }
  let removed = 0;
  let freedBytes = 0;
  for (const entry of readdirSync(localesDir)) {
    if (!entry.endsWith('.pak') || KEEP.has(entry)) continue;
    const full = join(localesDir, entry);
    freedBytes += statSync(full).size;
    unlinkSync(full);
    removed += 1;
  }
  console.log(
    `[trim-locales] removed ${removed} unused locale(s), freed ${(freedBytes / 1048576).toFixed(1)} MB; kept ${[...KEEP].join(', ')}`
  );
}
