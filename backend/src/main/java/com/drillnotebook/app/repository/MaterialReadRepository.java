package com.drillnotebook.app.repository;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 资料工具的有界只读投影；正文仅在明确 ID 后加载。 */
@Repository
public class MaterialReadRepository {
    private final JdbcTemplate jdbc;
    public MaterialReadRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public boolean exists(String type, long id) {
        String table = switch (type) {
            case "notebook" -> "notebook"; case "note" -> "note_page";
            case "knowledge" -> "knowledge_point"; case "bank" -> "question_bank";
            case "question" -> "question"; default -> throw new IllegalArgumentException();
        };
        return Integer.valueOf(1).equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM " + table + " WHERE id=?)", Integer.class, id));
    }

    public Map<String, Object> detail(String type, long id) {
        String sql = switch (type) {
            case "notebook" -> "SELECT id,title FROM notebook WHERE id=?";
            case "bank" -> "SELECT id,name AS title,description FROM question_bank WHERE id=?";
            case "note" -> "SELECT p.id,p.notebook_id AS notebookId,p.parent_id AS parentId,p.title,p.content,p.updated_at AS updatedAt,n.title AS parentTitle FROM note_page p JOIN notebook n ON n.id=p.notebook_id WHERE p.id=?";
            case "knowledge" -> "SELECT p.id,p.bank_id AS bankId,p.title,p.content,p.category,p.tags,p.heading_path AS headingPath,p.updated_at AS updatedAt,b.name AS parentTitle,(SELECT json_group_array(question_id) FROM (SELECT question_id FROM knowledge_point_question WHERE knowledge_point_id=p.id ORDER BY question_id)) AS questionIds FROM knowledge_point p LEFT JOIN question_bank b ON b.id=p.bank_id WHERE p.id=?";
            case "question" -> "SELECT q.id,q.bank_id AS bankId,q.type AS questionType,q.stem,q.options,q.answer,q.analysis,q.chapter,q.tags,q.difficulty,b.name AS parentTitle FROM question q JOIN question_bank b ON b.id=q.bank_id WHERE q.id=?";
            default -> throw new IllegalArgumentException();
        };
        var rows = jdbc.queryForList(sql, id);
        return rows.isEmpty() ? null : new LinkedHashMap<>(rows.get(0));
    }

    public String knowledgeVersion(long id, String role) {
        var rows = jdbc.queryForList("SELECT content FROM knowledge_point_original WHERE point_id=? AND role=?", String.class, id, role);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 同一个 UNION 顺序提供全类型分页；笔记正文关键词直接复用现有 retrieval_chunk 词法索引。 */
    public List<Map<String, Object>> list(String type, String query, Long notebookId, Long bankId,
                                          Long questionId, int offset, int limit) {
        List<String> types = "all".equals(type) ? List.of("notebook", "note", "knowledge", "bank", "question", "wrong") : List.of(type);
        var selects = new ArrayList<String>();
        var args = new ArrayList<Object>();
        String pattern = "%" + query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
        for (String t : types) {
            if (notebookId != null && !Set.of("notebook", "note").contains(t)) continue;
            if (bankId != null && Set.of("notebook", "note").contains(t)) continue;
            String select, filter;
            switch (t) {
                case "notebook" -> { select="SELECT 'notebook' AS type,id,substr(title,1,200) AS title,NULL AS parentId,NULL AS parentTitle,'' AS snippet,0 AS attemptCount,0 AS wrongCount FROM notebook WHERE 1=1"; filter="title"; }
                case "bank" -> { select="SELECT 'bank' AS type,id,substr(name,1,200) AS title,NULL AS parentId,NULL AS parentTitle,substr(coalesce(description,''),1,240) AS snippet,0 AS attemptCount,0 AS wrongCount FROM question_bank WHERE 1=1"; filter="name || ' ' || coalesce(description,'')"; }
                case "note" -> { select="SELECT 'note' AS type,p.id,substr(p.title,1,200) AS title,p.notebook_id AS parentId,substr(n.title,1,200) AS parentTitle,coalesce((SELECT substr(r.text,1,240) FROM retrieval_chunk r WHERE r.corpus_type='NOTEBOOK' AND r.source_id=p.id ORDER BY r.chunk_index LIMIT 1),'') AS snippet,0 AS attemptCount,0 AS wrongCount FROM note_page p JOIN notebook n ON n.id=p.notebook_id WHERE 1=1"; filter="p.title"; }
                case "knowledge" -> { select="SELECT 'knowledge' AS type,p.id,substr(p.title,1,200) AS title,p.bank_id AS parentId,substr(b.name,1,200) AS parentTitle,substr(p.content,1,240) AS snippet,0 AS attemptCount,0 AS wrongCount FROM knowledge_point p LEFT JOIN question_bank b ON b.id=p.bank_id WHERE 1=1"; filter="p.title || ' ' || p.content || ' ' || coalesce(p.category,'') || ' ' || coalesce(p.tags,'')"; }
                case "question", "wrong" -> {
                    select="SELECT '"+t+"' AS type,q.id,substr(q.stem,1,200) AS title,q.bank_id AS parentId,substr(b.name,1,200) AS parentTitle,substr(q.stem,1,240) AS snippet,"
                            + ("wrong".equals(t) ? "COALESCE(agg.attempt_count,0) AS attemptCount,COALESCE(agg.wrong_count,0) AS wrongCount" : "0 AS attemptCount,0 AS wrongCount")
                            + " FROM question q JOIN question_bank b ON b.id=q.bank_id "
                            + ("wrong".equals(t) ? QuestionRepository.WRONG_MEMBERSHIP_SQL : "WHERE 1=1");
                    filter="q.stem || ' ' || coalesce(q.chapter,'') || ' ' || coalesce(q.tags,'')";
                }
                default -> throw new IllegalArgumentException();
            }
            // 目录所属容器与页面树的父节点分别返回，避免把 notebookId 当成页面父 ID。
            select = select.replace(" AS snippet,", " AS snippet," + ("note".equals(t) ? "p.parent_id" : "NULL") + " AS parentPageId,");
            if (!query.isBlank()) {
                select += " AND (" + filter + " LIKE ? ESCAPE '\\'"; args.add(pattern);
                if ("note".equals(t)) {
                    select += " OR EXISTS(SELECT 1 FROM retrieval_chunk r WHERE r.corpus_type='NOTEBOOK' AND r.source_id=p.id AND r.text LIKE ? ESCAPE '\\')";
                    args.add(pattern);
                }
                select += ")";
            }
            if (notebookId != null) { select += " AND " + ("notebook".equals(t) ? "id" : "p.notebook_id") + "=?"; args.add(notebookId); }
            if (bankId != null) { select += " AND " + ("bank".equals(t) ? "id" : "knowledge".equals(t) ? "p.bank_id" : "q.bank_id") + "=?"; args.add(bankId); }
            if (questionId != null) { select += " AND q.id=?"; args.add(questionId); }
            selects.add(select);
        }
        if (selects.isEmpty()) return List.of();
        args.add(limit); args.add(offset);
        return jdbc.queryForList("SELECT * FROM (" + String.join(" UNION ALL ", selects) + ") ORDER BY type,id LIMIT ? OFFSET ?", args.toArray());
    }
}
