// 发布配置回归：node --test scripts/test-release-config.mjs（打包行为用例需 Windows + PowerShell 7）。
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { copyFileSync, existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { createRequire } from 'node:module';
import { dirname, join, relative, resolve, sep } from 'node:path';
import { fileURLToPath } from 'node:url';
import test from 'node:test';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
// 复用已有打包工具声明的 YAML 依赖，不为配置测试增加应用依赖。
const require = createRequire(import.meta.url);
const builderRequire = createRequire(require.resolve('app-builder-lib/package.json'));
const { load } = builderRequire('js-yaml');
const workflow = load(readFileSync(join(root, '.github/workflows/release-portable.yml'), 'utf8'));
const job = workflow.jobs['build-windows'];
const steps = job.steps;
const packageJson = JSON.parse(readFileSync(join(root, 'package.json'), 'utf8'));
const defaultVersion = workflow.on.workflow_dispatch.inputs.version.default.replace(/^v/, '');
const mavenCommand = 'mvn --batch-mode --show-version --no-transfer-progress -f backend/pom.xml';

test('发布默认值、预发行渠道与 PowerShell 7 入口保持一致', () => {
  assert.equal(workflow.on.workflow_dispatch.inputs.version.default, 'v0.7.1');
  assert.equal(workflow.on.workflow_dispatch.inputs.publish_release.default, false);
  assert.equal(steps.find(step => step.uses?.startsWith('softprops/action-gh-release@')).with.prerelease, true);
  assert.equal(job.defaults.run.shell, 'pwsh');
  assert.match(packageJson.scripts['package:portable'], /^pwsh .* -File scripts\/package-portable\.ps1$/);
});

test('锁文件安装与发布配置检查通过后构建发布，不重复本地业务全量测试', () => {
  const install = steps.findIndex(step => step.run === 'npm ci');
  const build = steps.findIndex(step => step.run === 'npm run build');
  assert.ok(install >= 0 && build > install);
  for (const command of ['node --test scripts/test-release-config.mjs']) {
    const index = steps.findIndex(step => step.run === command);
    assert.ok(index > install && index < build, `${command} 必须在安装后、构建前`);
    assert.equal(steps[index]['continue-on-error'], undefined);
    assert.equal(steps[index].if, undefined);
  }
  const commands = steps.map(step => step.run ?? '').join('\n');
  assert.doesNotMatch(commands, /npm (?:run )?test(?::(?:frontend|backend|electron|rag))?\b|npm (?:--workspace|--prefix) frontend (?:run )?test\b|\bvitest\s+run\b|\bmvn[^\n]*\b(?:test|verify)\s*(?:\n|$)/);
  assert.equal(job['continue-on-error'], undefined);
  const pack = steps.findIndex(step => step.run?.includes('npx electron-builder'));
  const release = steps.findIndex(step => step.uses?.startsWith('softprops/action-gh-release@'));
  assert.ok(pack > build && release > pack);
  assert.match(steps[pack].run, /--publish never --config\.extraMetadata\.version=/);
  assert.equal(steps[release].if, "github.event_name == 'push' || inputs.publish_release == true");
  assert.equal(steps[release].with.name, 'Drill Notebook ${{ steps.ver.outputs.tag }}');
  assert.equal(steps[release].with.target_commitish, '${{ github.sha }}');
  assert.equal(steps[release].with.body_path, '${{ steps.notes.outputs.path }}');
  assert.equal(steps[release].with.fail_on_unmatched_files, true);
  const artifact = steps.find(step => step.uses?.startsWith('actions/upload-artifact@'));
  assert.equal(artifact.if, undefined);
  assert.equal(artifact.with['if-no-files-found'], 'error');
  const notes = steps.findIndex(step => step.id === 'notes');
  assert.ok(notes > steps.findIndex(step => step.id === 'ver') && notes < install);
  assert.equal(steps[notes].if, undefined);
  assert.equal(steps[notes]['continue-on-error'], undefined);
  assert.ok(existsSync(join(root, `docs/plans/v${defaultVersion}-release-notes.md`)));
});

test('云端后端使用预装 Maven 跳过测试执行，保留 Java 17 编译和 JAR 产物路径', () => {
  const configCheck = steps.findIndex(step => step.run === 'node --test scripts/test-release-config.mjs');
  const backendBuild = steps.findIndex(step => step.name.startsWith('构建后端 jar'));
  const pack = steps.findIndex(step => step.run?.includes('npx electron-builder'));
  assert.ok(configCheck >= 0 && backendBuild > configCheck && pack > backendBuild);
  assert.equal(steps.findIndex(step => step.name === '后端全量测试'), -1);
  assert.equal(steps.findIndex(step => step.name === '前端全量测试'), -1);
  assert.equal(steps[backendBuild].run, `${mavenCommand} -DskipTests package`);
  assert.equal(steps[backendBuild]['continue-on-error'], undefined);
  assert.equal(steps[backendBuild].if, undefined);
  assert.doesNotMatch(steps.map(step => step.run ?? '').join('\n'), /\bmvnw(?:\.cmd)?\b|npm run (?:test|build):backend/);
  assert.equal(steps.find(step => step.uses?.startsWith('actions/setup-java@')).with['java-version'], '17');
  assert.equal(packageJson.scripts['test:backend'], 'mvnw.cmd -f backend/pom.xml test');
  assert.deepEqual(packageJson.build.extraResources.find(resource => resource.to === 'backend/app.jar'), {
    from: 'backend/target/drill-notebook-backend-0.1.0.jar', to: 'backend/app.jar',
  });
});

const cacheRoot = join(root, 'cache');
function fixture(t, builderExit = 0) {
  mkdirSync(cacheRoot, { recursive: true });
  const dir = mkdtempSync(join(cacheRoot, 'release-config-test-'));
  t.after(() => {
    // 仅删除本测试刚创建的工作区内临时目录。
    const pathInCache = relative(cacheRoot, dir);
    assert.ok(pathInCache && !pathInCache.startsWith(`..${sep}`) && !pathInCache.includes(sep));
    rmSync(dir, { recursive: true, force: true });
  });
  for (const file of ['jre/bin/java.exe', 'frontend/dist/index.html', 'electron-dist/main.js', 'backend/target/drill-notebook-backend-0.1.0.jar']) {
    const path = join(dir, file);
    mkdirSync(dirname(path), { recursive: true });
    writeFileSync(path, 'fixture');
  }
  mkdirSync(join(dir, 'scripts'));
  copyFileSync(join(root, 'scripts/package-portable.ps1'), join(dir, 'scripts/package-portable.ps1'));
  mkdirSync(join(dir, 'node_modules/.bin'), { recursive: true });
  writeFileSync(join(dir, 'node_modules/.bin/electron-builder.cmd'), [
    '@echo off',
    '>builder-args.txt echo %*',
    `if ${builderExit} NEQ 0 exit /b ${builderExit}`,
    'if not exist "dist\\win-unpacked" mkdir "dist\\win-unpacked"',
    '>"dist\\win-unpacked\\Drill Notebook.exe" echo fixture',
    'exit /b 0',
    '',
  ].join('\r\n'));
  return dir;
}

function runPackage(dir, args = []) {
  const result = spawnSync('pwsh', ['-NoProfile', '-File', join(dir, 'scripts/package-portable.ps1'), ...args], {
    // 从非仓库根目录启动，检查构建命令及压缩路径是否都锚定脚本所属仓库。
    cwd: join(dir, 'scripts'), encoding: 'utf8', timeout: 30000, windowsHide: true,
  });
  assert.ifError(result.error);
  return result;
}

const windowsOnly = { skip: process.platform !== 'win32' };
test('发布说明按完整版本选择，预发行复用同版正文，缺失或空白立即失败', windowsOnly, t => {
  mkdirSync(cacheRoot, { recursive: true });
  const dir = mkdtempSync(join(cacheRoot, 'release-notes-test-'));
  t.after(() => {
    const pathInCache = relative(cacheRoot, dir);
    assert.ok(pathInCache && !pathInCache.startsWith(`..${sep}`) && !pathInCache.includes(sep));
    rmSync(dir, { recursive: true, force: true });
  });
  mkdirSync(join(dir, 'docs/plans'), { recursive: true });
  for (const tag of ['v0.7.0', 'v0.7.1', 'v0.7.10']) {
    writeFileSync(join(dir, `docs/plans/${tag}-release-notes.md`), `# ${tag} 发布说明\n`);
  }
  writeFileSync(join(dir, 'docs/plans/v0.7.2-release-notes.md'), ' \n');
  const script = join(dir, 'select-notes.ps1');
  const output = join(dir, 'notes-output.txt');
  writeFileSync(script, steps.find(step => step.id === 'notes').run);
  for (const tag of ['v0.7.0', 'v0.7.1', 'v0.7.1-rc.1', 'v0.7.10', 'v0.7.2', 'v0.7.3']) {
    const result = spawnSync('pwsh', ['-NoProfile', '-File', script], {
      cwd: dir, encoding: 'utf8', timeout: 30000, windowsHide: true,
      env: { ...process.env, RELEASE_TAG: tag, GITHUB_OUTPUT: output },
    });
    assert.ifError(result.error);
    if (tag === 'v0.7.2' || tag === 'v0.7.3') {
      assert.notEqual(result.status, 0, `不应发布缺少正文的版本：${tag}`);
      assert.equal(existsSync(output), false);
    } else {
      assert.equal(result.status, 0, result.stdout + result.stderr);
      assert.equal(readFileSync(output, 'utf8').trim(), `path=docs/plans/${tag.split('-')[0]}-release-notes.md`);
      rmSync(output);
    }
  }
});

test('云端版本校验拒绝路径、命令及多行输入，正常输出发布版本', windowsOnly, t => {
  const dir = fixture(t);
  const script = join(dir, 'scripts/validate-version.ps1');
  const output = join(dir, 'version-output.txt');
  writeFileSync(script, steps.find(step => step.id === 'ver').run);
  for (const version of ['v0.7.1', 'v0.7.1-rc.1', '../outside', 'v0.7.1;echo bad', 'v0.7.1\ntag=other', 'v0.7.1\n', '0.7.1']) {
    const valid = version === 'v0.7.1' || version === 'v0.7.1-rc.1';
    const result = spawnSync('pwsh', ['-NoProfile', '-File', script], {
      cwd: dir, encoding: 'utf8', timeout: 30000, windowsHide: true,
      env: { ...process.env, RAW: version, GITHUB_OUTPUT: output },
    });
    assert.ifError(result.error);
    if (valid) {
      assert.equal(result.status, 0, result.stdout + result.stderr);
      assert.deepEqual(readFileSync(output, 'utf8').trim().split(/\r?\n/), [
        `tag=${version}`, `version=${version.slice(1)}`,
      ]);
      rmSync(output);
    } else {
      assert.notEqual(result.status, 0, `不应接受版本：${version}`);
      assert.equal(existsSync(output), false);
    }
  }
});

test('本地默认版本与 CI 一致，注入应用版本并生成同版本 ZIP', windowsOnly, t => {
  const dir = fixture(t);
  const result = runPackage(dir);
  assert.equal(result.status, 0, result.stdout + result.stderr);
  const args = readFileSync(join(dir, 'builder-args.txt'), 'utf8');
  assert.ok(args.includes(`--config.extraMetadata.version=${defaultVersion}`));
  assert.ok(args.includes('--publish never'));
  assert.ok(existsSync(join(dir, `dist/Drill-Notebook-${defaultVersion}-win-x64-portable.zip`)));
});

test('自定义版本去除 v 前缀，应用版本与 ZIP 名称一致', windowsOnly, t => {
  const dir = fixture(t);
  const result = runPackage(dir, ['-Version', 'v0.7.1-rc.1']);
  assert.equal(result.status, 0, result.stdout + result.stderr);
  assert.ok(readFileSync(join(dir, 'builder-args.txt'), 'utf8').includes('--config.extraMetadata.version=0.7.1-rc.1'));
  assert.ok(existsSync(join(dir, 'dist/Drill-Notebook-0.7.1-rc.1-win-x64-portable.zip')));
});

test('非法版本在构建和写入产物前被拒绝', windowsOnly, t => {
  const dir = fixture(t);
  for (const version of ['../outside', 'v0.6.3;echo bad', '0.6.3\n', '0.6', '']) {
    const result = runPackage(dir, ['-Version', version]);
    assert.notEqual(result.status, 0, `不应接受版本：${version}`);
  }
  assert.equal(existsSync(join(dir, 'builder-args.txt')), false);
  assert.equal(existsSync(join(dir, 'dist')), false);
});

test('打包命令失败立即停止，不生成成功假象', windowsOnly, t => {
  const dir = fixture(t, 9);
  const result = runPackage(dir);
  assert.notEqual(result.status, 0);
  assert.match(result.stderr, /exit code 9/);
  assert.equal(existsSync(join(dir, `dist/Drill-Notebook-${defaultVersion}-win-x64-portable.zip`)), false);
});

test('缺少内嵌 JRE 时拒绝调用打包工具', windowsOnly, t => {
  const dir = fixture(t);
  rmSync(join(dir, 'jre/bin/java.exe'));
  const result = runPackage(dir);
  assert.notEqual(result.status, 0);
  assert.match(result.stderr, /Embedded JRE is missing/);
  assert.equal(existsSync(join(dir, 'builder-args.txt')), false);
});
