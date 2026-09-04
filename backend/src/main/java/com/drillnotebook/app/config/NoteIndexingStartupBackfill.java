package com.drillnotebook.app.config;

import com.drillnotebook.app.repository.RetrievalIndexRepository;
import com.drillnotebook.app.service.NoteIndexingService;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Asynchronous startup backfill that runs full indexing (normalization,
 * chunks, FTS, optional embedding jobs) for any {@code note_page} rows
 * where {@code content_hash} is still {@code NULL}.
 *
 * <p>Each page is processed through {@link NoteIndexingService#savePageAndIndex}
 * via the Spring-proxied bean, so every save is transactional.
 *
 * <p>Malformed pages remain unmarked so a later repair can retry them. Valid
 * pages that normalize to zero chunks still receive a content hash.
 *
 * <p>This process is idempotent and never blocks startup.
 */
@Component
public class NoteIndexingStartupBackfill {

    private static final Logger log =
            LoggerFactory.getLogger(NoteIndexingStartupBackfill.class);

    private final RetrievalIndexRepository retrievalRepo;
    private final NoteIndexingService indexingService;
    public NoteIndexingStartupBackfill(
            RetrievalIndexRepository retrievalRepo,
            NoteIndexingService indexingService) {
        this.retrievalRepo = retrievalRepo;
        this.indexingService = indexingService;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "note-indexing-backfill");
            t.setDaemon(true);
            return t;
        });
        executor.submit(this::backfillAll);
        executor.shutdown();
    }

    /**
     * Deterministic direct method for tests. Scans pages with
     * {@code content_hash IS NULL} and runs full indexing on each one, then
     * audits already-hashed pages against the current normalizer.
     *
     * <p>Repeat calls are idempotent: after all pages are processed, the
     * scan returns zero rows and the audit finds no hash drift.
     */
    public void backfillAll() {
        try {
            List<Map<String, Object>> pages =
                    retrievalRepo.findPagesWithNullContentHash();
            if (pages.isEmpty()) {
                log.info("Note indexing backfill: no pages to process");
            } else {
                log.info("Note indexing backfill: processing {} pages", pages.size());
                for (Map<String, Object> page : pages) {
                    long pageId = ((Number) page.get("page_id")).longValue();
                    try {
                        indexingService.backfillPage(pageId);
                        log.info("Backfilled page {}", pageId);
                    } catch (Exception e) {
                        log.warn("Full indexing failed for page {}; leaving it pending",
                                pageId);
                    }
                }
                log.info("Note indexing backfill: completed");
            }
        } catch (Exception e) {
            log.warn("Note indexing backfill: scan failed", e);
        }
        auditNormalization();
    }

    /**
     * Re-index pages whose stored {@code content_hash} disagrees with the current
     * normalizer. Without this a normalizer upgrade (e.g. indexing video/file
     * blocks) never reaches pages that were already indexed — they keep the stale
     * chunk set, or none at all, until the user edits them.
     *
     * <p>Cheap in the common case: hashes are compared, and only mismatching pages
     * are re-chunked.
     */
    private void auditNormalization() {
        try {
            List<Map<String, Object>> pages = retrievalRepo.findPageHashes();
            int repaired = 0;
            int failed = 0;
            for (Map<String, Object> page : pages) {
                long pageId = ((Number) page.get("page_id")).longValue();
                try {
                    if (indexingService.reindexIfNormalizationChanged(pageId)) repaired++;
                } catch (Exception e) {
                    failed++;
                    log.warn("Normalizer audit failed for page {}; leaving it as is",
                            pageId);
                }
            }
            if (repaired > 0 || failed > 0) {
                log.info("Normalizer audit: re-indexed {} of {} pages ({} failed)",
                        repaired, pages.size(), failed);
            }
        } catch (Exception e) {
            log.warn("Normalizer audit: scan failed", e);
        }
    }
}
