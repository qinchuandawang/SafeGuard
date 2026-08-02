CREATE TABLE IF NOT EXISTS `llm_usage_record` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `request_id` VARCHAR(64) NOT NULL,
    `scene` VARCHAR(64) NOT NULL,
    `model` VARCHAR(100) DEFAULT NULL,
    `prompt_tokens` INT NOT NULL DEFAULT 0,
    `completion_tokens` INT NOT NULL DEFAULT 0,
    `total_tokens` INT NOT NULL DEFAULT 0,
    `cache_hit` TINYINT(1) NOT NULL DEFAULT 0,
    `status` VARCHAR(32) NOT NULL,
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE INDEX `uk_llm_usage_request` (`request_id`),
    INDEX `idx_llm_usage_created_scene` (`created_at`, `scene`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='大模型 Token 用量账本';

CREATE INDEX `idx_outbox_status_updated`
    ON `task_event_outbox` (`status`, `updated_at`);
