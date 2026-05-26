-- ===============================================
-- SafeGuard 初始化数据（条件插入，可重复执行）
-- ===============================================

-- 知识库基础数据（仅当表为空时插入）
INSERT INTO `knowledge_item` (`question`, `answer`, `category`, `tags`, `priority`, `enabled`)
SELECT '什么是AI换脸诈骗', 'AI换脸诈骗是指利用深度学习技术（如DeepFaceLab、FaceSwap等）将受害者的面部特征替换到其他视频中，制作虚假的视频内容进行诈骗。常见形式包括：冒充熟人借钱、冒充明星代言、伪造视频证据等。', 'AI诈骗', 'AI换脸,深度伪造,诈骗', 10, 1
WHERE NOT EXISTS (SELECT 1 FROM `knowledge_item` LIMIT 1);

INSERT INTO `knowledge_item` (`question`, `answer`, `category`, `tags`, `priority`, `enabled`)
SELECT '如何识别AI合成的语音', '识别AI合成语音的方法：1）注意语音的自然度和情感表达；2）检查背景噪音是否自然；3）观察语音与口型是否同步；4）询问只有双方知道的信息进行验证；5）使用专业的音频检测工具进行检测。', 'AI诈骗', '语音合成,AI识别,防骗', 10, 1
WHERE (SELECT COUNT(*) FROM `knowledge_item`) < 2;

INSERT INTO `knowledge_item` (`question`, `answer`, `category`, `tags`, `priority`, `enabled`)
SELECT '什么是语音克隆诈骗', '语音克隆诈骗是指通过获取目标的少量语音样本，使用AI技术克隆其声音，然后利用克隆的声音进行诈骗。骗子通常会冒充亲友、公司领导等，以紧急情况为由要求转账。', 'AI诈骗', '语音克隆,AI诈骗,紧急转账', 10, 1
WHERE (SELECT COUNT(*) FROM `knowledge_item`) < 3;

INSERT INTO `knowledge_item` (`question`, `answer`, `category`, `tags`, `priority`, `enabled`)
SELECT '遇到紧急转账要求怎么办', '遇到紧急转账要求时：1）保持冷静，不要被催促；2）通过其他渠道（如打电话、视频通话）核实对方身份；3）不要轻信对方提供的电话号码；4）与家人朋友商量；5）必要时报警处理。', '防骗技巧', '紧急转账,防骗,核实身份', 10, 1
WHERE (SELECT COUNT(*) FROM `knowledge_item`) < 4;

INSERT INTO `knowledge_item` (`question`, `answer`, `category`, `tags`, `priority`, `enabled`)
SELECT '如何保护个人信息', '保护个人信息的方法：1）不要在社交媒体公开过多个人信息；2）谨慎对待陌生来电和短信；3）定期更换密码，使用双因素认证；4）不要随意连接公共WiFi；5）安装正规的安全软件。', '安全防护', '个人信息,隐私保护,安全', 8, 1
WHERE (SELECT COUNT(*) FROM `knowledge_item`) < 5;

-- 音频模型基础数据（仅当表为空时插入）
INSERT INTO `audio_model` (`name`, `model_version`, `model_type`, `training_dataset`, `training_epochs`, `accuracy`, `eer`, `training_minutes`, `is_active`, `model_path`, `description`)
SELECT 'Wav2Vec2 语音伪造检测模型', 'v1.0.0', 'wav2vec2', 'ASVspoof2019 LA', 50, 0.985, 0.012, 120, 1, '/models/wav2vec2-spoof-detector', '基于Wav2Vec2的语音伪造检测模型，在ASVspoof2019 LA数据集上训练'
WHERE NOT EXISTS (SELECT 1 FROM `audio_model` LIMIT 1);

INSERT INTO `audio_model` (`name`, `model_version`, `model_type`, `training_dataset`, `training_epochs`, `accuracy`, `eer`, `training_minutes`, `is_active`, `model_path`, `description`)
SELECT 'LA模型 EER', '2021', 'lcnn', 'ASVspoof2019 LA', 100, 0.991, 0.009, 180, 0, '/models/lcnn-la-model', 'ASVspoof2019 LA赛道官方基线模型'
WHERE (SELECT COUNT(*) FROM `audio_model`) < 2;

-- 管理员用户（仅当 openid 不存在时插入）
INSERT INTO `user` (`openid`, `nickname`, `avatar_url`, `role`, `last_login_at`)
SELECT 'admin_default', '管理员', '', 'admin', NOW()
WHERE NOT EXISTS (SELECT 1 FROM `user` WHERE `openid` = 'admin_default');
