package com.drillnotebook.app;

import com.drillnotebook.app.config.DatabaseInitializer;
import java.nio.file.Files;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.springframework.jdbc.UncategorizedSQLException;

class DatabaseInitializerTest {
    @Test
    void createsSchemaInConfiguredDatabase() throws Exception {
        var root = Files.createTempDirectory("drill-notebook-test");
        Files.createDirectories(root.resolve("data"));
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + root.resolve("data/study.db"));
        new DatabaseInitializer(dataSource).initialize();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        assertEquals(8, jdbc.queryForObject("SELECT version FROM schema_version", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = 'knowledge_point'", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = 'ai_chat_session'", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = 'study_plan_group'", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = 'study_plan_item'", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM pragma_table_info('answer_record') WHERE name = 'grading_status'", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM pragma_table_info('answer_record') WHERE name = 'grading_json'", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM pragma_table_info('ai_chat_message') WHERE name = 'content_cipher'", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM pragma_table_info('note_page') WHERE name = 'content_hash'", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = 'retrieval_chunk'", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = 'embedding_model'", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = 'embedding_space'", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = 'retrieval_embedding'", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = 'embedding_job'", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = 'retrieval_chunk_fts'", Integer.class));
        assertTrue(Files.exists(root.resolve("data/study.db")));
    }

    @Test
    void createsTrigramFtsAndMatchesChineseSubstring() throws Exception {
        var root = Files.createTempDirectory("drill-notebook-trigram-fts");
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + root.resolve("study.db"));
        new DatabaseInitializer(dataSource).initialize();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        jdbc.update(
                "INSERT INTO retrieval_chunk_fts(rowid, title, heading_path, text) VALUES (?, ?, ?, ?)",
                1, "中文向量检索", "人工智能", "这是用于验证笔记向量检索能力的正文");

        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM retrieval_chunk_fts WHERE retrieval_chunk_fts MATCH ?",
                Integer.class,
                "向量检"));
    }

    @Test
    void upgradesVersionTwoAnswerRecordsIdempotently() throws Exception {
        var root = Files.createTempDirectory("drill-notebook-migration-test");
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + root.resolve("study.db"));
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE schema_version(version INTEGER NOT NULL)");
        jdbc.update("INSERT INTO schema_version(version) VALUES (2)");
        jdbc.execute("CREATE TABLE answer_record(id INTEGER PRIMARY KEY, question_id INTEGER NOT NULL, user_answer TEXT, is_correct INTEGER, time_spent INTEGER, session_id TEXT, answered_at TEXT)");
        jdbc.update("INSERT INTO answer_record(id, question_id, user_answer, is_correct) VALUES (1, 9, 'A', 1)");
        DatabaseInitializer initializer = new DatabaseInitializer(dataSource);
        initializer.initialize();
        initializer.initialize();
        assertEquals(8, jdbc.queryForObject("SELECT version FROM schema_version", Integer.class));
        assertEquals("A", jdbc.queryForObject("SELECT user_answer FROM answer_record WHERE id = 1", String.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM pragma_table_info('answer_record') WHERE name = 'grading_status'", Integer.class));
    }

    @Test
    void upgradesLegacyAiChatMessagesIntoDefaultSession() throws Exception {
        var root = Files.createTempDirectory("drill-notebook-ai-session-migration");
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + root.resolve("study.db"));
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE schema_version(version INTEGER NOT NULL)");
        jdbc.update("INSERT INTO schema_version(version) VALUES (3)");
        jdbc.execute("CREATE TABLE ai_chat_message(id INTEGER PRIMARY KEY, role TEXT NOT NULL, content TEXT NOT NULL, created_at TEXT)");
        jdbc.update("INSERT INTO ai_chat_message(role, content) VALUES ('user', '旧消息')");
        new DatabaseInitializer(dataSource).initialize();
        assertEquals(8, jdbc.queryForObject("SELECT version FROM schema_version", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM ai_chat_session", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM ai_chat_message WHERE session_id IS NOT NULL AND content = '旧消息'", Integer.class));
    }

    @Test
    void upgradesV7ToV8AndPreservesNotebookData() throws Exception {
        var root = Files.createTempDirectory("drill-notebook-v7-migration");
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + root.resolve("study.db"));
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        // Create v7 schema with notebook data.
        // Pre-create the full note_page/notebook tables with data to verify preservation.
        // Let schema.sql create the remaining tables from scratch as IF NOT EXISTS.
        jdbc.execute("CREATE TABLE schema_version(version INTEGER NOT NULL)");
        jdbc.update("INSERT INTO schema_version(version) VALUES (7)");
        jdbc.execute("CREATE TABLE notebook(id INTEGER PRIMARY KEY AUTOINCREMENT, title TEXT NOT NULL, created_at TEXT, updated_at TEXT)");
        jdbc.execute("CREATE TABLE note_page(id INTEGER PRIMARY KEY AUTOINCREMENT, notebook_id INTEGER NOT NULL REFERENCES notebook(id) ON DELETE CASCADE, parent_id INTEGER, title TEXT, sort_order INTEGER DEFAULT 0, content TEXT, created_at TEXT, updated_at TEXT)");
        jdbc.update("INSERT INTO notebook(id, title) VALUES (1, '测试笔记本')");
        jdbc.update("INSERT INTO note_page(id, notebook_id, title, content) VALUES (1, 1, '测试页', '{\"type\":\"doc\",\"content\":[{\"type\":\"paragraph\",\"content\":[{\"type\":\"text\",\"text\":\"Hello\"}]}]}')");
        jdbc.update("INSERT INTO note_page(id, notebook_id, title, content) VALUES (2, 1, '第二页', '{\"type\":\"doc\",\"content\":[{\"type\":\"paragraph\",\"content\":[{\"type\":\"text\",\"text\":\"World\"}]}]}')");
        // Pre-create ai_config and ai_chat_message to test migrateAiConfigPurposes and migrateAiChatSessions.
        // Leave ai_chat_session to schema.sql (it needs full columns including updated_at for the index).
        jdbc.execute("CREATE TABLE ai_config(id INTEGER PRIMARY KEY, provider TEXT, endpoint TEXT, model TEXT)");
        jdbc.execute("CREATE TABLE ai_chat_message(id INTEGER PRIMARY KEY, role TEXT, content TEXT, created_at TEXT)");

        new DatabaseInitializer(dataSource).initialize();

        // Version bumped
        assertEquals(8, jdbc.queryForObject("SELECT version FROM schema_version", Integer.class));
        // Original data preserved
        assertEquals("测试页", jdbc.queryForObject("SELECT title FROM note_page WHERE id = 1", String.class));
        assertEquals("Hello", jdbc.queryForObject("SELECT content FROM note_page WHERE id = 1", String.class).replaceAll(".*\"text\":\"(.*?)\".*", "$1"));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM note_page", Integer.class));
        // New retrieval tables exist and empty
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM retrieval_chunk", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM retrieval_chunk_fts", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM embedding_model", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM embedding_space", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM retrieval_embedding", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM embedding_job", Integer.class));
        // Idempotent re-init
        new DatabaseInitializer(dataSource).initialize();
        assertEquals(8, jdbc.queryForObject("SELECT version FROM schema_version", Integer.class));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM note_page", Integer.class));
    }

    @Test
    void rejectsDimensionMismatchViaTrigger() throws Exception {
        var root = Files.createTempDirectory("drill-notebook-vector-constraint");
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + root.resolve("study.db"));
        new DatabaseInitializer(dataSource).initialize();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        // Insert valid model
        jdbc.update("INSERT INTO embedding_model(catalog_id, provider_model_id, artifact_revision, dimensions, installation_state) VALUES (?, ?, ?, ?, ?)",
                "bge-small-zh-v1.5", "Qdrant/bge-small-zh-v1.5", "46fbe35fd4374a00fee7de77dfddaeb6dd6a2c59", 512, "READY");
        // Insert valid space with 512 dimensions
        jdbc.update("INSERT INTO embedding_space(embedding_space_id, canonical_contract_json, provider_type, model_identifier, dimensions, state, is_selected) VALUES (?, ?, ?, ?, ?, ?, ?)",
                "space-512", "{}", "local-rust", "bge-small-zh-v1.5", 512, "ACTIVE", 1);
        // Insert a chunk
        jdbc.update("INSERT INTO retrieval_chunk(corpus_type, corpus_id, source_id, chunk_index, text, start_offset, end_offset, content_hash) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                "NOTEBOOK", 1, 1, 0, "test", 0, 4, "abc123");

        // Valid insert with matching dimensions succeeds
        jdbc.update("INSERT INTO retrieval_embedding(chunk_id, corpus_type, embedding_space_id, dimensions, content_hash, vector_blob) VALUES (?, ?, ?, ?, ?, ?)",
                1, "NOTEBOOK", "space-512", 512, "hash1", new byte[]{0, 0, 0, 0});
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM retrieval_embedding", Integer.class));

        // Mismatched dimensions (384 vs 512) must be rejected by the trigger
        assertThrows(UncategorizedSQLException.class, () ->
            jdbc.update("INSERT INTO retrieval_embedding(chunk_id, corpus_type, embedding_space_id, dimensions, content_hash, vector_blob) VALUES (?, ?, ?, ?, ?, ?)",
                    1, "NOTEBOOK", "space-512", 384, "hash2", new byte[]{0, 0, 0, 0})
        );
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM retrieval_embedding", Integer.class));
    }

    @Test
    void rejectsInvalidEmbeddingSpaceAndJobState() throws Exception {
        var root = Files.createTempDirectory("drill-notebook-space-constraints");
        org.sqlite.SQLiteConfig fkConfig = new org.sqlite.SQLiteConfig();
        fkConfig.enforceForeignKeys(true);
        SQLiteDataSource dataSource = new SQLiteDataSource(fkConfig);
        dataSource.setUrl("jdbc:sqlite:" + root.resolve("study.db"));
        new DatabaseInitializer(dataSource).initialize();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        assertThrows(UncategorizedSQLException.class, () ->
                jdbc.update("INSERT INTO embedding_space(embedding_space_id, canonical_contract_json, provider_type, model_identifier, dimensions, coverage, is_selected) VALUES (?, ?, ?, ?, ?, ?, ?)",
                        "bad-coverage", "{}", "local-rust", "model", 512, 1.1, 0));
        assertThrows(UncategorizedSQLException.class, () ->
                jdbc.update("INSERT INTO embedding_space(embedding_space_id, canonical_contract_json, provider_type, model_identifier, dimensions, coverage, is_selected) VALUES (?, ?, ?, ?, ?, ?, ?)",
                        "bad-selected", "{}", "local-rust", "model", 512, 0.0, 2));
        assertThrows(UncategorizedSQLException.class, () ->
                jdbc.update("INSERT INTO embedding_job(corpus_type, source_id, source_content_hash, embedding_space_id, reason, attempts) VALUES (?, ?, ?, ?, ?, ?)",
                        "NOTEBOOK", 1, "hash", "missing-space", "new", 0));
    }

    @Test
    void ensuresForeignKeyReferentialIntegrity() throws Exception {
        var root = Files.createTempDirectory("drill-notebook-fk-test");
        org.sqlite.SQLiteConfig fkConfig = new org.sqlite.SQLiteConfig();
        fkConfig.enforceForeignKeys(true);
        SQLiteDataSource dataSource = new SQLiteDataSource(fkConfig);
        dataSource.setUrl("jdbc:sqlite:" + root.resolve("study.db"));
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        new DatabaseInitializer(dataSource).initialize();

        // Insert required parent rows
        jdbc.update("INSERT INTO embedding_model(catalog_id, provider_model_id, artifact_revision, dimensions, installation_state) VALUES (?, ?, ?, ?, ?)",
                "bge-small-zh-v1.5", "Qdrant/bge-small-zh-v1.5", "rev1", 512, "READY");
        jdbc.update("INSERT INTO embedding_space(embedding_space_id, canonical_contract_json, provider_type, model_identifier, dimensions, state) VALUES (?, ?, ?, ?, ?, ?)",
                "space-1", "{}", "local-rust", "bge-small-zh-v1.5", 512, "ACTIVE");
        jdbc.update("INSERT INTO retrieval_chunk(corpus_type, corpus_id, source_id, chunk_index, text, start_offset, end_offset, content_hash) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                "NOTEBOOK", 1, 1, 0, "test", 0, 4, "hash1");

        // Valid insertion: matching chunk_id and embedding_space_id
        jdbc.update("INSERT INTO retrieval_embedding(chunk_id, corpus_type, embedding_space_id, dimensions, content_hash, vector_blob) VALUES (?, ?, ?, ?, ?, ?)",
                1, "NOTEBOOK", "space-1", 512, "hash1", new byte[]{0, 0, 0, 0});
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM retrieval_embedding", Integer.class));

        // Attempt orphan chunk_id should fail FK constraint
        assertThrows(UncategorizedSQLException.class, () ->
            jdbc.update("INSERT INTO retrieval_embedding(chunk_id, corpus_type, embedding_space_id, dimensions, content_hash, vector_blob) VALUES (?, ?, ?, ?, ?, ?)",
                    999, "NOTEBOOK", "space-1", 512, "hash2", new byte[]{0, 0, 0, 0})
        );

        // Attempt invalid embedding_space_id should fail FK constraint
        assertThrows(UncategorizedSQLException.class, () ->
            jdbc.update("INSERT INTO retrieval_embedding(chunk_id, corpus_type, embedding_space_id, dimensions, content_hash, vector_blob) VALUES (?, ?, ?, ?, ?, ?)",
                    1, "NOTEBOOK", "nonexistent-space", 512, "hash3", new byte[]{0, 0, 0, 0})
        );
    }
}
