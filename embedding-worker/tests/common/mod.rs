//! Shared test utilities for embedding-worker integration tests.
#![allow(dead_code)]

use std::path::PathBuf;

/// Resolve the fixture directory.
///
/// Checks, in order:
/// 1. `EMBEDDING_FIXTURE_DIR` env var
/// 2. `APP_ROOT/data/models/embedding-fixtures` (dev default)
/// 3. `../runtime-portable/data/models/embedding-fixtures` relative to workspace root
pub fn fixture_dir() -> PathBuf {
    if let Ok(dir) = std::env::var("EMBEDDING_FIXTURE_DIR") {
        if !dir.is_empty() {
            return PathBuf::from(dir);
        }
    }

    if let Ok(root) = std::env::var("APP_ROOT") {
        return PathBuf::from(root)
            .join("data")
            .join("models")
            .join("embedding-fixtures");
    }

    // Fallback: workspace-root/runtime-portable/...
    let workspace =
        PathBuf::from(std::env::var("CARGO_MANIFEST_DIR").expect("CARGO_MANIFEST_DIR must be set"));
    workspace
        .parent()
        .expect("embedding-worker should have a parent dir")
        .join("runtime-portable")
        .join("data")
        .join("models")
        .join("embedding-fixtures")
}

/// Check if all five runtime-required files exist in `dir`.
pub fn fixture_is_complete(dir: &PathBuf) -> bool {
    let required = [
        "model_optimized.onnx",
        "tokenizer.json",
        "config.json",
        "tokenizer_config.json",
        "special_tokens_map.json",
    ];
    required.iter().all(|f| dir.join(f).exists())
}

/// Print a clear skip message when the fixture is missing.
pub fn skip_reason() -> String {
    format!(
        "Fixture not found at '{}'. Run `scripts/fetch-embedding-fixture.ps1` first.",
        fixture_dir().display()
    )
}
