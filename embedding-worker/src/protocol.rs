//! Versioned NDJSON protocol types for the embedding worker.
//!
//! # Framing
//!
//! Every line on stdin is a JSON object (NDJSON).  Each request contains
//! `protocolVersion`, `requestId` and a `type` tag.  Responses use the same
//! framing and tag style.
//!
//! # Current version
//!
//! `PROTOCOL_VERSION = 1`
//!
//! # Limits
//!
//! All limits are measured in **Unicode scalar characters** (Rust
//! [`str::chars().count()`]), not UTF‑8 bytes or UTF‑16 code units.
//!
//! - `MAX_EMBED_INPUTS`: maximum number of strings per embed request (100)
//! - `MAX_INPUT_LENGTH`: maximum Unicode scalar characters per single input (10 000)
//! - `MAX_TOTAL_INPUT_LENGTH`: sum of all input Unicode scalar characters (100 000)

use serde::{Deserialize, Serialize};

// ---------------------------------------------------------------------------
// Version & limits
// ---------------------------------------------------------------------------

pub const PROTOCOL_VERSION: u32 = 1;
pub const MAX_EMBED_INPUTS: usize = 100;
pub const MAX_INPUT_LENGTH: usize = 10_000;
pub const MAX_TOTAL_INPUT_LENGTH: usize = 100_000;

// ---------------------------------------------------------------------------
// Error codes
// ---------------------------------------------------------------------------

/// Structured error codes returned in error frames.
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub enum ErrorCode {
    #[serde(rename = "MALFORMED_REQUEST")]
    MalformedRequest,
    #[serde(rename = "PROTOCOL_VERSION_MISMATCH")]
    ProtocolVersionMismatch,
    #[serde(rename = "MODEL_FILES_MISSING")]
    ModelFilesMissing,
    #[serde(rename = "MODEL_LOAD_FAILED")]
    ModelLoadFailed,
    #[serde(rename = "MODEL_NOT_LOADED")]
    ModelNotLoaded,
    #[serde(rename = "DIMENSION_MISMATCH")]
    DimensionMismatch,
    #[serde(rename = "EMBEDDING_FAILED")]
    EmbeddingFailed,
    #[serde(rename = "REQUEST_TOO_LARGE")]
    RequestTooLarge,
    #[serde(rename = "INTERNAL_ERROR")]
    InternalError,
}

// ---------------------------------------------------------------------------
// Embed mode
// ---------------------------------------------------------------------------

/// Whether the embed request is for a query (prefix added) or document (no prefix).
#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "snake_case")]
pub enum EmbedMode {
    Query,
    Document,
}

// ---------------------------------------------------------------------------
// Request
// ---------------------------------------------------------------------------

/// Every request on stdin is one of these variants, tagged by `type`.
#[derive(Debug, Deserialize)]
#[serde(tag = "type")]
pub enum Request {
    #[serde(rename = "hello")]
    Hello {
        #[serde(rename = "protocolVersion")]
        protocol_version: u32,
        #[serde(rename = "requestId")]
        request_id: String,
    },
    #[serde(rename = "load_model")]
    LoadModel {
        #[serde(rename = "protocolVersion")]
        protocol_version: u32,
        #[serde(rename = "requestId")]
        request_id: String,
        #[serde(rename = "modelId")]
        model_id: String,
        #[serde(rename = "modelDir")]
        model_dir: String,
        #[serde(rename = "requiredFiles")]
        required_files: Vec<String>,
        dimensions: usize,
    },
    #[serde(rename = "embed")]
    Embed {
        #[serde(rename = "protocolVersion")]
        protocol_version: u32,
        #[serde(rename = "requestId")]
        request_id: String,
        mode: EmbedMode,
        inputs: Vec<String>,
    },
    #[serde(rename = "unload")]
    Unload {
        #[serde(rename = "protocolVersion")]
        protocol_version: u32,
        #[serde(rename = "requestId")]
        request_id: String,
    },
    #[serde(rename = "shutdown")]
    Shutdown {
        #[serde(rename = "protocolVersion")]
        protocol_version: u32,
        #[serde(rename = "requestId")]
        request_id: String,
    },
}

impl Request {
    pub fn protocol_version(&self) -> u32 {
        match self {
            Self::Hello {
                protocol_version, ..
            }
            | Self::LoadModel {
                protocol_version, ..
            }
            | Self::Embed {
                protocol_version, ..
            }
            | Self::Unload {
                protocol_version, ..
            }
            | Self::Shutdown {
                protocol_version, ..
            } => *protocol_version,
        }
    }

