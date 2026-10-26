import Foundation
import JavaScriptCore
import CommonCrypto
import UIKit

/// 漫画源引擎（iOS 侧宿主）
///
/// 源是 JS 脚本，跑在系统自带的 JavaScriptCore 里（不引第三方解释器）。
/// 桥的关键是**同步**：源脚本里 `sendMessage({method:'http'})` 是同步调用，
/// 所以给 JSC 注入一个 `@convention(block)` 的 `__hostCall`，它在 JS 线程上同步返回。
///
/// 一个坑：JSC 的 Promise 微任务不会自己跑，必须反复 evaluateScript 才能排空，
/// 所以等待结果时在同一个串行队列上泵一下（pump）。
final class SourceEngine {
    static let shared = SourceEngine()

    private let queue = DispatchQueue(label: "com.enhance.comic.source-engine")
    private var ctx: JSContext?
    private var booted = false
    private var events: [String: [String: Any]] = [:]
    private let defaults = UserDefaults.standard

    private init() {}

    private struct HostError: Error, CustomStringConvertible {
        let msg: String
        var description: String { msg }
    }

    // MARK: - 启动

    func start() async -> Bool {
        await withCheckedContinuation { cont in
            queue.async { cont.resume(returning: self.bootLocked()) }
        }
    }

    private func bootLocked() -> Bool {
        if booted { return true }
        guard let js = JSContext() else { return false }
        js.exceptionHandler = { _, err in
            NSLog("[source] JS 异常: %@", err?.toString() ?? "?")
        }

        // 同步桥：JS -> 原生。原生只负责“返回一段 JSON 文本”，
        // 拆包装（{v:...} / __err / bytes）交给下面注入的 JS 前置做——
        // 跟安卓侧 HOSTCALL_PRELUDE 完全一致，别在这里返回解好的对象：
        // 之前就是在原生这层直接回了 {v:...}，JS 那边当成结果用，
        // 于是 res.status 变 undefined，而 !!{v:null} 恒为真（所有源都显示已登录）。
        let hostRaw: @convention(block) (JSValue?) -> JSValue? = { [weak js] msg in
            guard let js else { return nil }
            let json = msg?.toString() ?? "{}"
            let out = self.handleHostCall(json)
            return JSValue(object: out, in: js)
        }
        js.setObject(hostRaw, forKeyedSubscript: "__hostRaw" as NSString)

        // 异步事件：任务结果回传
        let hostEvent: @convention(block) (JSValue?) -> Void = { [weak self] o in
            guard let self, let dict = o?.toDictionary() as? [String: Any] else { return }
            let id = dict["id"] as? String ?? ""
            guard !id.isEmpty else { return }
            if id == "route_state" {
                if let data = dict["data"], JSONSerialization.isValidJSONObject(data),
                   let raw = try? JSONSerialization.data(withJSONObject: data) {
                    self.defaults.set(String(data: raw, encoding: .utf8), forKey: "source_route_state")
                }
                return
            }
            self.events[id] = dict
        }
        js.setObject(hostEvent, forKeyedSubscript: "__hostEvent" as NSString)

        guard let bundleURL = resourceURL("bundle", "js"),
              let source = try? String(contentsOf: bundleURL, encoding: .utf8) else {
            NSLog("[source] 找不到 bundle.js")
            return false
        }
        js.evaluateScript(source)
        js.evaluateScript(Self.hostBridgeJS)
        js.evaluateScript("SourceEngine.setEventSink(function(o){ __hostEvent(o); })")

        js.evaluateScript("SourceEngine.init(\(jsLiteral(routesJson())), \(jsLiteral(defaults.string(forKey: "source_route_state"))))")
        js.evaluateScript("SourceEngine.boot()")

        ctx = js
        booted = true
        return true
    }

    /// JSC 里没有 window / console。桥的拆包装与二进制转换都在引擎 prelude 里做（两端共用），
    /// 这里只补 JSC 缺的全局对象。
    private static let hostBridgeJS = """
    (function () {
      var g = (typeof globalThis !== 'undefined') ? globalThis : this;
      if (typeof g.console === 'undefined') {
        g.console = { log: function () {}, error: function () {}, warn: function () {}, info: function () {} };
      }
    })();
    """

