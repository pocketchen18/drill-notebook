import nodeFs from 'node:fs';

// Program packages must be treated as raw files. Electron's patched fs interprets .asar as a virtual directory.
const updateFs: typeof nodeFs = process.versions.electron ? require('original-fs') : nodeFs;
export default updateFs;
