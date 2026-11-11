# 画质漫画 · 安装包

版本：**双端同号 `1.9.78 (89)`**。

## 下载

| 平台 | 位置 | 大小 |
|---|---|---|
| iOS | 本目录 `EnhanceComic-iOS-v1.9.78.ipa`（未签名，需 Sideloadly / AltStore 签名）；也可到 [Release 页 → v1.9.78](https://github.com/xieanranya/yidumanhua-gerenchuangjianban/releases) | 9.9 MB |
| Android | [Release 页 → v1.9.78](https://github.com/xieanranya/yidumanhua-gerenchuangjianban/releases)（47 MB，超过 GitHub 文件 API 上限只走 Release） | 47 MB |

## v1.9.78（双端）：在线阅读器 v7 重构（按《在线阅读 UI 重构计划书》）

按用户提供的 PDF 计划书（4 页全文已解析）实装：

- **工具栏**：点画面中央呼出——顶部「章节名 + 书名副标 + 页码胶囊」（画布色渐变衬底），底部「进度条 + 上一话 / 目录 / 设置 / 下一话」四键；禁用态灰、触控 ≥44pt
- **设置面板**：三组 tab（阅读 / 图像 / 显示），每次只显示两三项，占屏约 38-45%；「章节」从设置挪到工具栏「目录」
- **目录**：独立面板呼出，打开自动定位当前话（当前话淡紫高亮 + 「当前」）
- **配色**（计划书定稿）：画布 #101217、面板 #191C23、文字 #F2F3F6、当前值 #B6BBC8、强调淡紫 #CDBBFF（只用于进度、当前选项与「完成」）
- 页码与页内位置在开关工具栏 / 目录 / 设置时保持不变；设置读取与保存逻辑全部沿用

### 校验

| 检查项 | 结果 |
|---|---|
| iOS CFBundleShortVersionString / CFBundleVersion | `1.9.78` / `89` |
| Android versionName / versionCode | `1.9.78` / `89` |
| CI（iOS）| success · gradle（安卓）| BUILD SUCCESSFUL |

## 之前版本

### v1.9.77：杂志海报修复+安卓设置对齐 · v1.9.76：v6 杂志封面 · v1.9.75：排行进首屏 · v1.9.74：v5 极简网格首页 · v1.9.71：排行接源真实通道 · v1.9.69：v4 品牌头
