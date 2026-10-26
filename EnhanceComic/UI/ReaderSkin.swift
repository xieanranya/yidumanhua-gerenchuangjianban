import SwiftUI

// ======================================================================================= theme

/// One palette for the whole reader chrome. Two instances exist: the near-black one copied
/// from the reference reader's dark mode and a paper-white one for reading outdoors.
struct Skin {
    let chrome: Color
    let bar: Color
    let panel: Color
    let menu: Color
    let fg: Color
    let dim: Color
    let hair: Color
    let track: Color
    let accent: Color

    /// 跟 App 界面同一套：纯黑打底 + PicaComic 粉，不再用旧版那个蓝色。
    static let dark = Skin(
        chrome: Color(hex: 0x000000),
        bar: Color(hex: 0x000000),
        panel: Color(hex: 0x0A0A0A),
        menu: Color(hex: 0x171717),
        fg: Color(hex: 0xFAFAFA),
        dim: Color(hex: 0xA3A3A3),
        hair: Color(hex: 0x262626),
        track: Color.white.opacity(0.22),
        accent: Color(hex: 0xFF6E9C)
    )

    static let light = Skin(
        chrome: .white,
        bar: .white,
        panel: Color(hex: 0xF7F7F7),
        menu: .white,
        fg: Color(hex: 0x111111),
        dim: Color(hex: 0x636363),
        hair: Color(hex: 0xE2E2E2),
        track: Color.black.opacity(0.12),
        accent: Color(hex: 0xE0336E)
    )
}

func skinFor(_ light: Bool) -> Skin { light ? .light : .dark }

/// Reading panels, one per tool icon.
enum ReaderPanel: Equatable {
    case none, gap, filter, turn, layout, bookmark, settings

    var title: String {
        switch self {
        case .gap: return "页面间距"
        case .filter: return "滤镜"
        case .turn: return "翻页方式"
        case .layout: return "页面布局"
        case .bookmark: return "书签"
        case .settings: return "设置"
        case .none: return ""
        }
    }
}

// ====================================================================================== glyphs

enum Glyph {
    case gap, filter, turn, layout, bookmark, gear, list, theme
    case back, more, check, left, right, sun, crop, spark
    // 底部四个 Tab 与搜索框用的图标，跟上面一套同样的细线风格
    case home, discover, shelf, user, search
}

private struct GlyphInk {
    let path: Path
    let filled: Bool
}

