//! Embedding worker — versioned NDJSON protocol on stdin/stdout.
//!
//! # Protocol
//!
//! Every line on **stdin** is a JSON object.  Each object MUST contain
//! `protocolVersion`, `requestId` and `type` (the request variant).
//!
//! Every line on **stdout** is a JSON response object.  **No non‑JSON output
//! ever appears on stdout.**  Logs and diagnostics go to **stderr** only.
//!
//! See `src/protocol.rs` for the exact type definitions.
//!
//! # Lifecycle
//!
//! ```text
//!  Client                          Worker
//!    |── hello ──────────────────→ |  ← ready
//!    |── load_model ─────────────→ |  ← model_loaded / error
//!    |── embed (query|document) ─→ |  ← embed_result / error
//!    |── unload ─────────────────→ |  ← ok
//!    |── shutdown ───────────────→ |  ← ok  (worker exits)
//! ```
//!
//! The worker loads at most one model at a time.  A second `load_model`
//! implicitly unloads the previous one.

mod protocol;

use std::io::{BufRead, Write};
use std::path::Path;

use embedding_worker::{EmbeddingError, EmbeddingWorker, EXPECTED_DIMENSIONS};
use protocol::*;

fn main() {
    // All diagnostics go to stderr — never stdout.
    tracing_subscriber::fmt()
        .with_writer(std::io::stderr)
        .with_target(false)
        .with_level(true)
        .init();

    tracing::info!("embedding-worker started (protocolVersion={PROTOCOL_VERSION})");

    let mut worker: Option<EmbeddingWorker> = None;
    let stdin = std::io::stdin();
    let stdout = std::io::stdout();
    let mut stdout = stdout.lock();

    for line_result in stdin.lock().lines() {
        let line = match line_result {
            Ok(l) => l,
            Err(e) => {
                tracing::warn!("stdin I/O error, shutting down: {e}");
                break;
            }
        };

        // Skip blank lines (often sent as keep-alive or trailing newline).
        if line.trim().is_empty() {
            continue;
        }

        // --- Parse the JSON frame ---
        let request: Request = match serde_json::from_str(&line) {
            Ok(r) => r,
            Err(e) => {
                tracing::warn!("malformed JSON received: {e}");
                write_response(
                    &mut stdout,
                    &Response::error(
                        "",
                        ErrorCode::MalformedRequest,
                        format!("Invalid JSON: {e}"),
                        false,
                    ),
                );
                continue; // stay alive for the next request
            }
        };

        // --- Protocol version check ---
        if request.protocol_version() != PROTOCOL_VERSION {
            tracing::warn!(
                "protocol version mismatch: got {}, expected {}",
                request.protocol_version(),
                PROTOCOL_VERSION
            );
            write_response(
                &mut stdout,
                &Response::error(
                    request.request_id(),
                    ErrorCode::ProtocolVersionMismatch,
                    format!(
                        "Expected protocol version {PROTOCOL_VERSION}, got {}",
                        request.protocol_version()
                    ),
                    false,
                ),
            );
            continue; // mismatched version doesn't break the session
        }

        // --- Dispatch ---
        match request {
            Request::Hello { request_id, .. } => {
                tracing::info!("handling hello (request_id={request_id})");
                write_response(
                    &mut stdout,
                    &Response::Ready {
                        protocol_version: PROTOCOL_VERSION,
                        request_id,
                    },
                );
            }

            Request::LoadModel {
                request_id,
                model_id,
                model_dir,
                dimensions,
                required_files,
                ..
            } => {
                tracing::info!(
                    "handling load_model (request_id={request_id}, model_dir={model_dir})"
                );

                // Validate model_id is nonblank.
                if model_id.trim().is_empty() {
                    write_response(
                        &mut stdout,
                        &Response::error(
                            &request_id,
                            ErrorCode::MalformedRequest,
                            "modelId must be a nonblank string".to_string(),
                            false,
                        ),
                    );
                    continue;
                }

                // Validate dimensions early.
                if dimensions != EXPECTED_DIMENSIONS {
                    write_response(
                        &mut stdout,
                        &Response::error(
                            &request_id,
                            ErrorCode::DimensionMismatch,
                            format!("Expected {EXPECTED_DIMENSIONS} dimensions, got {dimensions}"),
                            false,
                        ),
                    );
                    continue;
                }

                let dir = Path::new(&model_dir);

                // Fast check: does every required file the caller listed exist?
                let missing: Vec<String> = required_files
                    .iter()
                    .filter(|f| !dir.join(f).exists())
                    .map(|f| f.to_string())
                    .collect();
                if !missing.is_empty() {
                    write_response(
                        &mut stdout,
                        &Response::error(
                            &request_id,
                            ErrorCode::ModelFilesMissing,
                            format!(
                                "Required files missing in '{}': {}",
                                model_dir,
                                missing.join(", ")
                            ),
                            true,
                        ),
                    );
                    continue;
                }

                // Unload any currently loaded model.
                worker = None;

                // Load the new model.
                match EmbeddingWorker::new(dir) {
                    Ok(w) => {
                        worker = Some(w);
                        tracing::info!("model loaded (request_id={request_id})");
                        write_response(
                            &mut stdout,
                            &Response::ModelLoaded {
                                protocol_version: PROTOCOL_VERSION,
                                request_id,
                                dimensions: EXPECTED_DIMENSIONS,
                            },
                        );
                    }
                    Err(e) => {
                        let (code, retryable) = match &e {
                            EmbeddingError::ModelFilesMissing(_) => {
                                (ErrorCode::ModelFilesMissing, true)
                            }
                            EmbeddingError::ModelLoadFailed(_) => {
                                (ErrorCode::ModelLoadFailed, false)
                            }
                            EmbeddingError::EmbeddingFailed(_) => {
                                (ErrorCode::EmbeddingFailed, true)
                            }
                            EmbeddingError::IoError(_) => (ErrorCode::InternalError, true),
                        };
                        tracing::error!("model load failed (request_id={request_id}): {e}");
                        write_response(
                            &mut stdout,
                            &Response::error(&request_id, code, e.to_string(), retryable),
                        );
                    }
                }
            }

            Request::Embed {
                request_id,
                mode,
                inputs,
                ..
            } => {
                tracing::info!(
                    "handling embed (request_id={request_id}, mode={mode:?}, inputs={})",
                    inputs.len()
                );

                // --- Validate model is loaded ---
                let w = match &mut worker {
                    Some(w) => w,
                    None => {
                        write_response(
                            &mut stdout,
                            &Response::error(
                                &request_id,
                                ErrorCode::ModelNotLoaded,
                                "No model loaded. Send load_model first.".to_string(),
                                true,
                            ),
                        );
                        continue;
                    }
                };

                // --- Validate input count ---
                if inputs.len() > MAX_EMBED_INPUTS {
                    write_response(
                        &mut stdout,
                        &Response::error(
                            &request_id,
                            ErrorCode::RequestTooLarge,
                            format!(
                                "Too many inputs: {}. Maximum is {MAX_EMBED_INPUTS}.",
                                inputs.len()
                            ),
                            false,
                        ),
                    );
                    continue;
                }

                // --- Validate per-input length (in Unicode scalar characters) ---
                let mut oversize = false;
                for input in &inputs {
                    if input.chars().count() > MAX_INPUT_LENGTH {
                        write_response(
                            &mut stdout,
                            &Response::error(
                                &request_id,
                                ErrorCode::RequestTooLarge,
                                format!(
                                    "Input length {} exceeds max {MAX_INPUT_LENGTH}",
                                    input.chars().count()
                                ),
                                false,
                            ),
                        );
                        oversize = true;
                        break;
                    }
                }
                if oversize {
                    continue;
                }

                // --- Validate total length (Unicode scalar characters) ---
                let total_chars: usize = inputs.iter().map(|s| s.chars().count()).sum();
                if total_chars > MAX_TOTAL_INPUT_LENGTH {
                    write_response(
                        &mut stdout,
                        &Response::error(
                            &request_id,
                            ErrorCode::RequestTooLarge,
                            format!(
                                "Total input size {total_chars} exceeds max {MAX_TOTAL_INPUT_LENGTH}"
                            ),
                            false,
                        ),
                    );
                    continue;
                }

                // --- Embed each input (stop at first failure) ---
                let mut embeddings = Vec::with_capacity(inputs.len());
                let mut failed = false;
                for input in &inputs {
                    let result = match mode {
                        EmbedMode::Query => w.embed_query(input),
                        EmbedMode::Document => w.embed_document(input),
                    };
                    match result {
                        Ok(emb) => embeddings.push(emb),
                        Err(e) => {
                            tracing::error!("embedding failed (request_id={request_id}): {e}");
                            write_response(
                                &mut stdout,
                                &Response::error(
                                    &request_id,
                                    ErrorCode::EmbeddingFailed,
                                    format!("Embedding failed: {e}"),
                                    true,
                                ),
                            );
                            failed = true;
                            break;
                        }
                    }
                }

                if !failed {
                    tracing::info!(
                        "embedding succeeded (request_id={request_id}, vectors={})",
                        embeddings.len()
                    );
                    write_response(
                        &mut stdout,
                        &Response::EmbedResult {
                            protocol_version: PROTOCOL_VERSION,
                            request_id,
                            embeddings,
                        },
                    );
                }
            }

            Request::Unload { request_id, .. } => {
                tracing::info!("handling unload (request_id={request_id})");
                worker = None;
                write_response(
                    &mut stdout,
                    &Response::Ok {
                        protocol_version: PROTOCOL_VERSION,
                        request_id,
                    },
                );
            }

            Request::Shutdown { request_id, .. } => {
                tracing::info!("handling shutdown (request_id={request_id})");
                write_response(
                    &mut stdout,
                    &Response::Ok {
                        protocol_version: PROTOCOL_VERSION,
                        request_id,
                    },
                );
                // Explicit flush before exiting.
                let _ = stdout.flush();
                break;
            }
        }
    }

    // Clean shutdown: model is dropped, logs flushed.
    drop(worker);
    tracing::info!("embedding-worker exiting");
}
