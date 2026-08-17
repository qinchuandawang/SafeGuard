CREATE TABLE IF NOT EXISTS `conversation_message` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `message_id` VARCHAR(64) NOT NULL,
    `conversation_id` VARCHAR(128) NOT NULL,
    `role` VARCHAR(32) NOT NULL,
    `content` TEXT NOT NULL,
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE INDEX `uk_conversation_message_id` (`message_id`),
    INDEX `idx_conversation_message_recent` (`conversation_id`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='会话消息历史';
