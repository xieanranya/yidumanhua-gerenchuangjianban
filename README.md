# 画质漫画 iOS

本地漫画阅读器（iPhone / iPad），和安卓版「画质漫画」是同一套设计。
本地导入 ZIP / CBZ / RAR / CBR / 7Z / TAR / PDF / 图片文件夹，无网络、无账号、无追踪。

当前版本 **1.9.4**（`versionCode` 15，与安卓端同号）：书库 + 三种阅读模式 + 画质增强 +
自动裁白边 + 蓝光过滤 + 章节。支持 ZIP/CBZ、RAR/CBR、7Z/CB7、TAR 系列、PDF、图片文件夹。

> 安卓端源码在 [`android/`](android/)，本文件是 iOS 端的说明。
> 成品安装包（双端）见 [`dist/`](dist/)。

---

## 在 Windows 上出 IPA（推荐，不需要 Mac）

苹果的 IPA 必须在 macOS 上编译。用 GitHub 的免费 macOS 机器即可，全程在浏览器里点：

1. 注册 / 登录 GitHub，新建一个空仓库（Private 也可以）。
2. 把**这个文件夹**（`EnhanceComic-iOS` 里的全部内容）上传为仓库根目录。
   网页上传即可：`Add file → Upload files`，把 `EnhanceComic`、`EnhanceComic.xcodeproj`、`.github` 一起拖进去。
   > 注意 `.github` 是隐藏开头的文件夹，必须一起上传，否则不会触发自动编译。
3. 打开仓库的 **Actions** 标签，等 `build-ios` 跑完（约 3~5 分钟，首次更久）。
4. 进这次运行，页面底部 **Artifacts** 下载 `EnhanceComic-unsigned-ipa`，解压得到 `EnhanceComic-unsigned.ipa`。

工作流文件：`.github/workflows/build-ios.yml`（`macos-15` + Xcode 16，`CODE_SIGNING_ALLOWED=NO` 出未签名包）。

## 装到 iPhone 上

未签名的 IPA 需要侧载（自签）：

- **Sideloadly**（Windows 可用，最省事）：插上 iPhone，选中 IPA，填 Apple ID，Start。
- **AltStore / SideStore**：装一次后可以无线续签。
- **爱思助手 / TrollStore**（视 iOS 版本）。

注意：

- 免费 Apple ID 签名 **7 天过期**，过期后重新签一次即可（书库和进度保存在 App 沙盒里，重签会清空，重新导入就行）。
- 付费开发者账号（$99/年）签名有效期 1 年。
- iOS 16.0 及以上。

想改 Bundle ID（避免和别的 App 冲突）：`EnhanceComic.xcodeproj` → target `EnhanceComic` → General → Bundle Identifier，或直接改 `project.pbxproj` 里的 `PRODUCT_BUNDLE_IDENTIFIER`。

## 在 Mac 上编译

```bash
open EnhanceComic.xcodeproj
# Xcode 16+，选中真机，Signing 里选自己的 Team，Run
```

命令行出未签名 IPA：

```bash
xcodebuild -project EnhanceComic.xcodeproj -scheme EnhanceComic \
  -configuration Release -sdk iphoneos -destination 'generic/platform=iOS' \
  -derivedDataPath build CODE_SIGNING_ALLOWED=NO build

mkdir -p Payload && cp -R build/Build/Products/Release-iphoneos/EnhanceComic.app Payload/
zip -qry EnhanceComic-unsigned.ipa Payload
```

---

## 代码结构

```
EnhanceComic/
  App/EnhanceComicApp.swift      入口
  Model/Models.swift             书、阅读设置、增强参数、分页模型、章节
  Data/ZipReader.swift           极简 ZIP/CBZ 读取（中央目录 + raw deflate）
  Data/Inflate.swift             自研 RFC1951 解压器（移植 zlib puff.c）
  Data/LibArchive.swift          RAR / 7Z / TAR：dlopen 系统 libarchive
  Data/BookSession.swift         页数、长宽比扫描、单页解码（ImageIO / CGPDF）、章节切分
  Data/Enhancer.swift            Core Image 画质增强 + 白边检测
  Data/LibraryStore.swift        书库持久化 + 导入 + 封面生成
  UI/LibraryView.swift           书库首页（导入 / 继续阅读 / 封面网格 / 长按删除）
  UI/ReaderView.swift            阅读页（翻页 / 纵向滚动 / 横向滚动、缩放、章节）
  UI/ReaderSettingsView.swift    阅读设置面板
```

## 已实现（0.2）

- 书库首页：继续阅读卡片、封面网格（渲染第一页做真实封面）、进度条、长按删除
- 导入：ZIP / CBZ / RAR / CBR / 7Z / TAR / PDF 多选，或整个图片文件夹（复制进 App 沙盒，原文件不动）
- 7Z / TAR 系列导入时解包成目录（solid block 没法按页随机读），RAR 按页流式读
- 三种阅读模式：单页翻页 / 纵向连续滚动（条漫）/ 横向连续滚动
- 画质增强（GPU / Core Image）：锐化、降噪平滑、S 曲线对比度、亮度、对比度、伽马
- 自动裁掉白边（80px 缩略图做墨迹统计，裁掉四周空白）
- 宽页自动拆成左右两半、从右到左阅读
- 章节：压缩包里按子文件夹自动分章，顶栏可打开目录跳转
- 点击左右翻页、滑动翻页、双指缩放、退出记住进度
- 中文文件名兼容（ZIP 内 GBK 文件名会兜底解码）

## 还没做

- 书签 UI
- 双页并排显示（现在只有宽页拆分）

## 和安卓版的实现差别

安卓用 GLES 3.1 的三个 compute shader 做增强（锐化 / Kuwahara 降噪 / 对比度），
iOS 这边用 Core Image 的 `sharpenLuminance` / `noiseReduction` / `toneCurve`，
同样跑在 GPU 上，效果接近但省掉了手写 Metal shader 的维护成本。

## 一个已知的编译风险

工程文件是根据 Xcode 16 的格式手写的（`objectVersion = 77`，文件夹自动同步）。
如果 Xcode 报 “The project … cannot be opened because it is in a future Xcode project file format”，
说明 Xcode 版本低于 16，需要升级，或把 `objectVersion` 改成 56 并在 Xcode 里手动添加文件。
