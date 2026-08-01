//! Integration tests for the versioned NDJSON worker protocol.
//!
//! These tests spawn the real `embedding-worker` binary as a child process,
//! pipe NDJSON to stdin and assert JSON‑L responses on stdout.
//!
//! Tests that require the model fixture (`require_fixture`) panic if the
//! fixture is missing — they form the hard gate for protocol acceptance.

mod common;

use std::io::{BufRead, BufReader, Write};
use std::path::{Path, PathBuf};
use std::process::{Child, Command, Stdio};

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

/// Locate the `embedding-worker` binary.
///
/// Search order:
///   1. `EMBEDDING_WORKER_EXE` env var
///   2. `CARGO_TARGET_DIR` / `x86_64-pc-windows-msvc` / `{release,debug}` / `embedding-worker.exe`
///   3. `<manifest>/target` / `x86_64-pc-windows-msvc` / `{release,debug}` / `embedding-worker.exe`
fn worker_exe() -> PathBuf {
    // Allow explicit override.
    if let Ok(exe) = std::env::var("EMBEDDING_WORKER_EXE") {
        if !exe.is_empty() {
            return PathBuf::from(exe);
        }
    }

    let manifest_dir = PathBuf::from(env!("CARGO_MANIFEST_DIR"));

    // Determine target root.
    let target_root: PathBuf = std::env::var("CARGO_TARGET_DIR")
        .map(PathBuf::from)
        .unwrap_or_else(|_| manifest_dir.join("target"));

    // Determine profile.
    let profile = if cfg!(debug_assertions) {
        "debug"
    } else {
        "release"
    };

    let exe = target_root
        .join("x86_64-pc-windows-msvc")
        .join(profile)
        .join("embedding-worker.exe");

    if exe.exists() {
        return exe;
    }

    // Fallback: try the opposite profile.
    let alt_profile = if cfg!(debug_assertions) {
        "release"
    } else {
        "debug"
    };
    let alt = target_root
        .join("x86_64-pc-windows-msvc")
        .join(alt_profile)
        .join("embedding-worker.exe");
    if alt.exists() {
        return alt;
    }

    panic!(
        "embedding-worker binary not found at {} or {}.\n\
         Build it first with: cargo build --release --target x86_64-pc-windows-msvc",
        exe.display(),
        alt.display()
    );
}

/// Return the fixture directory.  Panics if the fixture is incomplete.
fn require_fixture() -> PathBuf {
    let dir = common::fixture_dir();
    if !common::fixture_is_complete(&dir) {
        panic!(
            "Fixture not found at '{}'. Run `scripts/fetch-embedding-fixture.ps1` first.",
            dir.display()
        );
    }
    dir
}

/// A managed child process with typed I/O.
struct WorkerProcess {
    child: Child,
    stdin: std::io::BufWriter<std::process::ChildStdin>,
    stdout: BufReader<std::process::ChildStdout>,
    /// Stderr collector handle (joined in `drop`).
    stderr_thread: Option<std::thread::JoinHandle<String>>,
}

impl WorkerProcess {
    /// Spawn the worker binary.
    fn spawn() -> Self {
        let exe = worker_exe();
        let mut child = Command::new(&exe)
            .stdin(Stdio::piped())
            .stdout(Stdio::piped())
            .stderr(Stdio::piped())
            .spawn()
            .unwrap_or_else(|e| panic!("Failed to spawn {exe:?}: {e}"));

        let stdin = std::io::BufWriter::new(child.stdin.take().unwrap());
        let stdout = BufReader::new(child.stdout.take().unwrap());
        let stderr = child.stderr.take().unwrap();

        // Collect stderr on a background thread so the pipe doesn't stall.
        let stderr_thread = std::thread::spawn(move || {
            let mut buf = String::new();
            std::io::Read::read_to_string(&mut std::io::BufReader::new(stderr), &mut buf).ok();
            buf
        });

        Self {
            child,
            stdin,
            stdout,
            stderr_thread: Some(stderr_thread),
        }
    }

    /// Send a line (JSON) to the worker.
    fn send(&mut self, line: &str) {
        writeln!(self.stdin, "{line}").expect("write to worker stdin");
        self.stdin.flush().expect("flush worker stdin");
    }

