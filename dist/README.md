# 画质漫画 · 安装包

版本：iOS 当前 **`1.9.59 (70)`**，Android 当前 `1.9.54 (65)`（安卓对齐沉浸式 UI 时追平）。

## 下载

| 平台 | 位置 | 大小 |
|---|---|---|
| iOS | 本目录 `EnhanceComic-iOS-v1.9.59.ipa`（9.8 MB，未签名，需 Sideloadly / AltStore 签名）；也可到 [Release 页 → v1.9.59](https://github.com/xieanranya/yidumanhua-gerenchuangjianban/releases) | 9.8 MB |
| Android | [Release 页 → v1.9.54](https://github.com/xieanranya/yidumanhua-gerenchuangjianban/releases/download/v1.9.54/EnhanceComic-Android-v1.9.54.apk) | 47 MB |

## v1.9.59（iOS）：v3 沉浸式 UI

按用户确认的网页交互稿（`ui-preview/v3-interactive.html`）把整套沉浸式设计搬进实机：

- **沉浸头图**：封面大图直接铺到状态栏后面，白色大标题压图，头图底部渐变融进内容底色；
  上滚时头图比内容滚得慢（视差下沉）+ 标题淡出，下拉时头图跟着拉长；
- **四个 Tab 全套沉浸化**：首页头图 = 今日最热封面；书架 = 在读第一本的封面；发现 = 紫渐变；我的 = 粉渐变；
- **发现页**：源列表升级为毛玻璃悬浮卡（渐变圆标 + 登录状态 + 绿点）；点源进单源页，同样沉浸头图 + 左上毛玻璃返回钮；
- **详情页**：封面大图头图 + 简介 + 换源 chips + 章节网格，返回 / 账号都是毛玻璃圆钮；
- **底栏毛玻璃**（内容透出）+ 选中态改粉色；白天主题底色改浅灰 `#F4F4F7`，卡片白底悬浮；
- 状态栏在沉浸页永远白字（全局单点控制，切页/弹层自动切换），阅读器白字压黑底。

### 校验

| 检查项 | 结果 |
|---|---|
| iOS CFBundleShortVersionString / CFBundleVersion | `1.9.59` / `70` |
| 包内 `bundle.js` md5 | `ed3b1586…`（引擎未动，与本地一致） |
| CI | success |

## 之前版本

### v1.9.56-58：UI 打磨 · v1.9.55：主页方案 B 条带流 · v1.9.53-54：上色最终形态 + 安卓 Aidoku 化第一轮 · v1.9.50-52：上色多轮修复 · v1.9.43-49：上色打磨 · v1.9.42：「12 本 ›」 · v1.9.36-41：登录闪退根治 + EH 登录修复
