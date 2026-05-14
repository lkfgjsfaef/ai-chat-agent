package com.niit.agent.config;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class DbInitializer {

    private final JdbcTemplate jdbcTemplate;

    @PostConstruct
    public void init() {
        try {
            jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS chat_attachment (" +
                    "id BIGINT AUTO_INCREMENT PRIMARY KEY," +
                    "session_id BIGINT," +
                    "user_id BIGINT," +
                    "message_id BIGINT," +
                    "scope VARCHAR(20) DEFAULT 'session'," +
                    "vector_doc_ids LONGTEXT," +
                    "file_name VARCHAR(255)," +
                    "file_path VARCHAR(500)," +
                    "file_size BIGINT," +
                    "file_type VARCHAR(100)," +
                    "extracted_text LONGTEXT," +
                    "create_time DATETIME," +
                    "INDEX idx_chat_attachment_session_id (session_id)," +
                    "INDEX idx_chat_attachment_user_scope (user_id, scope)," +
                    "INDEX idx_chat_attachment_scope (scope)" +
                    ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;");
            log.info("数据库表 chat_attachment 初始化完成");

            ensureColumnExists("chat_attachment", "user_id",
                    "ALTER TABLE `chat_attachment` ADD COLUMN `user_id` BIGINT DEFAULT NULL AFTER `session_id`;",
                    "成功为 chat_attachment 表添加 user_id 字段");
            ensureColumnExists("chat_attachment", "scope",
                    "ALTER TABLE `chat_attachment` ADD COLUMN `scope` VARCHAR(20) DEFAULT 'session' AFTER `message_id`;",
                    "成功为 chat_attachment 表添加 scope 字段");
            ensureColumnExists("chat_attachment", "vector_doc_ids",
                    "ALTER TABLE `chat_attachment` ADD COLUMN `vector_doc_ids` LONGTEXT AFTER `scope`;",
                    "成功为 chat_attachment 表添加 vector_doc_ids 字段");
            ensureIndexExists("chat_attachment", "idx_chat_attachment_session_id",
                    "ALTER TABLE `chat_attachment` ADD INDEX `idx_chat_attachment_session_id` (`session_id`);",
                    "成功为 chat_attachment 表添加 session_id 索引");
            ensureIndexExists("chat_attachment", "idx_chat_attachment_user_scope",
                    "ALTER TABLE `chat_attachment` ADD INDEX `idx_chat_attachment_user_scope` (`user_id`, `scope`);",
                    "成功为 chat_attachment 表添加 user_id_scope 索引");
            ensureIndexExists("chat_attachment", "idx_chat_attachment_scope",
                    "ALTER TABLE `chat_attachment` ADD INDEX `idx_chat_attachment_scope` (`scope`);",
                    "成功为 chat_attachment 表添加 scope 索引");
            
            // 兼容低版本 MySQL: 捕获由于不支持 IF NOT EXISTS 或字段已存在抛出的异常
            try {
                String createChatSessionSql = "CREATE TABLE IF NOT EXISTS `chat_session` (" +
                        "`id` BIGINT PRIMARY KEY AUTO_INCREMENT," +
                        "`user_id` BIGINT NOT NULL," +
                        "`title` VARCHAR(200)," +
                        "`system_prompt` TEXT," +
                        "`skill_id` VARCHAR(64)," +
                        "`create_time` DATETIME DEFAULT CURRENT_TIMESTAMP," +
                        "INDEX `idx_user_id` (`user_id`)" +
                        ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;";
                jdbcTemplate.execute(createChatSessionSql);
                ensureColumnExists("chat_session", "skill_id",
                        "ALTER TABLE `chat_session` ADD COLUMN `skill_id` VARCHAR(64) DEFAULT NULL AFTER `system_prompt`;",
                        "成功为 chat_session 表添加 skill_id 字段");
            } catch (Exception sessionEx) {
                log.warn("检查或初始化 chat_session 表失败: {}", sessionEx.getMessage());
            }

            try {
                // 检查字段是否存在
                String checkSql = "SELECT COUNT(*) FROM information_schema.COLUMNS WHERE table_schema = DATABASE() AND table_name = 'user' AND column_name = 'role'";
                Integer count = jdbcTemplate.queryForObject(checkSql, Integer.class);
                if (count == null || count == 0) {
                    jdbcTemplate.execute("ALTER TABLE `user` ADD COLUMN `role` VARCHAR(20) DEFAULT 'user' AFTER `theme`;");
                    log.info("成功为 user 表添加 role 字段");
                } else {
                    log.info("user 表已存在 role 字段");
                }
            } catch (Exception alterEx) {
                log.warn("检查或更新 user 表 role 字段失败 (可能已存在): {}", alterEx.getMessage());
            }

            try {
                String checkSummaryColumnSql = "SELECT COUNT(*) FROM information_schema.COLUMNS WHERE table_schema = DATABASE() AND table_name = 'chat_summary' AND column_name = 'last_summarized_message_id'";
                Integer summaryColumnCount = jdbcTemplate.queryForObject(checkSummaryColumnSql, Integer.class);
                if (summaryColumnCount == null || summaryColumnCount == 0) {
                    jdbcTemplate.execute("ALTER TABLE `chat_summary` ADD COLUMN `last_summarized_message_id` BIGINT DEFAULT NULL AFTER `summary`;");
                    log.info("成功为 chat_summary 表添加 last_summarized_message_id 字段");
                } else {
                    log.info("chat_summary 表已存在 last_summarized_message_id 字段");
                }
            } catch (Exception summaryAlterEx) {
                log.warn("检查或更新 chat_summary 表 last_summarized_message_id 字段失败 (可能已存在): {}", summaryAlterEx.getMessage());
            }

            ensureColumnExists("chat_message", "rag_chunk_ids",
                    "ALTER TABLE `chat_message` ADD COLUMN `rag_chunk_ids` TEXT AFTER `feedback`;",
                    "成功为 chat_message 表添加 rag_chunk_ids 字段");
            ensureColumnExists("chat_message", "rag_chunk_scores",
                    "ALTER TABLE `chat_message` ADD COLUMN `rag_chunk_scores` TEXT AFTER `rag_chunk_ids`;",
                    "成功为 chat_message 表添加 rag_chunk_scores 字段");

        } catch (Exception e) {
            log.error("初始化数据表异常", e);
        }
    }

    private void ensureColumnExists(String tableName, String columnName, String alterSql, String successLog) {
        try {
            String checkSql = "SELECT COUNT(*) FROM information_schema.COLUMNS WHERE table_schema = DATABASE() AND table_name = ? AND column_name = ?";
            Integer count = jdbcTemplate.queryForObject(checkSql, Integer.class, tableName, columnName);
            if (count == null || count == 0) {
                jdbcTemplate.execute(alterSql);
                log.info(successLog);
            } else {
                log.info("{} 表已存在 {} 字段", tableName, columnName);
            }
        } catch (Exception e) {
            log.warn("检查或更新 {} 表 {} 字段失败 (可能已存在): {}", tableName, columnName, e.getMessage());
        }
    }

    private void ensureIndexExists(String tableName, String indexName, String alterSql, String successLog) {
        try {
            String checkSql = "SELECT COUNT(*) FROM information_schema.STATISTICS WHERE table_schema = DATABASE() AND table_name = ? AND index_name = ?";
            Integer count = jdbcTemplate.queryForObject(checkSql, Integer.class, tableName, indexName);
            if (count == null || count == 0) {
                jdbcTemplate.execute(alterSql);
                log.info(successLog);
            } else {
                log.info("{} 表已存在 {} 索引", tableName, indexName);
            }
        } catch (Exception e) {
            log.warn("检查或更新 {} 表 {} 索引失败 (可能已存在): {}", tableName, indexName, e.getMessage());
        }
    }
}