    /// 资源可能在 bundle 根目录，也可能被保留在 Resources/ 子目录里（看 Xcode 怎么同步的）
    private func resourceURL(_ name: String, _ ext: String) -> URL? {
        Bundle.main.url(forResource: name, withExtension: ext)
            ?? Bundle.main.url(forResource: name, withExtension: ext, subdirectory: "Resources")
    }

    private func routesJson() -> String {
        guard let url = resourceURL("routes", "json"),
              let s = try? String(contentsOf: url, encoding: .utf8) else {
            return "{\"sources\":[]}"
        }
        return s
    }

    /// Swift 字符串 -> 安全的 JS 字面量
    private func jsLiteral(_ s: String?) -> String {
        guard let s else { return "null" }
        guard let data = try? JSONSerialization.data(withJSONObject: [s]),
              let arr = String(data: data, encoding: .utf8) else { return "null" }
        return String(arr.dropFirst().dropLast())
    }

    // MARK: - 对外 API

    func sources() async -> [[String: Any]] {
        (await syncCall("sources") as? [[String: Any]]) ?? []
    }

    func search(key: String, keyword: String, options: String, page: Int) async -> [String: Any] {
        await asyncCall("search", [jsLiteral(key), jsLiteral(keyword), jsLiteral(options), "\(page)"])
    }

    func explore(key: String, index: Int = 0) async -> [String: Any] {
        await asyncCall("explore", [jsLiteral(key), "\(index)"])
    }

    func detail(key: String, id: String) async -> [String: Any] {
        await asyncCall("detail", [jsLiteral(key), jsLiteral(id)])
    }

    func pages(key: String, comicId: String, epId: String) async -> [String: Any] {
        await asyncCall("pages", [jsLiteral(key), jsLiteral(comicId), jsLiteral(epId)])
    }

    /// 惰性取图：解第 index 页的真地址（E-Hentai 这类一页一次 API 的源用）
    func pageImage(key: String, comicId: String, epId: String, index: Int) async -> [String: Any] {
        await asyncCall("pageImage", [jsLiteral(key), jsLiteral(comicId), jsLiteral(epId), "\(index)"])
    }

    func login(key: String, user: String, password: String) async -> [String: Any] {
        await asyncCall("login", [jsLiteral(key), jsLiteral(user), jsLiteral(password)])
    }

    func logout(key: String) async -> [String: Any] {
        await asyncCall("logout", [jsLiteral(key)])
    }

    /// 账号页：登录态 + 源自己声明的信息项（account.infoItems）
    func account(key: String) async -> [String: Any] {
        await asyncCall("account", [jsLiteral(key)])
    }

    // MARK: - 调 JS

    private func syncCall(_ method: String, _ args: [String] = []) async -> Any? {
        await withCheckedContinuation { cont in
            queue.async {
                guard self.bootLocked(), let js = self.ctx else { cont.resume(returning: nil); return }
                let expr = "JSON.stringify(SourceEngine.\(method)(\(args.joined(separator: ","))))"
                let s = js.evaluateScript(expr)?.toString() ?? ""
                cont.resume(returning: Self.parseJSON(s))
            }
        }
    }

    private func asyncCall(_ method: String, _ args: [String], timeout: TimeInterval = 45) async -> [String: Any] {
        await withCheckedContinuation { cont in
            queue.async {
                guard self.bootLocked(), let js = self.ctx else {
                    cont.resume(returning: ["ok": false, "error": "引擎未启动"]); return
                }
                let id = js.evaluateScript("SourceEngine.\(method)(\(args.joined(separator: ",")))")?.toString() ?? ""
                guard !id.isEmpty, id != "undefined" else {
                    cont.resume(returning: ["ok": false, "error": "引擎未返回任务号"]); return
                }
                let deadline = Date().addingTimeInterval(timeout)
                while Date() < deadline {
                    if let evt = self.events.removeValue(forKey: id) {
                        cont.resume(returning: evt); return
                    }
                    // JSC 的 Promise 微任务必须靠重新进入 JS 引擎才排空
                    js.evaluateScript("0")
                    Thread.sleep(forTimeInterval: 0.012)
                }
                cont.resume(returning: ["ok": false, "error": "超时（\(Int(timeout)) 秒）"])
            }
        }
    }

