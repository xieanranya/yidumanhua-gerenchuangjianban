# 画质漫画 · 安装包

版本：双端当前 **`1.9.87 (98)`**。

## 下载

| 平台 | 位置 | 大小 |
|---|---|---|
| iOS | 本目录 `EnhanceComic-iOS-v1.9.87.ipa`（未签名，需 Sideloadly / AltStore 签名）；也可到 [Release 页 → v1.9.87](https://github.com/xieanranya/yidumanhua-gerenchuangjianban/releases) | 12.0 MB |
| Android | [Release 页 → v1.9.87](https://github.com/xieanranya/yidumanhua-gerenchuangjianban/releases) 资产 `EnhanceComic-Android-v1.9.87.apk`（固定签名，直接覆盖安装） | ≈50 MB |

## v1.9.87（双端）：排行榜升一级 Tab（首页/排行榜/书架/我的），发现降级为首页「分类浏览」入口

## v1.9.86（双端）：下载中心进「我的」页 + 导出（整本 CBZ / 单张 JPG·PNG·WebP）

## v1.9.85（双端）：新增漫画下载（离线阅读）

- **入口**：在线漫画详情页新增「**下载全本**」按钮（状态化：排队/下载中 x%/暂停/已下载/部分失败，点击暂停⇄继续）
- **调度**：同时最多 **5 本**（超出自动排队）；每本内章节串行、章内页图并行 **3 路**，不限速——快慢由网络质量决定
- **落盘结构**：书库 `Downloads/<源_key>/` 下每本一个目录，`章节子目录（0001 - 章节名）/页图`；完成后自动注册成本地书（FOLDER 型），**书架直接可见、点开即读**（iOS 的 folder 阅读器本次补上了「子目录即章节」）
- **断点与续传**：章节内按「页文件是否存在」续传，任务队列整体落盘 `tasks.json`，杀进程重启自动续跑；失败章节点「继续」自动重试
- **乱序还原**：禁漫等分块乱序源在下载时即还原成整页（num>1 的页 JPEG 重编码 q92；普通页存原始字节零损耗）
- 下载管理 UI：书架第三段「**下载**」（iOS）/ 筛选 chip「**下载**」（安卓）——每本显示封面、来源、进度条、当前章节，可暂停/继续/删除/阅读

### 校验

| 检查项 | 结果 |
|---|---|
| iOS CFBundleShortVersionString / CFBundleVersion | `1.9.85` / `96` |
| Android versionName / versionCode | `1.9.85` / `96`（签名指纹 d5084caf… 不变） |
| CI（iOS）| success · gradle（安卓）| BUILD SUCCESSFUL |

## v1.9.84：画质增强效果修复（文字发虚/纹理怪）

跳过闸（源宽≥显示宽/长边超限的页直接用原图）+ 处理上限 1024→1536 + line-mix 掩码 0.45→0.60 + 原图侧轻度 unsharp 锐化文字。

## v1.9.83：修复画质增强「黑横带」

真重叠分块（步进 240/tile 256/8px 上下文环）+ 拼合画布兜底底图，任何 tile 失败回退放大原图。

## v1.9.82：画质增强 · 超分（realesr-animevideov3，模型随包内置）

## 之前版本

### v1.9.81：常驻进度条 · v1.9.80：排行榜解析修复 · v1.9.79：竖长页解码修复 · v1.9.78：v7 阅读器重构 · v1.9.76：v6 杂志封面 · v1.9.71：排行接源真实通道
