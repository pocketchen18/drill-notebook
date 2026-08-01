//! Integration test: offline behavior when model files are missing.
//!
//! Verifies that:
//! - Missing model directory returns structured MODEL_FILES_MISSING error.
//! - No implicit download/cache is attempted.
//! - No FastEmbed/HF cache directories are created in user space or
//!   workspace-private env locations.

mod common;

use std::env;
use std::path::{Path, PathBuf};

use embedding_worker::EmbeddingWorker;

fn check_no_cache_under(dir: &Path, label: &str) {
    let cache = dir.join(".fastembed_cache");
    assert!(
        !cache.exists(),
        "{label}: unexpected .fastembed_cache created under {}: {}",
        dir.display(),
        cache.display()
    );
}

#[test]
fn model_files_missing_empty_dir() {
    // Create a temporary empty directory and point the worker at it.
    let tmp = tempfile::TempDir::new().expect("create temp dir");
    let err = EmbeddingWorker::new(tmp.path()).unwrap_err();

    let msg = err.to_string();
    assert!(
        msg.starts_with("MODEL_FILES_MISSING:"),
        "expected MODEL_FILES_MISSING, got: {msg}"
    );
    eprintln!("OK empty_dir: error = {msg}");
}

#[test]
fn model_files_missing_nonexistent_path() {
    // Use a guaranteed-missing subdirectory inside a temp dir
    // (workspace-local, no C:\ hardcoded paths).
    let tmp = tempfile::TempDir::new().expect("create temp dir");
    let bad_path = tmp.path().join("nonexistent_subdir");
    let err = EmbeddingWorker::new(&bad_path).unwrap_err();

    let msg = err.to_string();
    assert!(
        msg.starts_with("MODEL_FILES_MISSING:"),
        "expected MODEL_FILES_MISSING, got: {msg}"
    );
    eprintln!("OK nonexistent_path: error = {msg}");
}

#[test]
fn model_files_missing_partial_files() {
    // Create a directory with only some files — should still fail
    // because all five runtime-required files must be present.
    let tmp = tempfile::TempDir::new().expect("create temp dir");

    // Write one file but not the others
    std::fs::write(tmp.path().join("config.json"), b"{}").expect("write config.json");

    let err = EmbeddingWorker::new(tmp.path()).unwrap_err();
    let msg = err.to_string();

    assert!(
        msg.starts_with("MODEL_FILES_MISSING:"),
        "expected MODEL_FILES_MISSING, got: {msg}"
    );
    assert!(
        msg.contains("model_optimized.onnx"),
        "error should list model_optimized.onnx: {msg}"
    );
    assert!(
        msg.contains("tokenizer.json"),
        "error should list tokenizer.json: {msg}"
    );
    eprintln!("OK partial_files: error = {msg}");
}

#[test]
fn no_implicit_cache_created() {
    // When we try to load from a missing directory, fastembed must NOT
    // create any cache files (no hf-hub feature, no download).
    //
    // Check that workspace-root cache AND env-configured workspace-private
    // locations are all untouched.
    let tmp = tempfile::TempDir::new().expect("create temp dir");
    let _err = EmbeddingWorker::new(tmp.path()).unwrap_err();

    // 1. Workspace root .fastembed_cache
    let manifest_dir =
        env::var("CARGO_MANIFEST_DIR").expect("CARGO_MANIFEST_DIR must be set by Cargo");
    let workspace_root = Path::new(&manifest_dir)
        .parent()
        .expect("embedding-worker has a parent workspace directory");
    check_no_cache_under(workspace_root, "workspace root");

    // 2. CARGO_HOME (workspace-private, set by scripts)
    if let Ok(cargo_home) = env::var("CARGO_HOME") {
        let p = PathBuf::from(cargo_home);
        if p.is_dir() {
            check_no_cache_under(&p, "CARGO_HOME");
        }
    }

    // 3. TEMP (workspace-private, set by scripts)
    for var in &["TEMP", "TMP"] {
        if let Ok(temp_dir) = env::var(var) {
            let p = PathBuf::from(&temp_dir);
            if p.is_dir() {
                check_no_cache_under(&p, var);
            }
        }
    }

    eprintln!("OK no_implicit_cache: no cache created in workspace root, CARGO_HOME, or TEMP");
}

#[test]
fn error_type_is_displayed_correctly() {
    let tmp = tempfile::TempDir::new().expect("create temp dir");
    let err = EmbeddingWorker::new(tmp.path()).unwrap_err();

    let display = format!("{err}");
    assert!(
        display.contains("MODEL_FILES_MISSING"),
        "Display should contain MODEL_FILES_MISSING: {display}"
    );

    let debug = format!("{err:?}");
    assert!(
        debug.contains("ModelFilesMissing"),
        "Debug should contain ModelFilesMissing: {debug}"
    );

    eprintln!("OK error_display: {display}");
}
