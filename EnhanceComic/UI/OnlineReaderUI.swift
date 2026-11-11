import SwiftUI

/// 在线阅读器自己的视觉（v7 · 按《在线阅读 UI 重构计划书》定稿）。
///
/// 配色取自计划书：画布 #101217 / 面板 #191C23 / 文字 #F2F3F6 / 当前值 #B6BBC8 / 强调淡紫 #CDBBFF。
/// 淡紫只用在阅读进度、当前选项与「完成」，其余都是深墨衬底上的白灰文字。
/// 结构：点画面中央呼出上下浮层 —— 顶部章节名、底部「上一话 / 目录 / 设置 / 下一话」四键；
/// 设置按「阅读 / 图像 / 显示」分三组，每次只显示两三项。
enum OnlineSkin {
    static let accent = Color(hex: 0xCDBBFF)                            // 强调淡紫
    static let canvas = Color(hex: 0x101217)                            // 阅绘画布
    static let sheet  = Color(hex: 0x191C23)                            // 面板
    static let hair   = Color.white.opacity(0.065)
    static let fg     = Color(hex: 0xF2F3F6)                            // 文字
    static let value  = Color(hex: 0xB6BBC8)                            // 当前值
    static let disabled = Color(hex: 0x4A4D58)
    static let dim    = Color.white.opacity(0.48)
    static let dim2   = Color.white.opacity(0.30)
    static let scrim  = Color.black.opacity(0.46)
}

// MARK: - 顶栏（章节名衬底）

/// 返回 / 章节名+书名 / 页码。衬底是画布色的顶部渐变 —— 画面不会被切掉一块。
struct OnlineTopBar: View {
    let title: String
    let subtitle: String
    let pageLabel: String
    let onBack: () -> Void

    var body: some View {
        HStack(spacing: 4) {
            Button(action: onBack) {
                Image(systemName: "chevron.left")
                    .font(.system(size: 17, weight: .semibold))
                    .foregroundColor(.white)
                    .frame(width: 44, height: 44)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)

            VStack(alignment: .leading, spacing: 3) {
                Text(title.isEmpty ? "阅读" : title)
                    .font(.system(size: 15, weight: .bold))
                    .foregroundColor(OnlineSkin.fg)
                    .lineLimit(1)
                if !subtitle.isEmpty {
                    Text(subtitle)
                        .font(.system(size: 10))
                        .foregroundColor(OnlineSkin.value)
                        .lineLimit(1)
                }
            }

            Spacer(minLength: 0)

            Text(pageLabel)
                .font(.system(size: 10.5))
                .foregroundColor(OnlineSkin.value)
                .lineLimit(1)
                .monospacedDigit()
                .padding(.horizontal, 10)
                .padding(.vertical, 4)
                .background(Capsule().fill(Color.white.opacity(0.08)))
        }
        .padding(.horizontal, 8)
        .padding(.top, 4)
        .frame(height: 58)
        .background(
            LinearGradient(
                colors: [OnlineSkin.canvas.opacity(0.96), OnlineSkin.canvas.opacity(0.60), OnlineSkin.canvas.opacity(0)],
                startPoint: .top,
                endPoint: .bottom
            )
            .ignoresSafeArea(edges: .top)
        )
    }
}

// MARK: - 底部栏（进度 + 四键）

/// 进度行（页码 + 细进度条 + 章节序号）+「上一话 / 目录 / 设置 / 下一话」。
/// 翻页类布局进度条可拖跳页；条漫类只做展示（位置本来就是连续的）。
struct OnlineBottomBar: View {
    let current: Int
    let total: Int
    let chapterLabel: String
    let hasPrev: Bool
    let hasNext: Bool
    let chaptersAvailable: Bool
    let seekable: Bool
    let onSeek: (Int) -> Void
    let onPrev: () -> Void
    let onContents: () -> Void
    let onSettings: () -> Void
    let onNext: () -> Void

    private var ratio: CGFloat {
        guard total > 1 else { return 1 }
        return CGFloat(min(current + 1, total)) / CGFloat(total)
    }

