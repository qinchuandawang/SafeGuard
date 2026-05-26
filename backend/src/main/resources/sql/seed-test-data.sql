-- ===============================================
-- SafeGuard 测试数据填充脚本
-- 使用方法: mysql -u root -p safeguard < seed-test-data.sql
-- 生成约 1200 条测试数据
-- ===============================================

USE safeguard;

-- ===============================================
-- 1. 知识库 (500 条)
-- ===============================================
DROP PROCEDURE IF EXISTS seed_knowledge;
DELIMITER //
CREATE PROCEDURE seed_knowledge()
BEGIN
    DECLARE i INT DEFAULT 1;
    DECLARE cat VARCHAR(50);
    WHILE i <= 500 DO
        CASE (i - 1) % 10
            WHEN 0 THEN SET cat = '冒充公检法';
            WHEN 1 THEN SET cat = '刷单诈骗';
            WHEN 2 THEN SET cat = '投资理财';
            WHEN 3 THEN SET cat = '冒充客服';
            WHEN 4 THEN SET cat = '杀猪盘';
            WHEN 5 THEN SET cat = 'AI诈骗';
            WHEN 6 THEN SET cat = '网络贷款';
            WHEN 7 THEN SET cat = '裸聊敲诈';
            WHEN 8 THEN SET cat = '钓鱼链接';
            WHEN 9 THEN SET cat = '社保诈骗';
        END CASE;
        INSERT INTO knowledge_item (question, answer, category, tags, priority, enabled)
        VALUES (
            CONCAT(cat, '相关问题（', i, '）'),
            CONCAT('关于"', cat, '"的详细解答第', i, '条：这是由SafeGuard反诈专家团队整理的防骗知识。', cat, '是当前高发的诈骗类型之一，受害者遍布全国各地。防范', cat, '的关键在于：不轻信、不转账、多核实。如遇到可疑情况，请及时拨打96110反诈热线咨询。'),
            cat,
            CONCAT(cat, ',防骗,安全'),
            5 + (i % 5),
            1
        );
        SET i = i + 1;
    END WHILE;
END //
DELIMITER ;
CALL seed_knowledge();
DROP PROCEDURE IF EXISTS seed_knowledge;

-- ===============================================
-- 2. 用户 (20 条)
-- ===============================================
INSERT INTO user (openid, nickname, avatar_url, role, last_login_at) VALUES
('test_user_001', '张三', '', 'user', NOW()),
('test_user_002', '李四', '', 'user', NOW()),
('test_user_003', '王五', '', 'user', NOW()),
('test_user_004', '赵六', '', 'user', NOW()),
('test_user_005', '孙七', '', 'user', NOW()),
('test_user_006', '周八', '', 'user', NOW()),
('test_user_007', '吴九', '', 'user', NOW()),
('test_user_008', '郑十', '', 'user', NOW()),
('test_user_009', '钱大爷', '', 'user', NOW()),
('test_user_010', '陈阿姨', '', 'user', NOW()),
('test_user_011', '林同学', '', 'user', NOW()),
('test_user_012', '黄老师', '', 'user', NOW()),
('test_user_013', '杨医生', '', 'user', NOW()),
('test_user_014', '刘会计', '', 'user', NOW()),
('test_user_015', '程序员小王', '', 'user', NOW()),
('test_user_016', '设计师小李', '', 'user', NOW()),
('test_user_017', '外卖小哥', '', 'user', NOW()),
('test_user_018', '退休老张', '', 'user', NOW()),
('test_user_019', '大学生小美', '', 'user', NOW()),
('test_user_020', '宝妈小芳', '', 'user', NOW());

