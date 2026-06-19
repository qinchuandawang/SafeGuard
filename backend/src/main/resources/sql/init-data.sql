-- ===============================================
-- SafeGuard 初始化数据（条件插入，可重复执行）
-- ===============================================

-- 兼容升级：为旧版本数据库补上 password_hash 列
-- 注意：schema.sql 已包含此列，新建库无需执行；旧库升级时手动取消注释
-- ALTER TABLE `user` ADD COLUMN `password_hash` VARCHAR(255) DEFAULT NULL COMMENT '管理员密码的BCrypt哈希' AFTER `role`;

-- ===============================================
-- 知识库基础数据
-- 分类分布：诈骗类型(8) > AI诈骗(5) > 防骗技巧(4) > 安全防护(3) > 法律法规(1)
-- 优先级范围：6~10，部分条目设为禁用以演示状态管理
-- ===============================================

-- 1~8: 诈骗类型（8条，最多）
INSERT INTO `knowledge_item` (`question`, `answer`, `category`, `tags`, `priority`, `enabled`)
SELECT '什么是刷单诈骗', '刷单诈骗是骗子以"刷单返利""兼职赚钱"为诱饵，先让受害人做几单小额任务并返还佣金，取得信任后诱导做大额任务，随后以"系统故障""需要解冻"等理由拒绝返款。切勿轻信任何形式的刷单兼职。', '诈骗类型', '刷单,兼职,返利', 10, 1
WHERE NOT EXISTS (SELECT 1 FROM `knowledge_item` LIMIT 1);

INSERT INTO `knowledge_item` (`question`, `answer`, `category`, `tags`, `priority`, `enabled`)
SELECT '遇到冒充客服退款诈骗怎么办', '冒充客服退款诈骗中，骗子谎称商品质量问题要退款，诱导受害人点击钓鱼链接或提供验证码。正确做法：1）通过官方平台联系客服核实；2）不要点击对方发送的链接；3）不要告知验证码和密码。', '诈骗类型', '冒充客服,退款,钓鱼链接', 10, 1
WHERE (SELECT COUNT(*) FROM `knowledge_item`) < 2;

INSERT INTO `knowledge_item` (`question`, `answer`, `category`, `tags`, `priority`, `enabled`)
SELECT '什么是杀猪盘诈骗', '杀猪盘是骗子通过婚恋平台建立感情关系后诱导投资或赌博的诈骗方式。特征：对方条件优越但急于确定关系、以稳赚不赔诱导投资、初期小额可提现、大额投入后平台无法提现。', '诈骗类型', '杀猪盘,婚恋,投资', 9, 1
WHERE (SELECT COUNT(*) FROM `knowledge_item`) < 3;

INSERT INTO `knowledge_item` (`question`, `answer`, `category`, `tags`, `priority`, `enabled`)
SELECT '如何识别冒充公检法诈骗', '冒充公检法诈骗的特征：自称公安局检察院工作人员、称你涉嫌洗钱贩毒、要求将资金转入"安全账户"、要求保密不得告知他人。公检法不会通过电话办案，没有安全账户，更不会要求转账。', '诈骗类型', '冒充公检法,安全账户,洗钱', 10, 1
WHERE (SELECT COUNT(*) FROM `knowledge_item`) < 4;

INSERT INTO `knowledge_item` (`question`, `answer`, `category`, `tags`, `priority`, `enabled`)
SELECT '什么是投资理财诈骗', '投资理财诈骗以高回报为诱饵，通过虚假平台吸引投资。常见手段：群聊里老师带单、虚假数字货币平台、前期小额盈利可提现、大额充值后平台跑路。年化收益超过6%就要警惕。', '诈骗类型', '投资理财,高回报,虚拟货币', 9, 1
WHERE (SELECT COUNT(*) FROM `knowledge_item`) < 5;

INSERT INTO `knowledge_item` (`question`, `answer`, `category`, `tags`, `priority`, `enabled`)
SELECT '什么是屏幕共享诈骗', '屏幕共享诈骗诱导受害人开启屏幕共享功能，实时监控受害人操作，窃取银行卡号、密码、验证码等信息。常见于冒充客服退款、注销校园贷等诈骗。千万不要与陌生人开启屏幕共享。', '诈骗类型', '屏幕共享,验证码,冒充客服', 10, 1
WHERE (SELECT COUNT(*) FROM `knowledge_item`) < 6;

INSERT INTO `knowledge_item` (`question`, `answer`, `category`, `tags`, `priority`, `enabled`)
SELECT '什么是注销校园贷诈骗', '注销校园贷诈骗是骗子冒充网贷平台客服，称受害人曾注册过校园贷需要注销否则影响征信，诱导从各大网贷平台借款后转入诈骗账户。国家已明令禁止校园贷，不存在注销校园贷账号的说法。', '诈骗类型', '校园贷,征信,注销账号', 9, 0
WHERE (SELECT COUNT(*) FROM `knowledge_item`) < 7;

