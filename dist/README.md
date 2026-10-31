# 画质漫画 · 安装包

版本：Android 当前 **`1.9.46 (57)`**，iOS 当前 **`1.9.43 (54)`**。

## 下载

| 平台 | 位置 | 大小 |
|---|---|---|
| Android | **[Release 页 → EnhanceComic-Android-v1.9.46.apk](https://github.com/xieanranya/yidumanhua-gerenchuangjianban/releases/download/v1.9.46/EnhanceComic-Android-v1.9.46.apk)** | 47 MB |
| iOS | 本目录 `EnhanceComic-iOS-v1.9.43.ipa`（9.7 MB） | 9.7 MB |

## v1.9.46（Android）：修上色后画面全黑

根因：上色生成的 Bitmap **没写 alpha 通道**（全透明 = 显示黑屏）。
桌面验证时 ImageIO 不看 alpha 所以看不出来，安卓的 Compose 渲染就看出来了。

顺带：模型已就绪时不再触发重复下载（每次点新漫画不会再下载）。

## 黑白漫画 AI 上色

设置 → 阅读器 → 黑白上色。打开开关即自动下载模型（入口行显示进度/状态）。
只对在线阅读生效；失败自动回退原图；每页首次上色几秒，看过的页有缓存秒开。

如果还有异常：`Android/data/com.enhance.comic/files/colorize_error.log` 会记录
每个失败原因（含「模型未下载」「推理异常」），发我即可。

## 之前版本

### v1.9.45：安卓上色三个真根因修复（Optional/256输入/ab输出，桌面验证出彩色）
### v1.9.43–44：黑白上色首版 · v1.9.42：「12 本 ›」分区列表 · v1.9.36–41：登录闪退根治 + EH 登录修复

### 校验

| 检查项 | 结果 |
|---|---|
| Android versionCode / versionName | `57` / `1.9.46` |
| Android 签名 SHA-256 | `d5084caf…`（未变） |
| 包内 `bundle.js` md5 | `ed3b1586…`（引擎未动） |