-- ===============================================
-- 3. 音频检测记录 (500 条)
-- ===============================================
DROP PROCEDURE IF EXISTS seed_audio_records;
DELIMITER //
CREATE PROCEDURE seed_audio_records()
BEGIN
    DECLARE i INT DEFAULT 1;
    DECLARE taskId VARCHAR(64);
    DECLARE detectionResult VARCHAR(50);
    DECLARE riskLevel VARCHAR(20);
    DECLARE spoofProb DOUBLE;
    DECLARE statusVal VARCHAR(20);
    DECLARE userId BIGINT;
    WHILE i <= 500 DO
        SET taskId = CONCAT('audio_task_', LPAD(i, 5, '0'));
        IF i % 3 = 0 THEN
            SET detectionResult = 'spoof';
            SET spoofProb = 0.7 + RAND() * 0.29;
            IF spoofProb > 0.85 THEN SET riskLevel = 'high'; ELSE SET riskLevel = 'medium'; END IF;
        ELSE
            SET detectionResult = 'bonafide';
            SET spoofProb = RAND() * 0.4;
            SET riskLevel = 'low';
        END IF;
        IF i % 20 = 0 THEN SET statusVal = 'failed';
        ELSEIF i % 15 = 0 THEN SET statusVal = 'pending';
        ELSE SET statusVal = 'success';
        END IF;
        SET userId = 1 + (i % 20);
        INSERT INTO audio_detection_record (task_id, user_id, file_name, file_size, file_path, detection_result, spoof_probability, bonafide_probability, confidence, risk_level, model_version, detection_latency_ms, status)
        VALUES (taskId, userId, CONCAT('audio_', i, '.wav'), 50000 + (i * 100), CONCAT('/tmp/audio_', i, '.wav'), detectionResult, spoofProb, 1 - spoofProb, 0.85 + RAND() * 0.14, riskLevel, 'wav2vec2-v1.0', 80 + RAND() * 200, statusVal);
        SET i = i + 1;
    END WHILE;
END //
DELIMITER ;
CALL seed_audio_records();
DROP PROCEDURE IF EXISTS seed_audio_records;

-- ===============================================
-- 4. 综合检测记录 (200 条)
-- ===============================================
DROP PROCEDURE IF EXISTS seed_detection_records;
DELIMITER //
CREATE PROCEDURE seed_detection_records()
BEGIN
    DECLARE i INT DEFAULT 1;
    DECLARE taskId VARCHAR(64);
    DECLARE detType VARCHAR(20);
    DECLARE resultVal VARCHAR(20);
    DECLARE riskScore INT;
    DECLARE statusVal VARCHAR(20);
    DECLARE userId BIGINT;
    DECLARE typeIdx INT;
    WHILE i <= 200 DO
        SET taskId = CONCAT('detect_task_', LPAD(i, 5, '0'));
        SET typeIdx = (i - 1) % 4;
        IF typeIdx = 0 THEN SET detType = 'audio';
        ELSEIF typeIdx = 1 THEN SET detType = 'video';
        ELSEIF typeIdx = 2 THEN SET detType = 'text';
        ELSE SET detType = 'multimodal';
        END IF;
        SET riskScore = FLOOR(RAND() * 100);
        IF riskScore > 70 THEN SET resultVal = 'dangerous';
        ELSEIF riskScore > 40 THEN SET resultVal = 'suspicious';
        ELSE SET resultVal = 'safe';
        END IF;
        IF i % 25 = 0 THEN SET statusVal = 'failed'; ELSE SET statusVal = 'completed'; END IF;
        SET userId = 1 + (i % 20);
        INSERT INTO detection_record (task_id, user_id, detection_type, file_name, status, result, risk_score, spoof_probability, analysis_detail, processing_time_ms, completed_at)
        VALUES (taskId, userId, detType, CONCAT('file_', i, CASE detType WHEN 'audio' THEN '.wav' WHEN 'video' THEN '.mp4' ELSE '.txt' END),
                statusVal, resultVal, riskScore, riskScore / 100.0,
                CONCAT('{"analysis":"检测分析结果', i, '","riskLevel":"', resultVal, '","score":', riskScore, '}'),
                FLOOR(100 + RAND() * 5000),
                IF(statusVal = 'completed', NOW() - INTERVAL i MINUTE, NULL));
        SET i = i + 1;
    END WHILE;
END //
DELIMITER ;
CALL seed_detection_records();
DROP PROCEDURE IF EXISTS seed_detection_records;

