import SwiftUI

/// 在线阅读器自己的视觉。
///
/// 本地的 `ReaderSkin` 是「两行常驻工具栏 + 一排小图标」，那套是给本地画布配的。
/// 在线是网页/条漫，顶上加两行会一直压着内容，密度也在手机上显得挤 —— 所以这里单独一套。
enum OnlineSkin {
    static let accent = Color(red: 1.0, green: 0.431, blue: 0.612)      // #FF6E9C
    static let onAccent = Color(red: 0.086, green: 0.031, blue: 0.047)  // #16080C
    static let bar = Color.black.opacity(0.82)
    static let sheet = Color(red: 0.102, green: 0.102, blue: 0.114)     // #1A1A1D
    static let card = Color(red: 0.141, green: 0.141, blue: 0.157)      // #242428
    static let hair = Color.white.opacity(0.12)
    static let dim = Color.white.opacity(0.60)
    static let hint = Color.white.opacity(0.40)
    static let scrim = Color.black.opacity(0.42)
}

// MARK: - 顶栏

/// 一行：返回 / 标题 + 页码 / 目录 / 设置。
struct OnlineTopBar: View {
    let title: String
    let pageLabel: String
    let hasChapters: Bool
    let onBack: () -> Void
    let onChapters: () -> Void
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

            VStack(alignment: .leading, spacing: 1) {
                Text(title.isEmpty ? "阅读" : title)
                    .font(.system(size: 13.5, weight: .medium))
                    .foregroundColor(.white)
                    .lineLimit(1)
                Text(pageLabel)
                    .font(.system(size: 10.5))
                    .foregroundColor(OnlineSkin.dim)
                    .lineLimit(1)
            }

            Spacer(minLength: 0)

            if hasChapters {
                Button(action: onChapters) {
                    Image(systemName: "list.bullet")
                        .font(.system(size: 15, weight: .medium))
                        .foregroundColor(.white)
                        .frame(width: 44, height: 44)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
            }

            Button(action: onSettings) {
                Image(systemName: "slider.horizontal.3")
                    .font(.system(size: 16, weight: .medium))
                    .foregroundColor(.white)
                    .frame(width: 44, height: 44)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
        }
        .padding(.horizontal, 4)
        .frame(height: 52)
        .background(OnlineSkin.bar)
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
                .font(.system(size: 11))
                .foregroundColor(.white)
                .monospacedDigit()

            GeometryReader { geo in
                ZStack(alignment: .leading) {
                    Capsule()
                        .fill(Color.white.opacity(0.20))
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
            .frame(height: 24)

            Text("/ \(total)")
                .font(.system(size: 11))
                .foregroundColor(OnlineSkin.dim)
                .monospacedDigit()
        }
        .padding(.horizontal, 16)
        .padding(.bottom, 6)
        .padding(.top, 8)
        .background(OnlineSkin.bar)
    }
}

// MARK: - 设置（两层：目录 → 单项）

struct OnlineSettingsPanel: View {
    @Binding var prefs: ReaderPrefs
    let onClose: () -> Void

    @State private var page = ""

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

    private var title: String {
        switch page {
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
                .fill(Color.white.opacity(0.20))
                .frame(width: 36, height: 4)
                .padding(.top, 10)
                .padding(.bottom, 6)

            if page.isEmpty {
                header("阅读设置", back: nil)
                ScrollView(showsIndicators: false) {
                    VStack(spacing: 9) {
                        entry("rectangle.split.1x2", "阅读器", layoutLabel) { page = "layout" }
                        entry("arrow.left.arrow.right", "阅读方向",
                              prefs.rightToLeft ? "右到左" : "左到右") { page = "dir" }
                        entry("arrow.up.and.down", "页间距",
                              prefs.pageGapPercent == 0 ? "无缝" : "\(prefs.pageGapPercent)%") {
                            page = "gap"
                        }
                        entry("crop", "自动裁白边", cropLabel) { page = "crop" }
                        entry("sun.max", "亮度",
                              prefs.autoBrightness ? "跟随系统" : "\(Int(prefs.brightness))%") {
                            page = "bright"
                        }
                        entry("moon", "蓝光过滤", prefs.blueLight == 0 ? "关" : "\(prefs.blueLight)") {
                            page = "blue"
                        }
                        entry("circle.lefthalf.filled", "画面", filterLabel) { page = "filter" }
                    }
                    .padding(.horizontal, 14)
                    .padding(.top, 2)
                    .padding(.bottom, 24)
                }
                .frame(maxHeight: 452)
            } else {
                header(title, back: { page = "" })
                ScrollView(showsIndicators: false) {
                    subBody
                        .padding(.horizontal, 14)
                        .padding(.bottom, 24)
                }
                .frame(maxHeight: 452)
            }
        }
        .frame(maxWidth: .infinity)
        .background(OnlineSkin.sheet)
        .clipShape(RoundedRectangle(cornerRadius: 20, style: .continuous))
    }

