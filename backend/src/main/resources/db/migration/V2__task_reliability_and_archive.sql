ALTER TABLE `async_task`
    ADD COLUMN `file_hash` CHAR(64) DEFAULT NULL COMMENT '上传文件SHA-256',
    ADD COLUMN `idempotency_key` VARCHAR(128) DEFAULT NULL COMMENT '请求幂等键',
    ADD COLUMN `model_id` VARCHAR(100) DEFAULT NULL COMMENT '任务固化模型ID',
    ADD COLUMN `object_key` VARCHAR(500) DEFAULT NULL COMMENT '对象存储Key',
    ADD COLUMN `storage_tier` VARCHAR(20) NOT NULL DEFAULT 'hot' COMMENT '任务结构化数据层级，媒体对象存储由object_key标识',
    ADD COLUMN `version` INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    ADD UNIQUE INDEX `uk_idempotency_key` (`idempotency_key`);

CREATE TABLE IF NOT EXISTS `consumed_message` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `consumer_group` VARCHAR(128) NOT NULL,
    `message_id` VARCHAR(64) NOT NULL,
    `task_id` VARCHAR(64) DEFAULT NULL,
    `status` VARCHAR(20) NOT NULL DEFAULT 'PROCESSING',
    `last_error` VARCHAR(1000) DEFAULT NULL,
    `consumed_at` DATETIME DEFAULT NULL,
    `deleted` TINYINT(1) NOT NULL DEFAULT 0,
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE INDEX `uk_consumer_message` (`consumer_group`, `message_id`),
    INDEX `idx_consumed_task` (`task_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='MQ消费幂等表';

CREATE TABLE IF NOT EXISTS `detection_record_archive` LIKE `detection_record`;
