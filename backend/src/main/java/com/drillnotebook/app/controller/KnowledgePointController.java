package com.drillnotebook.app.controller;

import com.drillnotebook.app.model.KnowledgePointRecord;
import com.drillnotebook.app.repository.BankRepository;
import com.drillnotebook.app.repository.KnowledgePointOriginalRepository;
import com.drillnotebook.app.repository.KnowledgePointRepository;
import com.drillnotebook.app.service.KnowledgePointImportService;
import com.drillnotebook.app.service.KnowledgePointSummaryService;
import java.util.List;
import java.util.Map;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/knowledge-points")
public class KnowledgePointController {
    private final KnowledgePointRepository points;
    private final KnowledgePointImportService importer;
    private final BankRepository banks;
    private final KnowledgePointSummaryService summarySvc;
    private final KnowledgePointOriginalRepository originals;

    public KnowledgePointController(KnowledgePointRepository points, KnowledgePointImportService importer, BankRepository banks, KnowledgePointSummaryService summarySvc, KnowledgePointOriginalRepository originals) {
        this.points = points;
        this.importer = importer;
        this.banks = banks;
        this.summarySvc = summarySvc;
        this.originals = originals;
    }

    @GetMapping
    public List<Map<String, Object>> list(@RequestParam(required = false) Long bankId) {
        return points.findAll(bankId).stream().map(this::toMap).toList();
    }

    @GetMapping("/{id}")
    public Map<String, Object> get(@PathVariable long id) { return toMap(find(id)); }

    @PostMapping
    public Map<String, Object> create(@RequestBody Map<String, Object> body) {
        Long bankId = longValue(body.get("bankId"));
        if (bankId != null) banks.find(bankId);
        try {
            long id = points.insert(bankId, required(body, "title"), required(body, "content"), string(body.get("category")), strings(body.get("tags")), strings(body.get("headingPath")), longs(body.get("questionIds")));
            return toMap(points.findById(id));
        } catch (IllegalArgumentException error) {
            throw error;
        } catch (Exception error) {
            throw new IllegalArgumentException("知识点保存失败");
        }
    }

    @PutMapping("/{id}")
    public Map<String, Object> update(@PathVariable long id, @RequestBody Map<String, Object> body) {
        KnowledgePointRecord current = find(id);
        try {
            points.update(id,
                    stringOr(body.get("title"), current.title),
                    stringOr(body.get("content"), current.content),
                    body.containsKey("category") ? string(body.get("category")) : current.category,
                    body.containsKey("tags") ? strings(body.get("tags")) : current.tags,
                    body.containsKey("headingPath") ? strings(body.get("headingPath")) : current.headingPath,
                    body.containsKey("questionIds") ? longs(body.get("questionIds")) : points.questionIds(id));
            return toMap(points.findById(id));
        } catch (IllegalArgumentException error) {
            throw error;
        } catch (Exception error) {
            throw new IllegalArgumentException("知识点保存失败");
        }
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable long id) { find(id); points.delete(id); }

    /** 批量删除：body { ids: [1,2,3] }，单事务内级联删除，返回实际删除数。 */
    @PostMapping("/batch-delete")
    public Map<String, Object> deleteBatch(@RequestBody Map<String, Object> body) {
        List<Long> ids = longs(body.get("ids"));
        if (ids.isEmpty()) throw new IllegalArgumentException("缺少 ids");
        int deleted = points.deleteBatch(ids);
        return Map.of("deleted", deleted);
    }