    /// Read one response line from the worker (with timeout guard via
    /// `wait_stdout_ready`).
    fn recv(&mut self) -> String {
        let mut line = String::new();
        self.stdout
            .read_line(&mut line)
            .expect("read line from worker stdout");
        if line.is_empty() {
            panic!("worker stdout closed unexpectedly");
        }
        let trimmed = line.trim_end().to_string();
        eprintln!("[test] << {trimmed}");
        trimmed
    }

    /// Parse the next response line as JSON Value.
    fn recv_json(&mut self) -> serde_json::Value {
        let line = self.recv();
        serde_json::from_str(&line).unwrap_or_else(|e| {
            panic!("failed to parse worker response as JSON: {e}\nline: {line}")
        })
    }

    /// Assert the response has `type == expected_type` and `requestId == expected_rid`.
    fn assert_response_type(&mut self, expected_type: &str, expected_rid: &str) {
        let v = self.recv_json();
        assert_eq!(
            v["type"], expected_type,
            "expected type={expected_type}, got {:?}",
            v["type"]
        );
        assert_eq!(
            v["requestId"], expected_rid,
            "expected requestId={expected_rid}, got {:?}",
            v["requestId"]
        );
    }

    /// Assert the response is an error with the given code.
    fn assert_error(&mut self, expected_rid: &str, expected_code: &str) {
        let v = self.recv_json();
        assert_eq!(v["type"], "error", "expected error frame, got {v:?}");
        assert_eq!(
            v["requestId"], expected_rid,
            "expected requestId={expected_rid}, got {:?}",
            v["requestId"]
        );
        assert_eq!(
            v["code"], expected_code,
            "expected code={expected_code}, got {:?}",
            v["code"]
        );
    }

    /// Wait for the process to exit and return (exit_code, stderr).
    fn wait_for_exit(mut self) -> (i32, String) {
        // Close stdin so the worker sees EOF.
        drop(self.stdin);

        let exit = self.child.wait().expect("wait for worker process");

        let stderr = self
            .stderr_thread
            .take()
            .unwrap()
            .join()
            .expect("stderr thread join");

        (exit.code().unwrap_or(-1), stderr)
    }
}

