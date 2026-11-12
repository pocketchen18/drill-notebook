#![cfg_attr(windows, windows_subsystem = "windows")]
//! The helper accepts one fixed transaction under APP_ROOT/runtime/updater.
//! It never downloads, edits application preferences, installs services, or kills application tasks.
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::{collections::HashSet, fs, io::{Read, Write}, path::{Path, PathBuf}, process::{Child, Command}, thread, time::{Duration, Instant}};

type Result<T> = std::result::Result<T, Box<dyn std::error::Error>>;
const EXE: &str = "Drill Notebook.exe";
const MANIFEST: &str = "drill-program-manifest.json";

#[derive(Clone, Deserialize, Serialize)]
#[serde(deny_unknown_fields)]
struct FileSpec { path: String, size: u64, sha256: String }
#[derive(Deserialize, Serialize)]
#[serde(deny_unknown_fields, rename_all = "camelCase")]
struct Transaction {
    format: u32, id: String, app_root: PathBuf, from_version: String, to_version: String,
    parent_pid: u32, backend_pid: u32, boot_timeout_seconds: u64, files: Vec<FileSpec>, remove: Vec<String>,
}
#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct Manifest { format: u32, version: String, files: Vec<FileSpec> }
#[derive(Clone, Deserialize, Serialize)]
struct PreviousFile { path: String, existed: bool, size: u64, sha256: String }
#[derive(Default, Deserialize, Serialize)]
#[serde(default, rename_all = "camelCase")]
struct Journal {
    phase: String, from_version: String, to_version: String, helper_pid: u32,
    error: Option<String>, backed_up: bool, files: Vec<PreviousFile>, database: Vec<PreviousFile>,
    new_pid: u32, new_backend_pid: u32,
}
#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct Health { phase: String, version: String, pid: u32, #[serde(default)] backend_pid: u32, error: Option<String> }

struct Context { transaction: Transaction, root: PathBuf, work: PathBuf, stage: PathBuf, backup: PathBuf }

fn id_valid(id: &str) -> bool {
    id.len() == 36 && id.chars().enumerate().all(|(i,c)| if [8,13,18,23].contains(&i) { c == '-' } else { c.is_ascii_hexdigit() && !c.is_ascii_uppercase() })
}
fn relative_valid(value: &str) -> bool {
    if value.is_empty() || value.len() > 240 || value.starts_with('/') || value.contains('\\')
        || value.chars().any(|c| c.is_control() || ":<>\"|?*".contains(c)) { return false; }
    value.split('/').all(|part| {
        let stem = part.split('.').next().unwrap_or("").to_ascii_lowercase();
        !part.is_empty() && part != "." && part != ".." && !part.ends_with(['.', ' ']) && !part.starts_with(char::is_whitespace)
            && !["con", "prn", "aux", "nul"].contains(&stem.as_str())
            && !(stem.len() == 4 && (stem.starts_with("com") || stem.starts_with("lpt")) && matches!(stem.as_bytes()[3], b'1'..=b'9'))
    })
}
fn program_path(value: &str) -> bool {
    if !relative_valid(value) { return false; }
    let v = value.to_ascii_lowercase();
    if v.starts_with("resources/") { return true; }
    if v.starts_with("locales/") && v.ends_with(".pak") && !v[8..].contains('/') { return true; }
    ["drill notebook.exe", "chrome_100_percent.pak", "chrome_200_percent.pak", "d3dcompiler_47.dll", "ffmpeg.dll", "icudtl.dat",
        "libegl.dll", "libglesv2.dll", "resources.pak", "snapshot_blob.bin", "v8_context_snapshot.bin", "vk_swiftshader.dll",
        "vk_swiftshader_icd.json", "vulkan-1.dll", "license.electron.txt", "licenses.chromium.html", "notification_helper.exe", MANIFEST].contains(&v.as_str())
}
fn no_links(root: &Path, relative: &str) -> Result<PathBuf> {
    if !relative.is_empty() && !relative_valid(relative) { return Err("Invalid relative update path".into()); }
    let mut cursor = root.to_path_buf();
    check_link(&cursor)?;
    for part in relative.split('/').filter(|p| !p.is_empty()) { cursor.push(part); check_link(&cursor)?; }
    Ok(cursor)
}
fn check_link(value: &Path) -> Result<()> {
    match fs::symlink_metadata(value) {
        Ok(meta) => {
            if meta.file_type().is_symlink() { return Err("Symlinks are not supported in update paths".into()); }
            #[cfg(windows)] {
                use std::os::windows::fs::MetadataExt;
                if meta.file_attributes() & 0x400 != 0 { return Err("Reparse points are not supported in update paths".into()); }
            }
        },
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => (),
        Err(e) => return Err(e.into()),
    }
    Ok(())
}
fn hash(path: &Path) -> Result<(u64, String)> {
    let mut file = fs::File::open(path)?;
    let mut hash = Sha256::new();
    let mut buffer = [0u8; 65536];
    let mut size = 0;
    loop { let bytes = file.read(&mut buffer)?; if bytes == 0 { break; } hash.update(&buffer[..bytes]); size += bytes as u64; }
    Ok((size, format!("{:x}", hash.finalize())))
}
fn json<T: Serialize>(file: &Path, value: &T) -> Result<()> {
    if let Some(parent) = file.parent() { fs::create_dir_all(parent)?; }
    check_link(file)?;
    let nonce = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH)?.as_nanos();
    let temporary = file.with_extension(format!("json.{}.{nonce}.new", std::process::id()));
    let mut out = fs::OpenOptions::new().write(true).create_new(true).open(&temporary)?;
    out.write_all(&serde_json::to_vec_pretty(value)?)?;
    out.write_all(b"\n")?;
    out.sync_all()?;
    drop(out);
    fs::rename(temporary, file)?;
    Ok(())
}
fn read_json<T: for<'a> Deserialize<'a>>(file: &Path) -> Result<T> {
    if fs::metadata(file)?.len() > 8 * 1024 * 1024 { return Err("Update metadata exceeds size limit".into()); }
    Ok(serde_json::from_slice(&fs::read(file)?)?)
}
fn save(context: &Context, journal: &Journal) -> Result<()> { json(&context.work.join("status.json"), journal) }