/// Thin line icons drawn by hand in a 24x24 grid. SF Symbols' filled variants at this size
/// read as heavy blobs next to the reference reader's hairlines, so every icon is a stroke.
private func glyphInk(_ glyph: Glyph, _ k: CGFloat) -> [GlyphInk] {
    var out: [GlyphInk] = []

    func point(_ x: CGFloat, _ y: CGFloat) -> CGPoint { CGPoint(x: x * k, y: y * k) }

    func stroke(_ build: (inout Path) -> Void) {
        var p = Path()
        build(&p)
        out.append(GlyphInk(path: p, filled: false))
    }

    func dot(_ cx: CGFloat, _ cy: CGFloat, _ r: CGFloat) {
        let rect = CGRect(x: (cx - r) * k, y: (cy - r) * k, width: 2 * r * k, height: 2 * r * k)
        out.append(GlyphInk(path: Path(ellipseIn: rect), filled: true))
    }

    func ring(_ cx: CGFloat, _ cy: CGFloat, _ r: CGFloat) {
        let rect = CGRect(x: (cx - r) * k, y: (cy - r) * k, width: 2 * r * k, height: 2 * r * k)
        out.append(GlyphInk(path: Path(ellipseIn: rect), filled: false))
    }

    switch glyph {
    case .gap:
        stroke { p in
            p.move(to: point(4, 6.5)); p.addLine(to: point(20, 6.5))
            p.move(to: point(4, 17.5)); p.addLine(to: point(20, 17.5))
            p.move(to: point(12, 9.2)); p.addLine(to: point(12, 14.8))
            p.move(to: point(9.9, 11.2)); p.addLine(to: point(12, 9.1))
            p.addLine(to: point(14.1, 11.2))
            p.move(to: point(9.9, 12.8)); p.addLine(to: point(12, 14.9))
            p.addLine(to: point(14.1, 12.8))
        }

    case .filter:
        stroke { p in
            p.move(to: point(12, 3.2))
            p.addCurve(to: point(6, 14.6), control1: point(12, 3.2), control2: point(6, 10.2))
            p.addCurve(to: point(12, 20.6), control1: point(6, 17.9), control2: point(8.7, 20.6))
            p.addCurve(to: point(18, 14.6), control1: point(15.3, 20.6), control2: point(18, 17.9))
            p.addCurve(to: point(12, 3.2), control1: point(18, 10.2), control2: point(12, 3.2))
            p.closeSubpath()
        }

    case .turn:
        stroke { p in
            p.move(to: point(3.6, 9)); p.addLine(to: point(20.4, 9))
            p.move(to: point(17.7, 6.3)); p.addLine(to: point(20.4, 9))
            p.addLine(to: point(17.7, 11.7))
            p.move(to: point(20.4, 15)); p.addLine(to: point(3.6, 15))
            p.move(to: point(6.3, 12.3)); p.addLine(to: point(3.6, 15))
            p.addLine(to: point(6.3, 17.7))
        }

    case .layout:
        stroke { p in
            p.addRoundedRect(
                in: CGRect(x: 3.6 * k, y: 4.2 * k, width: 16.8 * k, height: 15.6 * k),
                cornerSize: CGSize(width: 2 * k, height: 2 * k)
            )
            p.move(to: point(12, 4.2)); p.addLine(to: point(12, 19.8))
        }

    case .bookmark:
        stroke { p in
            p.move(to: point(7, 4))
            p.addLine(to: point(17, 4))
            p.addLine(to: point(17, 20.2))
            p.addLine(to: point(12, 16.2))
            p.addLine(to: point(7, 20.2))
            p.closeSubpath()
        }

    case .gear:
        ring(12, 12, 3.1)
        stroke { p in
            for i in 0..<8 {
                let a = Double(i) * 45.0 * Double.pi / 180.0
                let c = CGFloat(cos(a))
                let s = CGFloat(sin(a))
                p.move(to: point(12 + c * 5.4, 12 + s * 5.4))
                p.addLine(to: point(12 + c * 7.6, 12 + s * 7.6))
            }
        }

    case .list:
        stroke { p in
            for y in [CGFloat(6.8), 12, 17.2] {
                p.move(to: point(8.6, y)); p.addLine(to: point(20, y))
            }
            dot(5, 6.8, 1.15)
            dot(5, 12, 1.15)
            dot(5, 17.2, 1.15)
        }

    case .theme:
        ring(12, 12, 7.2)
        var half = Path()
        half.move(to: point(12, 4.8))
        half.addArc(
            center: point(12, 12),
            radius: 7.2 * k,
            startAngle: .degrees(-90),
            endAngle: .degrees(90),
            clockwise: false
        )
        half.closeSubpath()
        out.append(GlyphInk(path: half, filled: true))

    case .back:
        stroke { p in
            p.move(to: point(14.6, 4.4))
            p.addLine(to: point(7.4, 12))
            p.addLine(to: point(14.6, 19.6))
        }

    case .more:
        dot(12, 5.2, 1.45)
        dot(12, 12, 1.45)
        dot(12, 18.8, 1.45)

    case .check:
        stroke { p in
            p.move(to: point(4.8, 12.6))
            p.addLine(to: point(9.8, 17.4))
            p.addLine(to: point(19.2, 7))
        }

    case .left:
        stroke { p in
            p.move(to: point(14.4, 5))
            p.addLine(to: point(7.6, 12))
            p.addLine(to: point(14.4, 19))
        }

    case .right:
        stroke { p in
            p.move(to: point(9.6, 5))
            p.addLine(to: point(16.4, 12))
            p.addLine(to: point(9.6, 19))
        }

    case .sun:
        ring(12, 12, 3.7)
        stroke { p in
            for i in 0..<8 {
                let a = Double(i) * 45.0 * Double.pi / 180.0
                let c = CGFloat(cos(a))
                let s = CGFloat(sin(a))
                p.move(to: point(12 + c * 6.1, 12 + s * 6.1))
                p.addLine(to: point(12 + c * 8.3, 12 + s * 8.3))
            }
        }

    case .crop:
        stroke { p in
            p.move(to: point(7, 3.4)); p.addLine(to: point(7, 17)); p.addLine(to: point(20.6, 17))
            p.move(to: point(3.4, 7)); p.addLine(to: point(17, 7)); p.addLine(to: point(17, 20.6))
        }

    case .spark:
        stroke { p in
            p.move(to: point(12, 3.2))
            p.addLine(to: point(13.9, 9.4))
            p.addLine(to: point(20.2, 11.2))
            p.addLine(to: point(13.9, 13))
            p.addLine(to: point(12, 19.2))
            p.addLine(to: point(10.1, 13))
            p.addLine(to: point(3.8, 11.2))
            p.addLine(to: point(10.1, 9.4))
            p.closeSubpath()
        }

    case .home:
        stroke { p in
            p.move(to: point(3.4, 10.6))
            p.addLine(to: point(12, 3.6))
            p.addLine(to: point(20.6, 10.6))
            p.move(to: point(5.9, 9.3))
            p.addLine(to: point(5.9, 20.2))
            p.addLine(to: point(18.1, 20.2))
            p.addLine(to: point(18.1, 9.3))
            p.move(to: point(10, 20.2))
            p.addLine(to: point(10, 14.2))
            p.addLine(to: point(14, 14.2))
            p.addLine(to: point(14, 20.2))
        }

    case .discover:
        ring(12, 12, 8.4)
        stroke { p in
            p.move(to: point(15.6, 8.4))
            p.addLine(to: point(13.5, 13.5))
            p.addLine(to: point(8.4, 15.6))
            p.addLine(to: point(10.5, 10.5))
            p.closeSubpath()
        }

    case .shelf:
        stroke { p in
            p.addRoundedRect(
                in: CGRect(x: 3.8 * k, y: 4.6 * k, width: 4.6 * k, height: 14.8 * k),
                cornerSize: CGSize(width: 1.2 * k, height: 1.2 * k)
            )
            p.addRoundedRect(
                in: CGRect(x: 9.7 * k, y: 4.6 * k, width: 4.6 * k, height: 14.8 * k),
                cornerSize: CGSize(width: 1.2 * k, height: 1.2 * k)
            )
            p.move(to: point(16.6, 5.8))
            p.addLine(to: point(20.4, 6.8))
            p.addLine(to: point(18.0, 19.6))
            p.addLine(to: point(14.4, 18.6))
            p.closeSubpath()
        }

    case .user:
        ring(12, 8.4, 3.5)
        stroke { p in
            p.move(to: point(4.6, 20.4))
            p.addCurve(
                to: point(19.4, 20.4),
                control1: point(5.4, 13.8),
                control2: point(18.6, 13.8)
            )
        }

    case .search:
        ring(10.8, 10.8, 6.3)
        stroke { p in
            p.move(to: point(15.5, 15.5))
            p.addLine(to: point(20.4, 20.4))
        }
    }
    return out
}