    var body: some View {
        VStack(spacing: 0) {
            HStack(spacing: 10) {
                Text("\(min(current + 1, max(total, 1))) / \(total)")
                    .font(.system(size: 9.5))
                    .foregroundColor(OnlineSkin.value)
                    .monospacedDigit()

                GeometryReader { geo in
                    ZStack(alignment: .leading) {
                        Capsule().fill(Color.white.opacity(0.14)).frame(height: 3)
                        Capsule().fill(OnlineSkin.accent)
                            .frame(width: max(3, geo.size.width * ratio), height: 3)
                    }
                    .frame(maxHeight: .infinity, alignment: .center)
                    .contentShape(Rectangle())
                    .gesture(seekable ?
                        DragGesture(minimumDistance: 0).onChanged { value in
                            let r = min(max(value.location.x / max(geo.size.width, 1), 0), 1)
                            onSeek(Int((r * CGFloat(max(total - 1, 1))).rounded()))
                        } : nil
                    )
                }
                .frame(height: 20)

                Text(chapterLabel)
                    .font(.system(size: 9.5))
                    .foregroundColor(OnlineSkin.value)
                    .monospacedDigit()
                    .lineLimit(1)
            }
            .padding(.horizontal, 20)
            .padding(.top, 10)
            .padding(.bottom, 4)

            HStack {
                key("chevron.left", "上一话", enabled: hasPrev, action: onPrev)
                Spacer(minLength: 0)
                key("list.bullet", "目录", enabled: chaptersAvailable, action: onContents)
                Spacer(minLength: 0)
                key("gearshape", "设置", enabled: true, action: onSettings)
                Spacer(minLength: 0)
                key("chevron.right", "下一话", enabled: hasNext, action: onNext)
            }
            .padding(.horizontal, 10)
            .padding(.top, 2)
            .padding(.bottom, 4)
        }
        .background(
            LinearGradient(
                colors: [OnlineSkin.canvas.opacity(0), OnlineSkin.canvas.opacity(0.78), OnlineSkin.canvas.opacity(0.97)],
                startPoint: .top,
                endPoint: .bottom
            )
            .ignoresSafeArea(edges: .bottom)
        )
    }

    private func key(_ icon: String, _ label: String, enabled: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            VStack(spacing: 4) {
                Image(systemName: icon)
                    .font(.system(size: 17, weight: .medium))
                Text(label)
                    .font(.system(size: 10))
            }
            .foregroundColor(enabled ? OnlineSkin.fg : OnlineSkin.disabled)
            .frame(width: 66, height: 44)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .disabled(!enabled)
    }
}

/// 量设置面板内容的高度：内容少的组不留大片空白，内容多的组再滚动。
private struct PanelHeightKey: PreferenceKey {
    static var defaultValue: CGFloat = 0
    static func reduce(value: inout CGFloat, nextValue: () -> CGFloat) {
        value = max(value, nextValue())
    }
}

// MARK: - 设置（三组 tab：阅读 / 图像 / 显示）

/// 计划书映射：章节挪到工具栏目录；阅读器/方向/页间距 → 阅读组；
/// 裁白边/上色/画面 → 图像组；亮度/蓝光 → 显示组。
/// 每组只有两三项，当前值用 #B6BBC8，点行进子页调原保存逻辑。
struct OnlineSettingsPanel: View {
    @Binding var prefs: ReaderPrefs
    let onClose: () -> Void

    @State private var tab = 0
    @State private var page = ""
    @State private var contentH: CGFloat = 0
    @ObservedObject private var colorizeStore = ColorizeModelStore.shared

    private let tabs = ["阅读", "图像", "显示"]

    private var layoutLabel: String {
        ReaderLayout.allCases.first { $0 == prefs.onlineLayout }?.label ?? "Webtoon"
    }

    private var cropLabel: String {
        guard prefs.autoCropOnline else { return "关" }
        return "开 · " + ["保守", "中", "激进"][min(max(prefs.cropLevel, 0), 2)]
    }

    private var colorizeLabel: String {
        prefs.colorizeOnline ? "开" : "关"
    }

