import Foundation
import UIKit
import OnnxRuntimeBindings

/// 黑白漫画自动上色：manga-colorization-v2 generator（ONNX fp16，HuggingFace，MIT）。
///
/// 输入 [1,5,H,W]：ch0 = 灰度(0..1)，ch1-3 = 颜色提示（自动模式全 0），ch4 = 提示掩码（0）。
/// 输出 [1,3,H,W]：RGB 0..1。H/W 须为 32 的倍数 —— 短边缩到 ≤512 并对齐 32，
/// 推理完再放大回原尺寸。
/// 后处理：灰世界白平衡去黄（该模型输出系统性偏暖，luoyeguishanhe fork 的实战结论）。
/// 任何失败都返回原图，绝不挡阅读。
enum ColorizeEngine {

    // MARK: - 模型分发

    static let modelFile = "manga-colorize-fp16.onnx"
    static let modelBytes = 61_650_260
    /// 双源：国内镜像优先，原站兜底
    static let downloadURLs = [
        "https://hf-mirror.com/Faridzar/manga-colorization-v2-onnx/resolve/main/manga-colorize-fp16.onnx",
        "https://huggingface.co/Faridzar/manga-colorization-v2-onnx/resolve/main/manga-colorize-fp16.onnx",
    ]

    private static let fm = FileManager.default

    private static var modelDir: URL {
        let base = fm.urls(for: .applicationSupportDirectory, in: .userDomainMask).first
            ?? fm.temporaryDirectory
        let dir = base.appendingPathComponent("models", isDirectory: true)
        try? fm.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }

    static var modelURL: URL { modelDir.appendingPathComponent(modelFile) }

    static var modelReady: Bool {
        let size = (try? fm.attributesOfItem(atPath: modelURL.path))?[.size] as? Int
        return size == modelBytes
    }

    static func deleteModel() {
        try? fm.removeItem(at: modelURL)
    }

    // MARK: - 下载（双源，进度回调）

    static func download(progress: @escaping (Double) -> Void) async throws {
        var lastError: Error?
        for u in downloadURLs {
            guard let url = URL(string: u) else { continue }
            do {
                try await fetchModel(url, progress: progress)
                if modelReady { return }
            } catch { lastError = error }
        }
        throw lastError ?? NSError(
            domain: "colorize", code: -1,
            userInfo: [NSLocalizedDescriptionKey: "模型下载失败：两个源都没成功"]
        )
    }

    private static func fetchModel(_ url: URL, progress: @escaping (Double) -> Void) async throws {
        let tmp = modelURL.path + ".part"
        try? fm.removeItem(atPath: tmp)
        var req = URLRequest(url: url)
        req.timeoutInterval = 60
        let (stream, resp) = try await URLSession.shared.bytes(for: req)
        guard let http = resp as? HTTPURLResponse, http.statusCode == 200 else {
            throw NSError(domain: "colorize", code: -2,
                userInfo: [NSLocalizedDescriptionKey: "下载源返回异常"])
        }
        let total = Double(max(http.expectedContentLength, 1))
        var data = Data()
        data.reserveCapacity(modelBytes)
        var lastReport = 0
        for try await byte in stream {
            data.append(byte)
            // 每下载约 1MB 报一次进度（逐字节回调太频繁）
            if data.count - lastReport >= (1 << 20) {
                lastReport = data.count
                progress(min(Double(data.count) / total, 0.99))
            }
        }
        guard data.count == modelBytes else {
            throw NSError(domain: "colorize", code: -3,
                userInfo: [NSLocalizedDescriptionKey: "模型大小校验失败（拿到 \(data.count) 字节）"])
        }
        try data.write(to: URL(fileURLWithPath: tmp))
        try? fm.removeItem(at: modelURL)
        try fm.moveItem(atPath: tmp, toPath: modelURL.path)
        progress(1.0)
    }

    // MARK: - 推理