    pub fn request_id(&self) -> &str {
        match self {
            Self::Hello { request_id, .. }
            | Self::LoadModel { request_id, .. }
            | Self::Embed { request_id, .. }
            | Self::Unload { request_id, .. }
            | Self::Shutdown { request_id, .. } => request_id,
        }
    }
}

// ---------------------------------------------------------------------------
// Response
// ---------------------------------------------------------------------------

/// Every response on stdout is one of these variants, tagged by `type`.
#[derive(Debug, Serialize)]
#[serde(tag = "type")]
pub enum Response {
    #[serde(rename = "ready")]
    Ready {
        #[serde(rename = "protocolVersion")]
        protocol_version: u32,
        #[serde(rename = "requestId")]
        request_id: String,
    },
    #[serde(rename = "model_loaded")]
    ModelLoaded {
        #[serde(rename = "protocolVersion")]
        protocol_version: u32,
        #[serde(rename = "requestId")]
        request_id: String,
        dimensions: usize,
    },
    #[serde(rename = "embed_result")]
    EmbedResult {
        #[serde(rename = "protocolVersion")]
        protocol_version: u32,
        #[serde(rename = "requestId")]
        request_id: String,
        embeddings: Vec<Vec<f32>>,
    },
    #[serde(rename = "ok")]
    Ok {
        #[serde(rename = "protocolVersion")]
        protocol_version: u32,
        #[serde(rename = "requestId")]
        request_id: String,
    },
    #[serde(rename = "error")]
    Error {
        #[serde(rename = "protocolVersion")]
        protocol_version: u32,
        #[serde(rename = "requestId")]
        request_id: String,
        code: ErrorCode,
        message: String,
        retryable: bool,
    },
}

impl Response {
    /// Convenience constructor for an error response.
    pub fn error(request_id: &str, code: ErrorCode, message: String, retryable: bool) -> Self {
        Self::Error {
            protocol_version: PROTOCOL_VERSION,
            request_id: request_id.to_string(),
            code,
            message,
            retryable,
        }
    }
}

/// Serialise `response` and write it as a single line to `writer`.
///
/// Panics only on programmer error (e.g. poisoned stdout) — never on protocol
/// logic.
pub fn write_response(writer: &mut impl std::io::Write, response: &Response) {
    let json = serde_json::to_string(response).expect("Response must serialize to JSON");
    writeln!(writer, "{json}").expect("write to stdout");
}

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

#[cfg(test)]
mod tests {
    use super::*;

    // --- Request round-trips ---

    #[test]
    fn parse_hello() {
        let json = r#"{"protocolVersion":1,"requestId":"r1","type":"hello"}"#;
        let req: Request = serde_json::from_str(json).unwrap();
        assert_eq!(req.protocol_version(), 1);
        assert_eq!(req.request_id(), "r1");
        assert!(matches!(req, Request::Hello { .. }));
    }

    #[test]
    fn parse_load_model() {
        let json = r#"{"protocolVersion":1,"requestId":"r2","type":"load_model","modelId":"bge-small-zh-v1.5","modelDir":"/models","requiredFiles":["model.onnx"],"dimensions":512}"#;
        let req: Request = serde_json::from_str(json).unwrap();
        assert_eq!(req.request_id(), "r2");
        match req {
            Request::LoadModel {
                model_id,
                model_dir,
                required_files,
                dimensions,
                ..
            } => {
                assert_eq!(model_id, "bge-small-zh-v1.5");
                assert_eq!(model_dir, "/models");
                assert_eq!(required_files, vec!["model.onnx".to_string()]);
                assert_eq!(dimensions, 512);
            }
            _ => panic!("expected LoadModel"),
        }
    }

    #[test]
    fn parse_embed_query() {
        let json = r#"{"protocolVersion":1,"requestId":"r3","type":"embed","mode":"query","inputs":["hello","world"]}"#;
        let req: Request = serde_json::from_str(json).unwrap();
        match req {
            Request::Embed { mode, inputs, .. } => {
                assert_eq!(mode, EmbedMode::Query);
                assert_eq!(inputs, vec!["hello".to_string(), "world".to_string()]);
            }
            _ => panic!("expected Embed"),
        }
    }

    #[test]
    fn parse_embed_document() {
        let json = r#"{"protocolVersion":1,"requestId":"r4","type":"embed","mode":"document","inputs":["doc"]}"#;
        let req: Request = serde_json::from_str(json).unwrap();
        match req {
            Request::Embed { mode, inputs, .. } => {
                assert_eq!(mode, EmbedMode::Document);
                assert_eq!(inputs, vec!["doc".to_string()]);
            }
            _ => panic!("expected Embed"),
        }
    }