INSERT INTO `knowledge_item` (`question`, `answer`, `category`, `tags`, `priority`, `enabled`)
SELECT '什么是冒充领导诈骗', '冒充领导诈骗是骗子盗用领导身份通过社交软件要求财务人员转账。手法：新建群聊拉入财务和领导、以商务合同保证金等理由要求转账、催促快速办理。涉及转账必须当面或电话确认。', '诈骗类型', '冒充领导,财务转账,社交软件', 9, 1
WHERE (SELECT COUNT(*) FROM `knowledge_item`) < 8;

-- 9~13: AI诈骗（5条）
INSERT INTO `knowledge_item` (`question`, `answer`, `category`, `tags`, `priority`, `enabled`)
SELECT '什么是AI换脸诈骗', 'AI换脸诈骗是利用深度学习技术将受害者面部特征替换到其他视频中制作虚假视频进行诈骗。常见形式包括冒充熟人借钱、伪造视频证据等。视频通话时要求对方做特定动作（摸脸、转头）可辅助辨别。', 'AI诈骗', 'AI换脸,深度伪造,诈骗', 10, 1
WHERE (SELECT COUNT(*) FROM `knowledge_item`) < 9;

INSERT INTO `knowledge_item` (`question`, `answer`, `category`, `tags`, `priority`, `enabled`)
SELECT '如何识别AI合成的语音', '识别AI合成语音的方法：注意语音的自然度和情感表达、检查背景噪音是否自然、询问只有双方知道的信息进行验证、使用专业的音频检测工具分析。AI合成语音在情感表达上通常不够自然。', 'AI诈骗', '语音合成,AI识别,防骗', 9, 1
WHERE (SELECT COUNT(*) FROM `knowledge_item`) < 10;

INSERT INTO `knowledge_item` (`question`, `answer`, `category`, `tags`, `priority`, `enabled`)
SELECT '什么是语音克隆诈骗', '语音克隆诈骗通过获取目标少量语音样本用AI克隆其声音，然后冒充亲友进行诈骗。骗子通常会以紧急情况为由要求转账。接到熟人语音电话涉及转账时务必通过其他渠道二次核实。', 'AI诈骗', '语音克隆,AI诈骗,紧急转账', 10, 1
WHERE (SELECT COUNT(*) FROM `knowledge_item`) < 11;

INSERT INTO `knowledge_item` (`question`, `answer`, `category`, `tags`, `priority`, `enabled`)
SELECT '如何辨别AI生成的假照片', '辨别AI生成图片的方法：1）检查手指数量是否正常（AI常画错手指）；2）观察眼睛反光是否一致；3）查看背景文字是否扭曲；4）检查皮肤质感是否过于完美；5）使用反向图片搜索验证来源。', 'AI诈骗', 'AI图片,伪造检测,辨别方法', 8, 1
WHERE (SELECT COUNT(*) FROM `knowledge_item`) < 12;

INSERT INTO `knowledge_item` (`question`, `answer`, `category`, `tags`, `priority`, `enabled`)
SELECT 'AI诈骗的常见手法有哪些', '当前AI诈骗的常见手法：1）AI换脸视频冒充熟人；2）AI语音克隆电话诈骗；3）AI生成的钓鱼邮件和短信；4）AI伪造身份证件和文件；5）AI合成的虚假新闻和证据。面对AI诈骗，多方核实是最有效的防御手段。', 'AI诈骗', 'AI诈骗,常见手法,防范', 8, 0
WHERE (SELECT COUNT(*) FROM `knowledge_item`) < 13;

-- 14~17: 防骗技巧（4条）
INSERT INTO `knowledge_item` (`question`, `answer`, `category`, `tags`, `priority`, `enabled`)
SELECT '遇到紧急转账要求怎么办', '遇到紧急转账要求：保持冷静不被催促、通过其他渠道核实对方身份、不要轻信对方提供的电话号码、与家人朋友商量、必要时报警处理。记住：真正的警察不会让你转账到安全账户。', '防骗技巧', '紧急转账,防骗,核实身份', 10, 1
WHERE (SELECT COUNT(*) FROM `knowledge_item`) < 14;

INSERT INTO `knowledge_item` (`question`, `answer`, `category`, `tags`, `priority`, `enabled`)
SELECT '96110是什么电话', '96110是全国统一的反电信网络诈骗预警劝阻咨询电话。当警方监测到你可能正在遭受诈骗时会通过96110联系你进行预警劝阻。接到96110来电一定要接听，也可主动拨打96110咨询诈骗相关问题。', '防骗技巧', '96110,反诈热线,咨询', 9, 1
WHERE (SELECT COUNT(*) FROM `knowledge_item`) < 15;

INSERT INTO `knowledge_item` (`question`, `answer`, `category`, `tags`, `priority`, `enabled`)
SELECT '遇到电信诈骗后怎么办', '发现被骗后的紧急措施：1）立即拨打110报警；2）联系银行冻结账户；3）保存聊天记录和转账凭证等证据；4）前往派出所做笔录；5）及时更改相关账号密码。黄金止付时间为30分钟。', '防骗技巧', '报警,止损,证据保存', 10, 1
WHERE (SELECT COUNT(*) FROM `knowledge_item`) < 16;

