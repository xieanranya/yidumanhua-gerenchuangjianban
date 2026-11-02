import Foundation
import UIKit
import OnnxRuntimeBindings

/// 黑白漫画自动上色：manga-colorization-v2 generator（ONNX **fp32** 117MB，Tinigon 导出）。
///
/// 输入 [1,5,H,W]：ch0 = 灰度(0..1)，ch1-3 = 颜色提示（自动模式全 0），ch4 = 提示掩码（0）。
/// H/W 为 32 的倍数（动态维度）—— 长边 ≤768 对齐 32。
/// 输出 [1,3,H,W]：RGB **[-1,1]**（tanh，需 *0.5+0.5 反归一化）。
///
/// 合成（line-mix，与安卓 1.9.52 对齐）：
///   暗部（原图线稿 Y<0.45）→ 亮度用原图（锐利黑线），色度向中性收 55%；
///   其余全部用模型原生输出（亮度+色度都来自模型 → 颜色天然协调）。
///   不要换回 fp16 模型（部分手机 ORT-ARM 推理 native 崩溃），也不要直接放大
///   模型输出（糊）或用「模型色度+原图亮度」（网点灰底被染成脏色）。
/// 任何失败都返回原图，绝不挡阅读。
enum ColorizeEngine {

    // MARK: - 模型分发

    static let modelFile = "manga-colorizer-fp32.onnx"
    static let modelBytes = 122_944_482
    /// 双源：国内镜像优先，原站兜底
    static let downloadURLs = [
        "https://hf-mirror.com/Tinigon/manga-colorizer-onnx/resolve/main/colorizer.onnx",
        "https://huggingface.co/Tinigon/manga-colorizer-onnx/resolve/main/colorizer.onnx",
    ]
    /// 旧版模型文件（fp16），新版下载成功后清理
    static let legacyModelFile = "manga-colorize-fp16.onnx"

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
        req.timeoutInterval = 120
        let (stream, resp) = try await URLSession.shared.bytes(for: req)
        guard let http = resp as? HTTPURLResponse, http.statusCode == 200 else {
            throw NSError(domain: "colorize", code: -2,
                userInfo: [NSLocalizedDescriptionKey: "下载源返回异常"])
        }
        let total = Double(max(http.expectedContentLength, 1))
        var data = Data()
        data.reserveCapacity(modelBytes)
        var lastReport = 0
        // 按块读（64KB）：逐字节 for-await 122MB 太慢
        var buffer = [UInt8](repeating: 0, count: 1 << 16)
        var filled = 0
        for try await byte in stream {
            buffer[filled] = byte
            filled += 1
            if filled == buffer.count {
                data.append(contentsOf: buffer)
                filled = 0
                if data.count - lastReport >= (1 << 20) {
                    lastReport = data.count
                    progress(min(Double(data.count) / total, 0.99))
                }
            }
        }
        if filled > 0 { data.append(contentsOf: buffer[0..<filled]) }
        guard data.count == modelBytes else {
            throw NSError(domain: "colorize", code: -3,
                userInfo: [NSLocalizedDescriptionKey: "模型大小校验失败（拿到 \(data.count) 字节）"])
        }
        try data.write(to: URL(fileURLWithPath: tmp))
        try? fm.removeItem(at: modelURL)
        try fm.moveItem(atPath: tmp, toPath: modelURL.path)
        // 旧 fp16 模型清理（换过模型后老用户目录里会留孤儿文件）
        try? fm.removeItem(at: modelDir.appendingPathComponent(legacyModelFile))
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

        // 推理尺寸：长边 ≤ 768，对齐 32（只影响色度精细度；清晰度由原图决定）
        var tw = w0, th = h0
        let long = max(w0, h0)
        if long > 768 {
            let k = 768.0 / CGFloat(long)
            tw = Int((CGFloat(w0) * k).rounded())
            th = Int((CGFloat(h0) * k).rounded())
        }
        tw = max(32, (tw / 32) * 32)
        th = max(32, (th / 32) * 32)