struct GlyphView: View {
    let glyph: Glyph
    var tint: Color = .white
    var size: CGFloat = 22
    var strokeWidth: CGFloat = 1.5

    var body: some View {
        Canvas { context, canvasSize in
            let k = min(canvasSize.width, canvasSize.height) / 24
            let style = StrokeStyle(lineWidth: strokeWidth, lineCap: .round, lineJoin: .round)
            for ink in glyphInk(glyph, k) {
                if ink.filled {
                    context.fill(ink.path, with: .color(tint))
                } else {
                    context.stroke(ink.path, with: .color(tint), style: style)
                }
            }
        }
        .frame(width: size, height: size)
    }
}

// ================================================================================== top chrome

private struct ToolItem: View {
    let glyph: Glyph
    let label: String
    let active: Bool
    let skin: Skin
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            VStack(spacing: 3) {
                GlyphView(glyph: glyph, tint: active ? skin.accent : skin.fg, size: 21)
                Text(label)
                    .font(.system(size: 8.5))
                    .foregroundColor(active ? skin.accent : skin.dim)
                    .lineLimit(1)
            }
            .padding(.horizontal, 15)
            .padding(.vertical, 5)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}

struct ReaderTopChrome: View {
    let title: String
    let pageLabel: String
    @Binding var prefs: ReaderPrefs
    @Binding var enhance: EnhanceSettings
    @Binding var panel: ReaderPanel
    let marked: Bool
    let hasChapters: Bool
    let onBack: () -> Void
    let onMenu: () -> Void
    let onChapters: () -> Void
    let onAddBookmark: () -> Void
    let onShowBookmarks: () -> Void

    private var skin: Skin { skinFor(prefs.chromeLight) }

    private var filtered: Bool {
        prefs.grayscale || prefs.invert || prefs.blueLight > 0
            || enhance.sharpen || enhance.contrastPass
    }

    var body: some View {
        VStack(spacing: 0) {
            titleRow
            toolRow
            if panel != .none { panelBody }
        }
        .background(skin.chrome.ignoresSafeArea(edges: .top))
    }

