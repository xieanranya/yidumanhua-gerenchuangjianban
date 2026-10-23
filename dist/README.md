# 安装包下载

| 文件 | 平台 | 说明 |
|---|---|---|
| `EnhanceComic-Android-v0.2.apk` | Android 8.0+ | 直接安装，需允许「安装未知来源应用」 |
| `EnhanceComic-iOS-v0.2.ipa` | iPhone / iPad (iOS 16+) | 未签名，需用 Sideloadly / AltStore 自签，免费 Apple ID 7 天有效 |

## Android 安装

1. 手机浏览器下载 apk
2. 点击安装，允许安装未知来源应用
3. 打开「画质漫画」→ 导入漫画（ZIP / CBZ / RAR / CBR / PDF / 图片文件夹）

实时画质增强需要 GLES 3.1，2016 年之后的机器基本都支持；不支持的机器会自动退到普通渲染，仍然可以正常看。

## iPhone 安装

1. 电脑装 Sideloadly，iPhone 连电脑并「信任此电脑」
2. 把 ipa 拖进 Sideloadly，填 Apple ID，Start
3. 手机：设置 → 通用 → VPN与设备管理 → 开发者APP → 信任

免费 Apple ID 签名 7 天过期，过期重签一次即可（App 内书库会被清空，重新导入就行）。

## 版本状态 (v0.2)

- 书库首页：导入 / 继续阅读 / 封面网格（真实首页做封面）/ 长按删除
- 三种阅读模式：单页翻页 / 纵向连续滚动 / 横向连续滚动
- 画质增强：锐化、降噪、对比度、亮度、伽马（安卓 GLES compute，iOS Core Image）
- 自动切掉白边、宽页自动拆双页、从右到左阅读
- 章节：压缩包内按子文件夹自动分章，顶栏打开目录跳转
- 安卓额外：RAR / CBR 支持、音量键翻页

## 两端差异

| 能力 | Android | iOS |
|---|---|---|
| ZIP / CBZ / PDF / 文件夹 | 有 | 有 |
| RAR / CBR | 有 | 无（需额外编译 unrar 源码） |
| 画质增强 | GLES compute shader | Core Image |
| 音量键翻页 | 有 | 无 |
| 缩放算法（Lanczos3 等） | 四种可选 | 系统高质量插值 |
