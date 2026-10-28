import SwiftUI

/// 在线阅读器自己的视觉。
///
/// 本地的 `ReaderSkin` 是「两行常驻工具栏 + 一排小图标 + 文字标签」，那套是给本地画布配的。
/// 在线是网页/条漫，顶上加两行会一直压着内容，密度也在手机上显得挤 —— 所以这里单独一套：
/// 一行顶栏 + 底部弹出面板，配色固定深色（阅读页背景是黑的），accent 用品牌粉。
enum OnlineSkin {
    static let accent = Color(red: 1.0, green: 0.431, blue: 0.612)      // #FF6E9C
    static let onAccent = Color(red: 0.086, green: 0.031, blue: 0.047)  // #16080C
    static let bar = Color.black.opacity(0.82)
    static let sheet = Color(red: 0.094, green: 0.094, blue: 0.106)     // #18181B
    static let hair = Color.white.opacity(0.12)
    static let dim = Color.white.opacity(0.60)
    static let hint = Color.white.opacity(0.40)
    static let scrim = Color.black.opacity(0.40)
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
                Image(systemName: "gearshape")
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

// MARK: - 底部设置面板

struct OnlineSettingsPanel: View {
    @Binding var prefs: ReaderPrefs
    let onClose: () -> Void

    var body: some View {
        VStack(spacing: 0) {
            Capsule()
                .fill(Color.white.opacity(0.22))
                .frame(width: 36, height: 4)
                .padding(.top, 10)
                .padding(.bottom, 16)

            ScrollView(showsIndicators: false) {
                VStack(alignment: .leading, spacing: 0) {
                    sectionTitle("阅读器")
                    FlowLayout(spacing: 8) {
                        ForEach(ReaderLayout.allCases) { layout in
                            OnlineChip(label: layout.label, on: prefs.onlineLayout == layout) {
                                prefs.onlineLayout = layout
                            }
                        }
                    }
                    .padding(.top, 9)

                    HStack(spacing: 8) {
                        sectionTitle("阅读方向")
                        Spacer(minLength: 0)
                        OnlineChip(label: "左到右", on: !prefs.rightToLeft) {
                            prefs.rightToLeft = false
                        }
                        OnlineChip(label: "右到左", on: prefs.rightToLeft) {
                            prefs.rightToLeft = true
                        }
                    }
                    .padding(.top, 18)

                    if prefs.onlineLayout == .webtoon || prefs.onlineLayout == .free {
                        Text("竖条模式是上下滚的，方向只在相册 / 双页 / 滑动里生效")
                            .font(.system(size: 10.5))
                            .foregroundColor(OnlineSkin.hint)
                            .padding(.top, 6)
                    }

                    slider(
                        "页面间距",
                        value: Binding(
                            get: { Double(prefs.pageGapPercent) },
                            set: { prefs.pageGapPercent = Int($0.rounded()) }
                        ),
                        range: 0...20,
                        text: "\(prefs.pageGapPercent)%"
                    )
                    .padding(.top, 14)

                    slider(
                        "亮度",
                        value: Binding(
                            get: { prefs.brightness },
                            set: { prefs.brightness = $0 }
                        ),
                        range: 5...100,
                        text: prefs.autoBrightness ? "跟随系统" : "\(Int(prefs.brightness))%",
                        enabled: !prefs.autoBrightness
                    )

                    slider(
                        "蓝光过滤",
                        value: Binding(
                            get: { Double(prefs.blueLight) },
                            set: { prefs.blueLight = Int($0.rounded()) }
                        ),
                        range: 0...100,
                        text: prefs.blueLight == 0 ? "关" : "\(prefs.blueLight)"
                    )

                    FlowLayout(spacing: 8) {
                        OnlineChip(label: "灰度", on: prefs.grayscale) {
                            prefs.grayscale.toggle()
                        }
                        OnlineChip(label: "反色", on: prefs.invert) {
                            prefs.invert.toggle()
                        }
                        OnlineChip(label: "自动亮度", on: prefs.autoBrightness) {
                            prefs.autoBrightness.toggle()
                        }
                        OnlineChip(label: "常亮", on: prefs.keepScreenOn) {
                            prefs.keepScreenOn.toggle()
                        }
                    }
                    .padding(.top, 16)
                }
                .padding(.horizontal, 18)
                .padding(.bottom, 22)
            }
        }
        .frame(maxWidth: .infinity)
        .background(OnlineSkin.sheet)
        .clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
    }

    private func sectionTitle(_ text: String) -> some View {
        Text(text)
            .font(.system(size: 12, weight: .medium))
            .foregroundColor(.white)
    }

    private func slider(
        _ label: String,
        value: Binding<Double>,
        range: ClosedRange<Double>,
        text: String,
        enabled: Bool = true
    ) -> some View {
        VStack(spacing: 0) {
            HStack {
                Text(label)
                    .font(.system(size: 12))
                    .foregroundColor(.white)
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
                .fill(Color.white.opacity(0.22))
                .frame(width: 36, height: 4)
                .padding(.top, 10)
                .padding(.bottom, 14)

            HStack(spacing: 8) {
                Text("目录")
                    .font(.system(size: 12, weight: .medium))
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
        .clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
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