    private static func parseJSON(_ s: String) -> Any? {
        guard !s.isEmpty, s != "null", s != "undefined",
              let data = s.data(using: .utf8) else { return nil }
        return try? JSONSerialization.jsonObject(with: data)
    }

    // MARK: - 同步宿主方法

    private func handleHostCall(_ json: String) -> String {
        guard let data = json.data(using: .utf8),
              let obj = try? JSONSerialization.jsonObject(with: data),
              let msg = obj as? [String: Any] else {
            return "{\"__err\":\"bad json\"}"
        }
        do {
            let raw = try dispatch(msg)
            let wrapped: [String: Any] = ["v": Self.jsonSafe(raw)]
            let d = try JSONSerialization.data(withJSONObject: wrapped)
            return String(data: d, encoding: .utf8) ?? "{\"v\":null}"
        } catch {
            let d = try? JSONSerialization.data(withJSONObject: ["__err": "\(error)"])
            return d.flatMap { String(data: $0, encoding: .utf8) } ?? "{\"__err\":\"error\"}"
        }
    }

    /// JSON 表达不了二进制：统一转成 {"__b64":"..."}，由引擎 prelude 还原成 Uint8Array。
    /// 之前这里直接回 Data，JSONSerialization 直接抛错 —— 表现为 jm 签名、拷贝正文解密全失败。
    private static func jsonSafe(_ v: Any?) -> Any {
        switch v {
        case let d as Data: return ["__b64": d.base64EncodedString()]
        case let a as [UInt8]: return ["__b64": Data(a).base64EncodedString()]
        default: return v ?? NSNull()
        }
    }

    /// KV 必须按源隔离：prelude 每个请求都带 key（源自己的 key）。
    /// 不加前缀的话「登录了 A 源」会让 B 源也显示已登录，两个源的 token 还会互相覆盖。
    private func kvKey(_ msg: [String: Any], _ prefix: String, _ field: String) -> String {
        let src = (msg["key"] as? String).flatMap { $0.isEmpty ? nil : $0 } ?? "default"
        return prefix + src + "_" + (msg[field] as? String ?? "")
    }

    private func dispatch(_ msg: [String: Any]) throws -> Any? {
        let method = msg["method"] as? String ?? ""
        switch method {
        case "http":
            return try http(msg)
        case "convert":
            return try convert(msg)
        case "cookie":
            return cookie(msg)
        case "load_data":
            return defaults.object(forKey: kvKey(msg, "d_", "data_key"))
        case "save_data":
            defaults.set(msg["data"], forKey: kvKey(msg, "d_", "data_key"))
            return nil
        case "delete_data":
            defaults.removeObject(forKey: kvKey(msg, "d_", "data_key"))
            return nil
        case "load_setting":
            return defaults.object(forKey: kvKey(msg, "s_", "setting_key"))
        case "save_setting":
            defaults.set(msg["value"], forKey: kvKey(msg, "s_", "setting_key"))
            return nil
        case "isLogged":
            return defaults.bool(forKey: "logged_\(msg["key"] as? String ?? "")")
        case "UI":
            return (msg["function"] as? String) == "showDialog" ? 0 : nil
        case "random":
            let lo = (msg["min"] as? Double) ?? 0
            let hi = (msg["max"] as? Double) ?? 1
            return lo + Double.random(in: 0..<1) * (hi - lo)
        case "uuid":
            return UUID().uuidString
        case "delay":
            return nil
        case "getPlatform":
            return "ios"
        case "getLocale":
            return "zh"
        case "log":
            return nil
        case "htmanga_state":
            let key = msg["key"] as? String ?? "htmanga"
            if let raw = defaults.string(forKey: "source_route_state"),
               let data = raw.data(using: .utf8),
               let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
               let st = obj["state"] as? [String: Any],
               let me = st[key] as? [String: Any],
               let host = me["host"] as? String, !host.isEmpty {
                return ["baseUrl": "https://\(host)"]
            }
            return [:]
        default:
            throw HostError(msg: "未实现的宿主方法: \(method)")
        }
    }