    private var titleRow: some View {
        HStack(spacing: 0) {
            Button(action: onBack) {
                GlyphView(glyph: .back, tint: skin.fg, size: 21)
                    .frame(width: 44, height: 46)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)

            VStack(alignment: .leading, spacing: 1) {
                Text(title)
                    .font(.system(size: 14.5, weight: .medium))
                    .foregroundColor(skin.fg)
                    .lineLimit(1)
                Text(pageLabel)
                    .font(.system(size: 10.5))
                    .foregroundColor(skin.dim)
                    .lineLimit(1)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.trailing, 8)

            Button(action: onMenu) {
                GlyphView(glyph: .more, tint: skin.fg, size: 21)
                    .frame(width: 44, height: 46)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
        }
        .frame(height: 46)
    }

    private var toolRow: some View {
        HStack(spacing: 0) {
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 0) {
                    ToolItem(glyph: .gap, label: "页面间距", active: panel == .gap, skin: skin) {
                        panel = panel == .gap ? .none : .gap
                    }
                    ToolItem(glyph: .filter, label: "滤镜", active: panel == .filter || filtered, skin: skin) {
                        panel = panel == .filter ? .none : .filter
                    }
                    ToolItem(glyph: .turn, label: "翻页方式", active: panel == .turn, skin: skin) {
                        panel = panel == .turn ? .none : .turn
                    }
                    ToolItem(
                        glyph: .layout,
                        label: "页面布局",
                        active: panel == .layout || prefs.doublePage || prefs.scaleMode != .fit,
                        skin: skin
                    ) {
                        panel = panel == .layout ? .none : .layout
                    }
                    ToolItem(glyph: .bookmark, label: "书签", active: marked, skin: skin) {
                        panel = panel == .bookmark ? .none : .bookmark
                    }
                    if hasChapters {
                        ToolItem(glyph: .list, label: "目录", active: false, skin: skin, action: onChapters)
                    }
                    ToolItem(glyph: .gear, label: "设置", active: panel == .settings, skin: skin) {
                        panel = panel == .settings ? .none : .settings
                    }
                    Color.clear.frame(width: 12)
                }
            }
            GlyphView(glyph: .right, tint: skin.dim, size: 14)
                .frame(width: 22, height: 52)
        }
        .frame(height: 52)
        .background(skin.bar)
    }

    private var panelBody: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack(spacing: 0) {
                Text(panel.title)
                    .font(.system(size: 12, weight: .medium))
                    .foregroundColor(skin.fg)
                Spacer(minLength: 0)
                Button { panel = .none } label: {
                    Text("关闭")
                        .font(.system(size: 11))
                        .foregroundColor(skin.dim)
                        .padding(.horizontal, 8)
                        .frame(height: 28)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
            }
            .padding(.bottom, 4)

            switch panel {
            case .gap:
                GapPanel(prefs: $prefs, skin: skin)
            case .filter:
                FilterPanel(prefs: $prefs, enhance: $enhance, skin: skin)
            case .turn:
                TurnPanel(prefs: $prefs, skin: skin)
            case .layout:
                LayoutPanel(prefs: $prefs, skin: skin)
            case .bookmark:
                BookmarkPanel(skin: skin, onAdd: onAddBookmark, onList: onShowBookmarks)
            case .settings:
                SettingsPanel(prefs: $prefs, skin: skin)
            case .none:
                EmptyView()
            }
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 10)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(skin.panel)
    }
}

// ===================================================================================== widgets

struct SkinSwitch: View {
    let checked: Bool
    let skin: Skin
    let onToggle: (Bool) -> Void

