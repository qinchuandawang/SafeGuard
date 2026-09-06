$ErrorActionPreference = 'Stop'

$root = $PSScriptRoot

# 从 evaluate-qdrant.ps1 中提取历史 105 条正例，避免手工维护重复
$evalScript = Get-Content -LiteralPath (Join-Path $root "evaluate-qdrant.ps1") -Raw
$extracted = [regex]::Matches($evalScript, "@\{ q = '([^']*)'; source = '([^']*)' \}")
$positive = @()
$multi = @()
$negative = @()
foreach ($m in $extracted) {
    $positive += [ordered]@{ q = $m.Groups[1].Value; sources = @($m.Groups[2].Value) }
}

# 新增知识库正例（追加的 25~40 号文档）
$newPositive = @(
    @{ q = '视频裸聊被录屏威胁索要封口费怎么办'; sources = @('anti_fraud_knowledge_25.txt') }
    @{ q = '裸聊诈骗要求下载App并开启通讯录权限'; sources = @('anti_fraud_knowledge_25.txt') }
    @{ q = '被裸聊视频威胁发送给家人应该怎么处理'; sources = @('anti_fraud_knowledge_25.txt') }
    @{ q = '有人冒充军警采购让我先垫付货款'; sources = @('anti_fraud_knowledge_26.txt') }
    @{ q = '部队采购指定供应商要求缴纳保证金'; sources = @('anti_fraud_knowledge_26.txt') }
    @{ q = '收到军警采购合同和红头文件如何核验'; sources = @('anti_fraud_knowledge_26.txt') }
    @{ q = '游戏账号交易要求脱离平台私下转账'; sources = @('anti_fraud_knowledge_27.txt') }
    @{ q = '游戏装备交易先交定金安全吗'; sources = @('anti_fraud_knowledge_27.txt') }
    @{ q = '游戏账号被要求交解冻费怎么办'; sources = @('anti_fraud_knowledge_27.txt') }
    @{ q = '演唱会门票转让要求私下转账可以吗'; sources = @('anti_fraud_knowledge_28.txt') }
    @{ q = '在社交平台买演唱会内部票被要求先交定金'; sources = @('anti_fraud_knowledge_28.txt') }
    @{ q = '电子票如何核验真伪'; sources = @('anti_fraud_knowledge_28.txt') }
    @{ q = '收到信用卡提额短信让我填验证码'; sources = @('anti_fraud_knowledge_29.txt') }
    @{ q = '银行积分兑换短信里的链接能点吗'; sources = @('anti_fraud_knowledge_29.txt') }
    @{ q = '信用卡提额要求先转账验证'; sources = @('anti_fraud_knowledge_29.txt') }
    @{ q = '收到ETC被禁用短信怎么办'; sources = @('anti_fraud_knowledge_30.txt') }
    @{ q = '车辆违章处理短信要求下载App'; sources = @('anti_fraud_knowledge_30.txt') }
    @{ q = 'ETC扣费失败短信怎么核实'; sources = @('anti_fraud_knowledge_30.txt') }
    @{ q = '收到医保卡被停用电话怎么办'; sources = @('anti_fraud_knowledge_31.txt') }
    @{ q = '社保补贴领取要求提供银行卡'; sources = @('anti_fraud_knowledge_31.txt') }
    @{ q = '公积金账户异常要求填验证码'; sources = @('anti_fraud_knowledge_31.txt') }
    @{ q = '数字人民币有投资理财吗'; sources = @('anti_fraud_knowledge_32.txt') }
    @{ q = '虚拟币平台要求充值激活才能提现'; sources = @('anti_fraud_knowledge_32.txt') }
    @{ q = '对方推荐央行虚拟货币高收益投资'; sources = @('anti_fraud_knowledge_32.txt') }
    @{ q = '注销京东白条要我借款转账'; sources = @('anti_fraud_knowledge_33.txt') }
    @{ q = '金条账户不注销影响征信吗'; sources = @('anti_fraud_knowledge_33.txt') }
    @{ q = '银监会电话要求清空贷款额度'; sources = @('anti_fraud_knowledge_33.txt') }
    @{ q = '收到孩子被绑架电话要求赎金'; sources = @('anti_fraud_knowledge_34.txt') }
    @{ q = '老板语音让我马上付款如何核实'; sources = @('anti_fraud_knowledge_34.txt') }
    @{ q = 'AI合成家人声音借钱'; sources = @('anti_fraud_knowledge_34.txt') }
    @{ q = '国家反诈中心App怎么用'; sources = @('anti_fraud_knowledge_35.txt') }
    @{ q = '12381短信预警是什么'; sources = @('anti_fraud_knowledge_35.txt') }
    @{ q = '96110来电不接会怎样'; sources = @('anti_fraud_knowledge_35.txt') }
    @{ q = '转账前如何做二次核验'; sources = @('anti_fraud_knowledge_36.txt') }
    @{ q = '大额转账前要检查哪些事项'; sources = @('anti_fraud_knowledge_36.txt') }
    @{ q = '对方催促紧急转账要不要先核实'; sources = @('anti_fraud_knowledge_36.txt') }
    @{ q = '老年人如何防范保健品投资诈骗'; sources = @('anti_fraud_knowledge_37.txt') }
    @{ q = '青少年游戏交易防骗要点'; sources = @('anti_fraud_knowledge_37.txt') }
    @{ q = '如何帮家里老人开启反诈预警'; sources = @('anti_fraud_knowledge_37.txt') }
    @{ q = '收到课程安排通知是诈骗吗'; sources = @('anti_fraud_knowledge_38.txt') }
    @{ q = '正常快递物流通知和钓鱼短信怎么区分'; sources = @('anti_fraud_knowledge_38.txt') }
    @{ q = '什么是安全的转账情形'; sources = @('anti_fraud_knowledge_38.txt') }
    @{ q = '出租银行卡给别人有什么法律风险'; sources = @('anti_fraud_knowledge_39.txt') }
    @{ q = '帮信罪是什么'; sources = @('anti_fraud_knowledge_39.txt') }
    @{ q = '出售电话卡可能承担什么责任'; sources = @('anti_fraud_knowledge_39.txt') }
    @{ q = '注销京东金条要借款转账吗'; sources = @('anti_fraud_knowledge_40.txt') }
    @{ q = 'ETC短信点进去了怎么办'; sources = @('anti_fraud_knowledge_40.txt') }
    @{ q = '老人接到孩子出事电话先做什么'; sources = @('anti_fraud_knowledge_40.txt') }
)