    // MARK: - HTTP（同步，供 JS 线程调用）

    private func http(_ msg: [String: Any]) throws -> [String: Any] {
        let rawUrl = Self.normalizeUrl(msg["url"] as? String ?? "")
        guard let url = URL(string: rawUrl) else {
            return ["status": 0, "headers": [String: String](), "body": "", "error": "bad url"]
        }
        var req = URLRequest(url: url)
        req.httpMethod = (msg["http_method"] as? String ?? "GET").uppercased()
        req.timeoutInterval = 30

        if let headers = msg["headers"] as? [String: Any] {
            for (k, v) in headers {
                let lk = k.lowercased()
                if lk == "accept-encoding" || lk == "connection" ||
                    lk == "content-length" || lk == "host" { continue }
                req.setValue("\(v)", forHTTPHeaderField: k)
            }
        }
        if req.value(forHTTPHeaderField: "User-Agent") == nil {
            req.setValue("Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) AppleWebKit/605.1.15 " +
                         "(KHTML, like Gecko) Version/18.0 Mobile/15E148 Safari/604.1",
                         forHTTPHeaderField: "User-Agent")
        }
        let host = url.host ?? ""
        let cookie = cookies(for: host).map { "\($0.key)=\($0.value)" }.joined(separator: "; ")
        if !cookie.isEmpty { req.setValue(cookie, forHTTPHeaderField: "Cookie") }

        if let data = msg["data"], !(data is NSNull), req.httpMethod != "GET" {
            if let s = data as? String {
                req.httpBody = s.data(using: .utf8)
            } else if let o = data as? [String: Any], let b = o["__b64"] as? String,
                      let d = Data(base64Encoded: b) {
                req.httpBody = d
            } else if let d = try? JSONSerialization.data(withJSONObject: data) {
                req.httpBody = d
            }
        }

        var status = 0
        var body = Data()
        var respHeaders: [String: String] = [:]
        let sem = DispatchSemaphore(value: 0)
        let task = URLSession.shared.dataTask(with: req) { data, resp, _ in
            if let h = resp as? HTTPURLResponse {
                status = h.statusCode
                for (k, v) in h.allHeaderFields {
                    respHeaders["\(k)"] = "\(v)"
                }
            }
            body = data ?? Data()
            sem.signal()
        }
        task.resume()
        sem.wait()

        let wantBytes = (msg["bytes"] as? Bool) ?? false
        // 二进制不直接塞进 JSON（会变成乱码字符串），统一走 {"__b64":...}
        let bodyValue: Any = wantBytes
            ? ["__b64": body.base64EncodedString()]
            : (String(data: body, encoding: .utf8) ?? "")
        return ["status": status, "headers": respHeaders, "body": bodyValue]
    }

    private static func normalizeUrl(_ u: String) -> String {
        // URL 里的中文必须先变成 %XX，否则 URL(string:) 直接返回 nil（安卓那边是 400）。
        // 只转空格和非 ASCII；/ : ? & = # 这些结构字符要原样保留，不能整体转义。
        var out = ""
        out.reserveCapacity(u.count + 16)
        for byte in u.utf8 {
            if byte > 0x20 && byte < 0x7F {
                out.append(Character(UnicodeScalar(byte)))
            } else {
                out += String(format: "%%%02X", byte)
            }
        }
        return out
    }

    // MARK: - Cookie

    private func cookieStore() -> [String: [String: String]] {
        (defaults.dictionary(forKey: "source_cookies") as? [String: [String: String]]) ?? [:]
    }

