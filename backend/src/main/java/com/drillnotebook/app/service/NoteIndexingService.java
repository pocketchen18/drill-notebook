package com.drillnotebook.app.service;

import com.drillnotebook.app.model.Chunk;
import com.drillnotebook.app.model.NormalizedUnit;
import com.drillnotebook.app.model.QuestionRecord;
import com.drillnotebook.app.repository.NotebookRepository;
import com.drillnotebook.app.repository.RetrievalIndexRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Transactional service for notebook page mutations that synchronizes the
 * retrieval index (chunks, FTS, embedding jobs).
 *
 * <p>All public mutation methods are {@code @Transactional}. Callers
 * (controllers and the startup backfill listener) must invoke through the
 * Spring-proxied bean to get effective transaction boundaries. Read-only
 * operations can bypass this service and use repositories directly.
 */
@Service
public class NoteIndexingService {

    private static final Logger log = LoggerFactory.getLogger(NoteIndexingService.class);

    private final NotebookRepository notebooks;
    private final RetrievalIndexRepository retrievalRepo;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final NoteNormalizer normalizer;
    private final NoteChunker chunker;

    public NoteIndexingService(
            NotebookRepository notebooks,
            RetrievalIndexRepository retrievalRepo,
            JdbcTemplate jdbc,
            ObjectMapper mapper) {
        this.notebooks = notebooks;
        this.retrievalRepo = retrievalRepo;
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.normalizer = new NoteNormalizer(mapper);
        this.chunker = new NoteChunker();
    }

    @Transactional
    public Map<String, Object> savePageAndIndex(
            long pageId, String title, Object content) {
        // Title-only: cheap update, preserve content/index, sync chunk/FTS titles
        if (content == null) {
            if (title != null) {
                notebooks.updatePage(pageId, title, null);
                List<Long> chunkIds = retrievalRepo.findChunkIdsBySource(
                        "NOTEBOOK", pageId);
                if (!chunkIds.isEmpty()) {
                    retrievalRepo.updateChunkTitlesBySource(
                            "NOTEBOOK", pageId, title);
                    retrievalRepo.updateFtsTitleByChunkIds(chunkIds, title);
                }
            }
            return notebooks.findPage(pageId);
        }

        // Serialize content to JSON string for normalization
        String contentJson;
        try {
            contentJson = mapper.writeValueAsString(content);
        } catch (Exception e) {
            throw new IllegalArgumentException(
                    "NORMALIZATION_ERROR: " + e.getMessage(), e);
        }

        // Normalize and chunk
        List<NormalizedUnit> units;
        try {
            units = normalizer.normalize(contentJson);
        } catch (IllegalArgumentException e) {
            throw e;
        }

        // Get page title for chunk title
        String pageTitle;
        try {
            Map<String, Object> existing = notebooks.findPage(pageId);
            pageTitle = title != null
                    ? title
                    : (existing.get("title") != null
                            ? String.valueOf(existing.get("title"))
                            : "");
        } catch (EmptyResultDataAccessException e) {
            throw new IllegalArgumentException("Page not found: " + pageId);
        }

        // Build full normalized text and compute content hash
        String fullText = buildFullText(units);
        String contentHash = sha256(fullText);

        // Chunk the normalized units
        List<Chunk> chunks = chunker.chunk(units, pageTitle);

        // Get notebook_id for chunk corpus_id
        long notebookId = retrievalRepo.findNotebookIdByPageId(pageId);

        // ---- Transactional index update ----
        // Update note_page with content and content_hash + optional title
        if (title != null) {
            jdbc.update(
                    "UPDATE note_page SET content = ?, content_hash = ?,"
                            + " title = ?, updated_at = datetime('now')"
                            + " WHERE id = ?",
                    contentJson, contentHash, title, pageId);
        } else {
            jdbc.update(
                    "UPDATE note_page SET content = ?, content_hash = ?,"
                            + " updated_at = datetime('now') WHERE id = ?",
                    contentJson, contentHash, pageId);
        }

        // Delete old FTS rows for this page's chunks
        List<Long> oldChunkIds = retrievalRepo.findChunkIdsBySource(
                "NOTEBOOK", pageId);
        if (!oldChunkIds.isEmpty()) {
            retrievalRepo.deleteFtsByChunkIds(oldChunkIds);
        }

        // Delete old chunks
        retrievalRepo.deleteChunksBySource("NOTEBOOK", pageId);

        // Insert new chunks and FTS rows
        replaceIndex(pageId, notebookId, chunks, contentHash, "PAGE_UPDATE");

        return notebooks.findPage(pageId);
    }