# 多意图用例：预期召回多个来源，用于验证 Agentic 补检
$newMulti = @(
    @{ q = '刷单兼职被骗后如何报警止损'; sources = @('anti_fraud_knowledge_1.txt', 'anti_fraud_knowledge_11.txt') }
    @{ q = '冒充公检法要求转账到安全账户，如何核实身份并报警'; sources = @('anti_fraud_knowledge_3.txt', 'anti_fraud_knowledge_10.txt') }
    @{ q = 'AI语音克隆借钱和视频换脸转账都要二次核实吗'; sources = @('anti_fraud_knowledge_7.txt', 'anti_fraud_knowledge_6.txt') }
    @{ q = '客服要求共享屏幕并下载会议软件，退款怎么核实'; sources = @('anti_fraud_knowledge_2.txt', 'anti_fraud_knowledge_13.txt') }
    @{ q = '投资理财平台不让提现还要求解冻费怎么办'; sources = @('anti_fraud_knowledge_4.txt', 'anti_fraud_knowledge_11.txt') }
    @{ q = '收到快递理赔和航班取消短信怎么核实'; sources = @('anti_fraud_knowledge_17.txt', 'anti_fraud_knowledge_21.txt') }
    @{ q = '校园贷注销要求刷流水和共享屏幕，如何防范'; sources = @('anti_fraud_knowledge_19.txt', 'anti_fraud_knowledge_13.txt') }
    @{ q = '裸聊被威胁发通讯录，如何止损并报警'; sources = @('anti_fraud_knowledge_25.txt', 'anti_fraud_knowledge_11.txt') }
    @{ q = '老人接到孩子出事电话要先做什么，如何防骗'; sources = @('anti_fraud_knowledge_37.txt', 'anti_fraud_knowledge_10.txt') }
    @{ q = '视频通话看到熟人称被绑架要赎金，如何核实'; sources = @('anti_fraud_knowledge_34.txt', 'anti_fraud_knowledge_6.txt') }
)

# 负例：与反诈知识无关，期望返回结果得分低于阈值
$newNegative = @(
    @{ q = '如何制作红烧肉' }
    @{ q = '今天天气怎么样' }
    @{ q = 'Java 后端学习路线推荐' }
    @{ q = '巴黎有哪些著名景点' }
    @{ q = '马拉松训练计划怎么写' }
    @{ q = '考研数学应该怎么复习' }
)

foreach ($c in $newPositive) {
    $positive += [ordered]@{ q = $c.q; sources = @($c.sources) }
}
foreach ($c in $newMulti) {
    $multi += [ordered]@{ q = $c.q; sources = @($c.sources) }
}
foreach ($c in $newNegative) {
    $negative += [ordered]@{ q = $c.q }
}

$out = [ordered]@{
    positive = $positive
    multiIntent = $multi
    negative = $negative
} | ConvertTo-Json -Depth 8

$target = Join-Path $root "cases.json"
Set-Content -LiteralPath $target -Value $out -Encoding utf8
"positive=$($positive.Count) multiIntent=$($multi.Count) negative=$($negative.Count) total=$($positive.Count + $multi.Count + $negative.Count)"
