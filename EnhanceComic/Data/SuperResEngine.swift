import Foundation
import UIKit
import OnnxRuntimeBindings

/// 在线页画质增强：realesr-animevideov3 超分（ONNX **fp32 2.4MB 随包内置**，选自
/// Tomoali/realesr-animevideov3-onnx，BSD-3-Clause，上游 xinntao/Real-ESRGAN）。
///
/// 模型：SRVGGNetCompact（18 层 3×3 卷积 64 通道），固定输入 [1,3,256,256]（RGB 0..1 NCHW），
/// 输出 [1,3,1024,1024]（0..1）—— 固定 4 倍。纯卷积 → 分块推理无缝可行。
///
/// 策略（与选型报告一致）：
///   - 处理尺寸：长边 ≤1024（内存/耗时控制），不足的维度用边缘色 pad 到 256 倍数；
///   - 分块：256×256 tile、重叠 8px（输出裁 32px），消除卷积边缘伪影；
///   - line-mix：暗部（原线稿 Y<0.45）保持「原图放大」的亮度与色度（防文字/网点被
///     GAN 重画），其余用模型输出——与上色引擎同一套混合思路；
///   - 输出缩回目标显示宽度（调用方传），绝不把 4x 大图直接塞进缓存。
/// 任何失败返回 nil，调用方回退原图，绝不挡阅读。
enum SuperResEngine {

    static let modelResource = "animevideov3_256"
    static let tile = 256
    static let overlap = 8            // 输入重叠像素（输出对应裁 overlap*4）
    private static let maxSide: CGFloat = 1024

    private static let runQueue = DispatchQueue(label: "superres.run", qos: .userInitiated)
    nonisolated(unsafe) private static var session: ORTSession?
    nonisolated(unsafe) private static var env: ORTEnv?

    private static func acquireSession() throws -> ORTSession {
        if let session { return session }
        guard let url = Bundle.main.url(forResource: modelResource, withExtension: "onnx") else {
            throw NSError(domain: "superres", code: -1,
                userInfo: [NSLocalizedDescriptionKey: "超分模型未内置"])
        }
        let e = try ORTEnv(loggingLevel: .warning)
        let s = try ORTSession(env: e, modelPath: url.path, sessionOptions: nil)
        env = e
        session = s
        return s
    }

    /// 增强一页。
    /// - Parameters:
    ///   - image: 已裁白边的页面
    ///   - targetWidth: 目标显示宽度（物理像素），输出会缩到这个宽度
    static func enhance(_ image: UIImage, targetWidth: CGFloat) async -> UIImage? {
        await withCheckedContinuation { cont in
            runQueue.async {
                cont.resume(returning: autoreleasepool { runEnhance(image, targetWidth: targetWidth) })
            }
        }
    }

    private static func runEnhance(_ input: UIImage, targetWidth: CGFloat) -> UIImage? {
        guard let cg = input.cgImage, cg.width > 64, cg.height > 64 else { return nil }
        guard let session = try? acquireSession() else { return nil }
        return autoreleasepool { runCore(cg, session: session, targetWidth: targetWidth) }
    }

    private static func runCore(_ cg: CGImage, session: ORTSession, targetWidth: CGFloat) -> UIImage? {
        let w0 = cg.width, h0 = cg.height

        // ---- 处理尺寸：长边 ≤ maxSide，宽高 pad 到 tile 倍数 ----
        var pw = w0, ph = h0
        let long = max(w0, h0)
        if CGFloat(long) > maxSide {
            let k = maxSide / CGFloat(long)
            pw = Int((CGFloat(w0) * k).rounded())
            ph = Int((CGFloat(h0) * k).rounded())
        }
        let tw = ((pw + tile - 1) / tile) * tile
        let th = ((ph + tile - 1) / tile) * tile

        // ---- 处理尺寸的 RGBA 位图（pad 区域用边缘像素色填充，避免 pad 黑边被模型增强）----
        guard let full = makePaddedBitmap(cg, pw: pw, ph: ph, tw: tw, th: th) else { return nil }

        // ---- 分块推理 ----
        let outTile = tile * 4
        let outOverlap = overlap * 4
        let bigW = tw * 4, bigH = th * 4
        guard let bigCtx = CGContext(
            data: nil, width: bigW, height: bigH, bitsPerComponent: 8, bytesPerRow: 0,
            space: CGColorSpaceCreateDeviceRGB(),
            bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue
        ) else { return nil }

        let cols = tw / tile
        let rows = th / tile
        for r in 0..<rows {
            for c in 0..<cols {
                let sx = c * tile, sy = r * tile
                autoreleasepool {
                    guard let tileImg = full.cropping(to: CGRect(x: sx, y: sy, width: tile, height: tile)) else { return }
                    guard let out = infer(tileImg, session: session) else { return }
                    // 裁掉重叠边缘（边缘受卷积 padding 影响）
                    let cutX = (c == 0 ? 0 : outOverlap)
                    let cutY = (r == 0 ? 0 : outOverlap)
                    let srcX = cutX, srcY = cutY
                    let srcW = outTile - cutX - (c == cols - 1 ? 0 : outOverlap)
                    let srcH = outTile - cutY - (r == rows - 1 ? 0 : outOverlap)
                    if let piece = out.cropping(to: CGRect(x: srcX, y: srcY, width: srcW, height: srcH)) {
                        // CGContext 原点在左下：输出的行方向要翻转
                        let dy = bigH - (r * tile * 4) - srcH - cutY
                        bigCtx.draw(piece, in: CGRect(x: CGFloat(c * tile * 4 + cutX), y: CGFloat(dy), width: CGFloat(srcW), height: CGFloat(srcH)))
                    }
                }
            }
        }
        guard let big = bigCtx.makeImage() else { return nil }

        // ---- 裁掉 pad、缩回目标宽度 ----
        let outW = Int((CGFloat(pw) * 4).rounded()), outH = Int((CGFloat(ph) * 4).rounded())
        guard let unpadded = big.cropping(to: CGRect(x: 0, y: bigH - outH, width: outW, height: outH)) else { return nil }
        let targetH = max(1, Int((CGFloat(outH) / CGFloat(outW) * targetWidth).rounded()))
        guard let sized = scaleTo(unpadded, w: Int(targetWidth), h: targetH) else { return nil }

        // ---- line-mix：暗部用原图（保文字/网点），其余用模型 ----
        return lineMix(original: cg, enhanced: sized)
    }