    private void replaceIndex(
            long pageId,
            long notebookId,
            List<Chunk> chunks,
            String contentHash,
            String jobReason) {
        for (Chunk chunk : chunks) {
            long chunkId = retrievalRepo.insertChunk(
                    "NOTEBOOK", notebookId, pageId, chunk);
            String headingPathStr = String.join(" / ", chunk.headingPath());
            retrievalRepo.insertFtsRow(
                    chunkId, chunk.title(), headingPathStr, chunk.text());
        }

        // Handle embedding job lifecycle
        Map<String, Object> selectedSpace = retrievalRepo.findSelectedSpace();
        if (selectedSpace != null) {
            String spaceId = (String) selectedSpace.get("embedding_space_id");
            String state = (String) selectedSpace.get("state");

            // Always supersede old jobs with different content hash,
            // even for zero-chunk pages (old chunks were just deleted)
            retrievalRepo.supersedeJobs(
                    "NOTEBOOK", pageId, contentHash, spaceId);

            if (!chunks.isEmpty()) {
                // Transition ACTIVE -> REBUILDING
                if ("ACTIVE".equals(state)) {
                    retrievalRepo.transitionActiveToRebuilding(spaceId);
                }

                // Upsert QUEUED job (resets attempt/claim/error state
                // even when the same hash already has a job row)
                retrievalRepo.upsertJob(
                        "NOTEBOOK", pageId, contentHash, spaceId, jobReason);
            }
        }
    }

    // ── Create page (atomic insert + index) ────────────────────────────────

    @Transactional
    public Map<String, Object> createAndIndexPage(
            long notebookId, String title, Object content) {
        // Insert the page
        long pageId = notebooks.insertPage(notebookId, title, content);
        // If content is provided, index it in the same transaction
        if (content != null) {
            return savePageAndIndex(pageId, null, content);
        }
        return notebooks.findPage(pageId);
    }

    // ── Page delete ────────────────────────────────────────────────────────

    /** Shared helper: study-plan + index cleanup for a single page. */
    private void deletePageIndex(long pageId) {
        // 1. study_plan_item/group cleanup
        List<Long> groupIds = jdbc.query(
                "SELECT DISTINCT group_id FROM study_plan_item"
                        + " WHERE resource_type = 'note_page' AND resource_id = ?",
                (rs, row) -> rs.getLong(1),
                pageId);
        jdbc.update(
                "DELETE FROM study_plan_item"
                        + " WHERE resource_type = 'note_page' AND resource_id = ?",
                pageId);
        for (Long groupId : groupIds) {
            if (groupId == null) continue;
            Integer count = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM study_plan_item WHERE group_id = ?",
                    Integer.class, groupId);
            if (count != null && count == 0) {
                jdbc.update("DELETE FROM study_plan_group WHERE id = ?", groupId);
            }
        }

        // 2. Delete embedding jobs for this source
        retrievalRepo.deleteJobsBySource("NOTEBOOK", pageId);

        // 3. Delete FTS rows by chunk rowids
        List<Long> chunkIds = retrievalRepo.findChunkIdsBySource(
                "NOTEBOOK", pageId);
        if (!chunkIds.isEmpty()) {
            retrievalRepo.deleteFtsByChunkIds(chunkIds);
        }

        // 4. Delete chunks (retrieval_embedding cascades via FK)
        retrievalRepo.deleteChunksBySource("NOTEBOOK", pageId);

