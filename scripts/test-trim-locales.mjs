// Behavior test for the afterPack locale-trim hook. Not part of the app build; run manually:
//   node scripts/test-trim-locales.mjs
import { mkdirSync, writeFileSync, rmSync, existsSync, readdirSync } from 'node:fs';
import { join } from 'node:path';
import { tmpdir } from 'node:os';
import afterPack from './after-pack-trim-locales.mjs';

const base = join(tmpdir(), `trim-locales-test-${Date.now()}`);
const locales = join(base, 'locales');
mkdirSync(locales, { recursive: true });

const all = ['en-US.pak', 'zh-CN.pak', 'zh-TW.pak', 'fr.pak', 'de.pak', 'ja.pak', 'ko.pak', 'es.pak', 'not-a-pak.txt'];
for (const name of all) writeFileSync(join(locales, name), Buffer.alloc(1024, 1));

await afterPack({ appOutDir: base });

const remaining = readdirSync(locales).filter((f) => f.endsWith('.pak')).sort();
const expected = ['en-US.pak', 'zh-CN.pak'];
const ok = JSON.stringify(remaining) === JSON.stringify(expected);
// non-.pak files must be untouched
const txtKept = existsSync(join(locales, 'not-a-pak.txt'));

console.log('remaining .pak:', remaining.join(', '));
console.log('non-pak preserved:', txtKept);
if (!ok || !txtKept) {
  rmSync(base, { recursive: true, force: true });
  console.error(`FAIL: expected [${expected.join(', ')}] and .txt kept`);
  process.exit(1);
}
rmSync(base, { recursive: true, force: true });
console.log('PASS: only zh-CN + en-US remain, non-pak untouched, temp cleaned');
