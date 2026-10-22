import SwiftUI

@main
struct EnhanceComicApp: App {

    @StateObject private var store = LibraryStore()

    var body: some Scene {
        WindowGroup {
            LibraryView()
                .environmentObject(store)
        }
    }
}