    private static let runQueue = DispatchQueue(label: "colorize.run", qos: .userInitiated)
    nonisolated(unsafe) private static var session: ORTSession?
    nonisolated(unsafe) private static var env: ORTEnv?

    private static func acquireSession() throws -> ORTSession {
        if let session { return session }
        guard modelReady else {
            throw NSError(domain: "colorize", code: -4,
                userInfo: [NSLocalizedDescriptionKey: "上色模型还没下载"])
        }
        let e = try ORTEnv(loggingLevel: .warning)
        let s = try ORTSession(env: e, modelPath: modelURL.path, sessionOptions: nil)
        env = e
        session = s
        return s
    }

    /// 上色一页。模型没就绪 / 推理失败一律返回 nil（调用方回退原图）。
    static func colorize(_ input: UIImage) async -> UIImage? {
        await withCheckedContinuation { cont in
            runQueue.async {
                cont.resume(returning: runColorize(input))
            }
        }
    }

    private static func runColorize(_ input: UIImage) -> UIImage? {
        guard let cg = input.cgImage, cg.width > 0, cg.height > 0 else { return nil }
        guard let session = try? acquireSession() else { return nil }
        return autoreleasepool { colorizeCore(cg, session: session) }
    }

    private static func colorizeCore(_ cg: CGImage, session: ORTSession) -> UIImage? {
        let w0 = cg.width, h0 = cg.height

        // 推理尺寸：长边 ≤ 512，对齐 32
        var tw = w0, th = h0
        let long = max(w0, h0)
        if long > 512 {
            let k = 512.0 / CGFloat(long)
            tw = Int((CGFloat(w0) * k).rounded())
            th = Int((CGFloat(h0) * k).rounded())
        }
        tw = max(32, (tw / 32) * 32)
        th = max(32, (th / 32) * 32)

        // ---- 画灰度（8bit） ------------------------------------------------
        var gray = [UInt8](repeating: 0, count: tw * th)
        var drew = false
        gray.withUnsafeMutableBytes { ptr in
            guard let base = ptr.baseAddress else { return }
            guard let gctx = CGContext(
                data: base, width: tw, height: th, bitsPerComponent: 8,
                bytesPerRow: tw, space: CGColorSpaceCreateDeviceGray(),
                bitmapInfo: CGImageAlphaInfo.none.rawValue
            ) else { return }
            gctx.interpolationQuality = .high
            gctx.draw(cg, in: CGRect(x: 0, y: 0, width: tw, height: th))
            drew = true
        }
        guard drew else { return nil }

        // ---- 组输入 [1,5,H,W] ---------------------------------------------
        let plane = th * tw
        var inp = [Float](repeating: 0, count: 5 * plane)
        for i in 0 ..< plane { inp[i] = Float(gray[i]) / 255.0 }
        let inputData = inp.withUnsafeBytes { Data($0) }

        // ---- 推理 -----------------------------------------------------------
        let inName: String
        let outName: String
        do {
            inName = (try? session.inputNames())?.first ?? "input"
            outName = (try? session.outputNames())?.first ?? "output"
            let value = try ORTValue(
                tensorData: NSMutableData(data: inputData),
                elementType: .float,
                shape: [1, 5, NSNumber(value: th), NSNumber(value: tw)]
            )
            let outputs = try session.run(
                withInputs: [inName: value],
                outputNames: Set([outName]),
                runOptions: nil
            )
            guard let out = outputs[outName] else { return nil }
            let outData = try out.tensorData() as Data
            guard outData.count == plane * 3 * MemoryLayout<Float>.size else { return nil }

            // ---- 输出 [1,3,H,W] → RGBA → 去黄 → UIImage -----------------------
            let rgba = outData.withUnsafeBytes { buf -> [UInt8] in
                let f = buf.bindMemory(to: Float.self)
                var px = [UInt8](repeating: 255, count: plane * 4)
                for i in 0 ..< plane {
                    px[i * 4 + 0] = clampByte(f[i])
                    px[i * 4 + 1] = clampByte(f[plane + i])
                    px[i * 4 + 2] = clampByte(f[2 * plane + i])
                }
                return grayWorldBalance(px, plane: plane, strength: 0.65)
            }
            guard let small = image(fromRGBA: rgba, width: tw, height: th) else { return nil }
            return resize(small, width: w0, height: h0)
        } catch {
            return nil
        }
    }

