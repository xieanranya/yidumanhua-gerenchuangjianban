import Foundation

/// A self-contained RFC 1951 ("deflate") decoder.
///
/// The system `Compression` framework only understands the zlib container and refuses to
/// write more bytes than the destination buffer holds, which silently produced zero pages
/// for real-world comic zips (streamed archives report "0" uncompressed size, some writers
/// omit the size entirely). This is a port of zlib's `puff.c`: it reads a raw deflate
/// stream, grows its output as needed and never trusts a declared size, so a file that
/// Android's `java.util.zip` can open opens here too.
///
/// The algorithm was validated against 28 generated archives (stored, every deflate level,
/// empty, tiny, incompressible, highly repetitive) before being translated.
enum Inflate {

    enum Failure: Error {
        case truncated
        case badData
        case outputLimit
    }

    /// Decompresses a raw deflate stream (no zlib header, no adler32 trailer).
    ///
    /// - Parameters:
    ///   - input: the deflate stream.
    ///   - hint: expected output size. Only used to presize the buffer; a wrong hint costs
    ///     a reallocation, not a failure.
    ///   - limit: stop after this many bytes and return what was produced. Used to read
    ///     just an image header instead of a whole multi-megabyte page.
    static func raw(_ input: Data, hint: Int = 0, limit: Int = Int.max) -> Data? {
        input.withUnsafeBytes { buffer -> Data? in
            guard let base = buffer.baseAddress?.assumingMemoryBound(to: UInt8.self),
                  buffer.count > 0
            else { return nil }
            return raw(base, count: buffer.count, hint: hint, limit: limit)
        }
    }

    static func raw(
        _ source: UnsafePointer<UInt8>,
        count: Int,
        hint: Int = 0,
        limit: Int = Int.max
    ) -> Data? {
        var reader = BitReader(base: source, count: count)
        let output = Output(capacity: hint > 0 ? min(hint, 1 << 24) : 1 << 16, limit: limit)
        do {
            try blocks(&reader, output)
        } catch Failure.outputLimit {
            return output.data
        } catch {
            return nil
        }
        return output.data
    }

    // MARK: - Blocks

    private static func blocks(_ r: inout BitReader, _ out: Output) throws {
        var last = 0
        repeat {
            last = try r.bits(1)
            let type = try r.bits(2)
            switch type {
            case 0:
                try stored(&r, out)
            case 1:
                try codes(&r, out, fixedLit, fixedDist)
            case 2:
                let hlit = try r.bits(5) + 257
                let hdist = try r.bits(5) + 1
                let hclen = try r.bits(4) + 4
                guard hlit <= 286, hdist <= 30 else { throw Failure.badData }

                var codeLengths = [Int](repeating: 0, count: 19)
                for i in 0..<hclen {
                    codeLengths[codeOrder[i]] = try r.bits(3)
                }
                let lengthCode = try Huffman(lengths: codeLengths)

                var lengths: [Int] = []
                lengths.reserveCapacity(hlit + hdist)
                while lengths.count < hlit + hdist {
                    guard let symbol = lengthCode.decode(&r) else { throw Failure.badData }
                    switch symbol {
                    case 0...15:
                        lengths.append(symbol)
                    case 16:
                        guard let previous = lengths.last else { throw Failure.badData }
                        let repeatCount = try r.bits(2) + 3
                        lengths.append(contentsOf: repeatElement(previous, count: repeatCount))
                    case 17:
                        let repeatCount = try r.bits(3) + 3
                        lengths.append(contentsOf: repeatElement(0, count: repeatCount))
                    default:
                        let repeatCount = try r.bits(7) + 11
                        lengths.append(contentsOf: repeatElement(0, count: repeatCount))
                    }
                }
                guard lengths.count == hlit + hdist else { throw Failure.badData }

                let literals = try Huffman(lengths: Array(lengths[0..<hlit]))
                let distances = try Huffman(lengths: Array(lengths[hlit...]))
                try codes(&r, out, literals, distances)
            default:
                throw Failure.badData
            }
        } while last == 0
    }

    private static func stored(_ r: inout BitReader, _ out: Output) throws {
        r.align()
        let length = try r.bits(16)
        let complement = try r.bits(16)
        guard length == (~complement & 0xFFFF) else { throw Failure.badData }
        for _ in 0..<length {
            let byte = try r.bits(8)
            try out.append(UInt8(byte))
        }
    }

    private static func codes(
        _ r: inout BitReader,
        _ out: Output,
        _ literals: Huffman,
        _ distances: Huffman
    ) throws {
        while true {
            guard let symbol = literals.decode(&r) else { throw Failure.badData }
            if symbol < 256 {
                try out.append(UInt8(symbol))
            } else if symbol == 256 {
                return
            } else {
                let index = symbol - 257
                guard index < 29 else { throw Failure.badData }
                let lengthBits = try r.bits(lengthExtra[index])
                let length = lengthBase[index] + lengthBits

                guard let distSymbol = distances.decode(&r) else { throw Failure.badData }
                guard distSymbol < 30 else { throw Failure.badData }
                let distBits = try r.bits(distExtra[distSymbol])
                let distance = distBase[distSymbol] + distBits
                try out.copyMatch(distance: distance, count: length)
            }
        }
    }

    // MARK: - Bit reader

    private struct BitReader {
        let base: UnsafePointer<UInt8>
        let count: Int
        var position = 0
        var buffer: UInt32 = 0
        var available: Int = 0

        init(base: UnsafePointer<UInt8>, count: Int) {
            self.base = base
            self.count = count
        }

