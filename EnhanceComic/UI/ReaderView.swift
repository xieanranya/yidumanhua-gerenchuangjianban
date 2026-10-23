import SwiftUI
import CoreGraphics

/// Reader with paged, vertical-scroll and horizontal-scroll modes.
///
/// Paged mode follows the finger: the current page slides with the drag while the
/// neighbouring page comes in from the side, and it snaps forward past a distance or
/// velocity threshold, otherwise it springs back.
struct ReaderView: View {

    let book: Book

    @EnvironmentObject private var store: LibraryStore
    @Environment(\.dismiss) private var dismiss
    @Environment(\.displayScale) private var displayScale

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
    @State private var bookmarks: [Int] = []

    @State private var zoom: CGFloat = 1
    @State private var pinch: CGFloat = 1
    @State private var pan: CGSize = .zero
    @State private var dragPan: CGSize = .zero
    @State private var dragX: CGFloat = 0
    @State private var turning = false
    @State private var viewSize: CGSize = .zero

    var body: some View {
        GeometryReader { geo in
            ZStack {
                Color.black.ignoresSafeArea()

                if failed {
                    Text("无法打开这本书")
                        .foregroundColor(.white)
                } else if refs.isEmpty {
                    ProgressView().tint(.white)
                } else {
                    readerBody(size: geo.size)
                }

                VStack(spacing: 0) {
                    if showUI && !failed { topBar }
                    Spacer(minLength: 0)
                    if showUI && !failed { bottomBar }
                }
            }
            .onAppear { viewSize = geo.size }
            .onChange(of: geo.size) { viewSize = $0 }
        }
        .statusBarHidden(!showUI)
        .onAppear(perform: open)
        .onAppear {
            DispatchQueue.main.asyncAfter(deadline: .now() + 3.8) {
                guard showUI, !showSettings, !showChapters else { return }
                withAnimation(.easeInOut(duration: 0.2)) { showUI = false }
            }
        }
        .onDisappear(perform: saveProgress)
        .onChange(of: store.prefs.splitWidePages) { _ in rebuildRefs() }
        .onChange(of: store.prefs.readingMode) { _ in
            dragX = 0
            pan = .zero
            dragPan = .zero
            zoom = 1
            pinch = 1
            if store.prefs.readingMode != .paged { scrollTarget = currentRef?.id }
        }
        .onChange(of: index) { value in
            guard !scanning else { return }
            sliderValue = Double(value)
        }
        .onChange(of: showUI) { visible in
            guard visible else { return }
            DispatchQueue.main.asyncAfter(deadline: .now() + 3.8) {
                guard showUI, !showSettings, !showChapters else { return }
                withAnimation(.easeInOut(duration: 0.2)) { showUI = false }
            }
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

    // MARK: - Paged

    private func paged(size: CGSize) -> some View {
        let span = spreadSpan(size: size)
        let rtl = store.prefs.rightToLeft
        // The page that a forward turn brings in sits on the side the finger drags from.
        let nextOffset: CGFloat = rtl ? -size.width : size.width
        let prevOffset: CGFloat = rtl ? size.width : -size.width

        return ZStack {
            if let session {
                if refs.indices.contains(index - span) {
                    unitView(start: index - span, span: span, session: session, size: size)
                        .offset(x: prevOffset)
                }
                if refs.indices.contains(index) {
                    unitView(start: index, span: span, session: session, size: size)
                }
                if refs.indices.contains(index + span) {
                    unitView(start: index + span, span: span, session: session, size: size)
                        .offset(x: nextOffset)
                }
            }
        }
        .offset(x: dragX)
        .scaleEffect(zoom * pinch, anchor: .center)
        .offset(x: pan.width + dragPan.width, y: pan.height + dragPan.height)
        .frame(width: size.width, height: size.height)
        .clipped()
        .contentShape(Rectangle())
        .gesture(
            ExclusiveGesture(
                SpatialTapGesture(count: 2).onEnded { value in
                    handleDoubleTap(at: value.location, size: size)
                },
                SpatialTapGesture(count: 1).onEnded { value in
                    handleSingleTap(at: value.location, size: size, span: span)
                }
            )
        )
        .simultaneousGesture(dragGesture(size: size, span: span))
        .simultaneousGesture(pinchGesture(size: size))
    }

    @ViewBuilder
    private func unitView(start: Int, span: Int, session: BookSession, size: CGSize) -> some View {
        if span == 2, refs.indices.contains(start), refs.indices.contains(start + 1) {
            let left = store.prefs.rightToLeft ? refs[start + 1] : refs[start]
            let right = store.prefs.rightToLeft ? refs[start] : refs[start + 1]
            HStack(spacing: 0) {
                pageCell(left, session: session, size: size, width: size.width / 2)
                pageCell(right, session: session, size: size, width: size.width / 2)
            }
            .frame(width: size.width, height: size.height)
        } else if refs.indices.contains(start) {
            pageCell(refs[start], session: session, size: size, width: size.width)
        }
    }

    private func pageCell(
        _ ref: PageRef,
        session: BookSession,
        size: CGSize,
        width: CGFloat
    ) -> some View {
        PageContent(
            session: session,
            ref: ref,
            settings: store.enhance,
            crop: store.prefs.autoCropWhiteBorders,
            maxWidth: width * displayScale * 1.1,
            rightToLeft: store.prefs.rightToLeft
        )
        .frame(width: width, height: size.height)
    }

    private func dragGesture(size: CGSize, span: Int) -> some Gesture {
        DragGesture(minimumDistance: 6)
            .onChanged { value in
                if zoom * pinch > 1.02 {
                    dragPan = value.translation
                } else if !turning {
                    dragX = value.translation.width
                }
            }
            .onEnded { value in
                if zoom * pinch > 1.02 {
                    let merged = CGSize(
                        width: pan.width + value.translation.width,
                        height: pan.height + value.translation.height
                    )
                    dragPan = .zero
                    withAnimation(.easeOut(duration: 0.2)) {
                        pan = clampedPan(merged, size: size)
                    }
                    return
                }
                endTurn(
                    dx: value.translation.width,
                    predicted: value.predictedEndTranslation.width,
                    span: span,
                    size: size
                )
            }
    }

    private func pinchGesture(size: CGSize) -> some Gesture {
        MagnificationGesture()
            .onChanged { value in pinch = value }
            .onEnded { value in
                let next = min(max(zoom * value, 1), 6)
                pinch = 1
                if next <= 1.001 {
                    withAnimation(.easeOut(duration: 0.18)) {
                        zoom = 1
                        pan = .zero
                    }
                } else {
                    zoom = next
                    withAnimation(.easeOut(duration: 0.18)) {
                        pan = clampedPan(pan, size: size)
                    }
                }
            }
    }

    private func endTurn(dx: CGFloat, predicted: CGFloat, span: Int, size: CGSize) {
        guard !turning else { return }
        guard abs(dx) > 0.5 else {
            withAnimation(.easeOut(duration: 0.16)) { dragX = 0 }
            return
        }
        let forward = store.prefs.rightToLeft ? dx > 0 : dx < 0
        let neighbour = index + (forward ? span : -span)
        let canTurn = refs.indices.contains(neighbour)
        let flickMatches = (predicted > 0) == (dx > 0)
        let commit = canTurn && (
            abs(dx) > size.width * 0.28 ||
                (flickMatches && abs(predicted) > size.width * 0.55)
        )
        let sign: CGFloat = dx >= 0 ? 1 : -1
        if commit {
            turning = true
            withAnimation(.easeOut(duration: 0.2)) { dragX = sign * size.width }
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.2) {
                commitTurn(to: neighbour)
            }
        } else {
            withAnimation(.easeOut(duration: 0.18)) { dragX = 0 }
        }
    }

    /// Turns without a preceding drag: edge taps and the bottom bar.
    private func step(forward: Bool, span: Int, size: CGSize) {
        guard !turning else { return }
        let neighbour = index + (forward ? span : -span)
        guard refs.indices.contains(neighbour) else { return }
        let rtl = store.prefs.rightToLeft
        let sign: CGFloat = forward ? (rtl ? 1 : -1) : (rtl ? -1 : 1)
        turning = true
        withAnimation(.easeOut(duration: 0.22)) { dragX = sign * size.width }
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.22) {
            commitTurn(to: neighbour)
        }
    }