    // MARK: - 图像工具

    private static func clampByte(_ f: Float) -> UInt8 {
        UInt8(max(0, min(255, f * 255.0)))
    }

    private static func image(fromRGBA px: [UInt8], width: Int, height: Int) -> UIImage? {
        var data = px
        let cs = CGColorSpaceCreateDeviceRGB()
        guard let ctx = CGContext(
            data: &data, width: width, height: height, bitsPerComponent: 8,
            bytesPerRow: width * 4, space: cs,
            bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue
        ), let img = ctx.makeImage() else { return nil }
        return UIImage(cgImage: img)
    }

    private static func resize(_ image: UIImage, width: Int, height: Int) -> UIImage? {
        let fmt = UIGraphicsImageRendererFormat()
        fmt.scale = 1
        let r = UIGraphicsImageRenderer(size: CGSize(width: width, height: height), format: fmt)
        return r.image { _ in
            image.draw(in: CGRect(x: 0, y: 0, width: width, height: height))
        }
    }

    /// 灰世界白平衡去黄：三通道均值拉向其中间值。
    /// 模型对中性灰区域系统性输出暖棕（R>G>B），全图按增益 lerp(1, target/m, strength) 校正。
    private static func grayWorldBalance(_ px: [UInt8], plane: Int, strength: Double) -> [UInt8] {
        var sr = 0.0, sg = 0.0, sb = 0.0
        var n = 0.0
        var i = 0
        while i < plane {
            sr += Double(px[i * 4]); sg += Double(px[i * 4 + 1]); sb += Double(px[i * 4 + 2])
            n += 1
            i += 7  // 抽样 1/7 像素，均值足够稳
        }
        guard n > 0 else { return px }
        let mr = sr / n, mg = sg / n, mb = sb / n
        let target = (mr + mg + mb) / 3
        let gr = 1.0 + strength * (target / max(mr, 1e-3) - 1.0)
        let gg = 1.0 + strength * (target / max(mg, 1e-3) - 1.0)
        let gb = 1.0 + strength * (target / max(mb, 1e-3) - 1.0)
        var out = px
        for i in 0 ..< plane {
            out[i * 4 + 0] = UInt8(max(0, min(255, Double(px[i * 4 + 0]) * gr)))
            out[i * 4 + 1] = UInt8(max(0, min(255, Double(px[i * 4 + 1]) * gg)))
            out[i * 4 + 2] = UInt8(max(0, min(255, Double(px[i * 4 + 2]) * gb)))
        }
        return out
    }
}

/// 模型下载状态机（设置页用）。
@MainActor
final class ColorizeModelStore: ObservableObject {
    static let shared = ColorizeModelStore()

    enum State: Equatable {
        case idle           // 未下载
        case downloading    // 下载中（看 progress）
        case ready          // 可用
        case failed(String)
    }

    @Published var state: State = ColorizeEngine.modelReady ? .ready : .idle
    @Published var progress: Double = 0

    func refresh() {
        if state != .downloading {
            state = ColorizeEngine.modelReady ? .ready : .idle
            progress = 0
        }
    }

    func startDownload() {
        guard state != .downloading else { return }
        state = .downloading
        progress = 0
        Task {
            do {
                try await ColorizeEngine.download { [weak self] p in
                    Task { @MainActor in self?.progress = p }
                }
                state = .ready
                progress = 1
            } catch {
                state = .failed(error.localizedDescription)
            }
        }
    }
}