    // MARK: 子界面

    @ViewBuilder
    private var subBody: some View {
        switch page {
        case "layout":
            VStack(spacing: 2) {
                ForEach(ReaderLayout.allCases) { layout in
                    option(layout.label, layout.desc, prefs.onlineLayout == layout) {
                        prefs.onlineLayout = layout
                    }
                }
            }

        case "dir":
            VStack(spacing: 2) {
                option("左到右", "第一页在左边", !prefs.rightToLeft) { prefs.rightToLeft = false }
                option("右到左", "第一页在右边（日漫习惯）", prefs.rightToLeft) { prefs.rightToLeft = true }
            }
            note(prefs.onlineLayout == .webtoon || prefs.onlineLayout == .free
                 ? "当前是竖条模式（上下滚），方向只在相册 / 双页 / 滑动里生效。"
                 : "左右翻的时候从哪一边开始。")
                .padding(.top, 10)

        case "gap":
            note("上下两页之间留多少空隙。0 就是无缝拼接（看条漫最常用），往右拉会看到明显的间隔。")
            sliderRow("空隙",
                      value: Binding(
                        get: { Double(prefs.pageGapPercent) },
                        set: { prefs.pageGapPercent = min(max(Int($0.rounded()), 0), 20) }
                      ),
                      range: 0...20,
                      text: prefs.pageGapPercent == 0 ? "无缝" : "\(prefs.pageGapPercent)%")
            HStack { note2("无缝"); Spacer(); note2("20%") }

        case "crop":
            note("自动认出每页四周的白边并裁掉，让画面按手机屏幕宽度铺满 —— 不会再有左右两条白杠。")
            toggleRow("开启", prefs.autoCropOnline) { prefs.autoCropOnline = $0 }
            if prefs.autoCropOnline {
                sliderRow("识别强度",
                          value: Binding(
                            get: { Double(prefs.cropLevel) },
                            set: { prefs.cropLevel = min(max(Int($0.rounded()), 0), 2) }
                          ),
                          range: 0...2,
                          text: ["保守", "中", "激进"][min(max(prefs.cropLevel, 0), 2)])
                HStack { note2("保守"); Spacer(); note2("激进") }
            }
            note("只裁「整行 / 整列都是白」的那种边，画面里的白色气泡不会被切。")
                .padding(.top, 12)

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
            note("退出阅读页会恢复成原来的亮度，不会锁死系统设置。")
                .padding(.top, 10)

        case "blue":
            note("整页盖一层暖色，夜里看着不刺眼。")
            sliderRow("强度",
                      value: Binding(
                        get: { Double(prefs.blueLight) },
                        set: { prefs.blueLight = min(max(Int($0.rounded()), 0), 100) }
                      ),
                      range: 0...100,
                      text: prefs.blueLight == 0 ? "关" : "\(prefs.blueLight)")

        case "filter":
            FlowLayout(spacing: 8) {
                OnlineChip(label: "灰度", on: prefs.grayscale) { prefs.grayscale.toggle() }
                OnlineChip(label: "反色", on: prefs.invert) { prefs.invert.toggle() }
                OnlineChip(label: "常亮", on: prefs.keepScreenOn) { prefs.keepScreenOn.toggle() }
            }
            note("灰度 / 反色直接作用在在线图片上，改完立刻能看出来。")
                .padding(.top, 12)

        default:
            EmptyView()
        }
    }

    // MARK: 零件

