import SwiftUI

// ==================================================================================== 四 Tab

enum AppTab: String, CaseIterable, Identifiable {
    case home
    case discover
    case shelf
    case me

    var id: String { rawValue }

    var label: String {
        switch self {
        case .home: return "首页"
        case .discover: return "发现"
        case .shelf: return "书架"
        case .me: return "我的"
        }
    }

    var glyph: Glyph {
        switch self {
        case .home: return .home
        case .discover: return .discover
        case .shelf: return .shelf
        case .me: return .user
        }
    }
}

struct AppBottomBar: View {
    let selected: AppTab
    let onSelect: (AppTab) -> Void

    @Environment(\.appColors) private var c

    var body: some View {
        VStack(spacing: 0) {
            HLine()
            HStack(spacing: 0) {
                ForEach(AppTab.allCases) { t in
                    let on = t == selected
                    Button {
                        onSelect(t)
                    } label: {
                        VStack(spacing: 3) {
                            GlyphView(
                                glyph: t.glyph,
                                tint: on ? c.fg : c.fg3,
                                size: 21,
                                strokeWidth: on ? 1.9 : 1.5
                            )
                            Text(t.label)
                                .font(.system(size: 9.5))
                                .foregroundColor(on ? c.fg : c.fg3)
                        }
                        .frame(maxWidth: .infinity)
                        .frame(height: 48)
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                }
            }
            .padding(.top, 4)
        }
        .background(c.bg)
    }
}

/// 整个 App 的壳：主题注入 + 四个 Tab + 底部栏。
struct AppRootView: View {

    @EnvironmentObject private var ui: UiStore
    @Environment(\.colorScheme) private var systemScheme

    @State private var tab: AppTab = .home
    @State private var loadedTab = false

    private var colors: AppColors { AppColors.of(ui.isDark(systemScheme)) }

    private var preferred: ColorScheme? {
        switch ui.theme {
        case .auto: return nil
        case .day: return .light
        case .night: return .dark
        }
    }

    var body: some View {
        content
            .environment(\.appColors, colors)
            .tint(colors.accent)
            .background(colors.bg.ignoresSafeArea())
            .preferredColorScheme(preferred)
            .safeAreaInset(edge: .bottom, spacing: 0) {
                AppBottomBar(selected: tab) { picked in
                    tab = picked
                }
                .environment(\.appColors, colors)
            }
            .onAppear {
                guard !loadedTab else { return }
                loadedTab = true
                tab = ui.lastTab
            }
            .onChange(of: tab) { value in
                ui.lastTab = value
            }
    }

    @ViewBuilder
    private var content: some View {
        switch tab {
        case .home:
            NavigationStack {
                HomeScreen(
                    onSearch: { tab = .discover },
                    onShelf: { tab = .shelf },
                    onSources: { tab = .me }
                )
                .toolbar(.hidden, for: .navigationBar)
            }
        case .discover:
            NavigationStack {
                DiscoverScreen()
                    .toolbar(.hidden, for: .navigationBar)
            }
        case .shelf:
            NavigationStack {
                ShelfScreen()
                    .toolbar(.hidden, for: .navigationBar)
            }
        case .me:
            NavigationStack {
                MeScreen()
                    .toolbar(.hidden, for: .navigationBar)
            }
        }
    }
}