        // ---- 原图全分辨率灰度 Y（line-mix 合成用，线稿清晰度全靠它） ----
        var grayFull = [UInt8](repeating: 0, count: w0 * h0)
        var drewFull = false
        grayFull.withUnsafeMutableBytes { ptr in
            guard let base = ptr.baseAddress else { return }
            guard let gctx = CGContext(
                data: base, width: w0, height: h0, bitsPerComponent: 8,
                bytesPerRow: w0, space: CGColorSpaceCreateDeviceGray(),
                bitmapInfo: CGImageAlphaInfo.none.rawValue
            ) else { return }
            gctx.interpolationQuality = .high
            gctx.draw(cg, in: CGRect(x: 0, y: 0, width: w0, height: h0))
            drewFull = true
        }
        guard drewFull else { return nil }

        // ---- 画推理用灰度（8bit） ------------------------------------------
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

            // ---- line-mix 合成：暗部线稿用原图亮度（色度收中性），其余用模型原生输出 ----
            let rgba = outData.withUnsafeBytes { buf -> [UInt8] in
                let f = buf.bindMemory(to: Float.self)
                var px = [UInt8](repeating: 255, count: w0 * h0 * 4)
                for y in 0 ..< h0 {
                    let fy = Float(y) * Float(th) / Float(h0)
                    let yi0 = min(Int(fy), th - 1), yi1 = min(yi0 + 1, th - 1)
                    let wy = fy - Float(yi0)
                    let row = y * w0
                    for x in 0 ..< w0 {
                        let fx = Float(x) * Float(tw) / Float(w0)
                        let xi0 = min(Int(fx), tw - 1), xi1 = min(xi0 + 1, tw - 1)
                        let wx = fx - Float(xi0)
                        let i = row + x
                        // 模型 RGB（tanh [-1,1] → 反归一化 [0,1]）双线性采样
                        let mr = clamp01(bilerp(f, 0, plane, tw, xi0, xi1, yi0, yi1, wx, wy) * 0.5 + 0.5)
                        let mg = clamp01(bilerp(f, plane, plane, tw, xi0, xi1, yi0, yi1, wx, wy) * 0.5 + 0.5)
                        let mb = clamp01(bilerp(f, 2 * plane, plane, tw, xi0, xi1, yi0, yi1, wx, wy) * 0.5 + 0.5)
                        let ys = Float(grayFull[i]) / 255.0
                        let ym = 0.299 * mr + 0.587 * mg + 0.114 * mb
                        let mask = max(0.0, min(1.0, (0.45 - ys) / 0.22))
                        let yf = ys * mask + ym * (1 - mask)
                        let chroma = 1.0 - 0.55 * mask
                        let cb = 0.5 + (-0.168736 * mr - 0.331264 * mg + 0.5 * mb) * chroma
                        let cr = 0.5 + (0.5 * mr - 0.418688 * mg - 0.081312 * mb) * chroma
                        px[i * 4 + 0] = clampByte(Float(yf + 1.402 * (cr - 0.5)))
                        px[i * 4 + 1] = clampByte(Float(yf - 0.344136 * (cb - 0.5) - 0.714136 * (cr - 0.5)))
                        px[i * 4 + 2] = clampByte(Float(yf + 1.772 * (cb - 0.5)))
                    }
                }
                return px
            }
            guard let img = image(fromRGBA: rgba, width: w0, height: h0) else { return nil }
            return img
        } catch {
            return nil
        }
    }

    // MARK: - 图像工具

    private static func clamp01(_ v: Float) -> Float { max(0, min(1, v)) }

    private static func bilerp(
        _ f: UnsafeBufferPointer<Float>, _ chBase: Int, _ plane: Int, _ tw: Int,
        _ x0: Int, _ x1: Int, _ y0: Int, _ y1: Int, _ wx: Float, _ wy: Float
    ) -> Float {
        let v00 = f[chBase + y0 * tw + x0]
        let v10 = f[chBase + y0 * tw + x1]
        let v01 = f[chBase + y1 * tw + x0]
        let v11 = f[chBase + y1 * tw + x1]
        return v00 * (1 - wx) * (1 - wy) + v10 * wx * (1 - wy) +
            v01 * (1 - wx) * wy + v11 * wx * wy
    }

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