fn context(file: &Path, recovery: bool) -> Result<Context> {
    let transaction: Transaction = read_json(file)?;
    if transaction.format != 1 || !id_valid(&transaction.id) || !transaction.app_root.is_absolute()
        || transaction.files.is_empty() || transaction.files.len() > 20_001 || transaction.remove.len() > 20_000
        || !(5..=120).contains(&transaction.boot_timeout_seconds) { return Err("Invalid update transaction".into()); }
    let old = semver::Version::parse(&transaction.from_version)?;
    let new = semver::Version::parse(&transaction.to_version)?;
    if new <= old { return Err("Update downgrade is not allowed".into()); }
    let root = transaction.app_root.clone();
    check_link(&root)?;
    let work = no_links(&root, &format!("runtime/updater/{}", transaction.id))?;
    if file != work.join("transaction.json") || std::env::current_exe()? != work.join("drill-updater.exe") {
        return Err("The updater must run from this transaction's portable directory".into());
    }
    let stage = no_links(&work, "staged")?;
    let backup = no_links(&root, &format!("backups/updates/{}", transaction.id))?;
    let mut paths = HashSet::new();
    let mut total = 0u64;
    for f in &transaction.files {
        if !program_path(&f.path) || f.sha256.len() != 64 || !f.sha256.chars().all(|c| c.is_ascii_hexdigit() && !c.is_ascii_uppercase())
            || !paths.insert(f.path.to_ascii_lowercase()) { return Err("Invalid or duplicate managed program path".into()); }
        total = total.checked_add(f.size).ok_or("Invalid update size")?;
        if total > 2 * 1024 * 1024 * 1024 { return Err("Unpacked update is too large".into()); }
        no_links(&root, &f.path)?;
        if !recovery {
            let staged = no_links(&stage, &f.path)?;
            let (size, digest) = hash(&staged)?;
            if size != f.size || digest != f.sha256 { return Err(format!("Staged file verification failed: {}", f.path).into()); }
        }
    }
    for p in &transaction.remove {
        if !program_path(p) || !paths.insert(p.to_ascii_lowercase()) { return Err("Unsafe managed file removal".into()); }
        no_links(&root, p)?;
    }
    for p in [EXE, MANIFEST, "resources/app.asar", "resources/backend/app.jar", "resources/jre/bin/java.exe", "resources/updater/drill-updater.exe"] {
        if !transaction.files.iter().any(|f| f.path == p) { return Err("Required portable program file is missing".into()); }
    }
    if !recovery {
        let manifest: Manifest = read_json(&stage.join(MANIFEST))?;
        if manifest.format != 1 || manifest.version != transaction.to_version || manifest.files.len() + 1 != transaction.files.len() {
            return Err("Staged manifest does not match the update transaction".into());
        }
        for f in &manifest.files {
            if f.path == MANIFEST || !transaction.files.iter().any(|a| a.path == f.path && a.size == f.size && a.sha256 == f.sha256) {
                return Err("Staged manifest file mismatch".into());
            }
        }
    }
    Ok(Context { transaction, root, work, stage, backup })
}