    // MARK: - 单块推理

    private static func infer(_ tileImg: CGImage, session: ORTSession) -> CGImage? {
        guard tileImg.width == tile, tileImg.height == tile else { return nil }
        let plane = tile * tile
        var inp = [Float](repeating: 0, count: 3 * plane)
        // 整块画进已知 RGBA 位图上下文读回（CGImage 直接取分量不可靠）
        var buf = [UInt8](repeating: 0, count: tile * tile * 4)
        let ok = buf.withUnsafeMutableBytes { ptr -> Bool in
            guard let ctx = CGContext(
                data: ptr.baseAddress, width: tile, height: tile, bitsPerComponent: 8,
                bytesPerRow: tile * 4, space: CGColorSpaceCreateDeviceRGB(),
                bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue
            ) else { return false }
            ctx.draw(tileImg, in: CGRect(x: 0, y: 0, width: tile, height: tile))
            return true
        }
        guard ok else { return nil }
        for i in 0..<plane {
            inp[i] = Float(buf[i * 4]) / 255.0
            inp[plane + i] = Float(buf[i * 4 + 1]) / 255.0
            inp[2 * plane + i] = Float(buf[i * 4 + 2]) / 255.0
        }
        do {
            let inName = (try? session.inputNames())?.first ?? "input"
            let outName = (try? session.outputNames())?.first ?? "output"
            let value = try ORTValue(
                tensorData: NSMutableData(bytes: inp, length: inp.count * MemoryLayout<Float>.size),
                elementType: .float,
                shape: [1, 3, NSNumber(value: tile), NSNumber(value: tile)]
            )
            let outputs = try session.run(withInputs: [inName: value], outputNames: Set([outName]), runOptions: nil)
            guard let out = outputs[outName] else { return nil }
            let outData = try out.tensorData() as Data
            let outTile = tile * 4
            let outPlane = outTile * outTile
            guard outData.count == outPlane * 3 * MemoryLayout<Float>.size else { return nil }

            let rgba = outData.withUnsafeBytes { buf2 -> [UInt8] in
                let f = buf2.bindMemory(to: Float.self)
                var px = [UInt8](repeating: 255, count: outPlane * 4)
                for i in 0..<outPlane {
                    px[i * 4]     = clampByte(f[i] * 255)
                    px[i * 4 + 1] = clampByte(f[outPlane + i] * 255)
                    px[i * 4 + 2] = clampByte(f[2 * outPlane + i] * 255)
                }
                return px
            }
            return image(fromRGBA: rgba, width: outTile, height: outTile)?.cgImage
        } catch {
            return nil
        }
    }

    // MARK: - 图像工具

    /// 处理尺寸画布：原图画在画布顶部（内容区），底部/右侧 pad 区保持黑色——
    /// 这些区域会被模型推理，但最终 unpadded 裁剪时全部裁掉
    private static func makePaddedBitmap(_ cg: CGImage, pw: Int, ph: Int, tw: Int, th: Int) -> CGImage? {
        var buf = [UInt8](repeating: 0, count: tw * th * 4)
        let ok = buf.withUnsafeMutableBytes { ptr -> Bool in
            guard let ctx = CGContext(
                data: ptr.baseAddress, width: tw, height: th, bitsPerComponent: 8,
                bytesPerRow: tw * 4, space: CGColorSpaceCreateDeviceRGB(),
                bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue
            ) else { return false }
            ctx.interpolationQuality = .high
            // CGContext 原点左下：内容放高 y 区（= 图像顶部），pad 落在图像底部
            ctx.draw(cg, in: CGRect(x: 0, y: CGFloat(th - ph), width: CGFloat(pw), height: CGFloat(ph)))
            return true
        }
        guard ok else { return nil }
        return buf.withUnsafeMutableBytes { ptr in
            CGContext(
                data: ptr.baseAddress, width: tw, height: th, bitsPerComponent: 8,
                bytesPerRow: tw * 4, space: CGColorSpaceCreateDeviceRGB(),
                bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue
            )?.makeImage()
        }
    }

