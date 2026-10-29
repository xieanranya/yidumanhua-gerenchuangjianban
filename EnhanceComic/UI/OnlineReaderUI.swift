import SwiftUI

/// 在线阅读器自己的视觉。
///
/// 原则：**装饰越少越好**。
/// 顶栏只在需要时出现（很淡的渐变），设置是一张纯列表 —— 名字、当前值、一根细线，
/// 没有卡片、没有图标方块、没有整行染色。粉色只留给「当前选中」和「完成」。
enum OnlineSkin {
    static let accent = Color(red: 1.0, green: 0.431, blue: 0.612)      // #FF6E9C
    static let sheet = Color(red: 0.098, green: 0.102, blue: 0.118)     // #191A1E
    static let hair = Color.white.opacity(0.065)
    static let fg = Color(red: 0.925, green: 0.925, blue: 0.933)        // #ECECEE
    static let dim = Color.white.opacity(0.48)
    static let dim2 = Color.white.opacity(0.30)
    static let scrim = Color.black.opacity(0.46)
}

// MARK: - 顶栏

/// 一行只有三样：返回 / 标题+页码 / 设置。目录收进了设置第一行。
/// 背景不是实心条，而是一条很淡的顶部渐变 —— 画面不会被切掉一块。
struct OnlineTopBar: View {
    let title: String
    let pageLabel: String
    let onBack: () -> Void
    let onSettings: () -> Void

    var body: some View {
        HStack(spacing: 0) {
            Button(action: onBack) {
                Image(systemName: "chevron.left")
                    .font(.system(size: 17, weight: .semibold))
                    .foregroundColor(.white)
                    .frame(width: 44, height: 44)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)

            VStack(alignment: .leading, spacing: 2) {
                Text(title.isEmpty ? "阅读" : title)
                    .font(.system(size: 13, weight: .medium))
                    .foregroundColor(.white)
                    .lineLimit(1)
                Text(pageLabel)
                    .font(.system(size: 10.5))
                    .foregroundColor(Color.white.opacity(0.5))
                    .lineLimit(1)
                    .monospacedDigit()
            }

            Spacer(minLength: 0)

            Button(action: onSettings) {
                Image(systemName: "slider.horizontal.3")
                    .font(.system(size: 16, weight: .medium))
                    .foregroundColor(.white)
                    .frame(width: 44, height: 44)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
        }
        .padding(.horizontal, 10)
        .frame(height: 56)
        .background(
            LinearGradient(
                colors: [Color.black.opacity(0.58), Color.black.opacity(0.20), .clear],
                startPoint: .top,
                endPoint: .bottom
            )
            .ignoresSafeArea(edges: .top)
        )
    }
}

// MARK: - 底部翻页滑条

/// 只有一条细线和两端页码，没有 thumb —— 拖动时手指就是 thumb。
struct OnlineSeekBar: View {
    let current: Int
    let total: Int
    let onSeek: (Int) -> Void

    var body: some View {
        HStack(spacing: 12) {
            Text("\(current + 1)")
                .font(.system(size: 10))
                .foregroundColor(Color.white.opacity(0.55))
                .monospacedDigit()

            GeometryReader { geo in
                ZStack(alignment: .leading) {
                    Capsule()
                        .fill(Color.white.opacity(0.18))
                        .frame(height: 2)
                    Capsule()
                        .fill(OnlineSkin.accent)
                        .frame(
                            width: max(2, geo.size.width * CGFloat(current + 1) / CGFloat(max(total, 1))),
                            height: 2
                        )
                }
                .frame(maxHeight: .infinity, alignment: .center)
                .contentShape(Rectangle())
                .gesture(
                    DragGesture(minimumDistance: 0)
                        .onChanged { value in
                            let ratio = min(max(value.location.x / max(geo.size.width, 1), 0), 1)
                            onSeek(Int((ratio * CGFloat(max(total - 1, 1))).rounded()))
                        }
                )
            }
            .frame(height: 22)

            Text("\(total)")
                .font(.system(size: 10))
                .foregroundColor(Color.white.opacity(0.55))
                .monospacedDigit()
        }
        .padding(.horizontal, 16)
        .padding(.top, 6)
        .padding(.bottom, 8)
        .background(
            LinearGradient(
                colors: [.clear, Color.black.opacity(0.55)],
                startPoint: .top,
                endPoint: .bottom
            )
            .ignoresSafeArea(edges: .bottom)
        )
    }
}

/// 量设置面板内容的高度：内容少的页（比如「蓝光过滤」）不该留一大片空白，
/// 内容多的页（目录）也不能被截断。
private struct PanelHeightKey: PreferenceKey {
    static var defaultValue: CGFloat = 0
    static func reduce(value: inout CGFloat, nextValue: () -> CGFloat) {
        value = max(value, nextValue())
    }
}

// MARK: - 设置（两层：目录 → 单项）

/// 第一层是纯列表：名字 / 当前值 / 细箭头。点进去只显示这一项。
///
/// 和上一版的区别（用户明确要求「简洁一点、美观一点」）：
///  - 去掉每行的图标方块与卡片底色，只留一根发丝线；
///  - 去掉分组小标题，两组之间用留白 + 一条线分开；
///  - 子页不再有「完成」，只有返回和居中标题；
///  - 选中不整行染色，只把文字变粉、右侧打勾。
struct OnlineSettingsPanel: View {
    @Binding var prefs: ReaderPrefs
    let chapters: [(String, String)]
    let chapterIndex: Int
    let onPickChapter: (Int) -> Void
    let onClose: () -> Void

