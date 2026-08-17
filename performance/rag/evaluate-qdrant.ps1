$ErrorActionPreference = 'Stop'

$baseUrl = 'http://127.0.0.1:8080'
$qdrantUrl = 'http://127.0.0.1:6333'

$health = Invoke-RestMethod "$qdrantUrl/healthz"
$collection = Invoke-RestMethod "$qdrantUrl/collections/anti_fraud_knowledge_v2"
if ($collection.result.points_count -le 36) {
    throw "Qdrant 知识集合未完成扩充，当前点数: $($collection.result.points_count)"
}

$siliconFlowKey = ((Get-Content '.env' | Where-Object { $_ -match '^SILICONFLOW_API_KEY=' } | Select-Object -First 1) -replace '^SILICONFLOW_API_KEY=', '').Trim()
if ([string]::IsNullOrWhiteSpace($siliconFlowKey)) {
    throw '缺少 SiliconFlow API Key，不能执行真实向量基线评测'
}
$embeddingCache = @{}

function Get-QueryEmbedding([string]$query) {
    if ($embeddingCache.ContainsKey($query)) {
        return $embeddingCache[$query]
    }
    $headers = @{ Authorization = "Bearer $siliconFlowKey" }
    $body = @{ model = 'BAAI/bge-base-zh-v1.5'; input = $query } | ConvertTo-Json -Compress
    $response = Invoke-RestMethod 'https://api.siliconflow.cn/v1/embeddings' -Method Post `
        -Headers $headers -ContentType 'application/json' -Body $body -TimeoutSec 60
    $vector = @($response.data[0].embedding)
    if ($vector.Count -ne 768) {
        throw "Embedding 维度异常: $($vector.Count)"
    }
    $embeddingCache[$query] = $vector
    return $vector
}

function Test-VectorRecall([array]$vector, [string]$expectedSource) {
    $body = @{ vector = $vector; limit = 5; with_payload = $true; with_vector = $false } | ConvertTo-Json -Depth 8 -Compress
    $response = Invoke-RestMethod "$qdrantUrl/collections/anti_fraud_knowledge_v2/points/search" -Method Post `
        -ContentType 'application/json' -Body $body -TimeoutSec 60
    return @($response.result | ForEach-Object { $_.payload.source }) -contains $expectedSource
}

