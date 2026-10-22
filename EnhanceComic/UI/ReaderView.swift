import SwiftUI

/// Minimal paged reader for the first milestone: fit-to-screen page, tap / swipe to turn.
/// The Metal enhancement pipeline plugs in here next.
struct ReaderView: View {

    let book: Book

    @EnvironmentObject private var store: LibraryStore
    @Environment(\.dismiss) private var dismiss

    @State private var session: BookSession?
    @State private var pageCount = 0
    @State private var index = 0
    @State private var image: CGImage?
    @State private var failed = false
    @State private var showUI = true
    @State private var drag: CGFloat = 0
    @State private var sliderValue: Double = 0

    var body: some View {
        GeometryReader { geo in
            ZStack {
                Color.black.ignoresSafeArea()

                if let image {
                    Image(decorative: image, scale: 1)
                        .resizable()
                        .interpolation(.high)
                        .aspectRatio(contentMode: .fit)
                        .frame(width: geo.size.width, height: geo.size.height)
                        .offset(x: drag)
                } else if failed {
                    Text("无法打开这本书")
                        .foregroundColor(.white)
                } else {
                    ProgressView().tint(.white)
                }

                if showUI {
                    chrome
                }
            }
            .contentShape(Rectangle())
            .simultaneousGesture(
                SpatialTapGesture().onEnded { value in
                    let width = geo.size.width
                    if value.location.x < width * 0.3 {
                        turn(forward: store.prefs.rightToLeft)
                    } else if value.location.x > width * 0.7 {
                        turn(forward: !store.prefs.rightToLeft)
                    } else {
                        withAnimation { showUI.toggle() }
                    }
                }
            )
            .gesture(
                DragGesture(minimumDistance: 30)
                    .onChanged { value in drag = value.translation.width * 0.4 }
                    .onEnded { value in
                        let dx = value.translation.width
                        drag = 0
                        guard abs(dx) > geo.size.width * 0.16 else { return }
                        turn(forward: store.prefs.rightToLeft ? dx > 0 : dx < 0)
                    }
            )
            .task { open(width: geo.size.width) }
            .onChange(of: index) { _ in load(width: geo.size.width) }
        }
        .statusBarHidden(!showUI)
        .onDisappear { saveProgress() }
    }

    private var chrome: some View {
        VStack(spacing: 0) {
            HStack(spacing: 8) {
                Button {
                    saveProgress()
                    dismiss()
                } label: {
                    Image(systemName: "chevron.left")
                        .font(.system(size: 17, weight: .semibold))
                        .foregroundColor(.white)
                        .frame(width: 40, height: 40)
                }
                VStack(alignment: .leading, spacing: 1) {
                    Text(book.title)
                        .font(.system(size: 14, weight: .medium))
                        .foregroundColor(.white)
                        .lineLimit(1)
                    Text("第 \(index + 1) / \(max(pageCount, 1)) 页")
                        .font(.system(size: 11))
                        .foregroundColor(.white.opacity(0.65))
                }
                Spacer(minLength: 0)
            }
            .padding(.horizontal, 8)
            .padding(.vertical, 4)
            .background(Color.black.opacity(0.72))

            Spacer()

            VStack(spacing: 4) {
                Slider(
                    value: $sliderValue,
                    in: 0...Double(max(pageCount - 1, 1)),
                    step: 1
                ) { editing in
                    if !editing {
                        let target = Int(sliderValue.rounded())
                        index = min(max(target, 0), max(pageCount - 1, 0))
                    }
                }
                .tint(.white)

                HStack {
                    Button { turn(forward: false) } label: {
                        Image(systemName: "chevron.left.circle.fill")
                            .font(.system(size: 22))
                            .foregroundColor(.white)
                    }
                    Spacer()
                    Text("\(index + 1) / \(max(pageCount, 1))")
                        .font(.system(size: 12))
                        .foregroundColor(.white.opacity(0.75))
                    Spacer()
                    Button { turn(forward: true) } label: {
                        Image(systemName: "chevron.right.circle.fill")
                            .font(.system(size: 22))
                            .foregroundColor(.white)
                    }
                }
            }
            .padding(.horizontal, 18)
            .padding(.top, 10)
            .padding(.bottom, 22)
            .background(Color.black.opacity(0.72))
        }
    }

    private func open(width: CGFloat) {
        guard session == nil else { return }
        guard let opened = store.session(for: book) else {
            failed = true
            return
        }
        session = opened
        pageCount = opened.pageCount
        index = min(max(0, book.lastPage), max(0, pageCount - 1))
        sliderValue = Double(index)
        load(width: width)
    }

    private func load(width: CGFloat) {
        guard let session else { return }
        image = session.image(at: index, maxWidth: width * 2)
    }

    private func turn(forward: Bool) {
        guard pageCount > 0 else { return }
        let next = index + (forward ? 1 : -1)
        guard next >= 0, next < pageCount else { return }
        index = next
        sliderValue = Double(next)
    }

    private func saveProgress() {
        guard session != nil else { return }
        var updated = book
        updated.lastPage = index
        store.update(updated)
    }
}