    private func commitTurn(to target: Int) {
        var tx = Transaction()
        tx.disablesAnimations = true
        withTransaction(tx) {
            index = target
            dragX = 0
            sliderValue = Double(target)
            zoom = 1
            pinch = 1
            pan = .zero
            dragPan = .zero
        }
        turning = false
    }

    private func handleSingleTap(at point: CGPoint, size: CGSize, span: Int) {
        if zoom * pinch > 1.02 {
            withAnimation(.easeInOut(duration: 0.15)) { showUI.toggle() }
            return
        }
        if point.x < size.width * 0.25 {
            step(forward: false, span: span, size: size)
        } else if point.x > size.width * 0.75 {
            step(forward: true, span: span, size: size)
        } else {
            withAnimation(.easeInOut(duration: 0.15)) { showUI.toggle() }
        }
    }

    private func handleDoubleTap(at point: CGPoint, size: CGSize) {
        if zoom > 1.05 {
            withAnimation(.easeOut(duration: 0.22)) {
                zoom = 1
                pinch = 1
                pan = .zero
            }
            return
        }
        let target: CGFloat = 2.6
        let cx = size.width / 2
        let cy = size.height / 2
        let wx = (point.x - cx - pan.width) / zoom
        let wy = (point.y - cy - pan.height) / zoom
        let bounds = maxPan(size: size, zoom: target)
        let nx = min(max(point.x - cx - wx * target, -bounds.width), bounds.width)
        let ny = min(max(point.y - cy - wy * target, -bounds.height), bounds.height)
        withAnimation(.easeOut(duration: 0.22)) {
            zoom = target
            pinch = 1
            pan = CGSize(width: nx, height: ny)
        }
    }