    var body: some View {
        Button { onToggle(!checked) } label: {
            ZStack(alignment: checked ? .trailing : .leading) {
                Capsule()
                    .fill(checked ? skin.accent : skin.track)
                    .frame(width: 36, height: 20)
                Circle()
                    .fill(Color.white)
                    .frame(width: 14, height: 14)
                    .padding(3)
            }
            .frame(width: 36, height: 20)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}

/// Flat slider: a 2.5pt track and a 12pt thumb, no system gloss.
struct SkinSlider: View {
    let value: Double
    let range: ClosedRange<Double>
    let skin: Skin
    var enabled = true
    let onChange: (Double) -> Void

    var body: some View {
        GeometryReader { geo in
            let w = max(geo.size.width, 1)
            let span = max(range.upperBound - range.lowerBound, 0.0001)
            let frac = CGFloat(min(max((value - range.lowerBound) / span, 0), 1))
            ZStack(alignment: .leading) {
                Rectangle()
                    .fill(enabled ? skin.track : skin.track.opacity(0.05))
                    .frame(height: 2.5)
                Rectangle()
                    .fill(enabled ? skin.accent : skin.dim)
                    .frame(width: w * frac, height: 2.5)
                Circle()
                    .fill(enabled ? skin.accent : skin.dim)
                    .frame(width: 12, height: 12)
                    .offset(x: min(max(w * frac - 6, 0), w - 12))
            }
            .frame(width: w, height: 30)
            .contentShape(Rectangle())
            .gesture(
                DragGesture(minimumDistance: 0, coordinateSpace: .local)
                    .onChanged { drag in
                        guard enabled else { return }
                        let f = min(max(drag.location.x / w, 0), 1)
                        onChange(range.lowerBound + Double(f) * span)
                    }
            )
        }
        .frame(height: 30)
    }
}

struct SkinCheckRow: View {
    let label: String
    let checked: Bool
    let skin: Skin
    var hint: String? = nil
    let onToggle: (Bool) -> Void

    var body: some View {
        Button { onToggle(!checked) } label: {
            HStack(spacing: 9) {
                ZStack {
                    RoundedRectangle(cornerRadius: 3)
                        .fill(checked ? skin.accent : Color.clear)
                        .frame(width: 16, height: 16)
                    RoundedRectangle(cornerRadius: 3)
                        .stroke(checked ? Color.clear : skin.dim, lineWidth: 1.2)
                        .frame(width: 16, height: 16)
                    if checked {
                        GlyphView(glyph: .check, tint: .white, size: 12, strokeWidth: 2)
                    }
                }
                Text(label)
                    .font(.system(size: 12))
                    .foregroundColor(skin.fg)
                    .lineLimit(1)
                if let hint {
                    Text(hint)
                        .font(.system(size: 9))
                        .foregroundColor(skin.dim)
                        .lineLimit(1)
                }
                Spacer(minLength: 0)
            }
            .frame(height: 34)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}

struct InlineSwitchRow: View {
    let label: String
    let checked: Bool
    let skin: Skin
    let onToggle: (Bool) -> Void

    var body: some View {
        HStack(spacing: 7) {
            Text(label)
                .font(.system(size: 11.5))
                .foregroundColor(skin.fg)
                .lineLimit(1)
            SkinSwitch(checked: checked, skin: skin, onToggle: onToggle)
        }
        .frame(height: 34)
    }
}

/// Segmented control used for enumerations (scale filter, scale mode).
struct SkinSeg: View {
    let labels: [String]
    let selected: Int
    let skin: Skin
    let onSelect: (Int) -> Void

    var body: some View {
        HStack(spacing: 0) {
            ForEach(Array(labels.enumerated()), id: \.offset) { index, label in
                Button { onSelect(index) } label: {
                    Text(label)
                        .font(.system(size: 11))
                        .foregroundColor(index == selected ? skin.accent : skin.dim)
                        .lineLimit(1)
                        .frame(maxWidth: .infinity, maxHeight: .infinity)
                        .background(
                            RoundedRectangle(cornerRadius: 3)
                                .fill(index == selected ? skin.accent.opacity(0.16) : Color.clear)
                        )
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
            }
        }
        .frame(height: 30)
        .background(RoundedRectangle(cornerRadius: 4).fill(skin.bar))
        .overlay(RoundedRectangle(cornerRadius: 4).stroke(skin.hair, lineWidth: 1))
    }
}

struct SkinTextButton: View {
    let label: String
    let skin: Skin
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Text(label)
                .font(.system(size: 11.5))
                .foregroundColor(skin.fg)
                .padding(.horizontal, 14)
                .frame(height: 30)
                .background(RoundedRectangle(cornerRadius: 4).fill(skin.bar))
                .overlay(RoundedRectangle(cornerRadius: 4).stroke(skin.hair, lineWidth: 1))
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}

// ====================================================================================== panels

private struct GapPanel: View {
    @Binding var prefs: ReaderPrefs
    let skin: Skin
    @State private var value: Double = 0

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack {
                Text("页面之间留白")
                    .font(.system(size: 11))
                    .foregroundColor(skin.dim)
                Spacer()
                Text("\(Int(value.rounded()))%")
                    .font(.system(size: 11.5))
                    .foregroundColor(skin.accent)
            }
            SkinSlider(value: value, range: 0...30, skin: skin) { next in
                value = next
                prefs.pageGapPercent = Int(next.rounded())
            }
            Text("竖向连续阅读保持 0% 才是无缝的；只有分页模式才需要留白。")
                .font(.system(size: 9.5))
                .foregroundColor(skin.dim)
        }
        .onAppear { value = Double(prefs.pageGapPercent) }
    }
}

private struct FilterPanel: View {
    @Binding var prefs: ReaderPrefs
    @Binding var enhance: EnhanceSettings
    let skin: Skin

    private var filterIndex: Int {
        switch enhance.scaleFilter {
        case .nearest, .bilinear: return 0
        case .bicubic: return 1
        case .lanczos3: return 2
        }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text("缩放插值")
                .font(.system(size: 10))
                .foregroundColor(skin.dim)
                .padding(.bottom, 4)
            SkinSeg(labels: ["线性", "Bicubic", "Lanczos"], selected: filterIndex, skin: skin) { index in
                switch index {
                case 0: enhance.scaleFilter = .bilinear
                case 1: enhance.scaleFilter = .bicubic
                default: enhance.scaleFilter = .lanczos3
                }
            }
            Color.clear.frame(height: 6)
            SkinCheckRow(label: "黑白", checked: prefs.grayscale, skin: skin) {
                prefs.grayscale = $0
            }
            SkinCheckRow(label: "自动对比度", checked: enhance.contrastPass, skin: skin) {
                enhance.contrastPass = $0
            }
            SkinCheckRow(
                label: "锐化（画质增强）",
                checked: enhance.sharpen,
                skin: skin,
                hint: "低清源更明显"
            ) {
                enhance.sharpen = $0
            }
            SkinCheckRow(label: "反色", checked: prefs.invert, skin: skin) {
                prefs.invert = $0
            }
            Color.clear.frame(height: 4)
            HStack(spacing: 0) {
                Text("蓝光过滤")
                    .font(.system(size: 11))
                    .foregroundColor(skin.dim)
                Spacer(minLength: 0)
                Text(prefs.blueLight > 0 ? "\(prefs.blueLight)%" : "关")
                    .font(.system(size: 11))
                    .foregroundColor(prefs.blueLight > 0 ? skin.accent : skin.dim)
                    .frame(width: 54, alignment: .trailing)
            }
            SkinSlider(
                value: Double(prefs.blueLight),
                range: 0...100,
                skin: skin
            ) { next in
                prefs.blueLight = Int(next.rounded())
            }
            Text("压低屏幕蓝光，暖色夜间阅读；0% 即关闭。")
                .font(.system(size: 9.5))
                .foregroundColor(skin.dim)
        }
    }
}

private struct TurnOption {
    let label: String
    let glyph: Glyph
    let mode: ReadingMode
    let rightToLeft: Bool
}

private struct TurnPanel: View {
    @Binding var prefs: ReaderPrefs
    let skin: Skin

    private var options: [TurnOption] {
        [
            TurnOption(label: "右翻页", glyph: .right, mode: .paged, rightToLeft: false),
            TurnOption(label: "左翻页", glyph: .left, mode: .paged, rightToLeft: true),
            TurnOption(label: "右翻·垂直", glyph: .gap, mode: .vertical, rightToLeft: false),
            TurnOption(label: "左翻·垂直", glyph: .gap, mode: .vertical, rightToLeft: true),
            TurnOption(label: "右翻·水平", glyph: .turn, mode: .horizontal, rightToLeft: false),
            TurnOption(label: "左翻·水平", glyph: .turn, mode: .horizontal, rightToLeft: true),
        ]
    }

    var body: some View {
        VStack(spacing: 8) {
            ForEach(0..<2, id: \.self) { row in
                HStack(spacing: 8) {
                    ForEach(0..<3, id: \.self) { column in
                        cell(index: row * 3 + column)
                    }
                }
            }
        }
    }

    private func cell(index: Int) -> some View {
        let option = options[index]
        let on = prefs.readingMode == option.mode && prefs.rightToLeft == option.rightToLeft
        return Button {
            prefs.readingMode = option.mode
            prefs.rightToLeft = option.rightToLeft
        } label: {
            VStack(spacing: 3) {
                GlyphView(glyph: option.glyph, tint: on ? skin.accent : skin.fg, size: 18)
                Text(option.label)
                    .font(.system(size: 9))
                    .foregroundColor(on ? skin.accent : skin.dim)
                    .lineLimit(1)
            }
            .frame(maxWidth: .infinity)
            .frame(height: 56)
            .background(
                RoundedRectangle(cornerRadius: 4)
                    .fill(on ? skin.accent.opacity(0.14) : skin.bar)
            )
            .overlay(
                RoundedRectangle(cornerRadius: 4)
                    .stroke(on ? skin.accent : skin.hair, lineWidth: 1)
            )
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}

private struct LayoutPanel: View {
    @Binding var prefs: ReaderPrefs
    let skin: Skin

    private var modeIndex: Int {
        ScaleMode.allCases.firstIndex(of: prefs.scaleMode) ?? 0
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text("缩放方式")
                .font(.system(size: 10))
                .foregroundColor(skin.dim)
                .padding(.bottom, 4)
            SkinSeg(labels: ScaleMode.allCases.map { $0.label }, selected: modeIndex, skin: skin) { index in
                let all = ScaleMode.allCases
                guard all.indices.contains(index) else { return }
                prefs.scaleMode = all[index]
            }
            Color.clear.frame(height: 8)
            HStack(spacing: 14) {
                InlineSwitchRow(label: "双页", checked: prefs.doublePage, skin: skin) { on in
                    prefs.doublePage = on
                    if !on { prefs.forceSpread = false }
                }
                InlineSwitchRow(label: "强制并排", checked: prefs.forceSpread, skin: skin) { on in
                    prefs.forceSpread = on
                    if on { prefs.doublePage = true }
                }
            }
            HStack(spacing: 14) {
                InlineSwitchRow(label: "拆宽页", checked: prefs.splitWidePages, skin: skin) {
                    prefs.splitWidePages = $0
                }
                InlineSwitchRow(label: "自动裁白边", checked: prefs.autoCropWhiteBorders, skin: skin) {
                    prefs.autoCropWhiteBorders = $0
                }
            }
        }
    }
}

private struct BookmarkPanel: View {
    let skin: Skin
    let onAdd: () -> Void
    let onList: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("书签会记住当前页，并从书架直接跳回。")
                .font(.system(size: 10.5))
                .foregroundColor(skin.dim)
            HStack(spacing: 8) {
                SkinTextButton(label: "添加书签", skin: skin, action: onAdd)
                SkinTextButton(label: "书签列表", skin: skin, action: onList)
            }
        }
    }
}

private struct SettingsPanel: View {
    @Binding var prefs: ReaderPrefs
    let skin: Skin
    @State private var brightness: Double = 50

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack(spacing: 0) {
                Text("亮度")
                    .font(.system(size: 11))
                    .foregroundColor(skin.dim)
                Spacer(minLength: 0)
                InlineSwitchRow(label: "自动", checked: prefs.autoBrightness, skin: skin) {
                    prefs.autoBrightness = $0
                }
                Text(prefs.autoBrightness ? "跟随系统" : "\(Int(brightness.rounded()))%")
                    .font(.system(size: 11))
                    .foregroundColor(skin.accent)
                    .frame(width: 54, alignment: .trailing)
            }
            SkinSlider(
                value: brightness,
                range: 5...100,
                skin: skin,
                enabled: !prefs.autoBrightness
            ) { next in
                brightness = next
                prefs.brightness = next
            }
            HStack(spacing: 0) {
                Text("背景")
                    .font(.system(size: 11))
                    .foregroundColor(skin.dim)
                    .padding(.trailing, 10)
                ForEach(0..<3, id: \.self) { index in
                    Swatch(index: index, selected: prefs.background, skin: skin) {
                        prefs.background = index
                    }
                    .padding(.trailing, 6)
                }
                Spacer(minLength: 0)
                InlineSwitchRow(label: "常亮", checked: prefs.keepScreenOn, skin: skin) {
                    prefs.keepScreenOn = $0
                }
            }
            HStack(spacing: 14) {
                InlineSwitchRow(label: "双击缩放", checked: prefs.doubleTapZoom, skin: skin) {
                    prefs.doubleTapZoom = $0
                }
                InlineSwitchRow(label: "浅色栏", checked: prefs.chromeLight, skin: skin) {
                    prefs.chromeLight = $0
                }
            }
        }
        .onAppear { brightness = prefs.brightness }
    }
}