        // 5. Delete note_question_ref
        jdbc.update("DELETE FROM note_question_ref WHERE note_id = ?", pageId);
    }

    @Transactional
    public void deletePage(long pageId) {
        deletePageIndex(pageId);
        jdbc.update("DELETE FROM note_page WHERE id = ?", pageId);
    }

    // ── Notebook delete ────────────────────────────────────────────────────

    @Transactional
    public void deleteNotebook(long notebookId) {
        // Enumerate page IDs for this notebook
        List<Long> pageIds = retrievalRepo.findPageIdsByNotebook(notebookId);

        // Clean up study-plan + index for each page
        for (long pageId : pageIds) {
            deletePageIndex(pageId);
        }

        // Delete notebook (cascades to note_page)
        jdbc.update("DELETE FROM notebook WHERE id = ?", notebookId);
    }

    // ── Add question snapshot ──────────────────────────────────────────────

    @Transactional
    public Map<String, Object> addQuestionSnapshot(
            long pageId, QuestionRecord question) {
        // Get current page content
        Map<String, Object> page = notebooks.findPage(pageId);
        Object rawContent = page.get("content");

        // Insert note_question_ref (upsert)
        try {
            jdbc.update(
                    "INSERT INTO note_question_ref"
                            + "(note_id, question_id, snapshot_json)"
                            + " VALUES (?, ?, ?)"
                            + " ON CONFLICT(note_id, question_id)"
                            + " DO UPDATE SET snapshot_json = excluded.snapshot_json",
                    pageId, question.id,
                    mapper.writeValueAsString(question.snapshot()));
        } catch (Exception e) {
            throw new IllegalArgumentException("题块保存失败", e);
        }

        // Modify content: add questionBlock node
        try {
            String raw = mapper.writeValueAsString(rawContent);
            ObjectNode document = (ObjectNode) mapper.readTree(raw);
            ArrayNode content = document.withArray("content");

            // Check if already present
            boolean exists = false;
            for (JsonNode node : content) {
                JsonNode attrs = node.get("attrs");
                if (attrs != null
                        && attrs.has("questionId")
                        && attrs.get("questionId").asLong() == question.id) {
                    exists = true;
                    break;
                }
            }

            if (!exists) {
                ObjectNode block = mapper.createObjectNode();
                block.put("type", "questionBlock");
                ObjectNode attrs = mapper.createObjectNode();
                attrs.put("questionId", question.id);
                attrs.set("snapshot",
                        mapper.valueToTree(question.snapshot()));
                block.set("attrs", attrs);
                content.add(block);
            }

            // Save through the full reindex path
            return savePageAndIndex(pageId, null, document);
        } catch (Exception e) {
            throw new IllegalArgumentException("题块保存失败", e);
        }
    }

    @Transactional
    public void backfillPage(long pageId) {
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT notebook_id, title, content FROM note_page WHERE id = ?",
                pageId);
        long notebookId = ((Number) row.get("notebook_id")).longValue();
        String title = row.get("title") == null ? "" : String.valueOf(row.get("title"));
        String contentJson = row.get("content") == null
                ? ""
                : String.valueOf(row.get("content"));

        List<NormalizedUnit> units = normalizer.normalize(contentJson);
        String contentHash = sha256(buildFullText(units));
        List<Chunk> chunks = chunker.chunk(units, title);

        int claimed = jdbc.update(
                "UPDATE note_page SET content_hash = ?"
                        + " WHERE id = ? AND content_hash IS NULL",
                contentHash, pageId);
        if (claimed == 0) {
            return;
        }

        List<Long> oldChunkIds = retrievalRepo.findChunkIdsBySource(
                "NOTEBOOK", pageId);
        if (!oldChunkIds.isEmpty()) {
            retrievalRepo.deleteFtsByChunkIds(oldChunkIds);
        }
        retrievalRepo.deleteChunksBySource("NOTEBOOK", pageId);
        replaceIndex(pageId, notebookId, chunks, contentHash, "STARTUP_BACKFILL");
    }

    // ── Utility methods ────────────────────────────────────────────────────

    static String buildFullText(List<NormalizedUnit> units) {
        if (units == null || units.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < units.size(); i++) {
            if (i > 0) sb.append("\n\n");
            sb.append(units.get(i).text());
        }
        return sb.toString();
    }

    public static String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] h = md.digest(
                    input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : h) {
                hex.append(String.format("%02x", b & 0xff));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }
}
