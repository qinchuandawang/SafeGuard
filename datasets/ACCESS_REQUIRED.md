# 数据集授权操作清单

本目录不保存账号密码、验证码、浏览器 Cookie 或身份证件。需要接受许可证的数据必须由数据申请人本人登录官方页面并确认；授权后的官方压缩包、临时下载链接或下载令牌放入 `datasets/credentials/`，该目录已被 Git 忽略。

## ASVspoof 2019 LA

官方入口：https://doi.org/10.7488/ds/2555

申请并下载以下内容：

- LA train
- LA development
- LA evaluation
- LA protocol / metadata

推荐把官方压缩包放入：

```text
datasets/audio/asvspoof2019_la/downloads/
```

如果官方提供临时下载链接，将每行一个链接写入：

```text
datasets/credentials/asvspoof2019-download-urls.txt
```

不要提供学校统一身份认证密码、验证码或浏览器 Cookie。

## FaceForensics++

官方入口：https://github.com/ondyari/FaceForensics

使用学校邮箱提交官方申请，研究用途可如实说明：

```text
Academic research and educational project for deepfake detection.
The dataset will be used to train and evaluate Xception/EfficientNet-based
binary classifiers. It will not be redistributed or used commercially.
```

授权后把官方脚本或令牌文件放入：

```text
datasets/credentials/faceforensicspp/
```

首轮只下载 `c23` 的 original、Deepfakes、Face2Face、FaceSwap 和 NeuralTextures，控制磁盘占用。

申请用途草稿见 `datasets/faceforensics-application-draft.md`。官方仓库说明数据下载脚本必须通过 Google Form 审核后获取；GitHub 仓库本身只包含代码，不包含视频数据。

## DFDC

官方入口：https://www.kaggle.com/c/deepfake-detection-challenge/data

必须由本人登录 Kaggle、接受竞赛数据条款并创建 API Token。凭证应保存到 Kaggle 默认目录，不要写入项目或聊天：

```text
C:\Users\34111\.kaggle\kaggle.json
```

完整 DFDC 及解压结果可能超过当前可用磁盘，不作为首轮训练集；后续只选择少量官方分片做跨数据集评测。