    @PutMapping("/sort-all")
    public void sortAll(@RequestBody Map<String, Object> body) {
        Long bankId = longValue(body.get("bankId"));
        if (bankId == null) throw new IllegalArgumentException("缺少 bankId");
        try {
            banks.find(bankId);
        } catch (EmptyResultDataAccessException error) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "题库不存在");
        }
        points.reorderAll(bankId, longs(body.get("sortedIds")));
    }

    @PostMapping("/import/markdown")
    public Map<String, Object> importMarkdown(@RequestBody Map<String, Object> body) {
        Long bankId = longValue(body.get("bankId"));
        if (bankId != null) banks.find(bankId);
        return importer.importMarkdown(bankId, required(body, "content"));
    }

    @PostMapping("/summarize")
    public Map<String, Object> summarizeBank(@RequestParam long bankId) {
        return summarySvc.summarizeBank(bankId);
    }

    @PostMapping("/resummarize")
    public Map<String, Object> resummarizeBank(@RequestParam long bankId) {
        return summarySvc.resummarizeBank(bankId);
    }

    @PostMapping("/summarize-import")
    public Map<String, Object> summarizeImport(@RequestBody Map<String, Object> body) {
        long bankId = ((Number) body.get("bankId")).longValue();
        String content = (String) body.get("content");
        return summarySvc.summarizeImport(bankId, content);
    }

    @PostMapping("/{id}/summarize")
    public Map<String, Object> summarizePoint(@PathVariable long id) {
        return summarySvc.summarizePoint(id);
    }

    @PostMapping("/{id}/resummarize")
    public Map<String, Object> resummarizePoint(@PathVariable long id) {
        return summarySvc.resummarizePoint(id);
    }

    @GetMapping("/{id}/original")
    public Map<String, Object> getOriginal(@PathVariable long id) {
        KnowledgePointOriginalRepository.OriginalRecord rec = originals.find(id, "original");
        if (rec == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "无原文记录");
        return Map.of("content", rec.content(), "role", rec.role());
    }

    @PostMapping("/{id}/restore-original")
    public Map<String, Object> restoreOriginal(@PathVariable long id) {
        KnowledgePointOriginalRepository.OriginalRecord rec = originals.find(id, "original");
        if (rec == null) throw new ResponseStatusException(HttpStatus.PRECONDITION_FAILED, "无原文记录");
        points.updateContentOnly(id, rec.content());
        return Map.of("content", rec.content());
    }

    @PostMapping("/{id}/restore-summary")
    public Map<String, Object> restoreSummary(@PathVariable long id) {
        KnowledgePointOriginalRepository.OriginalRecord rec = originals.find(id, "summary");
        if (rec == null) throw new ResponseStatusException(HttpStatus.PRECONDITION_FAILED, "无总结记录");
        points.updateContentOnly(id, rec.content());
        return Map.of("content", rec.content());
    }

    private KnowledgePointRecord find(long id) {
        try {
            return points.findById(id);
        } catch (EmptyResultDataAccessException error) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "知识点不存在");
        }
    }

    private Map<String, Object> toMap(KnowledgePointRecord point) { return point.toMap(points.questionIds(point.id)); }
    private static String required(Map<String, Object> body, String key) { String value = string(body.get(key)); if (value == null || value.isBlank()) throw new IllegalArgumentException("缺少 " + key); return value.trim(); }
    private static String string(Object value) { return value == null ? null : String.valueOf(value).trim(); }
    private static String stringOr(Object value, String fallback) { String text = string(value); return text == null || text.isBlank() ? fallback : text; }
    private static Long longValue(Object value) { if (value == null || String.valueOf(value).isBlank()) return null; return Long.valueOf(String.valueOf(value)); }
    private static int intOr(Object value, int fallback) { if (value == null || String.valueOf(value).isBlank()) return fallback; try { return Integer.parseInt(String.valueOf(value).trim()); } catch (NumberFormatException error) { return fallback; } }
    private static List<String> strings(Object value) { return value instanceof List<?> list ? list.stream().map(String::valueOf).map(String::trim).filter(item -> !item.isBlank()).toList() : List.of(); }
    private static List<Long> longs(Object value) { return value instanceof List<?> list ? list.stream().map(item -> Long.valueOf(String.valueOf(item))).toList() : List.of(); }
}
