import SwiftUI
import CoreGraphics

/// Reader with paged, vertical-scroll and horizontal-scroll modes.
/// Every page is decoded off the main thread and run through `ImageEnhancer`.
struct ReaderView: View {

    let book: Book

    @EnvironmentObject private var store: LibraryStore
    @Environment(\.dismiss) private var dismiss

    @State private var session: BookSession?
    @State private var refs: [PageRef] = []
    @State private var index = 0
    @State private var failed = false
    @State private var scanning = true
    @State private var scanProgress = 0.0

    @State private var showUI = true
    @State private var showSettings = false
    @State private var showChapters = false
    @State private var sliderValue = 0.0
    @State private var scrollTarget: Int?

    @State private var zoom: CGFloat = 1
    @State private var pinch: CGFloat = 1
    @State private var pan: CGSize = .zero
    @State private var dragTranslation: CGSize = .zero

    var body: some View {
        GeometryReader { geo in
            ZStack {
                Color.black.ignoresSafeArea()

                if failed {
                    Text("无法打开这本书")
                        .foregroundColor(.white)
                } else if scanning {
                    scanningView
                } else {
                    readerBody(size: geo.size)
                }

                VStack(spacing: 0) {
                    if showUI && !failed && !scanning { topBar }
                    Spacer(minLength: 0)
                    if showUI && !failed && !scanning { bottomBar }
                }
            }
        }
        .statusBarHidden(!showUI)
        .onAppear(perform: open)
        .onDisappear(perform: saveProgress)
        .onChange(of: store.prefs.splitWidePages) { _ in rebuildRefs() }
        .onChange(of: store.prefs.readingMode) { _ in
            pan = .zero
            zoom = 1
            if store.prefs.readingMode != .paged { scrollTarget = currentRef?.id }
        }
        .onChange(of: index) { value in
            guard !scanning else { return }
            sliderValue = Double(value)
        }
        .sheet(isPresented: $showSettings) {
            ReaderSettingsView(prefs: $store.prefs, enhance: $store.enhance)
        }
        .sheet(isPresented: $showChapters) {
            chapterSheet
        }
    }

    // MARK: - Bodies

    private var scanningView: some View {
        VStack(spacing: 14) {
            ProgressView().tint(.white)
            Text(scanProgress > 0.01
                ? "正在解析页面 \(Int(scanProgress * 100))%"
                : "正在打开…")
                .font(.system(size: 13))
                .foregroundColor(.white.opacity(0.85))
        }
    }

    @ViewBuilder
    private func readerBody(size: CGSize) -> some View {
        switch store.prefs.readingMode {
        case .paged:
            paged(size: size)
        case .vertical, .horizontal:
            scrolling(size: size)
        }
    }

    private func paged(size: CGSize) -> some View {
        ZStack {
            if let session, let ref = currentRef {
                PageContent(
                    session: session,
                    ref: ref,
                    settings: store.enhance,
                    crop: store.prefs.autoCropWhiteBorders,
                    maxWidth: size.width * 2,
                    rightToLeft: store.prefs.rightToLeft
                )
                .scaleEffect(zoom * pinch)
                .offset(x: pan.width + dragTranslation.width,
                        y: pan.height + dragTranslation.height)
            }
        }
        .frame(width: size.width, height: size.height)
        .contentShape(Rectangle())
        .gesture(
            DragGesture(minimumDistance: 12)
                .onChanged { value in
                    if zoom * pinch > 1.02 {
                        dragTranslation = value.translation
                    } else {
                        dragTranslation = CGSize(width: value.translation.width * 0.25, height: 0)
                    }
                }
                .onEnded { value in
                    let dx = value.translation.width
                    dragTranslation = .zero
                    if zoom * pinch > 1.02 {
                        pan = CGSize(
                            width: pan.width + dx,
                            height: pan.height + value.translation.height
                        )
                        return
                    }
                    guard abs(dx) > size.width * 0.16 else { return }
                    turn(forward: store.prefs.rightToLeft ? dx > 0 : dx < 0)
                }
        )
        .simultaneousGesture(
            MagnificationGesture()
                .onChanged { value in pinch = value }
                .onEnded { value in
                    zoom = min(max(zoom * value, 1), 8)
                    pinch = 1
                    if zoom <= 1.001 { pan = .zero }
                }
        )
        .simultaneousGesture(
            SpatialTapGesture().onEnded { value in
                guard zoom * pinch <= 1.02 else { return }
                let width = size.width
                if value.location.x < width * 0.3 {
                    turn(forward: store.prefs.rightToLeft)
                } else if value.location.x > width * 0.7 {
                    turn(forward: !store.prefs.rightToLeft)
                } else {
                    withAnimation(.easeInOut(duration: 0.15)) { showUI.toggle() }
                }
            }
        )
    }

