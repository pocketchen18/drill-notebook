# Embedded JRE recipe

The repository intentionally does not contain a JRE binary. For a green-portable Windows release, build a JRE 17 image with the JDK used for the release:

```powershell
jlink --add-modules java.base,java.net.http,java.sql,jdk.crypto.ec,jdk.crypto.cryptoki,jdk.management,jdk.unsupported `
  --strip-debug --no-header-files --no-man-pages --compress=2 `
  --output jre
```

Keep the resulting `jre/` directory at the repository root while packaging. The Electron bridge prefers `jre/bin/java.exe` in packaged mode and refuses to fall back to a system JRE. The electron-builder configuration copies it into the packaged resources together with the backend jar as `backend/app.jar`.

From the repository root, the checked-in packaging command validates this directory and creates the portable artifact:

```powershell
npm run package:portable
```

The builder copies `jre/` into the packaged resources. The source `jre/` directory is intentionally not committed because it is a generated binary runtime.