    private var filterLabel: String {
        var on: [String] = []
        if prefs.grayscale { on.append("灰度") }
        if prefs.invert { on.append("反色") }
        return on.isEmpty ? "正常" : on.joined(separator: " · ")
    }

    private var title: String {
        switch page {
        case "layout": return "阅读器"
        case "dir": return "阅读方向"
        case "gap": return "页间距"
        case "crop": return "自动裁白边"
        case "colorize": return "黑白上色"
        case "bright": return "亮度"
        case "blue": return "蓝光过滤"
        case "filter": return "画面"
        default: return ""
        }
    }

    var body: some View {
        VStack(spacing: 0) {
            Capsule()
                .fill(Color.white.opacity(0.16))
                .frame(width: 34, height: 3.5)
                .padding(.top, 8)
                .padding(.bottom, 4)

            if page.isEmpty {
                head
                tabsRow
                ScrollView(showsIndicators: false) {
                    VStack(spacing: 0) {
                        switch tab {
                        case 0:
                            row("阅读器", layoutLabel) { page = "layout" }
                            row("阅读方向", prefs.rightToLeft ? "右到左" : "左到右") { page = "dir" }
                            row("页间距", prefs.pageGapPercent == 0 ? "无缝" : "\(prefs.pageGapPercent)%", last: true) {
                                page = "gap"
                            }
                        case 1:
                            row("自动裁白边", cropLabel) { page = "crop" }
                            row("黑白上色", colorizeLabel) { page = "colorize" }
                            row("画面", filterLabel, last: true) { page = "filter" }
                        default:
                            row("亮度", prefs.autoBrightness ? "跟随系统" : "\(Int(prefs.brightness))%") {
                                page = "bright"
                            }
                            row("蓝光过滤", prefs.blueLight == 0 ? "关" : "\(prefs.blueLight)", last: true) {
                                page = "blue"
                            }
                        }
                    }
                    .padding(.horizontal, 18)
                    .padding(.bottom, 20)
                    .background(measure)
                }
                .frame(height: sheetHeight)
            } else {
                subHead
                ScrollView(showsIndicators: false) {
                    subBody
                        .padding(.horizontal, 18)
                        .padding(.bottom, 26)
                        .background(measure)
                }
                .frame(height: sheetHeight)
            }
        }
        .frame(maxWidth: .infinity)
        .background(OnlineSkin.sheet)
        .clipShape(RoundedRectangle(cornerRadius: 22, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: 22, style: .continuous)
                .stroke(Color.white.opacity(0.06), lineWidth: 0.5)
        )
        .onPreferenceChange(PanelHeightKey.self) { h in
            if abs(h - contentH) > 0.5 { contentH = h }
        }
    }

    private var measure: some View {
        GeometryReader { g in
            Color.clear.preference(key: PanelHeightKey.self, value: g.size.height)
        }
    }

    /// 计划书：设置默认约占屏 38%–45%
    private var sheetHeight: CGFloat {
        let maxH = UIScreen.main.bounds.height * 0.45 - 68
        return min(max(contentH, 120), maxH)
    }

    // MARK: 头部（标题 + 完成）