    private func cookies(for host: String) -> [String: String] {
        cookieStore()[host] ?? [:]
    }

    private func saveCookie(host: String, setCookie: String) {
        let first = setCookie.split(separator: ";").first.map(String.init)?
            .trimmingCharacters(in: .whitespaces) ?? ""
        guard let eq = first.firstIndex(of: "=") else { return }
        let name = String(first[first.startIndex..<eq])
        let value = String(first[first.index(after: eq)...])
        var store = cookieStore()
        var jar = store[host] ?? [:]
        jar[name] = value
        store[host] = jar
        defaults.set(store, forKey: "source_cookies")
    }

    private func cookie(_ msg: [String: Any]) -> Any? {
        let host = URL(string: msg["url"] as? String ?? "")?.host ?? ""
        switch msg["function"] as? String ?? "" {
        case "get":
            return cookies(for: host).map { ["name": $0.key, "value": $0.value, "domain": host] }
        case "delete":
            var store = cookieStore()
            store.removeValue(forKey: host)
            defaults.set(store, forKey: "source_cookies")
            return nil
        default:
            return nil
        }
    }

    // MARK: - convert

    private func convert(_ msg: [String: Any]) throws -> Any? {
        let type = (msg["type"] as? String ?? "").lowercased()
        let encode = (msg["isEncode"] as? Bool) ?? false

        func bytes(_ v: Any?) -> Data {
            if let s = v as? String { return Data(s.utf8) }
            if let d = v as? Data { return d }
            if let a = v as? [Int] { return Data(a.map { UInt8(truncatingIfNeeded: $0) }) }
            if let a = v as? [UInt8] { return Data(a) }
            // 引擎把 Uint8Array 打包成 {"__b64":"..."} 传下来
            if let o = v as? [String: Any], let b = o["__b64"] as? String,
               let d = Data(base64Encoded: b) { return d }
            return Data()
        }
        let value = bytes(msg["value"])

        switch type {
        case "utf8":
            return encode ? value : (String(data: value, encoding: .utf8) ?? "")
        case "gbk":
            if encode { return value }
            let enc = CFStringConvertEncodingToNSStringEncoding(
                CFStringEncoding(CFStringEncodings.GB_18030_2000.rawValue))
            return String(data: value, encoding: String.Encoding(rawValue: enc)) ?? ""
        case "base64":
            return encode ? value.base64EncodedString()
                : (Data(base64Encoded: msg["value"] as? String ?? "") ?? Data())
        case "md5":
            return Self.md5(value)
        case "sha1":
            return Self.hash(value, .sha1)
        case "sha256":
            return Self.hash(value, .sha256)
        case "sha512":
            return Self.hash(value, .sha512)
        case "hmac":
            let key = bytes(msg["key"])
            let out = Self.hmac(key: key, data: value,
                                alg: (msg["hash"] as? String ?? "sha256").lowercased())
            return (msg["isString"] as? Bool) == true ? out.hexString : out
        case "aes-ecb":
            return try Self.aes(value, key: bytes(msg["key"]), iv: nil, encrypt: encode)
        case "aes-cbc":
            return try Self.aes(value, key: bytes(msg["key"]), iv: bytes(msg["iv"]), encrypt: encode)
        default:
            throw HostError(msg: "convert 未实现: \(type)")
        }
    }

    private enum HashAlg { case sha1, sha256, sha512 }

    private static func md5(_ d: Data) -> Data {
        var out = Data(count: Int(CC_MD5_DIGEST_LENGTH))
        out.withUnsafeMutableBytes { ob in
            d.withUnsafeBytes { db in
                _ = CC_MD5(db.baseAddress, CC_LONG(d.count), ob.baseAddress?.assumingMemoryBound(to: UInt8.self))
            }
        }
        return out
    }

