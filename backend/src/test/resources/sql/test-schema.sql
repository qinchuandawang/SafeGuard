-- H2 兼容的测试用建表脚本（不含 CREATE DATABASE/USE）
-- 表结构与 schema.sql 一致，仅移除 MySQL 专属语句

DROP TABLE IF EXISTS `knowledge_item`;
CREATE TABLE `knowledge_item` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `question` VARCHAR(500) NOT NULL,
    `answer` TEXT NOT NULL,
    `category` VARCHAR(100) DEFAULT NULL,
    `tags` VARCHAR(255) DEFAULT NULL,
    `priority` INT DEFAULT 0,
    `enabled` INT DEFAULT 1,
    `deleted` INT DEFAULT 0,
    `created_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    `updated_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`)
);

DROP TABLE IF EXISTS `audio_detection_record`;
CREATE TABLE `audio_detection_record` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `task_id` VARCHAR(64) DEFAULT NULL,
    `user_id` BIGINT DEFAULT NULL,
    `file_name` VARCHAR(255) NOT NULL,
    `file_size` BIGINT DEFAULT NULL,
    `file_path` VARCHAR(500) DEFAULT NULL,
    `detection_result` VARCHAR(50) NOT NULL,
    `spoof_probability` DOUBLE DEFAULT NULL,
    `bonafide_probability` DOUBLE DEFAULT NULL,
    `confidence` DOUBLE DEFAULT NULL,
    `risk_level` VARCHAR(20) DEFAULT NULL,
    `model_version` VARCHAR(100) DEFAULT NULL,
    `detection_latency_ms` DOUBLE DEFAULT NULL,
    `status` VARCHAR(20) NOT NULL DEFAULT 'pending',
    `error_message` TEXT DEFAULT NULL,
    `deleted` INT DEFAULT 0,
    `created_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    `updated_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`)
);

DROP TABLE IF EXISTS `detection_record`;
CREATE TABLE `detection_record` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `task_id` VARCHAR(64) NOT NULL,
    `user_id` BIGINT DEFAULT NULL,
    `detection_type` VARCHAR(50) NOT NULL,
    `file_name` VARCHAR(255) DEFAULT NULL,
    `file_path` VARCHAR(500) DEFAULT NULL,
    `status` VARCHAR(20) NOT NULL DEFAULT 'pending',
    `result` VARCHAR(50) DEFAULT NULL,
    `risk_score` INT DEFAULT NULL,
    `spoof_probability` DOUBLE DEFAULT NULL,
    `analysis_detail` TEXT DEFAULT NULL,
    `error_message` TEXT DEFAULT NULL,
    `processing_time_ms` BIGINT DEFAULT NULL,
    `deleted` INT DEFAULT 0,
    `created_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    `completed_at` TIMESTAMP DEFAULT NULL,
    `updated_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`)
);

DROP TABLE IF EXISTS `audio_model`;
CREATE TABLE `audio_model` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `name` VARCHAR(100) NOT NULL,
    `model_version` VARCHAR(100) NOT NULL,
    `model_type` VARCHAR(50) NOT NULL,
    `training_dataset` VARCHAR(255) DEFAULT NULL,
    `training_epochs` INT DEFAULT NULL,
    `accuracy` DOUBLE DEFAULT NULL,
    `eer` DOUBLE DEFAULT NULL,
    `training_minutes` INT DEFAULT NULL,
    `is_active` INT DEFAULT 0,
    `model_path` VARCHAR(500) DEFAULT NULL,
    `description` TEXT DEFAULT NULL,
    `deleted` INT DEFAULT 0,
    `created_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    `updated_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`)
);

DROP TABLE IF EXISTS `user`;
CREATE TABLE `user` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `openid` VARCHAR(128) NOT NULL,
    `unionid` VARCHAR(128) DEFAULT NULL,
    `nickname` VARCHAR(100) DEFAULT NULL,
    `avatar_url` VARCHAR(500) DEFAULT NULL,
    `role` VARCHAR(20) NOT NULL DEFAULT 'user',
    `password_hash` VARCHAR(255) DEFAULT NULL,
    `email` VARCHAR(100) DEFAULT NULL,
    `phone` VARCHAR(20) DEFAULT NULL,
    `department` VARCHAR(100) DEFAULT NULL,
    `bio` VARCHAR(500) DEFAULT NULL,
    `last_login_at` TIMESTAMP DEFAULT NULL,
    `deleted` INT DEFAULT 0,
    `created_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    `updated_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`)
);

DROP TABLE IF EXISTS `async_task`;
CREATE TABLE `async_task` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `task_id` VARCHAR(64) NOT NULL,
    `type` VARCHAR(50) NOT NULL,
    `user_id` BIGINT DEFAULT NULL,
    `status` VARCHAR(20) NOT NULL DEFAULT 'processing',
    `progress` INT DEFAULT 0,
    `result_json` TEXT DEFAULT NULL,
    `error_message` TEXT DEFAULT NULL,
    `file_path` VARCHAR(500) DEFAULT NULL,
    `deleted` INT DEFAULT 0,
    `created_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    `completed_at` TIMESTAMP DEFAULT NULL,
    `updated_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`)
);

-- 初始化测试数据（5 条知识库 + 2 个音频模型 + 1 个管理员用户）
INSERT INTO `knowledge_item` (`question`, `answer`, `category`, `tags`, `priority`, `enabled`) VALUES
('什么是AI换脸诈骗', 'AI换脸诈骗是指利用深度学习技术将受害者的面部特征替换到其他视频中...', 'AI诈骗', 'AI换脸,深度伪造', 10, 1),
('如何识别AI合成的语音', '识别AI合成语音的方法：1）注意语音的自然度和情感表达...', 'AI诈骗', '语音合成,AI识别', 10, 1),
('遇到紧急转账要求怎么办', '遇到紧急转账要求时：1）保持冷静，不要被催促...', '防骗技巧', '紧急转账', 10, 1),
('公检法诈骗识别方法', '公检法不会通过电话办案，不会要求转账到安全账户，不会要求提供验证码。', '冒充公检法', '公检法,安全账户,验证码', 10, 1),
('AI换脸视频诈骗案例', '诈骗分子使用AI换脸技术冒充领导或熟人，以紧急情况为由要求转账汇款。', 'AI诈骗', 'AI换脸,冒充熟人,转账', 10, 1);

INSERT INTO `audio_model` (`name`, `model_version`, `model_type`, `training_dataset`, `training_epochs`, `accuracy`, `eer`, `is_active`, `description`) VALUES
('Wav2Vec2 语音伪造检测模型', 'v1.0.0', 'wav2vec2', 'ASVspoof2019 LA', 50, 0.985, 0.012, 1, '基于Wav2Vec2的语音伪造检测模型'),
('LA模型 EER', '2021', 'lcnn', 'ASVspoof2019 LA', 100, 0.991, 0.009, 0, 'ASVspoof2019 LA赛道官方基线模型');

INSERT INTO `user` (`openid`, `nickname`, `role`, `password_hash`, `last_login_at`) VALUES
('admin_default', '管理员', 'admin', '$2b$10$8t70ROuVfMOGoA1/wC0qIeeFxqhO.3b2UXXNLzu42wgmrZJpVDnfW', CURRENT_TIMESTAMP);
