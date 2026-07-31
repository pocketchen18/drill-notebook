package com.drillnotebook.app.config;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
class DatabaseInitializerTest {
    @Autowired
    private DataSource dataSource;

    @Test
    void noteAttachmentTableExists() throws Exception {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                     "SELECT name FROM sqlite_master WHERE type='table' AND name='note_attachment'")) {
            assertTrue(result.next(), "note_attachment 表应该存在");
        }
    }

    @Test
    void attachmentPageIndexExists() throws Exception {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                     "SELECT name FROM sqlite_master WHERE type='index' AND name='idx_attachment_page'")) {
            assertTrue(result.next(), "idx_attachment_page 索引应该存在");
        }
    }
}
