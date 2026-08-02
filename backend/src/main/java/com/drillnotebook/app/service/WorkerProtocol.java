package com.drillnotebook.app.service;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.List;

/**
 * NDJSON protocol types for the embedding worker (protocol v1).
 * <p>
 * Mirror of {@code embedding-worker/src/protocol.rs}. Every line on stdin is a
 * JSON-serialised {@link Request}; every line on stdout is a
 * {@link Response}.
 */
public final class WorkerProtocol {

    public static final int PROTOCOL_VERSION = 1;

    private WorkerProtocol() {}

    // -----------------------------------------------------------------------
    // Error codes
    // -----------------------------------------------------------------------

    public enum ErrorCode {
        MALFORMED_REQUEST("MALFORMED_REQUEST"),
        PROTOCOL_VERSION_MISMATCH("PROTOCOL_VERSION_MISMATCH"),
        MODEL_FILES_MISSING("MODEL_FILES_MISSING"),
        MODEL_LOAD_FAILED("MODEL_LOAD_FAILED"),
        MODEL_NOT_LOADED("MODEL_NOT_LOADED"),
        DIMENSION_MISMATCH("DIMENSION_MISMATCH"),
        EMBEDDING_FAILED("EMBEDDING_FAILED"),
        REQUEST_TOO_LARGE("REQUEST_TOO_LARGE"),
        INTERNAL_ERROR("INTERNAL_ERROR");

        private final String code;

        ErrorCode(String code) { this.code = code; }

        @JsonValue
        public String code() { return code; }
    }

    // -----------------------------------------------------------------------
    // Embed mode
    // -----------------------------------------------------------------------

    public enum EmbedMode {
        QUERY("query"),
        DOCUMENT("document");

        private final String value;

        EmbedMode(String value) { this.value = value; }

        @JsonValue
        public String value() { return value; }
    }

    // -----------------------------------------------------------------------
    // Request
    // -----------------------------------------------------------------------

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
    @JsonSubTypes({
        @JsonSubTypes.Type(value = Request.Hello.class, name = "hello"),
        @JsonSubTypes.Type(value = Request.LoadModel.class, name = "load_model"),
        @JsonSubTypes.Type(value = Request.Embed.class, name = "embed"),
        @JsonSubTypes.Type(value = Request.Unload.class, name = "unload"),
        @JsonSubTypes.Type(value = Request.Shutdown.class, name = "shutdown"),
    })
    public sealed interface Request {

        @JsonProperty("protocolVersion")
        int protocolVersion();
        @JsonProperty("requestId")
        String requestId();

        record Hello(
            @JsonProperty("protocolVersion") int protocolVersion,
            @JsonProperty("requestId") String requestId
        ) implements Request {}

        record LoadModel(
            @JsonProperty("protocolVersion") int protocolVersion,
            @JsonProperty("requestId") String requestId,
            @JsonProperty("modelId") String modelId,
            @JsonProperty("modelDir") String modelDir,
            @JsonProperty("requiredFiles") List<String> requiredFiles,
            int dimensions
        ) implements Request {}

        record Embed(
            @JsonProperty("protocolVersion") int protocolVersion,
            @JsonProperty("requestId") String requestId,
            EmbedMode mode,
            List<String> inputs
        ) implements Request {}

        record Unload(
            @JsonProperty("protocolVersion") int protocolVersion,
            @JsonProperty("requestId") String requestId
        ) implements Request {}

        record Shutdown(
            @JsonProperty("protocolVersion") int protocolVersion,
            @JsonProperty("requestId") String requestId
        ) implements Request {}
    }

    // -----------------------------------------------------------------------
    // Response
    // -----------------------------------------------------------------------

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
    @JsonSubTypes({
        @JsonSubTypes.Type(value = Response.Ready.class, name = "ready"),
        @JsonSubTypes.Type(value = Response.ModelLoaded.class, name = "model_loaded"),
        @JsonSubTypes.Type(value = Response.EmbedResult.class, name = "embed_result"),
        @JsonSubTypes.Type(value = Response.Ok.class, name = "ok"),
        @JsonSubTypes.Type(value = Response.Error.class, name = "error"),
    })
    public sealed interface Response {

        @JsonProperty("protocolVersion")
        int protocolVersion();
        @JsonProperty("requestId")
        String requestId();

        record Ready(
            @JsonProperty("protocolVersion") int protocolVersion,
            @JsonProperty("requestId") String requestId
        ) implements Response {}

        record ModelLoaded(
            @JsonProperty("protocolVersion") int protocolVersion,
            @JsonProperty("requestId") String requestId,
            int dimensions
        ) implements Response {}

        record EmbedResult(
            @JsonProperty("protocolVersion") int protocolVersion,
            @JsonProperty("requestId") String requestId,
            List<List<Float>> embeddings
        ) implements Response {}

        record Ok(
            @JsonProperty("protocolVersion") int protocolVersion,
            @JsonProperty("requestId") String requestId
        ) implements Response {}

        record Error(
            @JsonProperty("protocolVersion") int protocolVersion,
            @JsonProperty("requestId") String requestId,
            ErrorCode code,
            String message,
            boolean retryable
        ) implements Response {}
    }
}