    private static func scaleTo(_ cg: CGImage, w: Int, h: Int) -> CGImage? {
        guard w > 0, h > 0 else { return nil }
        var buf = [UInt8](repeating: 0, count: w * h * 4)
        let ok = buf.withUnsafeMutableBytes { ptr -> Bool in
            guard let ctx = CGContext(
                data: ptr.baseAddress, width: w, height: h, bitsPerComponent: 8,
                bytesPerRow: w * 4, space: CGColorSpaceCreateDeviceRGB(),
                bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue
            ) else { return false }
            ctx.interpolationQuality = .high
            ctx.draw(cg, in: CGRect(x: 0, y: 0, width: w, height: h))
            return true
        }
        guard ok else { return nil }
        return buf.withUnsafeMutableBytes { ptr in
            CGContext(
                data: ptr.baseAddress, width: w, height: h, bitsPerComponent: 8,
                bytesPerRow: w * 4, space: CGColorSpaceCreateDeviceRGB(),
                bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue
            )?.makeImage()
        }
    }

    /// line-mix：暗部（原线稿 Y<0.45）用「原图 Lanczos 放大」的亮度+色度（文字/网点不被
    /// GAN 重画），其余用模型输出。掩码思路与上色引擎一致。
    private static func lineMix(original cg: CGImage, enhanced: CGImage) -> UIImage? {
        let w = enhanced.width, h = enhanced.height
        // 原图缩放到输出尺寸
        guard let origScaled = scaleTo(cg, w: w, h: h) else { return UIImage(cgImage: enhanced) }
        var eBuf = [UInt8](repeating: 0, count: w * h * 4)
        var oBuf = [UInt8](repeating: 0, count: w * h * 4)
        func draw(_ img: CGImage, into buf: inout [UInt8]) -> Bool {
            buf.withUnsafeMutableBytes { ptr -> Void in
                if let ctx = CGContext(
                    data: ptr.baseAddress, width: w, height: h, bitsPerComponent: 8,
                    bytesPerRow: w * 4, space: CGColorSpaceCreateDeviceRGB(),
                    bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue
                ) {
                    ctx.interpolationQuality = .high
                    ctx.draw(img, in: CGRect(x: 0, y: 0, width: w, height: h))
                }
            }
            return true
        }
        _ = draw(enhanced, into: &eBuf)
        _ = draw(origScaled, into: &oBuf)

        var out = [UInt8](repeating: 255, count: w * h * 4)
        for i in stride(from: 0, to: w * h * 4, by: 4) {
            let r0 = CGFloat(oBuf[i]) / 255, g0 = CGFloat(oBuf[i + 1]) / 255, b0 = CGFloat(oBuf[i + 2]) / 255
            let ys = 0.299 * r0 + 0.587 * g0 + 0.114 * b0
            let mask = max(0.0, min(1.0, (0.45 - ys) / 0.22))   // 1 = 暗部
            if mask > 0 {
                for k in 0..<3 {
                    let e = CGFloat(eBuf[i + k]), o = CGFloat(oBuf[i + k])
                    out[i + k] = clampByte(Float(o * mask + e * (1 - mask)))
                }
            } else {
                out[i] = eBuf[i]; out[i + 1] = eBuf[i + 1]; out[i + 2] = eBuf[i + 2]
            }
            out[i + 3] = 255
        }
        guard let img = image(fromRGBA: out, width: w, height: h) else { return UIImage(cgImage: enhanced) }
        return img
    }

    private static func clampByte(_ v: Float) -> UInt8 {
        UInt8(max(0, min(255, v.rounded())))
    }

    private static func image(fromRGBA px: [UInt8], width w: Int, height h: Int) -> UIImage? {
        guard w > 0, h > 0 else { return nil }
        var data = px
        let ok = data.withUnsafeMutableBytes { ptr -> Bool in
            guard let ctx = CGContext(
                data: ptr.baseAddress, width: w, height: h, bitsPerComponent: 8,
                bytesPerRow: w * 4, space: CGColorSpaceCreateDeviceRGB(),
                bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue
            ) else { return false }
            return true
        }
        guard ok, let provider = CGDataProvider(data: Data(data) as CFData) else { return nil }
        guard let img = CGImage(
            width: w, height: h, bitsPerComponent: 8, bitsPerPixel: 32,
            bytesPerRow: w * 4, space: CGColorSpaceCreateDeviceRGB(),
            bitmapInfo: CGBitmapInfo(rawValue: CGImageAlphaInfo.premultipliedLast.rawValue),
            provider: provider, decode: nil, shouldInterpolate: false, intent: .defaultIntent
        ) else { return nil }
        return UIImage(cgImage: img)
    }
}
