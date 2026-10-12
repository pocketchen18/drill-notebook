package com.drillnotebook.app.memory;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.drillnotebook.app.config.DatabaseConfig;
import com.drillnotebook.app.config.PortablePathResolver;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/**
 * schema9 迁移与约束测试的夹具：v8 旧库重建、代表性旧数据与对象计数。
 *
 * <p>临时库目录由 {@link TestWorkspace} 提供，一律落在 APP_ROOT 的 tmp/ 下
 * （不用 @TempDir，避免把测试产物写进系统 %TEMP%）；DataSource 走生产的
 * {@link DatabaseConfig}，pragma 组合与线上完全一致。</p>
 */
public final class Schema9Fixture {
    private Schema9Fixture() {
    }

    /** 以便携根目录构造生产同款 DataSource（WAL + busy_timeout + 每连接 foreign_keys）。 */
    public static DataSource dataSource(Path appRoot) {
        return new DatabaseConfig().dataSource(resolver(appRoot));
    }

    /** 指向临时根目录的路径解析器，供 DatabaseInitializer 决定快照落点。 */
    public static PortablePathResolver resolver(Path appRoot) {
        PortablePathResolver paths = mock(PortablePathResolver.class);
        when(paths.root()).thenReturn(appRoot);
        when(paths.data()).thenReturn(appRoot.resolve("data"));
        when(paths.database()).thenReturn(appRoot.resolve("data/study.db"));
        return paths;
    }

    /** 用测试资源里冻结的 v8 建库脚本重建旧库，并把版本标成 8。 */
    public static JdbcTemplate createV8Database(DataSource dataSource) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("schema-v8.sql"));
        }
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.update("INSERT INTO schema_version(version) VALUES (8)");
        return jdbc;
    }

    /**
     * 写入一组有代表性的 v8 数据：笔记本、题库、加密/明文聊天消息、向量索引与空间。
     * 迁移测试据此断言旧内容一条不少、一字不改。
     */
    public static void seedV8Data(JdbcTemplate jdbc) {
        jdbc.update("INSERT INTO notebook(id, title) VALUES (1, '测试笔记本')");
        jdbc.update("INSERT INTO note_page(id, notebook_id, title, content, content_hash) VALUES (1, 1, '第一页', ?, 'hash-1')",
                "{\"type\":\"doc\",\"content\":[{\"type\":\"paragraph\",\"text\":\"中文正文\"}]}");
        jdbc.update("INSERT INTO note_page(id, notebook_id, title, content, content_hash) VALUES (2, 1, '第二页', ?, NULL)",
                "{\"type\":\"doc\",\"content\":[]}");

        jdbc.update("INSERT INTO question_bank(id, name) VALUES (1, '题库')");
        jdbc.update("INSERT INTO question(id, bank_id, type, stem, answer, content_hash) VALUES (1, 1, 'single', '什么是二分查找', 'B', 'q-1')");
        jdbc.update("INSERT INTO question(id, bank_id, type, stem, answer, content_hash) VALUES (2, 1, 'single', '最坏复杂度', 'O(log n)', 'q-2')");

        jdbc.update("INSERT INTO ai_chat_session(id, title) VALUES (1, '旧会话')");
        jdbc.update("INSERT INTO ai_chat_message(id, session_id, role, content, created_at) VALUES (1, 1, 'user', '旧明文消息', '2026-01-01 00:00:00')");
        jdbc.update("INSERT INTO ai_chat_message(id, session_id, role, content, content_cipher, content_meta, reasoning_cipher, reasoning_meta, created_at) "
                + "VALUES (2, 1, 'assistant', '', ?, ?, ?, ?, '2026-01-01 00:00:01')",
                "ZmFrZS1jaXBoZXI=", "{\"salt\":\"s\",\"iv\":\"aAA=\"}", "cmVhc29uaW5n", "{\"salt\":\"s\",\"iv\":\"bAA=\"}");
        jdbc.update("INSERT INTO ai_chat_message(id, session_id, role, content, created_at) VALUES (3, 1, 'user', '第二条旧消息', '2026-01-02 00:00:00')");

        jdbc.update("INSERT INTO retrieval_chunk(id, corpus_type, corpus_id, source_id, chunk_index, text, start_offset, end_offset, content_hash) "
                + "VALUES (1, 'NOTEBOOK', 1, 1, 0, '中文正文', 0, 4, 'chunk-1')");
        jdbc.update("INSERT INTO embedding_space(embedding_space_id, canonical_contract_json, provider_type, model_identifier, dimensions, state, coverage, is_selected) "
                + "VALUES ('space-1', '{}', 'local-rust', 'bge-small-zh-v1.5', 512, 'ACTIVE', 1.0, 1)");
        jdbc.update("INSERT INTO retrieval_embedding(chunk_id, corpus_type, embedding_space_id, dimensions, content_hash, vector_blob) "
                + "VALUES (1, 'NOTEBOOK', 'space-1', 512, 'chunk-1', ?)", new byte[512 * 4]);
    }

    /** schema9 新增表在 sqlite_master 中只应有一份。 */
    public static int objectCount(JdbcTemplate jdbc, String type, String name) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM sqlite_master WHERE type = ? AND name = ?",
                Integer.class, type, name);
        return count == null ? 0 : count;
    }
}