INSERT INTO `knowledge_item` (`question`, `answer`, `category`, `tags`, `priority`, `enabled`)
SELECT '出境旅游如何防范诈骗', '出境旅游防骗指南：不轻信机场景区热心人、使用正规打车软件、不在街头兑换外币、警惕低价旅游团陷阱、保护护照证件、开启国际漫游、记下当地中国大使馆电话。遇到可疑情况及时联系使馆。', '防骗技巧', '出境旅游,旅行安全,防骗', 7, 1
WHERE (SELECT COUNT(*) FROM `knowledge_item`) < 17;

-- 18~20: 安全防护（3条）
INSERT INTO `knowledge_item` (`question`, `answer`, `category`, `tags`, `priority`, `enabled`)
SELECT '如何保护个人信息', '保护个人信息的方法：1）不要在社交媒体公开过多个人信息；2）谨慎对待陌生来电和短信；3）定期更换密码，使用双因素认证；4）不要随意连接公共WiFi；5）安装正规的安全软件并定期更新。', '安全防护', '个人信息,隐私保护,安全', 8, 1
WHERE (SELECT COUNT(*) FROM `knowledge_item`) < 18;

INSERT INTO `knowledge_item` (`question`, `answer`, `category`, `tags`, `priority`, `enabled`)
SELECT '经常收到骚扰电话怎么办', '应对骚扰电话：在手机设置中开启骚扰拦截、向12321网络不良与垃圾信息举报中心举报、不接听陌生号码、不在不明渠道填写手机号、使用运营商提供的防骚扰服务。iOS和Android都有内置骚扰拦截功能。', '安全防护', '骚扰电话,拦截,举报', 7, 1
WHERE (SELECT COUNT(*) FROM `knowledge_item`) < 19;

INSERT INTO `knowledge_item` (`question`, `answer`, `category`, `tags`, `priority`, `enabled`)
SELECT '手机丢失后如何保障账户安全', '手机丢失后的紧急措施：致电运营商挂失SIM卡、通过其他设备更改微信支付宝和网银密码、联系银行冻结账户、使用查找设备功能定位或擦除数据、补办SIM卡后重新绑定。建议提前开启远程擦除功能。', '安全防护', '手机丢失,账户安全,挂失', 8, 1
WHERE (SELECT COUNT(*) FROM `knowledge_item`) < 20;

-- 21: 法律法规（1条，最少）
INSERT INTO `knowledge_item` (`question`, `answer`, `category`, `tags`, `priority`, `enabled`)
SELECT '反电信网络诈骗法的主要内容', '反电信网络诈骗法于2022年12月1日起施行。主要内容：1）电信业务经营者需落实实名制；2）银行业金融机构需加强账户管理；3）互联网服务提供者需配合监测拦截涉诈信息；4）个人信息处理者需加强信息保护。违反规定将面临最高百万元罚款。', '法律法规', '反电信诈骗法,法律法规,实名制', 6, 1
WHERE (SELECT COUNT(*) FROM `knowledge_item`) < 21;

-- ===============================================
-- 音频模型基础数据
-- ===============================================
INSERT INTO `audio_model` (`name`, `model_version`, `model_type`, `training_dataset`, `training_epochs`, `accuracy`, `eer`, `training_minutes`, `is_active`, `model_path`, `description`)
SELECT 'Wav2Vec2 语音伪造检测模型', 'v1.0.0', 'wav2vec2', 'ASVspoof2019 LA', 50, 0.985, 0.012, 120, 1, '/models/wav2vec2-spoof-detector', '基于Wav2Vec2的语音伪造检测模型，在ASVspoof2019 LA数据集上训练'
WHERE NOT EXISTS (SELECT 1 FROM `audio_model` LIMIT 1);

INSERT INTO `audio_model` (`name`, `model_version`, `model_type`, `training_dataset`, `training_epochs`, `accuracy`, `eer`, `training_minutes`, `is_active`, `model_path`, `description`)
SELECT 'LA模型 EER', '2021', 'lcnn', 'ASVspoof2019 LA', 100, 0.991, 0.009, 180, 0, '/models/lcnn-la-model', 'ASVspoof2019 LA赛道官方基线模型'
WHERE (SELECT COUNT(*) FROM `audio_model`) < 2;

-- ===============================================
-- 管理员用户
-- ===============================================
INSERT INTO `user` (`openid`, `nickname`, `avatar_url`, `role`, `password_hash`, `last_login_at`)
SELECT 'admin_default', '管理员', '', 'admin', '$2b$10$8t70ROuVfMOGoA1/wC0qIeeFxqhO.3b2UXXNLzu42wgmrZJpVDnfW', NOW()
WHERE NOT EXISTS (SELECT 1 FROM `user` WHERE `openid` = 'admin_default');

UPDATE `user` SET `password_hash` = '$2b$10$8t70ROuVfMOGoA1/wC0qIeeFxqhO.3b2UXXNLzu42wgmrZJpVDnfW'
WHERE `role` = 'admin' AND (`password_hash` IS NULL OR `password_hash` = '');