# 画质漫画 · 安装包

版本：iOS 当前 **`1.9.60 (71)`**，Android 当前 `1.9.54 (65)`（安卓对齐时追平）。

## 下载

| 平台 | 位置 | 大小 |
|---|---|---|
| iOS | 本目录 `EnhanceComic-iOS-v1.9.60.ipa`（未签名，需 Sideloadly / AltStore 签名）；也可到 [Release 页 → v1.9.60](https://github.com/xieanranya/yidumanhua-gerenchuangjianban/releases) | 9.8 MB |
| Android | [Release 页 → v1.9.54](https://github.com/xieanranya/yidumanhua-gerenchuangjianban/releases/download/v1.9.54/EnhanceComic-Android-v1.9.54.apk) | 47 MB |

## v1.9.60（iOS）：源管理优化 + 禁漫线路 + 自动签到

- **源管理页沉浸化**：蓝渐变头图 + 返回毛玻璃圆钮；每个源一张毛玻璃卡（渐变图标 + 登录态绿点 + 开关 + 排序箭头）；
- **禁漫新增「线路」**：线路 / 分流1~4 / 恢复默认。选中的分流优先使用，连不上仍自动换下一条（不选就是自动轮换）；
- **禁漫新增「自动签到」**：开关打开后，每次打开 App 自动签到（已登录才生效，一天一次，失败下次再试）；
  也可在源管理里手动「立即签到」，签到状态实时显示（今天已签到 ✓）；
- 引擎层新增通用 `invoke`（调用源自己暴露的能力），签到状态与线路选择两端共用同一份引擎。

### 校验

| 检查项 | 结果 |
|---|---|
| iOS CFBundleShortVersionString / CFBundleVersion | `1.9.60` / `71` |
| 包内 `bundle.js` md5 | `ee0ce47f…`（新增 invoke，两端一致） |
| CI | success |

## 之前版本

### v1.9.59：v3 沉浸式 UI · v1.9.56-58：UI 打磨 · v1.9.55：主页方案 B 条带流 · v1.9.53-54：上色最终形态 · v1.9.43-52：上色打磨 · v1.9.36-41：登录闪退根治 + EH 登录修复