    private func header(_ text: String, back: (() -> Void)?) -> some View {
        HStack(spacing: 0) {
            if let back {
                Button(action: back) {
                    Image(systemName: "chevron.left")
                        .font(.system(size: 16, weight: .semibold))
                        .foregroundColor(.white)
                        .frame(width: 48, height: 44)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
            } else {
                Color.clear.frame(width: 48, height: 44)
            }

            Text(text)
                .font(.system(size: 13.5, weight: .semibold))
                .foregroundColor(.white)
                .frame(maxWidth: .infinity)

            Button(action: onClose) {
                Text("完成")
                    .font(.system(size: 12.5, weight: .medium))
                    .foregroundColor(OnlineSkin.accent)
                    .frame(width: 48, height: 44)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
        }
        .frame(height: 44)
    }

    private func entry(
        _ icon: String,
        _ name: String,
        _ value: String,
        action: @escaping () -> Void
    ) -> some View {
        Button(action: action) {
            HStack(spacing: 12) {
                RoundedRectangle(cornerRadius: 10, style: .continuous)
                    .fill(Color.white.opacity(0.08))
                    .frame(width: 34, height: 34)
                    .overlay(
                        Image(systemName: icon)
                            .font(.system(size: 15))
                            .foregroundColor(.white)
                    )
                VStack(alignment: .leading, spacing: 2) {
                    Text(name)
                        .font(.system(size: 13, weight: .medium))
                        .foregroundColor(.white)
                    Text(value)
                        .font(.system(size: 11))
                        .foregroundColor(OnlineSkin.dim)
                        .lineLimit(1)
                }
                Spacer(minLength: 0)
                Image(systemName: "chevron.right")
                    .font(.system(size: 12, weight: .semibold))
                    .foregroundColor(Color.white.opacity(0.26))
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 13)
            .background(
                RoundedRectangle(cornerRadius: 13, style: .continuous).fill(OnlineSkin.card)
            )
        }
        .buttonStyle(.plain)
    }

    private func option(
        _ name: String,
        _ desc: String,
        _ selected: Bool,
        action: @escaping () -> Void
    ) -> some View {
        Button(action: action) {
            HStack(spacing: 12) {
                VStack(alignment: .leading, spacing: 3) {
                    Text(name)
                        .font(.system(size: 13.5, weight: selected ? .medium : .regular))
                        .foregroundColor(selected ? OnlineSkin.accent : .white)
                    if !desc.isEmpty {
                        Text(desc)
                            .font(.system(size: 11))
                            .foregroundColor(OnlineSkin.dim)
                    }
                }
                Spacer(minLength: 0)
                if selected {
                    Image(systemName: "checkmark")
                        .font(.system(size: 14, weight: .semibold))
                        .foregroundColor(OnlineSkin.accent)
                }
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 13)
            .background(
                RoundedRectangle(cornerRadius: 12, style: .continuous)
                    .fill(selected ? OnlineSkin.accent.opacity(0.10) : Color.clear)
            )
        }
        .buttonStyle(.plain)
    }

    private func toggleRow(_ label: String, _ on: Bool, action: @escaping (Bool) -> Void) -> some View {
        HStack {
            Text(label).font(.system(size: 13)).foregroundColor(.white)
            Spacer(minLength: 0)
            Toggle("", isOn: Binding(get: { on }, set: { action($0) }))
                .labelsHidden()
                .tint(OnlineSkin.accent)
        }
        .padding(.vertical, 10)
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
                Text(label).font(.system(size: 12)).foregroundColor(.white)
                Spacer(minLength: 0)
                Text(text)
                    .font(.system(size: 11))
                    .foregroundColor(enabled ? OnlineSkin.accent : OnlineSkin.dim)
            }
            Slider(value: value, in: range)
                .tint(OnlineSkin.accent)
                .disabled(!enabled)
                .padding(.top, 2)
        }
        .padding(.top, 10)
    }

    private func note(_ text: String) -> some View {
        Text(text)
            .font(.system(size: 11.5))
            .foregroundColor(OnlineSkin.hint)
            .lineSpacing(3)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.top, 2)
    }

    private func note2(_ text: String) -> some View {
        Text(text).font(.system(size: 10.5)).foregroundColor(OnlineSkin.hint)
    }
}

// MARK: - 目录

