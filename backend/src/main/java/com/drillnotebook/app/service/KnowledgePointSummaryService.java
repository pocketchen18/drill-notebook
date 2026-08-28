package com.drillnotebook.app.service;

import com.drillnotebook.app.model.KnowledgePointRecord;
import com.drillnotebook.app.repository.KnowledgePointOriginalRepository;
import com.drillnotebook.app.repository.KnowledgePointOriginalRepository.OriginalRecord;
import com.drillnotebook.app.repository.KnowledgePointRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class KnowledgePointSummaryService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgePointSummaryService.class);

    private final KnowledgePointRepository points;
    private final KnowledgePointOriginalRepository originals;
    private final AiService ai;
    private final KnowledgePointImportService importer;

    public KnowledgePointSummaryService(KnowledgePointRepository points,
                                        KnowledgePointOriginalRepository originals,
                                        AiService ai,
                                        KnowledgePointImportService importer) {
        this.points = points;
        this.originals = originals;
        this.ai = ai;
        this.importer = importer;
    }

    @Transactional
    public Map<String, Object> summarizePoint(long pointId) {
        Map<String, Object> result = new LinkedHashMap<>();
        List<String> errors = new ArrayList<>();
        try {
            KnowledgePointRecord card = points.findById(pointId);
            if (card == null) throw new IllegalStateException("知识点不存在");
            String contentForAi = card.content;
            if (originals.existsOriginal(pointId)) {
                OriginalRecord orig = originals.find(pointId, "original");
                if (orig != null) contentForAi = orig.content();
            } else {
                originals.upsert(pointId, "original", card.content);
            }
            String summary = ai.summarizeKnowledgePoint(contentForAi);
            originals.upsert(pointId, "summary", summary);
            points.updateContentOnly(pointId, summary);
            result.put("summarized", 1);
        } catch (RuntimeException ex) {
            log.error("总结知识点 #{} 失败: {}", pointId, ex.getMessage(), ex);
            errors.add(ex.getMessage());
            result.put("summarized", 0);
        }
        result.put("errors", errors);
        return result;
    }

    @Transactional
    public Map<String, Object> resummarizePoint(long pointId) {
        Map<String, Object> result = new LinkedHashMap<>();
        List<String> errors = new ArrayList<>();
        try {
            if (!originals.existsOriginal(pointId)) {
                throw new IllegalStateException("当前知识卡片还未总结，请先点击\"总结\"");
            }
            OriginalRecord orig = originals.find(pointId, "original");
            if (orig == null) throw new IllegalStateException("原文记录缺失");
            String summary = ai.summarizeKnowledgePoint(orig.content());
            originals.upsert(pointId, "summary", summary);
            points.updateContentOnly(pointId, summary);
            result.put("summarized", 1);
        } catch (RuntimeException ex) {
            log.error("重新总结知识点 #{} 失败: {}", pointId, ex.getMessage(), ex);
            errors.add(ex.getMessage());
            result.put("summarized", 0);
        }
        result.put("errors", errors);
        return result;
    }

    @Transactional
    public Map<String, Object> summarizeBank(long bankId) {
        return processBank(bankId, false);
    }

    @Transactional
    public Map<String, Object> resummarizeBank(long bankId) {
        return processBank(bankId, true);
    }

    @Transactional
    public Map<String, Object> restoreOriginalBank(long bankId) {
        Map<String, Object> result = new LinkedHashMap<>();
        int restored = 0;
        List<KnowledgePointRecord> cards = points.findAll(bankId);
        for (KnowledgePointRecord card : cards) {
            OriginalRecord orig = originals.find(card.id, "original");
            if (orig != null && orig.content() != null) {
                points.updateContentOnly(card.id, orig.content());
                restored++;
            }
        }
        result.put("restored", restored);
        return result;
    }

    @Transactional
    public Map<String, Object> restoreSummaryBank(long bankId) {
        Map<String, Object> result = new LinkedHashMap<>();
        int restored = 0;
        List<KnowledgePointRecord> cards = points.findAll(bankId);
        for (KnowledgePointRecord card : cards) {
            OriginalRecord summ = originals.find(card.id, "summary");
            if (summ != null && summ.content() != null) {
                points.updateContentOnly(card.id, summ.content());
                restored++;
            }
        }
        result.put("restored", restored);
        return result;
    }

    private Map<String, Object> processBank(long bankId, boolean resummarizeMode) {
        Map<String, Object> result = new LinkedHashMap<>();
        List<String> errors = new ArrayList<>();
        int summarized = 0;
        int failed = 0;
        List<KnowledgePointRecord> cards = points.findAll(bankId);
        for (KnowledgePointRecord card : cards) {
            try {
                boolean hasOriginal = originals.existsOriginal(card.id);
                if (resummarizeMode) {
                    if (!hasOriginal) continue;
                    OriginalRecord orig = originals.find(card.id, "original");
                    if (orig == null || orig.content() == null || orig.content().isBlank()) continue;
                    String summary = ai.summarizeKnowledgePoint(orig.content());
                    originals.upsert(card.id, "summary", summary);
                    points.updateContentOnly(card.id, summary);
                } else {
                    if (hasOriginal) continue;
                    if (card.content == null || card.content.isBlank()) continue;
                    originals.upsert(card.id, "original", card.content);
                    String summary = ai.summarizeKnowledgePoint(card.content);
                    originals.upsert(card.id, "summary", summary);
                    points.updateContentOnly(card.id, summary);
                }
                summarized++;
            } catch (RuntimeException ex) {
                failed++;
                log.error("知识库 #{} 批量总结知识点 #{} 失败: {}", bankId, card.id, ex.getMessage(), ex);
                errors.add("知识点 #" + card.id + "：" + ex.getMessage());
            }
        }
        result.put("summarized", summarized);
        result.put("failed", failed);
        result.put("errors", errors);
        return result;
    }

    @Transactional
    public Map<String, Object> summarizeImport(long bankId, String content) {
        String summary = ai.summarizeMarkdown(content);
        Map<String, Object> importResult = importer.importMarkdown(bankId, summary);
        Map<String, Object> result = new LinkedHashMap<>(importResult);
        result.put("strategy", "ai-summary");
        return result;
    }
}