fn durable_copy(source: &Path, target: &Path) -> Result<()> {
    if let Some(parent) = target.parent() { fs::create_dir_all(parent)?; }
    fs::copy(source, target)?;
    fs::OpenOptions::new().write(true).open(target)?.sync_all()?;
    Ok(())
}
fn previous(context: &Context, p: &str, category: &str) -> Result<PreviousFile> {
    let old = no_links(&context.root, p)?;
    let existed = old.exists();
    let (size, sha256) = if existed {
        if !old.is_file() { return Err("A managed program path is a directory".into()); }
        let original = hash(&old)?;
        let target = no_links(&context.backup, &format!("{category}/{p}"))?;
        durable_copy(&old, &target)?;
        if hash(&target)? != original { return Err("Program/database backup verification failed".into()); }
        original
    } else { (0, String::new()) };
    Ok(PreviousFile { path: p.into(), existed, size, sha256 })
}
fn backup(context: &Context, journal: &mut Journal) -> Result<()> {
    journal.phase = "backing-up".into(); save(context, journal)?;
    for f in &context.transaction.files { journal.files.push(previous(context, &f.path, "program")?); }
    for p in &context.transaction.remove { journal.files.push(previous(context, p, "program")?); }
    // SQLite is copied only after the gracefully closed JVM has exited. Never copy a live database.
    for p in ["data/study.db", "data/study.db-wal", "data/study.db-shm"] { journal.database.push(previous(context, p, "database")?); }
    journal.backed_up = true;
    // This complete baseline is immutable and durable before the first replacement; recovery is idempotent.
    json(&context.work.join("baseline.json"), journal)?;
    journal.phase = "installing".into(); save(context, journal)
}
fn install_files(context: &Context) -> Result<()> {
    for f in &context.transaction.files {
        let source = no_links(&context.stage, &f.path)?;
        let target = no_links(&context.root, &f.path)?;
        if let Some(parent) = target.parent() { fs::create_dir_all(parent)?; }
        // MoveFileExW/rename replaces only a managed file, after all application handles have closed.
        fs::rename(&source, &target)?;
        if hash(&target)? != (f.size, f.sha256.clone()) { return Err("Installed program verification failed".into()); }
    }
    for p in &context.transaction.remove {
        let file = no_links(&context.root, p)?;
        if file.exists() { fs::remove_file(file)?; } // obsolete managed programs have a verified backup
    }
    Ok(())
}
fn restore_files(context: &Context, specs: &[PreviousFile], category: &str) -> Result<()> {
    for p in specs {
        let target = no_links(&context.root, &p.path)?;
        if p.existed {
            let source = no_links(&context.backup, &format!("{category}/{}", p.path))?;
            if hash(&source)? != (p.size, p.sha256.clone()) { return Err("Recovery backup verification failed; original data was not overwritten".into()); }
            let temporary = no_links(&context.work, "restore-file.new")?;
            durable_copy(&source, &temporary)?;
            if let Some(parent) = target.parent() { fs::create_dir_all(parent)?; }
            fs::rename(temporary, target)?;
        } else if target.exists() { fs::remove_file(target)?; }
    }
    Ok(())
}
fn validate_baseline(transaction: &Transaction, baseline: &Journal) -> Result<()> {
    if !baseline.backed_up || baseline.from_version != transaction.from_version || baseline.to_version != transaction.to_version
        || baseline.files.len() != transaction.files.len() + transaction.remove.len() || baseline.database.len() != 3 {
        return Err("Recovery baseline is incomplete".into());
    }
    let expected: HashSet<_> = transaction.files.iter().map(|f| f.path.as_str()).chain(transaction.remove.iter().map(String::as_str)).collect();
    let actual: HashSet<_> = baseline.files.iter().map(|p| p.path.as_str()).collect();
    let databases: HashSet<_> = baseline.database.iter().map(|p| p.path.as_str()).collect();
    if actual != expected || databases.len() != 3 || baseline.database.iter().any(|p|
        !["data/study.db", "data/study.db-wal", "data/study.db-shm"].contains(&p.path.as_str())) { return Err("Unsafe recovery baseline".into()); }
    Ok(())
}
fn rollback(context: &Context, journal: &mut Journal) -> Result<()> {
    let baseline: Journal = read_json(&context.work.join("baseline.json"))?;
    validate_baseline(&context.transaction, &baseline)?;
    // Validate every original before touching a current program or migrated database.
    for (files, category) in [(&baseline.files, "program"), (&baseline.database, "database")] {
        for p in files.iter().filter(|f| f.existed) {
            if hash(&no_links(&context.backup, &format!("{category}/{}", p.path))?)? != (p.size, p.sha256.clone()) {
                return Err("Recovery backup is corrupt; program and data were retained for manual recovery".into());
            }
        }
    }
    journal.phase = "rolling-back".into(); save(context, journal)?;
    for p in &baseline.database {
        let current = no_links(&context.root, &p.path)?;
        let preserved = no_links(&context.backup, &format!("failed-data/{}", p.path))?;
        if current.exists() && !preserved.exists() { durable_copy(&current, &preserved)?; }
    }
    restore_files(context, &baseline.files, "program")?;
    restore_files(context, &baseline.database, "database")?;
    journal.phase = "rolled-back".into(); save(context, journal)?;
    record_failure(context, journal)
}
fn record_failure(context: &Context, journal: &Journal) -> Result<()> {
    json(&no_links(&context.root, "config/update-failure.json")?, &serde_json::json!({
        "format": 1, "id": context.transaction.id, "version": context.transaction.to_version,
        "error": journal.error.as_deref().unwrap_or("The previous update failed and was restored")
    }))
}