    // MARK: - Scroll modes

    private func scrolling(size: CGSize) -> some View {
        let vertical = store.prefs.readingMode == .vertical
        let spacing: CGFloat = vertical ? 0 : 8
        return ScrollViewReader { proxy in
            ScrollView(vertical ? .vertical : .horizontal, showsIndicators: false) {
                if vertical {
                    LazyVStack(spacing: spacing) {
                        ForEach(Array(refs.enumerated()), id: \.element.id) { position, ref in
                            cell(ref: ref, position: position, size: size, vertical: true)
                        }
                    }
                } else {
                    LazyHStack(spacing: spacing) {
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
                SpatialTapGesture().onEnded { value in
                    if value.location.x < size.width * 0.22, index > 0 {
                        withAnimation(.easeOut(duration: 0.2)) {
                            proxy.scrollTo(refs[index - 1].id, anchor: vertical ? .top : .leading)
                        }
                    } else if value.location.x > size.width * 0.78, index + 1 < refs.count {
                        withAnimation(.easeOut(duration: 0.2)) {
                            proxy.scrollTo(refs[index + 1].id, anchor: vertical ? .top : .leading)
                        }
                    } else {
                        withAnimation(.easeInOut(duration: 0.15)) { showUI.toggle() }
                    }
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
                maxWidth: (vertical ? size.width : size.height * aspect) * displayScale * 1.1,
                rightToLeft: store.prefs.rightToLeft,
                fill: true
            )
            .frame(
                width: vertical ? size.width : size.height * aspect,
                height: vertical ? size.width / aspect : size.height
            )
            .clipped()
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

            Button {
                let page = currentSourcePage
                if bookmarks.contains(page) {
                    bookmarks.removeAll { $0 == page }
                } else {
                    bookmarks.append(page)
                }
            } label: {
                Image(systemName: isBookmarked ? "bookmark.fill" : "bookmark")
                    .font(.system(size: 15))
                    .foregroundColor(isBookmarked ? Color(red: 1, green: 0.76, blue: 0.29) : .white)
                    .frame(width: 38, height: 38)
            }

            if session?.hasChapters == true {
                Button { showChapters = true } label: {
                    Image(systemName: "list.bullet")
                        .font(.system(size: 15))
                        .foregroundColor(.white)
                        .frame(width: 38, height: 38)
                }
            }
        }
        .padding(.horizontal, 4)
        .padding(.top, 2)
        .padding(.bottom, 2)
        .background(Color(red: 0.06, green: 0.06, blue: 0.07).opacity(0.94).ignoresSafeArea(edges: .top))
    }

    private var isBookmarked: Bool { bookmarks.contains(currentSourcePage) }

    /// One row of actions under the slider, the way the classic comic readers lay it out.
    private var bottomBar: some View {
        VStack(spacing: 2) {
            Text("\(min(index + 1, max(refs.count, 1))) / \(max(refs.count, 1))")
                .font(.system(size: 11))
                .foregroundColor(.white.opacity(0.6))

            Slider(
                value: $sliderValue,
                in: 0...Double(max(refs.count - 1, 1)),
                step: 1
            ) { editing in
                if !editing { jump(to: Int(sliderValue.rounded())) }
            }
            .tint(.white)
            .padding(.horizontal, 8)

            HStack(spacing: 0) {
                barAction("上一页", "chevron.left") { turn(forward: false) }
                barAction("下一页", "chevron.right") { turn(forward: true) }
                barAction(modeLabel, modeIcon) {
                    withAnimation(.easeInOut(duration: 0.2)) {
                        store.prefs.readingMode = nextMode
                    }
                }
                barAction(isBookmarked ? "已书签" : "书签", isBookmarked ? "bookmark.fill" : "bookmark") {
                    let page = currentSourcePage
                    if bookmarks.contains(page) {
                        bookmarks.removeAll { $0 == page }
                    } else {
                        bookmarks.append(page)
                    }
                }
                barAction("设置", "slider.horizontal.3") { showSettings = true }
            }
            .padding(.horizontal, 4)
            .padding(.bottom, 2)
        }
        .padding(.top, 6)
        .padding(.bottom, 10)
        .background(Color(red: 0.06, green: 0.06, blue: 0.07).opacity(0.94).ignoresSafeArea(edges: .bottom))
    }

    private func barAction(_ label: String, _ icon: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            VStack(spacing: 3) {
                Image(systemName: icon)
                    .font(.system(size: 17))
                    .foregroundColor(.white)
                Text(label)
                    .font(.system(size: 9))
                    .foregroundColor(.white.opacity(0.62))
            }
            .frame(maxWidth: .infinity)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }

    private var modeLabel: String {
        switch store.prefs.readingMode {
        case .paged: return "翻页"
        case .vertical: return "竖向"
        case .horizontal: return "横向"
        }
    }

    private var modeIcon: String {
        switch store.prefs.readingMode {
        case .paged: return "book"
        case .vertical: return "rectangle.split.1x2"
        case .horizontal: return "rectangle.split.2x1"
        }
    }

    private var nextMode: ReadingMode {
        switch store.prefs.readingMode {
        case .paged: return .vertical
        case .vertical: return .horizontal
        case .horizontal: return .paged
        }
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

    // MARK: - Layout helpers

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

    /// Two when the screen is wide and both neighbours are ordinary pages.
    private func spreadSpan(size: CGSize) -> Int {
        guard store.prefs.doublePage, size.width > size.height else { return 1 }
        guard refs.indices.contains(index) else { return 1 }
        let a = refs[index]
        guard !a.isSplit, refs.indices.contains(index + 1) else { return 1 }
        let b = refs[index + 1]
        guard !b.isSplit, b.index != a.index else { return 1 }
        return 2
    }

    private func fittedSize(aspect: CGFloat, size: CGSize) -> CGSize {
        let viewAspect = size.width / max(size.height, 1)
        if aspect > viewAspect {
            return CGSize(width: size.width, height: size.width / aspect)
        }
        return CGSize(width: size.height * aspect, height: size.height)
    }

    private func maxPan(size: CGSize, zoom level: CGFloat) -> CGSize {
        guard let ref = currentRef else { return .zero }
        if spreadSpan(size: size) > 1 { return .zero }
        let fitted = fittedSize(aspect: displayAspect(ref), size: size)
        return CGSize(
            width: max(0, (fitted.width * level - size.width) / 2),
            height: max(0, (fitted.height * level - size.height) / 2)
        )
    }

    private func clampedPan(_ value: CGSize, size: CGSize) -> CGSize {
        let bounds = maxPan(size: size, zoom: zoom * pinch)
        return CGSize(
            width: min(max(value.width, -bounds.width), bounds.width),
            height: min(max(value.height, -bounds.height), bounds.height)
        )
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
        bookmarks = book.bookmarks

        // Show the first page straight away with the default page ratio. Measuring every
        // page first is what used to keep the reader on a spinner for a long archive.
        refs = buildPageRefs(aspects: opened.aspects, splitWidePages: store.prefs.splitWidePages)
        index = refIndexOfSource(refs, book.lastPage)
        sliderValue = Double(index)
        scanning = true

        Task { @MainActor in
            let aspects = await Task.detached(priority: .utility) {
                opened.scanAspects()
                return opened.aspects
            }.value
            let source = currentSourcePage
            refs = buildPageRefs(aspects: aspects, splitWidePages: store.prefs.splitWidePages)
            index = refIndexOfSource(refs, source)
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
        if store.prefs.readingMode == .paged {
            let size = viewSize == .zero
                ? CGSize(width: 390, height: 844)
                : viewSize
            step(forward: forward, span: spreadSpan(size: size), size: size)
        } else {
            jump(to: index + (forward ? 1 : -1))
        }
    }

    private func jump(to target: Int) {
        guard !refs.isEmpty else { return }
        let next = min(max(target, 0), refs.count - 1)
        guard next != index else { return }
        var tx = Transaction()
        tx.disablesAnimations = true
        withTransaction(tx) {
            index = next
            dragX = 0
        }
        sliderValue = Double(next)
        pan = .zero
        dragPan = .zero
        zoom = 1
        pinch = 1
        if store.prefs.readingMode != .paged {
            scrollTarget = refs[next].id
        }
    }

    private func jumpToSource(_ sourcePage: Int) {
        let target = refIndexOfSource(refs, sourcePage)
        var tx = Transaction()
        tx.disablesAnimations = true
        withTransaction(tx) {
            index = target
            dragX = 0
        }
        sliderValue = Double(target)
        pan = .zero
        dragPan = .zero
        zoom = 1
        pinch = 1
        if store.prefs.readingMode != .paged {
            scrollTarget = refs.indices.contains(target) ? refs[target].id : nil
        }
    }

    private func saveProgress() {
        guard session != nil else { return }
        var updated = book
        updated.lastPage = currentSourcePage
        updated.bookmarks = bookmarks
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
    /// Scroll modes must cover the whole cell, otherwise a mismatched aspect
    /// leaves a black band between pages.
    var fill = false

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
                    .aspectRatio(contentMode: fill ? .fill : .fit)
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
