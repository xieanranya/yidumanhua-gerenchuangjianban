import Foundation

struct ZipEntry: Hashable {
    let name: String
    let method: UInt16
    let compressedSize: Int
    let uncompressedSize: Int
    let localOffset: Int
    let flags: UInt16
}

enum ZipError: Error {
    case invalidArchive
    case unsupportedMethod
    case encrypted
    case decompressFailed
}

/// Read-only ZIP reader: central directory scan + deflate via `Inflate`.
///
/// Deliberately avoids the system `Compression` framework. That API only accepts the zlib
/// container and caps its output at the size passed in, so an archive that declares "0" or
/// a stale uncompressed size produced pages that never decoded — the reader sat on a
/// spinner. Everything a stock `java.util.zip.ZipFile` handles is handled here: ZIP64,
/// stored and deflated entries, sizes only present in a trailing data descriptor, and
/// GB18030 file names.
final class ZipReader {

    private let data: Data
    private(set) var entries: [ZipEntry] = []

    private var centralDirectoryOffset = 0
    private var localOffsets: [Int] = []

    init(url: URL) throws {
        data = try Data(contentsOf: url, options: [.mappedIfSafe])
        try parse()
    }

    /// Bytes of one entry, decompressed. Only the first `limit` bytes are produced when a
    /// limit is given, which is how page headers get measured without inflating whole pages.
    func data(for entry: ZipEntry, limit: Int = Int.max) -> Data? {
        var result: Data?
        data.withUnsafeBytes { (raw: UnsafeRawBufferPointer) -> Void in
            guard let base = raw.baseAddress?.assumingMemoryBound(to: UInt8.self) else { return }
            guard let range = payloadRange(entry, base: base, count: raw.count) else { return }

            if entry.method == 0 {
                var end = range.upperBound
                if limit < Int.max { end = Swift.min(end, range.lowerBound + limit) }
                guard end > range.lowerBound else { return }
                result = Data(bytes: base.advanced(by: range.lowerBound), count: end - range.lowerBound)
                return
            }
            guard entry.method == 8 else { return }
            result = Inflate.raw(
                base.advanced(by: range.lowerBound),
                count: range.count,
                hint: entry.uncompressedSize,
                limit: limit
            )
        }
        return result
    }

    // MARK: - Parsing

    private func parse() throws {
        try data.withUnsafeBytes { (raw: UnsafeRawBufferPointer) throws -> Void in
            guard let base = raw.baseAddress?.assumingMemoryBound(to: UInt8.self), raw.count > 22 else {
                throw ZipError.invalidArchive
            }
            let total = raw.count

            var eocd = -1
            var i = total - 22
            let lower = max(0, total - 22 - 65_535)
            while i >= lower {
                if read32(base, i) == 0x0605_4B50 {
                    let commentLength = Int(read16(base, i + 20))
                    if i + 22 + commentLength <= total {
                        eocd = i
                        break
                    }
                }
                i -= 1
            }
            guard eocd >= 0 else { throw ZipError.invalidArchive }

            var entryCount = Int(read16(base, eocd + 10))
            var directoryOffset = Int(read32(base, eocd + 16))

            // ZIP64 end-of-central-directory, when the archive needed one.
            if eocd >= 20, read32(base, eocd - 20) == 0x0706_4B50 {
                let zip64 = Int(truncatingIfNeeded: read64(base, eocd - 20 + 8))
                if zip64 >= 0, zip64 + 56 <= total, read32(base, zip64) == 0x0606_4B50 {
                    entryCount = Int(truncatingIfNeeded: read64(base, zip64 + 32))
                    directoryOffset = Int(truncatingIfNeeded: read64(base, zip64 + 48))
                }
            }

            guard directoryOffset > 0, directoryOffset + 46 <= total else {
                throw ZipError.invalidArchive
            }
            centralDirectoryOffset = directoryOffset

            var parsed: [ZipEntry] = []
            parsed.reserveCapacity(min(entryCount, 1 << 16))
            var p = directoryOffset

            for _ in 0..<entryCount {
                guard p + 46 <= total, read32(base, p) == 0x0201_4B50 else { break }
                let flags = read16(base, p + 8)
                let method = read16(base, p + 10)
                var compressed = Int(read32(base, p + 20))
                var uncompressed = Int(read32(base, p + 24))
                let nameLength = Int(read16(base, p + 28))
                let extraLength = Int(read16(base, p + 30))
                let commentLength = Int(read16(base, p + 32))
                var localOffset = Int(read32(base, p + 42))

                let nameStart = p + 46
                guard nameStart + nameLength <= total else { break }

                let saturated = 0xFFFF_FFFF
                if uncompressed == saturated || compressed == saturated || localOffset == saturated {
                    let extraStart = nameStart + nameLength
                    let extraEnd = min(extraStart + extraLength, total)
                    var q = extraStart
                    while q + 4 <= extraEnd {
                        let tag = read16(base, q)
                        let size = Int(read16(base, q + 2))
                        if tag == 0x0001 {
                            var r = q + 4
                            if uncompressed == saturated, r + 8 <= extraEnd {
                                uncompressed = Int(truncatingIfNeeded: read64(base, r))
                                r += 8
                            }
                            if compressed == saturated, r + 8 <= extraEnd {
                                compressed = Int(truncatingIfNeeded: read64(base, r))
                                r += 8
                            }
                            if localOffset == saturated, r + 8 <= extraEnd {
                                localOffset = Int(truncatingIfNeeded: read64(base, r))
                                r += 8
                            }
                            break
                        }
                        q += 4 + size
                    }
                }

                let nameBytes = UnsafeRawBufferPointer(
                    rebasing: raw[nameStart..<(nameStart + nameLength)]
                )
                let name = ZipReader.decodeName(nameBytes, utf8Flag: flags & 0x0800 != 0)
                if !name.isEmpty, !name.hasSuffix("/") {
                    parsed.append(
                        ZipEntry(
                            name: name,
                            method: method,
                            compressedSize: compressed,
                            uncompressedSize: uncompressed,
                            localOffset: localOffset,
                            flags: flags
                        )
                    )
                }
                p = nameStart + nameLength + extraLength + commentLength
            }

            guard !parsed.isEmpty else { throw ZipError.invalidArchive }
            entries = parsed
            localOffsets = parsed.map { $0.localOffset }.sorted()
        }
    }

