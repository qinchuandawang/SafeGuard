$ErrorActionPreference = 'Stop'

$baseUrl = 'http://127.0.0.1:8080'

# ============================================================
# Agentic RAG 综合测评：基线单轮混合检索 vs Agentic（检索-反思-补检）
# 指标：hit@1/3/5、MRR、nDCG@5、precision@5、负例通过率、P95 延迟
# ============================================================

$scriptDir = $PSScriptRoot
$casesPath = Join-Path $scriptDir 'cases.json'
if (-not (Test-Path $casesPath)) {
    throw "未找到用例库: $casesPath"
}
$cases = Get-Content -LiteralPath $casesPath -Raw | ConvertFrom-Json

# 前置校验：知识库必须已扩充（40 节 -> 约 120+ chunk）
$stats = Invoke-RestMethod "$baseUrl/api/rag/stats"
$totalChunks = [int]$stats.data.totalChunks
if ($totalChunks -lt 80) {
    throw "知识库未扩充（当前 $totalChunks 块）。请先扩充 anti_fraud_knowledge.txt 后调用 POST /api/rag/reload。"
}

function Get-RagBaseline([string]$q, [int]$index) {
    $encoded = [Uri]::EscapeDataString($q)
    $headers = @{ 'X-Forwarded-For' = "10.200.0.$($index % 250 + 1)" }
    $resp = Invoke-RestMethod "$baseUrl/api/rag/query?q=$encoded" -Headers $headers -TimeoutSec 60
    return @($resp.data)
}

function Get-RagAgentic([string]$q, [int]$index) {
    $encoded = [Uri]::EscapeDataString($q)
    $headers = @{ 'X-Forwarded-For' = "10.200.0.$($index % 250 + 1)" }
    $resp = Invoke-RestMethod "$baseUrl/api/rag/agentic-query?q=$encoded" -Headers $headers -TimeoutSec 90
    $data = $resp.data
    return [ordered]@{
        results = @($data.results)
        trace = @($data.trace)
        iterations = $data.iterations
        refined = $data.refined
        coverageScore = $data.coverageScore
        sufficient = $data.sufficient
        costMs = $data.costMs
    }
}

function Get-RankMetrics([array]$results, [string[]]$expectedSources) {
    $hitAt1 = $false; $hitAt3 = $false; $hitAt5 = $false; $rr = 0.0; $dcg = 0.0; $relCount = 0
    $limit = [Math]::Min(5, $results.Count)
    for ($i = 0; $i -lt $limit; $i++) {
        $src = [string]$results[$i].source
        $rel = if ($expectedSources -contains $src) { 1 } else { 0 }
        if ($rel -eq 1) { $relCount++ }
        if ($i -eq 0 -and $rel -eq 1) { $hitAt1 = $true }
        if ($i -lt 3 -and $rel -eq 1) { $hitAt3 = $true }
        if ($i -lt 5 -and $rel -eq 1) { $hitAt5 = $true }
        if ($rel -eq 1 -and $rr -eq 0.0) { $rr = 1.0 / ($i + 1) }
        $dcg += $rel / [Math]::Log($i + 2, 2)
    }
    # IDCG：假设所有相关源都排在最前
    $relTotal = [Math]::Min($expectedSources.Count, 5)
    $idcg = 0.0
    for ($i = 0; $i -lt $relTotal; $i++) { $idcg += 1.0 / [Math]::Log($i + 2, 2) }
    return [ordered]@{
        hitAt1 = $hitAt1; hitAt3 = $hitAt3; hitAt5 = $hitAt5
        mrr = $rr
        ndcg = if ($idcg -gt 0) { $dcg / $idcg } else { 0.0 }
        precisionAt5 = if ($results.Count -gt 0) { $relCount / $limit } else { 0.0 }
        sources = @($results | ForEach-Object { $_.source })
    }
}