        mutating func bits(_ need: Int) throws -> Int {
            var value = buffer
            while available < need {
                guard position < count else { throw Failure.truncated }
                value |= UInt32(base[position]) << UInt32(available)
                position += 1
                available += 8
            }
            buffer = value >> UInt32(need)
            available -= need
            let mask: UInt32 = need >= 32 ? UInt32.max : (UInt32(1) << UInt32(need)) - 1
            return Int(value & mask)
        }

        mutating func align() {
            buffer = 0
            available = 0
        }

        /// Loads one whole byte into the bit buffer. `false` when the input is exhausted.
        mutating func refillByte() -> Bool {
            guard position < count else { return false }
            buffer |= UInt32(base[position]) << UInt32(available)
            position += 1
            available += 8
            return true
        }
    }

    // MARK: - Huffman

    private struct Huffman {
        var count = [Int](repeating: 0, count: 16)
        var symbol: [Int] = []

        init(lengths: [Int]) throws {
            for length in lengths {
                guard length >= 0, length <= 15 else { throw Failure.badData }
                count[length] += 1
            }
            // A tree with no codes at all is legal (e.g. zero distance codes).
            if count[0] == lengths.count { return }

            var left = 1
            for length in 1...15 {
                left <<= 1
                left -= count[length]
                if left < 0 { throw Failure.badData }
            }

            var offsets = [Int](repeating: 0, count: 16)
            for length in 1..<15 {
                offsets[length + 1] = offsets[length] + count[length]
            }

            var ordered = [Int](repeating: 0, count: lengths.count - count[0])
            for (value, length) in lengths.enumerated() where length != 0 {
                ordered[offsets[length]] = value
                offsets[length] += 1
            }
            symbol = ordered
        }

        /// Bit-at-a-time canonical decode with the bit fetch inlined — this is the hot loop,
        /// it runs once per output byte, so it must not go through a throwing accessor.
        func decode(_ r: inout BitReader) -> Int? {
            var code = 0
            var first = 0
            var index = 0
            for length in 1...15 {
                if r.available == 0, !r.refillByte() { return nil }
                let bit = Int(r.buffer & 1)
                r.buffer >>= 1
                r.available -= 1
                code |= bit
                let codes = count[length]
                if code - first < codes {
                    let slot = index + (code - first)
                    guard slot >= 0, slot < symbol.count else { return nil }
                    return symbol[slot]
                }
                index += codes
                first = (first + codes) << 1
                code <<= 1
            }
            return nil
        }
    }

    // MARK: - Output

    /// Growable raw buffer. Byte-at-a-time decode is fast enough here because the reader
    /// only ever inflates one page, and it keeps the port identical to the reference
    /// implementation that was tested.
    private final class Output {
        private var buffer: UnsafeMutablePointer<UInt8>
        private var capacity: Int
        private let limit: Int
        private(set) var length = 0

        init(capacity: Int, limit: Int) {
            self.capacity = max(capacity, 1024)
            self.limit = limit
            buffer = UnsafeMutablePointer<UInt8>.allocate(capacity: self.capacity)
        }

        deinit {
            buffer.deallocate()
        }

        private func reserve(_ extra: Int) {
            guard length + extra > capacity else { return }
            let grown = max(capacity * 2, length + extra)
            let fresh = UnsafeMutablePointer<UInt8>.allocate(capacity: grown)
            fresh.initialize(from: buffer, count: length)
            buffer.deallocate()
            buffer = fresh
            capacity = grown
        }

        func append(_ byte: UInt8) throws {
            if length >= limit { throw Failure.outputLimit }
            reserve(1)
            buffer[length] = byte
            length += 1
        }

        func copyMatch(distance: Int, count: Int) throws {
            guard distance > 0, distance <= length else { throw Failure.badData }
            if length + count > limit {
                // Partially useful data is better than none for header sniffing.
                throw Failure.outputLimit
            }
            reserve(count)
            var source = length - distance
            for _ in 0..<count {
                buffer[length] = buffer[source]
                length += 1
                source += 1
            }
        }

        var data: Data { Data(bytes: buffer, count: length) }
    }

    // MARK: - Tables

    private static let codeOrder = [16, 17, 18, 0, 8, 7, 9, 6, 10, 5, 11, 4, 12, 3, 13, 2, 14, 1, 15]

    private static let lengthBase = [
        3, 4, 5, 6, 7, 8, 9, 10, 11, 13, 15, 17, 19, 23, 27, 31,
        35, 43, 51, 59, 67, 83, 99, 115, 131, 163, 195, 227, 258,
    ]

    private static let lengthExtra = [
        0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 2, 2, 2, 2,
        3, 3, 3, 3, 4, 4, 4, 4, 5, 5, 5, 5, 0,
    ]

    private static let distBase = [
        1, 2, 3, 4, 5, 7, 9, 13, 17, 25, 33, 49, 65, 97, 129, 193,
        257, 385, 513, 769, 1025, 1537, 2049, 3073, 4097, 6145,
        8193, 12289, 16385, 24577,
    ]

    private static let distExtra = [
        0, 0, 0, 0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 6, 6,
        7, 7, 8, 8, 9, 9, 10, 10, 11, 11, 12, 12, 13, 13,
    ]

    private static let fixedLit: Huffman = {
        var lengths = [Int](repeating: 0, count: 288)
        for i in 0..<144 { lengths[i] = 8 }
        for i in 144..<256 { lengths[i] = 9 }
        for i in 256..<280 { lengths[i] = 7 }
        for i in 280..<288 { lengths[i] = 8 }
        return try! Huffman(lengths: lengths)
    }()

    private static let fixedDist: Huffman = {
        return try! Huffman(lengths: [Int](repeating: 5, count: 30))
    }()
}