# 每条查询绑定知识文档来源，相关来源来自当前知识库的人工主题标注。
$cases = @(
    @{ q = '刷单兼职先垫钱再返利，应该如何处理'; source = 'anti_fraud_knowledge_1.txt' }
    @{ q = '刷单平台说最后一笔就能提现，我还要继续充值吗'; source = 'anti_fraud_knowledge_1.txt' }
    @{ q = '点赞返佣需要先转账，这种兼职可信吗'; source = 'anti_fraud_knowledge_1.txt' }
    @{ q = '刷单被骗后应该保存哪些证据'; source = 'anti_fraud_knowledge_1.txt' }
    @{ q = '网购退款客服要求共享屏幕，怎么验证真假'; source = 'anti_fraud_knowledge_2.txt' }
    @{ q = '冒充快递客服说会员扣费异常，要我提供验证码'; source = 'anti_fraud_knowledge_2.txt' }
    @{ q = '客服退款让我点击陌生链接，应该怎么办'; source = 'anti_fraud_knowledge_2.txt' }
    @{ q = '退款时泄露银行卡信息后如何止损'; source = 'anti_fraud_knowledge_2.txt' }
    @{ q = '有人自称公安说我涉嫌洗钱，要把钱转安全账户'; source = 'anti_fraud_knowledge_3.txt' }
    @{ q = '公检法会通过电话要求转账接受审查吗'; source = 'anti_fraud_knowledge_3.txt' }
    @{ q = '收到伪造通缉令并被要求保密，怎么核实'; source = 'anti_fraud_knowledge_3.txt' }
    @{ q = '冒充警官让我下载会议软件，应该打什么电话确认'; source = 'anti_fraud_knowledge_3.txt' }
    @{ q = '网友建立感情后推荐高收益投资平台，是否可信'; source = 'anti_fraud_knowledge_4.txt' }
    @{ q = '投资老师说稳赚不赔并要求缴纳解冻费'; source = 'anti_fraud_knowledge_4.txt' }
    @{ q = '杀猪盘投资被骗后还要追加资金吗'; source = 'anti_fraud_knowledge_4.txt' }
    @{ q = '虚假理财平台限制提现并要求保证金怎么办'; source = 'anti_fraud_knowledge_4.txt' }
    @{ q = '领导在群里让我紧急转账，如何确认身份'; source = 'anti_fraud_knowledge_5.txt' }
    @{ q = '熟人账号借钱并催促保密，能直接转账吗'; source = 'anti_fraud_knowledge_5.txt' }
    @{ q = '冒充老板要求财务马上付款，应该走什么流程'; source = 'anti_fraud_knowledge_5.txt' }
    @{ q = '朋友说出事故急需借钱，如何通过第二渠道核实'; source = 'anti_fraud_knowledge_5.txt' }
    @{ q = 'AI换脸视频中嘴型和语音不同步，怎么判断风险'; source = 'anti_fraud_knowledge_6.txt' }
    @{ q = '视频通话看到熟人本人，涉及借钱就一定安全吗'; source = 'anti_fraud_knowledge_6.txt' }
    @{ q = '深度伪造视频借钱时应该要求对方做什么'; source = 'anti_fraud_knowledge_6.txt' }
    @{ q = 'AI换脸视频识别不能只依靠哪些细节'; source = 'anti_fraud_knowledge_6.txt' }
    @{ q = '朋友发来语音借钱，可能是语音克隆吗'; source = 'anti_fraud_knowledge_7.txt' }
    @{ q = 'AI合成语音要求紧急转账，应该如何核实'; source = 'anti_fraud_knowledge_7.txt' }
    @{ q = '语音电话背景异常且持续催促，怎么处理'; source = 'anti_fraud_knowledge_7.txt' }
    @{ q = '怀疑亲友声音被克隆后能不能直接向陌生账户付款'; source = 'anti_fraud_knowledge_7.txt' }
    @{ q = 'AI生成的客服短信和钓鱼页面有哪些风险'; source = 'anti_fraud_knowledge_8.txt' }
    @{ q = '如何识别AI生成的头像证件和聊天截图'; source = 'anti_fraud_knowledge_8.txt' }
    @{ q = '短信页面要求输入银行卡和验证码，是否安全'; source = 'anti_fraud_knowledge_8.txt' }
    @{ q = '遇到精美的投资海报和中奖页面应该怎么核实'; source = 'anti_fraud_knowledge_8.txt' }
    @{ q = '有人要求马上紧急转账，我首先应该做什么'; source = 'anti_fraud_knowledge_9.txt' }
    @{ q = '领导催办转账并说不转就违法，如何处理'; source = 'anti_fraud_knowledge_9.txt' }
    @{ q = '遇到最后一次解冻的转账要求，应该相信吗'; source = 'anti_fraud_knowledge_9.txt' }
    @{ q = '核实紧急转账时可以通过哪些渠道确认'; source = 'anti_fraud_knowledge_9.txt' }
    @{ q = '96110是什么电话，接到来电需要接听吗'; source = 'anti_fraud_knowledge_10.txt' }
    @{ q = '怀疑自己正在遭遇电信诈骗可以拨打什么号码咨询'; source = 'anti_fraud_knowledge_10.txt' }
    @{ q = '已经发生转账应该拨打96110还是110'; source = 'anti_fraud_knowledge_10.txt' }
    @{ q = '警方会要求把钱转入安全账户吗'; source = 'anti_fraud_knowledge_10.txt' }
    @{ q = '被骗转账后第一时间应该做什么'; source = 'anti_fraud_knowledge_11.txt' }
    @{ q = '发现被骗后如何联系银行止付和冻结账户'; source = 'anti_fraud_knowledge_11.txt' }
    @{ q = '报警时需要保存哪些聊天和转账证据'; source = 'anti_fraud_knowledge_11.txt' }
    @{ q = '有人承诺帮助追回被骗资金，是否可能是二次诈骗'; source = 'anti_fraud_knowledge_11.txt' }
    @{ q = '社交平台上哪些个人信息不应该公开'; source = 'anti_fraud_knowledge_12.txt' }
    @{ q = '身份证和快递单丢弃前应该怎么处理'; source = 'anti_fraud_knowledge_12.txt' }
    @{ q = '如何设置账号密码和双因素认证更安全'; source = 'anti_fraud_knowledge_12.txt' }
    @{ q = '公共WiFi和来源不明的App有什么风险'; source = 'anti_fraud_knowledge_12.txt' }
    @{ q = '陌生人要求屏幕共享，可能看到哪些敏感信息'; source = 'anti_fraud_knowledge_13.txt' }
    @{ q = '开启远程控制后应该立即采取哪些措施'; source = 'anti_fraud_knowledge_13.txt' }
    @{ q = '冒充客服和公检法为什么喜欢要求共享屏幕'; source = 'anti_fraud_knowledge_13.txt' }
    @{ q = '如何防范屏幕共享泄露验证码和支付密码'; source = 'anti_fraud_knowledge_13.txt' }
    @{ q = '反电信网络诈骗法什么时候开始施行'; source = 'anti_fraud_knowledge_14.txt' }
    @{ q = '出租出售银行卡和电话卡可能承担什么法律责任'; source = 'anti_fraud_knowledge_14.txt' }
    @{ q = '反诈法对银行和互联网服务提供者有什么要求'; source = 'anti_fraud_knowledge_14.txt' }
    @{ q = '个人为什么不能出借支付账户给他人'; source = 'anti_fraud_knowledge_14.txt' }
    @{ q = '安全账户要求转账时应该怎么办'; source = 'anti_fraud_knowledge_15.txt' }
    @{ q = '朋友发语音借钱是否可以只凭声音相信'; source = 'anti_fraud_knowledge_15.txt' }
    @{ q = '视频通话看到本人后还能直接转账吗'; source = 'anti_fraud_knowledge_15.txt' }
    @{ q = '刷单投入资金后还要不要继续做任务'; source = 'anti_fraud_knowledge_15.txt' }

    @{ q = '贷款前要求交保证金和解冻费，是否属于诈骗'; source = 'anti_fraud_knowledge_16.txt' }
    @{ q = '有人说不修复征信就不能贷款，应该怎么核验'; source = 'anti_fraud_knowledge_16.txt' }
    @{ q = '网贷平台要求刷流水后才能放款，能不能操作'; source = 'anti_fraud_knowledge_16.txt' }
    @{ q = '贷款验证码和支付密码可以提供给客服吗'; source = 'anti_fraud_knowledge_16.txt' }
    @{ q = '征信记录能通过交钱让内部人员删除吗'; source = 'anti_fraud_knowledge_16.txt' }
    @{ q = '航班取消后有人来电要求验证资金，怎么处理'; source = 'anti_fraud_knowledge_17.txt' }
    @{ q = '快递理赔客服让我下载会议软件并共享屏幕'; source = 'anti_fraud_knowledge_17.txt' }
    @{ q = '机票退改签应该从什么渠道核实'; source = 'anti_fraud_knowledge_17.txt' }
    @{ q = '客服号码显示官方号码就一定可靠吗'; source = 'anti_fraud_knowledge_17.txt' }
    @{ q = '退改签时要求向个人账户转账是否正常'; source = 'anti_fraud_knowledge_17.txt' }
    @{ q = '低价购物收款后又要求交保证金，应该付款吗'; source = 'anti_fraud_knowledge_18.txt' }
    @{ q = '中奖通知要求先交税费才能领奖是真的吗'; source = 'anti_fraud_knowledge_18.txt' }
    @{ q = '取消会员扣费需要提供验证码和支付密码吗'; source = 'anti_fraud_knowledge_18.txt' }
    @{ q = '虚假购物诈骗应该保存哪些订单证据'; source = 'anti_fraud_knowledge_18.txt' }
    @{ q = '陌生收款码和私人转账能作为购物付款方式吗'; source = 'anti_fraud_knowledge_18.txt' }
    @{ q = '校园贷客服要求注销额度并把借款转给他'; source = 'anti_fraud_knowledge_19.txt' }
    @{ q = '有人说不注销校园贷会影响征信，怎么核验'; source = 'anti_fraud_knowledge_19.txt' }
    @{ q = '注销网贷要求刷流水和屏幕共享是否可信'; source = 'anti_fraud_knowledge_19.txt' }
    @{ q = '学生遇到校园贷诈骗可以向谁求助'; source = 'anti_fraud_knowledge_19.txt' }
    @{ q = '平台注销前要求借款转入指定账户怎么办'; source = 'anti_fraud_knowledge_19.txt' }
    @{ q = 'AI生成的营业执照和合同可以直接证明对方真实吗'; source = 'anti_fraud_knowledge_20.txt' }
    @{ q = '如何独立核验AI生成的公司证件和印章'; source = 'anti_fraud_knowledge_20.txt' }
    @{ q = '大额付款前如何核对合同主体和收款账户'; source = 'anti_fraud_knowledge_20.txt' }
    @{ q = '伪造的聊天截图和证件材料有什么风险'; source = 'anti_fraud_knowledge_20.txt' }
    @{ q = '不能只通过材料中的电话和二维码核实身份吗'; source = 'anti_fraud_knowledge_20.txt' }
    @{ q = '钓鱼网站仿冒银行页面时有哪些特征'; source = 'anti_fraud_knowledge_21.txt' }
    @{ q = '陌生App要求开启无障碍权限应该怎么办'; source = 'anti_fraud_knowledge_21.txt' }
    @{ q = '从短信链接下载金融App安全吗'; source = 'anti_fraud_knowledge_21.txt' }
    @{ q = '恶意App可能窃取哪些验证码和设备权限'; source = 'anti_fraud_knowledge_21.txt' }
    @{ q = '发现可疑App后应该先做哪些处置'; source = 'anti_fraud_knowledge_21.txt' }
    @{ q = '账号被盗后骗子冒充本人借钱怎么办'; source = 'anti_fraud_knowledge_22.txt' }
    @{ q = '发现异常登录后如何撤销第三方授权'; source = 'anti_fraud_knowledge_22.txt' }
    @{ q = '企业群付款为什么需要二次确认和审批'; source = 'anti_fraud_knowledge_22.txt' }
    @{ q = '验证码和恢复码可以发给所谓客服吗'; source = 'anti_fraud_knowledge_22.txt' }
    @{ q = '社交工程攻击通常如何套取个人信息'; source = 'anti_fraud_knowledge_22.txt' }
    @{ q = '报警和止付时需要提供哪些诈骗证据'; source = 'anti_fraud_knowledge_23.txt' }
    @{ q = '聊天记录只截一张图能作为完整证据吗'; source = 'anti_fraud_knowledge_23.txt' }
    @{ q = '被骗后为什么不能删除聊天记录和刷机'; source = 'anti_fraud_knowledge_23.txt' }
    @{ q = '有人承诺交费就能追回资金是真的吗'; source = 'anti_fraud_knowledge_23.txt' }
    @{ q = '被骗后应该如何整理转账时间线'; source = 'anti_fraud_knowledge_23.txt' }
    @{ q = '贷款前交解冻费正常吗'; source = 'anti_fraud_knowledge_24.txt' }
    @{ q = '陌生App要求开启无障碍权限怎么办'; source = 'anti_fraud_knowledge_24.txt' }
    @{ q = '合同和营业执照图片能证明对方真实可靠吗'; source = 'anti_fraud_knowledge_24.txt' }
    @{ q = '账号被盗后联系人已经转账应该怎么办'; source = 'anti_fraud_knowledge_24.txt' }
    @{ q = '客服号码显示官方来电就一定是真的吗'; source = 'anti_fraud_knowledge_24.txt' }
)

