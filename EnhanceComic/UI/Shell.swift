import SwiftUI

// ==================================================================================== 四 Tab

enum AppTab: String, CaseIterable, Identifiable {
    // 用户要求的顺序：首页 / 书架 / 发现 / 我的
    case home
    case shelf
    case discover
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
                                tint: on ? c.accent : c.fg3,
                                size: 21,
                                strokeWidth: on ? 1.9 : 1.5
                            )
                            Text(t.label)
                                .font(.system(size: 9.5, weight: on ? .semibold : .regular))
                                .foregroundColor(on ? c.accent : c.fg3)
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
        // v3：毛玻璃底栏，背景延伸到 home indicator 区，不然下面会断一截实色
        .background(
            Rectangle()
                .fill(.ultraThinMaterial)
                .overlay(c.bg.opacity(c.dark ? 0.62 : 0.55))
                .ignoresSafeArea(edges: .bottom)
        )
    }
}

/// 整个 App 的壳：主题注入 + 四个 Tab + 底部栏。
struct AppRootView: View {
    @EnvironmentObject private var ui: UiStore
    @Environment(\.colorScheme) private var systemScheme
    @Environment(\.scenePhase) private var scenePhase

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
            // 全局唯一的状态栏控制器：沉浸头图页可见时白字压图
            .background(StatusBarLightController(light: ui.statusBarLight))
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
            // 禁漫自动签到：启动 + 每次回前台都检查一次；
            // 里面有三道闸（开关 / 登录态 / 当日去重），空跑只是三次 UserDefaults 读
            .task { await SourceHub.shared.maybeAutoCheckin() }
            // 帧率自动调节：启动 + 每次回前台各调一次（低电量模式切回时恢复高刷）
            .onAppear { FrameRate.apply() }
            .onReceive(NotificationCenter.default.publisher(
                for: .NSProcessInfoPowerStateDidChange
            )) { _ in
                FrameRate.apply()
            }
            .onChange(of: scenePhase) { phase in
                guard phase == .active else { return }
                FrameRate.apply()
                Task { await SourceHub.shared.maybeAutoCheckin() }
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
