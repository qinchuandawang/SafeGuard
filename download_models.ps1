# SafeGuard 模型文件下载脚本
# 模型文件超过 Gitee 100MB 限制，无法直接包含在仓库中
# 运行此脚本自动下载所需的模型文件

$models = @(
    @{Name="视频检测模型 (XceptionNet, 245MB)"; Url=""; Path="ai-services/video/pretrained/best_model.pth"},
    @{Name="音频检测模型 (Wav2Vec2 ASVspoof5, 361MB)"; Url=""; Path="ai-services/audio/pretrained/asvspoof-finetuned/model.safetensors"},
    @{Name="预训练基座模型 (Wav2Vec2 Base, 363MB)"; Url=""; Path="ai-services/audio/pretrained/wav2vec2-base/model.safetensors"}
)

Write-Host "=== SafeGuard 模型文件下载 ===" -ForegroundColor Cyan
Write-Host ""
Write-Host "模型文件太大无法直接包含在 Gitee 仓库中，请通过以下方式获取：" -ForegroundColor Yellow
Write-Host ""

# 选项 A: 从组员处拷贝
Write-Host "【方式 1】从组员处拷贝（推荐）" -ForegroundColor Green
Write-Host "  找已经下载好的组员，拷贝以下文件到对应目录："
foreach ($m in $models) {
    Write-Host "    $($m.Path)"
}
Write-Host ""

# 选项 B: 使用 HuggingFace 镜像下载
Write-Host "【方式 2】从 HuggingFace 镜像站下载" -ForegroundColor Green
Write-Host "  需要先打开 VPN（huggingface.co 被屏蔽）"
Write-Host ""

Write-Host "  视频模型 (需先训练)：目前无公开下载地址，找组员拷贝即可" -ForegroundColor Gray
Write-Host ""

Write-Host "  音频微调模型："
Write-Host "  pip install huggingface_hub"
Write-Host '  $env:HF_ENDPOINT="https://hf-mirror.com"'
Write-Host "  huggingface-cli download DavidCombei/wav2vec2-base-ASVSpoof5_TUC-N --local-dir ai-services/audio/pretrained/asvspoof-finetuned"
Write-Host ""

Write-Host "  音频基座模型（回退用）："
Write-Host "  huggingface-cli download facebook/wav2vec2-base --local-dir ai-services/audio/pretrained/wav2vec2-base"
Write-Host ""

Write-Host "【方式 3】从训练脚本自行训练" -ForegroundColor Green
Write-Host "  视频: python ai-services/video/train.py"
Write-Host "  音频: python ai-services/audio/src/train_wav2vec2.py"
Write-Host ""

Write-Host "完成后验证文件是否就位：" -ForegroundColor Cyan
Write-Host "  ai-services/video/pretrained/best_model.pth"
Write-Host "  ai-services/audio/pretrained/asvspoof-finetuned/model.safetensors"
Write-Host "  ai-services/audio/pretrained/wav2vec2-base/model.safetensors"