# FaceForensics++ 数据访问申请草稿

请在官方 Google Form 中由申请人本人审阅、补充真实信息并提交：

官方申请表：

https://docs.google.com/forms/d/e/1FAIpQLSdRRR3L5zAv6tQ_CKxmK4W96tAab_pfBu2EKAgQbeDVhmXagg/viewform

## 英文用途说明

```text
I am applying for access to the FaceForensics++ dataset for an academic and educational project on deepfake detection. The project is a student portfolio project for backend, full-stack, and AI application development recruitment. The dataset will be used only for non-commercial research, model training, validation, and engineering evaluation of Xception/EfficientNet-style video forgery detectors. We will not redistribute the raw videos, download scripts, passwords, or any derived dataset. The project repository will contain only code, metadata references, and reproducible processing instructions. Access to the data will be restricted to the project team and the data will be deleted when the permitted research use ends.
```

## 建议填写的信息

- 姓名、学校、机构邮箱：填写真实信息。
- 研究/教育用途：如实填写，不要声称商业部署或代表公司。
- 项目方向：DeepFake 视频检测、模型工程化、数据处理和评测。
- 预计使用范围：优先下载 `c23` 视频，后续根据许可决定是否下载 masks 或 models。

申请通过后，官方会发送下载脚本链接。请把官方脚本放入：

```text
datasets/credentials/faceforensicspp/
```

不要把 GitHub 密码、2FA 验证码、下载密码或邮件内容直接发送给我。你只需告诉我脚本文件路径和已获授权，我会在本地执行官方脚本并校验结果。
