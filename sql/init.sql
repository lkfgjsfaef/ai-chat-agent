CREATE DATABASE IF NOT EXISTS ai_chat DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

USE ai_chat;

CREATE TABLE IF NOT EXISTS `user` (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    username VARCHAR(50) NOT NULL UNIQUE,
    password VARCHAR(100) NOT NULL,
    nickname VARCHAR(50),
    avatar_url VARCHAR(255),
    bio VARCHAR(500),
    theme VARCHAR(10) DEFAULT 'dark',
    role VARCHAR(20) DEFAULT 'user',
    total_tokens BIGINT DEFAULT 0,
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS chat_session (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    title VARCHAR(200),
    system_prompt TEXT,
    skill_id VARCHAR(64),
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_user_id (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS chat_message (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    session_id BIGINT NOT NULL,
    role VARCHAR(20) NOT NULL,
    content TEXT NOT NULL,
    feedback VARCHAR(10),
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_session_id (session_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS chat_summary (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    session_id BIGINT NOT NULL UNIQUE,
    summary TEXT,
    last_summarized_message_id BIGINT DEFAULT NULL,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_session_id (session_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS chat_attachment (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    session_id BIGINT,
    user_id BIGINT,
    message_id BIGINT,
    scope VARCHAR(20) DEFAULT 'session',
    vector_doc_ids LONGTEXT,
    file_name VARCHAR(255),
    file_path VARCHAR(500),
    file_size BIGINT,
    file_type VARCHAR(100),
    extracted_text LONGTEXT,
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_chat_attachment_session_id (session_id),
    INDEX idx_chat_attachment_user_scope (user_id, scope),
    INDEX idx_chat_attachment_scope (scope)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS session_tag (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    name VARCHAR(50) NOT NULL,
    color VARCHAR(20) DEFAULT '#818cf8',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_user_id (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS session_tag_relation (
    session_id BIGINT NOT NULL,
    tag_id BIGINT NOT NULL,
    PRIMARY KEY (session_id, tag_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS prompt_template (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    title VARCHAR(100) NOT NULL,
    content TEXT NOT NULL,
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO prompt_template (title, content) VALUES
('代码检查', '请帮我检查以下代码，指出潜在的Bug、性能问题和不符合规范的地方，并给出改进建议：\n\n'),
('代码解释', '请逐行解释以下代码的逻辑和作用：\n\n'),
('中英翻译', '请将以下内容翻译成英文（如果是英文请翻译成中文），要求表达地道、专业：\n\n');

CREATE TABLE IF NOT EXISTS model_config (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    model_name VARCHAR(50) NOT NULL,
    api_key VARCHAR(255),
    endpoint VARCHAR(255),
    status INT DEFAULT 1,
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO model_config (model_name, api_key, endpoint, status) VALUES
('glm-4.7', 'b54f75e62d5345f099aa51764019a92e.ET6EQT55100IcCM0', 'https://open.bigmodel.cn/api/coding/paas/v4/chat/completions', 1),
('glm-4.6v', 'b54f75e62d5345f099aa51764019a92e.ET6EQT55100IcCM0', 'https://open.bigmodel.cn/api/coding/paas/v4/chat/completions', 1),
('glm-4.5-air', 'b54f75e62d5345f099aa51764019a92e.ET6EQT55100IcCM0', 'https://open.bigmodel.cn/api/coding/paas/v4/chat/completions', 1),
('deepseek', '', 'https://api.deepseek.com/v1/chat/completions', 0),
('openai', '', 'https://api.openai.com/v1/chat/completions', 0);