    #[test]
    fn parse_unload() {
        let json = r#"{"protocolVersion":1,"requestId":"r5","type":"unload"}"#;
        let req: Request = serde_json::from_str(json).unwrap();
        assert!(matches!(req, Request::Unload { .. }));
        assert_eq!(req.request_id(), "r5");
    }

    #[test]
    fn parse_shutdown() {
        let json = r#"{"protocolVersion":1,"requestId":"r6","type":"shutdown"}"#;
        let req: Request = serde_json::from_str(json).unwrap();
        assert!(matches!(req, Request::Shutdown { .. }));
        assert_eq!(req.request_id(), "r6");
    }

    #[test]
    fn parse_unknown_type_is_error() {
        let json = r#"{"protocolVersion":1,"requestId":"r7","type":"unknown"}"#;
        let result: Result<Request, _> = serde_json::from_str(json);
        assert!(result.is_err());
    }

    // --- Response serialisation ---

    #[test]
    fn serialize_ready() {
        let resp = Response::Ready {
            protocol_version: 1,
            request_id: "r1".into(),
        };
        let json = serde_json::to_string(&resp).unwrap();
        let v: serde_json::Value = serde_json::from_str(&json).unwrap();
        assert_eq!(v["type"], "ready");
        assert_eq!(v["protocolVersion"], 1);
        assert_eq!(v["requestId"], "r1");
    }

    #[test]
    fn serialize_model_loaded() {
        let resp = Response::ModelLoaded {
            protocol_version: 1,
            request_id: "r2".into(),
            dimensions: 512,
        };
        let json = serde_json::to_string(&resp).unwrap();
        let v: serde_json::Value = serde_json::from_str(&json).unwrap();
        assert_eq!(v["type"], "model_loaded");
        assert_eq!(v["dimensions"], 512);
    }

    #[test]
    fn serialize_embed_result() {
        let resp = Response::EmbedResult {
            protocol_version: 1,
            request_id: "r3".into(),
            embeddings: vec![vec![0.1, 0.2, 0.3]],
        };
        let json = serde_json::to_string(&resp).unwrap();
        let v: serde_json::Value = serde_json::from_str(&json).unwrap();
        assert_eq!(v["type"], "embed_result");
        assert!(v["embeddings"][0][0].as_f64().is_some());
    }

    #[test]
    fn serialize_ok() {
        let resp = Response::Ok {
            protocol_version: 1,
            request_id: "r4".into(),
        };
        let json = serde_json::to_string(&resp).unwrap();
        let v: serde_json::Value = serde_json::from_str(&json).unwrap();
        assert_eq!(v["type"], "ok");
    }

    #[test]
    fn serialize_error() {
        let resp = Response::error(
            "r5",
            ErrorCode::ModelFilesMissing,
            "file not found".into(),
            true,
        );
        let json = serde_json::to_string(&resp).unwrap();
        let v: serde_json::Value = serde_json::from_str(&json).unwrap();
        assert_eq!(v["type"], "error");
        assert_eq!(v["code"], "MODEL_FILES_MISSING");
        assert!(v["retryable"].as_bool().unwrap());
    }

    // --- ErrorCode serialisation ---

    #[test]
    fn error_code_serde() {
        let cases = vec![
            (ErrorCode::MalformedRequest, "MALFORMED_REQUEST"),
            (
                ErrorCode::ProtocolVersionMismatch,
                "PROTOCOL_VERSION_MISMATCH",
            ),
            (ErrorCode::ModelFilesMissing, "MODEL_FILES_MISSING"),
            (ErrorCode::ModelLoadFailed, "MODEL_LOAD_FAILED"),
            (ErrorCode::ModelNotLoaded, "MODEL_NOT_LOADED"),
            (ErrorCode::DimensionMismatch, "DIMENSION_MISMATCH"),
            (ErrorCode::EmbeddingFailed, "EMBEDDING_FAILED"),
            (ErrorCode::RequestTooLarge, "REQUEST_TOO_LARGE"),
            (ErrorCode::InternalError, "INTERNAL_ERROR"),
        ];
        for (code, expected) in cases {
            let json = serde_json::to_string(&code).unwrap();
            assert_eq!(json, format!("\"{expected}\""), "code={code:?}");
            let back: ErrorCode = serde_json::from_str(&json).unwrap();
            assert_eq!(back, code);
        }
    }

    // --- Limits ---

    #[test]
    fn constants_are_sane() {
        assert_eq!(PROTOCOL_VERSION, 1);
        assert!(MAX_EMBED_INPUTS > 0);
        assert!(MAX_INPUT_LENGTH > 0);
        assert!(MAX_TOTAL_INPUT_LENGTH > MAX_INPUT_LENGTH);
    }
}
