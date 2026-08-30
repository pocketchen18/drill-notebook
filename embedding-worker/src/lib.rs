//! Embedding worker library for Drill Notebook RAG.
//!
//! Loads a pinned `bge-small-zh-v1.5` ONNX model via fastembed's
//! [`UserDefinedEmbeddingModel`] — no implicit downloads, no HF cache,
//! no built-in `EmbeddingModel` enum.
//!
//! # Canonical Prefixes
//!
//! - **Query**:  `为这个句子生成表示以用于检索相关文章：`
//! - **Document**: empty prefix (no modification)

use std::path::Path;

use fastembed::{
    InitOptionsUserDefined, Pooling, QuantizationMode, TextEmbedding, TokenizerFiles,
    UserDefinedEmbeddingModel,
};

pub use fastembed::Embedding;

// ---------------------------------------------------------------------------
// Errors
// ---------------------------------------------------------------------------

/// Typed errors the embedding worker can produce.
#[derive(Debug)]
pub enum EmbeddingError {
    /// One or more required model files are missing from the expected directory.
    ModelFilesMissing(String),
    /// The embedding model could not be loaded (invalid ONNX, bad tokenizer, …).
    ModelLoadFailed(String),
    /// The embedding call itself failed.
    EmbeddingFailed(String),
    /// I/O error while reading model files.
    IoError(std::io::Error),
}

impl std::fmt::Display for EmbeddingError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::ModelFilesMissing(detail) => {
                write!(f, "MODEL_FILES_MISSING: {detail}")
            }
            Self::ModelLoadFailed(detail) => {
                write!(f, "MODEL_LOAD_FAILED: {detail}")
            }
            Self::EmbeddingFailed(detail) => {
                write!(f, "EMBEDDING_FAILED: {detail}")
            }
            Self::IoError(inner) => write!(f, "IO_ERROR: {inner}"),
        }
    }
}

impl std::error::Error for EmbeddingError {
    fn source(&self) -> Option<&(dyn std::error::Error + 'static)> {
        match self {
            Self::IoError(inner) => Some(inner),
            _ => None,
        }
    }
}

impl From<std::io::Error> for EmbeddingError {
    fn from(e: std::io::Error) -> Self {
        Self::IoError(e)
    }
}

// ---------------------------------------------------------------------------
// Constants
// ---------------------------------------------------------------------------

/// Query prefix mandated by the embedding-space canonical contract.
pub const QUERY_PREFIX: &str = "为这个句子生成表示以用于检索相关文章：";

/// Number of dimensions expected from `bge-small-zh-v1.5`.
pub const EXPECTED_DIMENSIONS: usize = 512;

/// Names of the five runtime-required model files.
pub const REQUIRED_RUNTIME_FILES: &[&str] = &[
    "model_optimized.onnx",
    "tokenizer.json",
    "config.json",
    "tokenizer_config.json",
    "special_tokens_map.json",
];

// ---------------------------------------------------------------------------
// Worker
// ---------------------------------------------------------------------------

/// An offline embedding worker that uses a pinned local model.
pub struct EmbeddingWorker {
    #[allow(dead_code)]
    model: TextEmbedding,
}

impl std::fmt::Debug for EmbeddingWorker {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("EmbeddingWorker").finish_non_exhaustive()
    }
}

impl EmbeddingWorker {
    /// Create a new `EmbeddingWorker` by loading the model from `model_dir`.
    ///
    /// `model_dir` must contain the five required files listed in
    /// [`REQUIRED_RUNTIME_FILES`].  Returns [`EmbeddingError::ModelFilesMissing`]
    /// if any file is absent; never initiates network I/O.
    pub fn new(model_dir: &Path) -> Result<Self, EmbeddingError> {
        // --- validate all files exist first ---
        let missing: Vec<String> = REQUIRED_RUNTIME_FILES
            .iter()
            .filter(|f| !model_dir.join(f).exists())
            .map(|f| f.to_string())
            .collect();

        if !missing.is_empty() {
            return Err(EmbeddingError::ModelFilesMissing(format!(
                "required files missing in '{}': {}",
                model_dir.display(),
                missing.join(", ")
            )));
        }

        // --- read files ---
        let onnx_file = std::fs::read(model_dir.join("model_optimized.onnx"))?;
        let tokenizer_file = std::fs::read(model_dir.join("tokenizer.json"))?;
        let config_file = std::fs::read(model_dir.join("config.json"))?;
        let special_tokens_map_file = std::fs::read(model_dir.join("special_tokens_map.json"))?;
        let tokenizer_config_file = std::fs::read(model_dir.join("tokenizer_config.json"))?;

        let user_model = UserDefinedEmbeddingModel::new(
            onnx_file,
            TokenizerFiles {
                tokenizer_file,
                config_file,
                special_tokens_map_file,
                tokenizer_config_file,
            },
        )
        .with_pooling(Pooling::Cls)
        .with_quantization(QuantizationMode::None);

        let options = InitOptionsUserDefined::new().with_max_length(512);

        let model = TextEmbedding::try_new_from_user_defined(user_model, options)
            .map_err(|e| EmbeddingError::ModelLoadFailed(e.to_string()))?;

        Ok(Self { model })
    }

