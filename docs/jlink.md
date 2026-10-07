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
npm run package:portable -- -Rebuild -Version v0.6.3
```

The builder copies `jre/` into the packaged resources. The source `jre/` directory is intentionally not committed because it is a generated binary runtime.

Use PowerShell 7 and run the [local quality gates](testing/TESTING_GUIDELINES.md) before packaging; the local packaging script does not run tests. `-Rebuild` refreshes existing build outputs. The default version is `0.6.3`; `-Version` accepts it with or without the `v` prefix and sets both the application metadata and ZIP filename. Packaging never publishes a release.

The GitHub workflow installs locked dependencies with `npm ci`, runs release-configuration tests and both frontend/backend test suites, then builds and packages. Tag pushes publish a **pre-release**; manual runs only upload artifacts unless `publish_release` is enabled.