# ---------- 正例 + 多意图 ----------
$allCases = @()
foreach ($c in $cases.positive) {
    $allCases += [ordered]@{ q = $c.q; sources = @($c.sources); type = 'positive' }
}
foreach ($c in $cases.multiIntent) {
    $allCases += [ordered]@{ q = $c.q; sources = @($c.sources); type = 'multi-intent' }
}

$baseMetrics = @()
$agenticMetrics = @()
$baseLatencies = [System.Collections.Generic.List[double]]::new()
$agenticLatencies = [System.Collections.Generic.List[double]]::new()
$misses = [System.Collections.Generic.List[object]]::new()
$refinedCount = 0
$multiRefined = 0
$index = 0

foreach ($case in $allCases) {
    $index++
    $q = $case.q
    $expected = @($case.sources)

    $sw = [Diagnostics.Stopwatch]::StartNew()
    $baseResults = Get-RagBaseline $q $index
    $sw.Stop()
    $baseLatencies.Add($sw.Elapsed.TotalMilliseconds)
    $baseMetrics += Get-RankMetrics $baseResults $expected

    $sw2 = [Diagnostics.Stopwatch]::StartNew()
    $agentic = Get-RagAgentic $q $index
    $sw2.Stop()
    $agenticLatencies.Add($sw2.Elapsed.TotalMilliseconds)
    $agenticMetrics += Get-RankMetrics $agentic.results $expected

    if ($agentic.refined -eq $true) {
        $refinedCount++
        if ($case.type -eq 'multi-intent') { $multiRefined++ }
    }

    $hitExpected = @($agentic.results | Where-Object { $expected -contains $_.source }).Count
    if ($hitExpected -eq 0) {
        $misses.Add([ordered]@{
            query = $q
            expected = $expected
            baseline = @($baseResults | ForEach-Object { $_.source })
            agentic = @($agentic.results | ForEach-Object { $_.source })
            trace = $agentic.trace
        })
    }
}

function Pct($v) { return [Math]::Round($v * 100, 2) }

$n = $allCases.Count

# ---- 显式统计：Measure-Object 对 [ordered] 字典数组的属性测量会失效，改用手动累加 ----
function Get-MetricSums([array]$metrics) {
    $mrr = 0.0; $ndcg = 0.0; $prec = 0.0
    foreach ($m in $metrics) {
        $mrr += [double]$m.mrr
        $ndcg += [double]$m.ndcg
        $prec += [double]$m.precisionAt5
    }
    return [ordered]@{ mrr = $mrr; ndcg = $ndcg; prec = $prec }
}
$baseSums = Get-MetricSums $baseMetrics
$agenticSums = Get-MetricSums $agenticMetrics

function Get-P95([array]$sorted) {
    if ($sorted.Count -eq 0) { return 0.0 }
    return $sorted[[Math]::Min($sorted.Count - 1, [Math]::Ceiling($sorted.Count * 0.95) - 1)]
}
$baseP95 = Get-P95 (@($baseLatencies | Sort-Object))
$agenticP95 = Get-P95 (@($agenticLatencies | Sort-Object))
$baseLatMax = [Math]::Round(($baseLatencies | Measure-Object -Maximum).Maximum, 0)
$agenticLatMax = [Math]::Round(($agenticLatencies | Measure-Object -Maximum).Maximum, 0)