    /// Where the entry's compressed bytes live. Sizes of `0` mean the writer left them in a
    /// data descriptor, so the payload is bounded by the next local header instead.
    private func payloadRange(
        _ entry: ZipEntry,
        base: UnsafePointer<UInt8>,
        count: Int
    ) -> Range<Int>? {
        guard entry.flags & 0x0001 == 0 else { return nil }  // encrypted
        let offset = entry.localOffset
        guard offset >= 0, offset + 30 <= count, read32(base, offset) == 0x0403_4B50 else {
            return nil
        }
        let nameLength = Int(read16(base, offset + 26))
        let extraLength = Int(read16(base, offset + 28))
        let start = offset + 30 + nameLength + extraLength
        guard start > 0, start <= count else { return nil }

        var size = entry.compressedSize
        if size <= 0 { size = endBound(for: offset) - start }
        guard size > 0 else { return nil }

        let end = start + size
        guard end <= count, end > start else { return nil }
        return start..<end
    }

    private func endBound(for offset: Int) -> Int {
        var low = 0
        var high = localOffsets.count
        while low < high {
            let mid = (low + high) / 2
            if localOffsets[mid] <= offset { low = mid + 1 } else { high = mid }
        }
        return low < localOffsets.count ? localOffsets[low] : centralDirectoryOffset
    }

    // MARK: - Little-endian helpers

    private func read16(_ base: UnsafePointer<UInt8>, _ offset: Int) -> UInt16 {
        UInt16(base[offset]) | (UInt16(base[offset + 1]) << 8)
    }

    private func read32(_ base: UnsafePointer<UInt8>, _ offset: Int) -> UInt32 {
        UInt32(base[offset])
            | (UInt32(base[offset + 1]) << 8)
            | (UInt32(base[offset + 2]) << 16)
            | (UInt32(base[offset + 3]) << 24)
    }

    private func read64(_ base: UnsafePointer<UInt8>, _ offset: Int) -> UInt64 {
        var value: UInt64 = 0
        for i in 0..<8 {
            value |= UInt64(base[offset + i]) << UInt64(i * 8)
        }
        return value
    }

    /// ZIP names are UTF-8 only when the language-encoding flag is set; plenty of Chinese
    /// comic archives carry GBK bytes instead, and some carry UTF-8 without setting it.
    private static func decodeName(_ bytes: UnsafeRawBufferPointer, utf8Flag: Bool) -> String {
        let raw = Data(bytes)
        guard !raw.isEmpty else { return "" }
        if utf8Flag, let name = String(data: raw, encoding: .utf8) {
            return name
        }
        if let name = String(data: raw, encoding: .utf8) {
            return name
        }
        let gb = CFStringConvertEncodingToNSStringEncoding(
            CFStringEncoding(CFStringEncodings.GB_18030_2000.rawValue)
        )
        if let name = String(data: raw, encoding: String.Encoding(rawValue: gb)) {
            return name
        }
        return String(decoding: bytes, as: UTF8.self)
    }
}