/// Send a hello request and verify the ready response.
fn send_hello(proc: &mut WorkerProcess, rid: &str) {
    let req = format!(r#"{{"protocolVersion":1,"requestId":"{rid}","type":"hello"}}"#);
    eprintln!("[test] >> {req}");
    proc.send(&req);
    proc.assert_response_type("ready", rid);
}

/// Send a load_model request using the fixture directory.
fn send_load_model(proc: &mut WorkerProcess, rid: &str, model_dir: &Path) {
    let req = serde_json::json!({
        "protocolVersion": 1,
        "requestId": rid,
        "type": "load_model",
        "modelId": "bge-small-zh-v1.5",
        "modelDir": model_dir,
        "requiredFiles": [
            "model_optimized.onnx",
            "tokenizer.json",
            "config.json",
            "tokenizer_config.json",
            "special_tokens_map.json"
        ],
        "dimensions": 512,
    });
    let req_str = req.to_string();
    eprintln!("[test] >> {req_str}");
    proc.send(&req_str);
    proc.assert_response_type("model_loaded", rid);
}

// ===========================================================================
// Tests – Protocol only (no model fixture required)
// ===========================================================================

#[test]
fn hello_ready() {
    let mut proc = WorkerProcess::spawn();
    send_hello(&mut proc, "h1");
    let (_code, stderr) = proc.wait_for_exit();
    eprintln!("[test] stderr:\n{stderr}");
}

#[test]
fn shutdown_ok() {
    let mut proc = WorkerProcess::spawn();

    let req = r#"{"protocolVersion":1,"requestId":"s1","type":"shutdown"}"#;
    eprintln!("[test] >> {req}");
    proc.send(req);
    proc.assert_response_type("ok", "s1");

    let (code, stderr) = proc.wait_for_exit();
    assert_eq!(code, 0, "worker should exit 0 after shutdown");
    eprintln!("[test] stderr:\n{stderr}");
}

#[test]
fn malformed_json_recovers() {
    let mut proc = WorkerProcess::spawn();

    // First send garbage.
    proc.send("this is not json at all !!!");
    proc.assert_error("", "MALFORMED_REQUEST");

    // Worker should still be alive — send a valid hello.
    send_hello(&mut proc, "m1");

    // Then shutdown cleanly.
    let req = r#"{"protocolVersion":1,"requestId":"m2","type":"shutdown"}"#;
    eprintln!("[test] >> {req}");
    proc.send(req);
    proc.assert_response_type("ok", "m2");

    let (code, stderr) = proc.wait_for_exit();
    assert_eq!(code, 0, "worker should exit 0 after malformed recovery");
    eprintln!("[test] stderr:\n{stderr}");
}

#[test]
fn malformed_partial_json_recovers() {
    let mut proc = WorkerProcess::spawn();

    // Partial JSON (truncated object).
    proc.send(r#"{"protocolVersion":1,"requestId""#);
    proc.assert_error("", "MALFORMED_REQUEST");

    // Valid hello.
    send_hello(&mut proc, "pj1");

    // Shutdown.
    let req = r#"{"protocolVersion":1,"requestId":"pj2","type":"shutdown"}"#;
    eprintln!("[test] >> {req}");
    proc.send(req);
    proc.assert_response_type("ok", "pj2");

    let (code, stderr) = proc.wait_for_exit();
    assert_eq!(code, 0);
    eprintln!("[test] stderr:\n{stderr}");
}

#[test]
fn protocol_version_mismatch_returns_error() {
    let mut proc = WorkerProcess::spawn();

    let req = r#"{"protocolVersion":99,"requestId":"v1","type":"hello"}"#;
    eprintln!("[test] >> {req}");
    proc.send(req);
    proc.assert_error("v1", "PROTOCOL_VERSION_MISMATCH");

    // After mismatched version, a correct hello should still work.
    send_hello(&mut proc, "v2");

    let req = r#"{"protocolVersion":1,"requestId":"v3","type":"shutdown"}"#;
    eprintln!("[test] >> {req}");
    proc.send(req);
    proc.assert_response_type("ok", "v3");

    let (code, stderr) = proc.wait_for_exit();
    assert_eq!(code, 0);
    eprintln!("[test] stderr:\n{stderr}");
}

#[test]
fn embed_without_model_returns_error() {
    let mut proc = WorkerProcess::spawn();

    send_hello(&mut proc, "e1");

    let req =
        r#"{"protocolVersion":1,"requestId":"e2","type":"embed","mode":"query","inputs":["test"]}"#;
    eprintln!("[test] >> {req}");
    proc.send(req);
    proc.assert_error("e2", "MODEL_NOT_LOADED");

    let req = r#"{"protocolVersion":1,"requestId":"e3","type":"shutdown"}"#;
    eprintln!("[test] >> {req}");
    proc.send(req);
    proc.assert_response_type("ok", "e3");

    let (code, stderr) = proc.wait_for_exit();
    assert_eq!(code, 0);
    eprintln!("[test] stderr:\n{stderr}");
}

#[test]
fn unload_without_model_is_ok() {
    let mut proc = WorkerProcess::spawn();

    send_hello(&mut proc, "u1");

    // Unload when no model is loaded should succeed.
    let req = r#"{"protocolVersion":1,"requestId":"u2","type":"unload"}"#;
    eprintln!("[test] >> {req}");
    proc.send(req);
    proc.assert_response_type("ok", "u2");

    let req = r#"{"protocolVersion":1,"requestId":"u3","type":"shutdown"}"#;
    eprintln!("[test] >> {req}");
    proc.send(req);
    proc.assert_response_type("ok", "u3");

    let (code, stderr) = proc.wait_for_exit();
    assert_eq!(code, 0);
    eprintln!("[test] stderr:\n{stderr}");
}

#[test]
fn request_too_large_exceeds_input_count() {
    let mut proc = WorkerProcess::spawn();

    let dir = require_fixture();
    send_hello(&mut proc, "l1");
    send_load_model(&mut proc, "l2", &dir);

    // Send 101 inputs — exceeds MAX_EMBED_INPUTS (100).
    let inputs: Vec<String> = (0..101).map(|i| format!("input_{i}")).collect();
    let inputs_json = serde_json::to_string(&inputs).unwrap();
    let req = format!(
        r#"{{"protocolVersion":1,"requestId":"l3","type":"embed","mode":"query","inputs":{inputs_json}}}"#
    );
    eprintln!("[test] >> embed with 101 inputs");
    proc.send(&req);
    proc.assert_error("l3", "REQUEST_TOO_LARGE");

    let req = r#"{"protocolVersion":1,"requestId":"l4","type":"shutdown"}"#;
    eprintln!("[test] >> {req}");
    proc.send(req);
    proc.assert_response_type("ok", "l4");

    let (code, stderr) = proc.wait_for_exit();
    assert_eq!(code, 0);
    eprintln!("[test] stderr:\n{stderr}");
}

#[test]
fn request_too_large_exceeds_total_chars() {
    let mut proc = WorkerProcess::spawn();

    let dir = require_fixture();
    send_hello(&mut proc, "t1");
    send_load_model(&mut proc, "t2", &dir);

    // Each input is exactly MAX_INPUT_LENGTH (10k chars) so per-input check passes.
    // 11 inputs × 10k = 110k chars > MAX_TOTAL_INPUT_LENGTH (100k), triggers total-limit check.
    let input: String = "x".repeat(10_000);
    let inputs = vec![input.as_str(); 11];
    let inputs_json = serde_json::to_string(&inputs).unwrap();
    let req = format!(
        r#"{{"protocolVersion":1,"requestId":"t3","type":"embed","mode":"query","inputs":{inputs_json}}}"#
    );
    eprintln!("[test] >> embed with 11 inputs of 10k each = 110k total");
    proc.send(&req);
    proc.assert_error("t3", "REQUEST_TOO_LARGE");

    let req = r#"{"protocolVersion":1,"requestId":"t4","type":"shutdown"}"#;
    eprintln!("[test] >> {req}");
    proc.send(req);
    proc.assert_response_type("ok", "t4");

    let (code, stderr) = proc.wait_for_exit();
    assert_eq!(code, 0);
    eprintln!("[test] stderr:\n{stderr}");
}

#[test]
fn request_too_large_exceeds_single_input() {
    let mut proc = WorkerProcess::spawn();

    let dir = require_fixture();
    send_hello(&mut proc, "s1");
    send_load_model(&mut proc, "s2", &dir);

    // Single input > MAX_INPUT_LENGTH (10k).
    let big = "y".repeat(11_000);
    let inputs = vec![big.as_str()];
    let inputs_json = serde_json::to_string(&inputs).unwrap();
    let req = format!(
        r#"{{"protocolVersion":1,"requestId":"s3","type":"embed","mode":"query","inputs":{inputs_json}}}"#
    );
    eprintln!("[test] >> embed with 11k single input");
    proc.send(&req);
    proc.assert_error("s3", "REQUEST_TOO_LARGE");

    let req = r#"{"protocolVersion":1,"requestId":"s4","type":"shutdown"}"#;
    eprintln!("[test] >> {req}");
    proc.send(req);
    proc.assert_response_type("ok", "s4");

    let (code, stderr) = proc.wait_for_exit();
    assert_eq!(code, 0);
    eprintln!("[test] stderr:\n{stderr}");
}

#[test]
fn multiple_errors_then_valid() {
    let mut proc = WorkerProcess::spawn();

    // Malformed.
    proc.send("{{{");
    proc.assert_error("", "MALFORMED_REQUEST");

    // Version mismatch.
    let req = r#"{"protocolVersion":0,"requestId":"me1","type":"hello"}"#;
    eprintln!("[test] >> {req}");
    proc.send(req);
    proc.assert_error("me1", "PROTOCOL_VERSION_MISMATCH");

    // Valid hello.
    send_hello(&mut proc, "me2");

    let req = r#"{"protocolVersion":1,"requestId":"me3","type":"shutdown"}"#;
    eprintln!("[test] >> {req}");
    proc.send(req);
    proc.assert_response_type("ok", "me3");

    let (code, stderr) = proc.wait_for_exit();
    assert_eq!(code, 0);
    eprintln!("[test] stderr:\n{stderr}");
}

// ===========================================================================
// Tests – Full protocol with model fixture
// ===========================================================================

#[test]
fn full_protocol_session() {
    let dir = require_fixture();
    let mut proc = WorkerProcess::spawn();

    // 1. hello
    send_hello(&mut proc, "f1");

    // 2. load_model
    send_load_model(&mut proc, "f2", &dir);

    // 3. embed query
    let req = r#"{"protocolVersion":1,"requestId":"f3","type":"embed","mode":"query","inputs":["什么是机器学习？"]}"#;
    eprintln!("[test] >> {req}");
    proc.send(req);
    let v = proc.recv_json();
    assert_eq!(v["type"], "embed_result");
    assert_eq!(v["requestId"], "f3");
    let embeddings = v["embeddings"].as_array().expect("embeddings array");
    assert_eq!(embeddings.len(), 1, "should have 1 embedding vector");
    let vector = embeddings[0].as_array().expect("vector array");
    assert_eq!(vector.len(), 512, "expected 512 dimensions");
    assert!(
        vector[0].as_f64().unwrap().is_finite(),
        "first value must be finite"
    );

    // 4. embed document
    let req = r#"{"protocolVersion":1,"requestId":"f4","type":"embed","mode":"document","inputs":["机器学习是人工智能的一个分支。"]}"#;
    eprintln!("[test] >> {req}");
    proc.send(req);
    let v = proc.recv_json();
    assert_eq!(v["type"], "embed_result");
    assert_eq!(v["requestId"], "f4");
    let doc_vec = v["embeddings"][0].as_array().expect("doc vector array");
    assert_eq!(doc_vec.len(), 512);

    // 5. unload
    let req = r#"{"protocolVersion":1,"requestId":"f5","type":"unload"}"#;
    eprintln!("[test] >> {req}");
    proc.send(req);
    proc.assert_response_type("ok", "f5");

    // 6. shutdown
    let req = r#"{"protocolVersion":1,"requestId":"f6","type":"shutdown"}"#;
    eprintln!("[test] >> {req}");
    proc.send(req);
    proc.assert_response_type("ok", "f6");

    let (code, stderr) = proc.wait_for_exit();
    assert_eq!(code, 0, "worker should exit 0");
    eprintln!("[test] stderr:\n{stderr}");
}

#[test]
fn multiple_embed_batch() {
    let dir = require_fixture();
    let mut proc = WorkerProcess::spawn();

    send_hello(&mut proc, "b1");
    send_load_model(&mut proc, "b2", &dir);

    // Batch embed 3 inputs.
    let req = r#"{"protocolVersion":1,"requestId":"b3","type":"embed","mode":"document","inputs":["first document","second document","third document"]}"#;
    eprintln!("[test] >> {req}");
    proc.send(req);
    let v = proc.recv_json();
    assert_eq!(v["type"], "embed_result");
    assert_eq!(v["embeddings"].as_array().unwrap().len(), 3);
    for (i, emb) in v["embeddings"].as_array().unwrap().iter().enumerate() {
        let vec = emb.as_array().unwrap();
        assert_eq!(vec.len(), 512, "embedding {i} should have 512 dims");
    }

    let req = r#"{"protocolVersion":1,"requestId":"b4","type":"shutdown"}"#;
    eprintln!("[test] >> {req}");
    proc.send(req);
    proc.assert_response_type("ok", "b4");

    let (code, stderr) = proc.wait_for_exit();
    assert_eq!(code, 0);
    eprintln!("[test] stderr:\n{stderr}");
}

#[test]
fn load_model_twice_replaces() {
    let dir = require_fixture();
    let mut proc = WorkerProcess::spawn();

    send_hello(&mut proc, "r1");
    send_load_model(&mut proc, "r2", &dir);

    // Load again (same model).
    send_load_model(&mut proc, "r3", &dir);

    // Embed should still work with the second instance.
    let req =
        r#"{"protocolVersion":1,"requestId":"r4","type":"embed","mode":"query","inputs":["test"]}"#;
    eprintln!("[test] >> {req}");
    proc.send(req);
    let v = proc.recv_json();
    assert_eq!(v["type"], "embed_result");

    let req = r#"{"protocolVersion":1,"requestId":"r5","type":"shutdown"}"#;
    eprintln!("[test] >> {req}");
    proc.send(req);
    proc.assert_response_type("ok", "r5");

    let (code, stderr) = proc.wait_for_exit();
    assert_eq!(code, 0);
    eprintln!("[test] stderr:\n{stderr}");
}

#[test]
fn unknown_request_type_returns_error() {
    let mut proc = WorkerProcess::spawn();

    let req = r#"{"protocolVersion":1,"requestId":"x1","type":"unknown"}"#;
    eprintln!("[test] >> {req}");
    proc.send(req);
    proc.assert_error("", "MALFORMED_REQUEST");

    // Worker still alive.
    send_hello(&mut proc, "x2");

    let req = r#"{"protocolVersion":1,"requestId":"x3","type":"shutdown"}"#;
    eprintln!("[test] >> {req}");
    proc.send(req);
    proc.assert_response_type("ok", "x3");

    let (code, stderr) = proc.wait_for_exit();
    assert_eq!(code, 0);
    eprintln!("[test] stderr:\n{stderr}");
}

#[test]
fn load_model_blank_model_id_returns_error() {
    let mut proc = WorkerProcess::spawn();
    send_hello(&mut proc, "i1");

    let req = serde_json::json!({
        "protocolVersion": 1,
        "requestId": "i2",
        "type": "load_model",
        "modelId": "   ",
        "modelDir": "dummy",
        "requiredFiles": ["model.onnx"],
        "dimensions": 512,
    });
    let req_str = req.to_string();
    eprintln!("[test] >> {req_str}");
    proc.send(&req_str);
    proc.assert_error("i2", "MALFORMED_REQUEST");

    let req = r#"{"protocolVersion":1,"requestId":"i3","type":"shutdown"}"#;
    eprintln!("[test] >> {req}");
    proc.send(req);
    proc.assert_response_type("ok", "i3");
    let (code, stderr) = proc.wait_for_exit();
    assert_eq!(code, 0);
    eprintln!("[test] stderr:\n{stderr}");
}

#[test]
fn chinese_char_count_uses_scalar_chars_not_bytes() {
    // "你好世界" = 4 Chinese characters (12 UTF-8 bytes, but only 4 scalar chars).
    // With MAX_INPUT_LENGTH = 10_000, this easily passes.
    // Same for total — we just verify the request succeeds (not "too large").
    let mut proc = WorkerProcess::spawn();

    let dir = require_fixture();
    send_hello(&mut proc, "c1");
    send_load_model(&mut proc, "c2", &dir);

    let req = r#"{"protocolVersion":1,"requestId":"c3","type":"embed","mode":"query","inputs":["你好世界"]}"#;
    eprintln!("[test] >> {req}");
    proc.send(req);
    let v = proc.recv_json();
    assert_eq!(
        v["type"], "embed_result",
        "Chinese chars should be accepted"
    );

    let req = r#"{"protocolVersion":1,"requestId":"c4","type":"shutdown"}"#;
    eprintln!("[test] >> {req}");
    proc.send(req);
    proc.assert_response_type("ok", "c4");
    let (code, stderr) = proc.wait_for_exit();
    assert_eq!(code, 0);
    eprintln!("[test] stderr:\n{stderr}");
}

#[test]
fn load_model_wrong_dimensions_returns_error() {
    let dir = require_fixture();
    let mut proc = WorkerProcess::spawn();

    send_hello(&mut proc, "d1");

    // Wrong dimensions (384 instead of 512).
    let req = serde_json::json!({
        "protocolVersion": 1,
        "requestId": "d2",
        "type": "load_model",
        "modelId": "bge-small-zh-v1.5",
        "modelDir": dir,
        "requiredFiles": ["model_optimized.onnx"],
        "dimensions": 384,
    });
    let req_str = req.to_string();
    eprintln!("[test] >> {req_str}");
    proc.send(&req_str);
    proc.assert_error("d2", "DIMENSION_MISMATCH");

    let req = r#"{"protocolVersion":1,"requestId":"d3","type":"shutdown"}"#;
    eprintln!("[test] >> {req}");
    proc.send(req);
    proc.assert_response_type("ok", "d3");

    let (code, stderr) = proc.wait_for_exit();
    assert_eq!(code, 0);
    eprintln!("[test] stderr:\n{stderr}");
}