private struct Swatch: View {
    let index: Int
    let selected: Int
    let skin: Skin
    let action: () -> Void

    private var color: Color {
        switch index {
        case 0: return Color(red: 0.043, green: 0.043, blue: 0.051)
        case 1: return Color(red: 0.086, green: 0.086, blue: 0.102)
        default: return Color(red: 0.929, green: 0.929, blue: 0.902)
        }
    }

    var body: some View {
        Button(action: action) {
            ZStack {
                RoundedRectangle(cornerRadius: 2).fill(color)
                RoundedRectangle(cornerRadius: 2)
                    .stroke(index == selected ? skin.accent : skin.hair, lineWidth: 1.5)
                if index == selected {
                    GlyphView(
                        glyph: .check,
                        tint: index == 2 ? .black : .white,
                        size: 12,
                        strokeWidth: 2
                    )
                }
            }
            .frame(width: 24, height: 24)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}

// ================================================================================= bottom chrome

struct ReaderBottomChrome: View {
    let pageLabel: String
    @Binding var sliderValue: Double
    let total: Int
    let light: Bool
    let onSeek: (Int) -> Void
    let onPrev: () -> Void
    let onNext: () -> Void

    private var skin: Skin { skinFor(light) }

    var body: some View {
        VStack(spacing: 0) {
            Text(pageLabel)
                .font(.system(size: 11))
                .foregroundColor(skin.dim)
                .padding(.top, 3)
            HStack(spacing: 0) {
                Button(action: onPrev) {
                    GlyphView(glyph: .left, tint: skin.dim, size: 16)
                        .frame(width: 34, height: 30)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)

                SkinSlider(
                    value: min(max(sliderValue, 0), Double(max(total - 1, 1))),
                    range: 0...Double(max(total - 1, 1)),
                    skin: skin
                ) { next in
                    sliderValue = next
                    onSeek(Int(next.rounded()))
                }

                Button(action: onNext) {
                    GlyphView(glyph: .right, tint: skin.dim, size: 16)
                        .frame(width: 34, height: 30)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
            }
            .padding(.horizontal, 4)
            .padding(.bottom, 4)
        }
        .background(skin.chrome.ignoresSafeArea(edges: .bottom))
    }
}

// ======================================================================================= menu

struct MenuEntry {
    let label: String
    var checked: Bool? = nil
    let action: () -> Void
}

/// The original's overflow menu: a solid slab under the title bar, not a rounded popup.
struct ReaderMenuPanel: View {
    let entries: [MenuEntry]
    let light: Bool
    let onDismiss: () -> Void

    private var skin: Skin { skinFor(light) }

    var body: some View {
        ZStack(alignment: .topTrailing) {
            Color.clear
                .contentShape(Rectangle())
                .onTapGesture { onDismiss() }

            VStack(spacing: 0) {
                ForEach(Array(entries.enumerated()), id: \.offset) { index, entry in
                    Button {
                        onDismiss()
                        entry.action()
                    } label: {
                        HStack(spacing: 8) {
                            Text(entry.label)
                                .font(.system(size: 14))
                                .foregroundColor(skin.fg)
                                .lineLimit(1)
                            Spacer(minLength: 0)
                            if entry.checked == true {
                                GlyphView(glyph: .check, tint: skin.accent, size: 16, strokeWidth: 1.8)
                            }
                        }
                        .padding(.horizontal, 20)
                        .frame(height: 48)
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)

                    if index < entries.count - 1 {
                        Rectangle()
                            .fill(skin.hair.opacity(0.6))
                            .frame(height: 1)
                    }
                }
            }
            .frame(width: 264)
            .background(skin.menu)
            .padding(.top, 46)
        }
        // The panel is dropped into the reader's full-screen ZStack; without this it would
        // be sized to the slab and centred instead of hanging under the title bar.
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topTrailing)
    }
}

// ==================================================================================== dialogs

/// Dark dialog matching the chrome; the system alert would come back in white.
struct SkinDialog<Content: View>: View {
    let title: String
    let skin: Skin
    let onDismiss: () -> Void
    private let content: () -> Content

    init(
        title: String,
        skin: Skin,
        onDismiss: @escaping () -> Void,
        @ViewBuilder content: @escaping () -> Content
    ) {
        self.title = title
        self.skin = skin
        self.onDismiss = onDismiss
        self.content = content
    }

    var body: some View {
        ZStack {
            Color.black.opacity(0.55)
                .ignoresSafeArea()
                .onTapGesture { onDismiss() }

            VStack(alignment: .leading, spacing: 0) {
                Text(title)
                    .font(.system(size: 14, weight: .medium))
                    .foregroundColor(skin.fg)
                    .padding(.bottom, 8)

                content()

                HStack {
                    Spacer(minLength: 0)
                    Button { onDismiss() } label: {
                        Text("关闭")
                            .font(.system(size: 12))
                            .foregroundColor(skin.accent)
                            .padding(.horizontal, 10)
                            .frame(height: 30)
                            .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                }
                .padding(.top, 6)
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 14)
            .background(RoundedRectangle(cornerRadius: 6).fill(skin.menu))
            .padding(.horizontal, 28)
        }
    }
}

/// Radio list used by the orientation dialog.
struct SkinRadioRow: View {
    let label: String
    let selected: Bool
    let skin: Skin
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 10) {
                ZStack {
                    Circle()
                        .stroke(selected ? skin.accent : skin.dim, lineWidth: 1.4)
                        .frame(width: 16, height: 16)
                    if selected {
                        Circle()
                            .fill(skin.accent)
                            .frame(width: 8, height: 8)
                    }
                }
                Text(label)
                    .font(.system(size: 12.5))
                    .foregroundColor(selected ? skin.accent : skin.fg)
                Spacer(minLength: 0)
            }
            .frame(height: 40)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}

/// Single-line input with the reader's own colours; the system field is a white box.
struct SkinTextField: View {
    @Binding var value: String
    let skin: Skin
    let placeholder: String

    var body: some View {
        ZStack(alignment: .leading) {
            RoundedRectangle(cornerRadius: 4)
                .fill(skin.bar)
            RoundedRectangle(cornerRadius: 4)
                .stroke(skin.hair, lineWidth: 1)
            if value.isEmpty {
                Text(placeholder)
                    .font(.system(size: 12))
                    .foregroundColor(skin.dim)
                    .padding(.horizontal, 10)
            }
            TextField("", text: $value)
                .font(.system(size: 13))
                .foregroundColor(skin.fg)
                .tint(skin.accent)
                .padding(.horizontal, 10)
        }
        .frame(height: 38)
    }
}

/// Label + switch row used inside the hardware-key dialog.
struct SkinKeyRow: View {
    let label: String
    let checked: Bool
    let skin: Skin
    var enabled = true
    let onToggle: (Bool) -> Void

    var body: some View {
        Button { if enabled { onToggle(!checked) } } label: {
            HStack(spacing: 8) {
                Text(label)
                    .font(.system(size: 12))
                    .foregroundColor(enabled ? skin.fg : skin.dim.opacity(0.5))
                    .lineLimit(1)
                Spacer(minLength: 0)
                SkinSwitch(checked: checked && enabled, skin: skin) { on in
                    if enabled { onToggle(on) }
                }
            }
            .frame(height: 40)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}

/// 左 / 中 / 右三列的动作表格，和原版的「页面按钮设置」一一对应。
struct TapZoneTable: View {
    @Binding var prefs: ReaderPrefs
    let skin: Skin

    private func action(for zone: Int) -> TapAction {
        switch zone {
        case 0: return prefs.tapLeftAction
        case 2: return prefs.tapRightAction
        default: return prefs.tapCenterAction
        }
    }

    private func assign(_ action: TapAction, to zone: Int) {
        switch zone {
        case 0: prefs.tapLeftAction = action
        case 2: prefs.tapRightAction = action
        default: prefs.tapCenterAction = action
        }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack(spacing: 0) {
                Text("点击区域")
                    .font(.system(size: 10))
                    .foregroundColor(skin.dim)
                Spacer(minLength: 0)
                ForEach(["左", "中", "右"], id: \.self) { name in
                    Text(name)
                        .font(.system(size: 10.5))
                        .foregroundColor(skin.dim)
                        .frame(width: 40)
                }
            }
            .padding(.bottom, 2)

            ForEach(TapAction.allCases) { item in
                HStack(spacing: 0) {
                    Text(item.label)
                        .font(.system(size: 12))
                        .foregroundColor(skin.fg)
                    Spacer(minLength: 0)
                    ForEach(0..<3, id: \.self) { zone in
                        Button { assign(item, to: zone) } label: {
                            ZStack {
                                if action(for: zone) == item {
                                    GlyphView(glyph: .check, tint: skin.accent, size: 15, strokeWidth: 2)
                                }
                            }
                            .frame(width: 40, height: 34)
                            .contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                    }
                }
                .frame(height: 34)
            }

            Text("竖向阅读时中间区域建议保留「显示 / 隐藏栏」。")
                .font(.system(size: 9.5))
                .foregroundColor(skin.dim)
                .padding(.top, 4)
        }
    }
}