    private var head: some View {
        HStack(spacing: 0) {
            Color.clear.frame(width: 44, height: 44)
            Text("阅读设置")
                .font(.system(size: 15, weight: .semibold))
                .foregroundColor(OnlineSkin.fg)
                .frame(maxWidth: .infinity)
            Button(action: onClose) {
                Text("完成")
                    .font(.system(size: 13.5, weight: .medium))
                    .foregroundColor(OnlineSkin.accent)
                    .frame(width: 44, height: 44)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
        }
        .frame(height: 48)
        .padding(.horizontal, 4)
    }

    private var subHead: some View {
        HStack(spacing: 0) {
            Button {
                withAnimation(.easeOut(duration: 0.15)) { page = "" }
            } label: {
                Image(systemName: "chevron.left")
                    .font(.system(size: 16, weight: .semibold))
                    .foregroundColor(.white)
                    .frame(width: 44, height: 44)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)

            Text(title)
                .font(.system(size: 15, weight: .semibold))
                .foregroundColor(OnlineSkin.fg)
                .frame(maxWidth: .infinity)

            Color.clear.frame(width: 44, height: 44)
        }
        .frame(height: 48)
        .padding(.horizontal, 4)
    }

    /// 三组 tab：当前组白字浅底，其余当前值灰
    private var tabsRow: some View {
        HStack(spacing: 6) {
            ForEach(tabs.indices, id: \.self) { i in
                Button {
                    withAnimation(.easeOut(duration: 0.15)) { tab = i }
                } label: {
                    Text(tabs[i])
                        .font(.system(size: 13, weight: tab == i ? .semibold : .regular))
                        .foregroundColor(tab == i ? OnlineSkin.fg : OnlineSkin.value)
                        .frame(maxWidth: .infinity)
                        .frame(height: 34)
                        .background(
                            RoundedRectangle(cornerRadius: 10, style: .continuous)
                                .fill(tab == i ? Color.white.opacity(0.07) : Color.white.opacity(0.02))
                        )
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
            }
        }
        .padding(.horizontal, 14)
        .padding(.top, 2)
        .padding(.bottom, 6)
    }

    // MARK: 组内一行入口

    private func row(
        _ name: String,
        _ value: String,
        last: Bool = false,
        action: @escaping () -> Void
    ) -> some View {
        Button(action: action) {
            HStack(spacing: 10) {
                Text(name)
                    .font(.system(size: 14))
                    .foregroundColor(OnlineSkin.fg)
                Spacer(minLength: 8)
                Text(value)
                    .font(.system(size: 12.5))
                    .foregroundColor(OnlineSkin.value)
                    .lineLimit(1)
                Image(systemName: "chevron.right")
                    .font(.system(size: 9.5, weight: .semibold))
                    .foregroundColor(Color.white.opacity(0.22))
                    .padding(.leading, 2)
            }
            .padding(.vertical, 14)
            .contentShape(Rectangle())
            .overlay(alignment: .bottom) {
                if !last {
                    Rectangle()
                        .fill(OnlineSkin.hair)
                        .frame(height: 0.5)
                }
            }
        }
        .buttonStyle(.plain)
    }

    // MARK: 子页：只有这一项

    @ViewBuilder
    private var subBody: some View {
        switch page {
        case "layout":
            VStack(spacing: 0) {
                ForEach(ReaderLayout.allCases) { layout in
                    option(layout.label, layout.desc, prefs.onlineLayout == layout,
                           last: layout == ReaderLayout.slide) {
                        prefs.onlineLayout = layout
                    }
                }
            }

        case "dir":
            VStack(spacing: 0) {
                option("左到右", "第一页在左边", !prefs.rightToLeft, last: false) {
                    prefs.rightToLeft = false
                }
                option("右到左", "第一页在右边（日漫习惯）", prefs.rightToLeft, last: true) {
                    prefs.rightToLeft = true
                }
            }
            note(prefs.onlineLayout == .webtoon || prefs.onlineLayout == .free
                 ? "当前是竖条模式（上下滚），方向只在相册 / 双页 / 滑动里生效。"
                 : "左右翻的时候从哪一边开始。")

        case "gap":
            sliderRow("空隙",
                      value: Binding(
                        get: { Double(prefs.pageGapPercent) },
                        set: { prefs.pageGapPercent = min(max(Int($0.rounded()), 0), 20) }
                      ),
                      range: 0...20,
                      text: prefs.pageGapPercent == 0 ? "无缝" : "\(prefs.pageGapPercent)%")
            ends("无缝", "20%")
            note("页与页之间的空隙。看条漫一般保持无缝，翻页阅读时留一点更容易分清两页。")

        case "crop":
            toggleRow("开启", prefs.autoCropOnline) { prefs.autoCropOnline = $0 }
            if prefs.autoCropOnline {
                sliderRow("识别强度",
                          value: Binding(
                            get: { Double(prefs.cropLevel) },
                            set: { prefs.cropLevel = min(max(Int($0.rounded()), 0), 2) }
                          ),
                          range: 0...2,
                          text: ["保守", "中", "激进"][min(max(prefs.cropLevel, 0), 2)])
                ends("保守", "激进")
            }
            note("只裁「整行或整列都是白」的边，画面里的白色气泡和白底对白框不会被切。"
                 + "裁掉超过一半会判定误判，宁可不裁。")

        case "colorize":
            toggleRow("开启", prefs.colorizeOnline) { prefs.colorizeOnline = $0 }
            if prefs.colorizeOnline {
                switch colorizeStore.state {
                case .ready:
                    note("模型已就绪。黑白页面会自动上色，每页首次打开要等几秒，"
                         + "看过的页有缓存会秒开。彩色页和本地书不受影响。")
                case .downloading:
                    HStack(spacing: 10) {
                        ProgressView(value: colorizeStore.progress)
                            .progressViewStyle(.linear)
                            .tint(OnlineSkin.accent)
                        Text("\(Int(colorizeStore.progress * 100))%")
                            .font(.system(size: 11))
                            .foregroundColor(.white.opacity(0.6))
                            .monospacedDigit()
                    }
                    .padding(.vertical, 6)
                    note("正在下载上色模型（约 117MB，只下载一次）。下载完成前先显示原图。")
                case .idle:
                    Button {
                        colorizeStore.startDownload()
                    } label: {
                        Text("下载上色模型（约 117MB，仅一次）")
                            .font(.system(size: 13, weight: .medium))
                            .foregroundColor(OnlineSkin.accent)
                    }
                    .buttonStyle(.plain)
                    note("模型来自 manga-colorization-v2（开源 MIT，专为黑白漫画训练）。"
                         + "下载完成前先显示原图。")
                case .failed(let msg):
                    Button {
                        colorizeStore.startDownload()
                    } label: {
                        Text("下载失败，点这里重试")
                            .font(.system(size: 13, weight: .medium))
                            .foregroundColor(Color(hex: 0xE0736B))
                    }
                    .buttonStyle(.plain)
                    note(msg)
                }
            } else {
                note("打开后黑白漫画会自动上色（模型约 117MB，仅下载一次，"
                     + "专为黑白漫画训练）。彩色源和本地书不受影响。")
            }

        case "bright":
            toggleRow("跟随系统", prefs.autoBrightness) { prefs.autoBrightness = $0 }
            sliderRow("屏幕亮度",
                      value: Binding(
                        get: { prefs.brightness },
                        set: { prefs.brightness = min(max($0, 5), 100) }
                      ),
                      range: 5...100,
                      text: prefs.autoBrightness ? "跟随系统" : "\(Int(prefs.brightness))%",
                      enabled: !prefs.autoBrightness)
            note("退出阅读页会恢复原来的亮度，不会锁死系统设置。")

        case "blue":
            sliderRow("强度",
                      value: Binding(
                        get: { Double(prefs.blueLight) },
                        set: { prefs.blueLight = min(max(Int($0.rounded()), 0), 100) }
                      ),
                      range: 0...100,
                      text: prefs.blueLight == 0 ? "关" : "\(prefs.blueLight)")
            note("整页盖一层暖色，夜里看着不刺眼。0 就是关闭。")

        case "filter":
            toggleRow("灰度", prefs.grayscale) { prefs.grayscale = $0 }
            toggleRow("反色", prefs.invert) { prefs.invert = $0 }
            toggleRow("阅读时常亮", prefs.keepScreenOn) { prefs.keepScreenOn = $0 }
            note("灰度与反色直接作用在每一张在线图上，改完立刻能看到。")

        default:
            EmptyView()
        }
    }

    private func option(
        _ name: String,
        _ desc: String,
        _ selected: Bool,
        last: Bool,
        action: @escaping () -> Void
    ) -> some View {
        Button(action: action) {
            HStack(spacing: 12) {
                VStack(alignment: .leading, spacing: 3) {
                    Text(name)
                        .font(.system(size: 14))
                        .foregroundColor(selected ? OnlineSkin.accent : OnlineSkin.fg)
                    if !desc.isEmpty {
                        Text(desc)
                            .font(.system(size: 11))
                            .foregroundColor(OnlineSkin.dim2)
                            .lineSpacing(1)
                    }
                }
                Spacer(minLength: 8)
                Image(systemName: "checkmark")
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundColor(OnlineSkin.accent)
                    .opacity(selected ? 1 : 0)
            }
            .padding(.vertical, 13)
            .contentShape(Rectangle())
            .overlay(alignment: .bottom) {
                if !last {
                    Rectangle().fill(OnlineSkin.hair).frame(height: 0.5)
                }
            }
        }
        .buttonStyle(.plain)
    }

    private func toggleRow(_ label: String, _ on: Bool, action: @escaping (Bool) -> Void) -> some View {
        HStack {
            Text(label)
                .font(.system(size: 14))
                .foregroundColor(OnlineSkin.fg)
            Spacer(minLength: 0)
            Toggle("", isOn: Binding(get: { on }, set: { action($0) }))
                .labelsHidden()
                .tint(OnlineSkin.accent)
                .scaleEffect(0.9)
        }
        .padding(.vertical, 12)
    }

    private func sliderRow(
        _ label: String,
        value: Binding<Double>,
        range: ClosedRange<Double>,
        text: String,
        enabled: Bool = true
    ) -> some View {
        VStack(spacing: 0) {
            HStack {
                Text(label)
                    .font(.system(size: 14))
                    .foregroundColor(OnlineSkin.fg)
                Spacer(minLength: 0)
                Text(text)
                    .font(.system(size: 12.5))
                    .foregroundColor(enabled ? OnlineSkin.value : OnlineSkin.dim2)
                    .monospacedDigit()
            }
            Slider(value: value, in: range)
                .tint(OnlineSkin.accent)
                .disabled(!enabled)
                .padding(.top, 4)
        }
        .padding(.top, 10)
    }

    private func ends(_ left: String, _ right: String) -> some View {
        HStack {
            Text(left)
            Spacer()
            Text(right)
        }
        .font(.system(size: 10))
        .foregroundColor(Color.white.opacity(0.32))
        .padding(.top, 2)
    }

    private func note(_ text: String) -> some View {
        Text(text)
            .font(.system(size: 11.5))
            .foregroundColor(Color.white.opacity(0.38))
            .lineSpacing(3)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.top, 14)
    }
}

// MARK: - 常驻进度条（贴底细线，可拖拽跳页）

/// 常驻在屏幕底部的一条细进度线：随当前页实时更新，点住可拖拽预览、
/// 松手跳到目标页。触控区只有细线附近 ±11pt，不挡漫画底部的翻页点击；
/// 拖拽时放大成 4pt + 拇指圆点 + 页码气泡。淡紫 = v7 强调色。
struct OnlineProgressRail: View {
    let current: Int
    let total: Int
    let onSeek: (Int) -> Void

