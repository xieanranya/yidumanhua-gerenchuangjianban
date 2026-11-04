# 画质漫画 · 安装包

版本：iOS 当前 **`1.9.55 (66)`**，Android 当前 `1.9.54 (65)`（下个安卓包追平 66）。

## 下载

| 平台 | 位置 | 大小 |
|---|---|---|
| iOS | 本目录 `EnhanceComic-iOS-v1.9.55.ipa`（9.7 MB，未签名，需 Sideloadly / AltStore 签名） | 9.7 MB |
| Android | [Release 页 → v1.9.54](https://github.com/xieanranya/yidumanhua-gerenchuangjianban/releases/download/v1.9.54/EnhanceComic-Android-v1.9.54.apk) | 47 MB |

## v1.9.55（iOS）：主页方案 B 条带流

按用户选定的方案 B 重构主页：

- 大标题「画质漫画」+ 右上圆形搜索按钮（独立搜索框删除）；
- **「继续阅读」删除**（与书架重复）；
- 热门内容拆成三条带（数据扩到 18 本）：
  - **今日热门**：横排大卡（118pt）
  - **本周上升最快**：横排中卡（96pt）
  - **为你挑的**：3 列网格
- 大标题升级 iOS Large-Title 风格（30pt 粗体 + 操作钮槽）；
- 发现页 / 我的页的 Aidoku 化下一轮做。

### 校验

| 检查项 | 结果 |
|---|---|
| iOS CFBundleShortVersionString / CFBundleVersion | `1.9.55` / `66` |
| 包内 `bundle.js` md5 | `ed3b1586…`（引擎未动） |

## 之前版本

### v1.9.53-54：上色最终形态 + 安卓 Aidoku 化第一轮 · v1.9.50-52：上色多轮修复 · v1.9.43-49：上色打磨 · v1.9.42：「12 本 ›」 · v1.9.36-41：登录闪退根治 + EH 登录修复