#[cfg(windows)]
mod processes {
    use super::*;
    use std::os::windows::process::CommandExt;
    type Handle = *mut std::ffi::c_void;
    #[link(name = "kernel32")]
    extern "system" {
        fn OpenProcess(access: u32, inherit: i32, pid: u32) -> Handle;
        fn WaitForSingleObject(handle: Handle, milliseconds: u32) -> u32;
        fn CloseHandle(handle: Handle) -> i32;
        fn QueryFullProcessImageNameW(handle: Handle, flags: u32, name: *mut u16, size: *mut u32) -> i32;
    }
    pub fn wait(pid: u32, expected: &Path, duration: Duration) -> Result<bool> {
        unsafe { wait_with_query(pid, expected, duration, |handle, name, size| QueryFullProcessImageNameW(handle, 0, name, size)) }
    }
    unsafe fn wait_with_query(pid: u32, expected: &Path, duration: Duration, query: impl Fn(Handle, *mut u16, *mut u32) -> i32) -> Result<bool> {
        if pid == 0 { return Ok(true); }
            let handle = OpenProcess(0x00100000 | 0x1000, 0, pid);
            if handle.is_null() {
                let code = std::io::Error::last_os_error().raw_os_error();
                return if matches!(code, Some(87)) { Ok(true) } else { Err("Unable to inspect application process; update stopped".into()) };
            }
            // A process can exit between OpenProcess and querying its image. A signaled handle is already safe to replace.
            if WaitForSingleObject(handle, 0) == 0 { CloseHandle(handle); return Ok(true); }
            let mut buffer = vec![0u16; 32768]; let mut size = buffer.len() as u32;
            let queried = query(handle, buffer.as_mut_ptr(), &mut size);
            if queried == 0 {
                // Windows can tear down the image before the process handle becomes signaled.
                // Keep the same handle and wait; only a confirmed exit permits replacement.
                let stopped = WaitForSingleObject(handle, duration.as_millis().min(u32::MAX as u128) as u32);
                CloseHandle(handle);
                return match stopped { 0 => Ok(true), 258 => Ok(false), _ => Err("Unable to wait for application exit after image query failed".into()) };
            }
            let actual = String::from_utf16_lossy(&buffer[..size as usize]);
            // Win32 returns backslashes and may expand short names; compare normalized filesystem paths.
            let belongs = match (fs::canonicalize(Path::new(&actual)), fs::canonicalize(expected)) {
                (Ok(actual), Ok(expected)) => actual.as_os_str().to_string_lossy().to_lowercase() == expected.as_os_str().to_string_lossy().to_lowercase(),
                _ => false,
            };
            if !belongs {
                CloseHandle(handle); return Err("Process identity does not belong to the portable application".into());
            }
            let result = WaitForSingleObject(handle, duration.as_millis().min(u32::MAX as u128) as u32);
            CloseHandle(handle);
            match result { 0 => Ok(true), 258 => Ok(false), _ => Err("Unable to wait for application exit".into()) }
    }
    #[cfg(test)]
    mod tests {
        use super::*;
        #[test]
        fn failed_image_query_waits_and_never_treats_a_live_process_as_exited() {
            let queried = std::cell::Cell::new(false);
            let started = Instant::now();
            let stopped = unsafe { wait_with_query(std::process::id(), &std::env::current_exe().unwrap(), Duration::from_millis(30), |_, _, _| {
                queried.set(true);
                0 // Deterministically reproduce the Win32 image-query failure during teardown.
            }) }.unwrap();
            assert!(queried.get());
            assert!(!stopped, "an unsignaled process handle must not permit program replacement");
            assert!(started.elapsed() >= Duration::from_millis(10), "query failure must wait for exit instead of failing immediately");
        }
    }
    pub fn start(context: &Context, updated: bool) -> Result<Child> {
        let mut command = Command::new(context.root.join(EXE));
        command.current_dir(&context.root).env("APP_ROOT", &context.root).creation_flags(0x08000000);
        if updated { command.env("DRILL_UPDATE_TRANSACTION", &context.transaction.id); }
        else { command.env_remove("DRILL_UPDATE_TRANSACTION"); }
        Ok(command.spawn()?)
    }
}
#[cfg(not(windows))]
mod processes {
    use super::*;
    pub fn wait(pid: u32, _expected: &Path, _duration: Duration) -> Result<bool> { Ok(pid == 0) }
    pub fn start(context: &Context, updated: bool) -> Result<Child> {
        let mut command = Command::new(context.root.join(EXE)); command.current_dir(&context.root).env("APP_ROOT", &context.root);
        if updated { command.env("DRILL_UPDATE_TRANSACTION", &context.transaction.id); } else { command.env_remove("DRILL_UPDATE_TRANSACTION"); }
        Ok(command.spawn()?)
    }
}
fn exited(context: &Context, main: u32, backend: u32, timeout: Duration) -> Result<bool> {
    Ok(processes::wait(main, &context.root.join(EXE), timeout)?
        && processes::wait(backend, &context.root.join("resources/jre/bin/java.exe"), timeout)?)
}
fn wait_boot(context: &Context, child: &mut Child, journal: &mut Journal) -> Result<()> {
    let deadline = Instant::now() + Duration::from_secs(context.transaction.boot_timeout_seconds);
    while Instant::now() < deadline {
        if let Ok(health) = read_json::<Health>(&context.work.join("health.json")) {
            if health.version != context.transaction.to_version || health.pid != child.id() { return Err("Invalid new-version health acknowledgement".into()); }
            journal.new_backend_pid = health.backend_pid; save(context, journal)?;
            if health.phase == "healthy" { return Ok(()); }
            if health.phase == "failed" { return Err(health.error.unwrap_or_else(|| "The new version did not start successfully".into()).into()); }
        }
        if child.try_wait()?.is_some() { return Err("The new version exited before becoming ready".into()); }
        thread::sleep(Duration::from_millis(200));
    }
    Err("The new version did not become ready in time; close it and reopen to recover".into())
}
fn run(context: &Context, recovery: bool, recovery_parent: u32) -> Result<()> {
    let mut journal = Journal { phase: "waiting".into(), from_version: context.transaction.from_version.clone(),
        to_version: context.transaction.to_version.clone(), helper_pid: std::process::id(), ..Default::default() };
    if recovery {
        journal = read_json(&context.work.join("status.json"))?;
        journal.helper_pid = std::process::id();
        // A durable acknowledgement is the commit point. Business edits may exist afterwards: never roll them back.
        if let Ok(health) = read_json::<Health>(&context.work.join("health.json")) {
            if health.phase == "healthy" && health.version == context.transaction.to_version {
                journal.phase = "complete".into(); save(context, &journal)?; return Ok(());
            }
        }
        if !processes::wait(recovery_parent, &context.root.join(EXE), Duration::from_secs(120))?
            || !exited(context, journal.new_pid, journal.new_backend_pid, Duration::from_secs(10))? {
            return Err("A previous update process is still running; recovery will not replace live data".into());
        }
        journal.error.get_or_insert("The previous update was interrupted and has been restored".into());
        if context.work.join("baseline.json").exists() { rollback(context, &mut journal)?; }
        else { journal.phase = "rolled-back".into(); save(context, &journal)?; record_failure(context, &journal)?; }
        processes::start(context, false)?; return Ok(());
    }
    save(context, &journal)?;
    json(&context.work.join("ready.json"), &serde_json::json!({"id":context.transaction.id,"pid":std::process::id()}))?;
    if !exited(context, context.transaction.parent_pid, context.transaction.backend_pid, Duration::from_secs(120))? {
        journal.phase = "recovery-required".into(); journal.error = Some("Application/backend did not exit; no program was replaced".into());
        save(context, &journal)?; record_failure(context, &journal)?; return Err(journal.error.clone().unwrap().into());
    }
    let attempt = (|| -> Result<()> {
        backup(context, &mut journal)?;
        install_files(context)?;
        journal.phase = "starting".into(); save(context, &journal)?;
        let mut child = processes::start(context, true)?;
        journal.new_pid = child.id(); save(context, &journal)?;
        wait_boot(context, &mut child, &mut journal)?;
        journal.phase = "complete".into(); save(context, &journal)?;
        Ok(())
    })();
    if let Err(error) = attempt {
        journal.error = Some(error.to_string());
        if !exited(context, journal.new_pid, journal.new_backend_pid, Duration::from_secs(10))? {
            journal.phase = "recovery-required".into(); save(context, &journal)?; record_failure(context, &journal)?;
            return Err("New application is still running; close it before recovery. Backups and data were retained".into());
        }
        if journal.backed_up { rollback(context, &mut journal)?; }
        else { journal.phase = "rolled-back".into(); save(context, &journal)?; record_failure(context, &journal)?; }
        processes::start(context, false)?;
    }
    Ok(())
}

