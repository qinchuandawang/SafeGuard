-- ===============================================
-- SafeGuard 数据库表结构
-- 适用数据库：MySQL 8.0+ / H2 (MODE=MYSQL)
-- 创建时间：2026-05-20
-- 描述：防欺诈检测系统数据库表
-- ===============================================

-- 数据库由 JDBC URL 的 createDatabaseIfNotExist=true 自动创建
-- 手动执行时可取消注释以下两行：
-- CREATE DATABASE IF NOT EXISTS safeguard DEFAULT CHARACTER SET utf8mb4 DEFAULT COLLATE utf8mb4_unicode_ci;
-- USE safeguard;

-- ===============================================
-- 表1：knowledge_item - 知识库表
-- ===============================================
-- DROP TABLE IF EXISTS `knowledge_item`;  -- 生产环境禁止使用，会丢失数据
CREATE TABLE IF NOT EXISTS `knowledge_item` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `question` VARCHAR(500) NOT NULL COMMENT '问题/关键词',
    `answer` TEXT NOT NULL COMMENT '答案/回复内容',
    `category` VARCHAR(100) DEFAULT NULL COMMENT '分类',
    `tags` VARCHAR(255) DEFAULT NULL COMMENT '标签（逗号分隔）',
    `priority` INT DEFAULT 0 COMMENT '优先级',
    `enabled` TINYINT(1) DEFAULT 1 COMMENT '是否启用：0-禁用，1-启用',
    `deleted` TINYINT(1) DEFAULT 0 COMMENT '逻辑删除：0-未删除，1-已删除',
    `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    INDEX `idx_category` (`category`),
    INDEX `idx_question` (`question`(255)),
    INDEX `idx_enabled_deleted` (`enabled`, `deleted`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='知识库表';

-- ===============================================
-- 表2：audio_detection_record - 音频检测记录表
-- ===============================================
-- DROP TABLE IF EXISTS `audio_detection_record`;  -- 生产环境禁止使用
CREATE TABLE IF NOT EXISTS `audio_detection_record` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `task_id` VARCHAR(64) DEFAULT NULL COMMENT '任务ID',
    `user_id` BIGINT DEFAULT NULL COMMENT '用户ID',
    `file_name` VARCHAR(255) NOT NULL COMMENT '原始文件名',
    `file_size` BIGINT DEFAULT NULL COMMENT '文件大小（字节）',
    `file_path` VARCHAR(500) DEFAULT NULL COMMENT '文件存储路径',
    `detection_result` VARCHAR(50) NOT NULL COMMENT '检测结果：bonafide-真实，spoof-伪造',
    `spoof_probability` DOUBLE DEFAULT NULL COMMENT '伪造概率（0-1）',
    `bonafide_probability` DOUBLE DEFAULT NULL COMMENT '真实概率（0-1）',
    `confidence` DOUBLE DEFAULT NULL COMMENT '置信度（0-1）',
    `risk_level` VARCHAR(20) DEFAULT NULL COMMENT '风险等级：low-低，medium-中，high-高',
    `model_version` VARCHAR(100) DEFAULT NULL COMMENT '检测模型版本',
    `detection_latency_ms` DOUBLE DEFAULT NULL COMMENT '检测耗时（毫秒）',
    `status` VARCHAR(20) NOT NULL DEFAULT 'pending' COMMENT '检测状态：pending-待处理，success-成功，failed-失败',
    `error_message` TEXT DEFAULT NULL COMMENT '错误信息',
    `deleted` TINYINT(1) DEFAULT 0 COMMENT '逻辑删除：0-未删除，1-已删除',
    `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE INDEX `idx_task_id` (`task_id`),
    INDEX `idx_user_id` (`user_id`),
    INDEX `idx_detection_result` (`detection_result`),
    INDEX `idx_status` (`status`),
    INDEX `idx_created_at` (`created_at`),
    INDEX `idx_deleted` (`deleted`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='音频检测记录表';

-- ===============================================
-- 表3：detection_record - 综合检测记录表
-- ===============================================
-- DROP TABLE IF EXISTS `detection_record`;  -- 生产环境禁止使用
CREATE TABLE IF NOT EXISTS `detection_record` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `task_id` VARCHAR(64) NOT NULL COMMENT '任务ID',
    `user_id` BIGINT DEFAULT NULL COMMENT '用户ID',
    `detection_type` VARCHAR(50) NOT NULL COMMENT '检测类型：audio-音频，video-视频，text-文本，multimodal-多模态',
    `file_name` VARCHAR(255) DEFAULT NULL COMMENT '文件名',
    `file_path` VARCHAR(500) DEFAULT NULL COMMENT '文件存储路径',
    `status` VARCHAR(20) NOT NULL DEFAULT 'pending' COMMENT '检测状态：pending-待处理，processing-处理中，completed-完成，failed-失败',
    `result` VARCHAR(50) DEFAULT NULL COMMENT '检测结果：safe-安全，suspicious-可疑，dangerous-危险',
    `risk_score` INT DEFAULT NULL COMMENT '风险分数（0-100）',
    `spoof_probability` DOUBLE DEFAULT NULL COMMENT '伪造概率（0-1）',
    `analysis_detail` TEXT DEFAULT NULL COMMENT '详细分析结果（JSON格式）',
    `error_message` TEXT DEFAULT NULL COMMENT '错误信息',
    `processing_time_ms` BIGINT DEFAULT NULL COMMENT '检测耗时（毫秒）',
    `deleted` TINYINT(1) DEFAULT 0 COMMENT '逻辑删除：0-未删除，1-已删除',
    `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `completed_at` DATETIME DEFAULT NULL COMMENT '完成时间',
    `updated_at` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE INDEX `idx_task_id` (`task_id`),
    INDEX `idx_user_id` (`user_id`),
    INDEX `idx_detection_type` (`detection_type`),
    INDEX `idx_status` (`status`),
    INDEX `idx_result` (`result`),
    INDEX `idx_created_at` (`created_at`),
    INDEX `idx_deleted` (`deleted`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='综合检测记录表';

-- ===============================================
-- 表4：audio_model - 音频模型表
-- ===============================================
-- DROP TABLE IF EXISTS `audio_model`;  -- 生产环境禁止使用
CREATE TABLE IF NOT EXISTS `audio_model` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `name` VARCHAR(100) NOT NULL COMMENT '模型名称',
    `model_version` VARCHAR(100) NOT NULL COMMENT '模型版本/路径',
    `model_type` VARCHAR(50) NOT NULL COMMENT '模型类型：wav2vec2, xvect 等',
    `training_dataset` VARCHAR(255) DEFAULT NULL COMMENT '训练数据集',
    `training_epochs` INT DEFAULT NULL COMMENT '训练轮次',
    `accuracy` DOUBLE DEFAULT NULL COMMENT '准确率（0-1）',
    `eer` DOUBLE DEFAULT NULL COMMENT '等错误率（Equal Error Rate）',
    `training_minutes` INT DEFAULT NULL COMMENT '训练耗时（分钟）',
    `is_active` TINYINT(1) DEFAULT 0 COMMENT '是否为当前生产模型：0-否，1-是',
    `model_path` VARCHAR(500) DEFAULT NULL COMMENT '模型文件路径',
    `description` TEXT DEFAULT NULL COMMENT '模型描述',
    `deleted` TINYINT(1) DEFAULT 0 COMMENT '逻辑删除：0-未删除，1-已删除',
    `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    INDEX `idx_model_type` (`model_type`),
    INDEX `idx_is_active` (`is_active`),
    INDEX `idx_deleted` (`deleted`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='音频模型表';

-- ===============================================
-- 表5：user - 微信小程序用户表
-- ===============================================
CREATE TABLE IF NOT EXISTS `user` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `openid` VARCHAR(128) NOT NULL COMMENT '微信openid',
    `unionid` VARCHAR(128) DEFAULT NULL COMMENT '微信unionid',
    `nickname` VARCHAR(100) DEFAULT NULL COMMENT '用户昵称',
    `avatar_url` VARCHAR(500) DEFAULT NULL COMMENT '头像URL',
    `email` VARCHAR(100) DEFAULT NULL COMMENT '邮箱',
    `phone` VARCHAR(20) DEFAULT NULL COMMENT '手机号',
    `department` VARCHAR(100) DEFAULT NULL COMMENT '部门',
    `bio` VARCHAR(500) DEFAULT NULL COMMENT '个人简介',
    `role` VARCHAR(20) NOT NULL DEFAULT 'user' COMMENT '角色：user-普通用户，admin-管理员',
    `password_hash` VARCHAR(255) DEFAULT NULL COMMENT '管理员密码的BCrypt哈希',
    `last_login_at` DATETIME DEFAULT NULL COMMENT '最后登录时间',
    `deleted` TINYINT(1) DEFAULT 0 COMMENT '逻辑删除：0-未删除，1-已删除',
    `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE INDEX `idx_openid` (`openid`),
    INDEX `idx_role` (`role`),
    INDEX `idx_deleted` (`deleted`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户表';

-- ===============================================
-- 表6：async_task - 异步任务表
-- ===============================================
CREATE TABLE IF NOT EXISTS `async_task` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `task_id` VARCHAR(64) NOT NULL COMMENT '任务唯一ID',
    `type` VARCHAR(50) NOT NULL COMMENT '任务类型：video/audio/text/multimodal',
    `user_id` BIGINT DEFAULT NULL COMMENT '用户ID',
    `status` VARCHAR(20) NOT NULL DEFAULT 'queued' COMMENT '状态：queued/processing/waiting_review/completed/failed',
    `progress` INT DEFAULT 0 COMMENT '进度(0-100)',
    `result_json` TEXT DEFAULT NULL COMMENT '结果JSON',
    `error_message` TEXT DEFAULT NULL COMMENT '错误信息',
    `file_path` VARCHAR(500) DEFAULT NULL COMMENT '关联文件路径',
    `file_hash` CHAR(64) DEFAULT NULL COMMENT '上传文件SHA-256',
    `idempotency_key` VARCHAR(128) DEFAULT NULL COMMENT '请求幂等键',
    `model_id` VARCHAR(100) DEFAULT NULL COMMENT '任务固化模型ID',
    `object_key` VARCHAR(500) DEFAULT NULL COMMENT '对象存储Key',
    `storage_tier` VARCHAR(20) NOT NULL DEFAULT 'hot' COMMENT '任务结构化数据层级：hot/cold，媒体对象存储由object_key标识',
    `version` INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    `deleted` INT DEFAULT 0 COMMENT '逻辑删除',
    `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `completed_at` DATETIME DEFAULT NULL COMMENT '完成时间',
    `updated_at` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE INDEX `idx_task_id` (`task_id`),
    UNIQUE INDEX `uk_idempotency_key` (`idempotency_key`),
    INDEX `idx_user_id` (`user_id`),
    INDEX `idx_status` (`status`),
    INDEX `idx_created_at` (`created_at`),
    INDEX `idx_deleted` (`deleted`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='异步任务表';

-- ===============================================
-- 表7：mq_transaction_record - RocketMQ 事务消息本地事务记录
-- ===============================================
CREATE TABLE IF NOT EXISTS `mq_transaction_record` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `message_id` VARCHAR(64) NOT NULL COMMENT '业务消息ID',
    `task_id` VARCHAR(64) NOT NULL COMMENT '任务ID',
    `event_type` VARCHAR(64) NOT NULL COMMENT '事件类型',
    `transaction_status` VARCHAR(20) NOT NULL COMMENT '本地事务状态',
    `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    UNIQUE INDEX `uk_mq_transaction_message` (`message_id`),
    INDEX `idx_task_event` (`task_id`, `event_type`),
    INDEX `idx_mq_transaction_created` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='RocketMQ事务消息本地事务记录';

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

CREATE TABLE IF NOT EXISTS `memory_fact_event` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `event_id` VARCHAR(64) NOT NULL,
    `session_id` VARCHAR(255) NOT NULL,
    `owner_key` CHAR(64) NOT NULL,
    `fact_key` VARCHAR(128) NOT NULL,
    `fact_type` VARCHAR(64) NOT NULL,
    `fact_value` VARCHAR(512) NOT NULL,
    `content` TEXT NOT NULL,
    `summary` VARCHAR(512) DEFAULT NULL,
    `source` VARCHAR(32) NOT NULL,
    `confidence` DECIMAL(5,4) NOT NULL DEFAULT 0,
    `importance` DECIMAL(5,4) NOT NULL DEFAULT 0,
    `version` INT NOT NULL,
    `status` VARCHAR(16) NOT NULL,
    `supersedes_event_id` VARCHAR(64) DEFAULT NULL,
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE INDEX `uk_memory_fact_event` (`event_id`),
    UNIQUE INDEX `uk_memory_fact_version` (`owner_key`, `fact_key`, `version`),
    INDEX `idx_memory_fact_active` (`owner_key`, `fact_key`, `status`),
    INDEX `idx_memory_fact_created` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='长期记忆事实事件与版本表';

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
