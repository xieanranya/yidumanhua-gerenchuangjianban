import SwiftUI
import CoreGraphics
import UIKit

/// Serial, low-priority queue that warms the pages just ahead of the finger.
///
/// Serial on purpose: a burst of parallel decodes saturates the CPU, and the page the user is
/// actually looking at then has to compete with pages they may never reach.
private let pagePrefetchQueue = DispatchQueue(
    label: "com.enhancecomic.page.prefetch",
    qos: .utility
)

/// Reader with paged, vertical-scroll and horizontal-scroll modes.
///
/// The layout follows the reference reader: a title row with an overflow menu, a strip of
/// thin line icons that each open a panel in place, and a bottom scrubber. All three tap
/// zones are configurable from ⋮ → 页面按钮设置.
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

    @State private var showChrome = true
    @State private var panel: ReaderPanel = .none
    @State private var showMenu = false
    @State private var showChapters = false
    @State private var showBookmarkList = false
    @State private var showAddBookmark = false
    @State private var showOrientation = false
    @State private var showHardwareKeys = false
    @State private var showTapZones = false
    @State private var addName = ""
    @State private var orientationMask: UIInterfaceOrientationMask?
    @State private var toast: String?

    @State private var bookmarks: [Int] = []
    @State private var bookmarkNames: [Int: String] = [:]
    @State private var sliderValue = 0.0

    @State private var zoom: CGFloat = 1
    @State private var pinch: CGFloat = 1
    @State private var pan: CGSize = .zero
    @State private var dragPan: CGSize = .zero
    @State private var dragX: CGFloat = 0
    @State private var turning = false
    @State private var viewSize: CGSize = .zero
    @State private var scrollTarget: Int?

    @State private var progressTask: Task<Void, Never>?
    @State private var chromeTask: Task<Void, Never>?
    @State private var systemBrightness: CGFloat = 0.5
    @State private var lastVisibleReport = Date.distantPast
    @State private var pendingVisible: Int?
    @State private var visibleFlush: Task<Void, Never>?

    private var chromeVisible: Bool { showChrome && !failed }
    private var skin: Skin { skinFor(store.prefs.chromeLight) }

    var body: some View {
        GeometryReader { geo in
            ZStack {
                backgroundColor.ignoresSafeArea()

                if failed {
                    failureBody
                } else if refs.isEmpty {
                    ProgressView().tint(.white)
                } else {
                    pageStack(size: geo.size)
                }

                // 蓝光过滤：一层暖色 multiply 覆盖在页面之上、工具栏之下。用叠加而不是改
                // 像素，所以拖动强度滑杆时画面立刻变，不用重新解码任何一页。
                if store.prefs.blueLight > 0 {
                    BlueLightLayer(strength: store.prefs.blueLight)
                }

                VStack(spacing: 0) {
                    ReaderTopChrome(
                        title: book.title,
                        pageLabel: pageLabel,
                        prefs: $store.prefs,
                        enhance: $store.enhance,
                        panel: $panel,
                        marked: isBookmarked,
                        hasChapters: session?.hasChapters == true,
                        onBack: dismissReader,
                        onMenu: { showMenu = true },
                        onChapters: { showChapters = true },
                        onAddBookmark: {
                            addName = ""
                            showAddBookmark = true
                        },
                        onShowBookmarks: { showBookmarkList = true }
                    )
                    .opacity(chromeVisible ? 1 : 0)
                    .allowsHitTesting(chromeVisible)

                    Spacer(minLength: 0)

                    ReaderBottomChrome(
                        pageLabel: pageLabel,
                        sliderValue: $sliderValue,
                        total: refs.count,
                        light: store.prefs.chromeLight,
                        onSeek: { jump(to: $0) },
                        onPrev: { turn(forward: false) },
                        onNext: { turn(forward: true) }
                    )
                    .opacity(chromeVisible ? 1 : 0)
                    .allowsHitTesting(chromeVisible)
                }
                .animation(.easeInOut(duration: 0.18), value: chromeVisible)

                if showMenu {
                    ReaderMenuPanel(entries: menuEntries, light: store.prefs.chromeLight) {
                        showMenu = false
                        scheduleChromeHide()
                    }
                }

                dialogs

                if let toast {
                    VStack {
                        Spacer(minLength: 0)
                        Text(toast)
                            .font(.system(size: 12))
                            .foregroundColor(.white)
                            .padding(.horizontal, 14)
                            .padding(.vertical, 8)
                            .background(Color.black.opacity(0.82))
                            .clipShape(Capsule())
                            .padding(.bottom, 90)
                    }
                    .allowsHitTesting(false)
                    .transition(.opacity)
                }
            }
            .onAppear { viewSize = geo.size }
            .onChange(of: geo.size) { viewSize = $0 }
        }
        .statusBarHidden(!showChrome)
        .onAppear(perform: appeared)
        .onDisappear(perform: leave)
        .onChange(of: store.prefs.splitWidePages) { _ in rebuildRefs() }
        .onChange(of: store.prefs.autoCropWhiteBorders) { _ in
            rescanCrop(store.prefs.autoCropWhiteBorders)
        }
        .onChange(of: store.prefs.readingMode) { _ in
            resetZoom()
            if store.prefs.readingMode != .paged { scrollTarget = currentRef?.id }
        }
        .onChange(of: index) { value in
            sliderValue = Double(value)
            scheduleProgressSave()
            prefetchAhead(from: value)
        }
        .onChange(of: showChrome) { visible in
            if visible { scheduleChromeHide() }
        }
        .onChange(of: panel) { _ in scheduleChromeHide() }
        .onChange(of: showMenu) { open in
            if !open { scheduleChromeHide() }
        }
        .onChange(of: bookmarks) { _ in persistBookmarks() }
        .onChange(of: bookmarkNames) { _ in persistBookmarks() }
        .onChange(of: store.prefs.keepScreenOn) { _ in applyIdleTimer() }
        .onChange(of: store.prefs.autoBrightness) { _ in applyBrightness() }
        .onChange(of: store.prefs.brightness) { _ in applyBrightness() }
    }

    // MARK: - Bodies

    @ViewBuilder
    private func pageStack(size: CGSize) -> some View {
        Group {
            switch store.prefs.readingMode {
            case .paged:
                paged(size: size)
            case .vertical, .horizontal:
                scrolling(size: size)
            }
        }
        .modifier(GrayscaleFilter(active: store.prefs.grayscale))
        .modifier(InvertFilter(active: store.prefs.invert))
    }

    private var failureBody: some View {
        VStack(spacing: 8) {
            Text("这本书打不开")
                .font(.system(size: 14))
                .foregroundColor(skin.fg)
            Text("文件可能已损坏，或压缩包里没有可识别的图片。")
                .font(.system(size: 11))
                .foregroundColor(skin.dim)
            Button { dismissReader() } label: {
                Text("返回书架")
                    .font(.system(size: 13))
                    .foregroundColor(skin.accent)
                    .padding(.top, 10)
            }
            .buttonStyle(.plain)
        }
    }

    private var backgroundColor: Color {
        switch store.prefs.background {
        case 1: return Color(red: 0.086, green: 0.086, blue: 0.102)
        case 2: return Color(red: 0.929, green: 0.929, blue: 0.902)
        default: return .black
        }
    }

    // MARK: - Paged

    private func paged(size: CGSize) -> some View {
        let span = spreadSpan(size: size)
        let rtl = store.prefs.rightToLeft
        // The page a forward turn brings in sits on the side the finger drags from.
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
                    handleSingleTap(at: value.location, size: size)
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
                pageCell(left, session: session, viewport: size, cellWidth: size.width / 2)
                pageCell(right, session: session, viewport: size, cellWidth: size.width / 2)
            }
            .frame(width: size.width, height: size.height)
            .clipped()
        } else if refs.indices.contains(start) {
            pageCell(refs[start], session: session, viewport: size, cellWidth: size.width)
        }
    }

    private func pageCell(
        _ ref: PageRef,
        session: BookSession,
        viewport: CGSize,
        cellWidth: CGFloat
    ) -> some View {
        let target = pageTarget(ref: ref, viewport: viewport, cellWidth: cellWidth)
        return PageContent(
            session: session,
            ref: ref,
            settings: store.enhance,
            crop: store.prefs.autoCropWhiteBorders,
            maxWidth: max(target.width, cellWidth) * displayScale,
            rightToLeft: store.prefs.rightToLeft,
            fill: true
        )
        .frame(width: target.width, height: target.height)
        .frame(width: cellWidth, height: viewport.height)
        .clipped()
    }

    private func dragGesture(size: CGSize, span: Int) -> some Gesture {
        DragGesture(minimumDistance: 6)
            .onChanged { value in
                if panIntent(translation: value.translation, size: size) {
                    dragPan = value.translation
                } else if !turning {
                    dragX = value.translation.width
                }
            }
            .onEnded { value in
                if panIntent(translation: value.translation, size: size) {
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

    /// A drag pans only along an axis that actually overflows; otherwise it turns the page.
    private func panIntent(translation: CGSize, size: CGSize) -> Bool {
        if zoom * pinch > 1.02 { return true }
        let bounds = maxPan(size: size, zoom: 1)
        if abs(translation.height) > abs(translation.width) {
            return bounds.height > 0.5
        }
        return bounds.width > 0.5
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

    /// Turns without a preceding drag: taps and the bottom bar.
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

    private func handleSingleTap(at point: CGPoint, size: CGSize) {
        if zoom * pinch > 1.02 {
            toggleChrome()
            return
        }
        let zone: Int
        if point.x < size.width * 0.28 {
            zone = 0
        } else if point.x > size.width * 0.72 {
            zone = 2
        } else {
            zone = 1
        }
        runTap(zone: zone)
    }

    private func handleDoubleTap(at point: CGPoint, size: CGSize) {
        guard store.prefs.doubleTapZoom else { return }
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
        let gap = CGFloat(store.prefs.pageGapPercent) / 100 * size.height
        return ScrollViewReader { proxy in
            ScrollView(vertical ? .vertical : .horizontal, showsIndicators: false) {
                if vertical {
                    LazyVStack(spacing: gap) {
                        // Indexed ForEach: `Array(refs.enumerated())` allocated a fresh array
                        // of tuples on every body pass, i.e. on every scroll frame.
                        ForEach(refs.indices, id: \.self) { position in
                            cell(ref: refs[position], position: position, size: size, vertical: true)
                        }
                    }
                } else {
                    LazyHStack(spacing: gap) {
                        ForEach(refs.indices, id: \.self) { position in
                            cell(ref: refs[position], position: position, size: size, vertical: false)
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
                    let zone: Int
                    if value.location.x < size.width * 0.28 {
                        zone = 0
                    } else if value.location.x > size.width * 0.72 {
                        zone = 2
                    } else {
                        zone = 1
                    }
                    runTap(zone: zone)
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
                maxWidth: (vertical ? size.width : size.height * aspect) * displayScale,
                rightToLeft: store.prefs.rightToLeft,
                fill: true
            )
            .frame(
                width: vertical ? size.width : size.height * aspect,
                height: vertical ? size.width / aspect : size.height
            )
            .clipped()
            .id(ref.id)
            .onAppear { if !scanning { reportVisible(position) } }
        }
    }

    // MARK: - Chrome actions

    private func toggleChrome() {
        withAnimation(.easeInOut(duration: 0.18)) {
            showChrome.toggle()
            if !showChrome { panel = .none }
        }
    }

    private func scheduleChromeHide() {
        chromeTask?.cancel()
        guard showChrome, panel == .none, !showMenu, !failed else { return }
        chromeTask = Task { @MainActor in
            try? await Task.sleep(nanoseconds: 4_000_000_000)
            guard !Task.isCancelled else { return }
            withAnimation(.easeInOut(duration: 0.2)) { showChrome = false }
        }
    }

    private func runTap(zone: Int) {
        let action: TapAction
        switch zone {
        case 0: action = store.prefs.tapLeftAction
        case 2: action = store.prefs.tapRightAction
        default: action = store.prefs.tapCenterAction
        }
        switch action {
        case .prevPage:
            turn(forward: false)
        case .nextPage:
            turn(forward: true)
        case .prevChapter:
            jumpChapter(-1)
        case .nextChapter:
            jumpChapter(1)
        case .toggleChrome:
            toggleChrome()
        case .openMenu:
            withAnimation(.easeInOut(duration: 0.18)) { showChrome = true }
            showMenu = true
        case .disabled:
            break
        }
    }

    private var menuEntries: [MenuEntry] {
        var entries: [MenuEntry] = []
        entries.append(MenuEntry(label: "屏幕方向") { showOrientation = true })
        entries.append(MenuEntry(label: "手机硬件按钮") { showHardwareKeys = true })
        entries.append(MenuEntry(label: "页面按钮设置") { showTapZones = true })
        entries.append(
            MenuEntry(label: "双击缩放", checked: store.prefs.doubleTapZoom) {
                store.prefs.doubleTapZoom.toggle()
            }
        )
        entries.append(
            MenuEntry(label: "滤镜") {
                withAnimation(.easeInOut(duration: 0.18)) {
                    showChrome = true
                    panel = .filter
                }
            }
        )
        entries.append(
            MenuEntry(label: "添加书签") {
                addName = ""
                showAddBookmark = true
            }
        )
        entries.append(MenuEntry(label: "书签列表") { showBookmarkList = true })
        if session?.hasChapters == true {
            entries.append(MenuEntry(label: "目录") { showChapters = true })
        }
        entries.append(MenuEntry(label: "设置漫画封面") { setCoverFromPage() })
        return entries
    }

    // MARK: - Dialogs

    @ViewBuilder
    private var dialogs: some View {
        if showOrientation {
            SkinDialog(title: "屏幕方向", skin: skin, onDismiss: { showOrientation = false }) {
                orientationRow("跟随系统", nil)
                orientationRow("竖屏", .portrait)
                orientationRow("横屏", .landscape)
            }
        }

        if showHardwareKeys {
            SkinDialog(title: "手机硬件按钮", skin: skin, onDismiss: { showHardwareKeys = false }) {
                Text("iOS 没有把音量键开放给第三方 App，所以这里没有音量键翻页。可以改用：")
                    .font(.system(size: 10.5))
                    .foregroundColor(skin.dim)
                    .fixedSize(horizontal: false, vertical: true)
                Color.clear.frame(height: 6)
                Text("· 页面按钮设置：把左 / 右区域设成上一页、下一页\n· 双击屏幕放大，再拖动查看细节\n· 底部滑杆直接跳页")
                    .font(.system(size: 10.5))
                    .foregroundColor(skin.dim)
                    .fixedSize(horizontal: false, vertical: true)
                Color.clear.frame(height: 8)
                SkinKeyRow(
                    label: "双击缩放",
                    checked: store.prefs.doubleTapZoom,
                    skin: skin
                ) { store.prefs.doubleTapZoom = $0 }
                SkinKeyRow(
                    label: "阅读时常亮",
                    checked: store.prefs.keepScreenOn,
                    skin: skin
                ) { store.prefs.keepScreenOn = $0 }
            }
        }

        if showTapZones {
            SkinDialog(title: "页面按钮设置", skin: skin, onDismiss: { showTapZones = false }) {
                TapZoneTable(prefs: $store.prefs, skin: skin)
            }
        }

        if showAddBookmark {
            SkinDialog(title: "添加书签", skin: skin, onDismiss: { showAddBookmark = false }) {
                Text("第 \(currentSourcePage + 1) 页 / 共 \(max(session?.pageCount ?? 0, 1)) 页")
                    .font(.system(size: 10.5))
                    .foregroundColor(skin.dim)
                Color.clear.frame(height: 8)
                SkinTextField(value: $addName, skin: skin, placeholder: "书签名称（可留空）")
                Color.clear.frame(height: 10)
                HStack(spacing: 8) {
                    Spacer(minLength: 0)
                    SkinTextButton(label: "取消", skin: skin) { showAddBookmark = false }
                    SkinTextButton(label: "保存", skin: skin) { saveBookmark() }
                }
            }
        }

        if showBookmarkList {
            SkinDialog(title: "书签列表", skin: skin, onDismiss: { showBookmarkList = false }) {
                if bookmarks.isEmpty {
                    Text("还没有书签。")
                        .font(.system(size: 12))
                        .foregroundColor(skin.dim)
                } else {
                    ScrollView {
                        VStack(spacing: 0) {
                            ForEach(bookmarks.sorted(), id: \.self) { page in
                                bookmarkRow(page)
                            }
                        }
                    }
                    .frame(maxHeight: 320)
                }
            }
        }

        if showChapters {
            SkinDialog(title: "目录", skin: skin, onDismiss: { showChapters = false }) {
                let chapters = session?.chapters ?? []
                if chapters.count <= 1 {
                    Text("这个压缩包里没有分文件夹，所以没有章节。")
                        .font(.system(size: 11))
                        .foregroundColor(skin.dim)
                } else {
                    ScrollView {
                        VStack(spacing: 0) {
                            ForEach(chapters) { chapter in
                                chapterRow(chapter)
                            }
                        }
                    }
                    .frame(maxHeight: 380)
                }
            }
        }
    }

    private func orientationRow(_ label: String, _ mask: UIInterfaceOrientationMask?) -> some View {
        SkinRadioRow(label: label, selected: orientationMask == mask, skin: skin) {
            orientationMask = mask
            OrientationLock.apply(mask ?? .all)
            showOrientation = false
        }
    }

    private func bookmarkRow(_ page: Int) -> some View {
        HStack(spacing: 8) {
            Button {
                showBookmarkList = false
                jumpToSource(page)
            } label: {
                VStack(alignment: .leading, spacing: 1) {
                    Text(bookmarkNames[page] ?? "第 \(page + 1) 页")
                        .font(.system(size: 12.5))
                        .foregroundColor(skin.fg)
                        .lineLimit(1)
                    Text("\(page + 1) / \(max(session?.pageCount ?? 0, 1))")
                        .font(.system(size: 10))
                        .foregroundColor(skin.dim)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)

            Button {
                bookmarks.removeAll { $0 == page }
                bookmarkNames.removeValue(forKey: page)
            } label: {
                Text("删除")
                    .font(.system(size: 10.5))
                    .foregroundColor(skin.dim)
                    .frame(width: 36, height: 30)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
        }
        .padding(.vertical, 6)
    }

    private func chapterRow(_ chapter: Chapter) -> some View {
        let active = chapter.contains(currentSourcePage)
        return Button {
            showChapters = false
            jumpToSource(chapter.start)
        } label: {
            HStack(spacing: 8) {
                Text(chapter.title.isEmpty ? "第 \(chapter.start + 1) 页起" : chapter.title)
                    .font(.system(size: 12.5, weight: active ? .medium : .regular))
                    .foregroundColor(active ? skin.accent : skin.fg)
                    .lineLimit(1)
                Spacer(minLength: 0)
                Text("\(chapter.count) 页")
                    .font(.system(size: 10.5))
                    .foregroundColor(skin.dim)
            }
            .padding(.vertical, 10)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }

    // MARK: - Layout helpers

    private var currentRef: PageRef? {
        refs.indices.contains(index) ? refs[index] : refs.first
    }

    private var currentSourcePage: Int { currentRef?.index ?? 0 }

    private var isBookmarked: Bool { bookmarks.contains(currentSourcePage) }

    private var pageLabel: String {
        var text = ""
        if session?.hasChapters == true,
           let chapter = session?.chapter(at: currentSourcePage),
           !chapter.title.isEmpty {
            text += chapter.title + "  ·  "
        }
        text += "\(currentSourcePage + 1) / \(max(session?.pageCount ?? 0, 1))"
        return text
    }

    private func displayAspect(_ ref: PageRef) -> CGFloat {
        // Measured once when the refs were built. Asking the session here meant a lock and
        // possibly an inflate inside a layout pass.
        min(max(ref.aspect, 0.1), 10)
    }

    /// Two pages when 双页 is on and the screen is wide (or 强制并排 is on).
    private func spreadSpan(size: CGSize) -> Int {
        let wantsSpread = store.prefs.doublePage
            && (size.width > size.height || store.prefs.forceSpread)
        guard wantsSpread else { return 1 }
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

    private func pageTarget(ref: PageRef, viewport: CGSize, cellWidth: CGFloat) -> CGSize {
        let aspect = displayAspect(ref)
        let cell = CGSize(width: max(cellWidth, 1), height: max(viewport.height, 1))
        switch store.prefs.scaleMode {
        case .fit:
            return fittedSize(aspect: aspect, size: cell)
        case .width:
            return CGSize(width: cell.width, height: cell.width / aspect)
        case .height:
            return CGSize(width: cell.height * aspect, height: cell.height)
        case .original:
            let native = nativeWidth(ref: ref)
            guard native > 1 else { return fittedSize(aspect: aspect, size: cell) }
            return CGSize(width: native, height: native / aspect)
        }
    }

    /// 1 source pixel == 1 physical pixel, so "original" does not depend on screen density.
    private func nativeWidth(ref: PageRef) -> CGFloat {
        guard let session else { return 0 }
        let pixels = session.pixelWidth(at: ref.index)
        guard pixels > 0 else { return 0 }
        let points = CGFloat(pixels) / max(displayScale, 1)
        return ref.isSplit ? points * 0.5 : points
    }

    private func maxPan(size: CGSize, zoom level: CGFloat) -> CGSize {
        guard let ref = currentRef else { return .zero }
        let span = spreadSpan(size: size)
        let cellWidth = span == 2 ? size.width / 2 : size.width
        let target = pageTarget(ref: ref, viewport: size, cellWidth: cellWidth)
        let contentWidth = span == 2 ? target.width * 2 : target.width
        return CGSize(
            width: max(0, (contentWidth * level - size.width) / 2),
            height: max(0, (target.height * level - size.height) / 2)
        )
    }

    private func clampedPan(_ value: CGSize, size: CGSize) -> CGSize {
        let bounds = maxPan(size: size, zoom: zoom * pinch)
        return CGSize(
            width: min(max(value.width, -bounds.width), bounds.width),
            height: min(max(value.height, -bounds.height), bounds.height)
        )
    }

    private func resetZoom() {
        dragX = 0
        pan = .zero
        dragPan = .zero
        zoom = 1
        pinch = 1
    }

    // MARK: - Lifecycle

    private func appeared() {
        open()
        systemBrightness = UIScreen.main.brightness
        applyIdleTimer()
        applyBrightness()
        scheduleChromeHide()
    }

    private func open() {
        guard session == nil else { return }
        guard let opened = store.session(for: book) else {
            failed = true
            scanning = false
            return
        }
        session = opened
        bookmarks = book.bookmarks
        bookmarkNames = book.bookmarkNames

        // Show the first page straight away with the default page ratio. Measuring every
        // page first is what used to keep the reader on a spinner for a long archive.
        // With 裁白边 on, measure that one page now: the page list is built from the cropped
        // ratio, and a first frame drawn before the box exists would be stretched.
        if store.prefs.autoCropWhiteBorders {
            _ = opened.cropBox(at: book.lastPage)
        }
        refs = buildPageRefs(
            aspects: opened.displayAspects(crop: store.prefs.autoCropWhiteBorders),
            splitWidePages: store.prefs.splitWidePages
        )
        index = refIndexOfSource(refs, book.lastPage)
        sliderValue = Double(index)
        scanning = true

        Task { @MainActor in
            let crop = store.prefs.autoCropWhiteBorders
            let aspects = await Task.detached(priority: .utility) {
                opened.scanAspects(crop: crop)
                return opened.displayAspects(crop: crop)
            }.value
            let source = currentSourcePage
            refs = buildPageRefs(aspects: aspects, splitWidePages: store.prefs.splitWidePages)
            index = refIndexOfSource(refs, source)
            sliderValue = Double(index)
            scanning = false
        }
    }

    private func rebuildRefs() {
        guard let session else { return }
        let source = currentSourcePage
        refs = buildPageRefs(
            aspects: session.displayAspects(crop: store.prefs.autoCropWhiteBorders),
            splitWidePages: store.prefs.splitWidePages
        )
        index = refIndexOfSource(refs, source)
        sliderValue = Double(index)
    }

    /// 裁白边开关一变，页面比例就变了 —— 必须重新量一遍，否则图被裁窄了还塞在原来的框
    /// 里，看着就是「变形」。
    private func rescanCrop(_ on: Bool) {
        guard let session else { return }
        let source = currentSourcePage
        scanning = true
        Task { @MainActor in
            let aspects = await Task.detached(priority: .utility) {
                session.scanAspects(crop: on)
                return session.displayAspects(crop: on)
            }.value
            refs = buildPageRefs(aspects: aspects, splitWidePages: store.prefs.splitWidePages)
            index = refIndexOfSource(refs, source)
            sliderValue = Double(index)
            scanning = false
            if store.prefs.readingMode != .paged, refs.indices.contains(index) {
                scrollTarget = refs[index].id
            }
        }
    }

    /// Scrolling reports a new page for every cell Lazy builds, and each write to `index`
    /// re-evaluates the whole reader — including every visible page. Merging the reports to
    /// one per 120 ms keeps the scrubber honest while a fast flick is in progress, and the
    /// trailing flush makes sure the final page still lands.
    private func reportVisible(_ position: Int) {
        guard position != index else { return }
        let now = Date()
        if now.timeIntervalSince(lastVisibleReport) < 0.12 {
            pendingVisible = position
            visibleFlush?.cancel()
            visibleFlush = Task { @MainActor in
                try? await Task.sleep(nanoseconds: 140_000_000)
                guard !Task.isCancelled, let target = pendingVisible else { return }
                pendingVisible = nil
                lastVisibleReport = Date()
                index = target
            }
            return
        }
        lastVisibleReport = now
        index = position
    }

    /// Decodes the next couple of pages in the background so a flick does not land on a
    /// spinner. Serial + low priority, so the page on screen always wins the CPU.
    private func prefetchAhead(from position: Int) {
        guard let session, !scanning, refs.count > 1 else { return }
        let size = viewSize
        guard size.width > 1, size.height > 1 else { return }
        let vertical = store.prefs.readingMode == .vertical
        let settings = store.enhance
        let crop = store.prefs.autoCropWhiteBorders
        let rtl = store.prefs.rightToLeft
        let scale = displayScale

        // 竖滚是最容易「划到一半空白」的模式：一屏里会同时露出两三页，所以窗口放大到
        // 前后各两页；分页模式只需要下一页。
        var targets: [Int] = vertical
            ? [position + 1, position + 2, position + 3]
            : [position + 1, position + 2]
        if position > 0 { targets.append(position - 1) }
        if vertical && position > 1 { targets.append(position - 2) }

        for p in targets where refs.indices.contains(p) {
            let ref = refs[p]
            let width = vertical
                ? size.width * scale
                : size.height * ref.aspect * scale
            if session.isRendered(
                at: ref.index,
                maxWidth: width,
                settings: settings,
                crop: crop,
                half: ref.half,
                rightToLeft: rtl
            ) { continue }
            pagePrefetchQueue.async {
                _ = session.render(
                    at: ref.index,
                    maxWidth: width,
                    settings: settings,
                    crop: crop,
                    half: ref.half,
                    rightToLeft: rtl
                )
            }
        }
    }

    private func dismissReader() {
        leave()
        dismiss()
    }

    private func leave() {
        progressTask?.cancel()
        chromeTask?.cancel()
        persistProgress()
        persistBookmarks()
        UIScreen.main.brightness = systemBrightness
        UIApplication.shared.isIdleTimerDisabled = false
        // Hand rotation back to the system so the shelf is not stuck in the reader's lock.
        OrientationLock.reset()
    }

    private func applyIdleTimer() {
        UIApplication.shared.isIdleTimerDisabled = store.prefs.keepScreenOn
    }

    private func applyBrightness() {
        if store.prefs.autoBrightness {
            UIScreen.main.brightness = systemBrightness
        } else {
            UIScreen.main.brightness = CGFloat(min(max(store.prefs.brightness / 100, 0.05), 1))
        }
    }

    private func showToast(_ text: String) {
        withAnimation { toast = text }
        Task { @MainActor in
            try? await Task.sleep(nanoseconds: 1_600_000_000)
            withAnimation { toast = nil }
        }
    }

    // MARK: - Navigation

    private func turn(forward: Bool) {
        if store.prefs.readingMode == .paged {
            let size = viewSize == .zero ? CGSize(width: 390, height: 844) : viewSize
            step(forward: forward, span: spreadSpan(size: size), size: size)
        } else {
            jump(to: index + (forward ? 1 : -1))
        }
    }

    private func jumpChapter(_ delta: Int) {
        let chapters = session?.chapters ?? []
        guard chapters.count > 1,
              let here = session?.chapter(at: currentSourcePage),
              let position = chapters.firstIndex(where: { $0.start == here.start })
        else {
            turn(forward: delta > 0)
            return
        }
        let target = position + delta
        guard chapters.indices.contains(target) else {
            showToast(delta > 0 ? "已经是最后一话了" : "已经是第一话了")
            return
        }
        jumpToSource(chapters[target].start)
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

    // MARK: - Persistence

    private func saveBookmark() {
        let page = currentSourcePage
        if !bookmarks.contains(page) { bookmarks.append(page) }
        bookmarkNames[page] = addName.isEmpty ? "第 \(page + 1) 页" : addName
        showAddBookmark = false
    }

    private func persistBookmarks() {
        guard var updated = store.books.first(where: { $0.id == book.id }) else { return }
        updated.bookmarks = bookmarks
        updated.bookmarkNames = bookmarkNames
        store.update(updated)
    }

    private func scheduleProgressSave() {
        progressTask?.cancel()
        progressTask = Task { @MainActor in
            try? await Task.sleep(nanoseconds: 1_500_000_000)
            guard !Task.isCancelled else { return }
            persistProgress()
        }
    }

    private func persistProgress() {
        guard session != nil else { return }
        guard var updated = store.books.first(where: { $0.id == book.id }) else { return }
        let page = currentSourcePage
        guard updated.lastPage != page else { return }
        updated.lastPage = page
        updated.lastReadAt = Date()
        store.update(updated)
    }

    private func setCoverFromPage() {
        let ok = store.setCover(book, page: currentSourcePage)
        showToast(ok ? "封面已更新" : "这张页面无法用作封面")
    }
}

/// Inverts the page content only, so the black surround stays black.
private struct GrayscaleFilter: ViewModifier {
    let active: Bool

    @ViewBuilder
    func body(content: Content) -> some View {
        if active {
            content.grayscale(1)
        } else {
            content
        }
    }
}

private struct InvertFilter: ViewModifier {
    let active: Bool


    @ViewBuilder
    func body(content: Content) -> some View {
        if active {
            content.colorInvert()
        } else {
            content
        }
    }
}

/// 蓝光过滤层。
///
/// 用一层暖色做 multiply 叠加，而不是在解码时改像素：调强度是实时的，不用把已经缓存的
/// 页面全部重算一遍；黑色区域乘任何颜色还是黑色，所以黑底漫画不会发灰。
private struct BlueLightLayer: View {
    /// 0–100
    let strength: Int

    var body: some View {
        let t = Double(min(max(strength, 0), 100)) / 100
        Color(
            red: 1.0,
            green: 1.0 - 0.16 * t,
            blue: 1.0 - 0.52 * t
        )
        .blendMode(.multiply)
        .allowsHitTesting(false)
        .ignoresSafeArea()
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
    /// Paged cells are already sized to the page ratio, so they must cover the frame
    /// exactly; otherwise a rounding difference leaves a hairline of background.
    var fill = false

    @State private var image: CGImage?
    @State private var failed = false
    @State private var attempt = 0

    init(
        session: BookSession,
        ref: PageRef,
        settings: EnhanceSettings,
        crop: Bool,
        maxWidth: CGFloat,
        rightToLeft: Bool,
        fill: Bool = false
    ) {
        self.session = session
        self.ref = ref
        self.settings = settings
        self.crop = crop
        self.maxWidth = maxWidth
        self.rightToLeft = rightToLeft
        self.fill = fill
        // Lazy containers recycle cells while scrolling; a rebuilt cell whose page is still
        // in the render cache shows its image in the very same frame instead of flashing a
        // spinner and decoding again.
        _image = State(
            initialValue: session.cachedRender(
                at: ref.index,
                maxWidth: maxWidth,
                settings: settings,
                crop: crop,
                half: ref.half,
                rightToLeft: rightToLeft
            )
        )
    }

    private var cacheKey: String {
        "\(ref.index)-\(ref.half)-\(settings.hashValue)-\(crop)-\(rightToLeft)-\(Int(maxWidth))-\(attempt)"
    }

    var body: some View {
        ZStack {
            if let image {
                Image(decorative: image, scale: 1)
                    .resizable()
                    // `.high` resampled the whole page on every scroll frame. Pages are
                    // decoded at ~1.05x their display size, so the linear filter is visually
                    // indistinguishable here and much cheaper on a 120Hz panel.
                    .interpolation(.medium)
                    .aspectRatio(contentMode: fill ? .fill : .fit)
            } else if failed {
                // Never spin forever: a page that cannot be decoded says so, and the reason
                // is visible instead of looking like a stuck loader.
                VStack(spacing: 10) {
                    Image(systemName: "exclamationmark.triangle")
                        .font(.system(size: 22, weight: .light))
                    Text("第 \(ref.index + 1) 页无法解码")
                        .font(.system(size: 13))
                    Button("重试") { attempt += 1 }
                        .font(.system(size: 13, weight: .medium))
                }
                .foregroundStyle(Color.white.opacity(0.7))
                .padding(18)
            } else {
                ProgressView().tint(.white.opacity(0.5))
            }
        }
        .task(id: cacheKey) {
            // Recycled cells whose page is still cached answer immediately.
            if let hit = self.session.cachedRender(
                at: self.ref.index,
                maxWidth: self.maxWidth,
                settings: self.settings,
                crop: self.crop,
                half: self.ref.half,
                rightToLeft: self.rightToLeft
            ) {
                image = hit
                failed = false
                return
            }
            image = nil
            failed = false
            let settings = self.settings
            let crop = self.crop
            let rightToLeft = self.rightToLeft
            let maxWidth = self.maxWidth
            let ref = self.ref
            let session = self.session

            let result = await Task.detached(priority: .userInitiated) { () -> CGImage? in
                // Decode + enhance + crop in one go, and keep the result: coming back to a
                // page then costs nothing at all.
                session.render(
                    at: ref.index,
                    maxWidth: maxWidth,
                    settings: settings,
                    crop: crop,
                    half: ref.half,
                    rightToLeft: rightToLeft
                )
            }.value
            // A cell that scrolled away before its page was ready has nothing to show.
            guard !Task.isCancelled else { return }
            image = result
            failed = (result == nil)
        }
    }
}