fn notify_failure(message: &str) {
    #[cfg(windows)] unsafe {
        #[link(name = "user32")] extern "system" { fn MessageBoxW(window: *mut std::ffi::c_void, text: *const u16, title: *const u16, flags: u32) -> i32; }
        let title: Vec<u16> = "Drill Notebook 更新未完成\0".encode_utf16().collect();
        let text: Vec<u16> = format!("更新未完成，已保留原程序、数据和恢复材料。\n请关闭软件后重新打开。\n\n{message}\0").encode_utf16().collect();
        MessageBoxW(std::ptr::null_mut(), text.as_ptr(), title.as_ptr(), 0x10);
    }
    #[cfg(not(windows))] eprintln!("{message}");
}
fn main() {
    let arguments: Vec<_> = std::env::args_os().skip(1).collect();
    let recovery = arguments.first().is_some_and(|a| a == "--recover");
    let result = (|| -> Result<()> {
        let file = if recovery { arguments.get(1) } else if arguments.first().is_some_and(|a| a == "--transaction") { arguments.get(1) } else { None }
            .ok_or("A portable update transaction is required")?;
        let recovery_parent = if recovery { arguments.get(2).and_then(|s| s.to_str()).and_then(|s| s.parse().ok()).unwrap_or(0) } else { 0 };
        let ctx = context(&PathBuf::from(file), recovery)?;
        if let Err(error) = run(&ctx, recovery, recovery_parent) {
            let mut journal = read_json::<Journal>(&ctx.work.join("status.json")).unwrap_or_default();
            journal.phase = "recovery-required".into();
            journal.error = Some(error.to_string());
            let _ = save(&ctx, &journal);
            let _ = record_failure(&ctx, &journal);
            return Err(error);
        }
        Ok(())
    })();
    if let Err(error) = result { notify_failure(&error.to_string()); std::process::exit(1); }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test] fn win32_paths_and_managed_roots() {
        for p in ["../data/study.db", "resources/../data/study.db", "resources/NUL.txt", "resources/test:ads", "resources/a.", "resources\\app.asar", "data/study.db", "config/app-config.json"] {
            assert!(!program_path(p), "{p}");
        }
        for p in [EXE, "resources/app.asar", "resources/jre/bin/java.exe", "locales/zh-CN.pak"] { assert!(program_path(p)); }
    }
    #[test] fn transaction_ids_are_fixed_relative_names() {
        assert!(id_valid("11111111-1111-4111-8111-111111111111"));
        assert!(!id_valid("../../outside")); assert!(!id_valid("11111111-1111-4111-8111-11111111111G"));
    }
    fn test_context() -> Context {
        let nonce = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).unwrap().as_nanos();
        let root = Path::new(env!("CARGO_MANIFEST_DIR")).parent().unwrap().join("temp").join(format!("updater-rust-{}-{nonce}", std::process::id()));
        fs::create_dir_all(&root).unwrap();
        Context { work: root.join("runtime/updater/test"), stage: root.join("staged"), backup: root.join("backups/updates/test"),
            transaction: Transaction { format: 1, id: "11111111-1111-4111-8111-111111111111".into(), app_root: root.clone(),
                from_version: "0.7.3".into(), to_version: "0.8.0".into(), parent_pid: 0, backend_pid: 0, boot_timeout_seconds: 10,
                files: vec![FileSpec { path: EXE.into(), size: 3, sha256: "0".repeat(64) }], remove: vec![] }, root }
    }
    struct TestRoot(PathBuf);
    impl Drop for TestRoot { fn drop(&mut self) { fs::remove_dir_all(&self.0).unwrap(); } }
    #[test] fn incomplete_or_duplicate_recovery_baseline_is_rejected() {
        let context = test_context(); let _cleanup = TestRoot(context.root.clone());
        let mut journal = Journal { from_version: "0.7.3".into(), to_version: "0.8.0".into(), ..Default::default() };
        backup(&context, &mut journal).unwrap();
        assert!(validate_baseline(&context.transaction, &journal).is_ok());
        journal.database[1].path = journal.database[0].path.clone();
        assert!(validate_baseline(&context.transaction, &journal).is_err());
        journal.database[1].path = "data/study.db-wal".into();
        journal.files[0].path = "data/attachments/photo.png".into();
        assert!(validate_baseline(&context.transaction, &journal).is_err());
    }
    #[test] fn corrupt_backup_never_overwrites_current_program_or_database() {
        let context = test_context(); let _cleanup = TestRoot(context.root.clone());
        fs::write(context.root.join(EXE), b"old program").unwrap();
        fs::create_dir_all(context.root.join("data")).unwrap();
        fs::write(context.root.join("data/study.db"), b"old database").unwrap();
        let mut journal = Journal { from_version: "0.7.3".into(), to_version: "0.8.0".into(), ..Default::default() };
        backup(&context, &mut journal).unwrap();
        fs::write(context.root.join(EXE), b"new program").unwrap();
        fs::write(context.root.join("data/study.db"), b"new database").unwrap();
        fs::write(context.backup.join("database/data/study.db"), b"corrupt").unwrap();
        assert!(rollback(&context, &mut journal).is_err());
        assert_eq!(fs::read(context.root.join(EXE)).unwrap(), b"new program");
        assert_eq!(fs::read(context.root.join("data/study.db")).unwrap(), b"new database");
        assert!(!context.backup.join("failed-data").exists());
    }
}
