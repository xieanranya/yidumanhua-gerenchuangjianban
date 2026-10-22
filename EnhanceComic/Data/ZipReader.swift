import Foundation
import Compression

struct ZipEntry: Hashable {
    let name: String
    let method: UInt16
    let compressedSize: Int
    let uncompressedSize: Int
    let localOffset: Int
}

enum ZipError: Error {
    case invalidArchive
    case unsupportedMethod
    case decompressFailed
}

/// Minimal read-only ZIP reader: central directory scan + raw deflate inflate.
/// Keeps the whole archive in memory mapped storage, which is fine for comics.
final class ZipReader {

    private let data: Data
    private(set) var entries: [ZipEntry] = []

    init(url: URL) throws {
        data = try Data(contentsOf: url, options: [.mappedIfSafe])
        try parse()
    }

    func data(for entry: ZipEntry) -> Data? {
        var result: Data?
        data.withUnsafeBytes { (raw: UnsafeRawBufferPointer) in
            guard let base = raw.baseAddress else { return }
            guard entry.localOffset >= 0, entry.localOffset + 30 <= raw.count else { return }
            guard read32(base, entry.localOffset) == 0x0403_4B50 else { return }

            let nameLen = Int(read16(base, entry.localOffset + 26))
            let extraLen = Int(read16(base, entry.localOffset + 28))
            let start = entry.localOffset + 30 + nameLen + extraLen
            let end = start + entry.compressedSize
            guard start >= 0, end <= raw.count, end > start else { return }

            let compressed = Data(bytes: base.advanced(by: start), count: end - start)
            if entry.method == 0 {
                result = compressed
                return
            }
            guard entry.method == 8 else { return }
            result = ZipReader.inflate(compressed, expected: entry.uncompressedSize)
        }
        return result
    }

    // MARK: - Parsing

    private func parse() throws {
        try data.withUnsafeBytes { (raw: UnsafeRawBufferPointer) in
            guard let base = raw.baseAddress, raw.count > 22 else {
                throw ZipError.invalidArchive
            }
            let count = raw.count

            var eocd = -1
            var i = count - 22
            let lower = max(0, count - 22 - 65535)
            while i >= lower {
                if read32(base, i) == 0x0605_4B50 {
                    eocd = i
                    break
                }
                i -= 1
            }
            guard eocd >= 0 else { throw ZipError.invalidArchive }

            let entryCount = Int(read16(base, eocd + 10))
            var p = Int(read32(base, eocd + 16))
            guard p > 0, p + 46 <= count else { throw ZipError.invalidArchive }

            var result: [ZipEntry] = []
            result.reserveCapacity(entryCount)

            for _ in 0..<entryCount {
                guard p + 46 <= count, read32(base, p) == 0x0201_4B50 else { break }
                let method = read16(base, p + 10)
                let csize = Int(read32(base, p + 20))
                let usize = Int(read32(base, p + 24))
                let nameLen = Int(read16(base, p + 28))
                let extraLen = Int(read16(base, p + 30))
                let commentLen = Int(read16(base, p + 32))
                let localOffset = Int(read32(base, p + 42))

                if p + 46 + nameLen <= count {
                    let nameBytes = UnsafeRawBufferPointer(
                        rebasing: raw[(p + 46)..<(p + 46 + nameLen)]
                    )
                    let name = ZipReader.decodeName(nameBytes)
                    if !name.isEmpty {
                        result.append(
                            ZipEntry(
                                name: name,
                                method: method,
                                compressedSize: csize,
                                uncompressedSize: usize,
                                localOffset: localOffset
                            )
                        )
                    }
                }
                p += 46 + nameLen + extraLen + commentLen
            }

            entries = result
        }
    }

    private func read16(_ base: UnsafeRawPointer, _ offset: Int) -> UInt16 {
        UInt16(base.load(fromByteOffset: offset, as: UInt8.self))
            | (UInt16(base.load(fromByteOffset: offset + 1, as: UInt8.self)) << 8)
    }

    private func read32(_ base: UnsafeRawPointer, _ offset: Int) -> UInt32 {
        UInt32(base.load(fromByteOffset: offset, as: UInt8.self))
            | (UInt32(base.load(fromByteOffset: offset + 1, as: UInt8.self)) << 8)
            | (UInt32(base.load(fromByteOffset: offset + 2, as: UInt8.self)) << 16)
            | (UInt32(base.load(fromByteOffset: offset + 3, as: UInt8.self)) << 24)
    }

    private static func decodeName(_ bytes: UnsafeRawBufferPointer) -> String {
        let raw = Data(bytes)
        if let s = String(data: raw, encoding: .utf8), !s.isEmpty { return s }
        let gb = CFStringConvertEncodingToNSStringEncoding(
            CFStringEncoding(CFStringEncodings.GB_18030_2000.rawValue)
        )
        if let s = String(data: raw, encoding: String.Encoding(rawValue: gb)), !s.isEmpty {
            return s
        }
        return String(decoding: bytes, as: UTF8.self)
    }

    // MARK: - Inflate

    private static func inflate(_ input: Data, expected: Int) -> Data? {
        guard expected > 0 else { return Data() }
        for header in [UInt8(0x9C), UInt8(0x01), UInt8(0xDA)] {
            var zlib = Data([0x78, header])
            zlib.append(input)
            var out = Data(count: expected)
            let written = out.withUnsafeMutableBytes { dst -> Int in
                zlib.withUnsafeBytes { src -> Int in
                    guard let d = dst.bindMemory(to: UInt8.self).baseAddress,
                          let s = src.bindMemory(to: UInt8.self).baseAddress
                    else { return 0 }
                    return compression_decode_buffer(
                        d, expected, s, zlib.count, nil, COMPRESSION_ZLIB
                    )
                }
            }
            if written > 0 { return out.prefix(written) }
        }
        return nil
    }
}
