package com.drillnotebook.app.service;

import com.drillnotebook.app.repository.QuestionRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class QuestionImportService {
    private final MarkdownQuestionParser parser;
    private final QuestionRepository questions;

    public QuestionImportService(MarkdownQuestionParser parser, QuestionRepository questions) {
        this.parser = parser;
        this.questions = questions;
    }

    public ImportResult importMarkdown(long bankId, String source) {
        List<String> errors = new ArrayList<>();
        int imported = 0;
        int skipped = 0;
        List<MarkdownQuestionParser.ParsedQuestion> parsed;
        try { parsed = parser.parse(source); } catch (IllegalArgumentException error) { return new ImportResult(0, 0, 1, List.of(error.getMessage())); }
        for (int index = 0; index < parsed.size(); index++) {
            MarkdownQuestionParser.ParsedQuestion item = parsed.get(index);
            try {
                String hash = hash(item);
                String legacyHash = legacyHash(item);
                if (questions.findByHash(bankId, hash) != null
                        || (!legacyHash.equals(hash) && questions.findByHash(bankId, legacyHash) != null)) {
                    skipped++;
                    continue;
                }
                questions.insert(bankId, item.type(), item.stem(), questions.optionsJson(item.options()), item.answer(), item.analysis(), item.difficulty(), questions.tagsJson(item.tags()), item.chapter(), item.groupId(), item.orderInGroup(), hash);
                imported++;
            } catch (Exception error) {
                errors.add("第 " + (index + 1) + " 题：" + (error.getMessage() == null ? "导入失败" : error.getMessage()));
            }
        }
        questions.rebuildFts();
        return new ImportResult(imported, skipped, errors.size(), errors);
    }

    public static String hash(MarkdownQuestionParser.ParsedQuestion item) throws NoSuchAlgorithmException, JsonProcessingException {
        String prefix = QuestionTypeRules.isChoice(item.type()) ? "" : item.type();
        return digest(prefix + item.stem() + item.options() + item.answer());
    }

    static String legacyHash(MarkdownQuestionParser.ParsedQuestion item) throws NoSuchAlgorithmException {
        return digest(item.stem() + item.options() + item.answer());
    }

    private static String digest(String material) throws NoSuchAlgorithmException {
        byte[] value = MessageDigest.getInstance("SHA-256").digest(material.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(value);
    }

    public record ImportResult(int imported, int skipped, int failed, List<String> errors) {
        public Map<String, Object> toMap() { return Map.of("imported", imported, "skipped", skipped, "failed", failed, "errors", errors); }
    }
}
