import Foundation
import CoreGraphics
import CoreImage
import CoreImage.CIFilterBuiltins
import ImageIO

/// GPU image pipeline shared by the reader.
///
/// The Android build uses hand written GLES compute shaders; here the equivalent work is
/// done with Core Image, which already runs on the GPU through Metal. Sharpen, denoise,
/// contrast and gamma map onto stock CI filters, so there is no custom shader to maintain.
enum ImageEnhancer {

    private static let context = CIContext(options: [
        .useSoftwareRenderer: false,
        .cacheIntermediates: false,
    ])

    // MARK: - Public

    static func process(
        _ image: CGImage,
        settings: EnhanceSettings,
        autoCrop: Bool,
        half: Int,
        rightToLeft: Bool
    ) -> CGImage? {
        // Detect the ink box on the untouched page: filters can lift faint borders.
        let box = autoCrop ? contentBox(image) : nil

        var result = applyFilters(image, settings: settings) ?? image

        if let box, let cropped = crop(result, normalized: box) {
            result = cropped
        }
        if half != 0, let split = cropHalf(result, half: half, rightToLeft: rightToLeft) {
            result = split
        }
        return result
    }

    // MARK: - Filters

    private static func applyFilters(_ image: CGImage, settings: EnhanceSettings) -> CGImage? {
        let needsColor = settings.brightness != 100
            || settings.contrastLevel != 100
            || settings.contrastPass
        let needsGamma = settings.gamma != 100
        guard settings.sharpen || settings.denoise || needsColor || needsGamma else {
            return image
        }

        var ci = CIImage(cgImage: image)

        if settings.denoise {
            let filter = CIFilter.noiseReduction()
            filter.inputImage = ci
            filter.noiseLevel = Float(0.02 + settings.denoiseBlend / 100.0 * 0.10)
            filter.sharpness = 0.4
            if let out = filter.outputImage { ci = out }
        }

        if settings.sharpen {
            let filter = CIFilter.sharpenLuminance()
            filter.inputImage = ci
            filter.sharpness = settings.sharpenF * 0.55
            filter.radius = 1.6
            if let out = filter.outputImage { ci = out }
        }

        if needsColor {
            let filter = CIFilter.colorControls()
            filter.inputImage = ci
            filter.brightness = Float((settings.brightness - 100) / 100.0 * 0.55)
            filter.contrast = Float(settings.contrastLevel / 100.0)
            filter.saturation = 1.0
            if let out = filter.outputImage { ci = out }
        }

        if settings.contrastPass {
            // An S-curve style lift: mid contrast plus a gentle black point.
            let filter = CIFilter.toneCurve()
            filter.inputImage = ci
            let blend = settings.contrastBlendF
            filter.point0 = CGPoint(x: 0, y: CGFloat(0.02 * blend))
            filter.point1 = CGPoint(x: 0.25, y: CGFloat(0.25 - 0.05 * blend))
            filter.point2 = CGPoint(x: 0.5, y: 0.5)
            filter.point3 = CGPoint(x: 0.75, y: CGFloat(0.75 + 0.05 * blend))
            filter.point4 = CGPoint(x: 1, y: 1)
            if let out = filter.outputImage { ci = out }
        }

        if needsGamma {
            let filter = CIFilter.gammaAdjust()
            filter.inputImage = ci
            filter.power = Float(1.0 / max(0.05, settings.gamma / 100.0))
            if let out = filter.outputImage { ci = out }
        }

        let rect = ci.extent.integral
        guard rect.width >= 1, rect.height >= 1 else { return image }
        return context.createCGImage(ci, from: rect)
    }

    // MARK: - Cropping

    private static func crop(_ image: CGImage, normalized box: CGRect) -> CGImage? {
        let width = CGFloat(image.width)
        let height = CGFloat(image.height)
        var rect = CGRect(
            x: (box.minX * width).rounded(),
            y: (box.minY * height).rounded(),
            width: (box.width * width).rounded(),
            height: (box.height * height).rounded()
        )
        rect = rect.intersection(CGRect(x: 0, y: 0, width: width, height: height))
        guard rect.width >= 16, rect.height >= 16 else { return nil }
        guard rect.width < width - 2 || rect.height < height - 2 else { return nil }
        return image.cropping(to: rect)
    }

    private static func cropHalf(_ image: CGImage, half: Int, rightToLeft: Bool) -> CGImage? {
        let width = CGFloat(image.width)
        let height = CGFloat(image.height)
        let leftFirst = half == 1
        let takeLeft = rightToLeft ? !leftFirst : leftFirst
        let rect = CGRect(
            x: takeLeft ? 0 : (width / 2).rounded(.down),
            y: 0,
            width: (width / 2).rounded(.up),
            height: height
        )
        return image.cropping(to: rect)
    }

    // MARK: - White border detection

    /// Non-white content box in normalized coordinates with the origin at the top left,
    /// or nil when the page is already tight.
    static func contentBox(_ image: CGImage, threshold: Int = 238) -> CGRect? {
        guard image.width > 0, image.height > 0 else { return nil }
        let w = 80
        let h = max(8, Int(Double(image.height) / Double(image.width) * Double(w)))

        var buffer = [UInt8](repeating: 255, count: w * h * 4)
        let drawn = buffer.withUnsafeMutableBytes { raw -> Bool in
            guard let ctx = CGContext(
                data: raw.baseAddress,
                width: w,
                height: h,
                bitsPerComponent: 8,
                bytesPerRow: w * 4,
                space: CGColorSpaceCreateDeviceRGB(),
                bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue
            ) else { return false }
            ctx.interpolationQuality = .low
            ctx.draw(image, in: CGRect(x: 0, y: 0, width: w, height: h))
            return true
        }
        guard drawn else { return nil }

        var rowInk = [Int](repeating: 0, count: h)
        var colInk = [Int](repeating: 0, count: w)
        var total = 0
        for y in 0..<h {
            for x in 0..<w {
                let p = (y * w + x) * 4
                let r = Int(buffer[p])
                let g = Int(buffer[p + 1])
                let b = Int(buffer[p + 2])
                if (r + g + b) / 3 < threshold {
                    rowInk[y] += 1
                    colInk[x] += 1
                    total += 1
                }
            }
        }
        guard total > 0 else { return nil }

        let rowMin = max(1, Int(Double(w) * 0.004))
        let colMin = max(1, Int(Double(h) * 0.004))

        var top = 0
        while top < h - 1 && rowInk[top] < rowMin { top += 1 }
        var bottom = h - 1
        while bottom > top && rowInk[bottom] < rowMin { bottom -= 1 }
        var left = 0
        while left < w - 1 && colInk[left] < colMin { left += 1 }
        var right = w - 1
        while right > left && colInk[right] < colMin { right -= 1 }

        guard top < bottom, left < right else { return nil }

        let pad: CGFloat = 0.004
        let rect = CGRect(
            x: max(0, CGFloat(left) / CGFloat(w) - pad),
            y: max(0, CGFloat(top) / CGFloat(h) - pad),
            width: min(1, CGFloat(right + 1) / CGFloat(w) + pad) - max(0, CGFloat(left) / CGFloat(w) - pad),
            height: min(1, CGFloat(bottom + 1) / CGFloat(h) + pad) - max(0, CGFloat(top) / CGFloat(h) - pad)
        )
        // Nothing meaningful to trim.
        if rect.width * rect.height > 0.96 { return nil }
        return rect
    }
}