$summary = [ordered]@{
    totalChunks = $totalChunks
    positiveAndMultiIntentCount = $n
    negativeCount = $cases.negative.Count
    # 基线单轮
    base = [ordered]@{
        hitAt1 = [Math]::Round((($baseMetrics | Where-Object hitAt1).Count) / $n, 4)
        hitAt3 = [Math]::Round((($baseMetrics | Where-Object hitAt3).Count) / $n, 4)
        hitAt5 = [Math]::Round((($baseMetrics | Where-Object hitAt5).Count) / $n, 4)
        mrr = [Math]::Round($baseSums.mrr / $n, 4)
        ndcgAt5 = [Math]::Round($baseSums.ndcg / $n, 4)
        precisionAt5 = [Math]::Round($baseSums.prec / $n, 4)
    }
    # Agentic
    agentic = [ordered]@{
        hitAt1 = [Math]::Round((($agenticMetrics | Where-Object hitAt1).Count) / $n, 4)
        hitAt3 = [Math]::Round((($agenticMetrics | Where-Object hitAt3).Count) / $n, 4)
        hitAt5 = [Math]::Round((($agenticMetrics | Where-Object hitAt5).Count) / $n, 4)
        mrr = [Math]::Round($agenticSums.mrr / $n, 4)
        ndcgAt5 = [Math]::Round($agenticSums.ndcg / $n, 4)
        precisionAt5 = [Math]::Round($agenticSums.prec / $n, 4)
        refinedCaseCount = $refinedCount
        multiIntentRefinedCount = $multiRefined
    }
    # 延迟
    baseLatencyP95Ms = [Math]::Round($baseP95, 0)
    agenticLatencyP95Ms = [Math]::Round($agenticP95, 0)
    agenticLatencyMaxMs = $agenticLatMax
    baseLatencyMaxMs = $baseLatMax
    misses = $misses
}

# ---------- 负例 ----------
$negativePass = 0
$negativeFailures = [System.Collections.Generic.List[object]]::new()
$negativeScoreThreshold = 0.30
foreach ($neg in $cases.negative) {
    $index++
    $results = Get-RagBaseline $neg.q $index
    $topScore = if ($results.Count -gt 0) { [double]$results[0].score } else { 0.0 }
    $topVector = if ($results.Count -gt 0) { [double]$results[0].vectorScore } else { 0.0 }
    $ok = $topScore -lt $negativeScoreThreshold -and $topVector -lt $negativeScoreThreshold
    if ($ok) { $negativePass++ }
    else {
        $negativeFailures.Add([ordered]@{ q = $neg.q; topScore = [Math]::Round($topScore, 4); topVector = [Math]::Round($topVector, 4) })
    }
}
$summary.negativePassRate = [Math]::Round($negativePass / $cases.negative.Count, 4)
$summary.negativeFailures = $negativeFailures

# ---------- 断言 ----------
$agenticRecall5 = (($agenticMetrics | Where-Object hitAt5).Count) / $n
$agenticHit1 = (($agenticMetrics | Where-Object hitAt1).Count) / $n
$negativeRate = $negativePass / $cases.negative.Count

$gates = [ordered]@{
    'agentic recall@5 >= 0.95' = ($agenticRecall5 -ge 0.95)
    'agentic hit@1 >= 0.70' = ($agenticHit1 -ge 0.70)
    'agentic mrr >= baseline mrr' = ($summary.agentic.mrr -ge $summary.base.mrr)
    'negative pass rate >= 0.80' = ($negativeRate -ge 0.80)
    'agentic P95 latency <= 5000ms' = ($summary.agenticLatencyP95Ms -le 5000)
    'knowledge base expanded >= 80 chunks' = ($totalChunks -ge 80)
}
$summary.gates = $gates
$allPass = @($gates.Values) -notcontains $false

$resultObj = [ordered]@{
    passed = $allPass
    summary = $summary
}

$resultJson = $resultObj | ConvertTo-Json -Depth 10
$outPath = Join-Path $scriptDir 'evaluate-agentic-result.json'
Set-Content -LiteralPath $outPath -Value $resultJson -Encoding utf8

$gatesPass = @($gates.Values | Where-Object { $_ }).Count
"RESULT p=$n | base hit5=$($summary.base.hitAt5) hit1=$($summary.base.hitAt1) mrr=$($summary.base.mrr) | ag hit5=$($summary.agentic.hitAt5) hit1=$($summary.agentic.hitAt1) mrr=$($summary.agentic.mrr) refined=$($summary.agentic.refinedCaseCount) | neg=$($summary.negativePassRate) | P95ms b=$($summary.baseLatencyP95Ms)/a=$($summary.agenticLatencyP95Ms) amax=$($summary.agenticLatencyMaxMs) | gates=$gatesPass/$($gates.Count) PASS=$allPass | $outPath"
exit $(if ($allPass) { 0 } else { 1 })