    @State private var page = ""
    @State private var contentH: CGFloat = 0

    private var layoutLabel: String {
        ReaderLayout.allCases.first { $0 == prefs.onlineLayout }?.label ?? "Webtoon"
    }

    private var cropLabel: String {
        guard prefs.autoCropOnline else { return "关" }
        return "开 · " + ["保守", "中", "激进"][min(max(prefs.cropLevel, 0), 2)]
    }

    private var filterLabel: String {
        var on: [String] = []
        if prefs.grayscale { on.append("灰度") }
        if prefs.invert { on.append("反色") }
        return on.isEmpty ? "正常" : on.joined(separator: " · ")
    }

    private var chapterLabel: String {
        chapters.count > 1
            ? "第 \(chapterIndex + 1) 话 · 共 \(chapters.count) 话"
            : "共 \(max(chapters.count, 1)) 话"
    }

    private var title: String {
        switch page {
        case "chapter": return "目录"
        case "layout": return "阅读器"
        case "dir": return "阅读方向"
        case "gap": return "页间距"
        case "crop": return "自动裁白边"
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
                head(back: nil, done: true)
                ScrollView(showsIndicators: false) {
                    VStack(spacing: 0) {
                        row("章节", chapterLabel) { page = "chapter" }
                        row("阅读器", layoutLabel) { page = "layout" }
                        row("阅读方向", prefs.rightToLeft ? "右到左" : "左到右") { page = "dir" }

                        groupGap

                        row("页间距", prefs.pageGapPercent == 0 ? "无缝" : "\(prefs.pageGapPercent)%") {
                            page = "gap"
                        }
                        row("自动裁白边", cropLabel) { page = "crop" }
                        row("亮度",
                            prefs.autoBrightness ? "跟随系统" : "\(Int(prefs.brightness))%") {
                            page = "bright"
                        }
                        row("蓝光过滤", prefs.blueLight == 0 ? "关" : "\(prefs.blueLight)") {
                            page = "blue"
                        }
                        row("画面", filterLabel, last: true) { page = "filter" }
                    }
                    .padding(.horizontal, 18)
                    .padding(.bottom, 22)
                    .background(measure)
                }
                .frame(height: sheetHeight)
            } else {
                head(back: { page = "" }, done: false)
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

    /// 内容多高就多高，最多到屏幕 72%（再多就滚动）
    private var sheetHeight: CGFloat {
        let maxH = UIScreen.main.bounds.height * 0.72 - 68
        return min(max(contentH, 120), maxH)
    }

    // MARK: 头部

    private func head(back: (() -> Void)?, done: Bool) -> some View {
        HStack(spacing: 0) {
            if let back {
                Button(action: back) {
                    Image(systemName: "chevron.left")
                        .font(.system(size: 16, weight: .semibold))
                        .foregroundColor(.white)
                        .frame(width: 44, height: 44)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
            } else {
                Color.clear.frame(width: 44, height: 44)
            }

            Text(page.isEmpty ? "阅读设置" : title)
                .font(.system(size: 14, weight: .semibold))
                .foregroundColor(OnlineSkin.fg)
                .frame(maxWidth: .infinity)

            if done {
                Button(action: onClose) {
                    Text("完成")
                        .font(.system(size: 13.5, weight: .medium))
                        .foregroundColor(OnlineSkin.accent)
                        .frame(width: 44, height: 44)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
            } else {
                Color.clear.frame(width: 44, height: 44)
            }
        }
        .frame(height: 48)
        .padding(.horizontal, 4)
    }

    // MARK: 第一层：一行入口

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
                    .foregroundColor(OnlineSkin.dim)
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

    private var groupGap: some View {
        VStack(spacing: 0) {
            Rectangle().fill(OnlineSkin.hair).frame(height: 0.5)
            Color.clear.frame(height: 16)
        }
    }

    // MARK: 第二层：只有这一项

    @ViewBuilder
    private var subBody: some View {
        switch page {
        case "chapter":
            VStack(spacing: 0) {
                ForEach(Array(chapters.indices), id: \.self) { i in
                    Button {
                        if i != chapterIndex { onPickChapter(i) }
                        onClose()
                    } label: {
                        HStack(spacing: 10) {
                            Text(chapters[i].1.isEmpty ? "第 \(i + 1) 话" : chapters[i].1)
                                .font(.system(size: 13.5))
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
                    .foregroundColor(enabled ? OnlineSkin.dim : OnlineSkin.dim2)
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
