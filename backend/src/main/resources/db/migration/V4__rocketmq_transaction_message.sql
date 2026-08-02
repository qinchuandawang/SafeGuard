CREATE TABLE IF NOT EXISTS `mq_transaction_record` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `message_id` VARCHAR(64) NOT NULL,
    `task_id` VARCHAR(64) NOT NULL,
    `event_type` VARCHAR(64) NOT NULL,
    `transaction_status` VARCHAR(20) NOT NULL,
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE INDEX `uk_mq_transaction_message` (`message_id`),
    INDEX `idx_mq_transaction_task_event` (`task_id`, `event_type`),
    INDEX `idx_mq_transaction_created` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='RocketMQ事务消息本地事务记录';

DROP TABLE IF EXISTS `task_event_outbox`;