    /// Embed a single **query** string.
    ///
    /// Prepends the canonical query prefix automatically.
    pub fn embed_query(&mut self, text: &str) -> Result<Embedding, EmbeddingError> {
        let prefixed = format!("{QUERY_PREFIX}{text}");
        self._embed_single(&prefixed)
    }

    /// Embed a single **document** string (no prefix added).
    pub fn embed_document(&mut self, text: &str) -> Result<Embedding, EmbeddingError> {
        self._embed_single(text)
    }

    // --- internal helpers ---

    fn _embed_single(&mut self, text: &str) -> Result<Embedding, EmbeddingError> {
        // Use a one-element batch and extract the first output.
        let mut results = self
            .model
            .embed(vec![text], Some(1))
            .map_err(|e| EmbeddingError::EmbeddingFailed(e.to_string()))?;

        results
            .pop()
            .ok_or_else(|| EmbeddingError::EmbeddingFailed("model returned zero embeddings".into()))
    }
}

// ---------------------------------------------------------------------------
// Utility: geometry checks
// ---------------------------------------------------------------------------

/// Check that `emb` has exactly `EXPECTED_DIMENSIONS` finite values.
pub fn validate_embedding_geometry(emb: &Embedding) -> Result<(), String> {
    if emb.len() != EXPECTED_DIMENSIONS {
        return Err(format!(
            "expected {EXPECTED_DIMENSIONS} dimensions, got {}",
            emb.len()
        ));
    }

    for (i, &v) in emb.iter().enumerate() {
        if !v.is_finite() {
            return Err(format!(
                "non-finite value at index {i}: {v} (is_nan={}, is_infinite={})",
                v.is_nan(),
                v.is_infinite()
            ));
        }
    }

    Ok(())
}

/// Compute the L2 norm of a vector.
pub fn l2_norm(emb: &Embedding) -> f64 {
    let sum_sq: f64 = emb.iter().map(|v| (*v as f64) * (*v as f64)).sum();
    sum_sq.sqrt()
}

/// Check that `emb`'s L2 norm is within [0.99, 1.01].
pub fn validate_l2_normalized(emb: &Embedding) -> Result<(), String> {
    let norm = l2_norm(emb);
    if !(0.99..=1.01).contains(&norm) {
        return Err(format!(
            "L2 norm {norm:.6} is outside expected range [0.99, 1.01]"
        ));
    }
    Ok(())
}

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn query_prefix_is_correct() {
        assert_eq!(QUERY_PREFIX, "为这个句子生成表示以用于检索相关文章：");
    }

    #[test]
    fn expected_dimensions_constant() {
        assert_eq!(EXPECTED_DIMENSIONS, 512);
    }

    #[test]
    fn required_runtime_files_list() {
        let list = REQUIRED_RUNTIME_FILES;
        assert!(list.contains(&"model_optimized.onnx"));
        assert!(list.contains(&"tokenizer.json"));
        assert!(list.contains(&"config.json"));
        assert!(list.contains(&"tokenizer_config.json"));
        assert!(list.contains(&"special_tokens_map.json"));
        assert_eq!(list.len(), 5);
    }

    #[test]
    fn model_files_missing_on_empty_dir() {
        let tmp = tempfile::TempDir::new().expect("create temp dir");
        let err = EmbeddingWorker::new(tmp.path()).unwrap_err();

        let msg = err.to_string();
        assert!(
            msg.starts_with("MODEL_FILES_MISSING:"),
            "expected MODEL_FILES_MISSING error, got: {msg}"
        );
        // Should mention all five missing files
        for f in REQUIRED_RUNTIME_FILES {
            assert!(msg.contains(f), "error missing mention of {f}: {msg}");
        }
    }

    #[test]
    fn model_files_missing_on_nonexistent_dir() {
        let err =
            EmbeddingWorker::new(Path::new("C:\\does_not_exist_0xDEAD_BEEF\\models")).unwrap_err();
        let msg = err.to_string();
        assert!(
            msg.starts_with("MODEL_FILES_MISSING:"),
            "expected MODEL_FILES_MISSING error, got: {msg}"
        );
    }

    #[test]
    fn geometry_validation_ok() {
        // A valid normalized 512-dim vector
        let val = 1.0 / (512.0_f64).sqrt();
        let emb: Embedding = (0..512).map(|_| val as f32).collect();
        assert!(validate_embedding_geometry(&emb).is_ok());
        assert!(validate_l2_normalized(&emb).is_ok());
    }

    #[test]
    fn geometry_validation_wrong_dims() {
        let emb: Embedding = vec![0.0; 128];
        assert!(validate_embedding_geometry(&emb).is_err());
    }

    #[test]
    fn geometry_validation_nan() {
        let mut emb: Embedding = vec![0.0; 512];
        emb[7] = f32::NAN;
        assert!(validate_embedding_geometry(&emb).is_err());
    }

    #[test]
    fn geometry_validation_inf() {
        let mut emb: Embedding = vec![0.0; 512];
        emb[42] = f32::INFINITY;
        assert!(validate_embedding_geometry(&emb).is_err());
    }

    #[test]
    fn l2_norm_works() {
        let emb: Embedding = vec![1.0, 0.0, 0.0, 0.0];
        let norm = l2_norm(&emb);
        assert!((norm - 1.0).abs() < 1e-12);
    }

    #[test]
    fn l2_norm_out_of_range() {
        let emb: Embedding = vec![100.0; 512];
        assert!(validate_l2_normalized(&emb).is_err());
    }
}