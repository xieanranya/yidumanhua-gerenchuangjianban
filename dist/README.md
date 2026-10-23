# 画质漫画 · 安装包

| 平台 | 文件 | 大小 | 要求 |
|---|---|---|---|
| Android | `EnhanceComic-Android-v1.3.apk` | 约 13.7 MB | Android 8.0 (API 26) 及以上，直接覆盖安装 |
| iOS | `EnhanceComic-iOS-v1.3.ipa` | 约 280 KB | iOS 16.0 及以上，**未签名**，需 Sideloadly / AltStore 签名 |

版本号：Android `1.3 (versionCode 5)`，iOS `1.3 (build 5)`。两端已统一，方便对照。

## v1.3：终于可以直接覆盖安装

之前每次更新都要先卸载，原因是两处：

1. **`versionName` 曾经回退**（v0.3 是 `1.2`，v0.4 写成了 `0.4`）。国产 ROM 的安装器在覆盖安装时会比较版本名，看到变小就判定为「版本不匹配」，要求先卸载。
2. **签名密钥没有固定在项目里**，只依赖本机 `~/.android/debug.keystore`。换了构建环境，密钥会重新生成，签名就不一致了。

现在的做法：

- 签名密钥固定为项目内的 `key/enhancecomic.jks`，指纹 `SHA-256 D5:08:4C:AF:…:9F:A4:2D`，与 v0.1 起的所有历史版本**完全一致**，所以这次也不需要卸载，直接装就行，书库和进度都在。
- 版本号只增不减：`versionCode` 从 5 起，之后 6、7、8……；`versionName` 从 `1.3` 起，之后 `1.4`、`1.5`……
- 书架标题下会显示 `v1.3 (5)`，装完一眼能确认是不是新版。
- 打包改成 release 构建（不再是 debug 包），体积从 20.3 MB 降到 13.7 MB，也不再有 debug 包的额外校验。

## 已实现

**读取**

- ZIP / CBZ、RAR / CBR、PDF、图片文件夹
- 压缩包内子文件夹自动分章节
- 中文文件名（GBK）兜底解码
- 导入时按文件头识别格式，扩展名缺失也能进

**阅读**

- 三种模式：单页翻页 / 竖向连续滚动（无缝拼接）/ 横向连续滚动
- 双页并排（横屏时自动启用），从右到左时早的一页在右边
- 宽页自动拆成两页
- 自动切白边
- 跟手翻页：拖动时当前页跟着手指走、下一页同步滑入，超过 28% 或快速甩动即翻过
- 双指缩放 1–8 倍（锚定手指位置）、双击 1× / 2.6× 切换
- 边缘橡皮筋回弹、滚动惯性
- 音量键翻页（可在设置里关）
- 书签、进度记忆、章节跳转

**画质增强**

- Android：CPU 版 USM 锐化 / 盒式降噪 / 曲线对比度 / 伽马，解码时一次算完
- iOS：Core Image（`sharpenLuminance` / `noiseReduction` / `toneCurve` / `gammaAdjust`）
- 都可以单独关掉，关掉等于纯解码

## 安装

**Android**：手机下载 APK 直接安装，允许「安装未知来源应用」即可。**直接从旧版覆盖安装**，不用卸载。

**iOS**：
1. Windows 装 [Sideloadly](https://sideloadly.io/)，iPhone 用数据线连电脑
2. 把 `EnhanceComic-iOS-v1.3.ipa` 拖进 Sideloadly，填自己的 Apple ID
3. 点 Start，按提示在 iPhone「设置 → 通用 → VPN与设备管理」里信任开发者证书
4. 免费 Apple ID 签 7 天，到期重新签一次即可

## 已知差异

| 能力 | Android | iOS |
|---|---|---|
| ZIP / CBZ | ✅ | ✅ |
| RAR / CBR | ✅ | ❌（需内置 unrar 源码） |
| PDF | ✅ | ✅ |
| 图片文件夹 | ✅ | ✅ |
| 音量键翻页 | ✅ | ❌（iOS 不允许） |
| 自动切白边 | ✅ | ✅ |
| 章节 | ✅ | ✅ |
| 画质增强 | ✅ CPU | ✅ Core Image |
