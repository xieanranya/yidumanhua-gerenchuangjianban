import SwiftUI

/// 一条在线阅读记录。同源同书只留最新一条，最多 60 条。
struct OnlineRecord: Codable, Identifiable, Equatable {
    let sourceKey: String
    let sourceName: String
    let comicId: String
    let title: String
    let cover: String
    let sourceTitle: String
    let epId: String
    let epTitle: String
    let epIndex: Int
    var at: Double

    var id: String { sourceKey + "|" + comicId }
}

/// 界面偏好：主题、上次停留的 Tab、在线阅读记录。
///
/// 单独存 UserDefaults，不去碰 LibraryStore 那份书库文件 —— 书库是用户数据，
/// 界面偏好丢了无所谓，混在一个文件里反而容易一起丢。
final class UiStore: ObservableObject {

    @Published var theme: ThemeMode {
        didSet { defaults.set(theme.rawValue, forKey: Keys.theme) }
    }

    @Published var lastTab: AppTab {
        didSet { defaults.set(lastTab.rawValue, forKey: Keys.tab) }
    }

    @Published private(set) var history: [OnlineRecord] {
        didSet { saveHistory() }
    }

    private let defaults = UserDefaults.standard

    private enum Keys {
        static let theme = "ui.theme"
        static let tab = "ui.tab"
        static let history = "ui.history"
    }

    init() {
        let d = UserDefaults.standard
        if let raw = d.string(forKey: Keys.theme), let mode = ThemeMode(rawValue: raw) {
            theme = mode
        } else {
            theme = .auto
        }
        if let raw = d.string(forKey: Keys.tab), let tab = AppTab(rawValue: raw) {
            lastTab = tab
        } else {
            lastTab = .home
        }
        if let data = d.data(forKey: Keys.history),
           let list = try? JSONDecoder().decode([OnlineRecord].self, from: data) {
            history = list
        } else {
            history = []
        }
    }

    /// 系统配色 + 用户选择 → 最终是不是深色。
    func isDark(_ system: ColorScheme) -> Bool {
        switch theme {
        case .auto: return system == .dark
        case .day: return false
        case .night: return true
        }
    }

    var colors: AppColors {
        // 主题明确时才给定；跟随系统由外面用 isDark 计算后覆盖。
        switch theme {
        case .auto: return .night
        case .day: return .day
        case .night: return .night
        }
    }

    func remember(
        sourceKey: String,
        sourceName: String,
        comicId: String,
        title: String,
        cover: String,
        sourceTitle: String,
        epId: String,
        epTitle: String,
        epIndex: Int
    ) {
        let rec = OnlineRecord(
            sourceKey: sourceKey,
            sourceName: sourceName,
            comicId: comicId,
            title: title,
            cover: cover,
            sourceTitle: sourceTitle,
            epId: epId,
            epTitle: epTitle,
            epIndex: epIndex,
            at: Date().timeIntervalSince1970
        )
        var list = history.filter { $0.id != rec.id }
        list.insert(rec, at: 0)
        history = Array(list.prefix(60))
    }

    /// 继续阅读一侧要显示「读到哪一话」，详情页据此高亮。
    func record(sourceKey: String, comicId: String) -> OnlineRecord? {
        history.first { $0.id == sourceKey + "|" + comicId }
    }

    func clearHistory() { history = [] }

    private func saveHistory() {
        guard let data = try? JSONEncoder().encode(history) else { return }
        defaults.set(data, forKey: Keys.history)
    }
}