-- ===============================================
-- 5. 音频模型 (10 条)
-- ===============================================
INSERT INTO audio_model (name, model_version, model_type, training_dataset, training_epochs, accuracy, eer, training_minutes, is_active, model_path, description) VALUES
('Wav2Vec2 Base 语音检测', 'v1.0.0', 'wav2vec2', 'ASVspoof2019 LA', 50, 0.985, 0.012, 120, 0, '/models/wav2vec2-v1', '初始版本，全量训练50轮'),
('Wav2Vec2 优化版', 'v1.1.0', 'wav2vec2', 'ASVspoof2019 LA', 80, 0.991, 0.008, 200, 0, '/models/wav2vec2-v1.1', '增加学习率衰减策略'),
('Wav2Vec2 大模型', 'v2.0.0', 'wav2vec2-large', 'ASVspoof2019 LA + 自采数据', 100, 0.994, 0.005, 360, 1, '/models/wav2vec2-v2', '当前生产模型，准确率最高'),
('LCNN 基线模型', '2021-baseline', 'lcnn', 'ASVspoof2019 LA', 100, 0.991, 0.009, 180, 0, '/models/lcnn-la', 'ASVspoof2019 LA赛道基线'),
('ResNet 音频检测', 'v1.0.0', 'resnet', 'ASVspoof2021', 60, 0.978, 0.015, 150, 0, '/models/resnet-v1', 'ResNet18 迁移学习实验'),
('Xception 音频', 'v1.0.0', 'xception', 'ASVspoof2019 LA', 45, 0.976, 0.018, 130, 0, '/models/xception-audio', 'Xception 架构实验'),
('Transformer 音频', 'v1.0.0', 'transformer', 'ASVspoof2019 LA', 30, 0.982, 0.011, 90, 0, '/models/transformer-audio', '纯 Transformer 编码器'),
('LCNN-Light', 'v1.0.0', 'lcnn', 'ASVspoof2019 LA', 80, 0.987, 0.010, 100, 0, '/models/lcnn-light', '轻量版 LCNN'),
('Wav2Vec2 多语种', 'v1.0.0', 'wav2vec2', 'ASVspoof2021 + CN', 70, 0.989, 0.009, 280, 0, '/models/wav2vec2-multi', '支持中英文混合检测'),
('Ensemble 集成模型', 'v1.0.0', 'ensemble', 'ASVspoof2019 LA', 100, 0.996, 0.003, 480, 0, '/models/ensemble-v1', 'Wav2Vec2+LCNN+ResNet 投票集成');

-- ===============================================
-- 6. 异步任务 (50 条)
-- ===============================================
DROP PROCEDURE IF EXISTS seed_async_tasks;
DELIMITER //
CREATE PROCEDURE seed_async_tasks()
BEGIN
    DECLARE i INT DEFAULT 1;
    DECLARE taskId VARCHAR(64);
    DECLARE taskType VARCHAR(20);
    DECLARE statusVal VARCHAR(20);
    DECLARE userId BIGINT;
    DECLARE typeIdx INT;
    WHILE i <= 50 DO
        SET taskId = CONCAT('async_task_', LPAD(i, 5, '0'));
        SET typeIdx = (i - 1) % 5;
        IF typeIdx = 0 THEN SET taskType = 'video';
        ELSEIF typeIdx = 1 THEN SET taskType = 'audio';
        ELSEIF typeIdx = 2 THEN SET taskType = 'video';
        ELSEIF typeIdx = 3 THEN SET taskType = 'audio';
        ELSE SET taskType = 'text';
        END IF;
        IF i <= 40 THEN SET statusVal = 'completed';
        ELSEIF i <= 45 THEN SET statusVal = 'failed';
        ELSE SET statusVal = 'processing';
        END IF;
        SET userId = 1 + (i % 20);
        INSERT INTO async_task (task_id, type, user_id, status, progress, result_json, file_path, completed_at)
        VALUES (taskId, taskType, userId, statusVal,
                IF(statusVal = 'completed', 100, IF(statusVal = 'failed', FLOOR(30 + RAND() * 50), FLOOR(10 + RAND() * 60))),
                IF(statusVal = 'completed', CONCAT('{"result":"success","taskType":"', taskType, '","score":', FLOOR(RAND() * 100), '}'), NULL),
                CONCAT('/tmp/upload_', i, '.', CASE WHEN taskType = 'video' THEN 'mp4' ELSE 'wav' END),
                IF(statusVal != 'processing', NOW() - INTERVAL i * 30 MINUTE, NULL));
        SET i = i + 1;
    END WHILE;
END //
DELIMITER ;
CALL seed_async_tasks();
DROP PROCEDURE IF EXISTS seed_async_tasks;

SELECT CONCAT('知识库条目: ', COUNT(*)) AS stats FROM knowledge_item
UNION ALL SELECT CONCAT('用户数: ', COUNT(*)) FROM user
UNION ALL SELECT CONCAT('音频检测记录: ', COUNT(*)) FROM audio_detection_record
UNION ALL SELECT CONCAT('综合检测记录: ', COUNT(*)) FROM detection_record
UNION ALL SELECT CONCAT('音频模型: ', COUNT(*)) FROM audio_model
UNION ALL SELECT CONCAT('异步任务: ', COUNT(*)) FROM async_task;