    private static func hash(_ d: Data, _ alg: HashAlg) -> Data {
        switch alg {
        case .sha1:
            var out = Data(count: Int(CC_SHA1_DIGEST_LENGTH))
            out.withUnsafeMutableBytes { ob in
                d.withUnsafeBytes { db in
                    _ = CC_SHA1(db.baseAddress, CC_LONG(d.count), ob.baseAddress?.assumingMemoryBound(to: UInt8.self))
                }
            }
            return out
        case .sha256:
            var out = Data(count: Int(CC_SHA256_DIGEST_LENGTH))
            out.withUnsafeMutableBytes { ob in
                d.withUnsafeBytes { db in
                    _ = CC_SHA256(db.baseAddress, CC_LONG(d.count), ob.baseAddress?.assumingMemoryBound(to: UInt8.self))
                }
            }
            return out
        case .sha512:
            var out = Data(count: Int(CC_SHA512_DIGEST_LENGTH))
            out.withUnsafeMutableBytes { ob in
                d.withUnsafeBytes { db in
                    _ = CC_SHA512(db.baseAddress, CC_LONG(d.count), ob.baseAddress?.assumingMemoryBound(to: UInt8.self))
                }
            }
            return out
        }
    }

    private static func hmac(key: Data, data: Data, alg: String) -> Data {
        let a: CCHmacAlgorithm
        let len: Int
        switch alg {
        case "sha1": a = CCHmacAlgorithm(kCCHmacAlgSHA1); len = Int(CC_SHA1_DIGEST_LENGTH)
        case "sha512": a = CCHmacAlgorithm(kCCHmacAlgSHA512); len = Int(CC_SHA512_DIGEST_LENGTH)
        case "md5": a = CCHmacAlgorithm(kCCHmacAlgMD5); len = Int(CC_MD5_DIGEST_LENGTH)
        default: a = CCHmacAlgorithm(kCCHmacAlgSHA256); len = Int(CC_SHA256_DIGEST_LENGTH)
        }
        var out = Data(count: len)
        out.withUnsafeMutableBytes { ob in
            key.withUnsafeBytes { kb in
                data.withUnsafeBytes { db in
                    CCHmac(a, kb.baseAddress, key.count, db.baseAddress, data.count, ob.baseAddress)
                }
            }
        }
        return out
    }

    private static func aes(_ data: Data, key: Data, iv: Data?, encrypt: Bool) throws -> Data {
        let keyLen = key.count
        guard keyLen == 16 || keyLen == 24 || keyLen == 32 else {
            throw HostError(msg: "AES key 长度不对: \(keyLen)")
        }
        // CommonCrypto 只有 kCCAlgorithmAES128 这一个算法标识（值就是 kCCAlgorithmAES），
        // 192/256 位是靠 key 长度区分的，没有单独的常量，写了会编译不过。
        let alg = CCAlgorithm(kCCAlgorithmAES128)
        var options = CCOptions(kCCOptionPKCS7Padding)
        if iv == nil { options |= CCOptions(kCCOptionECBMode) }

        var out = Data(count: data.count + kCCBlockSizeAES128)
        var moved = 0
        let status: CCCryptorStatus = out.withUnsafeMutableBytes { ob in
            data.withUnsafeBytes { db in
                key.withUnsafeBytes { kb in
                    if let iv {
                        return iv.withUnsafeBytes { ib in
                            CCCrypt(CCOperation(encrypt ? kCCEncrypt : kCCDecrypt), alg, options,
                                    kb.baseAddress, keyLen, ib.baseAddress,
                                    db.baseAddress, data.count,
                                    ob.baseAddress, ob.count, &moved)
                        }
                    }
                    return CCCrypt(CCOperation(encrypt ? kCCEncrypt : kCCDecrypt), alg, options,
                                   kb.baseAddress, keyLen, nil,
                                   db.baseAddress, data.count,
                                   ob.baseAddress, ob.count, &moved)
                }
            }
        }
        guard status == kCCSuccess else { throw HostError(msg: "AES 失败: \(status)") }
        return out.prefix(moved)
    }
}

private extension Data {
    var hexString: String { map { String(format: "%02x", $0) }.joined() }
}