$baselineHit = 0
$hybridHit = 0
$caseIndex = 0
$misses = [System.Collections.Generic.List[object]]::new()
$latencies = [System.Collections.Generic.List[double]]::new()
foreach ($case in $cases) {
    $caseIndex++
    $watch = [Diagnostics.Stopwatch]::StartNew()
    $encoded = [Uri]::EscapeDataString($case.q)
    $headers = @{ 'X-Forwarded-For' = "10.250.0.$caseIndex" }
    $vector = Get-QueryEmbedding $case.q
    if (Test-VectorRecall $vector $case.source) {
        $baselineHit++
    }
    $response = Invoke-RestMethod "$baseUrl/api/rag/query?q=$encoded" -Headers $headers
    $watch.Stop()
    $latencies.Add($watch.Elapsed.TotalMilliseconds)
    $sources = @($response.data | ForEach-Object { $_.source })
    if ($sources -contains $case.source) {
        $hybridHit++
    } else {
        $misses.Add([ordered]@{ query = $case.q; expected = $case.source; actual = $sources })
    }
}

$latencies.Sort()
$p95Index = [Math]::Min($latencies.Count - 1, [Math]::Ceiling($latencies.Count * 0.95) - 1)
[ordered]@{
    qdrantHealth = $health
    collectionPoints = $collection.result.points_count
    sampleCount = $cases.Count
    baselineHitCount = $baselineHit
    hybridHitCount = $hybridHit
    baselineRecallAt5Percent = [Math]::Round(100 * $baselineHit / $cases.Count, 2)
    hybridRecallAt5Percent = [Math]::Round(100 * $hybridHit / $cases.Count, 2)
    improvementPercentagePoints = [Math]::Round(100 * ($hybridHit - $baselineHit) / $cases.Count, 2)
    relativeImprovementPercent = if ($baselineHit -eq 0) { $null } else { [Math]::Round(100 * ($hybridHit - $baselineHit) / $baselineHit, 2) }
    queryP95Ms = [Math]::Round($latencies[$p95Index], 0)
    misses = $misses
} | ConvertTo-Json -Depth 8