struct OnlineChapterPanel: View {
    let chapters: [(String, String)]
    let index: Int
    let onPick: (Int) -> Void
    let onClose: () -> Void

    var body: some View {
        VStack(spacing: 0) {
            Capsule()
                .fill(Color.white.opacity(0.20))
                .frame(width: 36, height: 4)
                .padding(.top, 10)
                .padding(.bottom, 10)

            HStack(spacing: 8) {
                Text("目录")
                    .font(.system(size: 13.5, weight: .semibold))
                    .foregroundColor(.white)
                Text("\(chapters.count) 话")
                    .font(.system(size: 10.5))
                    .foregroundColor(OnlineSkin.dim)
                Spacer(minLength: 0)
                Button(action: onClose) {
                    Text("关闭")
                        .font(.system(size: 11))
                        .foregroundColor(OnlineSkin.dim)
                }
                .buttonStyle(.plain)
            }
            .padding(.horizontal, 18)
            .padding(.bottom, 6)

            ScrollView(showsIndicators: false) {
                LazyVStack(spacing: 0) {
                    ForEach(Array(chapters.indices), id: \.self) { i in
                        Button {
                            if i != index { onPick(i) }
                            onClose()
                        } label: {
                            HStack {
                                Text(chapters[i].1.isEmpty ? "第 \(i + 1) 话" : chapters[i].1)
                                    .font(.system(size: 13))
                                    .foregroundColor(i == index ? OnlineSkin.accent : .white)
                                    .lineLimit(1)
                                Spacer(minLength: 0)
                                if i == index {
                                    Text("当前")
                                        .font(.system(size: 10.5))
                                        .foregroundColor(OnlineSkin.accent)
                                }
                            }
                            .padding(.horizontal, 10)
                            .padding(.vertical, 11)
                            .background(
                                RoundedRectangle(cornerRadius: 8)
                                    .fill(i == index ? OnlineSkin.accent.opacity(0.12) : Color.clear)
                            )
                            .contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                    }
                }
                .padding(.horizontal, 8)
                .padding(.bottom, 18)
            }
            .frame(maxHeight: 440)
        }
        .frame(maxWidth: .infinity)
        .background(OnlineSkin.sheet)
        .clipShape(RoundedRectangle(cornerRadius: 20, style: .continuous))
    }
}

// MARK: - 小零件

struct OnlineChip: View {
    let label: String
    let on: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Text(label)
                .font(.system(size: 12, weight: on ? .medium : .regular))
                .foregroundColor(on ? OnlineSkin.onAccent : .white)
                .padding(.horizontal, 14)
                .padding(.vertical, 7)
                .background(Capsule().fill(on ? OnlineSkin.accent : Color.clear))
                .overlay(
                    Capsule().stroke(on ? OnlineSkin.accent : OnlineSkin.hair, lineWidth: 1)
                )
        }
        .buttonStyle(.plain)
    }
}

/// 会折行的 HStack（iOS 16 的 Layout 协议）。
struct FlowLayout: Layout {
    var spacing: CGFloat = 8

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let maxWidth = proposal.width ?? .infinity
        var x: CGFloat = 0
        var y: CGFloat = 0
        var rowHeight: CGFloat = 0

        for subview in subviews {
            let size = subview.sizeThatFits(.unspecified)
            if x > 0, x + size.width > maxWidth {
                x = 0
                y += rowHeight + spacing
                rowHeight = 0
            }
            x += size.width + spacing
            rowHeight = max(rowHeight, size.height)
        }
        return CGSize(width: maxWidth.isFinite ? maxWidth : x, height: y + rowHeight)
    }

    func placeSubviews(
        in bounds: CGRect,
        proposal: ProposedViewSize,
        subviews: Subviews,
        cache: inout ()
    ) {
        var x = bounds.minX
        var y = bounds.minY
        var rowHeight: CGFloat = 0

        for subview in subviews {
            let size = subview.sizeThatFits(.unspecified)
            if x > bounds.minX, x + size.width > bounds.maxX {
                x = bounds.minX
                y += rowHeight + spacing
                rowHeight = 0
            }
            subview.place(at: CGPoint(x: x, y: y), proposal: ProposedViewSize(size))
            x += size.width + spacing
            rowHeight = max(rowHeight, size.height)
        }
    }
}
