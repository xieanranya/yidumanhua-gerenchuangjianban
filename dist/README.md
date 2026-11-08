# 画质漫画 · 安装包

版本：iOS 当前 **`1.9.67 (78)`**，Android 当前 `1.9.54 (65)`（安卓对齐时追平）。

## 下载

| 平台 | 位置 | 大小 |
|---|---|---|
| iOS | 本目录 `EnhanceComic-iOS-v1.9.67.ipa`（未签名，需 Sideloadly / AltStore 签名）；也可到 [Release 页 → v1.9.67](https://github.com/xieanranya/yidumanhua-gerenchuangjianban/releases) | 9.8 MB |
| Android | [Release 页 → v1.9.54](https://github.com/xieanranya/yidumanhua-gerenchuangjianban/releases/download/v1.9.54/EnhanceComic-Android-v1.9.54.apk) | 47 MB |

## v1.9.67（iOS）：首页/书架头图改回纯渐变

实机反馈：头图用漫画封面后，封面自带的大字标题跟「画质漫画」白字标题叠在一起，非常乱，而且头图与下面的 Hero 卡重复显示同一本。

- 首页头图改回**纯渐变**（夜间绿青 / 白天紫罗兰，跟随主题）——这才是网页版交互稿的原始设计；
- 书架头图同样改纯渐变；
- 封面只出现在 Hero 卡（今日最热）和网格里，头图保持干净。

### 校验

| 检查项 | 结果 |
|---|---|
| iOS CFBundleShortVersionString / CFBundleVersion | `1.9.67` / `78` |
| CI | success |

## 之前版本

### v1.9.66：发现页单页直显 · v1.9.65：全源线路面板+测速 · v1.9.63-64：禁漫官方线路+图片源 · v1.9.62：哔咔登录态 · v1.9.61：哔咔签到 · v1.9.60：源管理优化 · v1.9.59：v3 沉浸式 UI
