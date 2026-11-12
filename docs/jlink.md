# Embedded JRE recipe

The repository intentionally does not contain a JRE binary. For a green-portable Windows release, build a JRE 17 image with the JDK used for the release:

```powershell
jlink --add-modules java.se,jdk.crypto.ec,jdk.crypto.cryptoki,jdk.crypto.mscapi,jdk.naming.dns,jdk.unsupported,jdk.zipfs,jdk.httpserver,jdk.management,jdk.charsets,jdk.security.auth,jdk.security.jgss `
  --strip-debug --no-header-files --no-man-pages --compress=2 `
  --output jre
```

The trimmed runtime must cover every module Spring Boot 3 + embedded Tomcat touch at startup. Hand-listing only a few `java.*` modules fails in two stages: first with visible `NoClassDefFoundError`s (`java.beans.PropertyEditorSupport` needs `java.desktop`; `javax.naming.NamingException` needs `java.naming`), then — even after adding those — with a **silent `exit 1` and no stack trace**, because the JVM dies during logging-system init before Logback can print (missing `jdk.charsets` for the Windows console code page, plus `java.logging` / `java.prefs` / `java.xml.crypto` etc.). Using the `java.se` aggregate (pulls in every standard `java.*` module) plus the server/Windows `jdk.*` modules above is the verified working set (~50 MB, boots to a healthy `/api/health`). Prefer this over a fragile hand-picked list; a missing standard module is far costlier than a slightly larger JRE for a local desktop app.

Keep the resulting `jre/` directory at the repository root while packaging. The Electron bridge prefers `jre/bin/java.exe` in packaged mode and refuses to fall back to a system JRE. The electron-builder configuration copies it into the packaged resources together with the backend jar as `backend/app.jar`.

From the repository root, the checked-in packaging command validates this directory and creates the portable artifact:

```powershell
npm run package:portable -- -Rebuild -Version v0.7.3
```

The builder copies `jre/` into the packaged resources. The source `jre/` directory is intentionally not committed because it is a generated binary runtime.

Use PowerShell 7 and run the [local quality gates](test/TESTING_GUIDELINES.md) before packaging; the local packaging script does not run tests. `-Rebuild` refreshes existing build outputs. The current default version is `0.7.3`; `-Version` accepts it with or without the `v` prefix and sets both the application metadata and ZIP filename. Local packaging creates a verification artifact and never publishes a release. v0.8.0 is implemented in the working tree but has not been released; implementation tests do not change this default.

The GitHub workflow installs locked dependencies with `npm ci`, runs release-configuration tests, builds the frontend and Electron, builds the Rust update helper, and compiles/packages the backend with Java 17 and `-DskipTests`. Full frontend/backend business tests remain local release gates. Tag pushes publish a **pre-release**; manual runs only upload artifacts unless `publish_release` is enabled.

The portable update feature uses the same directory ZIP layout. Packaging includes `resources/updater/drill-updater.exe`, generates a root `drill-program-manifest.json` with a size and SHA-256 for every managed program file, and emits a matching `.zip.sha256` beside the ZIP. Both ZIP and checksum are uploaded. User data, configuration, models and attachments are outside the program manifest. Build the helper with `npm run build:updater`; it requires an installed Rust toolchain, while Cargo cache, build output and temporary files remain inside the workspace.

The updater waits for Electron and the JVM to exit before backing up and replacing program files; the embedded JRE is a managed program component. All update settings, caches, staging, helper transactions and program/database backups remain under APP_ROOT. See the [update specification](work/app-update/spec.md) for graceful shutdown, health acknowledgement and recovery.

For a real local update test, build the backend JAR and JRE first, then run `npm run test:updater:desktop`. This builds a fresh isolated portable test directory under `temp/`, uses the actual Electron entry, renderer, JAR and JRE, and checks normal update, startup automatic update and failed-JAR recovery. The 2026-11-12 local test used a JRE generated with Java 21; source compatibility remains Java 17 and the release workflow continues to generate Java 17. The isolated test does not publish or update a user's installation; details and remaining release checks are in the [validation record](work/app-update/plan.md).
