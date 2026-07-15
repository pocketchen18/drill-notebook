package com.drillnotebook.app.service;

import com.drillnotebook.app.repository.QuestionRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class QuestionImportService {

    private final MarkdownQuestionParser parser;
    private final JsonQuestionParser jsonParser;
    private final QuestionRepository questions;
    private final ImportOrchestrator orchestrator;
    private final QuestionMarkdownRuleStrategy mdRuleStrategy;
    private final QuestionMarkdownAiStrategy mdAiStrategy;
    private final QuestionJsonRuleStrategy jsonRuleStrategy;
    private final QuestionJsonAiStrategy jsonAiStrategy;

    public QuestionImportService(MarkdownQuestionParser parser, JsonQuestionParser jsonParser,
                                  QuestionRepository questions, ImportOrchestrator orchestrator,
                                  QuestionMarkdownRuleStrategy mdRuleStrategy,
                                  QuestionMarkdownAiStrategy mdAiStrategy,
                                  QuestionJsonRuleStrategy jsonRuleStrategy,
                                  QuestionJsonAiStrategy jsonAiStrategy) {
        this.parser = parser;
        this.jsonParser = jsonParser;
        this.questions = questions;
        this.orchestrator = orchestrator;
        this.mdRuleStrategy = mdRuleStrategy;
        this.mdAiStrategy = mdAiStrategy;
        this.jsonRuleStrategy = jsonRuleStrategy;
        this.jsonAiStrategy = jsonAiStrategy;
    }

    public ImportResult importMarkdown(long bankId, String source) {
        ImportRequest req = new ImportRequest(bankId, source, null, null, false);
        RuleResult result = orchestrator.run(mdRuleStrategy, mdAiStrategy, req);
        return importParsed(bankId, result.parsed(), result.strategy());
    }

    public ImportResult importJson(long bankId, String source) {
        ImportRequest req = new ImportRequest(bankId, source, null, null, false);
        RuleResult result = orchestrator.run(jsonRuleStrategy, jsonAiStrategy, req);
        return importParsed(bankId, result.parsed(), result.strategy());
    }

    public ImportResult importParsed(long bankId, List<MarkdownQuestionParser.ParsedQuestion> parsed, String strategy) {
        List<String> errors = new ArrayList<>();
        int imported = 0;
        int skipped = 0;
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
        return new ImportResult(imported, skipped, errors.size(), errors, strategy);
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

    public record ImportResult(int imported, int skipped, int failed, List<String> errors, String strategy) {
        public Map<String, Object> toMap() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("imported", imported);
            result.put("skipped", skipped);
            result.put("failed", failed);
            result.put("errors", errors);
            result.put("strategy", strategy);
            return result;
        }
    }
}