    private func scrolling(size: CGSize) -> some View {
        let vertical = store.prefs.readingMode == .vertical
        return ScrollViewReader { proxy in
            ScrollView(vertical ? .vertical : .horizontal, showsIndicators: false) {
                if vertical {
                    LazyVStack(spacing: 8) {
                        ForEach(Array(refs.enumerated()), id: \.element.id) { position, ref in
                            cell(ref: ref, position: position, size: size, vertical: true)
                        }
                    }
                } else {
                    LazyHStack(spacing: 8) {
                        ForEach(Array(refs.enumerated()), id: \.element.id) { position, ref in
                            cell(ref: ref, position: position, size: size, vertical: false)
                        }
                    }
                }
            }
            .onChange(of: scrollTarget) { target in
                guard let target else { return }
                withAnimation(.easeInOut(duration: 0.2)) {
                    proxy.scrollTo(target, anchor: vertical ? .top : .leading)
                }
                scrollTarget = nil
            }
            .simultaneousGesture(
                SpatialTapGesture().onEnded { _ in
                    withAnimation(.easeInOut(duration: 0.15)) { showUI.toggle() }
                }
            )
        }
    }

    @ViewBuilder
    private func cell(ref: PageRef, position: Int, size: CGSize, vertical: Bool) -> some View {
        let aspect = displayAspect(ref)
        if let session {
            PageContent(
                session: session,
                ref: ref,
                settings: store.enhance,
                crop: store.prefs.autoCropWhiteBorders,
                maxWidth: vertical ? size.width * 1.4 : size.height * aspect * 1.4,
                rightToLeft: store.prefs.rightToLeft
            )
            .frame(
                width: vertical ? size.width : size.height * aspect,
                height: vertical ? size.width / aspect : size.height
            )
            .id(ref.id)
            .onAppear { if !scanning { index = position } }
        }
    }

    // MARK: - Chrome

    private var topBar: some View {
        HStack(spacing: 6) {
            Button {
                saveProgress()
                dismiss()
            } label: {
                Image(systemName: "chevron.left")
                    .font(.system(size: 16, weight: .semibold))
                    .foregroundColor(.white)
                    .frame(width: 38, height: 38)
            }

            VStack(alignment: .leading, spacing: 1) {
                Text(book.title)
                    .font(.system(size: 14, weight: .medium))
                    .foregroundColor(.white)
                    .lineLimit(1)
                Text(subtitle)
                    .font(.system(size: 11))
                    .foregroundColor(.white.opacity(0.65))
                    .lineLimit(1)
            }

            Spacer(minLength: 0)

            if session?.hasChapters == true {
                Button { showChapters = true } label: {
                    Image(systemName: "list.bullet")
                        .font(.system(size: 15))
                        .foregroundColor(.white)
                        .frame(width: 38, height: 38)
                }
            }
            Button { showSettings = true } label: {
                Image(systemName: "slider.horizontal.3")
                    .font(.system(size: 15))
                    .foregroundColor(.white)
                    .frame(width: 38, height: 38)
            }
        }
        .padding(.horizontal, 4)
        .padding(.top, 4)
        .padding(.bottom, 4)
        .background(Color.black.opacity(0.78).ignoresSafeArea(edges: .top))
    }

    private var bottomBar: some View {
        VStack(spacing: 2) {
            Slider(
                value: $sliderValue,
                in: 0...Double(max(refs.count - 1, 1)),
                step: 1
            ) { editing in
                if !editing { jump(to: Int(sliderValue.rounded())) }
            }
            .tint(.white)

            HStack {
                Button { turn(forward: false) } label: {
                    Image(systemName: "chevron.left.circle.fill")
                        .font(.system(size: 22))
                        .foregroundColor(.white)
                }
                Spacer()
                Text("\(index + 1) / \(max(refs.count, 1))")
                    .font(.system(size: 12))
                    .foregroundColor(.white.opacity(0.75))
                Spacer()
                Button { turn(forward: true) } label: {
                    Image(systemName: "chevron.right.circle.fill")
                        .font(.system(size: 22))
                        .foregroundColor(.white)
                }
            }
            .padding(.horizontal, 18)
        }
        .padding(.top, 8)
        .padding(.bottom, 20)
        .background(Color.black.opacity(0.78).ignoresSafeArea(edges: .bottom))
    }

