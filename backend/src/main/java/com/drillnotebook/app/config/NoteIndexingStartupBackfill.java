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
     * {@code content_hash IS NULL} and runs full indexing on each one.
     *
     * <p>Repeat calls are idempotent: after all pages are processed, the
     * scan returns zero rows.
     */
    public void backfillAll() {
        try {
            List<Map<String, Object>> pages =
                    retrievalRepo.findPagesWithNullContentHash();
            if (pages.isEmpty()) {
                log.info("Note indexing backfill: no pages to process");
                return;
            }
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
        } catch (Exception e) {
            log.warn("Note indexing backfill: scan failed", e);
        }
    }
}