    @State private var dragging = false
    @State private var dragValue = 0

    private var shown: Int {
        min(max(dragging ? dragValue : current, 0), max(total - 1, 0))
    }

    private var ratio: CGFloat {
        guard total > 1 else { return 1 }
        return CGFloat(shown + 1) / CGFloat(total)
    }

    var body: some View {
        GeometryReader { geo in
            let w = max(geo.size.width, 1)
            let thumbX = w * ratio
            VStack(spacing: 0) {
                if dragging {
                    Text("\(shown + 1) / \(total)")
                        .font(.system(size: 10.5, weight: .semibold))
                        .foregroundColor(OnlineSkin.fg)
                        .monospacedDigit()
                        .padding(.horizontal, 10)
                        .padding(.vertical, 4)
                        .background(Capsule().fill(OnlineSkin.sheet.opacity(0.97)))
                        .overlay(Capsule().stroke(Color.white.opacity(0.08), lineWidth: 0.5))
                        .offset(
                            x: min(max(thumbX, 44), w - 44),
                            y: dragging ? -2 : 0
                        )
                }
                Spacer(minLength: 0)
                ZStack(alignment: .leading) {
                    Capsule()
                        .fill(Color.white.opacity(0.15))
                        .frame(height: dragging ? 4 : 2.5)
                    Capsule()
                        .fill(OnlineSkin.accent)
                        .frame(width: max(3, w * ratio), height: dragging ? 4 : 2.5)
                    if dragging {
                        Circle()
                            .fill(OnlineSkin.accent)
                            .frame(width: 14, height: 14)
                            .shadow(color: .black.opacity(0.4), radius: 4, x: 0, y: 1)
                            .offset(x: min(max(thumbX - 7, 0), w - 14))
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                // 触控区只在线附近 ±11pt：不挡漫画底部的翻页点击
                .contentShape(Rectangle().inset(by: -11))
                .gesture(
                    DragGesture(minimumDistance: 0)
                        .onChanged { value in
                            if !dragging { dragging = true }
                            let r = min(max(value.location.x / w, 0), 1)
                            dragValue = Int((r * CGFloat(max(total - 1, 1))).rounded())
                        }
                        .onEnded { value in
                            let r = min(max(value.location.x / w, 0), 1)
                            let target = Int((r * CGFloat(max(total - 1, 1))).rounded())
                            dragging = false
                            onSeek(target)
                        }
                )
            }
        }
        .frame(height: 24)
        .animation(.easeOut(duration: 0.14), value: dragging)
    }
}

// MARK: - 目录（工具栏「目录」键呼出）

/// 独立章节面板：打开时定位当前话（滚动到屏幕中央），点行切章并关闭。
struct OnlineChapterPanel: View {
    let chapters: [(String, String)]
    let chapterIndex: Int
    let onPick: (Int) -> Void
    let onClose: () -> Void

    var body: some View {
        VStack(spacing: 0) {
            Capsule()
                .fill(Color.white.opacity(0.16))
                .frame(width: 34, height: 3.5)
                .padding(.top, 8)
                .padding(.bottom, 2)

            HStack(spacing: 0) {
                Color.clear.frame(width: 44, height: 44)
                Text("目录")
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundColor(OnlineSkin.fg)
                    .frame(maxWidth: .infinity)
                Button(action: onClose) {
                    Text("完成")
                        .font(.system(size: 13.5, weight: .medium))
                        .foregroundColor(OnlineSkin.accent)
                        .frame(width: 44, height: 44)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
            }
            .frame(height: 48)
            .padding(.horizontal, 4)

            ScrollViewReader { proxy in
                ScrollView(showsIndicators: false) {
                    LazyVStack(spacing: 0, pinnedViews: []) {
                        ForEach(Array(chapters.indices), id: \.self) { i in
                            chapterRow(i).id(i)
                        }
                    }
                    .padding(.horizontal, 18)
                    .padding(.bottom, 24)
                }
                .onAppear {
                    proxy.scrollTo(chapterIndex, anchor: .center)
                }
            }
        }
        .frame(height: UIScreen.main.bounds.height * 0.58)
        .frame(maxWidth: .infinity)
        .background(OnlineSkin.sheet)
        .clipShape(RoundedRectangle(cornerRadius: 22, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: 22, style: .continuous)
                .stroke(Color.white.opacity(0.06), lineWidth: 0.5)
        )
    }

    private func chapterRow(_ i: Int) -> some View {
        Button {
            onPick(i)
        } label: {
            HStack(spacing: 10) {
                Text(chapters[i].1.isEmpty ? "第 \(i + 1) 话" : chapters[i].1)
                    .font(.system(size: 13.5, weight: i == chapterIndex ? .semibold : .regular))
                    .foregroundColor(i == chapterIndex ? OnlineSkin.accent : OnlineSkin.fg)
                    .lineLimit(1)
                Spacer(minLength: 8)
                if i == chapterIndex {
                    Text("当前")
                        .font(.system(size: 10.5))
                        .foregroundColor(OnlineSkin.accent)
                }
            }
            .padding(.vertical, 13)
            .contentShape(Rectangle())
            .overlay(alignment: .bottom) {
                if i != chapters.count - 1 {
                    Rectangle().fill(OnlineSkin.hair).frame(height: 0.5)
                }
            }
        }
        .buttonStyle(.plain)
    }
}
