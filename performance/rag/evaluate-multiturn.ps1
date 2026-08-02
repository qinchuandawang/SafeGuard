$ErrorActionPreference = 'Stop'

$baseUrl = 'http://127.0.0.1:8080'
$cases = @(
    @{ first = '朋友发来语音说急需借钱，我担心是语音克隆诈骗，应该如何核实？'; follow = '那我只听到声音，可以直接转账吗？'; keywords = @('不要', '核实') }
    @{ first = '网购客服说可以退款，但要求我共享屏幕并提供验证码，怎么办？'; follow = '如果验证码已经泄露，我下一步做什么？'; keywords = @('冻结', '修改密码', '不要') }
    @{ first = '有人自称公安说我涉嫌洗钱，要求把钱转到安全账户，我该怎么办？'; follow = '他说不配合就违法，我要不要继续操作？'; keywords = @('不要转账', '挂断', '110', '报警') }
    @{ first = '网友和我建立感情后推荐一个高收益理财平台，我已经投入了一些钱。'; follow = '平台现在要求缴纳解冻费才能提现，我应该付款吗？'; keywords = @('不要', '不缴', '报警', '止付') }
    @{ first = '视频通话里看起来是熟人，但对方突然借钱，我怀疑是AI换脸。'; follow = '视频里确实是本人，我还需要通过其他方式核实吗？'; keywords = @('不一定', '核实', '不要转账', '二次') }
    @{ first = '我刚发现自己可能遭遇诈骗，已经向对方账户转过钱。'; follow = '现在最紧急的止损步骤是什么？'; keywords = @('止付', '冻结', '110', '报警') }
)

$pass = 0
$index = 0
$details = [System.Collections.Generic.List[object]]::new()
foreach ($case in $cases) {
    $index++
    $sessionId = "rag-multiturn-$index-$([Guid]::NewGuid().ToString())"
    $headers = @{ 'X-Forwarded-For' = "10.253.0.$index" }
    $firstBody = @{ text = $case.first; sessionId = $sessionId } | ConvertTo-Json
    $followBody = @{ text = $case.follow; sessionId = $sessionId } | ConvertTo-Json

    Invoke-RestMethod "$baseUrl/api/analyze/react" -Method Post -Headers $headers `
        -ContentType 'application/json' -Body $firstBody -TimeoutSec 120 | Out-Null
    $answerResponse = Invoke-RestMethod "$baseUrl/api/analyze/react" -Method Post -Headers $headers `
        -ContentType 'application/json' -Body $followBody -TimeoutSec 120
    $answer = @($answerResponse.data | Select-Object -Last 1).finalAnswer
    if ([string]::IsNullOrWhiteSpace($answer)) {
        $answer = @($answerResponse.data | Select-Object -Last 1).observation
    }
    $matched = @($case.keywords | Where-Object { $answer.Contains($_) })
    $isCorrect = $matched.Count -ge 2
    if ($isCorrect) { $pass++ }
    $details.Add([ordered]@{
        sessionId = $sessionId
        followUp = $case.follow
        matchedKeywords = $matched
        requiredKeywordCount = 2
        correct = $isCorrect
        answer = $answer
    })
}

[ordered]@{
    sampleCount = $cases.Count
    correctCount = $pass
    accuracy = [Math]::Round($pass / $cases.Count, 4)
    accuracyPercent = [Math]::Round(100 * $pass / $cases.Count, 2)
    details = $details
} | ConvertTo-Json -Depth 8
