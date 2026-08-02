param(
    [Parameter(Mandatory = $true)]
    [ValidateSet("submit", "hotspot", "cache")]
    [string]$Scenario,
    [string]$JMeter = "jmeter",
    [string]$HostName = "127.0.0.1",
    [int]$Port = 8080,
    [string]$Protocol = "http",
    [string]$VideoFile = "",
    [string]$Authorization = "",
    [int]$Threads = 0,
    [int]$Loops = 0,
    [int]$RampSeconds = 0,
    [string]$ClientIpPrefix = ""
)

$ErrorActionPreference = "Stop"
$root = (Resolve-Path (Join-Path $PSScriptRoot "../..")).Path
$plans = @{
    submit  = "01-async-video-submit.jmx"
    hotspot = "02-hotspot-singleflight.jmx"
    cache   = "03-llm-cache.jmx"
}
$defaults = @{
    submit  = @{ Threads = 50;  Loops = 4; Ramp = 5; Prefix = "10.71.1." }
    hotspot = @{ Threads = 200; Loops = 1; Ramp = 1; Prefix = "10.72.1." }
    cache   = @{ Threads = 20;  Loops = 5; Ramp = 2; Prefix = "10.73.1." }
}

$settings = $defaults[$Scenario]
if ($Threads -le 0) { $Threads = $settings.Threads }
if ($Loops -le 0) { $Loops = $settings.Loops }
if ($RampSeconds -le 0) { $RampSeconds = $settings.Ramp }
if ([string]::IsNullOrWhiteSpace($ClientIpPrefix)) { $ClientIpPrefix = $settings.Prefix }
if ([string]::IsNullOrWhiteSpace($VideoFile)) {
    $VideoFile = Join-Path $root "test_data/video/f6699295ad7d66734aa1d1d63004f7fb.mp4"
}

$runId = "{0}-{1}" -f $Scenario, (Get-Date -Format "yyyyMMdd-HHmmss")
$resultDir = Join-Path $PSScriptRoot "results/$runId"
$reportDir = Join-Path $resultDir "html"
$jtl = Join-Path $resultDir "result.jtl"
New-Item -ItemType Directory -Path $resultDir -Force | Out-Null

$jmeterCommand = Get-Command $JMeter -ErrorAction SilentlyContinue
if ($null -eq $jmeterCommand) {
    throw "未找到 JMeter。请通过 -JMeter 指定 jmeter.bat，或把 JMeter bin 加入 PATH。"
}

$arguments = @(
    "-n",
    "-t", (Join-Path $PSScriptRoot $plans[$Scenario]),
    "-Jprotocol=$Protocol",
    "-Jhost=$HostName",
    "-Jport=$Port",
    "-Jauthorization=$Authorization",
    "-Jvideo_file=$VideoFile",
    "-Jrun_id=$runId",
    "-Jthreads=$Threads",
    "-Jloops=$Loops",
    "-Jramp_seconds=$RampSeconds",
    "-Jsync_size=$Threads",
    "-Jclient_ip_prefix=$ClientIpPrefix",
    "-Jresult_dir=$resultDir",
    # 保留异步任务响应体，便于从 taskId 查询最终状态。
    "-Jjmeter.save.saveservice.response_data=true",
    "-l", $jtl,
    "-e",
    "-o", $reportDir
)

Push-Location $root
try {
    Write-Host "场景: $Scenario"
    Write-Host "线程/循环/升压: $Threads / $Loops / ${RampSeconds}s"
    Write-Host "结果目录: $resultDir"
    & $jmeterCommand.Source @arguments
    $exitCode = $LASTEXITCODE
    $failedSamples = @()
    if (Test-Path -LiteralPath $jtl) {
        $failedSamples = @(Import-Csv -LiteralPath $jtl | Where-Object { $_.success -ne "true" })
    }
    if ($exitCode -ne 0 -and (!(Test-Path -LiteralPath $jtl) -or $failedSamples.Count -gt 0)) {
        throw "JMeter 执行失败，退出码: $exitCode，失败采样数: $($failedSamples.Count)"
    }
    if ($exitCode -ne 0) {
        Write-Warning "jmeter.bat 返回 $exitCode，但 JTL 中全部采样成功，按报告结果判定为通过。"
    }
} finally {
    Pop-Location
}

Write-Host "测试完成。HTML 报告: $reportDir/index.html"
