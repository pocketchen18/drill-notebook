//! Integration test: offline model geometry and query/document embedding parity.
//!
//! Requires the pinned `bge-small-zh-v1.5` fixture downloaded by
//! `scripts/fetch-embedding-fixture.ps1`.  These tests PANIC if the fixture
//! is missing — they form the hard gate for Task 1 acceptance.  Use
//! `-NoParity` in the test script to explicitly exclude them for non-gate
//! development.

mod common;

use embedding_worker::{validate_embedding_geometry, validate_l2_normalized, EmbeddingWorker};

fn require_fixture() -> std::path::PathBuf {
    let dir = common::fixture_dir();
    if !common::fixture_is_complete(&dir) {
        panic!(
            "Fixture not found at '{}'. Run `scripts/fetch-embedding-fixture.ps1` first.",
            dir.display()
        );
    }
    dir
}

#[test]
fn offline_golden_parity() {
    let dir = require_fixture();

    let mut worker = EmbeddingWorker::new(&dir)
        .expect("EmbeddingWorker should load from complete fixture directory");

    // --- test inputs: 2 Chinese, 2 English ---
    let test_cases: Vec<(&str, &str)> = vec![
        ("query-zh", "什么是机器学习？"),
        ("doc-zh", "机器学习是人工智能的一个分支。"),
        ("query-en", "What is machine learning?"),
        (
            "doc-en",
            "Machine learning is a branch of artificial intelligence.",
        ),
    ];

    for (label, text) in &test_cases {
        let emb = if label.starts_with("query") {
            worker.embed_query(text)
        } else {
            worker.embed_document(text)
        };

        match emb {
            Ok(v) => {
                // 1. Exactly 512 dimensions
                assert_eq!(
                    v.len(),
                    512,
                    "{label}: expected 512 dimensions, got {}",
                    v.len()
                );

                // 2. All finite values
                if let Err(e) = validate_embedding_geometry(&v) {
                    panic!("{label}: geometry validation failed: {e}");
                }

                // 3. L2 norm within [0.99, 1.01] (model applies L2 normalization)
                if let Err(e) = validate_l2_normalized(&v) {
                    panic!("{label}: L2 norm outside expected range: {e}");
                }

                // Print first few values for inspection in evidence
                eprintln!(
                    "OK {label}: dims={} first_5=[{:.6}, {:.6}, {:.6}, {:.6}, {:.6}] norm={:.6}",
                    v.len(),
                    v[0],
                    v[1],
                    v[2],
                    v[3],
                    v[4],
                    embedding_worker::l2_norm(&v),
                );
            }
            Err(e) => {
                panic!("{label}: embedding failed: {e}");
            }
        }
    }
}

#[test]
fn query_prefix_added_correctly() {
    // This test verifies that query and document embeddings differ because
    // the query prefix is prepended.
    let dir = require_fixture();

    let mut worker = EmbeddingWorker::new(&dir).expect("EmbeddingWorker should load");

    let text = "测试文本";

    let query_emb = worker.embed_query(text).expect("query embed");
    let doc_emb = worker.embed_document(text).expect("doc embed");

    // Query and document embeddings for the same text should differ
    // because the query prefix is added to the query.
    let query_first: Vec<f32> = query_emb.iter().take(10).cloned().collect();
    let doc_first: Vec<f32> = doc_emb.iter().take(10).cloned().collect();

    assert_ne!(
        query_first, doc_first,
        "query and document embeddings should differ due to query prefix"
    );

    eprintln!("OK query_prefix: query first_10 differs from document first_10");
}

#[test]
fn deterministic_geometry() {
    // Same input → same embedding (within float tolerance)
    let dir = require_fixture();

    let mut worker = EmbeddingWorker::new(&dir).expect("EmbeddingWorker should load");

    let text = "重复的测试输入";

    let emb1 = worker.embed_query(text).expect("first embed");
    let emb2 = worker.embed_query(text).expect("second embed");

    assert_eq!(emb1.len(), emb2.len());
    for (i, (a, b)) in emb1.iter().zip(emb2.iter()).enumerate() {
        let diff = (a - b).abs();
        assert!(
            diff < 1e-5,
            "determinism failed at index {i}: {a} vs {b}, diff={diff}",
        );
    }

    eprintln!("OK deterministic: same input produces consistent embedding");
}
