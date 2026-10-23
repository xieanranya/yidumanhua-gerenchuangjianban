import UIKit

/// Lets the reader pin the interface to portrait or landscape from its ⋮ menu.
///
/// iOS only honours `requestGeometryUpdate` when the window's scene agrees with the mask,
/// so the mask is also returned from the app delegate, and reset when the reader closes.
enum OrientationLock {
    private(set) static var mask: UIInterfaceOrientationMask = .all

    static func apply(_ newMask: UIInterfaceOrientationMask) {
        mask = newMask
        guard let scene = UIApplication.shared.connectedScenes.first as? UIWindowScene else {
            return
        }
        let preferences = UIWindowScene.GeometryPreferences.iOS(interfaceOrientations: newMask)
        scene.requestGeometryUpdate(preferences) { _ in }
    }

    static func reset() {
        mask = .all
    }
}

final class AppDelegate: NSObject, UIApplicationDelegate {
    func application(
        _ application: UIApplication,
        supportedInterfaceOrientationsFor window: UIWindow?
    ) -> UIInterfaceOrientationMask {
        OrientationLock.mask
    }
}
