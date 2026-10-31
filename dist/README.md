# 画质漫画 · 安装包

版本：**双端统一 `1.9.43 (54)` —— 黑白漫画 AI 上色**

## 下载

| 平台 | 位置 | 大小 |
|---|---|---|
| Android | **[Release 页 → EnhanceComic-Android-v1.9.43.apk](https://github.com/xieanranya/yidumanhua-gerenchuangjianban/releases/download/v1.9.43/EnhanceComic-Android-v1.9.43.apk)** | 47 MB |
| iOS | 本目录 `EnhanceComic-iOS-v1.9.43.ipa`（9.7 MB，未签名，需 Sideloadly / AltStore 签名） | 9.7 MB |

- Android 要求 **arm64 真机**（2016 年后的手机全是）；签名没变（`d5084caf…`），直接覆盖安装，书库和登录态都保留。

## 黑白漫画 AI 上色（本版新功能）

阅读在线黑白漫画时打开「黑白上色」，AI 自动给页面涂色。设置 → 阅读器 → 黑白上色。

| | Android | iOS |
|---|---|---|
| 模型 | **DDColor int8**（达摩院 ICCV 2023，写实风）59.1MB | **manga-colorization-v2**（漫画动漫风）58.8MB |
| 来源 | HuggingFace（Apache-2.0），国内走 hf-mirror | 同左（MIT） |
| 推理引擎 | ONNX Runtime（已打包） | 同左 |

- 模型首次打开开关时自动下载（带进度条），只下一次；
- 页面管线：下载 → 还原 → 自动裁白边 → **AI 上色**，开关进缓存 key，来回切不串图；
- 模型没下载完 / 推理失败时自动回退原图，绝不挡阅读；
- 每页首次上色几秒，看过的页有缓存秒开；彩色源和本地书不受影响；
- 两个模型风格不同：DDColor 更「真实」，manga 版更「动漫」，都可以试试。

## 之前版本

### v1.9.42：发现页「12 本 ›」分区全部列表（双端）

### v1.9.36–41（安卓）：滑块登录闪退根治（独立进程）+ EH 登录校验修复

### 校验

| 检查项 | 结果 |
|---|---|
| Android versionCode / versionName | `54` / `1.9.43` |
| iOS CFBundleShortVersionString / CFBundleVersion | `1.9.43` / `54` |
| 包内 `bundle.js` md5（两端） | `ed3b1586…`（引擎未动） |
| onnxruntime | 两端均已打包 ✓ |
