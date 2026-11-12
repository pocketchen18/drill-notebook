import fs from 'node:fs';
import path from 'node:path';
import { createRequire } from 'node:module';
import { createHash } from 'node:crypto';
import { fileURLToPath } from 'node:url';

const require = createRequire(import.meta.url);
const { PROGRAM_MANIFEST, assertNoLinks, programPathAllowed, validateManifest, writeAtomicJson, fileDigest } = require('../electron-dist/program-manifest.js');
const { versionOf } = require('../electron-dist/update-feed.js');

export async function generateManifest(directory, version) {
  const root = path.resolve(directory);
  if (versionOf(version) !== version) throw new Error('A normalized semantic application version is required.');
  assertNoLinks(root);
  const files = [];
  async function walk(relative = '') {
    const entries = fs.readdirSync(assertNoLinks(root, relative), { withFileTypes: true }).sort((a, b) => a.name.localeCompare(b.name, 'en'));
    for (const entry of entries) {
      const name = relative ? `${relative}/${entry.name}` : entry.name;
      if (name === PROGRAM_MANIFEST) continue;
      if (entry.isSymbolicLink() || !programPathAllowed(name, entry.isDirectory())) throw new Error(`Unmanaged or unsafe package entry: ${name}`);
      const filename = assertNoLinks(root, name);
      if (entry.isDirectory()) await walk(name);
      else if (entry.isFile()) files.push({ path: name, size: fs.statSync(filename).size, sha256: await fileDigest(filename) });
      else throw new Error(`Unsupported package entry: ${name}`);
    }
  }
  await walk();
  const manifest = validateManifest({ format: 1, version, files }, version);
  writeAtomicJson(assertNoLinks(root, PROGRAM_MANIFEST), manifest);
  return manifest;
}

export async function generateChecksum(archive) {
  const filename = path.resolve(archive);
  if (!/^Drill-Notebook-\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?-win-x64-portable\.zip$/.test(path.basename(filename))) throw new Error('The checksum must describe a named Windows x64 portable ZIP.');
  const digest = createHash('sha256');
  for await (const chunk of fs.createReadStream(filename)) digest.update(chunk);
  const checksum = `${digest.digest('hex')}  ${path.basename(filename)}\n`;
  fs.writeFileSync(`${filename}.sha256`, checksum, 'utf8');
  return checksum;
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const args = process.argv.slice(2);
  if (args.length === 4 && args[0] === '--directory' && args[2] === '--version') {
    const manifest = await generateManifest(args[1], args[3]);
    console.log(`Program manifest: ${manifest.version}, ${manifest.files.length} verified program files.`);
  } else if (args.length === 2 && args[0] === '--archive') {
    console.log(`SHA-256: ${(await generateChecksum(args[1])).trim()}`);
  } else throw new Error('Use --directory <portable directory> --version <version>, or --archive <portable.zip>.');
}
