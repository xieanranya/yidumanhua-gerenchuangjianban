# 把 vendor/_venera_.js（官方 API 实现）里依赖原生 DOM 的三个类换成 dom_pure.js，
# 拼成两端共用的 prelude.js。
# 生成物：prelude.js = sendMessage 声明 + 官方 API（脚本/网络/Cookie/数据结构）+ 纯 JS DOM
import os

HERE = os.path.dirname(os.path.abspath(__file__))
VENDOR = os.path.join(HERE, "vendor", "_venera_.js")
DOM = os.path.join(HERE, "dom_pure.js")
OUT = os.path.join(HERE, "prelude.js")

src = open(VENDOR, encoding="utf-8").read()

DOM_START = "/**\n * HtmlDocument class for parsing HTML and querying elements.\n */"
LOG_START = "function log(level, title, content) {"
API_START = "function setTimeout(callback, delay) {"

i = src.index(DOM_START)
j = src.index(LOG_START)
k = src.index(API_START)
assert k < i < j, "切分点顺序不对"

api = src[k:i]          # setTimeout / Convert / Network / fetch ...
tail = src[j:]          # log / console / Comic / ComicSource / Image / UI / APP ...
dom = open(DOM, encoding="utf-8").read()

header = """// ============================================================================
// prelude.js —— 漫画源 JS 运行库（两端共用，由 build_prelude.py 生成，勿手改）
//   来源：vendor/_venera_.js（源脚本依赖的 Venera API）+ dom_pure.js（纯 JS DOM）
// 原生侧只需提供：
//   __hostRaw(jsonString) -> jsonString   同步调用，按 msg.method 分发：
//     http / convert / cookie / load_data / save_data / delete_data / load_setting
//     isLogged / UI / random / uuid / delay / getPlatform / getLocale / log / htmanga_state
//   入参：{"method":...}；二进制字段已写成 {"__b64":"..."}
//   返回：{"v": 任意值} 或 {"__err":"错误文本"}；二进制同样写成 {"__b64":"..."}
//   拆包装 / 抛错误 / 二进制互转都在下面统一做，两端行为必须一致。
// ============================================================================

var __B64C = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/';
var __B64I = null;

function __b64Decode(s) {
    if (__B64I === null) {
        __B64I = {};
        for (var i = 0; i < __B64C.length; i++) __B64I[__B64C.charAt(i)] = i;
    }
    var t = String(s).replace(/[^A-Za-z0-9+/=]/g, '');
    var out = new Uint8Array((t.length * 3) >> 2);
    var buf = 0, bits = 0, n = 0;
    for (var j = 0; j < t.length; j++) {
        var ch = t.charAt(j);
        if (ch === '=') break;
        buf = (buf << 6) | __B64I[ch];
        bits += 6;
        if (bits >= 8) { bits -= 8; out[n++] = (buf >> bits) & 0xFF; }
    }
    return out.subarray(0, n);
}

function __b64Encode(bytes) {
    var out = '';
    for (var i = 0; i < bytes.length; i += 3) {
        var c1 = bytes[i];
        var c2 = i + 1 < bytes.length ? bytes[i + 1] : -1;
        var c3 = i + 2 < bytes.length ? bytes[i + 2] : -1;
        out += __B64C.charAt(c1 >> 2);
        out += __B64C.charAt(((c1 & 3) << 4) | (c2 < 0 ? 0 : c2 >> 4));
        out += c2 < 0 ? '=' : __B64C.charAt(((c2 & 15) << 2) | (c3 < 0 ? 0 : c3 >> 6));
        out += c3 < 0 ? '=' : __B64C.charAt(c3 & 63);
    }
    return out;
}

function __isBinary(v) {
    if (typeof ArrayBuffer === 'undefined') return false;
    if (v instanceof ArrayBuffer) return true;
    return typeof ArrayBuffer.isView === 'function' && ArrayBuffer.isView(v);
}

function __binBytes(v) {
    if (v instanceof ArrayBuffer) return new Uint8Array(v);
    return new Uint8Array(v.buffer, v.byteOffset, v.byteLength);
}

// 发给原生前：TypedArray / ArrayBuffer -> {"__b64":"..."}
function __packBytes(v, depth) {
    if (v === null || v === undefined || (depth || 0) > 8) return v;
    if (__isBinary(v)) return { __b64: __b64Encode(__binBytes(v)) };
    if (Array.isArray(v)) {
        var a = new Array(v.length);
        for (var i = 0; i < v.length; i++) a[i] = __packBytes(v[i], (depth || 0) + 1);
        return a;
    }
    if (typeof v === 'object') {
        var o = {};
        for (var k in v) if (Object.prototype.hasOwnProperty.call(v, k)) o[k] = __packBytes(v[k], (depth || 0) + 1);
        return o;
    }
    return v;
}

// 原生返回后：{"__b64":"..."} -> Uint8Array
function __unpackBytes(v, depth) {
    if (v === null || v === undefined || typeof v !== 'object' || (depth || 0) > 8) return v;
    if (typeof v.__b64 === 'string') return __b64Decode(v.__b64);
    if (Array.isArray(v)) {
        for (var i = 0; i < v.length; i++) v[i] = __unpackBytes(v[i], (depth || 0) + 1);
        return v;
    }
    for (var k in v) if (Object.prototype.hasOwnProperty.call(v, k)) v[k] = __unpackBytes(v[k], (depth || 0) + 1);
    return v;
}

function sendMessage(msg) {
    // 兼容只有 __hostCall 的旧宿主（node 测试脚本）；真机两端都走 __hostRaw
    if (typeof __hostRaw !== 'function') return __hostCall(msg);

    // —— 域名别名改写 ——
    // 源脚本里写死的域名在国内不一定通：Komiic 写 komiic.com、拷贝漫画默认 www.2026copy.com。
    // 这里只做一件事：换成同一套站点的可用域名。没有直连/VPN 之分，也没有探测，
    // 用户看不到任何线路概念——跟普通聚合软件一样，发不通就换域名再发一次。
    // 放在 sendMessage 这一层，三端走同一份代码，不会漏。
    var isHttp = !!msg && msg.method === 'http';
    var isCookie = !!msg && msg.method === 'cookie';
    var origUrl = null;
    if ((isHttp || isCookie) && typeof msg.url === 'string' && msg.url) {
        var rw = __routeRewrite(msg.url);
        if (rw && rw !== msg.url) { origUrl = msg.url; msg.url = rw; }
    }

    var r = __hostRound(msg);
    if (!r) return null;

    // 这一趟彻底连不上（0）或对端 5xx：换下一个备用域名，再试一次就收手。
    // 用户不会看到「线路不通」，最多是第一次请求慢一点。
    if (isHttp && origUrl && r.v && typeof r.v === 'object') {
        var code = Number(r.v.status);
        if (!(code >= 200 && code <= 499)) {
            var moved = null;
            try {
                var RA = __routeApi();
                moved = RA ? RA.report(msg.url, code) : null;
            } catch (e) { moved = null; }
            if (moved && moved.next) {
                var again = __routeRewrite(origUrl);
                if (again && again !== msg.url) {
                    msg.url = again;
                    var r2 = __hostRound(msg);
                    if (r2) r = r2;
                }
            }
        }
    }

    if (r && r.__err) throw new Error(r.__err);
    return __unpackBytes(r ? r.v : null, 0);
}

function __routeApi() {
    try {
        if (typeof ComicRoute !== 'undefined' && ComicRoute && ComicRoute.rewrite) return ComicRoute;
    } catch (e) { }
    return null;
}

function __routeRewrite(url) {
    var R = __routeApi();
    if (!R) return url;
    try { return R.rewrite(url) || url; } catch (e) { return url; }
}

/** 一次同步宿主调用，返回拆好的 JSON（含 __err） */
function __hostRound(msg) {
    var raw = __hostRaw(JSON.stringify(__packBytes(msg, 0)));
    if (raw === null || raw === undefined) return null;
    var text = String(raw);
    if (!text || text === 'null') return null;
    try { return JSON.parse(text); } catch (e) { throw new Error('宿主返回非 JSON: ' + text.slice(0, 120)); }
}

"""

out = header + api + "\n" + dom + "\n" + tail
open(OUT, "w", encoding="utf-8").write(out)
print("prelude.js 生成：", len(out), "字符 /", out.count("\n") + 1, "行")