    private var chapterSheet: some View {
        NavigationStack {
            List {
                ForEach(Array((session?.chapters ?? []).enumerated()), id: \.offset) { _, chapter in
                    let active = chapter.contains(currentSourcePage)
                    Button {
                        showChapters = false
                        jumpToSource(chapter.start)
                    } label: {
                        HStack {
                            Text(chapter.title.isEmpty ? "从第 \(chapter.start + 1) 页开始" : chapter.title)
                                .font(.system(size: 14, weight: active ? .semibold : .regular))
                                .foregroundColor(active ? CoverPalette.colors[0] : .primary)
                                .lineLimit(1)
                            Spacer()
                            Text("\(chapter.count) 页")
                                .font(.system(size: 12))
                                .foregroundColor(.secondary)
                        }
                    }
                }
            }
            .navigationTitle("目录")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("完成") { showChapters = false }
                }
            }
        }
    }

    // MARK: - State helpers

    private var currentRef: PageRef? {
        refs.indices.contains(index) ? refs[index] : refs.first
    }

    private var currentSourcePage: Int { currentRef?.index ?? 0 }

    private var subtitle: String {
        var parts: [String] = []
        if session?.hasChapters == true,
           let chapter = session?.chapter(at: currentSourcePage),
           !chapter.title.isEmpty {
            parts.append(chapter.title)
        }
        parts.append("第 \(currentSourcePage + 1) / \(max(sourceCount, 1)) 页")
        return parts.joined(separator: " · ")
    }

    private var sourceCount: Int { session?.pageCount ?? 0 }

    private func displayAspect(_ ref: PageRef) -> CGFloat {
        guard let session else { return 0.7 }
        let base = session.aspect(at: ref.index)
        let value = ref.isSplit ? base * 0.5 : base
        return min(max(value, 0.1), 10)
    }

    // MARK: - Actions

    private func open() {
        guard session == nil else { return }
        guard let opened = store.session(for: book) else {
            failed = true
            scanning = false
            return
        }
        session = opened
        Task { @MainActor in
            let aspects = await Task.detached(priority: .userInitiated) { () -> [CGFloat] in
                opened.scanAspects { done, total in
                    guard total > 0 else { return }
                    Task { @MainActor in
                        scanProgress = Double(done) / Double(total)
                    }
                }
                return opened.aspects
            }.value
            refs = buildPageRefs(aspects: aspects, splitWidePages: store.prefs.splitWidePages)
            index = refIndexOfSource(refs, book.lastPage)
            sliderValue = Double(index)
            scanProgress = 1
            scanning = false
        }
    }

    private func rebuildRefs() {
        guard let session else { return }
        let source = currentSourcePage
        refs = buildPageRefs(aspects: session.aspects, splitWidePages: store.prefs.splitWidePages)
        index = refIndexOfSource(refs, source)
        sliderValue = Double(index)
    }

    private func turn(forward: Bool) {
        jump(to: index + (forward ? 1 : -1))
    }

    private func jump(to target: Int) {
        guard !refs.isEmpty else { return }
        let next = min(max(target, 0), refs.count - 1)
        guard next != index else { return }
        index = next
        sliderValue = Double(next)
        pan = .zero
        zoom = 1
        if store.prefs.readingMode != .paged {
            scrollTarget = refs[next].id
        }
    }

    private func jumpToSource(_ sourcePage: Int) {
        let target = refIndexOfSource(refs, sourcePage)
        index = target
        sliderValue = Double(target)
        pan = .zero
        zoom = 1
        if store.prefs.readingMode != .paged {
            scrollTarget = refs.indices.contains(target) ? refs[target].id : nil
        }
    }

    private func saveProgress() {
        guard session != nil else { return }
        var updated = book
        updated.lastPage = currentSourcePage
        store.update(updated)
    }
}

/// One decoded + enhanced page. Reloads when the page or the settings change.
private struct PageContent: View {

    let session: BookSession
    let ref: PageRef
    let settings: EnhanceSettings
    let crop: Bool
    let maxWidth: CGFloat
    let rightToLeft: Bool

    @State private var image: CGImage?

    private var cacheKey: String {
        "\(ref.index)-\(ref.half)-\(settings.hashValue)-\(crop)-\(rightToLeft)-\(Int(maxWidth))"
    }

    var body: some View {
        ZStack {
            if let image {
                Image(decorative: image, scale: 1)
                    .resizable()
                    .interpolation(.high)
                    .aspectRatio(contentMode: .fit)
            } else {
                ProgressView().tint(.white.opacity(0.5))
            }
        }
        .task(id: cacheKey) {
            image = nil
            let settings = self.settings
            let crop = self.crop
            let rightToLeft = self.rightToLeft
            let maxWidth = self.maxWidth
            let ref = self.ref
            let session = self.session

            let result = await Task.detached(priority: .userInitiated) { () -> CGImage? in
                guard let raw = session.image(at: ref.index, maxWidth: maxWidth) else {
                    return nil
                }
                return ImageEnhancer.process(
                    raw,
                    settings: settings,
                    autoCrop: crop,
                    half: ref.half,
                    rightToLeft: rightToLeft
                )
            }.value
            image = result
        }
    }
}
