# 画质漫画 · 安装包

版本：iOS 当前 **`1.9.82 (93)`**（安卓源码同步至同号，包按需出）。

## 下载

| 平台 | 位置 | 大小 |
|---|---|---|
| iOS | 本目录 `EnhanceComic-iOS-v1.9.82.ipa`（未签名，需 Sideloadly / AltStore 签名）；也可到 [Release 页 → v1.9.82](https://github.com/xieanranya/yidumanhua-gerenchuangjianban/releases) | 12.3 MB |

## v1.9.82（双端）：画质增强 · 超分（realesr-animevideov3）

按《画质增强-超分选型报告》实装——**模型随包内置（2.38MB），无需下载**：

- **模型**：realesr-animevideov3（Real-ESRGAN 家族，BSD-3-Clause），SRVGGNetCompact 架构，固定输入 256×256 → 输出 1024×1024（4x），已用真实漫画页在浏览器 ORT 中先验证效果
- **管线**：裁白边之后、黑白上色之前——分块推理（tile 256、重叠 8px）→ 拼接 → 裁 pad → 缩回显示宽度 → line-mix（暗部文字/网点保持原图，防 GAN 重画）
- **触发**：阅读设置 → 图像 → **画质增强**：`关` / `自动 · 仅低清页`（源图宽 < 显示宽 60% 才增强，正常源零开销，推荐）/ `全页`
- **体验**：命中页先显示原图，后台 1~3 秒算完自动替换；缓存按增强维度隔离
- 安卓同步实装（源码同号 93），包按需出

### 校验

| 检查项 | 结果 |
|---|---|
| iOS CFBundleShortVersionString / CFBundleVersion | `1.9.82` / `93` |
| 内置超分模型 | `animevideov3_256.onnx` 2.38MB ✓ |
| Android versionName / versionCode（源码同步） | `1.9.82` / `93` |
| CI（iOS）| success · gradle（安卓）| BUILD SUCCESSFUL |

## 之前版本

### v1.9.81：常驻进度条 · v1.9.80：排行榜解析修复 · v1.9.79：竖长页解码修复 · v1.9.78：v7 阅读器重构 · v1.9.76：v6 杂志封面 · v1.9.71：排行接源真实通道
