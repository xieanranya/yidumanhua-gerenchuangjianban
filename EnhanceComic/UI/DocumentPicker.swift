import SwiftUI
import UIKit
import UniformTypeIdentifiers

/// 要选文件还是选文件夹。
enum PickerKind: String, Identifiable {
    case files
    case folder

    var id: String { rawValue }
}

/// 用 UIKit 的 `UIDocumentPickerViewController` 代替 SwiftUI 的 `.fileImporter`。
///
/// 换掉的原因有三个，都是「点了打开却什么都没发生」的真凶：
///
/// 1. **`asCopy: true`**：系统把选中的文件复制到 App 的临时目录**再**交给我们。
///    走这条路不需要 security-scoped 访问，iCloud 里的文件也会被系统先下载完再给副本。
///    之前用 `.fileImporter` 拿到的是云盘占位符 URL，`FileManager.copyItem` 直接抛错，
///    而错误只进了计数器，界面上什么都没显示。
/// 2. **`.fileImporter` 在多选 + 文件夹混在一起时行为不稳定**，放宽到 `.item` 之后
///    时而能选时而只能选文件夹，这个问题用原生 picker 彻底绕开。
/// 3. 取消、失败都能明确回调，不再出现「静默 return」。
struct DocumentPicker: UIViewControllerRepresentable {
    let kind: PickerKind
    let onPick: ([URL]) -> Void
    let onCancel: () -> Void

    func makeCoordinator() -> Coordinator {
        Coordinator(onPick: onPick, onCancel: onCancel)
    }

    func makeUIViewController(context: Context) -> UIDocumentPickerViewController {
        // 文件走副本模式；文件夹不能复制整棵树，只能拿 security-scoped 的原始 URL。
        let picker: UIDocumentPickerViewController
        switch kind {
        case .files:
            picker = UIDocumentPickerViewController(
                forOpeningContentTypes: [.item],
                asCopy: true
            )
            picker.allowsMultipleSelection = true
        case .folder:
            picker = UIDocumentPickerViewController(
                forOpeningContentTypes: [.folder],
                asCopy: false
            )
            picker.allowsMultipleSelection = false
        }
        picker.delegate = context.coordinator
        picker.shouldShowFileExtensions = true
        picker.allowsMultipleSelection = kind == .files
        return picker
    }

    func updateUIViewController(_ controller: UIDocumentPickerViewController, context: Context) {}

    final class Coordinator: NSObject, UIDocumentPickerDelegate {
        private let onPick: ([URL]) -> Void
        private let onCancel: () -> Void

        init(onPick: @escaping ([URL]) -> Void, onCancel: @escaping () -> Void) {
            self.onPick = onPick
            self.onCancel = onCancel
        }

        func documentPicker(
            _ controller: UIDocumentPickerViewController,
            didPickDocumentsAt urls: [URL]
        ) {
            onPick(urls)
        }

        func documentPickerWasCancelled(_ controller: UIDocumentPickerViewController) {
            onCancel()
        }
    }
}
