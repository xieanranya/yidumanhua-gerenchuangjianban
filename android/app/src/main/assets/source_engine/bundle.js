// bundle.js —— 由 build_bundle.py 生成，勿手改
// ============================================================================
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

    // —— 线路改写 ——
    // 源脚本里写死的域名在国内不一定通：Komiic 写 komiic.com、拷贝漫画默认 www.2026copy.com，
    // 这两个直连都是 000。改写原先只在各平台宿主里做了一半（node 有、安卓/iOS 忘了），
    // 结果「源列表显示直连，请求全打在被墙域名上，登录和首页一起空白」。
    // 放在 sendMessage 这一层，三端走同一份代码，不可能再漏。
    var isHttp = !!msg && msg.method === 'http';
    var isCookie = !!msg && msg.method === 'cookie';
    var origUrl = null;
    if ((isHttp || isCookie) && typeof msg.url === 'string' && msg.url) {
        var rw = __routeRewrite(msg.url);
        if (rw && rw !== msg.url) { origUrl = msg.url; msg.url = rw; }
    }

    var r = __hostRound(msg);
    if (!r) return null;

    // 这一趟彻底连不上（0）或对端 5xx：记坏点换 host，最多再试一次。
    // 直连域名被污染时第一次必然失败，靠这一步才能不靠人手动切线路。
    if (isHttp && origUrl && r.v && typeof r.v === 'object') {
        var code = Number(r.v.status);
        if (!(code >= 200 && code <= 499)) {
            var moved = null;
            try { moved = __routeApi() && ComicRoute.report(msg.url, code); } catch (e) { moved = null; }
            if (moved && moved.to) {
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

function setTimeout(callback, delay) {
    const r = sendMessage({
        method: 'delay',
        time: delay,
    });
    // 宿主的 delay 一般直接回 null（真 sleep 会卡住主线程），
    // 直接 .then 会炸 null；能 then 就 then，不能就立刻执行。
    if (r && typeof r.then === 'function') r.then(callback);
    else if (typeof callback === 'function') callback();
}

/// encode, decode, hash, decrypt
let Convert = {
    /**
     * @param str {string}
     * @returns {ArrayBuffer}
     */
    encodeUtf8: (str) => {
        return sendMessage({
            method: "convert",
            type: "utf8",
            value: str,
            isEncode: true
        });
    },

    /**
     * @param value {ArrayBuffer}
     * @returns {string}
     */
    decodeUtf8: (value) => {
        return sendMessage({
            method: "convert",
            type: "utf8",
            value: value,
            isEncode: false
        });
    },

    /**
     * @param str {string}
     * @returns {ArrayBuffer}
     */
    encodeGbk: (str) => {
        return sendMessage({
            method: "convert",
            type: "gbk",
            value: str,
            isEncode: true
        });
    },

    /**
     * @param value {ArrayBuffer}
     * @returns {string}
     */
    decodeGbk: (value) => {
        return sendMessage({
            method: "convert",
            type: "gbk",
            value: value,
            isEncode: false
        });
    },

    /**
     * @param {ArrayBuffer} value
     * @returns {string}
     */
    encodeBase64: (value) => {
        return sendMessage({
            method: "convert",
            type: "base64",
            value: value,
            isEncode: true
        });
    },

    /**
     * @param {string} value
     * @returns {ArrayBuffer}
     */
    decodeBase64: (value) => {
        return sendMessage({
            method: "convert",
            type: "base64",
            value: value,
            isEncode: false
        });
    },

    /**
     * @param {ArrayBuffer} value
     * @returns {ArrayBuffer}
     */
    md5: (value) => {
        return sendMessage({
            method: "convert",
            type: "md5",
            value: value,
            isEncode: true
        });
    },

    /**
     * @param {ArrayBuffer} value
     * @returns {ArrayBuffer}
     */
    sha1: (value) => {
        return sendMessage({
            method: "convert",
            type: "sha1",
            value: value,
            isEncode: true
        });
    },

    /**
     * @param {ArrayBuffer} value
     * @returns {ArrayBuffer}
     */
    sha256: (value) => {
        return sendMessage({
            method: "convert",
            type: "sha256",
            value: value,
            isEncode: true
        });
    },

    /**
     * @param {ArrayBuffer} value
     * @returns {ArrayBuffer}
     */
    sha512: (value) => {
        return sendMessage({
            method: "convert",
            type: "sha512",
            value: value,
            isEncode: true
        });
    },

    /**
     * @param key {ArrayBuffer}
     * @param value {ArrayBuffer}
     * @param hash {string} - md5, sha1, sha256, sha512
     * @returns {ArrayBuffer}
     */
    hmac: (key, value, hash) => {
        return sendMessage({
            method: "convert",
            type: "hmac",
            value: value,
            key: key,
            hash: hash,
            isEncode: true
        });
    },

    /**
     * @param key {ArrayBuffer}
     * @param value {ArrayBuffer}
     * @param hash {string} - md5, sha1, sha256, sha512
     * @returns {string} - hex string
     */
    hmacString: (key, value, hash) => {
        return sendMessage({
            method: "convert",
            type: "hmac",
            value: value,
            key: key,
            hash: hash,
            isEncode: true,
            isString: true
        });
    },

    /**
     * @param {ArrayBuffer} value
     * @param {ArrayBuffer} key
     * @returns {ArrayBuffer}
     */
    decryptAesEcb: (value, key) => {
        return sendMessage({
            method: "convert",
            type: "aes-ecb",
            value: value,
            key: key,
            isEncode: false
        });
    },

    /**
     * @param {ArrayBuffer} value
     * @param {ArrayBuffer} key
     * @param {ArrayBuffer} iv
     * @returns {ArrayBuffer}
     */
    decryptAesCbc: (value, key, iv) => {
        return sendMessage({
            method: "convert",
            type: "aes-cbc",
            value: value,
            key: key,
            iv: iv,
            isEncode: false
        });
    },

    /**
     * @param {ArrayBuffer} value
     * @param {ArrayBuffer} key
     * @param {number} blockSize
     * @returns {ArrayBuffer}
     */
    decryptAesCfb: (value, key, blockSize) => {
        return sendMessage({
            method: "convert",
            type: "aes-cfb",
            value: value,
            key: key,
            blockSize: blockSize,
            isEncode: false
        });
    },

    /**
     * @param {ArrayBuffer} value
     * @param {ArrayBuffer} key
     * @param {number} blockSize
     * @returns {ArrayBuffer}
     */
    decryptAesOfb: (value, key, blockSize) => {
        return sendMessage({
            method: "convert",
            type: "aes-ofb",
            value: value,
            key: key,
            blockSize: blockSize,
            isEncode: false
        });
    },

    /**
     * @param {ArrayBuffer} value
     * @param {ArrayBuffer} key
     * @returns {ArrayBuffer}
     */
    decryptRsa: (value, key) => {
        return sendMessage({
            method: "convert",
            type: "rsa",
            value: value,
            key: key,
            isEncode: false
        });
    },
    /** Encode bytes to hex string
     * @param bytes {ArrayBuffer}
     * @return {string}
     */
    hexEncode: (bytes) => {
        const hexDigits = '0123456789abcdef';
        const view = new Uint8Array(bytes);
        let charCodes = new Uint8Array(view.length * 2);
        let j = 0;

        for (let i = 0; i < view.length; i++) {
            let byte = view[i];
            charCodes[j++] = hexDigits.charCodeAt((byte >> 4) & 0xF);
            charCodes[j++] = hexDigits.charCodeAt(byte & 0xF);
        }

        return String.fromCharCode(...charCodes);
    },
}

/**
 * create a time-based uuid
 *
 * Note: the engine will generate a new uuid every time it is called
 *
 * To get the same uuid, please save it to the local storage
 *
 * @returns {string}
 */
function createUuid() {
    return sendMessage({
        method: "uuid"
    });
}

/**
 * Generate a random integer between min and max
 * @param min {number}
 * @param max {number}
 * @returns {number}
 */
function randomInt(min, max) {
    return sendMessage({
        method: 'random',
        type: 'int',
        min: min,
        max: max
    });
}

/**
 * Generate a random double between min and max
 * @param min {number}
 * @param max {number}
 * @returns {number}
 */
function randomDouble(min, max) {
    return sendMessage({
        method: 'random',
        type: 'double',
        min: min,
        max: max
    });
}

class _Timer {
    delay = 0;

    callback = () => { };

    status = false;

    constructor(delay, callback) {
        this.delay = delay;
        this.callback = callback;
    }

    run() {
        this.status = true;
        this._interval();
    }

    _interval() {
        if (!this.status) {
            return;
        }
        this.callback();
        setTimeout(this._interval.bind(this), this.delay);
    }

    cancel() {
        this.status = false;
    }
}

function setInterval(callback, delay) {
    let timer = new _Timer(delay, callback);
    timer.run();
    return timer;
}

/**
 * Create a cookie object.
 * @param name {string}
 * @param value {string}
 * @param domain {string}
 * @constructor
 */
function Cookie({name, value, domain}) {
    this.name = name;
    this.value = value;
    this.domain = domain;
}

/**
 * Network object for sending HTTP requests and managing cookies.
 * @namespace Network
 */
let Network = {
    /**
     * Sends an HTTP request.
     * @param {string} method - The HTTP method (e.g., GET, POST, PUT, PATCH, DELETE).
     * @param {string} url - The URL to send the request to.
     * @param {Object} headers - The headers to include in the request.
     * @param data - The data to send with the request.
     * @returns {Promise<{status: number, headers: {}, body: ArrayBuffer}>} The response from the request.
     */
    async fetchBytes(method, url, headers, data) {
        let result = await sendMessage({
            method: 'http',
            http_method: method,
            bytes: true,
            url: url,
            headers: headers,
            data: data,
        });

        if (result.error) {
            throw result.error;
        }

        return result;
    },

    /**
     * Sends an HTTP request.
     * @param {string} method - The HTTP method (e.g., GET, POST, PUT, PATCH, DELETE).
     * @param {string} url - The URL to send the request to.
     * @param {Object} headers - The headers to include in the request.
     * @param data - The data to send with the request.
     * @returns {Promise<{status: number, headers: {}, body: string}>} The response from the request.
     */
    async sendRequest(method, url, headers, data) {
        let result = await sendMessage({
            method: 'http',
            http_method: method,
            url: url,
            headers: headers,
            data: data,
        });

        if (result.error) {
            throw result.error;
        }

        return result;
    },

    /**
     * Sends an HTTP GET request.
     * @param {string} url - The URL to send the request to.
     * @param {Object} headers - The headers to include in the request.
     * @returns {Promise<{status: number, headers: {}, body: string}>} The response from the request.
     */
    async get(url, headers) {
        return this.sendRequest('GET', url, headers);
    },

    /**
     * Sends an HTTP POST request.
     * @param {string} url - The URL to send the request to.
     * @param {Object} headers - The headers to include in the request.
     * @param data - The data to send with the request.
     * @returns {Promise<{status: number, headers: {}, body: string}>} The response from the request.
     */
    async post(url, headers, data) {
        return this.sendRequest('POST', url, headers, data);
    },

    /**
     * Sends an HTTP PUT request.
     * @param {string} url - The URL to send the request to.
     * @param {Object} headers - The headers to include in the request.
     * @param data - The data to send with the request.
     * @returns {Promise<{status: number, headers: {}, body: string}>} The response from the request.
     */
    async put(url, headers, data) {
        return this.sendRequest('PUT', url, headers, data);
    },

    /**
     * Sends an HTTP PATCH request.
     * @param {string} url - The URL to send the request to.
     * @param {Object} headers - The headers to include in the request.
     * @param data - The data to send with the request.
     * @returns {Promise<{status: number, headers: {}, body: string}>} The response from the request.
     */
    async patch(url, headers, data) {
        return this.sendRequest('PATCH', url, headers, data);
    },

    /**
     * Sends an HTTP DELETE request.
     * @param {string} url - The URL to send the request to.
     * @param {Object} headers - The headers to include in the request.
     * @returns {Promise<{status: number, headers: {}, body: string}>} The response from the request.
     */
    async delete(url, headers) {
        return this.sendRequest('DELETE', url, headers);
    },

    /**
     * Sets cookies for a specific URL.
     * @param {string} url - The URL to set the cookies for.
     * @param {Cookie[]} cookies - The cookies to set.
     */
    setCookies(url, cookies) {
        sendMessage({
            method: 'cookie',
            function: 'set',
            url: url,
            cookies: cookies,
        });
    },

    /**
     * Retrieves cookies for a specific URL.
     * @param {string} url - The URL to get the cookies from.
     * @returns {Promise<Cookie[]>} The cookies for the given URL.
     */
    getCookies(url) {
        return sendMessage({
            method: 'cookie',
            function: 'get',
            url: url,
        });
    },

    /**
     * Deletes cookies for a specific URL.
     * @param {string} url - The URL to delete the cookies from.
     * @param {string[]?} names - Delete only these names; omit to delete all.
     */
    deleteCookies(url, names = null) {
        sendMessage({
            method: 'cookie',
            function: 'delete',
            url: url,
            names: names,
        });
    },
};

/**
 * [fetch] function for sending HTTP requests. Same api as the browser fetch.
 * @param url {string}
 * @param [options] {{method?: string, headers?: Object, body?: any}}
 * @returns {Promise<{ok: boolean, status: number, statusText: string, headers: {}, arrayBuffer: (function(): Promise<ArrayBuffer>), text: (function(): Promise<string>), json: (function(): Promise<any>)}>}
 * @since 1.2.0
 */
async function fetch(url, options) {
    let method = 'GET';
    let headers = {};
    let data = null;

    if (options) {
        method = options.method || method;
        headers = options.headers || headers;
        data = options.body || data;
    }

    let result = await Network.fetchBytes(method, url, headers, data);

    return {
        ok: result.status >= 200 && result.status < 300,
        status: result.status,
        statusText: '',
        headers: result.headers,
        arrayBuffer: async () => result.body,
        text: async () => Convert.decodeUtf8(result.body),
        json: async () => JSON.parse(Convert.decodeUtf8(result.body)),
    }
}


// ============================================================================
// 纯 JS 的 HTML 解析 + CSS 选择器 —— 两端共用，不依赖 Jsoup / DOM
// 目标：行为对齐 Jsoup（源脚本作者按 Jsoup 语义写的选择器）
//   1) 容错解析：乱序标签、未闭合标签、隐式 tbody
//   2) 选择器：tag / #id / .class / [attr] / [attr=v] / [attr*=v] / [attr^=v]
//              / [attr$=v] / [attr~=v] / 后代(空格) / 子代(>) / 逗号分组 / :nth-child(n)
//   3) .text 归一化：块级元素之间补空格，其余直接拼接，最后压缩空白并 trim
// ============================================================================

const __VOID = new Set(['area', 'base', 'br', 'col', 'embed', 'hr', 'img', 'input',
  'link', 'meta', 'param', 'source', 'track', 'wbr', 'basefont', 'frame', 'isindex', 'keygen']);

const __RAW = new Set(['script', 'style', 'textarea', 'title', 'xmp', 'plaintext']);

// 这些标签出现时，会把未闭合的 <p> 关掉
const __P_CLOSERS = new Set(['address', 'article', 'aside', 'blockquote', 'div', 'dl', 'fieldset',
  'footer', 'form', 'h1', 'h2', 'h3', 'h4', 'h5', 'h6', 'header', 'hr', 'menu', 'nav', 'ol',
  'p', 'pre', 'section', 'table', 'ul', 'li', 'dd', 'dt', 'tr', 'td', 'th']);

// 同标签自动闭合（li 套 li、td 套 td 这类）
const __SELF_CLOSE_SAME = new Set(['li', 'dd', 'dt', 'tr', 'td', 'th', 'option', 'thead', 'tbody', 'tfoot', 'p']);

const __BLOCK = new Set(['address', 'article', 'aside', 'blockquote', 'br', 'dd', 'div', 'dl', 'dt',
  'fieldset', 'figcaption', 'figure', 'footer', 'form', 'h1', 'h2', 'h3', 'h4', 'h5', 'h6',
  'header', 'hr', 'li', 'main', 'nav', 'ol', 'p', 'pre', 'section', 'table', 'tbody', 'td',
  'tfoot', 'th', 'thead', 'tr', 'ul', 'img', 'option', 'select']);

const __ENTITIES = {
  amp: '&', lt: '<', gt: '>', quot: '"', apos: "'", nbsp: '\u00a0', copy: '\u00a9',
  reg: '\u00ae', hellip: '\u2026', mdash: '\u2014', ndash: '\u2013', middot: '\u00b7',
  laquo: '\u00ab', raquo: '\u00bb', ldquo: '\u201c', rdquo: '\u201d', lsquo: '\u2018',
  rsquo: '\u2019', times: '\u00d7', divide: '\u00f7', deg: '\u00b0', plusmn: '\u00b1',
  yen: '\u00a5', euro: '\u20ac', pound: '\u00a3', sect: '\u00a7', para: '\u00b6',
  bull: '\u2022', dagger: '\u2020', prime: '\u2032', Prime: '\u2033', ensp: '\u2002',
  emsp: '\u2003', thinsp: '\u2009', zwnj: '\u200c', zwj: '\u200d', larr: '\u2190',
  rarr: '\u2192', uarr: '\u2191', darr: '\u2193', harr: '\u2194', infin: '\u221e',
  ne: '\u2260', le: '\u2264', ge: '\u2265', hearts: '\u2665', spades: '\u2660',
  clubs: '\u2663', diams: '\u2666', check: '\u2713', cross: '\u2717', star: '\u2606',
};

function __decodeEntities(str) {
  if (str.indexOf('&') < 0) return str;
  return str.replace(/&(#x?[0-9a-fA-F]+|[a-zA-Z][a-zA-Z0-9]*);?/g, (whole, body) => {
    if (body[0] === '#') {
      const hex = body[1] === 'x' || body[1] === 'X';
      const code = parseInt(hex ? body.slice(2) : body.slice(1), hex ? 16 : 10);
      if (!isFinite(code) || code <= 0 || code > 0x10ffff) return whole;
      try { return String.fromCodePoint(code); } catch (e) { return whole; }
    }
    const v = __ENTITIES[body];
    return v === undefined ? whole : v;
  });
}

// ---------------------------------------------------------------- 节点模型

class __El {
  constructor(tag, attrs) {
    this.tag = tag;
    this.attrs = attrs || {};
    this.parent = null;
    this.children = [];
    this.isText = false;
  }
}

class __Tx {
  constructor(data) {
    this.data = data;
    this.parent = null;
    this.children = [];
    this.isText = true;
    this.tag = '';
    this.attrs = {};
  }
}

function __append(parent, node) {
  node.parent = parent;
  parent.children.push(node);
}

// ---------------------------------------------------------------- 解析器

function __parseHTML(html) {
  const root = new __El('#root', {});
  const stack = [root];
  let i = 0;
  const n = html.length;
  const top = () => stack[stack.length - 1];

  const pushText = (txt) => {
    if (!txt) return;
    __append(top(), new __Tx(__decodeEntities(txt)));
  };

  while (i < n) {
    const lt = html.indexOf('<', i);
    if (lt < 0) { pushText(html.slice(i)); break; }
    if (lt > i) pushText(html.slice(i, lt));

    // 注释 / doctype / CDATA
    if (html.startsWith('<!--', lt)) {
      const end = html.indexOf('-->', lt + 4);
      i = end < 0 ? n : end + 3;
      continue;
    }
    if (html.startsWith('<![CDATA[', lt)) {
      const end = html.indexOf(']]>', lt + 9);
      const raw = html.slice(lt + 9, end < 0 ? n : end);
      pushText(raw);
      i = end < 0 ? n : end + 3;
      continue;
    }
    if (html[lt + 1] === '!' || html[lt + 1] === '?') {
      const end = html.indexOf('>', lt);
      i = end < 0 ? n : end + 1;
      continue;
    }

    // 结束标签
    if (html[lt + 1] === '/') {
      const end = html.indexOf('>', lt);
      if (end < 0) { i = n; break; }
      const name = html.slice(lt + 2, end).trim().toLowerCase().split(/\s/)[0];
      i = end + 1;
      // 从栈顶往下找同名标签，找到就弹到它为止；找不到就忽略
      for (let k = stack.length - 1; k > 0; k--) {
        if (stack[k].tag === name) { stack.length = k; break; }
      }
      continue;
    }

    // 开始标签
    const m = /^<([a-zA-Z][^\s/>]*)/.exec(html.slice(lt, lt + 128));
    if (!m) { pushText(html.slice(lt, lt + 1)); i = lt + 1; continue; }
    const tag = m[1].toLowerCase();
    let p = lt + m[0].length;

    // 属性
    const attrs = {};
    let selfClose = false;
    while (p < n) {
      while (p < n && /\s/.test(html[p])) p++;
      if (p >= n) break;
      if (html[p] === '>') { p++; break; }
      if (html[p] === '/' && html[p + 1] === '>') { selfClose = true; p += 2; break; }
      if (html[p] === '/') { p++; continue; }
      // 属性名
      let ns = p;
      while (p < n && !/[\s=>\/]/.test(html[p])) p++;
      const name = html.slice(ns, p).toLowerCase();
      if (!name) { p++; continue; }
      while (p < n && /\s/.test(html[p])) p++;
      let val = '';
      if (html[p] === '=') {
        p++;
        while (p < n && /\s/.test(html[p])) p++;
        const q = html[p];
        if (q === '"' || q === "'") {
          const end = html.indexOf(q, p + 1);
          val = html.slice(p + 1, end < 0 ? n : end);
          p = end < 0 ? n : end + 1;
        } else {
          ns = p;
          while (p < n && !/[\s>]/.test(html[p])) p++;
          val = html.slice(ns, p);
        }
        val = __decodeEntities(val);
      }
      if (!(name in attrs)) attrs[name] = val;
    }
    i = p;

    if (tag === 'html' || tag === 'head' || tag === 'body' || tag === 'tbody' && top().tag === 'tbody') {
      // 这些包裹层不影响查询，但保留节点结构
    }

    // 隐式 tbody：tr 直接挂在 table 下时补一层
    if (tag === 'tr' && top().tag === 'table') {
      const tb = new __El('tbody', {});
      __append(top(), tb);
      stack.push(tb);
    }
    if ((tag === 'td' || tag === 'th') && top().tag === 'table') {
      const tb = new __El('tbody', {});
      __append(top(), tb);
      stack.push(tb);
      const tr = new __El('tr', {});
      __append(top(), tr);
      stack.push(tr);
    }

    // 同标签自动闭合
    if (__SELF_CLOSE_SAME.has(tag)) {
      while (stack.length > 1 && top().tag === tag) stack.pop();
    }
    // <p> 遇到块级元素先关掉
    if (__P_CLOSERS.has(tag)) {
      while (stack.length > 1 && top().tag === 'p') stack.pop();
    }

    const el = new __El(tag, attrs);
    __append(top(), el);

    if (selfClose || __VOID.has(tag)) continue;

    if (__RAW.has(tag)) {
      const close = '</' + tag;
      const end = html.toLowerCase().indexOf(close, i);
      const raw = html.slice(i, end < 0 ? n : end);
      if (raw) {
        const t = new __Tx(raw);
        __append(el, t);
      }
      if (end < 0) { i = n; } else {
        const gt = html.indexOf('>', end);
        i = gt < 0 ? n : gt + 1;
      }
      continue;
    }

    stack.push(el);
  }

  return root;
}

// ---------------------------------------------------------------- 选择器

function __parseSelector(sel) {
  // 返回 [[{compound, combinator}, ...], ...]  逗号分组
  const groups = [];
  let cur = [];
  let buf = '';
  let depth = 0;
  let quote = null;
  const flushCompound = () => {
    const c = buf.trim();
    buf = '';
    return c;
  };
  const pushGroup = () => {
    const c = flushCompound();
    if (c) cur.push({ compound: __parseCompound(c), combinator: cur.length === 0 ? null : ' ' });
    if (cur.length) groups.push(cur);
    cur = [];
  };

  for (let i = 0; i < sel.length; i++) {
    const ch = sel[i];
    if (quote) { buf += ch; if (ch === quote) quote = null; continue; }
    if (ch === '"' || ch === "'") { quote = ch; buf += ch; continue; }
    if (ch === '[') { depth++; buf += ch; continue; }
    if (ch === ']') { depth--; buf += ch; continue; }
    if (depth > 0) { buf += ch; continue; }
    if (ch === ',') { pushGroup(); continue; }
    if (/\s/.test(ch)) {
      // 空格是后代组合器。以前这里只 clear 了 buf、没把片段推进 cur，
      // 结果 "table.x > tbody > tr" 会被解析成纯 "tr"，所有带空格的选择器全部失效。
      const c = flushCompound();
      if (c) cur.push({ compound: __parseCompound(c), combinator: cur.length === 0 ? null : ' ' });
      continue;
    }
    if (ch === '>') {
      const c = flushCompound();
      if (c) cur.push({ compound: __parseCompound(c), combinator: cur.length === 0 ? null : ' ' });
      cur.push({ combinator: '>', compound: null });
      continue;
    }
    buf += ch;
  }
  pushGroup();

  // 修正：把 {"combinator":'>'} 这种占位符与后一个 compound 合并
  const out = [];
  for (const g of groups) {
    const seq = [];
    let comb = null;
    for (const item of g) {
      if (item.compound === null) { comb = '>'; continue; }
      seq.push({ compound: item.compound, combinator: seq.length === 0 ? null : (comb || ' ') });
      comb = null;
    }
    if (seq.length) out.push(seq);
  }
  return out;
}

function __parseCompound(str) {
  const c = { tag: null, id: null, classes: [], attrs: [], nth: [] };
  let i = 0;
  const n = str.length;
  while (i < n) {
    const ch = str[i];
    if (ch === '*') { i++; continue; }
    if (ch === '#') {
      let j = i + 1; let s = '';
      while (j < n && /[\w\-\u00a0-\uffff]/.test(str[j])) s += str[j++];
      c.id = s; i = j; continue;
    }
    if (ch === '.') {
      let j = i + 1; let s = '';
      while (j < n && /[\w\-\u00a0-\uffff]/.test(str[j])) s += str[j++];
      c.classes.push(s); i = j; continue;
    }
    if (ch === '[') {
      const end = str.indexOf(']', i);
      if (end < 0) break;
      const body = str.slice(i + 1, end);
      const am = /^\s*([\w\-:]+)\s*(?:([*^$~|]?=)\s*(.*?)\s*)?$/.exec(body);
      if (am) {
        let v = am[3] === undefined ? null : am[3];
        if (v != null) {
          if ((v[0] === '"' && v[v.length - 1] === '"') || (v[0] === "'" && v[v.length - 1] === "'")) {
            v = v.slice(1, -1);
          }
        }
        c.attrs.push({ name: am[1].toLowerCase(), op: am[2] || null, value: v });
      }
      i = end + 1; continue;
    }
    if (ch === ':') {
      const m = /^:([\w-]+)(?:\(([^)]*)\))?/.exec(str.slice(i));
      if (m) {
        c.nth.push({ name: m[1], arg: m[2] });
        i += m[0].length; continue;
      }
      i++; continue;
    }
    // 标签名
    let j = i; let s = '';
    while (j < n && /[\w\-\u00a0-\uffff]/.test(str[j])) s += str[j++];
    if (s) { c.tag = s.toLowerCase(); i = j; continue; }
    i++;
  }
  return c;
}

function __matchCompound(el, c) {
  if (!el || el.isText) return false;
  if (c.tag && el.tag !== c.tag) return false;
  if (c.id != null) {
    if ((el.attrs.id || '') !== c.id) return false;
  }
  if (c.classes.length) {
    const cls = (el.attrs['class'] || '').split(/\s+/);
    for (const want of c.classes) {
      if (!cls.includes(want)) return false;
    }
  }
  for (const a of c.attrs) {
    const v = el.attrs[a.name];
    if (v === undefined) return false;
    if (a.op === null || a.value === null) continue;
    const target = a.value;
    switch (a.op) {
      case '=': if (v !== target) return false; break;
      case '*=': if (!v.includes(target)) return false; break;
      case '^=': if (!v.startsWith(target)) return false; break;
      case '$=': if (!v.endsWith(target)) return false; break;
      case '~=': if (!v.split(/\s+/).includes(target)) return false; break;
      case '|=': if (!(v === target || v.startsWith(target + '-'))) return false; break;
      default: break;
    }
  }
  for (const p of c.nth) {
    if (p.name === 'nth-child') {
      const idx = parseInt(p.arg, 10);
      if (!isFinite(idx)) continue;
      const parent = el.parent;
      if (!parent) return false;
      const siblings = parent.children.filter(x => !x.isText);
      if (siblings.indexOf(el) + 1 !== idx) return false;
    } else if (p.name === 'first-child') {
      const parent = el.parent;
      if (!parent) return false;
      const siblings = parent.children.filter(x => !x.isText);
      if (siblings[0] !== el) return false;
    } else if (p.name === 'last-child') {
      const parent = el.parent;
      if (!parent) return false;
      const siblings = parent.children.filter(x => !x.isText);
      if (siblings[siblings.length - 1] !== el) return false;
    }
  }
  return true;
}

function __matchSeq(el, seq, idx) {
  if (!__matchCompound(el, seq[idx].compound)) return false;
  if (idx === 0) return true;
  const comb = seq[idx].combinator;
  if (comb === '>') {
    const p = el.parent;
    if (!p || p.tag === '#root') return false;
    return __matchSeq(p, seq, idx - 1);
  }
  let p = el.parent;
  while (p && p.tag !== '#root') {
    if (__matchSeq(p, seq, idx - 1)) return true;
    p = p.parent;
  }
  return false;
}

function __matches(el, groups) {
  for (const seq of groups) {
    if (__matchSeq(el, seq, seq.length - 1)) return true;
  }
  return false;
}

function __walk(el, cb) {
  for (const child of el.children) {
    if (child.isText) continue;
    if (cb(child) === false) return false;
    if (__walk(child, cb) === false) return false;
  }
  return true;
}

function __collect(el, groups, out, limit) {
  __walk(el, (child) => {
    if (__matches(child, groups)) {
      out.push(child);
      if (limit && out.length >= limit) return false;
    }
    return true;
  });
}

// ---------------------------------------------------------------- 文本

function __nodeText(node, out) {
  if (node.isText) { out.push(node.data); return; }
  const block = __BLOCK.has(node.tag);
  if (block) out.push(' ');
  for (const c of node.children) __nodeText(c, out);
  if (block) out.push(' ');
}

function __normText(s) {
  return s.replace(/[\u00a0\u3000]/g, ' ').replace(/\s+/g, ' ').trim();
}

function __innerHTML(node) {
  let s = '';
  for (const c of node.children) {
    if (c.isText) {
      s += c.data.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
    } else {
      s += '<' + c.tag;
      for (const k in c.attrs) s += ' ' + k + '="' + String(c.attrs[k]).replace(/"/g, '&quot;') + '"';
      s += '>' + __innerHTML(c) + '</' + c.tag + '>';
    }
  }
  return s;
}

// ---------------------------------------------------------------- 对外类

class HtmlDocument {
  constructor(html) {
    this._root = __parseHTML(html == null ? '' : String(html));
    this._cache = new Map();
  }

  _groups(query) {
    let g = this._cache.get(query);
    if (!g) { g = __parseSelector(query); this._cache.set(query, g); }
    return g;
  }

  querySelector(query) {
    const out = [];
    __collect(this._root, this._groups(query), out, 1);
    return out.length ? new HtmlElement(out[0], this) : null;
  }

  querySelectorAll(query) {
    const out = [];
    __collect(this._root, this._groups(query), out, 0);
    return out.map(x => new HtmlElement(x, this));
  }

  getElementById(id) {
    const out = [];
    __walk(this._root, (el) => {
      if ((el.attrs.id || '') === id) { out.push(el); return false; }
      return true;
    });
    return out.length ? new HtmlElement(out[0], this) : null;
  }

  dispose() { this._root = null; this._cache.clear(); }
}

class HtmlElement {
  constructor(node, doc) {
    this._n = node;
    this.doc = doc;
  }

  get _groups() { return this.doc._groups.bind(this.doc); }

  get text() {
    const out = [];
    __nodeText(this._n, out);
    // 官方 text 是 textContent 语义，不做 trim。
    // E-Hentai 会拿 " Add to Favorites"（前导空格）做比较，trim 过就判断反了。
    return out.join('').replace(/[\u00a0\u3000]/g, ' ');
  }

  get attributes() {
    const o = {};
    for (const k in this._n.attrs) o[k] = this._n.attrs[k];
    return o;
  }

  querySelector(query) {
    const out = [];
    __collect(this._n, this.doc._groups(query), out, 1);
    return out.length ? new HtmlElement(out[0], this.doc) : null;
  }

  querySelectorAll(query) {
    const out = [];
    __collect(this._n, this.doc._groups(query), out, 0);
    return out.map(x => new HtmlElement(x, this.doc));
  }

  get children() {
    return this._n.children.filter(x => !x.isText).map(x => new HtmlElement(x, this.doc));
  }

  get nodes() {
    return this._n.children.map(x => new HtmlNode(x, this.doc));
  }

  get innerHTML() { return __innerHTML(this._n); }

  get parent() {
    const p = this._n.parent;
    if (!p || p.tag === '#root') return null;
    return new HtmlElement(p, this.doc);
  }

  get classNames() {
    const v = this._n.attrs['class'];
    return v ? v.split(/\s+/).filter(Boolean) : [];
  }

  get id() {
    const v = this._n.attrs.id;
    return v === undefined ? null : v;
  }

  get localName() { return this._n.tag; }

  get tag() { return this._n.tag; }

  get className() { return this._n.attrs['class'] || ''; }

  getAttribute(name) {
    const v = this._n.attrs[String(name).toLowerCase()];
    return v === undefined ? null : v;
  }

  hasAttribute(name) { return Object.prototype.hasOwnProperty.call(this._n.attrs, String(name).toLowerCase()); }

  _sibling(dir) {
    const p = this._n.parent;
    if (!p) return null;
    const sib = p.children.filter(x => !x.isText);
    const idx = sib.indexOf(this._n) + dir;
    if (idx < 0 || idx >= sib.length) return null;
    return new HtmlElement(sib[idx], this.doc);
  }

  get previousElementSibling() { return this._sibling(-1); }

  get nextElementSibling() { return this._sibling(1); }
}

class HtmlNode {
  constructor(node, doc) {
    this._n = node;
    this.doc = doc;
  }

  get text() {
    if (this._n.isText) return __normText(this._n.data);
    const out = [];
    __nodeText(this._n, out);
    return __normText(out.join(''));
  }

  get type() { return this._n.isText ? 'text' : 'element'; }

  toElement() {
    if (this._n.isText) return null;
    return new HtmlElement(this._n, this.doc);
  }
}

function log(level, title, content) {
    sendMessage({
        method: 'log',
        level: level,
        title: title,
        content: content,
    })
}

let console = {
    log: (content) => {
        log('info', 'JS Console', content)
    },
    warn: (content) => {
        log('warning', 'JS Console', content)
    },
    error: (content) => {
        log('error', 'JS Console', content)
    },
};

/**
 * Create a comic object
 * @param id {string}
 * @param title {string}
 * @param subtitle {string}
 * @param subTitle {string} - equal to subtitle
 * @param cover {string}
 * @param tags {string[]}
 * @param description {string}
 * @param maxPage {number?}
 * @param language {string?}
 * @param favoriteId {string?} - Only set this field if the comic is from favorites page
 * @param stars {number?} - 0-5, double
 * @constructor
 */
function Comic({id, title, subtitle, subTitle, cover, tags, description, maxPage, language, favoriteId, stars}) {
    this.id = id;
    this.title = title;
    this.subtitle = subtitle;
    this.subTitle = subTitle;
    this.cover = cover;
    this.tags = tags;
    this.description = description;
    this.maxPage = maxPage;
    this.language = language;
    this.favoriteId = favoriteId;
    this.stars = stars;
}

/**
 * Create a comic details object
 * @param title {string}
 * @param subtitle {string}
 * @param subTitle {string} - equal to subtitle
 * @param cover {string}
 * @param description {string?}
 * @param tags {Map<string, string[]> | {} | null | undefined}
 * @param chapters {Map<string, string> | {} | null | undefined} - key: chapter id, value: chapter title
 * @param isFavorite {boolean | null | undefined} - favorite status.
 * @param subId {string?} - a param which is passed to comments api
 * @param thumbnails {string[]?} - for multiple page thumbnails, set this to null, and use `loadThumbnails` api to load thumbnails
 * @param recommend {Comic[]?} - related comics
 * @param commentCount {number?}
 * @param likesCount {number?}
 * @param isLiked {boolean?}
 * @param uploader {string?}
 * @param updateTime {string?}
 * @param uploadTime {string?}
 * @param url {string?}
 * @param stars {number?} - 0-5, double
 * @param maxPage {number?}
 * @param comments {Comment[]?}- `since 1.0.7` App will display comments in the details page.
 * @constructor
 */
function ComicDetails({title, subtitle, subTitle, cover, description, tags, chapters, isFavorite, subId, thumbnails, recommend, commentCount, likesCount, isLiked, uploader, updateTime, uploadTime, url, stars, maxPage, comments}) {
    this.title = title;
    this.subtitle = subtitle ?? subTitle;
    this.cover = cover;
    this.description = description;
    this.tags = tags;
    this.chapters = chapters;
    this.isFavorite = isFavorite;
    this.subId = subId;
    this.thumbnails = thumbnails;
    this.recommend = recommend;
    this.commentCount = commentCount;
    this.likesCount = likesCount;
    this.isLiked = isLiked;
    this.uploader = uploader;
    this.updateTime = updateTime;
    this.uploadTime = uploadTime;
    this.url = url;
    this.stars = stars;
    this.maxPage = maxPage;
    this.comments = comments;
}

/**
 * Create a comment object
 * @param userName {string}
 * @param avatar {string?}
 * @param content {string}
 * @param time {string?}
 * @param replyCount {number?}
 * @param id {string?}
 * @param isLiked {boolean?}
 * @param score {number?}
 * @param voteStatus {number?} - 1: upvote, -1: downvote, 0: none
 * @constructor
 */
function Comment({userName, avatar, content, time, replyCount, id, isLiked, score, voteStatus}) {
    this.userName = userName;
    this.avatar = avatar;
    this.content = content;
    this.time = time;
    this.replyCount = replyCount;
    this.id = id;
    this.isLiked = isLiked;
    this.score = score;
    this.voteStatus = voteStatus;
}

/**
 * Create image loading config
 * @param url {string?}
 * @param method {string?} - http method, uppercase
 * @param data {any} - request data, may be null
 * @param headers {Object?} - request headers
 * @param onResponse {((ArrayBuffer) => ArrayBuffer)?} - modify response data
 * @param modifyImage {string?}
 *  A js script string.
 *  The script will be executed in a new Isolate.
 *  A function named `modifyImage` should be defined in the script, which receives an [Image] as the only argument, and returns an [Image]..
 * @param onLoadFailed {(() => ImageLoadingConfig)?} - called when the image loading failed
 * @constructor
 * @since 1.0.5
 *
 * To keep the compatibility with the old version, do not use the constructor. Consider creating a new object with the properties directly.
 */
function ImageLoadingConfig({url, method, data, headers, onResponse, modifyImage, onLoadFailed}) {
    this.url = url;
    this.method = method;
    this.data = data;
    this.headers = headers;
    this.onResponse = onResponse;
    this.modifyImage = modifyImage;
    this.onLoadFailed = onLoadFailed;
}

class ComicSource {
    name = ""

    key = ""

    version = ""

    minAppVersion = ""

    url = ""

    /**
     * load data with its key
     * @param {string} dataKey
     * @returns {any}
     */
    loadData(dataKey) {
        return sendMessage({
            method: 'load_data',
            key: this.key,
            data_key: dataKey
        })
    }

    /**
     * load a setting with its key
     * @param key {string}
     * @returns {any}
     */
    loadSetting(key) {
        return sendMessage({
            method: 'load_setting',
            key: this.key,
            setting_key: key
        })
    }

    /**
     * save data
     * @param {string} dataKey
     * @param data
     */
    saveData(dataKey, data) {
        return sendMessage({
            method: 'save_data',
            key: this.key,
            data_key: dataKey,
            data: data
        })
    }

    /**
     * delete data
     * @param {string} dataKey
     */
    deleteData(dataKey) {
        return sendMessage({
            method: 'delete_data',
            key: this.key,
            data_key: dataKey,
        })
    }

    /**
     *
     * @returns {boolean}
     */
    get isLogged() {
        return sendMessage({
            method: 'isLogged',
            key: this.key,
        });
    }

    translation = {}

    /**
     * Translate given string with the current locale using the translation object.
     * @param key {string}
     * @returns {string}
     * @since 1.2.5
     */
    translate(key) {
        let locale = APP.locale;
        return this.translation[locale]?.[key] ?? key;
    }

    init() { }

    static sources = {}
}

/// A reference to dart object.
/// The api can only be used in the comic.onImageLoad.modifyImage function
class Image {
    key = 0;

    constructor(key) {
        this.key = key;
    }

    /**
     * Copy the specified range of the image
     * @param x
     * @param y
     * @param width
     * @param height
     * @returns {Image|null}
     */
    copyRange(x, y, width, height) {
        let key = sendMessage({
            method: "image",
            function: "copyRange",
            key: this.key,
            x: x,
            y: y,
            width: width,
            height: height
        })
        if(key == null) return null;
        return new Image(key);
    }

    /**
     * Copy the image and rotate 90 degrees
     * @returns {Image|null}
     */
    copyAndRotate90() {
        let key = sendMessage({
            method: "image",
            function: "copyAndRotate90",
            key: this.key
        })
        if(key == null) return null;
        return new Image(key);
    }

    /**
     * fill [image] to this image at (x, y)
     * @param x
     * @param y
     * @param image
     */
    fillImageAt(x, y, image) {
        sendMessage({
            method: "image",
            function: "fillImageAt",
            key: this.key,
            x: x,
            y: y,
            image: image.key
        })
    }

    /**
     * fill [image] with range(srcX, srcY, width, height) to this image at (x, y)
     * @param x
     * @param y
     * @param image
     * @param srcX
     * @param srcY
     * @param width
     * @param height
     */
    fillImageRangeAt(x, y, image, srcX, srcY, width, height) {
        sendMessage({
            method: "image",
            function: "fillImageRangeAt",
            key: this.key,
            x: x,
            y: y,
            image: image.key,
            srcX: srcX,
            srcY: srcY,
            width: width,
            height: height
        })
    }

    get width() {
        return sendMessage({
            method: "image",
            function: "getWidth",
            key: this.key
        })
    }

    get height() {
        return sendMessage({
            method: "image",
            function: "getHeight",
            key: this.key
        })
    }

    static empty(width, height) {
        let key = sendMessage({
            method: "image",
            function: "emptyImage",
            width: width,
            height: height
        })
        return new Image(key);
    }
}

/**
 * UI related apis
 * @since 1.2.0
 */
let UI = {
    /**
     * Show a message
     * @param message {string}
     */
    showMessage: (message) => {
        sendMessage({
            method: 'UI',
            function: 'showMessage',
            message: message,
        })
    },

    /**
     * Show a dialog. Any action will close the dialog.
     * @param title {string}
     * @param content {string}
     * @param actions {{text:string, callback: () => void | Promise<void>, style: "text"|"filled"|"danger"}[]} - If callback returns a promise, the button will show a loading indicator until the promise is resolved.
     * @returns {Promise<void>} - Resolved when the dialog is closed.
     * @since 1.2.1
     */
    showDialog: (title, content, actions) => {
        sendMessage({
            method: 'UI',
            function: 'showDialog',
            title: title,
            content: content,
            actions: actions,
        })
    },

    /**
     * Open [url] in external browser
     * @param url {string}
     */
    launchUrl: (url) => {
        sendMessage({
            method: 'UI',
            function: 'launchUrl',
            url: url,
        })
    },

    /**
     * Show a loading dialog.
     * @param onCancel {() => void | null | undefined} - Called when the loading dialog is canceled. If [onCancel] is null, the dialog cannot be canceled by the user.
     * @returns {number} - A number that can be used to cancel the loading dialog.
     * @since 1.2.1
     */
    showLoading: (onCancel) => {
        return sendMessage({
            method: 'UI',
            function: 'showLoading',
            onCancel: onCancel
        })
    },

    /**
     * Cancel a loading dialog.
     * @param id {number} - returned by [showLoading]
     * @since 1.2.1
     */
    cancelLoading: (id) => {
        sendMessage({
            method: 'UI',
            function: 'cancelLoading',
            id: id
        })
    },

    /**
     * Show an input dialog
     * @param title {string}
     * @param validator {(string) => string | null | undefined} - A function that validates the input. If the function returns a string, the dialog will show the error message.
     * @param image {string | ArrayBuffer | null | undefined} - Since 1.4.6, you can pass an image url to show an image in the dialog. Since 1.5.3, you can also pass an ArrayBuffer to show a custom image.
     * @returns {Promise<string | null>} - The input value. If the dialog is canceled, return null.
     */
    showInputDialog: (title, validator, image) => {
        return sendMessage({
            method: 'UI',
            function: 'showInputDialog',
            title: title,
            image: image,
            validator: validator
        })
    },

    /**
     * Show a select dialog
     * @param title {string}
     * @param options {string[]}
     * @param initialIndex {number?}
     * @returns {Promise<number | null>} - The selected index. If the dialog is canceled, return null.
     */
    showSelectDialog: (title, options, initialIndex) => {
        return sendMessage({
            method: 'UI',
            function: 'showSelectDialog',
            title: title,
            options: options,
            initialIndex: initialIndex
        })
    }
}

/**
 * App related apis
 * @since 1.2.1
 */
let APP = {
    /**
     * Get the app version
     * @returns {string} - The app version
     */
    get version() {
        return appVersion // defined in the engine
    },

    /**
     * Get current app locale
     * @returns {string} - The app locale, in the format of [languageCode]_[countryCode]
     */
    get locale() {
        return sendMessage({
            method: 'getLocale'
        })
    },

    /**
     * Get current running platform
     * @returns {string} - The platform name, "android", "ios", "windows", "macos", "linux"
     */
    get platform() {
        return sendMessage({
            method: 'getPlatform'
        })
    }
}

/**
 * Set clipboard text
 * @param text {string}
 * @returns {Promise<void>}
 * 
 * @since 1.3.4
 */
function setClipboard(text) {
    return sendMessage({
        method: 'setClipboard',
        text: text
    })
}

/**
 * Get clipboard text
 * @returns {Promise<string>}
 * 
 * @since 1.3.4
 */
function getClipboard() {
    return sendMessage({
        method: 'getClipboard'
    })
}

/**
 * Compute a function with arguments. The function will be executed in the engine pool which is not in the main thread.
 * @param func {string} - A js code string which can be evaluated to a function. The function will receive the args as its only argument.
 * @param args {any[]} - The arguments to pass to the function.
 * @returns {Promise<any>} - The result of the function.
 * @since 1.5.0
 */
function compute(func, ...args) {
    return sendMessage({
        method: 'compute',
        function: func,
        args: args
    })
}

// ===== 线路层 =====
/*
 * 线路层（三端共用，纯逻辑、无平台依赖）
 *
 * 目的：国内能直连的源走直连，直连不通的自动回落到 VPN 域名，两种都不打断。
 * 做法：不改源脚本，只在宿主 HTTP 出口把 URL 的 host 换成当前通道的目标 host。
 *
 * 概念：
 *   source  = 一个漫画源（jm / copy_manga / Komiic ...）
 *   channel = 一条通道（"直连" 或 "VPN"），包含
 *             hosts[]  该通道的一组候选主机（探测选最快的）
 *             map[]    主机改写规则，to 为空表示用本通道选中的 host
 *
 * 生命周期：
 *   R.init(table, store)   读 routes.json（obj 或 JSON 串），恢复上次状态
 *   R.probe(probeFn)       并发探测各通道，选第一个可用的（直连在前）
 *   R.rewrite(url)         请求前调用，返回改写后的 url（prelude 的 sendMessage 里调）
 *   R.report(url, status)  请求后调用，失败则换 host / 换通道 / 回落 VPN
 *
 * state[key] 字段：
 *   ci      当前通道序号
 *   host    当前主机（探测选中的，或 hosts[0] 的默认值）
 *   bad     通道内已判死的主机
 *   pick    true = ci/host 来自探测结果；false = 还只是默认值，UI 不能当「直连」显示
 *   offline 所有通道都不通
 */
(function (root, factory) {
  if (typeof module === 'object' && module.exports) module.exports = factory();
  else root.ComicRoute = factory();
})(typeof globalThis !== 'undefined' ? globalThis : this, function () {
  var S = {
    table: null,
    probe: { timeoutMs: 3000, okStatusMax: 499, cacheHours: 12 },
    state: {},        // key -> { ci, host, bad:[], at, probed, offline }
    store: null,
    probing: null
  };

  function now() { return Date.now(); }

  function init(table, store) {
    S.table = typeof table === 'string' ? JSON.parse(table) : table;
    S.probe = S.table.probe || S.probe;
    S.store = store || null;
    var saved = null;
    if (S.store) { try { saved = S.store.load(); } catch (e) { saved = null; } }
    S.state = (saved && saved.version === S.table.version && saved.state) ? saved.state : {};

    S.table.sources.forEach(function (src) {
      var st = S.state[src.key];
      if (!st || typeof st.ci !== 'number') {
        S.state[src.key] = {
          ci: 0,
          host: (src.channels[0].hosts || [])[0] || '',
          bad: [],
          at: 0,
          probed: false,
          pick: false,
          offline: false
        };
      } else if (typeof st.pick !== 'boolean') {
        // 老版本存下来的状态没有 pick，按「已探测」处理，别把用户手上的直连清掉
        st.pick = !!st.probed;
      }
    });
    persist();
    return S;
  }

  function persist() {
    if (!S.store) return;
    try { S.store.save({ version: S.table && S.table.version, state: S.state }); } catch (e) { }
  }

  function src(key) {
    if (!S.table) return null;
    for (var i = 0; i < S.table.sources.length; i++) if (S.table.sources[i].key === key) return S.table.sources[i];
    return null;
  }

  function chan(s, i) { return s && s.channels && s.channels[i] ? s.channels[i] : null; }

  function cur(key) {
    var s = src(key);
    if (!s) return null;
    var st = S.state[key];
    if (!st) return null;
    var c = chan(s, st.ci) || s.channels[0];
    return { src: s, st: st, ch: c };
  }

  function hostOf(url) {
    try { return new URL(url).hostname; } catch (e) {
      var m = /^[a-z][a-z0-9+.-]*:\/\/([^\/:?#]+)/i.exec(url);
      return m ? m[1] : '';
    }
  }

  /** 找 URL 属于哪个源（按各通道所有 map 规则的并集判断） */
  function matchSource(host) {
    if (!S.table) return null;
    for (var i = 0; i < S.table.sources.length; i++) {
      var s = S.table.sources[i];
      for (var j = 0; j < s.channels.length; j++) {
        var map = s.channels[j].map || [];
        for (var k = 0; k < map.length; k++) {
          try { if (new RegExp(map[k].re).test(host)) return s; } catch (e) { }
        }
      }
    }
    return null;
  }

  function rewrite(url) {
    if (!S.table || typeof url !== 'string') return url;
    var host = hostOf(url);
    if (!host) return url;
    var s = matchSource(host);
    if (!s) return url;
    // 由源脚本自己维护线路的源（禁漫：init 里拉官方线路表，比这里硬编码的新）不改写，
    // 否则会把源刚探到的新域名又改回本地表里的老域名。
    if (s.rewrite === false) return url;
    var c = cur(s.key);
    if (!c || !c.ch) return url;
    var map = c.ch.map || [];
    for (var i = 0; i < map.length; i++) {
      var rule = map[i];
      if (!new RegExp(rule.re).test(host)) continue;
      var to = rule.to || c.st.host;
      if (!to || to === host) return url;
      return url.replace('://' + host, '://' + to);
    }
    return url;
  }

  /** 换到本通道的下一个 host；通道内全坏就整条切换到下一通道 */
  function advance(key) {
    var c = cur(key);
    if (!c) return null;
    var st = c.st, s = c.src;
    var left = (c.ch.hosts || []).filter(function (h) { return st.bad.indexOf(h) < 0; });
    if (left.length) { st.host = left[0]; st.pick = true; return { host: st.host, channel: c.ch.name, line: c.ch.line }; }
    // 本通道没得选了，按顺序找下一条通道（直连 → VPN）
    for (var i = 0; i < s.channels.length; i++) {
      if (i === st.ci) continue;
      var ch = s.channels[i];
      var hs = (ch.hosts || []).filter(function (h) { return st.bad.indexOf(h) < 0; });
      if (hs.length) { st.ci = i; st.host = hs[0]; st.pick = true; return { host: st.host, channel: ch.name, line: ch.line }; }
    }
    return null;
  }

  function report(url, status) {
    var host = hostOf(url);
    if (!host) return null;
    var s = matchSource(host);
    if (!s) return null;
    if (s.rewrite === false) return null;
    var st = S.state[s.key];
    if (!st) return null;
    var ok = status && status >= 200 && status <= (S.probe.okStatusMax || 499);
    if (ok) {
      // 通了就把它从坏点里摘掉：代理开关过的场景下，同一个 host 会先死再活
      var bi = st.bad.indexOf(host);
      if (bi >= 0) { st.bad.splice(bi, 1); persist(); }
      if (st.offline) { st.offline = false; persist(); }
      return null;
    }
    var c = cur(s.key);
    var mine = c && c.ch && (c.ch.hosts || []).indexOf(host) >= 0;
    // 封面/图片走的是 CDN 域名，它挂了不代表 API 线路挂了——不碰状态。
    if (!mine) return null;
    // 只把「当前通道的打点 host」记坏，避免图片/静态域名带偏
    if (st.bad.indexOf(host) < 0) st.bad.push(host);
    var from = st.host;
    var r = advance(s.key);
    st.at = now();
    persist();
    if (!r) { st.offline = true; persist(); return null; }
    return { key: s.key, from: from, to: r.host, channel: r.channel, line: r.line };
  }

  /** 探测确认某源所有通道都不通 */
  function markOffline(key) {
    var st = S.state[key];
    if (!st) return false;
    st.probed = true;
    st.at = now();
    st.offline = true;
    st.pick = false;
    persist();
    return true;
  }

  function setChannel(key, ci, host) {
    var s = src(key), st = S.state[key];
    if (!s || !st || !s.channels[ci]) return false;
    st.ci = ci;
    st.host = host || (s.channels[ci].hosts || [])[0] || '';
    st.bad = [];
    st.at = now();
    st.probed = true;
    st.pick = !!host;
    st.offline = false;
    persist();
    return true;
  }

  function setHost(key, host) {
    var s = src(key), st = S.state[key];
    if (!s || !st) return false;
    for (var i = 0; i < s.channels.length; i++) {
      if ((s.channels[i].hosts || []).indexOf(host) >= 0) return setChannel(key, i, host);
    }
    st.host = host;
    persist();
    return true;
  }

  /** 并发探一组 host，返回最快成功的那个 */
  function raceFirst(hosts, probeFn) {
    if (!hosts || !hosts.length) return Promise.resolve('');
    var best = null, left = hosts.length;
    return new Promise(function (resolve) {
      hosts.forEach(function (h) {
        var t0 = now();
        Promise.resolve().then(function () { return probeFn(h); }).then(function (ok) {
          if (ok) {
            var cost = now() - t0;
            if (best === null || cost < best.cost) best = { host: h, cost: cost };
          }
        }, function () { }).then(function () {
          if (--left === 0) resolve(best ? best.host : '');
        });
      });
    });
  }

  /**
   * 探测择优：每个源按通道顺序试，第一条探通的通道胜出。
   * probeFn(host) -> Promise<bool>。force=true 忽略缓存。
   */
  function probe(probeFn, force) {
    if (!S.table) return Promise.resolve({});
    if (S.probing) return S.probing;
    var fresh = (S.probe.cacheHours || 12) * 3600 * 1000;

    var todo = S.table.sources.filter(function (s) {
      var st = S.state[s.key];
      if (!st) return false;
      if (force) return true;
      if (st.probed && now() - st.at < fresh) return false;
      return s.channels.some(function (c) { return (c.hosts || []).length; });
    });
    if (!todo.length) return Promise.resolve(S.state);

    S.probing = Promise.all(todo.map(function (s) {
      var st = S.state[s.key];
      var idx = 0;
      function next() {
        if (idx >= s.channels.length) {
          st.probed = true; st.at = now(); st.offline = true;
          return Promise.resolve();
        }
        var c = s.channels[idx];
        return raceFirst(c.hosts || [], probeFn).then(function (hit) {
          if (hit) {
            st.ci = idx; st.host = hit; st.bad = [];
            st.probed = true; st.at = now(); st.offline = false;
            return;
          }
          idx++;
          return next();
        });
      }
      return next();
    })).then(function () {
      S.probing = null;
      persist();
      return S.state;
    }, function () { S.probing = null; return S.state; });
    return S.probing;
  }

  /** 给 UI 用：每个源当前线路。pick=false 表示还没探过，UI 不能直接写「直连」 */
  function status() {
    if (!S.table) return [];
    return S.table.sources.map(function (s) {
      var st = S.state[s.key] || {};
      var c = chan(s, st.ci || 0) || s.channels[0];
      return {
        key: s.key,
        label: s.label,
        host: st.host || '',
        channel: c ? c.name : '',
        line: c ? c.line : '',
        offline: !!st.offline,
        pick: !!st.pick,
        probed: !!st.probed,
        channels: s.channels.map(function (x) { return { name: x.name, line: x.line, hosts: x.hosts || [], probe: x.probe || '' }; }),
        note: s.note || ''
      };
    });
  }

  return {
    init: init,
    probe: probe,
    rewrite: rewrite,
    report: report,
    markOffline: markOffline,
    setHost: setHost,
    setChannel: setChannel,
    status: status,
    hostOf: hostOf,
    _state: function () { return S; },
    _reset: function () { S.state = {}; S.probing = null; }
  };
});

// ===== 源 =====
var __SOURCE_CODE = {};
__SOURCE_CODE["jm"] = "class JM extends ComicSource {\n    // Note: The fields which are marked as [Optional] should be removed if not used\n\n    // name of the source\n    name = \"\u7981\u6f2b\u5929\u5802\"\n\n    // unique id of the source\n    key = \"jm\"\n\n    version = \"2.0.0\"\n\n    minAppVersion = \"4.9.3\"\n\n    static jmVersion = \"2.0.16\"\n\n    static jmPkgName = \"com.example.app\"\n\n    // update url\n    url = \"https://raw.githubusercontent.com/ccbkv/pica_configs/refs/heads/master/jm.js\"\n\n    idMatcher = '^(\\\\d+|jm\\\\d+)$'\n\n    dailyCheckInInProgress = false\n\n    static fallbackServers = [\n        \"www.cdntwice.org\",\n        \"www.cdnsha.org\",\n        \"www.cdnaspa.cc\",\n        \"www.cdnntr.cc\",\n    ];\n\n    static imageUrl = \"https://cdn-msp.jmapinodeudzn.net\"\n\n    // cache parsed comment replies, format: comicId -> { commentId: [Comment] }\n    static commentReplies = {}\n\n    static ua = \"Mozilla/5.0 (Linux; Android 10; K; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/130.0.0.0 Mobile Safari/537.36\"\n\n    get ua() {\n        return JM.ua;\n    }\n\n    get ui() {\n        if (sendMessage({ method: \"jm_ui\", key: this.key, function: \"supported\" }) !== true) {\n            return UI\n        }\n        return {\n            showMessage: message => sendMessage({\n                method: \"jm_ui\", key: this.key, function: \"showMessage\", message\n            }),\n            showDialog: async (title, content, actions) => {\n                const index = await sendMessage({\n                    method: \"jm_ui\", key: this.key, function: \"showDialog\",\n                    title, content, actions: actions.map(action => action.text)\n                })\n                if (Number.isInteger(index) && index >= 0 && index < actions.length) {\n                    await actions[index].callback()\n                }\n            }\n        }\n    }\n\n    get baseUrl() {\n        let index = parseInt(this.loadSetting('apiDomain')) - 1\n        return `https://${JM.apiDomains[index]}`\n    }\n\n    get imageUrl() {\n        return JM.imageUrl\n    }\n\n    overwriteApiDomains(domains) {\n        if (domains.length != 0) JM.apiDomains = domains\n    }\n\n    overwriteImgUrl(url) {\n        if (url.length != 0) JM.imageUrl = url\n    }\n\n    isNum(str) {\n        return /^\\d+$/.test(str)\n    }\n\n    get baseHeaders() {\n        return {\n            \"Accept\": \"*/*\",\n            \"Accept-Encoding\": \"gzip, deflate, br, zstd\",\n            \"Accept-Language\": \"zh-CN,zh;q=0.9,en-US;q=0.8,en;q=0.7\",\n            \"Connection\": \"keep-alive\",\n            \"Origin\": \"https://localhost\",\n            \"Referer\": \"https://localhost/\",\n            \"Sec-Fetch-Dest\": \"empty\",\n            \"Sec-Fetch-Mode\": \"cors\",\n            \"Sec-Fetch-Site\": \"cross-site\",\n            \"X-Requested-With\": JM.jmPkgName,\n        }\n    }\n\n    getApiHeaders(time) {\n        if (this.loadSetting(\"dailyCheckInTask\")) {\n            this.dailyCheckIn(true).catch(error => console.error(String(error)))\n        }\n        const jmAuthKey = \"18comicAPPContent\"\n        let token = Convert.md5(Convert.encodeUtf8(`${time}${jmAuthKey}`))\n\n        return {\n            ...this.baseHeaders,\n            \"Authorization\": \"Bearer\",\n            \"Sec-Fetch-Storage-Access\": \"active\",\n            \"token\": Convert.hexEncode(token),\n            \"tokenparam\": `${time},${JM.jmVersion}`,\n            \"User-Agent\": this.ua,\n        }\n    }\n\n    getImgHeaders() {\n        return {\n            \"Accept\": \"image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8\",\n            \"Accept-Encoding\": \"gzip, deflate, br, zstd\",\n            \"Accept-Language\": \"zh-CN,zh;q=0.9,en-US;q=0.8,en;q=0.7\",\n            \"Connection\": \"keep-alive\",\n            \"Referer\": \"https://localhost/\",\n            \"Sec-Fetch-Dest\": \"image\",\n            \"Sec-Fetch-Mode\": \"no-cors\",\n            \"Sec-Fetch-Site\": \"cross-site\",\n            \"Sec-Fetch-Storage-Access\": \"active\",\n            \"User-Agent\": this.ua,\n            \"X-Requested-With\": JM.jmPkgName,\n        }\n    }\n\n    getCoverUrl(id) {\n        return `${this.imageUrl}/media/albums/${id}_3x4.jpg`\n    }\n\n    getImageUrl(id, imageName) {\n        return `${this.imageUrl}/media/photos/${id}/${imageName}`\n    }\n\n    getAvatarUrl(imageName) {\n        return `${this.imageUrl}/media/users/${imageName}`\n    }\n\n    async init() {\n        if (this.loadSetting('refreshDomainsOnStart')) await this.refreshApiDomains(false)\n        this.refreshImgUrl(false)\n    }\n\n    /**\n     *\n     * @param showConfirmDialog {boolean}\n     */\n    async refreshApiDomains(showConfirmDialog) {\n        let url = \"https://rup4a04-c02.tos-cn-hongkong.bytepluses.com/newsvr-2025.txt\"\n        let domainSecret = \"diosfjckwpqpdfjkvnqQjsik\"\n        let title = \"\"\n        let message = \"\"\n        let servers = []\n        let domains = []\n        let res = null;\n        try {\n            res = await fetch(url, { headers: this.baseHeaders });\n        } catch (error) {\n            res = null;\n        }\n        if (res && res.status === 200) {\n            let data = this.convertData(await res.text(), domainSecret)\n            let json = JSON.parse(data)\n            if (json[\"Server\"]) {\n                title = \"\u66f4\u65b0\u6210\u529f\"\n                message = \"\\n\"\n                servers = json[\"Server\"].slice(0, 4)\n            }\n        }\n        if (servers.length === 0) {\n            title = \"\u66f4\u65b0\u5931\u8d25\"\n            message = `\u4f7f\u7528\u5185\u7f6e\u57df\u540d:\\n\\n`\n            servers = JM.fallbackServers\n        }\n        for (let i = 0; i < servers.length; i++) {\n            message = message + `\u7dda\u8def${i + 1}:  ${servers[i]}\\n\\n`\n            domains.push(servers[i])\n        }\n        if (showConfirmDialog) {\n            await this.ui.showDialog(\n                title,\n                message,\n                [\n                    {\n                        text: \"\u53d6\u6d88\",\n                        callback: () => {}\n                    },\n                    {\n                        text: \"\u5e94\u7528\",\n                        callback: async () => {\n                            this.overwriteApiDomains(domains)\n                            await this.refreshImgUrl(true)\n                        }\n                    }\n                ]\n            )\n        } else {\n            this.overwriteApiDomains(domains)\n        }\n    }\n\n    /**\n     *\n     * @param showMessage {boolean}\n     */\n    async refreshImgUrl(showMessage) {\n        let index = this.loadSetting('imageStream')\n        let res = await this.get(\n            `${this.baseUrl}/setting?app_img_shunt=${index}&express=`\n        )\n        let setting = JSON.parse(res)\n        if (setting[\"img_host\"]) {\n            if (showMessage) {\n                this.ui.showMessage(`\u56fe\u7247\u5206\u6d41 ${index}:\\n${setting[\"img_host\"]}`)\n            }\n            this.overwriteImgUrl(setting[\"img_host\"])\n        }\n    }\n\n    /**\n     *\n     * @param comic {object}\n     * @returns {Comic}\n     */\n    parseComic(comic) {\n        let id = comic.id.toString()\n        let author = comic.author\n        let title = comic.name\n        let description = `JM${id}`\n        let cover = this.getCoverUrl(id)\n        let tags =[]\n        if(comic[\"category\"][\"title\"]) {\n            tags.push(comic[\"category\"][\"title\"])\n        }\n        if(comic[\"category_sub\"][\"title\"]) {\n            tags.push(comic[\"category_sub\"][\"title\"])\n        }\n        return new Comic({\n            id: id,\n            title: title,\n            subTitle: author,\n            cover: cover,\n            tags: tags,\n            description: description\n        })\n    }\n\n    /**\n     *\n     * @param input {string}\n     * @param secret {string}\n     * @returns {string}\n     */\n    convertData(input, secret) {  \n        let key = Convert.encodeUtf8(Convert.hexEncode(Convert.md5(Convert.encodeUtf8(secret))))\n        let data = Convert.decodeBase64(input)\n        let decrypted = Convert.decryptAesEcb(data, key)\n        let res = Convert.decodeUtf8(decrypted)\n        let start = 0\n        while (start < res.length && res[start] !== '{' && res[start] !== '[') {\n            start++\n        }\n        let end = res.length - 1\n        while(end > start && res[end] !== '}' && res[end] !== ']') {\n            end--\n        }\n        return res.substring(start, end + 1)\n    }\n\n    /**\n     *\n     * @param url {string}\n     * @returns {Promise<string>}\n     */\n    async get(url) {\n        let time = Math.floor(Date.now() / 1000)\n        let kJmSecret = \"185Hcomic3PAPP7R\"\n        let res = await Network.get(url, this.getApiHeaders(time))\n        if(res.status !== 200) {\n            if(res.status === 401) {\n                let json = JSON.parse(res.body)\n                let message = json.errorMsg\n                if(message === \"\u8acb\u5148\u767b\u5165\u6703\u54e1\" && this.isLogged) {\n                    throw 'Login expired'\n                }\n                throw message ?? 'Invalid Status Code: ' + res.status\n            }\n            throw 'Invalid Status Code: ' + res.status\n        }\n        let json = JSON.parse(res.body)\n        let data = json.data\n        if(typeof data !== 'string') {\n            throw 'Invalid Data'\n        }\n        return this.convertData(data, `${time}${kJmSecret}`)\n    }\n\n    async post(url, body) {\n        let time = Math.floor(Date.now() / 1000)\n        let kJmSecret = \"185Hcomic3PAPP7R\"\n        let res = await Network.post(url, {\n            ...this.getApiHeaders(time),\n            \"Content-Type\": \"application/x-www-form-urlencoded\"\n        }, body)\n        if(res.status !== 200) {\n            if(res.status === 401) {\n                let json = JSON.parse(res.body)\n                let message = json.errorMsg\n                if(message === \"\u8acb\u5148\u767b\u5165\u6703\u54e1\" && this.isLogged) {\n                    throw 'Login expired'\n                }\n                throw message ?? 'Invalid Status Code: ' + res.status\n            }\n            throw 'Invalid Status Code: ' + res.status\n        }\n        let json = JSON.parse(res.body)\n        let data = json.data\n        if(typeof data !== 'string') {\n            throw 'Invalid Data'\n        }\n        return this.convertData(data, `${time}${kJmSecret}`)\n    }\n\n    async dailyCheckIn(isTask = false) {\n        if (this.dailyCheckInInProgress) return\n        this.dailyCheckInInProgress = true\n        const throwError = (msg) => {\n            this.ui.showMessage(msg)\n            throw msg\n        }\n        try {\n            const lastCheckInDate = this.loadData(\"lastCheckInDate\")\n            const today = new Date().toLocaleDateString('zh-CN')\n            if (lastCheckInDate && lastCheckInDate === today) {\n                if (isTask) return\n                throwError(\"\u4eca\u65e5\u5df2\u7b7e\u5230\")\n            }\n            if (!this.isLogged) {\n                if (isTask) return\n                throwError(\"\u8bf7\u5148\u767b\u5f55\u518d\u7b7e\u5230\")\n            }\n            const uid = this.loadData(\"uid\")\n            if (!uid) {\n                throwError(\"\u65e0\u6548\u7684\u7528\u6237ID\uff0c\u8bf7\u91cd\u65b0\u767b\u5f55\")\n            }\n            const checkRecordRes = await this.get(`${this.baseUrl}/daily?user_id=${uid}`)\n            const checkRecord = JSON.parse(checkRecordRes)\n            if (!('daily_id' in checkRecord)) {\n                throwError(\"\u65e0\u6548\u7684\u7b7e\u5230ID\uff0c\u7b7e\u5230\u5931\u8d25\")\n            }\n            const daily_id = checkRecord.daily_id\n            const checkResultRes = await this.post(`${this.baseUrl}/daily_chk`, `user_id=${uid}&daily_id=${daily_id}`)\n            const checkResult = JSON.parse(checkResultRes)\n            if (!checkResult.msg) {\n                throwError(\"\u7b7e\u5230\u7ed3\u679c\u65e0\u6548\uff0c\u7b7e\u5230\u5931\u8d25\")\n            }\n            this.ui.showMessage(checkResult.msg)\n            this.saveData(\"lastCheckInDate\", today)\n        } finally {\n            this.dailyCheckInInProgress = false\n        }\n    }\n\n    // [Optional] account related\n    account = {\n        /**\n         * [Optional] login with account and password, return any value to indicate success\n         * @param account {string}\n         * @param pwd {string}\n         * @returns {Promise<any>}\n         */\n        login: async (account, pwd) => {\n            let time = Math.floor(Date.now() / 1000)\n            let res = await this.post(\n                `${this.baseUrl}/login`,\n                `username=${encodeURIComponent(account)}&password=${encodeURIComponent(pwd)}`\n            )\n            let json = JSON.parse(res)\n            // \u539f\u6765\u4e0d\u8bba\u6709\u6ca1\u6709 uid \u90fd\u8fd4\u56de \"ok\"\uff0c\u5bc6\u7801\u9519\u4e86\u4e5f\u4f1a\u663e\u793a\u300c\u5df2\u767b\u5f55\u300d\n            if (!json.uid) {\n                throw json.errorMsg || json.msg || '\u8d26\u53f7\u6216\u5bc6\u7801\u4e0d\u5bf9'\n            }\n            this.saveData(\"uid\", json.uid)\n            return \"ok\"\n        },\n\n        /**\n         * logout function, clear account related data\n         */\n        logout: () => {\n            for (let url of JM.apiDomains) {\n                Network.deleteCookies(url)\n            }\n        },\n\n        // {string?} - register url\n        registerWebsite: null\n    }\n\n    // explore page list\n    explore = [\n        {\n            // title of the page.\n            // title is used to identify the page, it should be unique\n            title: \"\u7981\u6f2b\u5929\u5802\",\n\n            /// multiPartPage or multiPageComicList or mixed\n            type: \"multiPartPage\",\n\n            /**\n             * load function\n             * @param page {number | null} - page number, null for `singlePageWithMultiPart` type\n             * @returns {{}}\n             * - for `multiPartPage` type, return [{title: string, comics: Comic[], viewMore: string?}]\n             * - for `multiPageComicList` type, for each page(1-based), return {comics: Comic[], maxPage: number}\n             * - for `mixed` type, use param `page` as index. for each index(0-based), return {data: [], maxPage: number?}, data is an array contains Comic[] or {title: string, comics: Comic[], viewMore: string?}\n             */\n            load: async (page) => {\n                let res = await this.get(`${this.baseUrl}/promote?page=0`)\n                let result = []\n\n                for(let e of JSON.parse(res)) {\n                    let title = e[\"title\"]\n                    let type = e.type\n                    let id = e.id.toString()\n                    if (type === 'category_id') {\n                        id = e.slug\n                    }\n                    if (['library', 'novels'].includes(type)) {\n                        continue\n                    }\n                    let comics = e.content.map((e) => this.parseComic(e))\n                    result.push({\n                        title: e.title,\n                        comics: comics,\n                        viewMore: `category:${title}@${id}`\n                    })\n                }\n\n                return result\n            },\n        }\n    ]\n\n    // categories\n    category = {\n        /// title of the category page, used to identify the page, it should be unique\n        title: \"\u7981\u6f2b\u5929\u5802\",\n        parts: [\n            {\n                name: \"\u6bcf\u9031\u5fc5\u770b\",\n                type: \"fixed\",\n                categories: [\"\u6bcf\u9031\u5fc5\u770b\"],\n                itemType: \"category\",\n            },\n            {\n                name: \"\u6210\u4ebaA\u6f2b\",\n                type: \"fixed\",\n                categories: [\"\u6700\u65b0A\u6f2b\", \"\u540c\u4eba\", \"\u55ae\u672c\", \"\u77ed\u7bc7\", \"\u5176\u4ed6\u985e\", \"\u97d3\u6f2b\", \"\u7f8e\u6f2b\", \"Cosplay\", \"3D\", \"\u7981\u6f2b\u6f22\u5316\u7d44\"],\n                itemType: \"category\",\n                categoryParams: [\n                    \"0\",\n                    \"doujin\",\n                    \"single\",\n                    \"short\",\n                    \"another\",\n                    \"hanman\",\n                    \"meiman\",\n                    \"another_cosplay\",\n                    \"3D\",\n                    \"\u7981\u6f2b\u6f22\u5316\u7d44\"\n                ],\n            },\n            {\n                name: \"\u4e3b\u984cA\u6f2b\",\n                type: \"fixed\",\n                categories: [\n                    '\u7121\u4fee\u6b63',\n                    '\u5287\u60c5\u5411',\n                    '\u9752\u5e74\u6f2b',\n                    '\u6821\u670d',\n                    '\u7d14\u611b',\n                    '\u4eba\u59bb',\n                    '\u6559\u5e2b',\n                    '\u767e\u5408',\n                    'Yaoi',\n                    '\u6027\u8f49',\n                    'NTR',\n                    '\u5973\u88dd',\n                    '\u7661\u5973',\n                    '\u5168\u5f69',\n                    '\u5973\u6027\u5411',\n                    '\u5b8c\u7d50',\n                    '\u7d14\u611b',\n                    '\u7981\u6f2b\u6f22\u5316\u7d44'\n                ],\n                itemType: \"search\",\n            },\n            {\n                name: \"\u89d2\u8272\u626e\u6f14\",\n                type: \"fixed\",\n                categories: [\n                    '\u5fa1\u59d0',\n                    '\u719f\u5973',\n                    '\u5de8\u4e73',\n                    '\u8ca7\u4e73',\n                    '\u5973\u6027\u652f\u914d',\n                    '\u6559\u5e2b',\n                    '\u5973\u50d5',\n                    '\u8b77\u58eb',\n                    '\u6cf3\u88dd',\n                    '\u773c\u93e1',\n                    '\u9023\u8932\u896a',\n                    '\u5176\u4ed6\u5236\u670d',\n                    '\u5154\u5973\u90ce'\n                ],\n                itemType: \"search\",\n            },\n            {\n                name: \"\u7279\u6b8aPLAY\",\n                type: \"fixed\",\n                categories: [\n                    '\u7fa4\u4ea4',\n                    '\u8db3\u4ea4',\n                    '\u675f\u7e1b',\n                    '\u809b\u4ea4',\n                    '\u963f\u9ed1\u984f',\n                    '\u85e5\u7269',\n                    '\u6276\u4ed6',\n                    '\u8abf\u6559',\n                    '\u91ce\u5916\u9732\u51fa',\n                    '\u50ac\u7720',\n                    '\u81ea\u6170',\n                    '\u89f8\u624b',\n                    '\u7378\u4ea4',\n                    '\u4e9e\u4eba',\n                    '\u602a\u7269\u5973\u5b69',\n                    '\u76ae\u7269',\n                    'ryona',\n                    '\u9a0e\u5927\u8eca'\n                ],\n                itemType: \"search\",\n            },\n            {\n                name: \"\u7279\u6b8aPLAY\",\n                type: \"fixed\",\n                categories: ['CG', '\u91cd\u53e3', '\u7375\u5947', '\u975eH', '\u8840\u8165\u66b4\u529b', '\u7ad9\u9577\u63a8\u85a6'],\n                itemType: \"search\",\n            },\n        ],\n        // enable ranking page\n        enableRankingPage: true,\n    }\n\n    /// category comic loading related\n    categoryComics = {\n        /**\n         * load comics of a category\n         * @param category {string} - category name\n         * @param param {string?} - category param\n         * @param options {string[]} - options from optionList\n         * @param page {number} - page number\n         * @returns {Promise<{comics: Comic[], maxPage: number}>}\n         */\n        load: async (category, param, options, page) => {\n            if (category !== \"\u6bcf\u9031\u5fc5\u770b\") {\n                param ??= category\n                param = encodeURIComponent(param)\n                let res = await this.get(`${this.baseUrl}/categories/filter?o=${options[0]}&c=${param}&page=${page}`)\n                let data = JSON.parse(res)\n                let total = data.total\n                let maxPage = Math.ceil(total / 80)\n                let comics = data.content.map((e) => this.parseComic(e))\n                return {\n                    comics: comics,\n                    maxPage: maxPage\n                }\n            } else {\n                let res = await this.get(`${this.baseUrl}/week/filter?id=${options[0]}&type=${options[1]}&page=0`)\n                let data = JSON.parse(res)\n                let comics = data.list.map((e) => this.parseComic(e))\n                return {\n                    comics: comics,\n                    maxPage: 1\n                }\n            }\n        },\n        /**\n         * [Optional] load options dynamically. If `optionList` is provided, this will be ignored.\n         * @param category {string}\n         * @param param {string?}\n         * @return {Promise<{options: string[], label?: string}[]>} - return a list of option group, each group contains a list of options\n         */\n        optionLoader: async (category, param) => {\n            if (category !== \"\u6bcf\u9031\u5fc5\u770b\") {\n                return [\n                    {\n                        label: \"\u6392\u5e8f\",\n                        // For a single option, use `-` to separate the value and text, left for value, right for text\n                        options: [\n                            \"mr-\u6700\u65b0\",\n                            \"mv-\u7e3d\u6392\u884c\",\n                            \"mv_m-\u6708\u6392\u884c\",\n                            \"mv_w-\u5468\u6392\u884c\",\n                            \"mv_t-\u65e5\u6392\u884c\",\n                            \"mp-\u6700\u591a\u5716\u7247\",\n                            \"tf-\u6700\u591a\u559c\u6b61\",\n                        ],\n                    }\n                ]\n            } else {\n                let res = await this.get(`${this.baseUrl}/week`)\n                let data = JSON.parse(res)\n                let options = []\n                for (let e of data[\"categories\"]) {\n                    options.push(`${e[\"id\"]}-${e[\"time\"]}`)\n                }\n                return [\n                    {\n                        label: \"\u6642\u9593\",\n                        options: options,\n                    },\n                    {\n                        label: \"\u985e\u578b\",\n                        options: [\n                          \"manga-\u65e5\u6f2b\",\n                          \"hanman-\u97d3\u6f2b\",\n                          \"another-\u5176\u4ed6\",\n                        ]\n                    }\n                ]\n            }\n        },\n        ranking: {\n            // For a single option, use `-` to separate the value and text, left for value, right for text\n            options: [\n                \"mv-\u7e3d\u6392\u884c\",\n                \"mv_m-\u6708\u6392\u884c\",\n                \"mv_w-\u5468\u6392\u884c\",\n                \"mv_t-\u65e5\u6392\u884c\",\n            ],\n            /**\n             * load ranking comics\n             * @param option {string} - option from optionList\n             * @param page {number} - page number\n             * @returns {Promise<{comics: Comic[], maxPage: number}>}\n             */\n            load: async (option, page) => {\n                return this.categoryComics.load(\"\u7e3d\u6392\u884c\", \"0\", [option], page)\n            }\n        }\n    }\n\n    /// search related\n    search = {\n        /**\n         * load search result\n         * @param keyword {string}\n         * @param options {(string | null)[]} - options from optionList\n         * @param page {number}\n         * @returns {Promise<{comics: Comic[], maxPage: number}>}\n         */\n        load: async (keyword, options, page) => {\n            keyword = keyword.trim()\n            keyword = encodeURIComponent(keyword)\n            keyword = keyword.replace(/%20/g, '+')\n            let url = `${this.baseUrl}/search?search_query=${keyword}&o=${options[0]}`\n            if(page > 1) {\n                url += `&page=${page}`\n            }\n            let res = await this.get(url)\n            let data = JSON.parse(res)\n            let total = data.total\n            let maxPage = Math.ceil(total / 80)\n            let comics = data.content.map((e) => this.parseComic(e))\n            return {\n                comics: comics,\n                maxPage: maxPage\n            }\n        },\n\n        // provide options for search\n        optionList: [\n            {\n                type: \"select\",\n                // For a single option, use `-` to separate the value and text, left for value, right for text\n                options: [\n                    \"mr-\u6700\u65b0\",\n                    \"mv-\u7e3d\u6392\u884c\",\n                    \"mv_m-\u6708\u6392\u884c\",\n                    \"mv_w-\u5468\u6392\u884c\",\n                    \"mv_t-\u65e5\u6392\u884c\",\n                    \"mp-\u6700\u591a\u5716\u7247\",\n                    \"tf-\u6700\u591a\u559c\u6b61\",\n                ],\n                // option label\n                label: \"\u6392\u5e8f\",\n            }\n        ],\n    }\n\n    // favorite related\n    favorites = {\n        multiFolder: true,\n        /**\n         * add or delete favorite.\n         * throw `Login expired` to indicate login expired, App will automatically re-login and re-add/delete favorite\n         * @param comicId {string}\n         * @param folderId {string}\n         * @param isAdding {boolean} - true for add, false for delete\n         * @param favoriteId {string?} - [Comic.favoriteId]\n         * @returns {Promise<any>} - return any value to indicate success\n         */\n        addOrDelFavorite: async (comicId, folderId, isAdding, favoriteId) => {\n            if (isAdding) {\n                await this.post(`${this.baseUrl}/favorite`, `aid=${comicId}`)\n                await this.post(`${this.baseUrl}/favorite_folder`, `type=move&folder_id=${folderId}&aid=${comicId}`)\n            } else {\n                await this.post(`${this.baseUrl}/favorite`, `aid=${comicId}`)\n            }\n        },\n        /**\n         * load favorite folders.\n         * throw `Login expired` to indicate login expired, App will automatically re-login retry.\n         * if comicId is not null, return favorite folders which contains the comic.\n         * @param comicId {string?}\n         * @returns {Promise<{folders: {[p: string]: string}, favorited: string[]}>} - `folders` is a map of folder id to folder name, `favorited` is a list of folder id which contains the comic\n         */\n        loadFolders: async (comicId) => {\n            let res = await this.get(`${this.baseUrl}/favorite`)\n            let folders = {\n                \"0\": this.translate(\"All\")\n            }\n            let json = JSON.parse(res)\n            for (let e of json.folder_list) {\n                folders[e.FID.toString()] = e.name\n            }\n            return {\n                folders: folders,\n                favorited: []\n            }\n        },\n        /**\n         * add a folder\n         * @param name {string}\n         * @returns {Promise<any>} - return any value to indicate success\n         */\n        addFolder: async (name) => {\n            await this.post(`${this.baseUrl}/favorite_folder`, `type=add&folder_name=${name}`)\n        },\n        /**\n         * delete a folder\n         * @param folderId {string}\n         * @returns {Promise<void>} - return any value to indicate success\n         */\n        deleteFolder: async (folderId) => {\n            await this.post(`${this.baseUrl}/favorite_folder`, `type=del&folder_id=${folderId}`)\n        },\n        /**\n         * load comics in a folder\n         * throw `Login expired` to indicate login expired, App will automatically re-login retry.\n         * @param page {number}\n         * @param folder {string?} - folder id, null for non-multi-folder\n         * @returns {Promise<{comics: Comic[], maxPage: number}>}\n         */\n        loadComics: async (page, folder) => {\n            let order = this.loadSetting('favoriteOrder')\n            let res = await this.get(`${this.baseUrl}/favorite?folder_id=${folder}&page=${page}&o=${order}`)\n            let json = JSON.parse(res)\n            let total = json.total\n            let maxPage = Math.ceil(total / 20)\n            let comics = json.list.map((e) => this.parseComic(e))\n            return {\n                comics: comics,\n                maxPage: maxPage\n            }\n        },\n        singleFolderForSingleComic: true,\n    }\n\n    /// single comic related\n    comic = {\n        /**\n         * load comic info\n         * @param id {string}\n         * @returns {Promise<ComicDetails>}\n         */\n        loadInfo: async (id) => {\n            if (id.startsWith('jm')) {\n                id = id.substring(2)\n            }\n            let res = await this.get(`${this.baseUrl}/album?id=${id}`);\n            let data = JSON.parse(res)\n            let author = data.author ?? []\n            let works = data.works ?? []\n            let actors = data.actors ?? []\n            let chapters = new Map()\n            let series = (data.series ?? []).sort((a, b) => a.sort - b.sort)\n            for(let e of series) {\n                let title = e.name ?? ''\n                title = title.trim()\n                if(title.length === 0) {\n                    title = `\u7b2c${e[\"sort\"]}\u8a71`\n                }\n                let id = e.id.toString()\n                chapters.set(id, title)\n            }\n            if(chapters.size === 0) {\n                chapters.set(id, '\u7b2c1\u8a71')\n            }\n            let tags = data.tags ?? []\n            let related = data[\"related_list\"].map((e) => new Comic({\n                id: e.id.toString(),\n                title: e.name,\n                subtitle: e.author ?? \"\",\n                cover: this.getCoverUrl(e.id),\n                description: `JM${e.id}`\n            }))\n            let updateTimeStamp = data[\"addtime\"];\n            let date = new Date(updateTimeStamp * 1000)\n            let updateDate = `${date.getFullYear()}-${date.getMonth() + 1}-${date.getDate()}`;\n\n            return new ComicDetails({\n                title: data.name,\n                cover: this.getCoverUrl(id),\n                description: data.description,\n                likesCount: Number(data.likes),\n                chapters: chapters,\n                tags: {\n                    \"ID\": [`JM${id}`],\n                    \"\u4f5c\u8005\": author,\n                    \"\u6807\u7b7e\": tags,\n                    \"\u4f5c\u54c1\": works,\n                    \"\u89d2\u8272\": actors,\n                    \"\u6d4f\u89c8\u91cf\": data.total_views ? [data.total_views] : [],\n                },\n                recommend: related,\n                isFavorite: data.is_favorite ?? false,\n                isLiked: data.liked ?? false,\n                updateTime: updateDate,\n            })\n        },\n        /**\n         * load images of a chapter\n         * @param comicId {string}\n         * @param epId {string?}\n         * @returns {Promise<{images: string[]}>}\n         */\n        loadEp: async (comicId, epId) => {\n            let res = await this.get(`${this.baseUrl}/chapter?id=${epId}`);\n            let data = JSON.parse(res)\n            let images = data.images.map((e) => this.getImageUrl(epId, e))\n            return {\n                images: images\n            }\n        },\n        /**\n         * [Optional] provide configs for an image loading\n         * @param url\n         * @param comicId\n         * @param epId\n         * @returns {{} | Promise<{}>}\n         */\n        onImageLoad: (url, comicId, epId) => {\n            const scrambleId = 220980\n            let pictureName = \"\";\n            for (let i = url.length - 1; i >= 0; i--) {\n                if (url[i] === '/') {\n                    pictureName = url.substring(i + 1, url.length - 5);\n                    break;\n                }\n            }\n            epId = Number(epId);\n            let num = 0\n            if(epId < scrambleId) {\n                num = 0\n            } else if (epId < 268850) {\n                num = 10\n            } else if (epId > 421926) {\n                let str = epId.toString() + pictureName\n                let bytes = Convert.encodeUtf8(str)\n                let hash = Convert.md5(bytes)\n                let hashStr = Convert.hexEncode(hash)\n                let charCode = hashStr.charCodeAt(hashStr.length-1)\n                let remainder = charCode % 8\n                num = remainder * 2 + 2\n            } else {\n                let str = epId.toString() + pictureName\n                let bytes = Convert.encodeUtf8(str)\n                let hash = Convert.md5(bytes)\n                let hashStr = Convert.hexEncode(hash)\n                let charCode = hashStr.charCodeAt(hashStr.length-1)\n                let remainder = charCode % 10\n                num = remainder * 2 + 2\n            }\n            if (num <= 1) {\n                return {}\n            }\n            return {\n                headers: this.getImgHeaders(),\n                // gif \u56fe\u7247\u4e0d\u9700\u8981\u4fee\u6539\n                modifyImage: url.endsWith(\".gif\") ? null : `\n                    let modifyImage = (image) => {\n                        const num = ${num}\n                        let blockSize = Math.floor(image.height / num)\n                        let remainder = image.height % num\n                        let blocks = []\n                        for(let i = 0; i < num; i++) {\n                            let start = i * blockSize\n                            let end = start + blockSize + (i !== num - 1 ? 0 : remainder)\n                            blocks.push({\n                                start: start,\n                                end: end\n                            })\n                        }\n                        let res = Image.empty(image.width, image.height)\n                        let y = 0\n                        for(let i = blocks.length - 1; i >= 0; i--) {\n                            let block = blocks[i]\n                            let currentHeight = block.end - block.start\n                            res.fillImageRangeAt(0, y, image, 0, block.start, image.width, currentHeight)\n                            y += currentHeight\n                        }\n                        return res\n                    }\n                `,\n            }\n        },\n        /**\n         * [Optional] provide configs for a thumbnail loading\n         * @param url {string}\n         * @returns {{}}\n         */\n        onThumbnailLoad: (url) => {\n            return {\n                headers: this.getImgHeaders()\n            }\n        },\n        /**\n         * [Optional] like or unlike a comic\n         * @param id {string}\n         * @param isLike {boolean} - true for like, false for unlike\n         * @returns {Promise<void>}\n         */\n        likeComic: async (id, isLike) => {\n            let res = await this.post(`${this.baseUrl}/like`, `id=${id}`)\n            let json = JSON.parse(res)\n            if (json.code !== 200 || json.status === 'error') {\n                throw json.msg ?? 'Failed to like/unlike comic'\n            }\n            return \"ok\"\n        },\n        /**\n         * [Optional] load comments\n         * @param comicId {string}\n         * @param subId {string?} - ComicDetails.subId\n         * @param page {number}\n         * @param replyTo {string?} - commentId to reply, not null when reply to a comment\n         * @returns {Promise<{comments: Comment[], maxPage: number?}>}\n         */\n        loadComments: async (comicId, subId, page, replyTo) => {\n            if (replyTo != null) {\n                let replies = JM.commentReplies[comicId]?.[replyTo] ?? []\n                return {\n                    comments: replies,\n                    maxPage: 1\n                }\n            }\n            let res = await this.get(`${this.baseUrl}/forum?mode=manhua&aid=${comicId}&page=${page}`)\n            let json = JSON.parse(res)\n            const pageSize = 6\n            if (page === 1) {\n                JM.commentReplies[comicId] = {}\n            }\n            if (JM.commentReplies[comicId] == null) {\n                JM.commentReplies[comicId] = {}\n            }\n            let comicReplies = JM.commentReplies[comicId]\n            let parseContent = (content) => {\n                if (content == null) return ''\n                let start = content.indexOf('>')\n                let end = content.lastIndexOf('<')\n                if (start === -1 || end === -1 || end <= start) return content\n                return content.substring(start + 1, end)\n            }\n            return {\n                comments: json.list.map((e) => {\n                    let replys = (e.replys ?? []).map((r) => new Comment({\n                        avatar: this.getAvatarUrl(r.photo),\n                        userName: r.username,\n                        time: r.addtime,\n                        content: parseContent(r.content),\n                    }))\n                    let comment = {\n                        avatar: this.getAvatarUrl(e.photo),\n                        userName: e.username,\n                        time: e.addtime,\n                        content: parseContent(e.content),\n                    }\n                    if (replys.length > 0) {\n                        comment.id = e.CID.toString()\n                        comment.replyCount = replys.length\n                        comicReplies[comment.id] = replys\n                    }\n                    return new Comment(comment)\n                }),\n                maxPage: Math.floor(json.total / pageSize) + 1\n            }\n        },\n        /**\n         * [Optional] send a comment, return any value to indicate success\n         * @param comicId {string}\n         * @param subId {string?} - ComicDetails.subId\n         * @param content {string}\n         * @param replyTo {string?} - commentId to reply, not null when reply to a comment\n         * @returns {Promise<any>}\n         */\n        sendComment: async (comicId, subId, content, replyTo) => {\n            let res = await this.post(`${this.baseUrl}/comment`, `aid=${comicId}&comment=${encodeURIComponent(content)}&status=undefined`)\n            let json = JSON.parse(res)\n            if (json.status === \"fail\") {\n                throw json.msg ?? 'Failed to send comment'\n            }\n            return \"ok\"\n        },\n        // {string?} - regex string, used to identify comic id from user input\n        idMatch: \"^(\\\\d+|jm\\\\d+)$\",\n        /**\n         * [Optional] Handle tag click event\n         * @param namespace {string}\n         * @param tag {string}\n         * @returns {{action: string, keyword: string, param: string?}}\n         */\n        onClickTag: (namespace, tag) => {\n            return {\n                action: 'search',\n                keyword: tag,\n            }\n        },\n    }\n\n\n    /*\n    [Optional] settings related\n    Use this.loadSetting to load setting\n    ```\n    let setting1Value = this.loadSetting('setting1')\n    console.log(setting1Value)\n    ```\n     */\n    settings = {\n        refreshDomains: {\n            title: \"\u66f4\u65b0API\u57df\u540d\",\n            type: \"callback\",\n            buttonText: \"\u66f4\u65b0\",\n            callback: () => this.refreshApiDomains(true)\n        },\n        refreshDomainsOnStart: {\n            title: \"\u542f\u52a8\u65f6\u66f4\u65b0API\u57df\u540d\",\n            type: \"switch\",\n            default: true,\n        },\n        apiDomain: {\n            title: \"API\u57df\u540d\",\n            type: \"select\",\n            options: [\n                {\n                    value: '1',\n                    text: '\u5206\u6d411',\n                },\n                {\n                    value: '2',\n                    text: '\u5206\u6d412',\n                },\n                {\n                    value: '3',\n                    text: '\u5206\u6d413',\n                },\n                {\n                    value: '4',\n                    text: '\u5206\u6d414',\n                },\n            ],\n            default: \"1\",\n        },\n        imageStream: {\n            title: \"\u56fe\u7247\u5206\u6d41\",\n            type: \"select\",\n            options: [\n                {\n                    value: '1',\n                    text: '\u5206\u6d411',\n                },\n                {\n                    value: '2',\n                    text: '\u5206\u6d412',\n                },\n                {\n                    value: '3',\n                    text: '\u5206\u6d413',\n                },\n                {\n                    value: '4',\n                    text: '\u5206\u6d414',\n                },\n            ],\n            default: \"1\",\n        },\n        favoriteOrder: {\n            title: \"\u6536\u85cf\u5939\u6f2b\u753b\u6392\u5e8f\u6a21\u5f0f\",\n            type: \"select\",\n            options: [\n                {\n                    value: 'mr',\n                    text: '\u6700\u65b0\u6536\u85cf',\n                },\n                {\n                    value: 'mp',\n                    text: '\u6700\u65b0\u66f4\u65b0',\n                }\n            ],\n            default: 'mr'\n        },\n        dailyCheckInTask: {\n            title: \"\u6bcf\u65e5\u81ea\u52a8\u7b7e\u5230\",\n            type: \"switch\",\n            default: false\n        },\n        dailyCheckIn: {\n            title: \"\u624b\u52a8\u7b7e\u5230\",\n            type: \"callback\",\n            buttonText: \"\u7b7e\u5230\",\n            callback: () => this.dailyCheckIn()\n        },\n    }\n\n    // [Optional] translations for the strings in this config\n    translation = {\n        'zh_CN': {\n            'Refresh Domain List': '\u5237\u65b0\u57df\u540d\u5217\u8868',\n            'Refresh': '\u5237\u65b0',\n            'Refresh Domain List on Startup': '\u542f\u52a8\u65f6\u5237\u65b0\u57df\u540d\u5217\u8868',\n            'Api Domain': 'Api\u57df\u540d',\n            'Image Stream': '\u56fe\u7247\u5206\u6d41',\n            'Favorite Order': '\u6536\u85cf\u5939\u6392\u5e8f',\n            'Daily Check-in Task': '\u6bcf\u65e5\u81ea\u52a8\u7b7e\u5230',\n            'Manual Check-In': '\u624b\u52a8\u7b7e\u5230',\n            'Check-In': '\u7b7e\u5230',\n            'Add Time': '\u6dfb\u52a0\u65f6\u95f4',\n            'Update Time': '\u66f4\u65b0\u65f6\u95f4',\n            'All': '\u5168\u90e8',\n            'Author': '\u4f5c\u8005',\n            'Tag': '\u6807\u7b7e',\n            'Work': '\u4f5c\u54c1',\n            'Actor': '\u89d2\u8272',\n            'View': '\u6d4f\u89c8\u91cf',\n        },\n        'zh_TW': {\n            'Refresh Domain List': '\u5237\u65b0\u57df\u540d\u5217\u8868',\n            'Refresh': '\u5237\u65b0',\n            'Refresh Domain List on Startup': '\u555f\u52d5\u6642\u5237\u65b0\u57df\u540d\u5217\u8868',\n            'Api Domain': 'Api\u57df\u540d',\n            'Image Stream': '\u5716\u7247\u5206\u6d41',\n            'Favorite Order': '\u6536\u85cf\u593e\u6392\u5e8f',\n            'Daily Check-in Task': '\u6bcf\u65e5\u81ea\u52d5\u7c3d\u5230',\n            'Manual Check-In': '\u624b\u52d5\u7c3d\u5230',\n            'Check-In': '\u7c3d\u5230',\n            'Add Time': '\u6dfb\u52a0\u6642\u9593',\n            'Update Time': '\u66f4\u65b0\u6642\u9593',\n            'All': '\u5168\u90e8',\n            'Author': '\u4f5c\u8005',\n            'Tag': '\u6a19\u7c64',\n            'Work': '\u4f5c\u54c1',\n            'Actor': '\u89d2\u8272',\n            'View': '\u700f\u89bd\u91cf',\n        },\n    }\n}\n";
__SOURCE_CODE["copy_manga"] = "class CopyManga extends ComicSource {\n\n    name = \"\u62f7\u8d1d\u6f2b\u753b\"\n\n    key = \"copy_manga\"\n\n    version = \"3.3.0\"\n\n    minAppVersion = \"1.6.0\"\n\n    url = \"https://raw.githubusercontent.com/ccbkv/pica_configs/refs/heads/master/copy_manga.js\"\n\n    async getReqID() {\n        if (this.copyRegion === \"0\") {\n            return \"\";\n        }\n        const reqIdUrl = \"https://marketing.aiacgn.com/api/v2/adopr/query3/?format=json&ident=200100001\";\n        let reqId = \"\";\n        try {\n            const response = await Network.get(reqIdUrl, this.webHeaders);\n\n            if (response.status === 200) {\n                const data = JSON.parse(response.body);\n                reqId = data.results.request_id;\n            }\n        } catch (e) {\n        }\n        return reqId;\n    }\n\n    // static defaultCopyVersion = \"3.0.6\"\n\n    // static defaultCopyPlatform = \"2\"\n\n    static defaultCopyRegion = \"0\"\n\n    static defaultImageQuality = \"1500\"\n\n    static defaultApiUrl = 'www.2026copy.com'\n\n    static searchApi = \"/api/kb/web/searchb/comics\"\n\n    get webHeaders() {\n        return {\n            \"User-Agent\": \"Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36\",\n            \"Accept\": \"text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8\",\n            \"Accept-Language\": \"zh-CN,zh;q=0.9,en;q=0.8\",\n        }\n    }\n\n    get webApiHeaders() {\n        let token = this.loadData(\"token\") || \"\"\n        let cookieParts = []\n        if (token) {\n            cookieParts.push(`token=${token}`)\n        }\n        try {\n            let cookies = Network.getCookies(this.apiUrl)\n            for (let c of cookies) {\n                if (c.name !== 'token' && c.value) {\n                    cookieParts.push(`${c.name}=${c.value}`)\n                }\n            }\n        } catch (e) {}\n        return {\n            \"User-Agent\": \"Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36\",\n            \"Accept\": \"application/json\",\n            \"platform\": \"2\",\n            \"Authorization\": token ? `Token ${token}` : \"\",\n            \"Cookie\": cookieParts.join('; '),\n        }\n    }\n\n    get apiUrl() {\n        return `https://${this.loadSetting('base_url')}`\n    }\n\n    get copyRegion() {\n        return this.loadSetting('region') || this.defaultCopyRegion\n    }\n\n    get imageQuality() {\n        return this.loadSetting('image_quality') || this.defaultImageQuality\n    }\n\n    init() {\n        this.author_path_word_dict = {}\n        this.account.loginWithWebview.url = `${this.apiUrl}/web/login`\n        // \u8fd9\u4e24\u6b65\u53ea\u662f\u300c\u8bd5\u8bd5\u5b98\u65b9\u63a5\u53e3\u4f1a\u4e0d\u4f1a\u4e0b\u53d1\u66f4\u5feb\u7684\u57df\u540d\u300d\uff0c\u5931\u8d25\u4e0d\u80fd\u8fde\u5e26\u6574\u4e2a\u6e90\u4e0d\u53ef\u7528\uff1b\n        // \u66fe\u7ecf api.copy-manga.com \u88ab\u7ebf\u8def\u6620\u5c04\u6539\u5199\u6210 www.copy5000.com\uff0c\u63a5\u53e3 404 \u2192 explore \u76f4\u63a5\u62a5\u9519\u3002\n        this.refreshSearchApi().catch(() => { })\n        this.refreshAppApi().catch(() => { })\n    }\n\n    _encodeBase64(str) {\n        const chars = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/'\n        let result = ''\n        let i = 0\n        while (i < str.length) {\n            const a = str.charCodeAt(i++)\n            const b = i < str.length ? str.charCodeAt(i++) : 0\n            const c = i < str.length ? str.charCodeAt(i++) : 0\n            const bitmap = (a << 16) | (b << 8) | c\n            result += chars.charAt((bitmap >> 18) & 63)\n            result += chars.charAt((bitmap >> 12) & 63)\n            result += (i > str.length + 1) ? '=' : chars.charAt((bitmap >> 6) & 63)\n            result += (i > str.length) ? '=' : chars.charAt(bitmap & 63)\n        }\n        return result\n    }\n\n    /// account\n    /// set this to null to desable account feature\n    account = {\n        login: async (account, pwd) => {\n            Network.deleteCookies(this.apiUrl)\n\n            let csrfToken = \"\"\n            try {\n                let pageRes = await Network.get(`${this.apiUrl}/web/login`, this.webHeaders)\n                if (pageRes.status === 200) {\n                    let cookies = Network.getCookies(this.apiUrl)\n                    for (let cookie of cookies) {\n                        if (cookie.name === 'csrftoken') {\n                            csrfToken = cookie.value\n                            break\n                        }\n                    }\n                }\n            } catch (e) {}\n\n            let salt = 100000 + randomInt(0, 900000)\n            let base64 = this._encodeBase64(`${pwd}-${salt}`)\n\n            const loginPaths = ['/api/kb/web/login', '/api/v1/login']\n            let lastError = null\n\n            for (let path of loginPaths) {\n                try {\n                    let headers = {\n                        \"Accept\": \"application/json, text/plain, */*\",\n                        \"Content-Type\": \"application/x-www-form-urlencoded; charset=UTF-8\",\n                        \"User-Agent\": \"Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36\",\n                        \"platform\": \"2\",\n                        \"Referer\": `${this.apiUrl}/web/login`,\n                    }\n\n                    if (csrfToken) {\n                        headers[\"X-CSRFToken\"] = csrfToken\n                    }\n\n                    let res = await Network.post(\n                        `${this.apiUrl}${path}`,\n                        headers,\n                        `username=${encodeURIComponent(account)}&password=${encodeURIComponent(base64)}&salt=${salt}&platform=2&version=2025.12.10&source=freeSite`\n                    );\n\n                    if (res.status === 200) {\n                        let data = JSON.parse(res.body)\n                        if (data.code === 200) {\n                            let token = data.results.token\n                            this.saveData('token', token)\n\n                            let domain = this.apiUrl.replace('https://', '').replace('http://', '').split('/')[0]\n                            let cookiesToSet = [new Cookie({name: 'token', value: token, domain: domain})]\n                            if (data.results.username) {\n                                cookiesToSet.push(new Cookie({name: 'name', value: data.results.username, domain: domain}))\n                            }\n                            if (data.results.user_id) {\n                                cookiesToSet.push(new Cookie({name: 'user_id', value: data.results.user_id, domain: domain}))\n                            }\n                            Network.setCookies(this.apiUrl, cookiesToSet)\n\n                            return \"ok\"\n                        } else {\n                            throw data.message || '\u767b\u5f55\u5931\u8d25'\n                        }\n                    }\n                } catch (e) {\n                    lastError = e\n                }\n            }\n\n            throw lastError || '\u767b\u5f55\u5931\u8d25'\n        },\n        loginWithWebview: {\n            url: \"https://www.2026copy.com/web/login\",\n            checkStatus: (url, title) => {\n                let cookies = Network.getCookies(this.apiUrl)\n                for (let cookie of cookies) {\n                    if (cookie.name === 'token' && cookie.value) {\n                        this.saveData('token', cookie.value)\n                        return true\n                    }\n                }\n                return false\n            },\n            onLoginSuccess: () => {\n                let cookies = Network.getCookies(this.apiUrl)\n                for (let cookie of cookies) {\n                    if (cookie.name === 'token' && cookie.value) {\n                        this.saveData('token', cookie.value)\n                        break\n                    }\n                }\n            },\n        },\n        logout: () => {\n            this.deleteData('token')\n            Network.deleteCookies(this.apiUrl)\n        },\n        registerWebsite: null\n    }\n\n    /// explore pages\n    explore = [\n        {\n            title: \"\u62f7\u8d1d\u6f2b\u753b\",\n            type: \"singlePageWithMultiPart\",\n            load: async () => {\n                let res = await Network.get(`${this.apiUrl}/`, this.webHeaders);\n                if (res.status !== 200) {\n                    throw `Invalid status code: ${res.status}`;\n                }\n\n                let html = res.body;\n                let doc = new HtmlDocument(html);\n                let result = {};\n\n                function parseComicFromElement(item) {\n                    let href = item.attributes['href'] || '';\n                    if (!href.includes('/comic/')) return null;\n                    \n                    let pathWord = href.replace('/comic/', '').replace('/', '');\n                    \n                    let comicTitle = '';\n                    let titleAttr = item.querySelector('[title]');\n                    if (titleAttr && titleAttr.attributes) {\n                        comicTitle = titleAttr.attributes['title'] || '';\n                    }\n                    if (!comicTitle) {\n                        let titleEl2 = item.querySelector('.edit-txt');\n                        comicTitle = titleEl2 && titleEl2.text ? titleEl2.text.trim() : '';\n                    }\n                    if (!comicTitle) {\n                        let titleEl2 = item.querySelector('.twoLines');\n                        comicTitle = titleEl2 && titleEl2.text ? titleEl2.text.trim() : '';\n                    }\n                    if (!comicTitle) {\n                        comicTitle = item.text ? item.text.trim() : '';\n                    }\n\n                    let cover = '';\n                    let img = item.querySelector('img');\n                    if (img && img.attributes) {\n                        cover = img.attributes['src'] || img.attributes['data-src'] || '';\n                    }\n\n                    let author = '';\n                    let authorEl = item.querySelector('.exemptComicItem-txt-span');\n                    if (!authorEl) authorEl = item.querySelector('.oneLines');\n                    if (authorEl && authorEl.text) {\n                        author = authorEl.text.trim();\n                    }\n\n                    let updateTime = '';\n                    let updateEl = item.querySelector('.update span');\n                    if (updateEl && updateEl.text) {\n                        updateTime = updateEl.text.trim();\n                    }\n\n                    if (comicTitle && pathWord) {\n                        return {\n                            id: pathWord,\n                            title: comicTitle,\n                            subTitle: author,\n                            cover: cover,\n                            tags: [],\n                            description: updateTime\n                        };\n                    }\n                    return null;\n                }\n\n                function extractComics(selector, limit) {\n                    let items = doc.querySelectorAll(selector);\n                    let comics = [];\n                    let seen = new Set();\n                    for (let i = 0; i < items.length && comics.length < limit; i++) {\n                        let comic = parseComicFromElement(items[i]);\n                        if (comic && !seen.has(comic.id)) {\n                            seen.add(comic.id);\n                            comics.push(comic);\n                        }\n                    }\n                    return comics;\n                }\n\n                let recommend = extractComics('.comicRank-box a[href*=\"/comic/\"]', 20);\n                if (recommend.length > 0) {\n                    result[\"\u63a8\u8350\"] = recommend;\n                }\n\n                let rows = doc.querySelectorAll('.content-box .row');\n                if (rows.length > 0) {\n                    let hotUpdate = [];\n                    let seenHot = new Set();\n                    let links = rows[0].querySelectorAll('a[href*=\"/comic/\"]');\n                    for (let i = 0; i < links.length && hotUpdate.length < 20; i++) {\n                        let comic = parseComicFromElement(links[i]);\n                        if (comic && !seenHot.has(comic.id)) {\n                            seenHot.add(comic.id);\n                            hotUpdate.push(comic);\n                        }\n                    }\n                    if (hotUpdate.length > 0) {\n                        result[\"\u70ed\u95e8\u66f4\u65b0\"] = hotUpdate;\n                    }\n                }\n\n                if (rows.length > 1) {\n                    let newComics = [];\n                    let seenNew = new Set();\n                    let links = rows[1].querySelectorAll('a[href*=\"/comic/\"]');\n                    for (let i = 0; i < links.length && newComics.length < 20; i++) {\n                        let comic = parseComicFromElement(links[i]);\n                        if (comic && !seenNew.has(comic.id)) {\n                            seenNew.add(comic.id);\n                            newComics.push(comic);\n                        }\n                    }\n                    if (newComics.length > 0) {\n                        result[\"\u5168\u65b0\u4e0a\u67b6\"] = newComics;\n                    }\n                }\n\n                let allLinks = doc.querySelectorAll('a[href*=\"/comic/\"]');\n                let allComics = [];\n                let seenAll = new Set();\n                for (let i = 0; i < allLinks.length; i++) {\n                    let comic = parseComicFromElement(allLinks[i]);\n                    if (comic && !seenAll.has(comic.id)) {\n                        seenAll.add(comic.id);\n                        allComics.push(comic);\n                    }\n                }\n\n                if (allComics.length > 0) {\n                    let first20 = allComics.slice(0, 20);\n                    let hasRecommend = result[\"\u63a8\u8350\"] && result[\"\u63a8\u8350\"].length > 0;\n                    let hasHot = result[\"\u70ed\u95e8\u66f4\u65b0\"] && result[\"\u70ed\u95e8\u66f4\u65b0\"].length > 0;\n                    \n                    if (!hasRecommend && !hasHot) {\n                        result[\"\u9996\u9875\u63a8\u8350\"] = first20;\n                    } else {\n                        let existingIds = new Set();\n                        if (hasRecommend) result[\"\u63a8\u8350\"].forEach(c => existingIds.add(c.id));\n                        if (hasHot) result[\"\u70ed\u95e8\u66f4\u65b0\"].forEach(c => existingIds.add(c.id));\n                        \n                        let newComics = allComics.filter(c => !existingIds.has(c.id)).slice(0, 20);\n                        if (newComics.length > 0) {\n                            result[\"\u66f4\u591a\u63a8\u8350\"] = newComics;\n                        }\n                    }\n                }\n\n                return result;\n            }\n        }\n    ]\n\n    static category_param_dict = {\n        \"\u5168\u90e8\": \"\",\n        \"\u611b\u60c5\": \"aiqing\",\n        \"\u6b61\u6a02\u5411\": \"huanlexiang\",\n        \"\u5192\u96aa\": \"maoxian\",\n        \"\u5947\u5e7b\": \"qihuan\",\n        \"\u767e\u5408\": \"baihe\",\n        \"\u6821\u56ed\": \"xiaoyuan\",\n        \"\u79d1\u5e7b\": \"kehuan\",\n        \"\u6771\u65b9\": \"dongfang\",\n        \"\u803d\u7f8e\": \"danmei\",\n        \"\u751f\u6d3b\": \"shenghuo\",\n        \"\u683c\u9b25\": \"gedou\",\n        \"\u8f7b\u5c0f\u8bf4\": \"qingxiaoshuo\",\n        \"\u60ac\u7591\": \"xuanyi\",\n        \"\u5176\u4ed6\": \"qita\",\n        \"\u795e\u9b3c\": \"shengui\",\n        \"\u804c\u573a\": \"zhichang\",\n        \"TL\": \"teenslove\",\n        \"\u840c\u7cfb\": \"mengxi\",\n        \"\u6cbb\u6108\": \"zhiyu\",\n        \"\u9577\u689d\": \"changtiao\",\n        \"\u56db\u683c\": \"sige\",\n        \"\u8282\u64cd\": \"jiecao\",\n        \"\u8230\u5a18\": \"jianniang\",\n        \"\u7ade\u6280\": \"jingji\",\n        \"\u641e\u7b11\": \"gaoxiao\",\n        \"\u4f2a\u5a18\": \"weiniang\",\n        \"\u70ed\u8840\": \"rexue\",\n        \"\u52b1\u5fd7\": \"lizhi\",\n        \"\u6027\u8f6c\u6362\": \"xingzhuanhuan\",\n        \"\u5f69\u8272\": \"COLOR\",\n        \"\u5f8c\u5bae\": \"hougong\",\n        \"\u7f8e\u98df\": \"meishi\",\n        \"\u4fa6\u63a2\": \"zhentan\",\n        \"AA\": \"aa\",\n        \"\u97f3\u4e50\u821e\u8e48\": \"yinyuewudao\",\n        \"\u9b54\u5e7b\": \"mohuan\",\n        \"\u6218\u4e89\": \"zhanzheng\",\n        \"\u5386\u53f2\": \"lishi\",\n        \"\u5f02\u4e16\u754c\": \"yishijie\",\n        \"\u60ca\u609a\": \"jingsong\",\n        \"\u673a\u6218\": \"jizhan\",\n        \"\u90fd\u5e02\": \"dushi\",\n        \"\u7a7f\u8d8a\": \"chuanyue\",\n        \"\u6050\u6016\": \"kongbu\",\n        \"C100\": \"comiket100\",\n        \"\u91cd\u751f\": \"chongsheng\",\n        \"C99\": \"comiket99\",\n        \"C101\": \"comiket101\",\n        \"C97\": \"comiket97\",\n        \"C96\": \"comiket96\",\n        \"\u751f\u5b58\": \"shengcun\",\n        \"\u5b85\u7cfb\": \"zhaixi\",\n        \"\u6b66\u4fa0\": \"wuxia\",\n        \"C98\": \"C98\",\n        \"C95\": \"comiket95\",\n        \"FATE\": \"fate\",\n        \"\u8f6c\u751f\": \"zhuansheng\",\n        \"\u7121\u4fee\u6b63\": \"Uncensored\",\n        \"\u4ed9\u4fa0\": \"xianxia\",\n        \"LoveLive\": \"loveLive\"\n    }\n\n    category = {\n        title: \"\u62f7\u8d1d\u6f2b\u753b\",\n        parts: [\n            {\n                name: \"\u62f7\u8d1d\u6f2b\u753b\",\n                type: \"fixed\",\n                categories: [\"\u6392\u884c\"],\n                categoryParams: [\"ranking\"],\n                itemType: \"category\"\n            },\n            {\n                name: \"\u4e3b\u9898\",\n                type: \"fixed\",\n                categories: Object.keys(CopyManga.category_param_dict),\n                categoryParams: Object.values(CopyManga.category_param_dict),\n                itemType: \"category\"\n            }\n        ]\n    }\n\n    categoryComics = {\n        load: async (category, param, options, page) => {\n            if (category.startsWith(\"author:\")) {\n                let authorName = category.substring(\"author:\".length);\n                let pathWord = this.author_path_word_dict[authorName] || null;\n\n                if (!pathWord) {\n                    try {\n                        let searchHtmlRes = await Network.get(\n                            `${this.apiUrl}/search?q=${encodeURIComponent(authorName)}`,\n                            this.webHeaders\n                        );\n                        if (searchHtmlRes.status === 200) {\n                            let searchDoc = new HtmlDocument(searchHtmlRes.body);\n                            let authorLinks = searchDoc.querySelectorAll('a[href*=\"/author/\"]');\n                            for (let i = 0; i < authorLinks.length; i++) {\n                                let link = authorLinks[i];\n                                let href = link.attributes['href'] || '';\n                                let match = href.match(/\\/author\\/([^\\/]+)/);\n                                if (match && match[1]) {\n                                    let name = link.text ? link.text.trim() : '';\n                                    if (name) {\n                                        this.author_path_word_dict[name] = match[1];\n                                    }\n                                    if (!pathWord && name === authorName) {\n                                        pathWord = match[1];\n                                    }\n                                    if (!pathWord) {\n                                        pathWord = match[1];\n                                    }\n                                }\n                            }\n                        }\n                    } catch (e) {\n                    }\n                }\n\n                if (!pathWord) {\n                    throw `\u65e0\u6cd5\u627e\u5230\u4f5c\u8005: ${authorName}`;\n                }\n\n                this.author_path_word_dict[authorName] = pathWord;\n                let authorUrl = `${this.apiUrl}/author/${encodeURIComponent(pathWord)}/comics?page=${page}`;\n                let res = await Network.get(authorUrl, this.webHeaders);\n                if (res.status !== 200) {\n                    throw `Invalid status code: ${res.status}`;\n                }\n\n                let html = res.body;\n                let doc = new HtmlDocument(html);\n                let comics = [];\n\n                let listBox = doc.querySelector('.exemptComicList .exemptComic-box');\n                if (listBox && listBox.attributes && listBox.attributes['list']) {\n                    try {\n                        let rawList = listBox.attributes['list'];\n                        let decoded;\n                        try {\n                            decoded = JSON.parse(rawList);\n                        } catch (jsonErr) {\n                            decoded = new Function('return ' + rawList)();\n                        }\n                        if (Array.isArray(decoded)) {\n                            for (let item of decoded) {\n                                let pw = item.path_word || '';\n                                let title = item.name || '';\n                                if (pw && title) {\n                                    let authors = item.author || [];\n                                    let authorNames = authors.map(a => a.name).filter(n => n);\n                                    let authorName2 = authorNames.length > 0 ? authorNames.join(', ') : '';\n                                    let cover = item.cover || '';\n                                    let updateDate = item.datetime_updated || item.update_time || item.last_update_time || '';\n                                    comics.push({\n                                        id: pw,\n                                        title: title,\n                                        subTitle: authorName2,\n                                        cover: cover,\n                                        tags: [],\n                                        description: updateDate\n                                    });\n                                }\n                            }\n                        }\n                    } catch (e) {\n                    }\n                }\n\n                if (comics.length === 0) {\n                    let items = doc.querySelectorAll('.exemptComic-box a[href*=\"/comic/\"]');\n                    if (items.length === 0) {\n                        items = doc.querySelectorAll('.correlationList a[href*=\"/comic/\"]');\n                    }\n                    for (let i = 0; i < items.length; i++) {\n                        let item = items[i];\n                        let href = item.attributes['href'] || '';\n                        let pw = href.replace('/comic/', '').replace('/', '');\n\n                        let title = '';\n                        let titleEl = item.querySelector('[title]');\n                        if (titleEl && titleEl.attributes) {\n                            title = titleEl.attributes['title'] || '';\n                        }\n                        if (!title) {\n                            titleEl = item.querySelector('.edit-txt');\n                            title = titleEl && titleEl.text ? titleEl.text.trim() : '';\n                        }\n                        if (!title) {\n                            titleEl = item.querySelector('.twoLines');\n                            title = titleEl && titleEl.text ? titleEl.text.trim() : '';\n                        }\n                        if (!title) {\n                            title = item.text ? item.text.trim() : '';\n                        }\n\n                        let img = item.querySelector('img');\n                        let cover = '';\n                        if (img && img.attributes) {\n                            cover = img.attributes['src'] || img.attributes['data-src'] || '';\n                        }\n\n                        let authorEl2 = item.querySelector('.exemptComicItem-txt-span');\n                        if (!authorEl2) authorEl2 = item.querySelector('.oneLines');\n                        let authorName2 = authorEl2 && authorEl2.text ? authorEl2.text.trim() : '';\n\n                        let updateEl = item.querySelector('.update span');\n                        let updateTime = updateEl && updateEl.text ? updateEl.text.trim() : '';\n\n                        if (title && pw) {\n                            comics.push({\n                                id: pw,\n                                title: title,\n                                subTitle: authorName2,\n                                cover: cover,\n                                tags: [],\n                                description: updateTime\n                            });\n                        }\n                    }\n                }\n\n                let maxPage = 1;\n                let totalFromList = 0;\n                if (listBox && listBox.attributes && listBox.attributes['total']) {\n                    totalFromList = parseInt(listBox.attributes['total']) || 0;\n                }\n                if (totalFromList > 0) {\n                    maxPage = Math.ceil(totalFromList / 30);\n                } else {\n                    let pager = doc.querySelector('.page-all');\n                    if (pager) {\n                        let totalLabels = pager.querySelectorAll('.page-total');\n                        if (totalLabels.length > 0) {\n                            let lastLabel = totalLabels[totalLabels.length - 1];\n                            maxPage = parseInt(lastLabel.text ? lastLabel.text.trim() : '1') || 1;\n                        }\n                    }\n                }\n\n                return {\n                    comics: comics,\n                    maxPage: maxPage\n                };\n            }\n\n            let category_url;\n            let isRanking = category === \"\u6392\u884c\" || param === \"ranking\";\n            \n            if (isRanking) {\n                let typeParam = options[0] || \"male\";\n                let tableParam = options[1] || \"week\";\n                category_url = `${this.apiUrl}/rank?type=${typeParam}&table=${tableParam}`;\n            } else {\n                if (category !== undefined && category !== null) {\n                    param = CopyManga.category_param_dict[category] || \"\";\n                }\n                let filterParam = options[0] || \"\";\n                let orderingRaw = options[1] || \"*datetime_updated\";\n                let orderingParam = orderingRaw.replace(\"*\", \"-\");\n                \n                let baseUrl = `${this.apiUrl}/comics?theme=${param}`;\n                if (filterParam && filterParam !== \"*all\") {\n                    let value = filterParam.replace(\"*\", \"\");\n                    if (value === \"finish\") {\n                        baseUrl += `&status=1`;\n                    } else {\n                        baseUrl += `&region=${value}`;\n                    }\n                }\n                baseUrl += `&ordering=${orderingParam}&limit=30&offset=${(page - 1) * 30}`;\n                category_url = baseUrl;\n            }\n\n            let res = await Network.get(category_url, this.webHeaders);\n            if (res.status !== 200) {\n                throw `Invalid status code: ${res.status}`;\n            }\n\n            let html = res.body;\n            let doc = new HtmlDocument(html);\n            let comics = [];\n            let totalFromList = 0;\n\n            if (isRanking) {\n                let items = doc.querySelectorAll('.ranking-all-box');\n                for (let i = 0; i < items.length; i++) {\n                    let item = items[i];\n                    let anchor = item.querySelector('a[href*=\"/comic/\"]');\n                    if (!anchor) continue;\n                    \n                    let href = anchor.attributes['href'] || '';\n                    let pathWord = href.replace('/comic/', '').replace('/', '');\n                    \n                    let titleEl = item.querySelector('.threeLines');\n                    let title = '';\n                    if (titleEl) {\n                        title = titleEl.attributes['title'] || '';\n                        if (!title && titleEl.text) title = titleEl.text.trim();\n                    }\n                    \n                    let img = item.querySelector('img');\n                    let cover = '';\n                    if (img && img.attributes) {\n                        cover = img.attributes['src'] || img.attributes['data-src'] || '';\n                    }\n                    \n                    let authorEl = item.querySelector('.oneLines');\n                    let author = authorEl && authorEl.text ? authorEl.text.replace(/\\s+/g, ' ').trim() : '';\n                    \n                    let rankEl = item.querySelector('.ranking-all-icon');\n                    let rank = rankEl && rankEl.text ? rankEl.text.trim() : '';\n                    \n                    let heatEl = item.querySelector('.update span');\n                    let heat = heatEl && heatEl.text ? heatEl.text.trim() : '';\n                    \n                    let trend = '';\n                    let trendEl = item.querySelector('.update-icon.up');\n                    if (trendEl) trend = '\u25b2';\n                    else {\n                        trendEl = item.querySelector('.update-icon.end');\n                        if (trendEl) trend = '\u25bd';\n                    }\n\n                    if (title && pathWord) {\n                        comics.push({\n                            id: pathWord,\n                            title: title,\n                            subTitle: author,\n                            cover: cover,\n                            tags: [],\n                            description: `${rank} ${trend}\\n${author}\\n\ud83d\udd25${heat}`\n                        });\n                    }\n                }\n            } else {\n                let listBox = doc.querySelector('.exemptComicList .exemptComic-box');\n                if (listBox && listBox.attributes) {\n                    if (listBox.attributes['total']) {\n                        totalFromList = parseInt(listBox.attributes['total']) || 0;\n                    }\n                    if (listBox.attributes['list']) {\n                        try {\n                            let rawList = listBox.attributes['list'];\n                            let decoded;\n                            try {\n                                decoded = JSON.parse(rawList);\n                            } catch (jsonErr) {\n                                decoded = new Function('return ' + rawList)();\n                            }\n                            if (Array.isArray(decoded)) {\n                                for (let item of decoded) {\n                                    let pathWord = item.path_word || '';\n                                    let title = item.name || '';\n                                    if (pathWord && title) {\n                                        let authors = item.author || [];\n                                        let authorNames = authors.map(a => a.name).filter(n => n);\n                                        let author = authorNames.length > 0 ? authorNames.join(', ') : '';\n                                        let cover = item.cover || '';\n                                        let updateDate = item.datetime_updated || item.update_time || item.last_update_time || '';\n                                        comics.push({\n                                            id: pathWord,\n                                            title: title,\n                                            subTitle: author,\n                                            cover: cover,\n                                            tags: [],\n                                            description: updateDate\n                                        });\n                                    }\n                                }\n                            }\n                        } catch (e) {\n                        }\n                    }\n                }\n                \n                if (comics.length === 0) {\n                    let items = doc.querySelectorAll('.exemptComic-box a[href*=\"/comic/\"]');\n                    if (items.length === 0) {\n                        items = doc.querySelectorAll('.correlationList a[href*=\"/comic/\"]');\n                    }\n                    for (let i = 0; i < items.length; i++) {\n                        let item = items[i];\n                        let href = item.attributes['href'] || '';\n                        let pathWord = href.replace('/comic/', '').replace('/', '');\n                        \n                        let title = '';\n                        let titleEl = item.querySelector('[title]');\n                        if (titleEl && titleEl.attributes) {\n                            title = titleEl.attributes['title'] || '';\n                        }\n                        if (!title) {\n                            titleEl = item.querySelector('.edit-txt');\n                            title = titleEl && titleEl.text ? titleEl.text.trim() : '';\n                        }\n                        if (!title) {\n                            titleEl = item.querySelector('.twoLines');\n                            title = titleEl && titleEl.text ? titleEl.text.trim() : '';\n                        }\n                        if (!title) {\n                            title = item.text ? item.text.trim() : '';\n                        }\n                        \n                        let img = item.querySelector('img');\n                        let cover = '';\n                        if (img && img.attributes) {\n                            cover = img.attributes['src'] || img.attributes['data-src'] || '';\n                        }\n                        \n                        let authorEl = item.querySelector('.exemptComicItem-txt-span');\n                        if (!authorEl) authorEl = item.querySelector('.oneLines');\n                        let author = authorEl && authorEl.text ? authorEl.text.trim() : '';\n                        \n                        let updateEl = item.querySelector('.update span');\n                        let updateTime = updateEl && updateEl.text ? updateEl.text.trim() : '';\n\n                        if (title && pathWord) {\n                            comics.push({\n                                id: pathWord,\n                                title: title,\n                                subTitle: author,\n                                cover: cover,\n                                tags: [],\n                                description: updateTime\n                            });\n                        }\n                    }\n                }\n            }\n\n            let maxPage = 1;\n            if (totalFromList > 0) {\n                maxPage = Math.ceil(totalFromList / 30);\n            } else {\n                let pager = doc.querySelector('.page-all');\n                if (pager) {\n                    let totalLabels = pager.querySelectorAll('.page-total');\n                    if (totalLabels.length > 0) {\n                        let lastLabel = totalLabels[totalLabels.length - 1];\n                        let total = parseInt(lastLabel.text ? lastLabel.text.trim() : '1') || 1;\n                        maxPage = total;\n                    }\n                }\n            }\n\n            return {\n                comics: comics,\n                maxPage: maxPage\n            };\n        },\n        optionList: [\n            {\n                options: [\n                    \"*all-\u5168\u90e8\",\n                    \"0-\u65e5\u6f2b\",\n                    \"1-\u97e9\u6f2b\",\n                    \"2-\u7f8e\u6f2b\",\n                    \"finish-\u5df2\u5b8c\u7ed3\"\n                ],\n                notShowWhen: null,\n                showWhen: Object.keys(CopyManga.category_param_dict)\n            },\n            {\n                options: [\n                    \"*datetime_updated-\u65f6\u95f4\u5012\u5e8f\",\n                    \"datetime_updated-\u65f6\u95f4\u6b63\u5e8f\",\n                    \"*popular-\u70ed\u5ea6\u5012\u5e8f\",\n                    \"popular-\u70ed\u5ea6\u6b63\u5e8f\",\n                ],\n                notShowWhen: null,\n                showWhen: Object.keys(CopyManga.category_param_dict)\n            },\n            {\n                options: [\n                    \"male-\u7537\u9891\",\n                    \"female-\u5973\u9891\"\n                ],\n                notShowWhen: null,\n                showWhen: [\"\u6392\u884c\"]\n            },\n            {\n                options: [\n                    \"day-\u4e0a\u5347\u6700\u5feb\",\n                    \"week-\u6700\u8fd17\u5929\",\n                    \"month-\u6700\u8fd130\u5929\",\n                    \"total-\u7e3d\u699c\u55ae\"\n                ],\n                notShowWhen: null,\n                showWhen: [\"\u6392\u884c\"]\n            }\n        ]\n    }\n\n    search = {\n        load: async (keyword, options, page) => {\n            let tagTheme = CopyManga.category_param_dict[keyword] || \"\";\n            if (!tagTheme && keyword.startsWith(\"tag:\")) {\n                let tagName = keyword.substring(\"tag:\".length).trim();\n                tagTheme = CopyManga.category_param_dict[tagName] || \"\";\n            }\n            if (tagTheme) {\n                let filterParam = options && options[0] ? options[0] : \"\";\n                let orderingRaw = options && options[1] ? options[1] : \"*datetime_updated\";\n                let orderingParam = orderingRaw.replace(\"*\", \"-\");\n\n                let baseUrl = `${this.apiUrl}/comics?theme=${tagTheme}`;\n                if (filterParam && filterParam !== \"*all\") {\n                    let value = filterParam.replace(\"*\", \"\");\n                    if (value === \"finish\") {\n                        baseUrl += `&status=1`;\n                    } else {\n                        baseUrl += `&region=${value}`;\n                    }\n                }\n                baseUrl += `&ordering=${orderingParam}&limit=30&offset=${(page - 1) * 30}`;\n\n                let res = await Network.get(baseUrl, this.webHeaders);\n                if (res.status !== 200) {\n                    throw `Invalid status code: ${res.status}`;\n                }\n\n                let html = res.body;\n                let doc = new HtmlDocument(html);\n                let comics = [];\n                let totalFromList = 0;\n\n                let listBox = doc.querySelector('.exemptComicList .exemptComic-box');\n                if (listBox && listBox.attributes) {\n                    if (listBox.attributes['total']) {\n                        totalFromList = parseInt(listBox.attributes['total']) || 0;\n                    }\n                    if (listBox.attributes['list']) {\n                        try {\n                            let rawList = listBox.attributes['list'];\n                            let decoded;\n                            try {\n                                decoded = JSON.parse(rawList);\n                            } catch (jsonErr) {\n                                decoded = new Function('return ' + rawList)();\n                            }\n                            if (Array.isArray(decoded)) {\n                                for (let item of decoded) {\n                                    let pw = item.path_word || '';\n                                    let title = item.name || '';\n                                    if (pw && title) {\n                                        let authors = item.author || [];\n                                        let authorNames = authors.map(a => a.name).filter(n => n);\n                                        comics.push({\n                                            id: pw,\n                                            title: title,\n                                            subTitle: authorNames.length > 0 ? authorNames.join(', ') : '',\n                                            cover: item.cover || '',\n                                            tags: [],\n                                            description: item.datetime_updated || item.update_time || item.last_update_time || ''\n                                        });\n                                    }\n                                }\n                            }\n                        } catch (e) {\n                        }\n                    }\n                }\n\n                if (comics.length === 0) {\n                    let items = doc.querySelectorAll('.exemptComic-box a[href*=\"/comic/\"]');\n                    if (items.length === 0) {\n                        items = doc.querySelectorAll('.correlationList a[href*=\"/comic/\"]');\n                    }\n                    for (let i = 0; i < items.length; i++) {\n                        let item = items[i];\n                        let href = item.attributes['href'] || '';\n                        let pw = href.replace('/comic/', '').replace('/', '');\n\n                        let title = '';\n                        let titleEl = item.querySelector('[title]');\n                        if (titleEl && titleEl.attributes) {\n                            title = titleEl.attributes['title'] || '';\n                        }\n                        if (!title) {\n                            titleEl = item.querySelector('.edit-txt');\n                            title = titleEl && titleEl.text ? titleEl.text.trim() : '';\n                        }\n                        if (!title) {\n                            titleEl = item.querySelector('.twoLines');\n                            title = titleEl && titleEl.text ? titleEl.text.trim() : '';\n                        }\n                        if (!title) {\n                            title = item.text ? item.text.trim() : '';\n                        }\n\n                        let img = item.querySelector('img');\n                        let cover = '';\n                        if (img && img.attributes) {\n                            cover = img.attributes['src'] || img.attributes['data-src'] || '';\n                        }\n\n                        let authorEl = item.querySelector('.exemptComicItem-txt-span');\n                        if (!authorEl) authorEl = item.querySelector('.oneLines');\n                        let an = authorEl && authorEl.text ? authorEl.text.trim() : '';\n\n                        let updateEl = item.querySelector('.update span');\n                        let updateTime = updateEl && updateEl.text ? updateEl.text.trim() : '';\n\n                        if (title && pw) {\n                            comics.push({\n                                id: pw,\n                                title: title,\n                                subTitle: an,\n                                cover: cover,\n                                tags: [],\n                                description: updateTime\n                            });\n                        }\n                    }\n                }\n\n                let maxPage = 1;\n                if (totalFromList > 0) {\n                    maxPage = Math.ceil(totalFromList / 30);\n                } else {\n                    let pager = doc.querySelector('.page-all');\n                    if (pager) {\n                        let totalLabels = pager.querySelectorAll('.page-total');\n                        if (totalLabels.length > 0) {\n                            let lastLabel = totalLabels[totalLabels.length - 1];\n                            maxPage = parseInt(lastLabel.text ? lastLabel.text.trim() : '1') || 1;\n                        }\n                    }\n                }\n\n                return {\n                    comics: comics,\n                    maxPage: maxPage\n                };\n            }\n\n            if (keyword.startsWith(\"tag:\")) {\n                let tagName = keyword.substring(\"tag:\".length).trim();\n                let themeParam = CopyManga.category_param_dict[tagName] || \"\";\n\n                if (!themeParam) {\n                    try {\n                        let searchHtmlRes = await Network.get(\n                            `${this.apiUrl}/search?q=${encodeURIComponent(tagName)}`,\n                            this.webHeaders\n                        );\n                        if (searchHtmlRes.status === 200) {\n                            let searchDoc = new HtmlDocument(searchHtmlRes.body);\n                            let tagLinks = searchDoc.querySelectorAll('.comicParticulars-tag a, a[href*=\"/comics?theme=\"]');\n                            for (let i = 0; i < tagLinks.length; i++) {\n                                let link = tagLinks[i];\n                                let href = link.attributes['href'] || '';\n                                let themeMatch = href.match(/theme=([^&]+)/);\n                                if (themeMatch && themeMatch[1]) {\n                                    let name = link.text ? link.text.trim().replace(/^#/, '') : '';\n                                    if (name) {\n                                        CopyManga.category_param_dict[name] = themeMatch[1];\n                                    }\n                                    if (!themeParam && name === tagName) {\n                                        themeParam = themeMatch[1];\n                                    }\n                                    if (!themeParam) {\n                                        themeParam = themeMatch[1];\n                                    }\n                                }\n                            }\n                        }\n                    } catch (e) {\n                    }\n                }\n\n                if (themeParam) {\n                    CopyManga.category_param_dict[tagName] = themeParam;\n                    let filterParam = options && options[0] ? options[0] : \"\";\n                    let orderingRaw = options && options[1] ? options[1] : \"*datetime_updated\";\n                    let orderingParam = orderingRaw.replace(\"*\", \"-\");\n\n                    let baseUrl = `${this.apiUrl}/comics?theme=${themeParam}`;\n                    if (filterParam && filterParam !== \"*all\") {\n                        let value = filterParam.replace(\"*\", \"\");\n                        if (value === \"finish\") {\n                            baseUrl += `&status=1`;\n                        } else {\n                            baseUrl += `&region=${value}`;\n                        }\n                    }\n                    baseUrl += `&ordering=${orderingParam}&limit=30&offset=${(page - 1) * 30}`;\n\n                    let res = await Network.get(baseUrl, this.webHeaders);\n                    if (res.status !== 200) {\n                        throw `Invalid status code: ${res.status}`;\n                    }\n\n                    let html = res.body;\n                    let doc = new HtmlDocument(html);\n                    let comics = [];\n                    let totalFromList = 0;\n\n                    let listBox = doc.querySelector('.exemptComicList .exemptComic-box');\n                    if (listBox && listBox.attributes) {\n                        if (listBox.attributes['total']) {\n                            totalFromList = parseInt(listBox.attributes['total']) || 0;\n                        }\n                        if (listBox.attributes['list']) {\n                            try {\n                                let rawList = listBox.attributes['list'];\n                                let decoded;\n                                try {\n                                    decoded = JSON.parse(rawList);\n                                } catch (jsonErr) {\n                                    decoded = new Function('return ' + rawList)();\n                                }\n                                if (Array.isArray(decoded)) {\n                                    for (let item of decoded) {\n                                        let pw = item.path_word || '';\n                                        let title = item.name || '';\n                                        if (pw && title) {\n                                            let authors = item.author || [];\n                                            let authorNames = authors.map(a => a.name).filter(n => n);\n                                            comics.push({\n                                                id: pw,\n                                                title: title,\n                                                subTitle: authorNames.length > 0 ? authorNames.join(', ') : '',\n                                                cover: item.cover || '',\n                                                tags: [],\n                                                description: item.datetime_updated || item.update_time || item.last_update_time || ''\n                                            });\n                                        }\n                                    }\n                                }\n                            } catch (e) {\n                            }\n                        }\n                    }\n\n                    if (comics.length === 0) {\n                        let items = doc.querySelectorAll('.exemptComic-box a[href*=\"/comic/\"]');\n                        if (items.length === 0) {\n                            items = doc.querySelectorAll('.correlationList a[href*=\"/comic/\"]');\n                        }\n                        for (let i = 0; i < items.length; i++) {\n                            let item = items[i];\n                            let href = item.attributes['href'] || '';\n                            let pw = href.replace('/comic/', '').replace('/', '');\n\n                            let title = '';\n                            let titleEl = item.querySelector('[title]');\n                            if (titleEl && titleEl.attributes) {\n                                title = titleEl.attributes['title'] || '';\n                            }\n                            if (!title) {\n                                titleEl = item.querySelector('.edit-txt');\n                                title = titleEl && titleEl.text ? titleEl.text.trim() : '';\n                            }\n                            if (!title) {\n                                titleEl = item.querySelector('.twoLines');\n                                title = titleEl && titleEl.text ? titleEl.text.trim() : '';\n                            }\n                            if (!title) {\n                                title = item.text ? item.text.trim() : '';\n                            }\n\n                            let img = item.querySelector('img');\n                            let cover = '';\n                            if (img && img.attributes) {\n                                cover = img.attributes['src'] || img.attributes['data-src'] || '';\n                            }\n\n                            let authorEl = item.querySelector('.exemptComicItem-txt-span');\n                            if (!authorEl) authorEl = item.querySelector('.oneLines');\n                            let an = authorEl && authorEl.text ? authorEl.text.trim() : '';\n\n                            let updateEl = item.querySelector('.update span');\n                            let updateTime = updateEl && updateEl.text ? updateEl.text.trim() : '';\n\n                            if (title && pw) {\n                                comics.push({\n                                    id: pw,\n                                    title: title,\n                                    subTitle: an,\n                                    cover: cover,\n                                    tags: [],\n                                    description: updateTime\n                                });\n                            }\n                        }\n                    }\n\n                    let maxPage = 1;\n                    if (totalFromList > 0) {\n                        maxPage = Math.ceil(totalFromList / 30);\n                    } else {\n                        let pager = doc.querySelector('.page-all');\n                        if (pager) {\n                            let totalLabels = pager.querySelectorAll('.page-total');\n                            if (totalLabels.length > 0) {\n                                let lastLabel = totalLabels[totalLabels.length - 1];\n                                maxPage = parseInt(lastLabel.text ? lastLabel.text.trim() : '1') || 1;\n                            }\n                        }\n                    }\n\n                    return {\n                        comics: comics,\n                        maxPage: maxPage\n                    };\n                }\n            }\n\n            let authorName = keyword;\n            if (keyword.startsWith(\"\u4f5c\u8005:\")) {\n                authorName = keyword.substring(\"\u4f5c\u8005:\".length).trim();\n            }\n\n            let pathWord = this.author_path_word_dict[authorName] || null;\n\n            if (!pathWord) {\n                try {\n                    let searchHtmlRes = await Network.get(\n                        `${this.apiUrl}/search?q=${encodeURIComponent(authorName)}`,\n                        this.webHeaders\n                    );\n                    if (searchHtmlRes.status === 200) {\n                        let searchDoc = new HtmlDocument(searchHtmlRes.body);\n                        let authorLinks = searchDoc.querySelectorAll('a[href*=\"/author/\"]');\n                        for (let i = 0; i < authorLinks.length; i++) {\n                            let link = authorLinks[i];\n                            let href = link.attributes['href'] || '';\n                            let match = href.match(/\\/author\\/([^\\/]+)/);\n                            if (match && match[1]) {\n                                let name = link.text ? link.text.trim() : '';\n                                if (name) {\n                                    this.author_path_word_dict[name] = match[1];\n                                }\n                                if (!pathWord && name === authorName) {\n                                    pathWord = match[1];\n                                }\n                                if (!pathWord) {\n                                    pathWord = match[1];\n                                }\n                            }\n                        }\n                    }\n                } catch (e) {\n                }\n            }\n\n            if (pathWord) {\n                this.author_path_word_dict[authorName] = pathWord;\n                let authorUrl = `${this.apiUrl}/author/${encodeURIComponent(pathWord)}/comics?page=${page}`;\n                let res = await Network.get(authorUrl, this.webHeaders);\n                if (res.status !== 200) {\n                    throw `Invalid status code: ${res.status}`;\n                }\n\n                let doc = new HtmlDocument(res.body);\n                let comics = [];\n\n                let listBox = doc.querySelector('.exemptComicList .exemptComic-box');\n                if (listBox && listBox.attributes && listBox.attributes['list']) {\n                    try {\n                        let rawList = listBox.attributes['list'];\n                        let decoded;\n                        try {\n                            decoded = JSON.parse(rawList);\n                        } catch (jsonErr) {\n                            decoded = new Function('return ' + rawList)();\n                        }\n                        if (Array.isArray(decoded)) {\n                            for (let item of decoded) {\n                                let pw = item.path_word || '';\n                                let title = item.name || '';\n                                if (pw && title) {\n                                    let authors = item.author || [];\n                                    let authorNames = authors.map(a => a.name).filter(n => n);\n                                    comics.push({\n                                        id: pw,\n                                        title: title,\n                                        subTitle: authorNames.length > 0 ? authorNames.join(', ') : '',\n                                        cover: item.cover || '',\n                                        tags: [],\n                                        description: item.datetime_updated || item.update_time || item.last_update_time || ''\n                                    });\n                                }\n                            }\n                        }\n                    } catch (e) {\n                    }\n                }\n\n                if (comics.length === 0) {\n                    let items = doc.querySelectorAll('.exemptComic-box a[href*=\"/comic/\"]');\n                    if (items.length === 0) {\n                        items = doc.querySelectorAll('.correlationList a[href*=\"/comic/\"]');\n                    }\n                    for (let i = 0; i < items.length; i++) {\n                        let item = items[i];\n                        let href = item.attributes['href'] || '';\n                        let pw = href.replace('/comic/', '').replace('/', '');\n\n                        let title = '';\n                        let titleEl = item.querySelector('[title]');\n                        if (titleEl && titleEl.attributes) {\n                            title = titleEl.attributes['title'] || '';\n                        }\n                        if (!title) {\n                            titleEl = item.querySelector('.edit-txt');\n                            title = titleEl && titleEl.text ? titleEl.text.trim() : '';\n                        }\n                        if (!title) {\n                            titleEl = item.querySelector('.twoLines');\n                            title = titleEl && titleEl.text ? titleEl.text.trim() : '';\n                        }\n                        if (!title) {\n                            title = item.text ? item.text.trim() : '';\n                        }\n\n                        let img = item.querySelector('img');\n                        let cover = '';\n                        if (img && img.attributes) {\n                            cover = img.attributes['src'] || img.attributes['data-src'] || '';\n                        }\n\n                        let authorEl = item.querySelector('.exemptComicItem-txt-span');\n                        if (!authorEl) authorEl = item.querySelector('.oneLines');\n                        let an = authorEl && authorEl.text ? authorEl.text.trim() : '';\n\n                        let updateEl = item.querySelector('.update span');\n                        let updateTime = updateEl && updateEl.text ? updateEl.text.trim() : '';\n\n                        if (title && pw) {\n                            comics.push({\n                                id: pw,\n                                title: title,\n                                subTitle: an,\n                                cover: cover,\n                                tags: [],\n                                description: updateTime\n                            });\n                        }\n                    }\n                }\n\n                let maxPage = 1;\n                let totalFromList = 0;\n                if (listBox && listBox.attributes && listBox.attributes['total']) {\n                    totalFromList = parseInt(listBox.attributes['total']) || 0;\n                }\n                if (totalFromList > 0) {\n                    maxPage = Math.ceil(totalFromList / 30);\n                } else {\n                    let pager = doc.querySelector('.page-all');\n                    if (pager) {\n                        let totalLabels = pager.querySelectorAll('.page-total');\n                        if (totalLabels.length > 0) {\n                            let lastLabel = totalLabels[totalLabels.length - 1];\n                            maxPage = parseInt(lastLabel.text ? lastLabel.text.trim() : '1') || 1;\n                        }\n                    }\n                }\n\n                return {\n                    comics: comics,\n                    maxPage: maxPage\n                };\n            }\n\n            let q_type = \"\";\n            if (options && options[0]) {\n                q_type = options[0];\n            }\n            keyword = encodeURIComponent(keyword)\n            let search_url = this.loadSetting('search_api') === \"webAPI\"\n                ? `${this.apiUrl}${CopyManga.searchApi}`\n                : `${this.apiUrl}/api/v3/search/comic`\n            let res = await Network.get(\n                `${search_url}?limit=30&offset=${(page - 1) * 30}&q=${keyword}&q_type=${q_type}`,\n                this.webApiHeaders\n            )\n            if (res.status !== 200) {\n                throw `Invalid status code: ${res.status}`\n            }\n\n            let data = JSON.parse(res.body)\n\n            function parseComic(comic) {\n                if (comic[\"comic\"] !== null && comic[\"comic\"] !== undefined) {\n                    comic = comic[\"comic\"]\n                }\n                let tags = []\n                if (comic[\"theme\"] !== null && comic[\"theme\"] !== undefined) {\n                    tags = comic[\"theme\"].map(t => t[\"name\"])\n                }\n                let author = null\n\n                if (Array.isArray(comic[\"author\"]) && comic[\"author\"].length > 0) {\n                    author = comic[\"author\"][0][\"name\"]\n                    if (author && comic[\"author\"][0][\"path_word\"]) {\n                        this.author_path_word_dict[author] = comic[\"author\"][0][\"path_word\"]\n                    }\n                }\n\n                return {\n                    id: comic[\"path_word\"],\n                    title: comic[\"name\"],\n                    subTitle: author,\n                    cover: comic[\"cover\"],\n                    tags: tags,\n                    description: comic[\"datetime_updated\"]\n                }\n            }\n\n            return {\n                comics: data[\"results\"][\"list\"].map(parseComic.bind(this)),\n                maxPage: (data[\"results\"][\"total\"] - (data[\"results\"][\"total\"] % 21)) / 21 + 1\n            }\n        },\n        optionList: [\n            {\n                type: \"select\",\n                options: [\n                    \"-\u5168\u90e8\",\n                    \"name-\u540d\u79f0\",\n                    \"author-\u4f5c\u8005\",\n                    \"local-\u6c49\u5316\u7ec4\"\n                ],\n                label: \"\u641c\u7d22\u9009\u9879\"\n            }\n        ]\n    }\n\n    favorites = {\n        multiFolder: false,\n        addOrDelFavorite: async (comicId, folderId, isAdding) => {\n            let is_collect = isAdding ? 1 : 0\n            let token = this.loadData(\"token\");\n            let comicData = await Network.get(\n                `${this.apiUrl}/api/v3/comic2/${comicId}?in_mainland=true&platform=2`,\n                this.webApiHeaders\n            )\n            if (comicData.status !== 200) {\n                throw `Invalid status code: ${comicData.status}`\n            }\n            let comic_id = JSON.parse(comicData.body).results.comic.uuid\n            let res = await Network.post(\n                `${this.apiUrl}/api/v3/member/collect/comic`,\n                {\n                    ...this.webApiHeaders,\n                    \"Content-Type\": \"application/x-www-form-urlencoded;charset=utf-8\",\n                },\n                `comic_id=${comic_id}&is_collect=${is_collect}`\n            )\n            if (res.status === 401) {\n                throw `Login expired`;\n            }\n            if (res.status !== 200) {\n                throw `Invalid status code: ${res.status}`\n            }\n            return \"ok\"\n        },\n        loadComics: async (page, folder) => {\n            let ordering = this.loadSetting('favorites_ordering') || '-datetime_updated';\n            var res = await Network.get(\n                `${this.apiUrl}/api/v3/member/collect/comics?limit=30&offset=${(page - 1) * 30}&free_type=1&ordering=${ordering}`,\n                this.webApiHeaders\n            )\n\n            if (res.status === 401) {\n                throw `Login expired`\n            }\n\n            if (res.status !== 200) {\n                throw `Invalid status code: ${res.status}`\n            }\n\n            let data = JSON.parse(res.body)\n\n            function parseComic(comic) {\n                if (comic[\"comic\"] !== null && comic[\"comic\"] !== undefined) {\n                    comic = comic[\"comic\"]\n                }\n                let tags = []\n                if (comic[\"theme\"] !== null && comic[\"theme\"] !== undefined) {\n                    tags = comic[\"theme\"].map(t => t[\"name\"])\n                }\n                let author = null\n\n                if (Array.isArray(comic[\"author\"]) && comic[\"author\"].length > 0) {\n                    author = comic[\"author\"][0][\"name\"]\n                    if (author && comic[\"author\"][0][\"path_word\"]) {\n                        this.author_path_word_dict[author] = comic[\"author\"][0][\"path_word\"]\n                    }\n                }\n\n                return {\n                    id: comic[\"path_word\"],\n                    title: comic[\"name\"],\n                    subTitle: author,\n                    cover: comic[\"cover\"],\n                    tags: tags,\n                    description: comic[\"datetime_updated\"]\n                }\n            }\n\n            return {\n                comics: data[\"results\"][\"list\"].map(parseComic.bind(this)),\n                maxPage: (data[\"results\"][\"total\"] - (data[\"results\"][\"total\"] % 21)) / 21 + 1\n            }\n        }\n    }\n\n    comic = {\n        loadInfo: async (id) => {\n            let res = await Network.get(`${this.apiUrl}/comic/${id}`, this.webHeaders);\n            if (res.status !== 200) {\n                throw `Invalid status code: ${res.status}`;\n            }\n\n            let html = res.body;\n            let doc = new HtmlDocument(html);\n\n            let title = '';\n            let titleEl = doc.querySelector('h6[title]');\n            if (titleEl && titleEl.attributes) {\n                title = titleEl.attributes['title'] || '';\n            }\n            if (!title) {\n                let pageTitle = doc.querySelector('title');\n                title = pageTitle && pageTitle.text ? pageTitle.text.trim().split('-')[0].trim() : '';\n            }\n\n            let cover = '';\n            let coverImg = doc.querySelector('.comicParticulars-left-img img');\n            if (coverImg && coverImg.attributes) {\n                cover = coverImg.attributes['src'] || coverImg.attributes['data-src'] || '';\n            }\n\n            let authors = [];\n            let authorEl = doc.querySelector('.comicParticulars-title-right');\n            if (authorEl) {\n                let authorLinks = authorEl.querySelectorAll('a');\n                for (let i = 0; i < authorLinks.length; i++) {\n                    let link = authorLinks[i];\n                    let text = link.text ? link.text.trim() : '';\n                    if (text && !authors.includes(text)) {\n                        authors.push(text);\n                    }\n                    let href = link.attributes['href'] || '';\n                    let authorMatch = href.match(/\\/author\\/([^\\/]+)/);\n                    if (authorMatch && authorMatch[1] && text) {\n                        this.author_path_word_dict[text] = authorMatch[1];\n                    }\n                }\n            }\n\n            let tags = [];\n            let tagEls = doc.querySelectorAll('.comicParticulars-tag a');\n            for (let i = 0; i < tagEls.length; i++) {\n                let tag = tagEls[i];\n                let text = tag.text ? tag.text.trim().replace(/^#/, '') : '';\n                if (text && !tags.includes(text)) {\n                    tags.push(text);\n                }\n            }\n\n            let description = '';\n            let introEl = doc.querySelector('.intro');\n            if (introEl) {\n                description = introEl.text ? introEl.text.trim() : '';\n            }\n\n            let updateTime = '';\n            let status = '';\n            let infoItems = doc.querySelectorAll('.comicParticulars-title-right li');\n            for (let i = 0; i < infoItems.length; i++) {\n                let item = infoItems[i];\n                let text = item.text ? item.text.replace(/\\s+/g, ' ').trim() : '';\n                if (text.includes('\u6700\u5f8c\u66f4\u65b0') || text.includes('\u6700\u540e\u66f4\u65b0')) {\n                    updateTime = text.replace(/\u6700\u5f8c\u66f4\u65b0|\u6700\u540e\u66f4\u65b0/g, '').replace(/^\\s*[:\uff1a]\\s*/, '').trim();\n                }\n                if (text.includes('\u72c0\u614b') || text.includes('\u72b6\u6001')) {\n                    status = text.replace(/\u72c0\u614b|\u72b6\u6001/g, '').replace(/^\\s*[:\uff1a]\\s*/, '').trim();\n                }\n            }\n\n            let chapters = {};\n            let hasGroups = false;\n            \n            let ccz = '';\n            let cczPatterns = [\n                /(?:var|let|const)\\s+ccz\\s*=\\s*['\"]([^'\"]+)['\"]/,\n                /window\\.ccz\\s*=\\s*['\"]([^'\"]+)['\"]/,\n                /ccz\\s*=\\s*['\"]([^'\"]+)['\"]/\n            ];\n            for (let pattern of cczPatterns) {\n                let match = html.match(pattern);\n                if (match && match[1]) {\n                    ccz = match[1];\n                    break;\n                }\n            }\n            \n            let dntEl = doc.querySelector('#dnt');\n            let dnt = dntEl && dntEl.attributes ? dntEl.attributes['value'] || '' : '';\n            \n            if (ccz && dnt) {\n                try {\n                    let chapterUrl = `${this.apiUrl}/comicdetail/${id}/chapters`;\n                    \n                    let chapterRes = await Network.get(\n                        chapterUrl,\n                        {\n                            ...this.webHeaders,\n                            'Accept': 'application/json, text/plain, */*',\n                            'Referer': `${this.apiUrl}/comic/${id}`,\n                            'dnts': dnt\n                        }\n                    );\n                    \n                    if (chapterRes.status === 200) {\n                        let chapterData = JSON.parse(chapterRes.body);\n                        \n                        if (chapterData.code === 200 && chapterData.results) {\n                            let encrypted = chapterData.results.trim();\n                            \n                            if (encrypted.length > 16) {\n                                let ivStr = encrypted.substring(0, 16);\n                                let cipherStr = encrypted.substring(16);\n                                \n                                let keyBytes = Convert.encodeUtf8(ccz);\n                                let ivBytes = Convert.encodeUtf8(ivStr);\n                                \n                                let cipherBytes;\n                                if (/^[0-9a-fA-F]+$/.test(cipherStr) && cipherStr.length % 2 === 0) {\n                                    let hexBytes = new Uint8Array(cipherStr.length / 2);\n                                    for (let i = 0; i < cipherStr.length; i += 2) {\n                                        hexBytes[i / 2] = parseInt(cipherStr.substring(i, i + 2), 16);\n                                    }\n                                    cipherBytes = hexBytes.buffer;\n                                } else {\n                                    cipherBytes = Convert.decodeBase64(cipherStr);\n                                }\n                                \n                                let plainBytes = Convert.decryptAesCbc(cipherBytes, keyBytes, ivBytes);\n                                let plainText = Convert.decodeUtf8(plainBytes);\n                                let parsed = JSON.parse(plainText);\n                                \n                                let groups = parsed.groups;\n                                if (groups) {\n                                    let groupKeys = Object.keys(groups);\n                                    for (let gKey of groupKeys) {\n                                        let group = groups[gKey];\n                                        let groupName = group.name || gKey;\n                                        if (group.chapters && group.chapters.length > 0) {\n                                            hasGroups = true;\n                                            let groupChapters = {};\n                                            for (let chapter of group.chapters) {\n                                                let chapterId = chapter.id || chapter.uuid;\n                                                let chapterTitle = chapter.name || '';\n                                                if (chapterId && chapterTitle) {\n                                                    groupChapters[chapterId] = chapterTitle;\n                                                }\n                                            }\n                                            chapters[groupName] = groupChapters;\n                                        }\n                                    }\n                                }\n                            }\n                        }\n                    }\n                } catch (e) {\n                    console.log('Chapter decryption failed: ' + e);\n                }\n            }\n\n            if (!hasGroups) {\n                chapters = {};\n                let chapterLinks = doc.querySelectorAll('a[href*=\"/chapter/\"]');\n                \n                let seenChapters = new Set();\n                for (let i = chapterLinks.length - 1; i >= 0; i--) {\n                    let link = chapterLinks[i];\n                    let href = link.attributes['href'] || '';\n                    let chapterTitle = link.text ? link.text.trim() : '';\n                    \n                    if (href && chapterTitle && !seenChapters.has(href)) {\n                        seenChapters.add(href);\n                        let parts = href.split('/');\n                        let chapterId = '';\n                        for (let j = parts.length - 1; j >= 0; j--) {\n                            if (parts[j] && parts[j] !== 'chapter') {\n                                chapterId = parts[j];\n                                break;\n                            }\n                        }\n                        if (chapterId) {\n                            chapters[chapterId] = chapterTitle;\n                        }\n                    }\n                }\n            }\n\n            let comicId = '';\n            let collectBtn = doc.querySelector('.comicParticulars-botton.collect');\n            if (collectBtn && collectBtn.attributes) {\n                let onclick = collectBtn.attributes['onclick'] || '';\n                let match = onclick.match(/collect\\s*\\(\\s*['\"]([^'\"]+)['\"]/);\n                if (match) {\n                    comicId = match[1];\n                }\n            }\n\n            let isFavorite = false;\n            if (collectBtn) {\n                let btnText = collectBtn.text || '';\n                isFavorite = btnText.includes('\u5df2\u6536\u85cf');\n            }\n\n            return {\n                title: title,\n                cover: cover,\n                description: description,\n                tags: {\n                    \"\u4f5c\u8005\": authors,\n                    \"\u66f4\u65b0\": [updateTime],\n                    \"\u6807\u7b7e\": tags,\n                    \"\u72b6\u6001\": [status],\n                },\n                chapters: chapters,\n                isFavorite: isFavorite,\n                subId: comicId\n            }\n        },\n        loadEp: async (comicId, epId) => {\n            let res = await Network.get(\n                `${this.apiUrl}/comic/${comicId}/chapter/${epId}`,\n                this.webHeaders\n            );\n\n            if (res.status !== 200) {\n                throw `Invalid status code: ${res.status}`;\n            }\n\n            let html = res.body;\n            let doc = new HtmlDocument(html);\n\n            let images = [];\n            \n            let contentKey = '';\n            let contentKeyPatterns = [\n                /(?:var|let|const)\\s+contentKey\\s*=\\s*['\"]([^'\"]+)['\"]/,\n                /window\\.contentKey\\s*=\\s*['\"]([^'\"]+)['\"]/,\n                /contentKey\\s*=\\s*['\"]([^'\"]+)['\"]/\n            ];\n            for (let pattern of contentKeyPatterns) {\n                let match = html.match(pattern);\n                if (match && match[1]) {\n                    contentKey = match[1];\n                    break;\n                }\n            }\n            \n            let cct = '';\n            let cctPatterns = [\n                /(?:var|let|const)\\s+cct\\s*=\\s*['\"]([^'\"]+)['\"]/,\n                /window\\.cct\\s*=\\s*['\"]([^'\"]+)['\"]/,\n                /cct\\s*=\\s*['\"]([^'\"]+)['\"]/\n            ];\n            for (let pattern of cctPatterns) {\n                let match = html.match(pattern);\n                if (match && match[1]) {\n                    cct = match[1];\n                    break;\n                }\n            }\n            \n            if (contentKey && cct && contentKey.length > 16) {\n                try {\n                    let ivStr = contentKey.substring(0, 16);\n                    let cipherStr = contentKey.substring(16);\n                    \n                    let keyBytes = Convert.encodeUtf8(cct);\n                    let ivBytes = Convert.encodeUtf8(ivStr);\n                    \n                    let cipherBytes;\n                    if (/^[0-9a-fA-F]+$/.test(cipherStr) && cipherStr.length % 2 === 0) {\n                        let hexBytes = new Uint8Array(cipherStr.length / 2);\n                        for (let i = 0; i < cipherStr.length; i += 2) {\n                            hexBytes[i / 2] = parseInt(cipherStr.substring(i, i + 2), 16);\n                        }\n                        cipherBytes = hexBytes.buffer;\n                    } else {\n                        cipherBytes = Convert.decodeBase64(cipherStr);\n                    }\n                    \n                    let plainBytes = Convert.decryptAesCbc(cipherBytes, keyBytes, ivBytes);\n                    let plainText = Convert.decodeUtf8(plainBytes);\n                    let parsed = JSON.parse(plainText);\n                    \n                    if (Array.isArray(parsed)) {\n                        for (let item of parsed) {\n                            let url = '';\n                            if (typeof item === 'string') {\n                                url = item;\n                            } else if (item && item.url) {\n                                url = item.url;\n                            }\n                            if (url) {\n                                if (url.startsWith('//')) {\n                                    url = 'https:' + url;\n                                }\n                                url = url.replace(/([./])c\\d+x\\.[a-zA-Z]+$/, `$1c${this.imageQuality}x.webp`);\n                                images.push(url);\n                            }\n                        }\n                    }\n                } catch (e) {\n                    console.log('Image decryption failed: ' + e);\n                }\n            }\n\n            if (images.length === 0) {\n                let imgEls = doc.querySelectorAll('.comicContent-list img');\n                for (let i = 0; i < imgEls.length; i++) {\n                    let img = imgEls[i];\n                    let src = '';\n                    if (img.attributes) {\n                        src = img.attributes['src'] || img.attributes['data-src'] || img.attributes['data-original'] || '';\n                    }\n                    if (src) {\n                        if (src.startsWith('//')) {\n                            src = 'https:' + src;\n                        }\n                        src = src.replace(/([./])c\\d+x\\.[a-zA-Z]+$/, `$1c${this.imageQuality}x.webp`);\n                        images.push(src);\n                    }\n                }\n            }\n\n            if (images.length === 0) {\n                throw 'No images found in chapter';\n            }\n\n            return {\n                images: images,\n            };\n        },\n        loadComments: async (comicId, subId, page, replyTo) => {\n            let url = `https://api.mangacopy.com/api/v3/comments?comic_id=${subId}&limit=20&offset=${(page - 1) * 20}`;\n            if (replyTo) {\n                url = url + `&reply_id=${replyTo}&_update=true`;\n            }\n            let res = await Network.get(\n                url,\n                {\n                    ...this.webHeaders,\n                    'Accept': 'application/json',\n                }\n            );\n\n            if (res.status !== 200) {\n                if (res.status === 210) {\n                    throw \"210\uff1a\u6ce8\u518a\u7528\u6236\u4e00\u5929\u53ef\u4ee5\u767c5\u689d\u8a55\u8ad6\"\n                }\n                throw `Invalid status code: ${res.status}`;\n            }\n\n            let data = JSON.parse(res.body);\n\n            let total = data.results.total;\n\n            return {\n                comments: data.results.list.map(e => {\n                    return {\n                        userName: replyTo ? `${e.user_name}  \ud83d\udc49  ${e.parent_user_name}` : e.user_name,\n                        avatar: e.user_avatar,\n                        content: e.comment,\n                        time: e.create_at,\n                        replyCount: e.count,\n                        id: e.id,\n                    }\n                }),\n                maxPage: (total - (total % 20)) / 20 + 1,\n            }\n        },\n        sendComment: async (comicId, subId, content, replyTo) => {\n            let token = this.loadData(\"token\");\n            if (!token) {\n                throw \"\u672a\u767b\u5f55\"\n            }\n            if (!replyTo) {\n                replyTo = '';\n            }\n            let res = await Network.post(\n                `https://api.mangacopy.com/api/v3/member/comment`,\n                {\n                    ...this.webHeaders,\n                    \"Content-Type\": \"application/x-www-form-urlencoded;charset=utf-8\",\n                    \"Accept\": \"application/json\",\n                    \"Authorization\": `Token ${token}`,\n                },\n                `comic_id=${subId}&comment=${encodeURIComponent(content)}&reply_id=${replyTo}`,\n            );\n\n            if (res.status === 401) {\n                error(`Login expired`);\n                return;\n            }\n\n            if (res.status !== 200) {\n                throw `Invalid status code: ${res.status}`;\n            } else {\n                return \"ok\"\n            }\n        },\n        loadChapterComments: async (comicId, epId, page, replyTo) => {\n            let url = `https://api.mangacopy.com/api/v3/roasts?chapter_id=${epId}&limit=20&offset=${(page - 1) * 20}&_update=true`;\n            let res = await Network.get(\n                url,\n                {\n                    ...this.webHeaders,\n                    'Accept': 'application/json',\n                }\n            );\n\n            if (res.status !== 200) {\n                throw `Invalid status code: ${res.status}`;\n            }\n\n            let data = JSON.parse(res.body);\n\n            let total = data.results.total;\n\n            return {\n                comments: data.results.list.map(e => {\n                    return {\n                        userName: e.user_name,\n                        avatar: e.user_avatar,\n                        content: e.comment,\n                        time: e.create_at,\n                        replyCount: null,\n                        id: null,\n                    }\n                }),\n                maxPage: (total - (total % 20)) / 20 + 1,\n            }\n        },\n        sendChapterComment: async (comicId, epId, content, replyTo) => {\n            let token = this.loadData(\"token\");\n            if (!token) {\n                throw \"\u672a\u767b\u5f55\"\n            }\n            let res = await Network.post(\n                `${this.apiUrl}/api/v3/member/roast`,\n                {\n                    ...this.webApiHeaders,\n                    \"Content-Type\": \"application/x-www-form-urlencoded;charset=utf-8\",\n                },\n                `chapter_id=${epId}&roast=${encodeURIComponent(content)}`,\n            );\n\n            if (res.status === 401) {\n                throw `Login expired`;\n            }\n\n            if (res.status !== 200) {\n                if (res.status === 210) {\n                    throw `210:\u8bc4\u8bba\u8fc7\u4e8e\u9891\u7e41\u6216\u8bc4\u8bba\u5185\u5bb9\u8fc7\u77ed\u8fc7\u957f`;\n                }\n                throw `Invalid status code: ${res.status}`;\n            } else {\n                return \"ok\"\n            }\n        },\n        onClickTag: (namespace, tag) => {\n            if (namespace === \"\u6807\u7b7e\") {\n                return {\n                    action: 'search',\n                    keyword: `tag:${tag}`,\n                    param: null,\n                }\n            }\n            if (namespace === \"\u4f5c\u8005\") {\n                return {\n                    action: 'search',\n                    keyword: `author:${tag}`,\n                    param: null,\n                }\n            }\n            throw \"\u672a\u652f\u6301\u6b64\u7c7bTag\u68c0\u7d22\"\n        }\n    }\n\n    settings = {\n        favorites_ordering: {\n            title: \"\u6536\u85cf\u6392\u5e8f\u65b9\u5f0f\",\n            type: \"select\",\n            options: [\n                {\n                    value: '-datetime_updated',\n                    text: '\u66f4\u65b0\u65f6\u95f4'\n                },\n                {\n                    value: '-datetime_modifier',\n                    text: '\u6536\u85cf\u65f6\u95f4'\n                },\n                {\n                    value: '-datetime_browse',\n                    text: '\u9605\u8bfb\u65f6\u95f4'\n                }\n            ],\n            default: '-datetime_updated',\n        },\n        region: {\n            title: \"CDN\u7ebf\u8def\",\n            type: \"select\",\n            options: [\n                {\n                    value: \"1\",\n                    text: '\u5927\u9646\u7ebf\u8def'\n                },\n                {\n                    value: \"0\",\n                    text: '\u6d77\u5916\u7ebf\u8def'\n                },\n            ],\n            default: CopyManga.defaultCopyRegion,\n        },\n        image_quality: {\n            title: \"\u56fe\u7247\u8d28\u91cf\",\n            type: \"select\",\n            options: [\n                {\n                    value: '800',\n                    text: '\u4f4e (800)'\n                },\n                {\n                    value: '1200',\n                    text: '\u4e2d (1200)'\n                },\n                {\n                    value: '1500',\n                    text: '\u9ad8 (1500)'\n                }\n            ],\n            default: CopyManga.defaultImageQuality,\n        },\n        search_api: {\n            title: \"\u641c\u7d22\u65b9\u5f0f\",\n            type: \"select\",\n            options: [\n                {\n                    value: 'baseAPI',\n                    text: '\u57fa\u7840API'\n                },\n                {\n                    value: 'webAPI',\n                    text: '\u7f51\u9875\u7aefAPI'\n                }\n            ],\n            default: 'baseAPI'\n        },\n        base_url: {\n            title: \"\u8282\u70b9\u9009\u62e9\",\n            type: \"select\",\n            options: [\n                {\n                    value: 'www.2026copy.com',\n                    text: '2026copy.com'\n                },\n                {\n                    value: 'www.2025copy.com',\n                    text: '2025copy.com'\n                },\n                {\n                    value: 'www.copy20.com',\n                    text: 'copy20.com'\n                },\n                {\n                    value: 'www.mangacopy.com',\n                    text: 'mangacopy.com'\n                },\n                {\n                    value: 'www.copy-manga.com',\n                    text: 'copy-manga.com'\n                },\n            ],\n            default: CopyManga.defaultApiUrl,\n        },\n\n        \n        // version: {\n        //     title: \"\u62f7\u8d1d\u7248\u672c\uff08\u91cd\u542fAPP\u751f\u6548\uff09\",\n        //     type: \"input\",\n        //     default: CopyManga.defaultCopyVersion,\n        // },\n        // platform: {\n        //     title: \"\u5e73\u53f0\u4ee3\u53f7\uff08\u91cd\u542fAPP\u751f\u6548\uff09\",\n        //     type: \"input\",\n        //     validator: '^\\\\d+(?:\\\\.\\\\d+)*$',\n        //     default: CopyManga.defaultCopyPlatform,\n        // },\n    }\n\n    /**\n     * Check if the current app version is after the target version\n     * @param target {string} target version\n     * @returns {boolean} true if the current app version is after the target version\n     */\n    isAppVersionAfter(target) {\n        let current = APP.version\n        let targetArr = target.split('.')\n        let currentArr = current.split('.')\n        for (let i = 0; i < 3; i++) {\n            if (parseInt(currentArr[i]) < parseInt(targetArr[i])) {\n                return false\n            }\n        }\n        return true\n    }\n\n    async refreshSearchApi() {\n        let url = \"https://www.copy20.com/search\"\n        let res = await fetch(url)\n        let searchApi = \"\"\n        if (res.status === 200) {\n            let text = await res.text()\n            let match = text.match(/const countApi = \"([^\"]+)\"/)\n            if (match && match[1]) {\n                CopyManga.searchApi = match[1]\n            }\n        }\n    }\n\n    async refreshAppApi() {\n        const url = \"https://api.copy-manga.com/api/v3/system/network2?platform=2\"\n        const res = await fetch(url, { headers: this.webApiHeaders });\n        if (res.status === 200) {\n            let data = await res.json();\n            this.settings.base_url = data.results.api[0][0];\n        }\n    }\n\n    async runSpeedTest() {\n        const hosts = [\n            'www.2027copy.com',\n            'www.2026copy.com',\n            'www.2025copy.com',\n            'www.copy20.com',\n            'www.mangacopy.com',\n            'www.copymanga.tv',\n            'www.copy-manga.com',\n            'www.copy2000.site',\n            'www.copy2000.online',\n        ];\n        \n        const results = {};\n        \n        for (const host of hosts) {\n            const startTime = Date.now();\n            try {\n                const res = await Network.get(`https://${host}/`, {\n                    \"User-Agent\": \"Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36\"\n                });\n                const endTime = Date.now();\n                const latency = endTime - startTime;\n                \n                if (res.status === 200) {\n                    const body = res.body.toLowerCase();\n                    const isValid = body.includes('content-box') || body.includes('swiperlist') || body.includes('comicrank');\n                    results[host] = {\n                        success: isValid,\n                        latency: latency,\n                        status: res.status\n                    };\n                } else {\n                    results[host] = {\n                        success: false,\n                        latency: latency,\n                        status: res.status\n                    };\n                }\n            } catch (e) {\n                results[host] = {\n                    success: false,\n                    latency: 999999,\n                    status: 0\n                };\n            }\n        }\n        \n        this.saveData(\"_speed_test_results\", JSON.stringify(results));\n        this.saveData(\"_speed_test_time\", new Date().toISOString());\n        \n        let bestHost = null;\n        let bestLatency = 999999;\n        for (const [host, result] of Object.entries(results)) {\n            if (result.success && result.latency < bestLatency) {\n                bestLatency = result.latency;\n                bestHost = host;\n            }\n        }\n        \n        if (bestHost) {\n            this.saveData(\"_best_host\", bestHost);\n        }\n    }\n\n    getSpeedTestResults() {\n        const results = this.loadData(\"_speed_test_results\");\n        const time = this.loadData(\"_speed_test_time\");\n        if (results) {\n            return {\n                results: JSON.parse(results),\n                time: time\n            };\n        }\n        return null;\n    }\n}\n";
__SOURCE_CODE["Komiic"] = "class Komiic extends ComicSource {\n\n    // \u6b64\u6f2b\u753b\u6e90\u7684\u540d\u79f0\n    name = \"Komiic\"\n\n    // \u552f\u4e00\u6807\u8bc6\u7b26\n    key = \"Komiic\"\n\n    version = \"1.0.6\"\n\n    minAppVersion = \"1.0.0\"\n\n    // \u66f4\u65b0\u94fe\u63a5\n    url = \"https://raw.githubusercontent.com/ccbkv/pica_configs/master/komiic.js\"\n\n    get headers() {\n        let token = this.loadData('token')\n        let headers = {\n            'Referer': 'https://komiic.com/',\n            'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36',\n            'Content-Type': 'application/json'\n        }\n        if (token) {\n            headers['Authorization'] = `Bearer ${token}`\n        }\n        return headers\n    }\n\n    async queryJson(query) {\n        let res = await Network.post(\n            'https://komiic.com/api/query',\n            this.headers,\n            query\n        )\n\n        if (res.status !== 200) {\n            throw `Invalid Status Code ${res.status}`\n        }\n\n        let json = JSON.parse(res.body)\n\n        if (json.errors != undefined) {\n            const errorInfo = json.errors[0].message.toString();\n            if ((errorInfo.indexOf('token is expired') >= 0) || (errorInfo.indexOf('no token') >= 0)) {\n                const accountData = this.loadData(\"account\");\n                await this.account.login(accountData[0], accountData[1]);\n                return await this.queryJson(query);\n            }\n            throw json.errors[0].message\n        }\n\n        return json\n    }\n\n    async queryComics(query) {\n        let operationName = query[\"operationName\"]\n        let json = await this.queryJson(query)\n\n        function parseComic(comic) {\n            let author = ''\n            if (comic.authors.length > 0) {\n                author = comic.authors[0].name\n            }\n            let tags = []\n            comic.categories.forEach((c) => {\n                tags.push(c.name)\n            })\n\n            function getTimeDifference(date) {\n                const now = new Date();\n                const timeDifference = now - date;\n\n                const millisecondsPerHour = 1000 * 60 * 60;\n                const millisecondsPerDay = millisecondsPerHour * 24;\n\n                if (timeDifference < millisecondsPerHour) {\n                    return '\u525b\u525b\u66f4\u65b0';\n                } else if (timeDifference < millisecondsPerDay) {\n                    const hours = Math.floor(timeDifference / millisecondsPerHour);\n                    return `${hours}\u5c0f\u6642\u524d\u66f4\u65b0`;\n                } else {\n                    const days = Math.floor(timeDifference / millisecondsPerDay);\n                    return `${days}\u5929\u524d\u66f4\u65b0`;\n                }\n            }\n\n            let updateTime = new Date(comic.dateUpdated)\n            let description = getTimeDifference(updateTime)\n            let formatedTime = `${updateTime.getFullYear()}-${updateTime.getMonth() + 1}-${updateTime.getDate()}`\n\n            return {\n                id: comic.id,\n                title: comic.title,\n                subTitle: author,\n                cover: comic.imageUrl,\n                tags: tags,\n                description: description,\n                updateTime: formatedTime\n            }\n        }\n\n        return {\n            comics: json.data[operationName].map(parseComic),\n            // \u6ca1\u627e\u5230\u6700\u5927\u9875\u6570\u7684\u63a5\u53e3\n            maxPage: null\n        }\n    }\n\n    /// \u8d26\u53f7\n    /// \u8bbe\u7f6e\u4e3anull\u7981\u7528\u8d26\u53f7\u529f\u80fd\n    account = {\n        /// \u767b\u5f55\n        /// \u8fd4\u56de\u4efb\u610f\u503c\u8868\u793a\u767b\u5f55\u6210\u529f\n        login: async (account, pwd) => {\n            let res = await Network.post(\n                'https://komiic.com/api/login',\n                this.headers,\n                {\n                    email: account,\n                    password: pwd\n                }\n            )\n\n            if (res.status === 200) {\n                // \u8d26\u53f7\u5bc6\u7801\u4e0d\u5bf9\u65f6\u7ad9\u70b9\u56de 200 + \u7a7a body\uff0c\u539f\u6765\u76f4\u63a5 JSON.parse \u4f1a\u629b\n                // \"Unexpected end of JSON input\"\uff0c\u7528\u6237\u6839\u672c\u770b\u4e0d\u61c2\u3002\n                let token = null\n                try { token = JSON.parse(res.body).token } catch (e) { token = null }\n                if (!token) { throw '\u8d26\u53f7\u6216\u5bc6\u7801\u4e0d\u5bf9' }\n                this.saveData('token', token)\n                return 'ok'\n            }\n\n            throw (res.status === 400 || res.status === 401)\n                ? '\u8d26\u53f7\u6216\u5bc6\u7801\u4e0d\u5bf9'\n                : ('\u767b\u5f55\u5931\u8d25\uff08HTTP ' + res.status + '\uff09')\n        },\n\n        // \u9000\u51fa\u767b\u5f55\u65f6\u5c06\u4f1a\u8c03\u7528\u6b64\u51fd\u6570\n        logout: () => {\n            this.deleteData('token')\n        },\n\n        registerWebsite: \"https://komiic.com/register\"\n    }\n\n    /// \u63a2\u7d22\u9875\u9762\n    /// \u4e00\u4e2a\u6f2b\u753b\u6e90\u53ef\u4ee5\u6709\u591a\u4e2a\u63a2\u7d22\u9875\u9762\n    explore = [\n        {\n            /// \u6807\u9898\n            /// \u6807\u9898\u540c\u65f6\u7528\u4f5c\u6807\u8bc6\u7b26, \u4e0d\u80fd\u91cd\u590d\n            title: \"Komiic\",\n\n            /// singlePageWithMultiPart \u6216\u8005 multiPageComicList\n            type: \"multiPageComicList\",\n\n            load: async (page) => {\n                return await this.queryComics({ \"operationName\": \"recentUpdate\", \"variables\": { \"pagination\": { \"limit\": 20, \"offset\": (page - 1) * 20, \"orderBy\": \"DATE_UPDATED\", \"status\": \"\", \"asc\": true } }, \"query\": \"query recentUpdate($pagination: Pagination!) {\\n  recentUpdate(pagination: $pagination) {\\n    id\\n    title\\n    status\\n    year\\n    imageUrl\\n    authors {\\n      id\\n      name\\n      __typename\\n    }\\n    categories {\\n      id\\n      name\\n      __typename\\n    }\\n    dateUpdated\\n    monthViews\\n    views\\n    favoriteCount\\n    lastBookUpdate\\n    lastChapterUpdate\\n    __typename\\n  }\\n}\" })\n            }\n        }\n    ]\n\n    category = {\n        title: \"Komiic\",\n        enableRankingPage: true,\n        parts: [\n            {\n                name: \"\u4e3b\u9898\",\n\n                type: \"fixed\",\n\n                categories: ['\u5168\u90e8', '\u611b\u60c5', '\u795e\u9b3c', '\u6821\u5712', '\u641e\u7b11', '\u751f\u6d3b', '\u61f8\u7591', '\u5192\u96aa', '\u8077\u5834', '\u9b54\u5e7b', '\u5f8c\u5bae', '\u9b54\u6cd5', '\u683c\u9b25', '\u5b85\u7537', '\u52f5\u5fd7', '\u803d\u7f8e', '\u79d1\u5e7b', '\u767e\u5408', '\u6cbb\u7652', '\u840c\u7cfb', '\u71b1\u8840', '\u7af6\u6280', '\u63a8\u7406', '\u96dc\u8a8c', '\u5075\u63a2', '\u507d\u5a18', '\u7f8e\u98df', '\u6050\u6016', '\u56db\u683c', '\u793e\u6703', '\u6b77\u53f2', '\u6230\u722d', '\u821e\u8e48', '\u6b66\u4fe0', '\u6a5f\u6230', '\u97f3\u6a02', '\u9ad4\u80b2', '\u9ed1\u9053'],\n\n                itemType: \"category\",\n\n                // \u82e5\u63d0\u4f9b, \u6570\u91cf\u9700\u8981\u548c`categories`\u4e00\u81f4, `categoryComics.load`\u65b9\u6cd5\u5c06\u4f1a\u6536\u5230\u6b64\u53c2\u6570\n                categoryParams: ['0', '1', '3', '4', '5', '6', '7', '8', '10', '11', '2', '12', '13', '14', '15', '16', '17', '18', '19', '20', '21', '22', '23', '24', '25', '26', '27', '9', '28', '31', '32', '33', '34', '35', '36', '37', '40', '42']\n            }\n        ]\n    }\n\n    /// \u5206\u7c7b\u6f2b\u753b\u9875\u9762, \u5373\u70b9\u51fb\u5206\u7c7b\u6807\u7b7e\u540e\u8fdb\u5165\u7684\u9875\u9762\n    categoryComics = {\n        load: async (category, param, options, page) => {\n          let variables = {\n              pagination: {\n                  limit: 30,\n                  offset: (page - 1) * 30,\n                  orderBy: options[0],\n                  asc: false,\n                  status: options[1]\n              }\n          };\n          \n          if (param !== '0') {\n              variables.categoryId = [param];\n          } else {\n              variables.categoryId = [];\n          }\n\n          return await this.queryComics({ \n              \"operationName\": \"comicByCategories\",\n              \"variables\": variables,\n              \"query\": `query comicByCategories($categoryId: [ID!]!, $pagination: Pagination!) {\n                  comicByCategories(categoryId: $categoryId, pagination: $pagination) {\n                      id\n                      title\n                      status\n                      year\n                      imageUrl\n                      authors { id name __typename }\n                      categories { id name __typename }\n                      dateUpdated\n                      monthViews\n                      views\n                      favoriteCount\n                      lastBookUpdate\n                      lastChapterUpdate\n                      __typename\n                  }\n              }`\n          })\n        },\n        // \u63d0\u4f9b\u9009\u9879\n        optionList: [\n            {\n                options: [\n                    \"DATE_UPDATED-\u66f4\u65b0\",\n                    \"VIEWS-\u89c0\u770b\u6578\",\n                    \"FAVORITE_COUNT-\u559c\u611b\u6578\",\n                ],\n                notShowWhen: null,\n                showWhen: null\n            },\n            {\n                options: [\n                    \"-\u5168\u90e8\",\n                    \"ONGOING-\u9023\u8f09\u4e2d\",\n                    \"END-\u5b8c\u7d50\",\n                ],\n                notShowWhen: null,\n                showWhen: null\n            },\n        ],\n        ranking: {\n            options: [\n                \"MONTH_VIEWS-\u6708\",\n                \"VIEWS-\u7d9c\u5408\"\n            ],\n            load: async (option, page) => {\n                return this.queryComics({ \"operationName\": \"hotComics\", \"variables\": { \"pagination\": { \"limit\": 20, \"offset\": (page - 1) * 20, \"orderBy\": option, \"status\": \"\", \"asc\": true } }, \"query\": \"query hotComics($pagination: Pagination!) {\\n  hotComics(pagination: $pagination) {\\n    id\\n    title\\n    status\\n    year\\n    imageUrl\\n    authors {\\n      id\\n      name\\n      __typename\\n    }\\n    categories {\\n      id\\n      name\\n      __typename\\n    }\\n    dateUpdated\\n    monthViews\\n    views\\n    favoriteCount\\n    lastBookUpdate\\n    lastChapterUpdate\\n    __typename\\n  }\\n}\" })\n            }\n        }\n    }\n\n    /// \u641c\u7d22\n    search = {\n        load: async (keyword, options, page) => {\n            let json = await this.queryJson({ \"operationName\": \"searchComicAndAuthorQuery\", \"variables\": { \"keyword\": keyword }, \"query\": \"query searchComicAndAuthorQuery($keyword: String!) {\\n  searchComicsAndAuthors(keyword: $keyword) {\\n    comics {\\n      id\\n      title\\n      status\\n      year\\n      imageUrl\\n      authors {\\n        id\\n        name\\n        __typename\\n      }\\n      categories {\\n        id\\n        name\\n        __typename\\n      }\\n      dateUpdated\\n      monthViews\\n      views\\n      favoriteCount\\n      lastBookUpdate\\n      lastChapterUpdate\\n      __typename\\n    }\\n    authors {\\n      id\\n      name\\n      chName\\n      enName\\n      wikiLink\\n      comicCount\\n      views\\n      __typename\\n    }\\n    __typename\\n  }\\n}\" })\n\n            function parseComic(comic) {\n                let author = ''\n                if (comic.authors.length > 0) {\n                    author = comic.authors[0].name\n                }\n                let tags = []\n                comic.categories.forEach((c) => {\n                    tags.push(c.name)\n                })\n\n                function getTimeDifference(date) {\n                    const now = new Date();\n                    const timeDifference = now - date;\n\n                    const millisecondsPerHour = 1000 * 60 * 60;\n                    const millisecondsPerDay = millisecondsPerHour * 24;\n\n                    if (timeDifference < millisecondsPerHour) {\n                        return '\u525b\u525b\u66f4\u65b0';\n                    } else if (timeDifference < millisecondsPerDay) {\n                        const hours = Math.floor(timeDifference / millisecondsPerHour);\n                        return `${hours}\u5c0f\u6642\u524d\u66f4\u65b0`;\n                    } else {\n                        const days = Math.floor(timeDifference / millisecondsPerDay);\n                        return `${days}\u5929\u524d\u66f4\u65b0`;\n                    }\n                }\n\n                let updateTime = new Date(comic.dateUpdated)\n                let description = getTimeDifference(updateTime)\n\n                return {\n                    id: comic.id,\n                    title: comic.title,\n                    subTitle: author,\n                    cover: comic.imageUrl,\n                    tags: tags,\n                    description: description\n                }\n            }\n\n            return {\n                comics: json.data.searchComicsAndAuthors.comics.map(parseComic),\n                // \u6ca1\u627e\u5230\u6700\u5927\u9875\u6570\u7684\u63a5\u53e3\n                maxPage: 1\n            }\n        },\n\n        optionList: []\n    }\n\n    /// \u6536\u85cf\n    favorites = {\n        /// \u662f\u5426\u4e3a\u591a\u6536\u85cf\u5939\n        multiFolder: true,\n        /// \u6dfb\u52a0\u6216\u8005\u5220\u9664\u6536\u85cf\n        addOrDelFavorite: async (comicId, folderId, isAdding) => {\n            let query = {}\n            if (isAdding) {\n                query = { \"operationName\": \"addComicToFolder\", \"variables\": { \"comicId\": comicId, \"folderId\": folderId }, \"query\": \"mutation addComicToFolder($comicId: ID!, $folderId: ID!) {\\n  addComicToFolder(comicId: $comicId, folderId: $folderId)\\n}\" }\n            } else {\n                query = { \"operationName\": \"removeComicToFolder\", \"variables\": { \"comicId\": comicId, \"folderId\": folderId }, \"query\": \"mutation removeComicToFolder($comicId: ID!, $folderId: ID!) {\\n  removeComicToFolder(comicId: $comicId, folderId: $folderId)\\n}\" }\n            }\n            await this.queryJson(query)\n            return \"ok\"\n        },\n        // \u52a0\u8f7d\u6536\u85cf\u5939, \u4ec5\u5f53multiFolder\u4e3atrue\u65f6\u6709\u6548\n        // \u5f53comicId\u4e0d\u4e3anull\u65f6, \u9700\u8981\u540c\u65f6\u8fd4\u56de\u5305\u542b\u8be5\u6f2b\u753b\u7684\u6536\u85cf\u5939\n        loadFolders: async (comicId) => {\n            let json = await this.queryJson({ \"operationName\": \"myFolder\", \"variables\": {}, \"query\": \"query myFolder {\\n  folders {\\n    id\\n    key\\n    name\\n    views\\n    comicCount\\n    dateCreated\\n    dateUpdated\\n    __typename\\n  }\\n}\" })\n            let folders = {}\n            json.data.folders.forEach((f) => {\n                folders[f.id] = f.name\n            })\n            let favorited = null\n            if (comicId) {\n                let json2 = await this.queryJson({ \"operationName\": \"comicInAccountFolders\", \"variables\": { \"comicId\": comicId }, \"query\": \"query comicInAccountFolders($comicId: ID!) {\\n  comicInAccountFolders(comicId: $comicId)\\n}\" })\n                favorited = json2.data.comicInAccountFolders\n            }\n            return {\n                folders: folders,\n                favorited: favorited\n            }\n        },\n        /// \u521b\u5efa\u6536\u85cf\u5939\n        addFolder: async (name) => {\n            let json = await this.queryJson({ \"operationName\": \"createFolder\", \"variables\": { \"name\": name }, \"query\": \"mutation createFolder($name: String!) {\\n  createFolder(name: $name) {\\n    id\\n    key\\n    name\\n    account {\\n      id\\n      nickname\\n      __typename\\n    }\\n    comicCount\\n    views\\n    dateCreated\\n    dateUpdated\\n    __typename\\n  }\\n}\" })\n            return \"ok\"\n        },\n        deleteFolder: async (id) => {\n            let json = await this.queryJson({ \"operationName\": \"removeFolder\", \"variables\": { \"folderId\": id }, \"query\": \"mutation removeFolder($folderId: ID!) {\\n  removeFolder(folderId: $folderId)\\n}\" })\n            return \"ok\"\n        },\n        /// \u52a0\u8f7d\u6f2b\u753b\n        loadComics: async (page, folder) => {\n            let json = await this.queryJson({ \"operationName\": \"folderComicIds\", \"variables\": { \"folderId\": folder, \"pagination\": { \"limit\": 30, \"offset\": (page - 1) * 30, \"orderBy\": \"DATE_UPDATED\", \"status\": \"\", \"asc\": true } }, \"query\": \"query folderComicIds($folderId: ID!, $pagination: Pagination!) {\\n  folderComicIds(folderId: $folderId, pagination: $pagination) {\\n    folderId\\n    key\\n    comicIds\\n    __typename\\n  }\\n}\" })\n            let ids = json.data.folderComicIds.comicIds\n            if (ids.length == 0) {\n                return {\n                    comics: [],\n                    maxPage: 1\n                }\n            }\n            return this.queryComics({ \"operationName\": \"comicByIds\", \"variables\": { \"comicIds\": ids }, \"query\": \"query comicByIds($comicIds: [ID]!) {\\n  comicByIds(comicIds: $comicIds) {\\n    id\\n    title\\n    status\\n    year\\n    imageUrl\\n    authors {\\n      id\\n      name\\n      __typename\\n    }\\n    categories {\\n      id\\n      name\\n      __typename\\n    }\\n    dateUpdated\\n    monthViews\\n    views\\n    favoriteCount\\n    lastBookUpdate\\n    lastChapterUpdate\\n    __typename\\n  }\\n}\" })\n        }\n    }\n\n    /// \u5355\u4e2a\u6f2b\u753b\u76f8\u5173\n    comic = {\n        // \u52a0\u8f7d\u6f2b\u753b\u4fe1\u606f\n        loadInfo: async (id) => {\n            let json1 = await this.queryJson({ \"operationName\": \"recommendComicById\", \"variables\": { \"comicId\": id }, \"query\": \"query recommendComicById($comicId: ID!) {\\n  recommendComicById(comicId: $comicId)\\n}\" })\n            let recommend = json1.data.recommendComicById\n            recommend.push(id)\n\n            let getFavoriteStatus = async () => {\n                let token = this.loadData('token')\n                if (!token) {\n                    return false\n                }\n                let json = await this.queryJson({ \"operationName\": \"comicInAccountFolders\", \"variables\": { \"comicId\": id }, \"query\": \"query comicInAccountFolders($comicId: ID!) {\\n  comicInAccountFolders(comicId: $comicId)\\n}\" })\n                let folders = json.data.comicInAccountFolders\n                return folders.length !== 0\n            }\n\n            let getChapter = async () => {\n                let json = await this.queryJson({ \"operationName\": \"chapterByComicId\", \"variables\": { \"comicId\": id }, \"query\": \"query chapterByComicId($comicId: ID!) {\\n  chaptersByComicId(comicId: $comicId) {\\n    id\\n    serial\\n    type\\n    dateCreated\\n    dateUpdated\\n    size\\n    __typename\\n  }\\n}\" })\n                let all = json.data.chaptersByComicId\n                let books = [], chapters = []\n                all.forEach((c) => {\n                    if(c.type === 'book') {\n                        books.push(c)\n                    } else {\n                        chapters.push(c)\n                    }\n                })\n                let res = new Map()\n                books.forEach((c) => {\n                    let name = '\u5377' + c.serial\n                    res.set(c.id, name)\n                })\n                chapters.forEach((c) => {\n                    let name = c.serial\n                    res.set(c.id, name)\n                })\n                return res\n            }\n\n            let results = await Promise.all([\n                this.queryComics({ \"operationName\": \"comicByIds\", \"variables\": { \"comicIds\": recommend }, \"query\": \"query comicByIds($comicIds: [ID]!) {\\n  comicByIds(comicIds: $comicIds) {\\n    id\\n    title\\n    status\\n    year\\n    imageUrl\\n    authors {\\n      id\\n      name\\n      __typename\\n    }\\n    categories {\\n      id\\n      name\\n      __typename\\n    }\\n    dateUpdated\\n    monthViews\\n    views\\n    favoriteCount\\n    lastBookUpdate\\n    lastChapterUpdate\\n    __typename\\n  }\\n}\" }),\n                getChapter.call()\n            ])\n\n            let info = results[0].comics.pop()\n\n            return {\n                // string \u6807\u9898\n                title: info.title,\n                // string \u5c01\u9762url\n                cover: info.cover,\n                // map<string, string[]> \u6807\u7b7e\n                tags: {\n                    \"\u4f5c\u8005\": [info.subTitle],\n                    \"\u6807\u7b7e\": info.tags\n                },\n                // map<string, string>?, key\u4e3a\u7ae0\u8282id, value\u4e3a\u7ae0\u8282\u540d\u79f0\n                chapters: results[1],\n                recommend: results[0].comics,\n                updateTime: info.updateTime,\n            }\n        },\n        // \u83b7\u53d6\u7ae0\u8282\u56fe\u7247\n        loadEp: async (comicId, epId) => {\n            let json = await this.queryJson({ \"operationName\": \"imagesByChapterId\", \"variables\": { \"chapterId\": epId }, \"query\": \"query imagesByChapterId($chapterId: ID!) {\\n  imagesByChapterId(chapterId: $chapterId) {\\n    id\\n    kid\\n    height\\n    width\\n    __typename\\n  }\\n}\" })\n            return {\n                images: json.data.imagesByChapterId.map((i) => {\n                    return `https://komiic.com/api/image/${i.kid}`\n                })\n            }\n        },\n        // \u53ef\u9009, \u8c03\u6574\u56fe\u7247\u52a0\u8f7d\u7684\u884c\u4e3a\n        onImageLoad: (url, comicId, epId) => {\n            return {\n                headers: {\n                    'user-agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36',\n                    'referer': `https://komiic.com/comic/${comicId}/chapter/${epId}/images/all`\n                }\n            }\n        },\n        // \u52a0\u8f7d\u8bc4\u8bba\n        loadComments: async (comicId, subId, page, replyTo) => {\n            let operationName = replyTo ? \"messageChan\" : \"getMessagesByComicId\"\n            let promise = replyTo\n                ? this.queryJson({ \"operationName\": \"messageChan\", \"variables\": { \"messageId\": replyTo }, \"query\": \"query messageChan($messageId: ID!) {\\n  messageChan(messageId: $messageId) {\\n    id\\n    comicId\\n    account {\\n      id\\n      nickname\\n      profileText\\n      profileTextColor\\n      profileBackgroundColor\\n      profileImageUrl\\n      __typename\\n    }\\n    message\\n    replyTo {\\n      id\\n      __typename\\n    }\\n    upCount\\n    downCount\\n    dateUpdated\\n    dateCreated\\n    __typename\\n  }\\n}\" })\n                : this.queryJson({ \"operationName\": \"getMessagesByComicId\", \"variables\": { \"comicId\": comicId, \"pagination\": { \"limit\": 100, \"offset\": (page - 1) * 100, \"orderBy\": \"DATE_UPDATED\", \"asc\": true } }, \"query\": \"query getMessagesByComicId($comicId: ID!, $pagination: Pagination!) {\\n  getMessagesByComicId(comicId: $comicId, pagination: $pagination) {\\n    id\\n    comicId\\n    account {\\n      id\\n      nickname\\n      profileText\\n      profileTextColor\\n      profileBackgroundColor\\n      profileImageUrl\\n      __typename\\n    }\\n    message\\n    replyTo {\\n      id\\n      message\\n      account {\\n        id\\n        nickname\\n        profileText\\n        profileTextColor\\n        profileBackgroundColor\\n        profileImageUrl\\n        __typename\\n      }\\n      __typename\\n    }\\n    upCount\\n    downCount\\n    dateUpdated\\n    dateCreated\\n    __typename\\n  }\\n}\" })\n            let json = await promise\n            return {\n                comments: json.data[operationName].map(e => {\n                    return {\n                        // string\n                        userName: e.account.nickname,\n                        // string\n                        avatar: e.account.profileImageUrl,\n                        // string\n                        content: e.message,\n                        // string?\n                        time: e.dateUpdated,\n                        // number?\n                        // TODO: \u6ca1\u6709\u6570\u91cf\u4fe1\u606f, \u4f46\u662f\u8bbe\u4e3anull\u4f1a\u7981\u7528\u56de\u590d\u529f\u80fd\n                        replyCount: 0,\n                        // string\n                        id: e.id,\n                    }\n                }),\n                maxPage: null,\n            }\n        },\n        // \u53d1\u9001\u8bc4\u8bba, \u8fd4\u56de\u4efb\u610f\u503c\u8868\u793a\u6210\u529f\n        sendComment: async (comicId, subId, content, replyTo) => {\n            if (!replyTo) {\n                replyTo = \"0\"\n            }\n            let json = await this.queryJson({ \"operationName\": \"addMessageToComic\", \"variables\": { \"comicId\": comicId, \"message\": content, \"replyToId\": replyTo }, \"query\": \"mutation addMessageToComic($comicId: ID!, $replyToId: ID!, $message: String!) {\\n  addMessageToComic(message: $message, comicId: $comicId, replyToId: $replyToId) {\\n    id\\n    message\\n    comicId\\n    account {\\n      id\\n      nickname\\n      __typename\\n    }\\n    replyTo {\\n      id\\n      message\\n      account {\\n        id\\n        nickname\\n        profileText\\n        profileTextColor\\n        profileBackgroundColor\\n        profileImageUrl\\n        __typename\\n      }\\n      __typename\\n    }\\n    dateCreated\\n    dateUpdated\\n    __typename\\n  }\\n}\" })\n            return \"ok\"\n        }\n    }\n}\n";
__SOURCE_CODE["noyacg"] = "class NoyAcgSource extends ComicSource {\n    name = \"NoyAcg\"\n    key = \"noyacg\"\n    version = \"1.0.0\"\n    minAppVersion = \"3.1.0\"\n    url = \"https://raw.githubusercontent.com/ccbkv/pica_configs/master/noyacg.js\"\n\n    init() {\n        Date.prototype.format = function () {\n            let padLeft = function (str, length) {\n                str = str.toString();\n                if (str.length >= length) {\n                    return str;\n                }\n                for (let i = 0; i < length - str.length; i++) {\n                    str = \"0\" + str;\n                }\n                return str;\n            }\n            return `${padLeft(this.getFullYear(), 4)}-${padLeft(this.getMonth() + 1, 2)}-${padLeft(this.getDate(), 2)}`;\n        }\n    }\n\n    parseComic = function (comic) {\n        let formatNum = function (num) {\n            if (num <= 999) {\n                return num.toString();\n            }\n            return (num / 1000).toFixed(2).toString() + \"k\";\n        }\n        let title = `${comic.Bookname}`;\n        if (comic.Len) {\n            title = `[${comic.Len}P]${comic.Bookname}`;\n        }\n        return {\n            id: `${comic.Bid}#${comic.Len}`,\n            title: title,\n            subTitle: comic.Author,\n            cover: `https://img.noy.asia/${comic.Bid}/1.webp`,\n            tags: (comic.Ptag || \"\").split(\" \"),\n            description: `${new Date(comic.Time * 1000).format()}`\n        }\n    };\n\n    post = async function (url, data) {\n        let headers = {\n            \"Content-Type\": \"application/x-www-form-urlencoded\"\n        }\n        let res = await Network.post(url, headers, data);\n        if (res.status != 200) {\n            throw `Invalid status code: ${res.status}`\n        }\n        let resp = JSON.parse(res.body);\n        if (\"status\" in resp && resp.status != 'ok') {\n            // \u670d\u52a1\u7aef\u5931\u8d25\u65f6\u53ea\u56de {\"status\":\"error\"}\uff0c\u6ca1\u6709\u6587\u5b57\uff0c\u76f4\u63a5\u7ed9\u7528\u6237\u770b\u5f97\u61c2\u7684\n            throw resp.status === 'error' ? '\u8d26\u53f7\u6216\u5bc6\u7801\u4e0d\u5bf9\uff08\u8fd8\u6ca1\u53f7\u7684\u8bdd\u5148\u53bb\u7ad9\u70b9\u6ce8\u518c\uff09' : resp.status;\n        }\n        return resp;\n    }\n\n    searchComic = async function ({ info, type, sort, page }) {\n        let data = await this.post(\"https://noy1.top/api/search_v2\", `info=${info}&type=${type}&sort=${sort}&page=${page}`);\n        let maxPage = Math.ceil(data.len / 20);\n        return {\n            comics: data.Info.map(this.parseComic),\n            maxPage: maxPage\n        }\n    }\n\n    getThumbnails = function (comicId, length) {\n        let images = [];\n        for (let i = 1; i <= parseInt(length); i++) {\n            images.push(`https://img.noy.asia/${comicId}/${i}.webp`);\n        }\n        return images;\n    };\n\n    /// \u8d26\u53f7\n    /// \u8bbe\u7f6e\u4e3anull\u7981\u7528\u8d26\u53f7\u529f\u80fd\n    account = {\n        /// \u767b\u5f55\n        /// \u8fd4\u56de\u4efb\u610f\u503c\u8868\u793a\u767b\u5f55\u6210\u529f\n        login: async (user, pass) => {\n            let data = await this.post('https://noy1.top/api/login', `user=${user}&pass=${pass}`)\n            const { status } = data;\n            if (status !== 'ok') {\n                // \u670d\u52a1\u7aef\u53ea\u56de {\"status\":\"error\"}\uff0c\u76f4\u63a5\u629b\u51fa\u53bb\u7528\u6237\u770b\u4e0d\u61c2\uff0c\u6362\u6210\u4e2d\u6587\n                throw '\u8d26\u53f7\u6216\u5bc6\u7801\u4e0d\u5bf9';\n            }\n            return 'ok';\n        },\n        // \u9000\u51fa\u767b\u5f55\u65f6\u5c06\u4f1a\u8c03\u7528\u6b64\u51fd\u6570\n        logout: () => {\n            Network.deleteCookies('https://noy1.top');\n        },\n        registerWebsite: \"https://noy1.top/#/reg\"\n    }\n    explore = [\n        {\n            title: \"NoyAcg\",\n            type: \"singlePageWithMultiPart\",\n            load: async () => {\n                let data = await this.post(\"https://noy1.top/api/home\")\n                let comics = {}\n                let size = 18;\n                comics[\"\u9605\u8bfb\u699c\"] = data[\"readDay\"].slice(0, size).map(this.parseComic)\n                comics[\"\u6536\u85cf\u699c\"] = data[\"favDay\"].slice(0, size).map(this.parseComic)\n                // \u9ad8\u8d28\u91cf\u699c\u90fd\u662f0P\n                comics[\"\u9ad8\u8d28\u91cf\u699c\"] = data[\"proportion\"].slice(0, size).map(this.parseComic)\n                comics[\"\u6536\u85cf\u63a8\u8350\"] = data[\"fs\"].slice(0, size).map(this.parseComic)\n                return comics;\n            }\n        }\n    ]\n\n    category = {\n        title: \"NoyAcg\",\n        parts: [\n            {\n                name: \"\u6807\u7b7e\",\n                type: \"fixed\",\n                categories: [\"\u7eaf\u7231\", \"\u5168\u5f69\", \"NTR\", \"\u4f2a\u5a18\", \"\u6276\u5979\", \"\u59d0\u59b9\", \"\u540e\u5bab\", \"\u91cd\u53e3\", \"\u675f\u7f1a\", \"\u6027\u8f6c\u6362\", \"\u65e0\u4fee\u6b63\", \"\u5355\u884c\u672c\", \"\u5f3a\u5978\", \"\u841d\u8389\", \"\u4e1c\u65b9\", \"\u539f\u795e\", \"\u8230C\", \"\u539f\u521b\"],\n                itemType: \"category\",\n                categoryParams: [\"\u7d14\u611b\", \"\u5168\u5f69\", \"NTR\", \"\u507d\u5a18\", \"\u6276\u5979\", \"\u59d0\u59b9\", \"\u591a\u4eba\u904b\u52d5\", \"\u91cd \u53e3\", \"\u675f\u7e1b\", \"\u6027\u8f49\u63db\", \"\u7121\u4fee\u6b63\", \"\u55ae\u884c\u672c\", \"\u5f37\u59e6\", \"\u863f\u8389\", \"\u6771\u65b9Project\", \"\u539f\u795e\", \"\u8266\u968aCollection\", \"\u539f\u5275\"]\n            }\n        ],\n        enableRankingPage: false,\n    }\n\n    categoryComics = {\n        load: async (category, param, options, page) => {\n            return await this.searchComic({\n                info: param,\n                type: 'tag',\n                sort: options[0],\n                page: page\n            })\n        },\n        optionList: [\n            {\n                options: [\n                    \"bid-\u65f6\u95f4\u6392\u5e8f\",\n                    \"views-\u9605\u8bfb\u91cf\u6392\u5e8f\",\n                    \"favorites-\u6536\u85cf\u6392\u5e8f\"\n                ],\n                notShowWhen: null,\n                showWhen: null\n            }\n        ],\n        ranking: {\n            options: [\n                \"day-\u65e5\",\n                \"week-\u5468\"\n            ],\n            load: async (option, page) => {\n\n            }\n        }\n    }\n\n    /// \u641c\u7d22\n    search = {\n        load: async (keyword, options, page) => {\n            return await this.searchComic({\n                info: keyword,\n                type: options[0],\n                sort: options[1],\n                page: page\n            });\n        },\n        // \u63d0\u4f9b\u9009\u9879\n        optionList: [\n            {\n                options: [\n                    \"de-\u7efc\u5408\",\n                    \"tag-\u6807\u7b7e\",\n                    \"author-\u4f5c\u8005\"\n                ],\n                label: \"\u641c\u7d22\u8bbe\u7f6e\"\n            },\n            {\n                // \u4f7f\u7528-\u5206\u5272, \u5de6\u4fa7\u7528\u4e8e\u6570\u636e\u52a0\u8f7d, \u53f3\u4fa7\u663e\u793a\u7ed9\u7528\u6237\n                options: [\n                    \"bid-\u65f6\u95f4\u6392\u5e8f\",\n                    \"views-\u9605\u8bfb\u91cf\u6392\u5e8f\",\n                    \"favorites-\u6536\u85cf\u6392\u5e8f\"\n                ],\n                // \u6807\u7b7e\n                label: \"\u6392\u5e8f\"\n            }\n        ]\n    }\n\n    /// \u6536\u85cf\n    favorites = {\n        /// \u662f\u5426\u4e3a\u591a\u6536\u85cf\u5939\n        multiFolder: false,\n        /// \u6dfb\u52a0\u6216\u8005\u5220\u9664\u6536\u85cf\n        addOrDelFavorite: async (id, folderId, isAdding) => {\n            let [comicId] = id.split(\"#\");\n            let res = await Network.post(\"https://noy1.top/api/adfavorites\", {\n                \"Content-Type\": \"application/x-www-form-urlencoded\"\n            }, `bid=${comicId}`)\n            let data = res.body;\n            if (data === 'ok') {\n                return 'ok';\n            }\n            throw data;\n        },\n        // \u52a0\u8f7d\u6536\u85cf\u5939, \u4ec5\u5f53multiFolder\u4e3atrue\u65f6\u6709\u6548\n        // \u5f53comicId\u4e0d\u4e3anull\u65f6, \u9700\u8981\u540c\u65f6\u8fd4\u56de\u5305\u542b\u8be5\u6f2b\u753b\u7684\u6536\u85cf\u5939\n        loadFolders: async (comicId) => {\n            /*\n            ```\n            let data = JSON.parse((await Network.get('...')).body)\n\n            let folders = {}\n\n            data.folders.forEach((f) => {\n                folders[f.id] = f.name\n            })\n\n            return {\n                // map<string, string> key\u4e3a\u6536\u85cf\u5939id, value\u4e3a\u6536\u85cf\u5939\u540d\u79f0, id\u7528\u4e8e\u6536\u85cf\u5939\u76f8\u5173\u5185\u5bb9\u7684\u52a0\u8f7d\n                folders: folders,\n                // string[]?, \u5305\u542bcomicId\u7684\u6536\u85cf\u5939, \u82e5comicId\u4e3a\u7a7a, \u5219\u6b64\u5b57\u6bb5\u4e3a\u7a7a\n                favorited: data.favorited\n            }\n            ```\n            */\n        },\n        /// \u52a0\u8f7d\u6f2b\u753b\n        loadComics: async (page, folder) => {\n            let data = await this.post(\"https://noy1.top/api/favoriteslist_v2\", `page=${page}`);\n            return {\n                comics: data.info.map(this.parseComic),\n                maxPage: Math.ceil(data.len / 20)\n            }\n        }\n    }\n\n    /// \u5355\u4e2a\u6f2b\u753b\u76f8\u5173\n    comic = {\n        // \u52a0\u8f7d\u6f2b\u753b\u4fe1\u606f\n        loadInfo: async (id) => {\n            let [comicId, length] = id.split(\"#\");\n            let data = await this.post('https://noy1.top/api/getbookinfo', `bid=${comicId}`);\n            let comic = {\n                // string \u6807\u9898\n                title: data.Bookname,\n                // string \u5c01\u9762url\n                cover: `https://img.noy.asia/${comicId}/m1.webp`,\n                // string\n                // description: `${data.Len}P`,\n                // Map<string, string[]> | object \u6807\u7b7e\n                tags: {\n                    \"\u4f5c\u8005\": [data.Author],\n                    \"\u89d2\u8272\": (data.Pname || \"\").split(\" \"),\n                    \"\u6807\u7b7e\": (data.Ptag || \"\").split(\" \"),\n                    \"\u5176\u4ed6\": (data.Otag || \"\").split(\" \"),\n                    \"\u9875\u6570\": [`${data.Len}P`],\n                    \"\u65e5\u671f\": [new Date(data.Time * 1000).format()]\n                },\n                // Map<string, string>? | object, key\u4e3a\u7ae0\u8282id, value\u4e3a\u7ae0\u8282\u540d\u79f0\n                // \u6ce8\u610f: \u4e3a\u4e86\u4fdd\u8bc1\u7ae0\u8282\u987a\u5e8f, \u6700\u597d\u4f7f\u7528Map, \u4f7f\u7528object\u4e0d\u80fd\u4fdd\u8bc1\u987a\u5e8f\n                chapters: {},\n                // bool \u6ce8\u610f, \u5982\u679c\u662f\u591a\u6536\u85cf\u5f0f\u7684\u7f51\u7edc\u6536\u85cf, \u5c06\u6b64\u9879\u8bbe\u7f6e\u4e3anull, \u4ece\u800c\u53ef\u4ee5\u5728\u6f2b\u753b\u8be6\u60c5\u9875\u9762, \u5bf9\u6bcf\u4e2a\u5355\u72ec\u7684\u6536\u85cf\u5939\u6267\u884c\u6536\u85cf\u6216\u8005\u53d6\u6d88\u6536\u85cf\u64cd\u4f5c\n                isFavorite: data.F,\n                // thumbnails: this.getThumbnails(comicId, length)\n            };\n            return comic;\n        },\n        // \u83b7\u53d6\u7ae0\u8282\u56fe\u7247\n        loadEp: async (id, epId) => {\n            let [comicId, len] = id.split(\"#\");\n            return {\n                images: this.getThumbnails(comicId, len)\n            }\n        },\n        // \u53ef\u9009, \u8c03\u6574\u56fe\u7247\u52a0\u8f7d\u7684\u884c\u4e3a; \u5982\u4e0d\u9700\u8981, \u5220\u9664\u6b64\u5b57\u6bb5\n        onImageLoad: (url, comicId, epId) => {\n            return {\n                headers: {\n                    'Referer': 'https://noy1.top/',\n                },\n            }\n        },\n        // [v3.1.4\u6dfb\u52a0] \u53ef\u9009, \u8c03\u6574\u7f29\u7565\u56fe(\u5c01\u9762, \u9884\u89c8, \u5934\u50cf\u7b49)\u52a0\u8f7d\u7684\u884c\u4e3a; \u5982\u4e0d\u9700\u8981, \u5220\u9664\u6b64\u5b57\u6bb5\n        onThumbnailLoad: (url) => {\n            return {\n                headers: {\n                    'Referer': 'https://noy1.top/',\n                },\n            }\n        },\n        // \u52a0\u8f7d\u8bc4\u8bba\n        loadComments: async (id, subId, page, replyTo) => {\n            let [comicId] = id.split(\"#\");\n            let data = await this.post(\"https://noy1.top/api/getComment\", `bid=${comicId}&page=${page}`);\n            let over = data.over;\n            let getReplyCount = function (cid) {\n                return data.info.filter(c => c.reply == cid).length\n            }\n            let comments = data.info;\n            let maxPage = over ? page : page + 1\n            if (replyTo) {\n                comments = comments.filter(c => c.reply == replyTo);\n            }\n            return {\n                comments: comments.map(c => {\n                    return {\n                        userName: c.username,\n                        avatar: `https://bucket.noy.asia/${c.avatarUrl}`,\n                        content: c.content,\n                        time: new Date(c.time * 1000).format(),\n                        replyCount: getReplyCount(c.cid),\n                        id: c.cid\n                    }\n                }),\n                maxPage: maxPage\n            }\n        },\n        // \u53d1\u9001\u8bc4\u8bba, \u8fd4\u56de\u4efb\u610f\u503c\u8868\u793a\u6210\u529f\n        sendComment: async (id, subId, content, replyTo) => {\n            if (!replyTo) {\n                replyTo = -1;\n            }\n            let [comicId] = id.split(\"#\");\n            let data = await this.post(\"https://noy1.top/api/sendComment\", `bid=${comicId}&platform=web&content=${content}&reply=${replyTo}`);\n            if (data.status === 'ok') {\n                return \"ok\";\n            }\n            throw data.status;\n        }\n    }\n}\n";
__SOURCE_CODE["picacg"] = "/** @type {import('./_venera_.js')} */\n\nconst picacgCategories = [\n    \"\u5927\u5bb6\u90fd\u5728\u770b\", \"\u5927\u6fd5\u63a8\u85a6\", \"\u90a3\u5e74\u4eca\u5929\", \"\u5b98\u65b9\u90fd\u5728\u770b\", \"\u55f6\u5494\u6f22\u5316\",\n    \"\u5168\u5f69\", \"\u9577\u7bc7\", \"\u540c\u4eba\", \"\u77ed\u7bc7\", \"\u5713\u795e\u9818\u57df\",\n    \"\u78a7\u85cd\u5e7b\u60f3\", \"CG\u96dc\u5716\", \"\u82f1\u8a9e ENG\", \"\u751f\u8089\", \"\u7d14\u611b\",\n    \"\u767e\u5408\u82b1\u5712\", \"\u803d\u7f8e\u82b1\u5712\", \"\u507d\u5a18\u54f2\u5b78\", \"\u5f8c\u5bae\u9583\u5149\", \"\u6276\u4ed6\u6a02\u5712\",\n    \"\u55ae\u884c\u672c\", \"\u59d0\u59d0\u7cfb\", \"\u59b9\u59b9\u7cfb\", \"SM\", \"\u6027\u8f49\u63db\",\n    \"\u8db3\u306e\u604b\", \"\u4eba\u59bb\", \"NTR\", \"\u5f37\u66b4\", \"\u975e\u4eba\u985e\",\n    \"\u8266\u968a\u6536\u85cf\", \"Love Live\", \"SAO \u5200\u528d\u795e\u57df\", \"Fate\", \"\u6771\u65b9\",\n    \"WEBTOON\", \"\u7981\u66f8\u76ee\u9304\", \"\u6b50\u7f8e\", \"Cosplay\", \"\u91cd\u53e3\u5730\u5e36\",\n]\n\nclass PicacgComicSource extends ComicSource {\n    // \u6f2b\u753b\u6e90\u540d\u79f0\n    name = \"picacg\"\n\n    // \u552f\u4e00\u6807\u8bc6\n    key = \"picacg\"\n\n    version = \"2.0.0\"\n\n    minAppVersion = \"4.9.3\"\n\n    // \u66f4\u65b0\u94fe\u63a5\n    url = \"https://raw.githubusercontent.com/ccbkv/pica_configs/refs/heads/master/picacg.js\"\n\n    apiUrl = \"https://picaapi.picacomic.com\"\n\n    apiKey = \"C69BAF41DA5ABD1FFEDC6D2FEA56B\"\n\n    // \u54d4\u5494 API \u7b7e\u540d\u5bc6\u94a5 (\u4e0e headers.dart \u4e2d\u4e00\u81f4)\n    signatureSecret = '~d}$Q7$eIni=V)9\\\\RK/P.RM4;9[7|@/CA}b~OW!3?EV`:<>M7pddUBL5n|0/*Cn'\n\n    // -------------------- \u901a\u7528\u5de5\u5177 --------------------\n\n    getSettingValue(key, fallback) {\n        try {\n            let value = this.loadSetting(key)\n            if (value != null) {\n                return value\n            }\n        } catch (e) { }\n        return fallback\n    }\n\n    getToken() {\n        return this.loadData('token') ?? ''\n    }\n\n    /**\n     * \u6784\u9020\u8bf7\u6c42\u5934 (\u4e0e headers.dart \u4e2d getHeaders \u4e00\u81f4)\n     * @param method {string} - \u5927\u5199\u7684 HTTP \u65b9\u6cd5\n     * @param path {string} - \u4e0d\u542b\u57df\u540d\u524d\u7f00\u7684\u8def\u5f84 (\u542b\u67e5\u8be2\u53c2\u6570)\n     */\n    createHeaders(method, path) {\n        let nonce = createUuid().replace(/-/g, '')\n        let time = Math.floor(Date.now() / 1000).toString()\n        // signature = HMAC-SHA256(secret, (path + time + nonce + method + apiKey).toLowerCase())\n        let raw = (path + time + nonce + method + this.apiKey).toLowerCase()\n        let signature = Convert.hmacString(\n            Convert.encodeUtf8(this.signatureSecret),\n            Convert.encodeUtf8(raw),\n            'sha256'\n        )\n        return {\n            'api-key': this.apiKey,\n            'accept': 'application/vnd.picacomic.com.v1+json',\n            'app-channel': this.loadData('appChannel') ??\n                this.getSettingValue('appChannel', '3'),\n            'authorization': this.getToken(),\n            'time': time,\n            'nonce': nonce,\n            'app-version': '2.2.1.3.3.4',\n            'app-uuid': 'defaultUuid',\n            'image-quality': this.loadData('imageQuality') ??\n                this.getSettingValue('imageQuality', 'original'),\n            'app-platform': 'android',\n            'app-build-version': '45',\n            'Content-Type': 'application/json; charset=UTF-8',\n            'user-agent': 'okhttp/3.8.1',\n            'version': 'v1.4.1',\n            'Host': 'picaapi.picacomic.com',\n            'signature': signature,\n        }\n    }\n\n    /**\n     * \u4f7f\u7528\u4fdd\u5b58\u7684\u8d26\u53f7\u91cd\u65b0\u767b\u5f55\n     */\n    async reLogin() {\n        let account = this.loadData('account')\n        if (account == null || account.length < 2) {\n            throw 'Login expired'\n        }\n        await this.account.login(account[0], account[1])\n    }\n\n    /**\n     * \u53d1\u9001 API \u8bf7\u6c42, \u81ea\u52a8\u5904\u7406 400 / 401\n     * @param method {string} - HTTP \u65b9\u6cd5\n     * @param path {string} - \u4e0d\u542b\u57df\u540d\u524d\u7f00\u7684\u8def\u5f84 (\u542b\u67e5\u8be2\u53c2\u6570)\n     * @param data {Object?} - POST \u8bf7\u6c42\u4f53\n     * @param allowRelogin {boolean} - 401 \u65f6\u662f\u5426\u81ea\u52a8\u91cd\u65b0\u767b\u5f55\u5e76\u91cd\u8bd5\n     */\n    async request(method, path, data, allowRelogin = true) {\n        method = method.toUpperCase()\n        if (this.getToken() === '' && !path.startsWith('auth')) {\n            throw '\u672a\u767b\u5f55'\n        }\n        let url = this.apiUrl + '/' + path\n        let body = data == null ? null : JSON.stringify(data)\n        let res = await Network.sendRequest(\n            method, url, this.createHeaders(method, path), body)\n        if (res.status === 200) {\n            return JSON.parse(res.body)\n        } else if (res.status === 400) {\n            let message = 'Invalid request'\n            try {\n                message = JSON.parse(res.body).message ?? message\n            } catch (e) { }\n            throw message\n        } else if (res.status === 401) {\n            if (!allowRelogin) {\n                throw 'Login expired'\n            }\n            await this.reLogin()\n            return await this.request(method, path, data, false)\n        } else {\n            throw 'Invalid status code: ' + res.status\n        }\n    }\n\n    /**\n     * \u62fc\u63a5\u56fe\u7247\u94fe\u63a5\n     */\n    buildImageUrl(image) {\n        if (image == null) {\n            return ''\n        }\n        return (image.fileServer ?? '') + '/static/' + (image.path ?? '')\n    }\n\n    /**\n     * \u8fc7\u6ee4\u65e0\u6548\u7684\u4f5c\u8005\u540d (\u4e0e\u5185\u7f6e _PicComicTile.subTitle \u903b\u8f91\u4e00\u81f4)\n     */\n    parseSubtitle(author) {\n        author = (author ?? '').trim()\n        if (author === '' || author === 'N/A' || author === 'Unknown' || author === '\u672a\u77e5') {\n            return ''\n        }\n        return author\n    }\n\n    /**\n     * \u5c06 API \u8fd4\u56de\u7684\u6f2b\u753b\u7b80\u4ecb\u6570\u636e\u8f6c\u6362\u4e3a Comic\n     */\n    parseComic(doc) {\n        let tags = []\n        tags.push(...(doc.tags ?? []))\n        tags.push(...(doc.categories ?? []))\n        let subtitle = this.parseSubtitle(doc.author)\n        return new Comic({\n            id: doc._id ?? '',\n            title: doc.title ?? 'Unknown',\n            subtitle: subtitle,\n            subTitle: subtitle,\n            cover: this.buildImageUrl(doc.thumb),\n            tags: tags,\n            description: (doc.totalLikes ?? doc.likesCount ?? 0) + ' likes',\n            maxPage: doc.pagesCount,\n        })\n    }\n\n    /**\n     * \u89e3\u6790\u5206\u9875\u6f2b\u753b\u5217\u8868\u54cd\u5e94 (docs / pages)\n     */\n    parseComicsResponse(comicsData) {\n        return {\n            comics: (comicsData.docs ?? []).map((e) => this.parseComic(e)),\n            maxPage: comicsData.pages ?? 1,\n        }\n    }\n\n    /**\n     * \u83b7\u53d6\u5168\u90e8\u7ae0\u8282, \u8fd4\u56de { \u7ae0\u8282\u5e8f\u53f7: \u7ae0\u8282\u6807\u9898 }, \u6309\u5e8f\u53f7\u5347\u5e8f\n     */\n    async loadChapters(id) {\n        let eps = []\n        let page = 1\n        while (true) {\n            let res = await this.request('GET', 'comics/' + id + '/eps?page=' + page)\n            let docs = res.data.eps.docs ?? []\n            eps.push(...docs)\n            if (res.data.eps.pages === page || docs.length === 0) {\n                break\n            }\n            page++\n        }\n        eps.sort((a, b) => (a.order ?? 0) - (b.order ?? 0))\n        let chapters = {}\n        for (let i = 0; i < eps.length; i++) {\n            chapters[eps[i].order.toString()] = eps[i].title\n        }\n        return chapters\n    }\n\n    /**\n     * \u5c06 API \u7528\u6237\u4fe1\u606f\u8f6c\u6362\u4e3a Profile \u517c\u5bb9\u683c\u5f0f\u4fdd\u5b58\n     * (\u4e0e methods.dart getProfile / Profile.fromJson \u5b57\u6bb5\u4e00\u81f4,\n     *  \u4f9b\u8d26\u53f7\u4fe1\u606f\u663e\u793a\u53ca\u7559\u8a00\u677f\u7b49\u54d4\u5494\u4e13\u5c5e\u9875\u9762\u4f7f\u7528)\n     */\n    parseProfile(user) {\n        let avatarUrl = 'DEFAULT AVATAR URL'\n        if (user.avatar != null) {\n            avatarUrl = (user.avatar.fileServer ?? '') +\n                '/static/' + (user.avatar.path ?? '')\n        }\n        return {\n            id: user._id,\n            title: user.title,\n            email: user.email,\n            name: user.name,\n            level: user.level,\n            exp: user.exp,\n            avatarUrl: avatarUrl,\n            frameUrl: user.character,\n            isPunched: user.isPunched,\n            slogan: user.slogan,\n        }\n    }\n\n    /**\n     * \u5c06 API \u4e0a\u4f20\u8005\u4fe1\u606f\u8f6c\u6362\u4e3a UploaderInfo \u683c\u5f0f\n     * (\u4e0e comic_source.dart UploaderInfo \u5b57\u6bb5\u4e00\u81f4, \u7528\u4e8e\u8be6\u60c5\u9875\u4e0a\u4f20\u8005\u5361\u7247)\n     */\n    parseUploader(creator, updateTime) {\n        if (creator == null || creator._id == null) {\n            return null\n        }\n        let avatarUrl = 'DEFAULT AVATAR URL'\n        if (creator.avatar != null) {\n            avatarUrl = (creator.avatar.fileServer ?? '') +\n                '/static/' + (creator.avatar.path ?? '')\n        }\n        return {\n            id: creator._id,\n            name: creator.name ?? '',\n            avatarUrl: avatarUrl,\n            frameUrl: creator.character,\n            slogan: creator.slogan,\n            level: creator.level ?? 0,\n            updateTime: updateTime ?? '',\n        }\n    }\n\n    // -------------------- \u8d26\u53f7 --------------------\n\n    account = {\n        /**\n         * \u767b\u5f55, \u6210\u529f\u540e\u4fdd\u5b58 token \u4e0e\u7528\u6237\u4fe1\u606f\n         */\n        login: async (account, pwd) => {\n            let res = await this.request('POST', 'auth/sign-in', {\n                'email': account,\n                'password': pwd,\n            }, false)\n            if (res.data == null || res.data.token == null) {\n                throw res.message ?? '\u767b\u5f55\u5931\u8d25'\n            }\n            this.saveData('token', res.data.token)\n            // \u83b7\u53d6\u5e76\u4fdd\u5b58\u7528\u6237\u4fe1\u606f (\u4e0e\u5185\u7f6e login \u4e00\u81f4)\n            let profileRes = await this.request('GET', 'users/profile')\n            if (profileRes.data == null || profileRes.data.user == null) {\n                this.deleteData('token')\n                throw profileRes.message ?? '\u83b7\u53d6\u7528\u6237\u4fe1\u606f\u5931\u8d25'\n            }\n            this.saveData('user', this.parseProfile(profileRes.data.user))\n            return 'ok'\n        },\n\n        /**\n         * \u767b\u51fa, \u6e05\u9664 token \u4e0e\u7528\u6237\u4fe1\u606f\n         */\n        logout: () => {\n            this.deleteData('token')\n            this.deleteData('user')\n        },\n\n        registerWebsite: null,\n\n        // \u8d26\u53f7\u4fe1\u606f\u9879 (\u4e0e\u5185\u7f6e infoItems \u4e00\u81f4)\n        infoItems: [\n            {\n                title: '\u8d26\u53f7',\n                data: () => this.loadData('user')?.email ?? '',\n            },\n            {\n                title: '\u7528\u6237\u540d',\n                data: () => this.loadData('user')?.name ?? '',\n            },\n            {\n                title: '\u7b49\u7ea7',\n                data: () => {\n                    let user = this.loadData('user')\n                    if (user == null) {\n                        return ''\n                    }\n                    return 'Lv' + (user.level ?? 0) + ' ' +\n                        (user.title ?? '') + ' Exp' + (user.exp ?? 0)\n                },\n            },\n            {\n                title: '\u7b80\u4ecb',\n                data: () => this.loadData('user')?.slogan ?? '',\n            },\n            {\n                title: '\u7559\u8a00\u677f',\n                action: 'picacg_leave_messages',\n            },\n            {\n                title: '\u6211\u7684\u8bc4\u8bba',\n                action: 'picacg_user_comments',\n            },\n        ],\n    }\n\n    // -------------------- \u63a2\u7d22\u9875 --------------------\n\n    explore = [\n        {\n            title: \"picacg\",\n            type: \"multiPartPage\",\n            load: async () => {\n                let [randomRes, latestRes] = await Promise.all([\n                    this.request('GET', 'comics/random'),\n                    this.request('GET', 'comics?page=1&s=dd'),\n                ])\n                return [\n                    {\n                        title: '\u968f\u673a',\n                        comics: (randomRes.data.comics ?? [])\n                            .map((e) => this.parseComic(e)),\n                        viewMore: {\n                            page: 'category',\n                            attributes: { category: 'random', param: null },\n                        },\n                    },\n                    {\n                        title: '\u6700\u65b0',\n                        comics: (latestRes.data.comics.docs ?? [])\n                            .map((e) => this.parseComic(e)),\n                        viewMore: {\n                            page: 'category',\n                            attributes: { category: 'latest', param: null },\n                        },\n                    },\n                ]\n            },\n        },\n    ]\n\n    // -------------------- \u5206\u7c7b\u9875 --------------------\n\n    category = {\n        title: \"Picacg\",\n        key: \"picacg\",\n        parts: [\n            {\n                name: '\u5206\u7c7b',\n                type: 'fixed',\n                categories: picacgCategories.map((label) => ({\n                    label: label,\n                    target: {\n                        page: 'category',\n                        attributes: {\n                            category: label,\n                            param: null,\n                        },\n                    },\n                })),\n            },\n        ],\n        enableRankingPage: true,\n    }\n\n    // -------------------- \u5206\u7c7b\u6f2b\u753b --------------------\n\n    categoryComics = {\n        /**\n         * \u52a0\u8f7d\u5206\u7c7b\u6f2b\u753b\n         * @param category {string} - \u5206\u7c7b\u540d / \"random\" / \"latest\" / \u4f5c\u8005\u540d\n         * @param param {string?} - \"a\": \u4f5c\u8005, \"ca\": \u521b\u4f5c\u8005, \u5176\u5b83: \u5206\u7c7b\n         * @param options {string[]} - \u6392\u5e8f\u9009\u9879\n         * @param page {number} - \u9875\u7801\n         */\n        load: async (category, param, options, page) => {\n            if (category === 'random') {\n                let res = await this.request('GET', 'comics/random')\n                return {\n                    comics: (res.data.comics ?? []).map((e) => this.parseComic(e)),\n                    maxPage: 1,\n                }\n            }\n            if (category === 'latest') {\n                let res = await this.request('GET', 'comics?page=' + page + '&s=dd')\n                return this.parseComicsResponse(res.data.comics)\n            }\n            let sort = options[0] ?? 'dd'\n            let type = param === 'a' ? 'a' : (param === 'ca' ? 'ca' : 'c')\n            if (type === 'c' && category.includes(',')) {\n                // \u591a\u5206\u7c7b\u641c\u7d22, \u4f7f\u7528\u9ad8\u7ea7\u641c\u7d22 API\n                let categories = category.split(',').filter((s) => s !== '')\n                let res = await this.request(\n                    'POST', 'comics/advanced-search?page=' + page, {\n                    categories: categories,\n                    keyword: '',\n                    sort: sort,\n                })\n                return this.parseComicsResponse(res.data.comics)\n            }\n            let res = await this.request('GET',\n                'comics?page=' + page + '&' + type + '=' +\n                encodeURIComponent(category) + '&s=' + sort)\n            return this.parseComicsResponse(res.data.comics)\n        },\n\n        optionList: [\n            {\n                options: [\n                    'dd-\u65b0\u5230\u65e7',\n                    'da-\u65e7\u5230\u65b0',\n                    'ld-\u6700\u591a\u559c\u6b22',\n                    'vd-\u6700\u591a\u6307\u540d',\n                ],\n                notShowWhen: ['random', 'latest'],\n            },\n        ],\n\n        ranking: {\n            options: [\n                'H24-24\u5c0f\u65f6',\n                'D7-7\u5929',\n                'D30-30\u5929',\n                'creator-\u9a91\u58eb\u699c',\n            ],\n            /**\n             * \u52a0\u8f7d\u6392\u884c\u699c\n             * @param option {string} - H24 / D7 / D30 / creator\n             * @param page {number} - \u9875\u7801\n             */\n            load: async (option, page) => {\n                if (option === 'creator') {\n                    // \u9a91\u58eb\u699c (\u7528\u6237\u699c)\n                    let res = await this.request('GET', 'comics/knight-leaderboard')\n                    let comics = (res.data.users ?? []).map((user) => {\n                        let subtitle = (user.title ?? '') + ' Lv' + (user.level ?? 0)\n                        return new Comic({\n                            id: 'creator:' + (user._id ?? ''),\n                            title: user.name ?? 'Unknown',\n                            subtitle: subtitle,\n                            subTitle: subtitle,\n                            cover: this.buildImageUrl(user.avatar),\n                            tags: [...(user.characters ?? [])],\n                            description: (user.exp ?? 0) + ' likes',\n                        })\n                    })\n                    return { comics: comics, maxPage: 1 }\n                }\n                let res = await this.request(\n                    'GET', 'comics/leaderboard?tt=' + option + '&ct=VC')\n                return {\n                    comics: (res.data.comics ?? []).map((e) => this.parseComic(e)),\n                    maxPage: 1,\n                }\n            },\n        },\n    }\n\n    // -------------------- \u641c\u7d22 --------------------\n\n    search = {\n        /**\n         * \u641c\u7d22\u6f2b\u753b\n         * @param keyword {string}\n         * @param options {string[]} - \u6392\u5e8f\u9009\u9879\n         * @param page {number}\n         */\n        load: async (keyword, options, page) => {\n            let res = await this.request(\n                'POST', 'comics/advanced-search?page=' + page, {\n                keyword: keyword,\n                sort: options[0] ?? 'dd',\n                ...(options[1] ? { categories: JSON.parse(options[1]) } : {}),\n            })\n            return this.parseComicsResponse(res.data.comics)\n        },\n\n        optionList: [\n            {\n                label: '\u6392\u5e8f',\n                options: [\n                    'dd-\u65b0\u5230\u65e7',\n                    'da-\u65e7\u5230\u65b0',\n                    'ld-\u6700\u591a\u559c\u6b22',\n                    'vd-\u6700\u591a\u6307\u540d',\n                ],\n            },\n        ],\n\n        enableTagsSuggestions: false,\n    }\n\n    // -------------------- \u6f2b\u753b\u8be6\u60c5 --------------------\n\n    comic = {\n        /**\n         * \u52a0\u8f7d\u6f2b\u753b\u8be6\u60c5\n         * @param id {string}\n         */\n        loadInfo: async (id) => {\n            if (id.startsWith('creator:')) {\n                throw '\u6682\u4e0d\u652f\u6301\u67e5\u770b\u7528\u6237\u4e3b\u9875'\n            }\n            let res = await this.request('GET', 'comics/' + id)\n            let comic = res.data.comic\n            // \u7ae0\u8282\u5217\u8868\n            let chapters = await this.loadChapters(id)\n            // \u76f8\u5173\u63a8\u8350 (\u5931\u8d25\u4e0d\u5f71\u54cd\u8be6\u60c5\u52a0\u8f7d)\n            let recommend = []\n            try {\n                let recRes = await this.request(\n                    'GET', 'comics/' + id + '/recommendation')\n                recommend = (recRes.data.comics ?? [])\n                    .map((e) => this.parseComic(e))\n            } catch (e) { }\n            let subtitle = this.parseSubtitle(comic.author)\n            let tags = {}\n            tags['\u4f5c\u8005'] = [comic.author ?? '']\n            tags['\u6c49\u5316'] = [comic.chineseTeam ?? '']\n            tags['\u5206\u7c7b'] = comic.categories ?? []\n            tags['\u6807\u7b7e'] = comic.tags ?? []\n            return {\n                title: comic.title ?? 'Unknown',\n                subtitle: subtitle,\n                subTitle: subtitle,\n                cover: this.buildImageUrl(comic.thumb),\n                description: comic.description ?? '\u65e0',\n                tags: tags,\n                chapters: chapters,\n                thumbnails: null,\n                recommend: recommend,\n                isFavorite: comic.isFavourite ?? false,\n                likesCount: comic.likesCount ?? 0,\n                isLiked: comic.isLiked ?? false,\n                commentsCount: comic.commentsCount ?? 0,\n                uploader: this.parseUploader(comic._creator, comic.updated_at),\n                updateTime: comic.updated_at,\n                maxPage: comic.pagesCount,\n            }\n        },\n\n        /**\n         * \u52a0\u8f7d\u7ae0\u8282\u56fe\u7247\n         * @param comicId {string}\n         * @param epId {string} - \u7ae0\u8282\u5e8f\u53f7 (1-based)\n         */\n        loadEp: async (comicId, epId) => {\n            let images = []\n            let page = 1\n            while (true) {\n                let res = await this.request('GET',\n                    'comics/' + comicId + '/order/' + epId + '/pages?page=' + page)\n                let docs = res.data.pages.docs ?? []\n                for (let i = 0; i < docs.length; i++) {\n                    images.push(this.buildImageUrl(docs[i].media))\n                }\n                if (res.data.pages.pages === page || docs.length === 0) {\n                    break\n                }\n                page++\n            }\n            return { images: images }\n        },\n\n        /**\n         * \u52a0\u8f7d\u8bc4\u8bba\n         * @param comicId {string}\n         * @param subId {string?}\n         * @param page {number}\n         * @param replyTo {string?} - \u975e\u7a7a\u65f6\u52a0\u8f7d\u8be5\u8bc4\u8bba\u7684\u56de\u590d\n         */\n        loadComments: async (comicId, subId, page, replyTo) => {\n            let res\n            if (replyTo != null) {\n                res = await this.request('GET',\n                    'comments/' + replyTo + '/childrens?page=' + page)\n            } else {\n                res = await this.request('GET',\n                    'comics/' + comicId + '/comments?page=' + page)\n            }\n            let docs = res.data.comments.docs ?? []\n            let comments = docs.map((doc) => {\n                let user = doc._user\n                return new Comment({\n                    userName: user?.name ?? 'Unknown',\n                    avatar: this.buildImageUrl(user?.avatar),\n                    content: doc.content ?? '',\n                    time: doc.created_at ?? '',\n                    replyCount: doc.commentsCount ?? 0,\n                    id: doc._id ?? '',\n                    isLiked: doc.isLiked ?? false,\n                    score: doc.likesCount ?? 0,\n                })\n            })\n            return {\n                comments: comments,\n                maxPage: res.data.comments.pages ?? 1,\n            }\n        },\n\n        /**\n         * \u53d1\u9001\u8bc4\u8bba / \u56de\u590d\n         * @param comicId {string}\n         * @param subId {string?}\n         * @param content {string}\n         * @param replyTo {string?} - \u975e\u7a7a\u65f6\u56de\u590d\u8be5\u8bc4\u8bba\n         */\n        sendComment: async (comicId, subId, content, replyTo) => {\n            if (replyTo != null) {\n                await this.request('POST', 'comments/' + replyTo, {\n                    content: content,\n                })\n            } else {\n                await this.request('POST', 'comics/' + comicId + '/comments', {\n                    content: content,\n                })\n            }\n            return 'ok'\n        },\n\n        /**\n         * \u70b9\u8d5e / \u53d6\u6d88\u70b9\u8d5e\u8bc4\u8bba\n         * @param comicId {string}\n         * @param subId {string?}\n         * @param commentId {string}\n         * @param isLike {boolean}\n         */\n        likeComment: async (comicId, subId, commentId, isLike) => {\n            await this.request('POST', 'comments/' + commentId + '/like', {})\n        },\n\n        /**\n         * \u70b9\u8d5e / \u53d6\u6d88\u70b9\u8d5e\u6f2b\u753b\n         * @param id {string}\n         * @param isLike {boolean} - true for like, false for unlike\n         */\n        likeComic: async (id, isLike) => {\n            await this.request('POST', 'comics/' + id + '/like', {})\n        },\n\n        /**\n         * \u70b9\u51fb\u6807\u7b7e\u8df3\u8f6c (\u5206\u7c7b -> \u5206\u7c7b\u9875, \u4f5c\u8005 -> \u4f5c\u8005\u641c\u7d22, \u5176\u5b83 -> \u641c\u7d22)\n         * @param namespace {string}\n         * @param tag {string}\n         */\n        onClickTag: (namespace, tag) => {\n            if (namespace === '\u5206\u7c7b') {\n                return {\n                    page: 'category',\n                    attributes: { category: tag, param: null },\n                }\n            } else if (namespace === '\u4f5c\u8005') {\n                return {\n                    page: 'category',\n                    attributes: { category: tag, param: 'a' },\n                }\n            }\n            return {\n                page: 'search',\n                attributes: { keyword: tag },\n            }\n        },\n    }\n\n    // -------------------- \u6536\u85cf --------------------\n\n    favorites = {\n        multiFolder: false,\n\n        /**\n         * \u6536\u85cf / \u53d6\u6d88\u6536\u85cf\n         * @param comicId {string}\n         * @param folderId {string}\n         * @param isAdding {boolean}\n         * @param favoriteId {string?}\n         */\n        addOrDelFavorite: async (comicId, folderId, isAdding, favoriteId) => {\n            await this.request('POST', 'comics/' + comicId + '/favourite', {})\n            return 'ok'\n        },\n\n        /**\n         * \u52a0\u8f7d\u6536\u85cf\u6f2b\u753b\n         * @param page {number}\n         * @param folder {string?}\n         */\n        loadComics: async (page, folder) => {\n            let sort = this.getSettingValue('favoriteSort', 'dd')\n            let res = await this.request('GET',\n                'users/favourite?s=' + sort + '&page=' + page)\n            return this.parseComicsResponse(res.data.comics)\n        },\n    }\n\n    // -------------------- \u8bbe\u7f6e --------------------\n\n    \n    settings = {\n        imageQuality: {\n            title: '\u8bbe\u7f6e\u56fe\u7247\u8d28\u91cf',\n            type: 'select',\n            saveTo: 'data',\n            options: [\n                { value: 'original', text: '\u539f\u56fe' },\n                { value: 'low', text: '\u4f4e' },\n                { value: 'middle', text: '\u4e2d' },\n                { value: 'high', text: '\u9ad8' },\n            ],\n            default: 'original',\n        },\n        appChannel: {\n            title: '\u8bbe\u7f6e\u5206\u6d41',\n            type: 'select',\n            saveTo: 'data',\n            options: [\n                { value: '1', text: '\u5206\u6d411' },\n                { value: '2', text: '\u5206\u6d412' },\n                { value: '3', text: '\u5206\u6d413' },\n            ],\n            default: '3',\n        },\n        favoriteSort: {\n            title: '\u56fe\u7247\u6536\u85cf\u6392\u5e8f\u65b9\u5f0f',\n            type: 'select',\n            options: [\n                { value: 'dd', text: '\u65b0\u5230\u65e7' },\n                { value: 'da', text: '\u65e7\u5230\u65b0' },\n            ],\n            default: 'dd',\n        },\n        leaveMessages: {\n            title: '\u7559\u8a00\u677f',\n            type: 'action',\n            action: 'picacg_leave_messages',\n        },\n        userComments: {\n            title: '\u6211\u7684\u8bc4\u8bba',\n            type: 'action',\n            action: 'picacg_user_comments',\n        },\n        showAvatarFrame: {\n            title: '\u663e\u793a\u5934\u50cf\u6846',\n            type: 'appSwitch',\n            appKey: 'showAvatarFrame',\n        },\n        autoPunchIn: {\n            title: '\u81ea\u52a8\u6253\u5361',\n            type: 'appSwitch',\n            appKey: 'autoPunchIn',\n        },\n    }\n\n    // -------------------- \u7ffb\u8bd1 --------------------\n\n    translation = {\n        'zh_CN': {},\n        'zh_TW': {},\n        'en': {\n            '\u968f\u673a': 'Random',\n            '\u6700\u65b0': 'Latest',\n            '\u5206\u7c7b': 'Categories',\n            '\u6392\u5e8f': 'Sort',\n            '\u65b0\u5230\u65e7': 'New to Old',\n            '\u65e7\u5230\u65b0': 'Old to New',\n            '\u6700\u591a\u559c\u6b22': 'Most Liked',\n            '\u6700\u591a\u6307\u540d': 'Most Followed',\n            '24\u5c0f\u65f6': '24 Hours',\n            '7\u5929': '7 Days',\n            '30\u5929': '30 Days',\n            '\u9a91\u58eb\u699c': 'Knight Rank',\n            '\u8bbe\u7f6e\u56fe\u7247\u8d28\u91cf': 'Image Quality',\n            '\u8bbe\u7f6e\u5206\u6d41': 'API Channel',\n            '\u56fe\u7247\u6536\u85cf\u6392\u5e8f\u65b9\u5f0f': 'Favorites Order',\n            '\u7559\u8a00\u677f': 'Messages',\n            '\u6211\u7684\u8bc4\u8bba': 'My Comments',\n            '\u663e\u793a\u5934\u50cf\u6846': 'Show Avatar Frame',\n            '\u81ea\u52a8\u6253\u5361': 'Auto Punch-in',\n            '\u539f\u56fe': 'Original',\n            '\u4f4e': 'Low',\n            '\u4e2d': 'Medium',\n            '\u9ad8': 'High',\n        },\n    }\n}\n";
__SOURCE_CODE["ehentai"] = "// \u53d6\u56fe\u65f6\u6bcf\u9875\u90fd\u4f1a\u7528\u5230\u540c\u4e00\u4efd\u300c\u7f29\u7565\u56fe\u5217\u8868\u300d\u548c\u300cshowkey\u300d\uff0c\u539f\u811a\u672c\u6bcf\u6b21\u90fd\u91cd\u65b0\u62c9\u4e24\u4e2a\u9875\u9762\uff0c\n// \u4e00\u9875\u8981 3 \u4e2a\u8bf7\u6c42\uff08gallery \u9875 + /s/ \u9875 + api.php\uff09\uff0c165 \u9875\u5c31\u662f 11 \u5206\u949f\u3002\n// \u8fd9\u4e24\u4efd\u6570\u636e\u5728\u4e00\u8bdd\u5185\u4e0d\u4f1a\u53d8\uff0c\u7f13\u5b58 10 \u5206\u949f\u80fd\u628a\u6bcf\u9875\u538b\u5230 1 \u4e2a\u8bf7\u6c42\u3002\nvar __ehThumbCache = {};\nvar __ehKeyCache = {};\nvar __ehCacheTtl = 600000;\n\nclass Ehentai extends ComicSource {\n    // Note: The fields which are marked as [Optional] should be removed if not used\n\n    // name of the source\n    name = \"ehentai\"\n\n    // unique id of the source\n    key = \"ehentai\"\n\n    version = \"2.0.0\"\n\n    minAppVersion = \"4.9.3\"\n\n    // update url\n    url = \"https://raw.githubusercontent.com/ccbkv/pica_configs/refs/heads/master/ehentai.js\"\n\n    /**\n     * cached api key\n     * @type {string | null}\n     */\n    apiKey = null\n\n    /**\n     * cached uid key\n     * @type {string | null}\n     */\n    uid = null\n\n    /**\n     * @param url\n     * @returns {{id: string, token: string}}\n     */\n    parseUrl(url) {\n        let segments = url.split(\"/\")\n        let id = segments[4]\n        let token = segments[5]\n        return {\n            id: id,\n            token: token\n        }\n    }\n\n    async checkEHEvent() {\n        if (!this.isLogged) {\n            return;\n        }\n        if (!this.loadSetting(\"ehevent\")) {\n            return;\n        }\n        const lastEvent = this.loadData(\"lastEventTime\");\n        const newTime = new Date().toISOString().split(\"T\")[0];\n        if (lastEvent == newTime) {\n            return;\n        }\n        const res = await Network.get(\"https://e-hentai.org/news.php\", {});\n        if (res.status !== 200) {\n            return;\n        }\n        this.saveData(\"lastEventTime\", newTime);\n        const document = new HtmlDocument(res.body);\n        const eventPane = document.getElementById(\"eventpane\");\n        if (eventPane == null) {\n            return;\n        }\n        const dawnInfo = eventPane.querySelector(\"div > p:nth-child(2)\");\n        if (dawnInfo == null) {\n            return;\n        }\n        UI.showMessage(dawnInfo.text);\n    }\n\n    // [Optional] account related\n    account = {\n\n        /**\n         * [Optional] login with webview\n         */\n        loginWithWebview: {\n            url: \"https://forums.e-hentai.org/index.php?act=Login&CODE=00\",\n            /**\n             * check login status\n             * @param url {string} - current url\n             * @param title {string} - current title\n             * @returns {boolean} - return true if login success\n             */\n            checkStatus: (url, title) => {\n                return title === \"E-Hentai Forums\";\n            },\n            onLoginSuccess: async () => {\n                let cookies = await Network.getCookies(\"https://forums.e-hentai.org\")\n                cookies.forEach((cookie) => {\n                    cookie.domain = \".exhentai.org\"\n                })\n                Network.setCookies(\"https://exhentai.org\", cookies)\n            },\n        },\n\n        loginWithCookies: {\n            fields: [\n                \"ipb_member_id\",\n                \"ipb_pass_hash\",\n                \"igneous\",\n                \"star\",\n            ],\n            /**\n             * Validate cookies, return false if cookies are invalid.\n             *\n             * Use `Network.setCookies` to set cookies before validate.\n             * @param values {string[]} - same order as `fields`\n             * @returns {Promise<boolean>}\n             */\n            validate: async (values) => {\n                if (values.length !== 4) {\n                    return false\n                }\n                if (values[0].length === 0 || values[1].length === 0) {\n                    return false\n                }\n                let cookies = []\n                for (let i = 0; i < values.length; i++) {\n                    cookies.push(new Cookie({\n                        name: this.account.loginWithCookies.fields[i],\n                        value: values[i],\n                        domain: \".e-hentai.org\"\n                    }))\n                    cookies.push(new Cookie({\n                        name: this.account.loginWithCookies.fields[i],\n                        value: values[i],\n                        domain: \".exhentai.org\"\n                    }))\n                }\n                // Keep browser verification cookies when retrying cookie login.\n                const preservedCookies = (await Network.getCookies('https://e-hentai.org/'))\n                    .filter(cookie => !this.account.loginWithCookies.fields.includes(cookie.name))\n                Network.deleteCookies('https://e-hentai.org')\n                Network.setCookies('https://e-hentai.org', [...preservedCookies, ...cookies])\n                let res = await Network.get(\n                    \"https://forums.e-hentai.org/\",\n                    {\n                        \"referer\": \"https://forums.e-hentai.org/index.php?\",\n                        \"accept\":\n                            \"text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7\",\n                        \"accept-encoding\": \"gzip, deflate, br\",\n                        \"accept-language\": \"zh-CN,zh;q=0.9,en-US;q=0.8,en;q=0.7\"\n                    });\n                if (res.status !== 200) {\n                    return false\n                }\n                let document = new HtmlDocument(res.body)\n                let name = document.querySelector(\"div#userlinks > p.home > b > a\");\n                // Some forum skins omit #userlinks but still show the logout action.\n                const logout = document.querySelector('a[href*=\"act=Login\"][href*=\"CODE=03\"]')\n                document.dispose()\n                return name != null || logout != null\n            }\n        },\n\n        /**\n         * logout function, clear account related data\n         */\n        logout: () => {\n            Network.deleteCookies(\"https://e-hentai.org\");\n            Network.deleteCookies(\"https://forums.e-hentai.org\");\n            Network.deleteCookies(\"https://exhentai.org\");\n        },\n\n        // {string?} - register url\n        registerWebsite: null,\n\n        // \u8d26\u53f7\u4fe1\u606f\u9879 (\u4e0e\u5185\u7f6e infoItems \u4e00\u81f4)\n        infoItems: [\n            {\n                title: 'cookies',\n                action: 'ehentai_cookie_management',\n            },\n        ],\n    }\n\n    get baseUrl() {\n        return 'https://' + this.loadSetting(\"domain\");\n    }\n\n    get apiUrl() {\n        return this.baseUrl.includes(\"exhentai\") ? \"https://exhentai.org/api.php\" : \"https://api.e-hentai.org/api.php\"\n    }\n\n    getStarsFromPosition(position) {\n        // \u539f\u5b9e\u73b0\u5728 position \u4e3a\u7a7a\u4e32\u65f6\u6b7b\u5faa\u73af\uff1ai \u4ece 1 \u8d77\u6c38\u8fdc\u4e0d\u7b49\u4e8e length(0)\u3002\n        // \u7a7a/\u7f3a\u7701\u4e00\u5f8b\u6309 0 \u661f\u5904\u7406\u3002\n        if (!position) {\n            return 0;\n        }\n        let i = 0;\n        while (i < position.length && position[i] !== \";\") {\n            i++;\n        }\n        switch (position.substring(0, i)) {\n            case \"background-position:0px -1px\":\n                return 5;\n            case \"background-position:0px -21px\":\n                return 4.5;\n            case \"background-position:-16px -1px\":\n                return 4;\n            case \"background-position:-16px -21px\":\n                return 3.5;\n            case \"background-position:-32px -1px\":\n                return 3;\n            case \"background-position:-32px -21px\":\n                return 2.5;\n            case \"background-position:-48px -1px\":\n                return 2;\n            case \"background-position:-48px -21px\":\n                return 1.5;\n            case \"background-position:-64px -1px\":\n                return 1;\n            case \"background-position:-64px -21px\":\n                return 0.5;\n        }\n        return 0.5;\n    }\n\n    async onLoadFailed() {\n        let cookies = await Network.getCookies('https://e-hentai.org')\n        cookies.forEach((c) => {\n            c.domain = '.exhentai.org'\n        })\n        cookies.filter((item) => item.name !== 'igneous')\n        Network.deleteCookies('https://exhentai.org')\n        Network.setCookies('https://exhentai.org', cookies)\n        throw `You may not have permission to access this page. Please check your network or try to login again.`\n    }\n\n    /**\n     *\n     * @param url {string}\n     * @param isLeaderBoard {boolean}\n     * @returns {Promise<{comics: Comic[], next: string?}>}\n     */\n    async getGalleries(url, isLeaderBoard) {\n        try {\n            this.checkEHEvent();\n        } catch (_) {}\n        // \u6e05\u7406\u9519\u8bef\u5199\u5165\u7684 cookie, \u4f1a\u5bfc\u81f4\u5361\u6b7b.\n\n        try {\n            Network.deleteCookies(this.baseUrl, ['sl']);\n        } catch (_) {}\n        // \u5df2\u767b\u5f55: \u8d26\u53f7\u8bbe\u7f6e\u4f18\u5148\u4e8e cookie, \u5728 URL \u9644\u52a0 inline_set=dm_e \u4fee\u6539\u8d26\u53f7\u8bbe\u7f6e;\n        // \u670d\u52a1\u5668\u5904\u7406\u540e 302 \u8df3\u56de\u539f\u5730\u5740(\u4fdd\u7559\u5176\u4f59\u53c2\u6570), \u8df3\u8f6c\u540e\u7684\u54cd\u5e94\u5373\u4e3a Extended \u5e03\u5c40.\n        // \u6392\u884c\u699c\u9875(toplist)\u4e0d\u652f\u6301\u8be5\u53c2\u6570(\u76f4\u63a5 200), \u65e0\u9700\u9644\u52a0.\n        if (!isLeaderBoard) {\n            url += (url.includes('?') ? '&' : '?') + 'inline_set=dm_e';\n        }\n        let t = isLeaderBoard ? 1 : 0;\n        let res\n        try {\n            res = await Network.get(url, {});\n        }\n        catch (e) {\n            if(e.toString().toLowerCase().includes(\"redirect\")) {\n                await this.onLoadFailed()\n            }\n            throw e\n        }\n        if (res.status !== 200) {\n            throw `Invalid status code: ${res.status}`\n        }\n        if(res.body.trim().length === 0) {\n            await this.onLoadFailed()\n        }\n        if(res.body[0] !== '<') {\n            if(res.body.includes(\"IP\")) {\n                throw \"Your IP address has been banned\"\n            }\n            throw \"Failed to load page\"\n        }\n        let document = new HtmlDocument(res.body);\n        let galleries = [];\n\n        // compact mode\n        for (let item of document.querySelectorAll(\"table.itg.gltc > tbody > tr\")) {\n            try {\n                let type = item.children[0 + t].children[0].text;\n                let time = item.children[1 + t].children[2].children[0].text;\n                let stars = this.getStarsFromPosition(item.children[1 + t].children[2].children[1].attributes[\"style\"])\n                let cover = item.children[1 + t].children[1].children[0].children[0].attributes[\"src\"];\n                if (cover[0] === 'd') {\n                    cover = item.children[1 + t].children[1].children[0].children[0].attributes[\"data-src\"];\n                }\n                let title = item.children[2 + t].children[0].children[0].text;\n                let link = item.children[2 + t].children[0].attributes[\"href\"];\n                let uploader = \"\";\n                let pages = 0;\n                try {\n                    if (url.includes(\"/favorites.php\")) {\n                        pages = Number(item.children[1 + t].children[1].children[1].children[1].children[1].text.match(/\\d+/)[0]);\n                    } else {\n                        pages = Number(item.children[3 + t].children[1].text.match(/\\d+/)[0]);\n                        uploader = item.children[3 + t].children[0].children[0].text;\n                    }\n                } catch(e) {}\n                let tags = [];\n                let language = null\n                for (let node of item.children[2 + t].children[0].children[1].children) {\n                    let tag = node.attributes[\"title\"]\n                    if (tag.startsWith(\"language:\")) {\n                        let l = tag.split(\":\")[1].trim()\n                        language = l === 'translated' ? language : l\n                        continue\n                    }\n                    tags.push(tag)\n                }\n                galleries.push(new Comic({\n                    id: link,\n                    title: title,\n                    subTitle: uploader,\n                    cover: cover,\n                    tags: tags,\n                    description: `${time}  ${type ?? \"\"}`,\n                    stars: stars,\n                    maxPage: pages,\n                    language: language\n                }));\n            } catch(e) {\n            }\n        }\n\n        // Thumbnail mode\n        for (let item of document.querySelectorAll(\"div.gl1t\")) {\n            try {\n                let title = item.querySelector(\"a\")?.text ?? \"Unknown\";\n                let type =\n                    item.querySelector(\"div.gl5t > div > div.cs\")?.text ?? \"Unknown\";\n                let time = item.querySelectorAll(\"div.gl5t > div > div\").find((element) => !isNaN(Date.parse(element.text)))?.text;\n                let coverPath = item.querySelector(\"img\")?.attributes[\"src\"] ?? \"\";\n                let stars = this.getStarsFromPosition(item.querySelector(\"div.gl5t > div > div.ir\")?.attributes[\"style\"] ?? \"\");\n                let link = item.querySelector(\"a\")?.attributes[\"href\"] ?? \"\";\n                let pages = Number(item.querySelectorAll(\"div.gl5t > div > div\").find((element) => element.text.includes(\"page\"))?.text.match(/\\d+/)[0] ?? \"0\");\n                let tags = item.querySelectorAll('div.gt, div.gtl').map((e) => e.attributes[\"title\"] ?? \"\");\n                let language = tags.find((e) => e.startsWith(\"language:\") && !e.includes('translated'))?.split(\":\")[1].trim() ?? null;\n                galleries.push(new Comic({\n                    id: link,\n                    title: title,\n                    cover: coverPath,\n                    tags: tags,\n                    description: `${time}  ${type ?? \"\"}`,\n                    stars: stars,\n                    maxPage: pages,\n                    language: language\n                }));\n            } catch (e) {\n                //\u5ffd\u89c6\n            }\n        }\n\n        // Extended mode\n        for (let item of document.querySelectorAll(\"table.itg.glte > tbody > tr\")) {\n            try {\n                let title = item.querySelector(\"td.gl2e > div > a > div > div.glink\")?.text ?? \"Unknown\";\n                let type = item.querySelector(\"td.gl2e > div > div.gl3e > div.cn\")?.text ?? \"Unknown\";\n                let time = item.querySelectorAll(\"td.gl2e > div > div.gl3e > div\").find((element) => !isNaN(Date.parse(element.text)))?.text ?? \"Unknown\";\n                let uploader = item.querySelector(\"td.gl2e > div > div.gl3e > div > a\")?.text ?? \"Unknown\";\n                let coverPath = item.querySelector(\"td.gl1e > div > a > img\")?.attributes[\"src\"] ?? \"\";\n                let stars = this.getStarsFromPosition(item.querySelector(\"td.gl2e > div > div.gl3e > div.ir\")?.attributes[\"style\"] ?? \"\");\n                let link = item.querySelector(\"td.gl1e > div > a\")?.attributes[\"href\"] ?? \"\";\n                let tags = item.querySelectorAll('div.gt, div.gtl').map((e) => e.attributes[\"title\"] ?? \"\");\n                let pages = Number(item.querySelectorAll(\"td.gl2e > div > div.gl3e > div\").find((element) => element.text.includes(\"page\"))?.text.match(/\\d+/)[0] ?? \"\");\n                let language = tags.find((e) => e.startsWith(\"language:\") && !e.includes('translated'))?.split(\":\")[1].trim() ?? null;\n                galleries.push(new Comic({\n                    id: link,\n                    title: title,\n                    subTitle: uploader,\n                    cover: coverPath,\n                    tags: tags,\n                    description: `${time}  ${type ?? \"\"}`,\n                    stars: stars,\n                    maxPage: pages,\n                    language: language\n                }))\n            } catch (e) {\n                //\u5ffd\u89c6\n            }\n        }\n\n        // minimal mode\n        for (let item of document.querySelectorAll(\"table.itg.gltm > tbody > tr\")) {\n            try {\n                let title = item.querySelector(\"td.gl3m > a > div.glink\")?.text ?? \"Unknown\";\n                let type = item.querySelector(\"td.gl1m > div.cs\")?.text ?? \"Unknown\";\n                let time = item.querySelectorAll(\"td.gl2m > div\").find((element) => !isNaN(Date.parse(element.text)))?.text ?? \"Unknown\";\n                let uploader = item.querySelector(\"td.gl5m > div > a\")?.text ?? \"Unknown\";\n                let coverPath = item.querySelector(\"td.gl2m > div > div > img\")?.attributes[\"src\"];\n                if (coverPath[0] === 'd') {\n                    coverPath = item.querySelector(\"td.gl2m > div > div > img\")?.attributes[\"data-src\"];\n                }\n                let stars = this.getStarsFromPosition(item.querySelector(\"td.gl4m > div.ir\")?.attributes[\"style\"] ?? \"\");\n                let link = item.querySelector(\"td.gl3m > a\")?.attributes[\"href\"] ?? \"\";\n                let tags = item.querySelectorAll('div.gt, div.gtl').map((e) => e.attributes[\"title\"] ?? \"\");\n                let language = tags.find((e) => e.startsWith(\"language:\") && !e.includes('translated'))?.split(\":\")[1].trim() ?? null;\n                galleries.push(new Comic({\n                    id: link,\n                    title: title,\n                    subTitle: uploader,\n                    cover: coverPath,\n                    tags: tags,\n                    description: `${time}  ${type ?? \"\"}`,\n                    stars: stars,\n                    language: language\n                }))\n            } catch (e) {\n                //\u5ffd\u89c6\n            }\n        }\n\n        let nextButton = document.querySelector(\"a#dnext\");\n        let next = nextButton?.attributes[\"href\"]\n\n        return {\n            comics: galleries,\n            next: next\n        }\n    }\n\n    // explore page list\n    explore = [\n        {\n            // title of the page.\n            // title is used to identify the page, it should be unique\n            title: \"Eh\u6700\u65b0\",\n\n            /// multiPartPage or multiPageComicList or mixed\n            type: \"multiPageComicList\",\n\n            loadNext: (next) => {\n                return this.getGalleries(next ?? this.baseUrl, false);\n            }\n        },\n        {\n            // title of the page.\n            // title is used to identify the page, it should be unique\n            title: \"Eh\u70ed\u95e8\",\n\n            /// multiPartPage or multiPageComicList or mixed\n            type: \"multiPageComicList\",\n\n            loadNext: (next) => {\n                return this.getGalleries(next ?? `${this.baseUrl}/popular`, false);\n            }\n        },\n        {\n            // title of the page.\n            // title is used to identify the page, it should be unique\n            title: \"Eh\u89c2\u770b\",\n\n            /// multiPartPage or multiPageComicList or mixed\n            type: \"multiPageComicList\",\n\n            loadNext: async (next) => {\n                if (!this.isLogged) {\n                    UI.showMessage(\"Need login first\");\n                    return {\n                        comics: [],\n                        next: null\n                    };\n                }\n                return this.getGalleries(next ?? `${this.baseUrl}/watched`, false);\n            }\n        },\n    ]\n\n    // categories\n    category = {\n        /// title of the category page, used to identify the page, it should be unique\n        title: \"ehentai\",\n        native: \"ehentai\",\n        parts: [],\n        // enable ranking page\n        enableRankingPage: true,\n    }\n\n    /// category comic loading related\n    categoryComics = {\n        ranking: {\n            // For a single option, use `-` to separate the value and text, left for value, right for text\n            options: [\n                \"15-\u6628\u5929\",\n                \"13-\u672c\u6708\",\n                \"12-\u672c\u5e74\",\n                \"11-\u5168\u90e8\"\n            ],\n            /**\n             * load ranking comics\n             * @param option {string} - option from optionList\n             * @param page {number} - page number\n             * @returns {Promise<{comics: Comic[], maxPage: number}>}\n             */\n            load: async (option, page) => {\n                let res = await this.getGalleries(`https://e-hentai.org/toplist.php?tl=${option}&p=${page-1}`, true);\n                let comics = res.comics\n                if(this.loadSetting('domain') === 'exhentai.org') {\n                    comics.forEach((e) => {\n                        e.id = e.id.replace('e-hentai', 'exhentai')\n                    })\n                }\n                return {\n                    comics: comics,\n                    maxPage: 200,\n                }\n            }\n        }\n    }\n\n    /// search related\n    search = {\n        /**\n         * load search result with next page token\n         * @param keyword {string}\n         * @param options {(string)[]} - options from optionList\n         * @param next {string | null}\n         * @returns {Promise<{comics: Comic[], maxPage: number}>}\n         */\n        loadNext: async (keyword, options, next) => {\n            let category = JSON.parse(options[0]);\n            let stars = options[1];\n            let language = options[2];\n            let fcats = 1023\n            if (!Array.isArray(category)) {\n                category = [category];\n            }\n            for(let c of category) {\n                fcats -= 1 << Number(c)\n            }\n            if(language && !keyword.includes(\"language:\")) {\n                keyword += ` language:${language}`\n            }\n            let url = `${this.baseUrl}/?f_search=${encodeURIComponent(keyword)}`\n            if(fcats) {\n                url += `&f_cats=${fcats}`\n            }\n            if(stars) {\n                url += `&f_srdd=${stars}`\n            }\n            return this.getGalleries(next ?? url, false);\n        },\n\n        // provide options for search\n        optionList: [\n            {\n                // type: select, multi-select, dropdown\n                type: \"multi-select\",\n                // For a single option, use `-` to separate the value and text, left for value, right for text\n                options: [\n                    \"0-Misc\",\n                    \"1-Doujinshi\",\n                    \"2-Manga\",\n                    \"3-Artist CG\",\n                    \"4-Game CG\",\n                    \"5-Image Set\",\n                    \"6-Cosplay\",\n                    \"7-Asian Porn\",\n                    \"8-Non-H\",\n                    \"9-Western\",\n                ],\n                // option label\n                label: \"\u5206\u7c7b\",\n                // default selected options\n                default: [\"0\", \"1\", \"2\", \"3\", \"4\", \"5\", \"6\", \"7\", \"8\", \"9\"],\n            },\n            {\n                // type: select, multi-select, dropdown\n                // For select, there is only one selected value\n                // For multi-select, there are multiple selected values or none. The `load` function will receive a json string which is an array of selected values\n                // For dropdown, there is one selected value at most. If no selected value, the `load` function will receive a null\n                type: \"dropdown\",\n                // For a single option, use `-` to separate the value and text, left for value, right for text\n                options: [\n                    \"-<none>\",\n                    \"0-0\",\n                    \"1-1\",\n                    \"2-2\",\n                    \"3-3\",\n                    \"4-4\",\n                    \"5-5\",\n                ],\n                // option label\n                label: \"\u6700\u5c11\u661f\u661f\",\n            },\n            {\n                // type: select, multi-select, dropdown\n                type: \"dropdown\",\n                // For a single option, use `-` to separate the value and text, left for value, right for text\n                options: [\n                    \"-<none>\",\n                    \"chinese-Chinese\",\n                    \"english-English\",\n                    \"japanese-Japanese\",\n                ],\n                // option label\n                label: \"\u8bed\u8a00\",\n            },\n        ],\n\n        // enable tags suggestions\n        enableTagsSuggestions: true,\n    }\n\n    // favorite related\n    favorites = {\n        // whether support multi folders\n        multiFolder: true,\n        singleFolderForSingleComic: true,\n        /**\n         * add or delete favorite.\n         * throw `Login expired` to indicate login expired, App will automatically re-login and re-add/delete favorite\n         * @param comicId {string}\n         * @param folderId {string}\n         * @param isAdding {boolean} - true for add, false for delete\n         * @param favoriteId {string?} - [Comic.favoriteId]\n         * @returns {Promise<any>} - return any value to indicate success\n         */\n        addOrDelFavorite: async (comicId, folderId, isAdding, favoriteId) => {\n            let parsed = this.parseUrl(comicId)\n            let id = parsed.id\n            let token = parsed.token\n            if(isAdding) {\n                let res = await Network.post(\n                    `${this.baseUrl}/gallerypopups.php?gid=${id}&t=${token}&act=addfav`,\n                    {\n                        \"Content-Type\": \"application/x-www-form-urlencoded\",\n                    },\n                    `favcat=${folderId}&favnote=&apply=Add+to+Favorites&update=1`\n                );\n                if (res.status !== 200 || res.body.length === 0 || res.body[0] !== \"<\") {\n                    throw \"Failed to add favorite\"\n                }\n                return \"ok\"\n            } else {\n                let res = await Network.post(\n                    `${this.baseUrl}/gallerypopups.php?gid=${id}&t=${token}&act=addfav`,\n                    {\n                        \"Content-Type\": \"application/x-www-form-urlencoded\",\n                    },\n                    `favcat=favdel&favnote=&apply=Apply+Changes&update=1`\n                );\n                if (res.status !== 200 || res.body.length === 0 || res.body[0] !== \"<\") {\n                    throw \"Failed to delete favorite\"\n                }\n                return \"ok\"\n            }\n        },\n        /**\n         * load favorite folders.\n         * throw `Login expired` to indicate login expired, App will automatically re-login retry.\n         * if comicId is not null, return favorite folders which contains the comic.\n         * @param comicId {string?}\n         * @returns {Promise<{folders: {[p: string]: string}, favorited: string[]}>} - `folders` is a map of folder id to folder name, `favorited` is a list of folder id which contains the comic\n         */\n        loadFolders: async (comicId) => {\n            try {\n                this.checkEHEvent();\n            } catch (_) {}\n            let res = await Network.get(`${this.baseUrl}/favorites.php`, {});\n            if (res.status !== 200) {\n                throw `Invalid status code: ${res.status}`\n            }\n            let document = new HtmlDocument(res.body);\n            let folders = new Map();\n            folders.set(\"-1\", \"All\")\n            let sum = 0;\n            for (let item of document.querySelectorAll(\"div.fp\")) {\n                if (item.text === \"Show All Favorites\") continue;\n                let name = item.children[2]?.text ?? `Favorite ${folders.size}`\n                let length = item.children[0]?.text;\n                if(length) {\n                    name += ` (${length})`\n                    sum += +length\n                }\n                folders.set((folders.size-1).toString(), name)\n            }\n            folders.set(\"-1\", `All (${sum})`)\n            document.dispose()\n            let favorited = []\n            if(comicId) {\n                let comic = await this.comic.loadInfo(comicId)\n                if(comic.isFavorite) {\n                    favorited.push(comic.folder)\n                }\n            }\n            return {\n                folders: folders,\n                favorited: favorited\n            }\n        },\n        loadNext: async (next, folder) => {\n            let url = `${this.baseUrl}/favorites.php`;\n            if(folder !== '-1') {\n                url += `?favcat=${folder}`\n            }\n            return this.getGalleries(next ?? url, false);\n        }\n    }\n\n    /// single comic related\n    comic = {\n        /**\n         * load comic info\n         * @param id {string}\n         * @returns {Promise<ComicDetails>}\n         */\n        loadInfo: async (id) => {\n            try {\n                this.checkEHEvent();\n            } catch (_) {}\n            let res = await Network.get(id, {\n                'cookie': 'nw=1'\n            });\n            if (res.status !== 200) {\n                throw `Invalid status code: ${res.status}`\n            }\n            if(res.body.trim().length === 0) {\n                throw `Exception: empty data\\nYou may not have permission to access this page.`\n            }\n            let document = new HtmlDocument(res.body);\n\n            if (this.isLogged && this.loadSetting(\"hvevent\")) {\n                const eventPane = document.getElementById(\"eventpane\");\n                if (eventPane != null) {\n                    const hvUrl = eventPane.querySelector('div > a')?.attributes['href'];\n                    if (hvUrl != null) {\n                        UI.showDialog(\n                            \"HentaiVerse\",\n                            this.translate(\"hentaiverse\"),\n                            [\n                                {\n                                    text: this.translate(\"cancel\"),\n                                    callback: () => {}\n                                },\n                                {\n                                    text: this.translate(\"fight\"),\n                                    callback: () => {\n                                        UI.launchUrl(hvUrl);\n                                    }\n                                }\n                            ]\n                        );\n                    }\n                }\n            }\n\n            let tags = new Map();\n            for(let tr of document.querySelectorAll(\"div#taglist > table > tbody > tr\")) {\n                tags.set(\n                    tr.children[0].text.substring(0, tr.children[0].text.length - 1),\n                    tr.children[1].children.map((e) =>\n                        e.children[0]\n                        .attributes[\"onclick\"]\n                        .split(\":\")[1]\n                        .split(\"'\")[0]\n                    )\n                )\n            }\n\n            let maxPage = \"1\"\n            for(let element of document.querySelectorAll(\"td.gdt2\")) {\n                if (element.text.includes(\"page\")) {\n                    maxPage = element.text.match(/\\d+/)[0];\n                }\n            }\n\n            let isFavorited = true;\n            // \u539f\u6587\u662f === \" Add to Favorites\"\uff08\u4f9d\u8d56\u524d\u5bfc\u7a7a\u683c\u7684\u7cbe\u786e\u6bd4\u8f83\uff09\uff0c\n            // \u4e0d\u540c\u89e3\u6790\u5668\u4ea7\u51fa\u7684\u7a7a\u767d\u6570\u4e0d\u540c\u5c31\u4f1a\u5224\u65ad\u53cd\uff0c\u8fd9\u91cc\u7edf\u4e00 trim \u540e\u6bd4\u8f83\u3002\n            if((document.querySelector(\"a#favoritelink\")?.text || \"\").trim() === \"Add to Favorites\") {\n                isFavorited = false;\n            }\n            let folder = null\n            if(isFavorited) {\n                // \u672a\u767b\u5f55\u65f6 div#fav \u662f\u7a7a\u7684\uff0c\u4e4b\u524d\u8fd9\u91cc\u76f4\u63a5\u53d6 children[0] \u4f1a\u5d29\uff0c\u6574\u6761\u8be6\u60c5\u5931\u8d25\u3002\n                let favBar = document.querySelector(\"div#fav\")?.children[0]?.attributes?.[\"style\"];\n                let position = favBar\n                    ? favBar.split(\"background-position:0px -\")[1]?.split(\"px;\")[0]\n                    : null;\n                if (position != null) {\n                    folder = (Number(position-2) / 19).toString()\n                }\n            }\n\n            let coverPath = document.querySelector(\"div#gleft > div#gd1 > div\").attributes[\"style\"];\n            coverPath = RegExp(\"https?://([-a-zA-Z0-9.]+(/\\\\S*)?\\\\.(?:jpg|jpeg|gif|png|webp))\").exec(coverPath)[0];\n\n            let uploader = document.getElementById(\"gdn\")?.children[0]?.text\n\n            let stars = Number(document.getElementById(\"rating_label\")?.text?.split(':')?.at(1)?.trim());\n\n            let category = document.querySelector(\"div.cs\").text;\n            tags.set(\"Category\", [category])\n\n            if (uploader) {\n                tags.set(\"uploader\", [uploader]);\n            }\n            \n            let time = document.querySelector(\"div#gdd > table > tbody > tr > td.gdt2\").text\n\n            let script = document.querySelectorAll(\"script\").find((e) => e.text.includes(\"var token\"));\n            let reg = RegExp(\"var\\\\s+(\\\\w+)\\\\s*=\\\\s*(.*?);\", \"g\");\n            let variables = new Map();\n            for(let match of script.text.matchAll(reg)) {\n                variables.set(match[1], match[2]);\n            }\n\n            let title = document.querySelector(\"h1#gn\").text;\n            let subtitle = document.querySelector(\"h1#gj\")?.text;\n            if(subtitle != null && subtitle.trim() === \"\") {\n                subtitle = null;\n            }\n            let comments = this.comic.parseComments(document)\n\n            let comic = new ComicDetails({\n                id: id,\n                title: title,\n                subTitle: subtitle,\n                cover: coverPath,\n                tags: tags,\n                stars: stars,\n                maxPage: Number(maxPage),\n                isFavorite: isFavorited,\n               // uploader: uploader,\n                uploadTime: time,\n                url: id,\n                comments: comments.comments,\n            })\n\n            comic.folder = folder\n            comic.token = variables.get(\"token\")\n            this.apikey = variables.get(\"apikey\")\n            if(this.apikey[0] === '\"') {\n                this.apikey = this.apikey.substring(1, this.apikey.length - 1)\n            }\n            this.uid = variables.get(\"apiuid\")\n\n            document.dispose()\n\n            return comic;\n        },\n        /**\n         * [Optional] load thumbnails of a comic\n         * @param id {string}\n         * @param next {string?} - next page token, null for first page\n         * @returns {Promise<{thumbnails: string[], next: string?, urls: string[]}>} - `next` is next page token, null for no more\n         */\n        loadThumbnails: async (id, next) => {\n            let _ck = String(id) + '|' + (next == null ? '' : String(next))\n            let _hit = __ehThumbCache[_ck]\n            if(_hit && Date.now() - _hit.t < __ehCacheTtl) return _hit.v\n            let url = id\n            if(next != null) {\n                url += `?p=${next}`\n            }\n            let res = await Network.get(url, {\n                'cache-time': 'long',\n                'prevent-parallel': 'true',\n                'cookie': 'nw=1'\n            });\n            if(res.status !== 200) {\n                throw `Invalid status code: ${res.status}`\n            }\n            let document = new HtmlDocument(res.body);\n            /**\n             * @param e {HtmlElement}\n             */\n            let parseImageUrl = (e) => {\n                let style = e.attributes['style'];\n                let width = Number(style.split('width:')[1].split('px')[0])\n                let height = Number(style.split('height:')[1].split('px')[0])\n                let r = style.split(\"background:transparent url(\")[1]\n                let url = r.split(\")\")[0]\n                let range = '';\n                if(r.includes('px')) {\n                    let position = Number(r.split(') -')[1].split('px')[0])\n                    range += `x=${position}-${position + width}`\n                }\n                if (height)  range += `${range ? \"&\" : \"\"}y=0-${height}`;\n                if (range)  url += `@${range}`;\n                return url;\n            };\n            let images = document.querySelectorAll(\"div.gdtm > div\").map((e) => {\n                return parseImageUrl(e)\n            });\n            images.push(...document.querySelectorAll(\"div.gdtl > a > img\").map((e) => e.attributes[\"src\"]))\n            if(images.length === 0) {\n                for(let e of document.querySelectorAll(\"div.gt100 > a > div\")\n                    .map(e => e.children.length === 0 ? e : e.children[0])) {\n                    images.push(parseImageUrl(e))\n                }\n                for(let e of document.querySelectorAll(\"div.gt200 > a > div\")\n                    .map(e => e.children.length === 0 ? e : e.children[0])) {\n                    images.push(parseImageUrl(e))\n                }\n            }\n            let urls = document.querySelectorAll(\"table.ptb > tbody > tr > td > a\").map((e) => e.attributes[\"href\"])\n            let pageNumbers = urls.map((e) => {\n                let n = Number(e.split(\"=\")[1])\n                if(isNaN(n)) {\n                    return 0\n                }\n                return n\n            })\n            let maxPage = Math.max(0, ...pageNumbers)\n            let current = 0\n            if(next) {\n                current = Number(next)\n            }\n            current += 1\n            if(current > maxPage) {\n                current = null\n            } else {\n                current = current.toString()\n            }\n            let _urls = document.querySelectorAll(\"div#gdt a\").map((e) => e.attributes[\"href\"])\n            document.dispose()\n            let _out = {\n                thumbnails: images,\n                urls: _urls,\n                maxPage: maxPage + 1,\n                next: current\n            }\n            __ehThumbCache[_ck] = { t: Date.now(), v: _out }\n            return _out\n        },\n\n        /**\n         * rate a comic\n         * @param id\n         * @param rating {number} - [0-10] app use 5 stars, 1 rating = 0.5 stars,\n         * @returns {Promise<any>}\n         */\n        starRating: async (id, rating) => {\n            let res = await Network.post(this.apiUrl, {\n                'Content-Type': 'application/json'\n            }, {\n                'gid': this.parseUrl(id).id,\n                'token': this.parseUrl(id).token,\n                'method': 'rategallery',\n                'rating': rating,\n                'apikey': this.apikey,\n                'apiuid': this.uid,\n            })\n            if(res.status !== 200) {\n                throw `Invalid status code: ${res.status}`\n            }\n            return 'ok'\n        },\n\n        getKey: async (url) => {\n            let _hit = __ehKeyCache[url]\n            if(_hit && Date.now() - _hit.t < __ehCacheTtl) return _hit.v\n            let res = await Network.get(url, {\n                'cache-time': 'long',\n                'prevent-parallel': 'true',\n            })\n            let document = new HtmlDocument(res.body)\n            let script = document.querySelectorAll(\"script\").find((e) => e.text.includes(\"showkey\"));\n            if(script) {\n                let reg = RegExp(\"showkey=\\\"(.*?)\\\"\", \"g\");\n                let match = reg.exec(script.text)\n                let _out = {\n                    'showkey': match[1]\n                }\n                document.dispose()\n                __ehKeyCache[url] = { t: Date.now(), v: _out }\n                return _out\n            }\n            script = document.querySelectorAll(\"script\").find((e) => e.text.includes(\"mpvkey\"))?.text;\n            document.dispose()\n            if(script) {\n                let mpvkey = script.split(';').find((e) => e.includes(\"mpvkey\")).replaceAll(' ', '').split('=')[1].replaceAll('\"', '');\n                let imageList = script.split(';').find((e) => e.includes(\"imagelist\")).replaceAll(' ', '').split('=')[1];\n                let _out2 = {\n                    'mpvkey': mpvkey,\n                    'imageKeys': JSON.parse(imageList).map((e) => e[\"k\"])\n                }\n                __ehKeyCache[url] = { t: Date.now(), v: _out2 }\n                return _out2\n            }\n            throw \"Failed to get key\"\n        },\n        /**\n         * load images of a chapter\n         * @param comicId {string}\n         * @param epId {string?}\n         * @returns {Promise<{images: string[]}>}\n         */\n        loadEp: async (comicId, epId) => {\n            let comic = await this.comic.loadInfo(comicId)\n            return {\n                images: Array.from({length: comic.maxPage}, (_, i) => i.toString())\n            }\n        },\n        /**\n         * [Optional] provide configs for an image loading\n         * @param image\n         * @param comicId\n         * @param epId\n         * @param nl\n         * @returns {{}}\n         */\n        onImageLoad: async (image, comicId, epId, nl) => {\n            let first = await this.comic.loadThumbnails(comicId)\n            let key = await this.comic.getKey(first.urls[0])\n            let page = Number(image)\n\n            let getImageFromApi = async (nl) => {\n                if(key.mpvkey) {\n                    let res = await Network.post(this.apiUrl, {\n                        'Content-Type': 'application/json',\n                    }, {\n                        'gid': this.parseUrl(comicId).id,\n                        \"imgkey\": key.imageKeys[page],\n                        \"method\": \"imagedispatch\",\n                        \"page\": Number(image) + 1,\n                        \"mpvkey\": key.mpvkey,\n                        \"nl\": nl,\n                    })\n                    let json = JSON.parse(res.body)\n                    return {\n                        url: json.i.toString(),\n                        nl: json.s.toString()\n                    }\n                } else {\n                    let parseImageKeyFromUrl = (url) => {\n                        return url.split(\"/\")[4]\n                    }\n\n                    let url = ''\n                    if(page < first.thumbnails.length) {\n                        url = first.urls[page]\n                    } else {\n                        let onePageLength = first.thumbnails.length\n                        let shouldLoadPage = Math.floor(page / onePageLength)\n                        let index = page % onePageLength\n                        let thumbnails =\n                            await this.comic.loadThumbnails(comicId, shouldLoadPage.toString())\n                        url = thumbnails.urls[index]\n                    }\n\n                    let res = await Network.post(this.apiUrl, {\n                        'Content-Type': 'application/json',\n                    }, {\n                        'gid': this.parseUrl(comicId).id,\n                        \"imgkey\": parseImageKeyFromUrl(url),\n                        \"method\": \"showpage\",\n                        \"page\": page + 1,\n                        \"showkey\": key.showkey,\n                        \"nl\": nl,\n                    })\n                    let json = JSON.parse(res.body)\n                    let i6 = json.i6\n                    let reg = RegExp(\"nl\\\\('(.+?)'\\\\)\").exec(i6)\n                    if(reg) nl = reg[1]\n                    let image = json.i3\n                    image = image.substring(image.indexOf(\"src=\\\"\") + 5, image.indexOf(\"\\\" style\"))\n                    return {\n                        url: image,\n                        nl: nl\n                    }\n                }\n            }\n\n            let res = await getImageFromApi(nl)\n\n            let onLoadFailed = null\n\n            if(res.nl) {\n                onLoadFailed = async () => {\n                    return this.comic.onImageLoad(image, comicId, epId, res.nl)\n                }\n            }\n\n            return {\n                url: res.url,\n                nl: res.nl,\n                headers: {\n                    'referer': this.baseUrl,\n                },\n                onLoadFailed: onLoadFailed,\n            }\n        },\n        /**\n         * [Optional] provide configs for a thumbnail loading\n         * @param url {string}\n         * @returns {{}}\n         */\n        onThumbnailLoad: (url) => {\n            if(url.includes('s.exhentai.org')) {\n                url = url.replace(\"s.exhentai.org\", \"ehgt.org\")\n            }\n            return {\n                url: url,\n                headers: {\n                    'referer': this.baseUrl,\n                }\n            }\n        },\n        parseComments: (document) => {\n            let comments = []\n            for(let c of document.querySelectorAll('div.c1')) {\n                let name = c.querySelector('div.c3 > a')?.text ?? \"\"\n                let time = c.querySelector('div.c3')?.text?.split(\"Posted on\")?.at(1)?.split('by')?.at(0)?.trim() ?? 'unknown'\n                let content = ''\n                if(typeof appVersion) {\n                    // since 1.0.6, [appVersion] field is available and rich comment is supported\n                    content = c.querySelector('div.c6').innerHTML\n                } else {\n                    content = c.querySelector('div.c6').text\n                }\n                let score = Number(c.querySelector('div.c5 > span')?.text)\n                if(isNaN(score)) {\n                    score = null\n                }\n                let id = c.previousElementSibling?.attributes['name']?.match(/\\d+/)[0] ?? '0'\n                let isUp = c.querySelector(`a#comment_vote_up_${id}`)?.attributes['style']?.length > 0\n                let isDown = c.querySelector(`a#comment_vote_down_${id}`)?.attributes['style']?.length > 0\n\n                comments.push(new Comment({\n                    id: id,\n                    content: content,\n                    time: time,\n                    userName: name,\n                    score: score,\n                    voteStatus: isUp ? 1 : isDown ? -1 : 0,\n                }))\n            }\n\n            return {\n                comments: comments,\n                maxPage: 1\n            }\n        },\n        /**\n         * [Optional] load comments\n         * @param comicId {string}\n         * @param subId {string?} - ComicDetails.subId\n         * @param page {number}\n         * @param replyTo {string?} - commentId to reply, not null when reply to a comment\n         * @returns {Promise<{comments: Comment[], maxPage: number?}>}\n         */\n        loadComments: async (comicId, subId, page, replyTo) => {\n            let res = await Network.get(`${comicId}?hc=1`, {\n                'cookie': 'nw=1'\n            });\n            if(res.status !== 200) {\n                throw `Invalid status code: ${res.status}`\n            }\n            let document = new HtmlDocument(res.body)\n            let result = this.comic.parseComments(document)\n            document.dispose()\n            return result\n        },\n        /**\n         * [Optional] send a comment, return any value to indicate success\n         * @param comicId {string}\n         * @param subId {string?} - ComicDetails.subId\n         * @param content {string}\n         * @param replyTo {string?} - commentId to reply, not null when reply to a comment\n         * @returns {Promise<any>}\n         */\n        sendComment: async (comicId, subId, content, replyTo) => {\n            let res = await Network.post(comicId, {\n                'Content-Type': 'application/x-www-form-urlencoded',\n                'referer': comicId,\n            }, `commenttext_new=${encodeURIComponent(content)}`)\n            if(res.status >= 400) {\n                throw `Invalid status code: ${res.status}`\n            }\n            let document = new HtmlDocument(res.body)\n            if(document.querySelector('p.br')) {\n                throw document.querySelector('p.br').text\n            }\n            return 'ok'\n        },\n        /**\n         * [Optional] vote a comment\n         * @param id {string} - comicId\n         * @param subId {string?} - ComicDetails.subId\n         * @param commentId {string} - commentId\n         * @param isUp {boolean} - true for up, false for down\n         * @param isCancel {boolean} - true for cancel, false for vote\n         * @returns {Promise<number>} - new score\n         */\n        voteComment: async (id, subId, commentId, isUp, isCancel) => {\n            if(this.apikey == null || this.uid == null) {\n                throw \"Login required\"\n            }\n\n            let res = await Network.post(this.apiUrl, {\n                'Content-Type': 'application/json'\n            }, {\n                'gid': this.parseUrl(id).id,\n                'token': this.parseUrl(id).token,\n                'method': 'votecomment',\n                'comment_id': commentId,\n                'comment_vote': isUp ? 1 : -1,\n                'apikey': this.apikey,\n                'apiuid': this.uid,\n            })\n\n            let json = JSON.parse(res.body)\n\n            if(json.error) {\n                throw json.error\n            }\n\n            return json.comment_score\n        },\n        archive: {\n            getArchives: async (cid) => {\n                let comicInfo = await this.comic.loadInfo(cid)\n                let urlParseResult = this.parseUrl(cid)\n                let gid = urlParseResult.id\n                let token = urlParseResult.token\n                let res = await Network.get(`${this.baseUrl}/archiver.php?gid=${gid}&token=${token}`, {})\n                if(res.status !== 200) {\n                    throw `Invalid status code: ${res.status}`\n                }\n                let document = new HtmlDocument(res.body)\n                let body = document.querySelector(\"div#db\")\n                let index = this.baseUrl.includes(\"exhentai\") ? 1 : 3\n                \n                let archives = []\n                \n                // Parse H@H Download options from the table\n                let hathTable = document.querySelector(\"table\");\n                if (hathTable) {\n                    let hathCells = hathTable.querySelectorAll(\"td\");\n                    for (let cell of hathCells) {\n                        let link = cell.querySelector(\"a\");\n                        if (link) {\n                            // Extract resolution from onclick attribute\n                            let onclick = link.attributes[\"onclick\"];\n                            let resolutionMatch = onclick.match(/do_hathdl\\('([^']+)'\\)/);\n                            if (resolutionMatch) {\n                                let resolution = resolutionMatch[1];\n                                let linkText = link.text;\n                                let paragraphs = cell.querySelectorAll(\"p\");\n                                let size = paragraphs.length > 1 ? paragraphs[1].text : \"Unknown\";\n                                let cost = paragraphs.length > 2 ? paragraphs[2].text : \"Unknown\";\n                                \n                                archives.push({\n                                    id: `h@h_${resolution}`,\n                                    title: `H@H ${linkText}`,\n                                    description: `Size: ${size}, Cost: ${cost}`,\n                                });\n                            }\n                        } else {\n                            // Skip disabled options (N/A) - don't add them to the list\n                            // This prevents users from accidentally selecting unavailable options\n                            let paragraphs = cell.querySelectorAll(\"p\");\n                            if (paragraphs.length > 0) {\n                                let size = paragraphs.length > 1 ? paragraphs[1].text : \"N/A\";\n                                let cost = paragraphs.length > 2 ? paragraphs[2].text : \"N/A\";\n                                \n                                // Only add if both size and cost are available (not \"N/A\")\n                                if (size !== \"N/A\" && cost !== \"N/A\") {\n                                    let resolutionText = paragraphs[0].text;\n                                    archives.push({\n                                        id: `h@h_${resolutionText.toLowerCase().replace('x', '')}`,\n                                        title: `H@H ${resolutionText}`,\n                                        description: `Cost: ${cost}, Size: ${size}`,\n                                    });\n                                }\n                                // If size or cost is \"N/A\", we simply skip this option\n                            }\n                        }\n                    }\n                }\n                \n                // Original Download\n                let origin = body.children[index]?.children[0];\n                if (origin) {\n                    let originCost = origin.querySelector(\"div > strong\")?.text || \"Unknown\";\n                    let originSize = origin.querySelector(\"p > strong\")?.text || \"Unknown\";\n                    archives.push({\n                        id: '0',\n                        title: 'Original',\n                        description: `Cost: ${originCost}, Size: ${originSize}`,\n                    });\n                }\n                \n                // Resample Download\n                let resample = body.children[index]?.children[1];\n                if (resample) {\n                    let resampleCost = resample.querySelector(\"div > strong\")?.text || \"Unknown\";\n                    let resampleSize = resample.querySelector(\"p > strong\")?.text || \"Unknown\";\n                    archives.push({\n                        id: '1',\n                        title: 'Resample',\n                        description: `Cost: ${resampleCost}, Size: ${resampleSize}`,\n                    });\n                }\n                \n                document.dispose()\n                return archives\n            },\n            getDownloadUrl: async (cid, aid) => {\n                let urlParseResult = this.parseUrl(cid)\n                let gid = urlParseResult.id\n                let token = urlParseResult.token\n                \n                // Handle H@H Download options\n                if (aid.startsWith('h@h_')) {\n                    let resolution = aid.substring(4); // Remove 'h@h_' prefix\n                    \n                    // For H@H downloads, send the command directly to archiver.php\n                    let hathRes = await Network.post(`${this.baseUrl}/archiver.php?gid=${gid}&token=${token}`, {\n                        \"Content-Type\": \"application/x-www-form-urlencoded\",\n                    }, `hathdl_xres=${resolution}`)\n                    \n                    if (hathRes.status !== 200) {\n                        throw `Failed to send H@H download command: ${hathRes.status}`\n                    }\n                    \n                    // Parse response for any error messages\n                    let hathDocument = new HtmlDocument(hathRes.body)\n                    let errorElement = hathDocument.querySelector(\"p.br\")\n                    \n                    if (errorElement) {\n                        let errorMessage = errorElement.text\n                        hathDocument.dispose()\n                        \n                        if (errorMessage.includes(\"H@H client\")) {\n                            throw \"You need an H@H client associated with your account to use this feature\"\n                        } else if (errorMessage.includes(\"offline\")) {\n                            throw \"Your H@H client appears to be offline. Please start it and try again\"\n                        } else if (errorMessage.includes(\"resolution\")) {\n                            throw \"This gallery cannot be downloaded at the selected resolution\"\n                        } else {\n                            throw errorMessage\n                        }\n                    }\n                    \n                    // Check for success message or assume success if no error\n                    let successMessage = hathDocument.querySelector(\"p\")?.text\n                    hathDocument.dispose()\n                    \n                    let resolutionText = resolution === 'org' ? 'Original' : \n                                       resolution === '800' ? '800x' :\n                                       resolution === '1280' ? '1280x' :\n                                       resolution === '1920' ? '1920x' :\n                                       resolution === '2560' ? '2560x' : resolution;\n                    \n                    // For H@H downloads, return a special value to indicate remote download\n                    // This should close the window without creating a local download task\n                    // let message = successMessage && successMessage.includes(\"successfully\") \n                    //     ? `H@H download command sent successfully (${resolutionText}). Check your H@H client.`\n                    //     : `H@H download command sent (${resolutionText}). Check your H@H client.`;\n                    \n                    // // Show success message to user\n                    // UI.showMessage(message);\n                    \n                    // Return empty string to avoid type error and prevent download task creation\n                    return \"\";\n                }\n                \n                // Handle regular downloads (Original and Resample)\n                let data;\n                switch(aid) {\n                    case '0':\n                        data = \"dltype=org&dlcheck=Download+Original+Archive\";\n                        break;\n                    case '1':\n                        data = \"dltype=res&dlcheck=Download+Resample+Archive\";\n                        break;\n                    default:\n                        throw \"Invalid archive type\";\n                }\n                \n                let res = await Network.post(`${this.baseUrl}/archiver.php?gid=${gid}&token=${token}`, {\n                    \"Content-Type\": \"application/x-www-form-urlencoded\",\n                }, data)\n                if (res.status !== 200) {\n                    throw `Invalid status code: ${res.status}`\n                }\n                let document = new HtmlDocument(res.body)\n                let link = document.querySelector(\"a\")?.attributes[\"href\"]\n                if(!link) {\n                    throw \"Failed to get download link\"\n                }\n                let res2 = await Network.get(link, {\n                    'http_client': 'dart:io' // The server is uncomfortable with the default client\n                })\n                document.dispose()\n                document = new HtmlDocument(res2.body)\n                let link2 = document.querySelector(\"a\")?.attributes[\"href\"]\n                document.dispose()\n                function getHost(url) {\n                    const regex = /^(?:https?:\\/\\/)?(?:www\\.)?([^\\/]+)/i;\n                    const match = url.match(regex);\n                    return match ? match[1] : null;\n                }\n                let host = getHost(link)\n                let resultLink = \"https://\" + host + link2\n                let test = await Network.sendRequest('HEAD', resultLink, {\n                    'http_client': 'dart:io',\n                }, null)\n                if(test.status === 410) {\n                    throw \"IP quota exhausted.\"\n                }\n                return resultLink\n            },\n        },\n        /**\n         * [Optional] Handle tag click event\n         * @param namespace {string}\n         * @param tag {string}\n         * @returns {{action: string, keyword: string, param: string?}}\n         */\n        onClickTag: (namespace, tag) => {\n            if (namespace == \"Category\") {\n                const categories = [\"misc\", \"doujinshi\", \"manga\", \"artist cg\", \"game cg\", \"image set\", \"cosplay\", \"asian porn\", \"non-h\", \"western\"];\n                return {\n                    page: \"search\",\n                    attributes: {\n                        'keyword': \"\",\n                        'options': [categories.indexOf(tag.toLowerCase()).toString(), \"\", \"\"]\n                    }\n                };\n            }\n            if(tag.includes(' ')) {\n                tag = `\"${tag}\"`\n            }\n            return {\n                // 'search' or 'category'\n                action: 'search',\n                keyword: `${namespace}:${tag}`,\n                // {string?} only for category action\n                param: null,\n            }\n        },\n        /**\n         * [Optional] Search similar comics\n         * @param title {string} - comic title\n         * @param subTitle {string?} - comic subtitle\n         * @returns {string?} - search keyword, return null to hide the button\n         */\n        searchSimilar: (title, subTitle) => {\n            let t = subTitle || title;\n            t = t.replace(/\\[.*?\\]/g, \"\").replace(/\\(.*?\\)/g, \"\");\n            if (t.trim() === \"\") {\n                return null;\n            }\n            return `\"${t.trim()}\"`;\n        },\n        /**\n         * [Optional] Handle links\n         */\n        link: {\n            /**\n             * set accepted domains\n             */\n            domains: [\n                'e-hentai.org',\n                'exhentai.org'\n            ],\n            /**\n             * parse url to comic id\n             * @param url {string}\n             * @returns {string | null}\n             */\n            linkToId: (url) => {\n                if(url.includes('?')) {\n                    url = url.split('?')[0]\n                }\n                let reg = RegExp(\"https?://(e-|ex)hentai.org/g/(\\\\d+)/(\\\\w+)/?$\")\n                let match = reg.exec(url)\n                if(match) {\n                    return `${this.baseUrl}/g/${match[2]}/${match[3]}/`\n                }\n                return null\n            }\n        },\n        enableTagsTranslate: true,\n    }\n\n\n    /*\n    [Optional] settings related\n    Use this.loadSetting to load setting\n    ```\n    let setting1Value = this.loadSetting('setting1')\n    console.log(setting1Value)\n    ```\n     */\n    settings = {\n        domain: {\n            // title\n            title: \"\u57df\u540d\",\n            // type: input, select, switch\n            type: \"select\",\n            // options\n            options: [\n                {\n                    value: 'e-hentai.org',\n                },\n                {\n                    value: 'exhentai.org',\n                },\n            ],\n            default: 'e-hentai.org',\n        },\n        ehevent: {\n            title: \"\u89e6\u53d1\u9ece\u660e\u4e8b\u4ef6\",\n            type: \"switch\",\n            default: false\n        },\n        hvevent: {\n            title: \"\u63d0\u793aHV\u906d\u9047\u6218\",\n            type: \"switch\",\n            default: false\n        },\n    }\n\n    // [Optional] translations for the strings in this config\n    translation = {\n        'zh_CN': {\n            \"domain\": \"\u57df\u540d\",\n            \"ehevent\": \"\u89e6\u53d1\u9ece\u660e\u4e8b\u4ef6\",\n            \"hvevent\": \"\u63d0\u793aHV\u906d\u9047\u6218\",\n            \"hentaiverse\": \"\u4f60\u9047\u5230\u4e86\u602a\u7269\uff01\",\n            \"fight\":\"\u6218\u6597\",\n            \"cancel\":\"\u53d6\u6d88\",\n            \"language\": \"\u8bed\u8a00\",\n            \"artist\": \"\u753b\u5e08\",\n            \"male\": \"\u7537\u6027\",\n            \"female\": \"\u5973\u6027\",\n            \"mixed\": \"\u6df7\u5408\",\n            \"other\": \"\u5176\u5b83\",\n            \"parody\": \"\u539f\u4f5c\",\n            \"character\": \"\u89d2\u8272\",\n            \"group\": \"\u56e2\u961f\",\n            \"cosplayer\": \"Coser\",\n            \"reclass\": \"\u91cd\u65b0\u5206\u7c7b\",\n            \"uploader\": \"\u4e0a\u4f20\u8005\",\n            \"Languages\": \"\u8bed\u8a00\",\n            \"Artists\": \"\u753b\u5e08\",\n            \"Characters\": \"\u89d2\u8272\",\n            \"Groups\": \"\u56e2\u961f\",\n            \"Tags\": \"\u6807\u7b7e\",\n            \"Parodies\": \"\u539f\u4f5c\",\n            \"Categories\": \"\u5206\u7c7b\",\n            \"Category\": \"\u5206\u7c7b\",\n            \"Min Stars\": \"\u6700\u5c11\u661f\u661f\",\n            \"Language\": \"\u8bed\u8a00\",\n            \"H@H Original\": \"H@H \u539f\u7248\",\n            \"H@H 800x\": \"H@H 800x\",\n            \"H@H 1280x\": \"H@H 1280x\", \n            \"H@H 1920x\": \"H@H 1920x\",\n            \"H@H 2560x\": \"H@H 2560x\",\n            \"Original\": \"\u539f\u7248\",\n            \"Resample\": \"\u91cd\u91c7\u6837\",\n        },\n        'zh_TW': {\n            'domain': '\u57df\u540d',\n            \"ehevent\": \"\u89f8\u767c\u9ece\u660e\u4e8b\u4ef6\",\n            \"hvevent\": \"\u63d0\u793aHV\u906d\u9047\u6230\",\n            \"hentaiverse\": \"\u4f60\u9047\u5230\u4e86\u602a\u7269\uff01\",\n            \"fight\":\"\u6230\u9b25\",\n            \"cancel\":\"\u53d6\u6d88\",\n            \"language\": \"\u8a9e\u8a00\",\n            \"artist\": \"\u756b\u5e2b\",\n            \"male\": \"\u7537\u6027\",\n            \"female\": \"\u5973\u6027\",\n            \"mixed\": \"\u6df7\u5408\",\n            \"other\": \"\u5176\u4ed6\",\n            \"parody\": \"\u539f\u4f5c\",\n            \"character\": \"\u89d2\u8272\",\n            \"group\": \"\u5718\u968a\",\n            \"cosplayer\": \"Coser\",\n            \"reclass\": \"\u91cd\u65b0\u5206\u985e\",\n            \"uploader\": \"\u4e0a\u50b3\u8005\",\n            \"Languages\": \"\u8a9e\u8a00\",\n            \"Artists\": \"\u756b\u5e2b\",\n            \"Characters\": \"\u89d2\u8272\",\n            \"Groups\": \"\u5718\u968a\",\n            \"Tags\": \"\u6a19\u7c64\",\n            \"Parodies\": \"\u539f\u4f5c\",\n            \"Categories\": \"\u5206\u985e\",\n            \"Category\": \"\u5206\u985e\",\n            \"Min Stars\": \"\u6700\u5c11\u661f\u661f\",\n            \"Language\": \"\u8a9e\u8a00\",\n            \"H@H Original\": \"H@H \u539f\u7248\",\n            \"H@H 800x\": \"H@H 800x\",\n            \"H@H 1280x\": \"H@H 1280x\",\n            \"H@H 1920x\": \"H@H 1920x\", \n            \"H@H 2560x\": \"H@H 2560x\",\n            \"Original\": \"\u539f\u7248\",\n            \"Resample\": \"\u91cd\u63a1\u6a23\",\n        },\n        'en_US': {\n            \"domain\": \"Domain\",\n            \"ehevent\": \"Trigger Dawn Event\",\n            \"hvevent\": \"HV Encounter Alert\",\n            \"hentaiverse\": \"You have encountered a monster!\",\n            \"fight\": \"Fight\",\n            \"cancel\": \"Cancel\",\n            \"language\": \"Language\",\n            \"artist\": \"Artist\",\n            \"male\": \"Male\",\n            \"female\": \"Female\",\n            \"mixed\": \"Mixed\",\n            \"other\": \"Other\",\n            \"parody\": \"Parody\",\n            \"character\": \"Character\",\n            \"group\": \"Group\",\n            \"cosplayer\": \"Cosplayer\",\n            \"reclass\": \"Reclass\",\n            \"uploader\": \"Uploader\",\n            \"Languages\": \"Languages\",\n            \"Artists\": \"Artists\",\n            \"Characters\": \"Characters\",\n            \"Groups\": \"Groups\",\n            \"Tags\": \"Tags\",\n            \"Parodies\": \"Parodies\",\n            \"Categories\": \"Categories\",\n            \"Category\": \"Category\",\n            \"Min Stars\": \"Min Stars\",\n            \"Language\": \"Language\",\n            \"H@H Original\": \"H@H Original\",\n            \"H@H 800x\": \"H@H 800x\",\n            \"H@H 1280x\": \"H@H 1280x\",\n            \"H@H 1920x\": \"H@H 1920x\",\n            \"H@H 2560x\": \"H@H 2560x\",\n            \"Original\": \"Original\",\n            \"Resample\": \"Resample\"\n        },\n    }\n}\n";
__SOURCE_CODE["htmanga"] = "/** @type {import('./_venera_.js')} */\n\n// Port of built_in/ht_manga.dart and network/htmanga_network.\nclass WnacgComicSource extends ComicSource {\n    name = '\u7ec5\u58eb\u6f2b\u753b'\n    key = 'htmanga'\n    version = '2.0.0'\n    minAppVersion = '4.9.3'\n    url = 'https://raw.githubusercontent.com/ccbkv/pica_configs/refs/heads/master/wnacg.js'\n\n    // Set a URL here to override the native API selection; leave empty to use it.\n    apiOverride = ''\n    // Used on hosts without the htmanga_state bridge.\n    fallbackApi = 'https://www.wnacg.com'\n\n    _favoriteIds = new Map()\n    _thumbnailPages = new Map()\n    // \u7528\u6237\u6295\u7a3f\u5217\u8868\u5feb\u7167 URL \u7f13\u5b58: \u9996\u9875\u8def\u5f84 -> (\u9875\u7801 -> \u5e26 c \u53c2\u6570\u7684\u641c\u7d22 URL)\n    _userSearchUrls = new Map()\n\n    get baseUrl() {\n        if (this.apiOverride.trim()) return this.normalizeApi(this.apiOverride)\n        const state = sendMessage({ method: 'htmanga_state', key: this.key })\n        return this.normalizeApi(state?.baseUrl || this.fallbackApi)\n    }\n\n    normalizeApi(value) {\n        let url = String(value).trim().replace(/\\/+$/, '')\n        if (!/^https?:\\/\\//i.test(url)) url = 'https://' + url\n        if (!/^https?:\\/\\/[a-z0-9.-]+(?::\\d+)?(?:\\/[^?#\\s]*)?$/i.test(url)) {\n            throw new Error('Invalid URL')\n        }\n        return url\n    }\n\n    absoluteUrl(path) {\n        path = path.trim()\n        if (/^https?:\\/\\//i.test(path)) return path.replace(/^(https?:)\\/{2,}/i, '$1//')\n        if (path.startsWith('//')) return 'https://' + path.replace(/^\\/+/, '')\n        return this.baseUrl + '/' + path.replace(/^\\/+/, '')\n    }\n\n    get headers() {\n        return {\n            'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.0.0 Safari/537.36',\n            'Referer': this.baseUrl + '/',\n        }\n    }\n\n    async get(path) {\n        const res = await Network.get(this.absoluteUrl(path), this.headers)\n        if (res.status !== 200) throw new Error('HTTP ' + res.status)\n        const body = String(res.body ?? '')\n        // Older hosts do not expose Dio's realUri; recognize the redirected login form.\n        if (/users-login/.test(res.url || '') ||\n            (/<input\\b[^>]*\\bname\\s*=\\s*[\"']login_name[\"']/i.test(body) &&\n             /<input\\b[^>]*\\bname\\s*=\\s*[\"']login_pass[\"']/i.test(body))) {\n            throw new Error('Login expired: \u672a\u767b\u5f55\u6216\u767b\u5f55\u5230\u671f')\n        }\n        return body\n    }\n\n    async post(path, data) {\n        const res = await Network.post(this.absoluteUrl(path), {\n            ...this.headers,\n            'Content-Type': 'application/x-www-form-urlencoded; charset=UTF-8',\n        }, data)\n        if (res.status !== 200) throw new Error('HTTP ' + res.status)\n        return res.body\n    }\n\n    compact(value) {\n        return value.replace(/[\\n \\t]/g, '')\n    }\n\n    comicId(link) {\n        if (/^\\d+$/.test(String(link))) return String(link)\n        const match = /-aid-(\\d+)/.exec(link)\n        if (!match) throw new Error('Invalid comic ID')\n        return match[1]\n    }\n\n    parseBrief(element, home = false) {\n        const link = element.querySelector('div.pic_box > a').attributes.href\n        const image = element.querySelector('div.pic_box > a > img').attributes.src\n        const title = element.querySelector('div.info > div.title > a')\n        const info = element.querySelector('div.info > div.info_col').text\n        return {\n            id: this.comicId(link),\n            title: (home ? title.text : (title.attributes.title ?? title.text)\n                .replace(/<\\/?em>/g, '')).trim(),\n            subTitle: '',\n            cover: this.absoluteUrl(image),\n            // Preserve count units: the host adds \"P\" to numeric pages.\n            description: info.trim().replace(/[\\n\\t]/g, ''),\n            tags: [],\n        }\n    }\n\n    listUrl(url, page) {\n        url = this.absoluteUrl(url)\n        if (page === 1) return url\n        if (url.includes('search')) return url + '&p=' + page\n        if (url.includes('ranking')) return url.replace(/ranking/g, 'ranking-page-' + page)\n        if (!url.includes('-')) url = url.replace(/\\.html/g, '-.html')\n        url = url.replace(/index/g, '')\n        const parts = url.split('albums-')\n        if (parts.length < 2) throw new Error('Invalid albums URL')\n        return parts[0] + 'albums-index-page-' + page + parts[1]\n    }\n\n    lastPage(document, fallback) {\n        const links = document.querySelectorAll('div.f_left.paginator > a')\n        const text = links.length ? links[links.length - 1].text.trim() : ''\n        return /^\\d+$/.test(text) ? Number(text) : fallback\n    }\n\n    async getComicList(url, page = 1, searchPage = false) {\n        const document = new HtmlDocument(await this.get(this.listUrl(url, page)))\n        try {\n            const elements = document.querySelectorAll('div.grid div.gallary_wrap > ul.cc > li')\n            const comics = []\n            for (const element of elements) {\n                try { comics.push(this.parseBrief(element)) } catch (_) {}\n            }\n            let maxPage = 1\n            if (searchPage) {\n                const result = document.querySelector('p.result > b')?.text.replace(/\\D/g, '')\n                if (result && elements.length) maxPage = Math.floor(Number(result) / elements.length) + 1\n            } else {\n                maxPage = this.lastPage(document, 1)\n            }\n            // \u5f53\u524d\u9875\u7801\u6807\u8bb0(\u7528\u4e8e\u68c0\u6d4b\u670d\u52a1\u5668\u56de\u843d)\u4e0e\u5206\u9875\u5668\u4e2d\u7684\u5feb\u7167\u94fe\u63a5\n            // (\u641c\u7d22\u9875\u7ffb\u9875\u9700\u5e26 c \u53c2\u6570\u4ee5\u56fa\u5b9a\u7ed3\u679c\u5feb\u7167, \u5426\u5219\u7ed3\u679c\u96c6\u4f1a\u88ab\u65b0\u4e0a\u4f20\u6324\u52a8)\n            let thisPage = null\n            const thisPageEl = document.querySelector('div.f_left.paginator span.thispage')\n            if (thisPageEl) thisPage = Number(thisPageEl.text)\n            const pageUrls = {}\n            for (const a of document.querySelectorAll('div.f_left.paginator a')) {\n                const href = a.attributes.href\n                if (!href || !href.includes('/search/index.php')) continue\n                const pm = /[?&]p=(\\d+)/.exec(href)\n                if (pm) pageUrls[Number(pm[1])] = href\n            }\n            return { comics, maxPage, thisPage, pageUrls }\n        } finally {\n            document.dispose()\n        }\n    }\n\n    /**\n     * \u7528\u6237\u6295\u7a3f\u9875\u5217\u8868.\n     * \u7528\u6237\u9875\u672c\u8eab\u662f cursor \u94fe\u5f0f\u5206\u9875(\u4e0b\u4e00\u9875 URL \u4f9d\u8d56\u4e0a\u4e00\u9875\u672b\u6761 aid, \u65e0\u6cd5\u6309\u9875\u7801\u8bf7\u6c42),\n     * \u4f46\u641c\u7d22\u63a5\u53e3 /search/index.php?q=*&u={uid} \u4e0e\u7528\u6237\u6295\u7a3f\u9875\u5185\u5bb9\u4e00\u81f4\u4e14\u652f\u6301\u6570\u5b57\u5206\u9875(&p=N),\n     * \u6545\u8f6c\u6362\u4e3a\u641c\u7d22\u63a5\u53e3\u5b9e\u73b0.\n     * \u641c\u7d22\u7ed3\u679c\u7684\u5206\u9875\u5668\u94fe\u63a5\u5e26 c \u53c2\u6570(\u7ed3\u679c\u5feb\u7167 ID), \u7528\u5176\u7ffb\u9875\u53ef\u4fdd\u8bc1\u540c\u4e00\u5feb\u7167\u5185\u5185\u5bb9\u7a33\u5b9a\n     * (\u4e0e\u6d4f\u89c8\u5668\u884c\u4e3a\u4e00\u81f4); \u4e0d\u5e26 c \u6bcf\u6b21\u91cd\u65b0\u6267\u884c\u641c\u7d22, \u6df1\u9875\u5185\u5bb9\u4f1a\u88ab\u65b0\u4e0a\u4f20\u6324\u52a8.\n     * \u7528\u6237\u4f5c\u54c1\u91cf\u5de8\u5927(\u53ef\u80fd\u4e0a\u767e\u4e07), \u65e0\u6cd5\u5f97\u77e5\u603b\u9875\u6570, maxPage \u8fd4\u56de null (UI \u663e\u793a \"1/?\",\n     * \u7ffb\u51fa\u7a7a\u9875\u5373\u89c6\u4e3a\u5230\u8fbe\u672b\u9875).\n     * @param firstUrl {string} \u9996\u9875\u8def\u5f84 (/albums-user-uid-X.html)\n     * @param page {number} \u9875\u7801 (1-based)\n     */\n    async getUserComics(firstUrl, page) {\n        const m = /albums-user-uid-(\\d+)/.exec(firstUrl)\n        if (!m) return { comics: [], maxPage: null }\n        // \u56de\u5230\u7b2c\u4e00\u9875\u65f6\u6e05\u9664\u5feb\u7167\u7f13\u5b58(\u5f00\u542f\u65b0\u5feb\u7167)\n        if (page === 1) this._userSearchUrls.delete(firstUrl)\n        let urls = this._userSearchUrls.get(firstUrl)\n        if (!urls) {\n            urls = new Map()\n            this._userSearchUrls.set(firstUrl, urls)\n        }\n        const base = '/search/index.php?q=%2A&m=&syn=yes&f=_all&s=create_time_DESC&u=' + m[1]\n        const plainUrl = base + (page > 1 ? '&p=' + page : '')\n        // \u4f18\u5148\u4f7f\u7528\u4e4b\u524d\u5206\u9875\u5668\u91cc\u7f13\u5b58\u7684\u5feb\u7167\u94fe\u63a5(\u5e26 c \u53c2\u6570)\n        let url = urls.get(page) ?? plainUrl\n        let res = await this.getComicList(url, 1)\n        // \u5feb\u7167\u8fc7\u671f\u65f6\u670d\u52a1\u5668\u4f1a\u9759\u9ed8\u56de\u843d(\u8fd4\u56de\u7b2c 1 \u9875\u5185\u5bb9), \u68c0\u6d4b\u5230\u5219\u6539\u7528\u666e\u901a URL \u91cd\u53d6\n        if (res.thisPage != null && res.thisPage !== page) {\n            res = await this.getComicList(plainUrl, 1)\n        }\n        // \u7f13\u5b58\u672c\u6b21\u54cd\u5e94\u5206\u9875\u5668\u4e2d\u7684\u5feb\u7167\u94fe\u63a5(\u8986\u76d6\u5f53\u524d\u9875\u9644\u8fd1\u7ea6 10 \u9875)\n        if (res.thisPage == null || res.thisPage === page) {\n            for (const k of Object.keys(res.pageUrls)) {\n                urls.set(Number(k), res.pageUrls[k])\n            }\n        }\n        return { comics: res.comics, maxPage: null }\n    }\n\n    account = {\n        registerWebsite: 'https://www.wnacg.com/albums.html',\n        login: async (account, pwd) => {\n            const body = await this.post('/users-check_login.html',\n                'login_name=' + encodeURIComponent(account) + '&login_pass=' + encodeURIComponent(pwd))\n            const data = typeof body === 'string' ? JSON.parse(body) : body\n            if (!String(data?.html || '').includes('\u767b\u9304\u6210\u529f')) {\n                throw new Error(data?.html || 'Login failed')\n            }\n            this._favoriteIds.clear()\n            this.saveData('name', account)\n            return true\n        },\n        logout: () => {\n            Network.deleteCookies(this.baseUrl)\n            this._favoriteIds.clear()\n            this.saveData('name', null)\n            this.saveData('account', null)\n        },\n    }\n\n    explore = [{\n        title: '\u7ec5\u58eb\u6f2b\u753b',\n        type: 'multiPartPage',\n        load: async () => {\n            const document = new HtmlDocument(await this.get(this.baseUrl))\n            try {\n                const titles = document.querySelectorAll('div.title_sort')\n                const blocks = document.querySelectorAll('div.bodywrap')\n                if (titles.length !== blocks.length) throw new Error('\u6f2b\u753b\u5757\u6570\u91cf\u548c\u6807\u9898\u6570\u91cf\u4e0d\u76f8\u7b49')\n                return titles.map((title, i) => {\n                    const name = this.compact(title.querySelector('div.title_h2').text)\n                    const link = title.querySelector('div.r > a').attributes.href\n                    return {\n                        title: name,\n                        comics: blocks[i].querySelectorAll('div.gallary_wrap > ul.cc > li')\n                            .map(element => this.parseBrief(element, true)),\n                        viewMore: { page: 'category', attributes: { category: name, param: link } },\n                    }\n                })\n            } finally {\n                document.dispose()\n            }\n        },\n    }]\n\n    category = {\n        title: '\u7ec5\u58eb\u6f2b\u753b',\n        key: 'htmanga',\n        enableRankingPage: true,\n        parts: [\n            { name: '\u6700\u65b0', type: 'fixed', categories: ['\u6700\u65b0\u6f2b\u753b'],\n                itemType: 'category', categoryParams: ['/albums.html'] },\n            { name: '\u540c\u4eba\u5fd7', type: 'fixed',\n                categories: ['\u540c\u4eba\u5fd7', '\u540c\u4eba\u5fd7-\u6c49\u5316', '\u540c\u4eba\u5fd7-\u65e5\u8bed', '\u540c\u4eba\u5fd7-English', '\u540c\u4eba\u5fd7-CG\u753b\u96c6', '\u540c\u4eba\u5fd7-3D\u6f2b\u753b', '\u540c\u4eba\u5fd7-Cosplay'],\n                itemType: 'category', categoryParams: [5, 1, 12, 16, 2, 22, 3].map(id => '/albums-index-cate-' + id + '.html') },\n            { name: '\u5355\u884c\u672c', type: 'fixed',\n                categories: ['\u5355\u884c\u672c', '\u5355\u884c\u672c-\u6c49\u5316', '\u5355\u884c\u672c-\u65e5\u8bed', '\u5355\u884c\u672c-English'],\n                itemType: 'category', categoryParams: [6, 9, 13, 17].map(id => '/albums-index-cate-' + id + '.html') },\n            { name: '\u6742\u5fd7&\u77ed\u7bc7', type: 'fixed',\n                categories: ['\u6742\u5fd7&\u77ed\u7bc7', '\u6742\u5fd7&\u77ed\u7bc7-\u6c49\u5316', '\u6742\u5fd7&\u77ed\u7bc7-\u65e5\u8bed', '\u6742\u5fd7&\u77ed\u7bc7-English'],\n                itemType: 'category', categoryParams: [7, 10, 14, 18].map(id => '/albums-index-cate-' + id + '.html') },\n            { name: '\u97e9\u6f2b', type: 'fixed', categories: ['\u97e9\u6f2b', '\u97e9\u6f2b-\u6c49\u5316', '\u97e9\u6f2b-\u5176\u5b83'],\n                itemType: 'category', categoryParams: [19, 20, 21].map(id => '/albums-index-cate-' + id + '.html') },\n        ],\n    }\n\n    categoryComics = {\n        load: async (category, param, options, page) => {\n            // \u4e0a\u4f20\u8005\u5361\u7247\u70b9\u51fb: param \u4e3a \"ca\", category \u4e3a\u7528\u6237\u6295\u7a3f\u9875\u8def\u5f84 (/albums-user-uid-X.html)\n            if (param === 'ca') {\n                return this.getUserComics(category, page)\n            }\n            return this.getComicList(param || '/albums.html', page)\n        },\n        ranking: {\n            // The host splits options at the first \"-\", so use these short keys.\n            options: ['day-\u4eca\u65e5', 'week-\u672c\u9031', 'month-\u672c\u6708'],\n            load: async (option, page) => this.getComicList(\n                '/albums-favorite_ranking-type-' + option + '.html', page),\n        },\n    }\n\n    search = {\n        load: async (keyword, options, page) => this.getComicList(\n            '/search/?q=' + encodeURIComponent(keyword) + '&f=_all&s=create_time_DESC&syn=yes', page, true),\n    }\n\n    comic = {\n        loadInfo: async (id) => {\n            id = this.comicId(id)\n            // Keep collection chapters in ascending list order regardless of site preferences.\n            let document = new HtmlDocument(await this.get(\n                '/photos-index-page-1-aid-' + id + '.html?order=asc&mode=list'))\n            try {\n                const title = document.querySelector('div.userwrap > h2').text\n                const cover = document.querySelector('div.userwrap > div.asTB > div.asTBcell.uwthumb > img').attributes.src\n                const labels = document.querySelectorAll('div.asTBcell.uwconn > label')\n                const category = labels[0].text.split('\uff1a')[1]\n                const isSeries = document.querySelector('#sr_pub') !== null\n                const count = labels[1].text.split('\uff1a')[1]\n                const pages = isSeries ? undefined : Number(/\\d+/.exec(count)[0])\n                const tags = {}\n                for (const tag of document.querySelectorAll(\n                    isSeries ? 'div.asTBcell.uwconn a.tagshow' : 'a.tagshow')) {\n                    tags[tag.text] = tag.attributes.href\n                }\n                const uploader = document.querySelector('div.asTBcell.uwuinfo > a > p').text\n                const avatar = document.querySelector('div.asTBcell.uwuinfo > a > img').attributes.src\n                // \u4e0a\u4f20\u8005\u7528\u6237\u6295\u7a3f\u9875\u94fe\u63a5 (/albums-user-uid-X.html), \u4f9b\u70b9\u51fb\u4e0a\u4f20\u8005\u5361\u7247\u8df3\u8f6c\n                const uploaderLink = document.querySelector('div.asTBcell.uwuinfo > a').attributes.href ?? ''\n                const uploadNum = Number(document.querySelector('div.asTBcell.uwuinfo > p > font').text)\n                const thumbnailMaxPage = isSeries ? 0 : Math.ceil(pages / 12)\n                this._thumbnailPages.set(id, thumbnailMaxPage)\n                const result = {\n                    title: this.compact(title), subTitle: uploader,\n                    cover: this.absoluteUrl(cover),\n                    description: document.querySelector('div.asTBcell.uwconn > p').text,\n                    tags: { ...(isSeries ? { '\u7ae0\u8282': [count] } : {}),\n                        '\u5206\u7c7b': [category], '\u6807\u7b7e': Object.keys(tags) },\n                    uploader: { id: uploaderLink, name: uploader, avatarUrl: this.absoluteUrl(avatar),\n                        slogan: '\u6295\u7a3f\u4f5c\u54c1' + uploadNum + '\u90e8',\n                        // PicaComic renders updateTime as the uploader card's second line.\n                        updateTime: '\u6295\u7a3f\u4f5c\u54c1' + uploadNum + '\u90e8' },\n                    pages, maxPage: pages, isFavorite: false,\n                    thumbnails: isSeries ? [] : document.querySelectorAll('div.pic_box.tb > a > img')\n                        .map(image => this.absoluteUrl(image.attributes.src)),\n                    thumbnailMaxPage,\n                    url: this.baseUrl + '/photos-index-page-1-aid-' + id + '.html',\n                }\n                if (isSeries) {\n                    const chapters = new Map()\n                    let page = 1\n                    let maxPage = 1\n                    while (true) {\n                        for (const chapter of document.querySelectorAll('a[data-chid]')) {\n                            chapters.set(chapter.attributes['data-chid'], chapter.text.trim())\n                        }\n                        for (const link of document.querySelectorAll('div.f_left.paginator a[href]')) {\n                            const match = /-page-(\\d+)-/.exec(link.attributes.href)\n                            if (match) maxPage = Math.max(maxPage, Number(match[1]))\n                        }\n                        if (page >= maxPage) break\n                        page++\n                        document.dispose()\n                        document = null\n                        document = new HtmlDocument(await this.get(\n                            '/photos-index-page-' + page + '-aid-' + id + '.html?order=asc&mode=list'))\n                    }\n                    if (chapters.size === 0) throw new Error('Invalid chapter directory')\n                    result.chapters = chapters\n                }\n                return result\n            } finally {\n                if (document) document.dispose()\n            }\n        },\n        loadThumbnails: async (id, next) => {\n            id = this.comicId(id)\n            // PicaComic sends \"0\" for the second thumbnail page.\n            const page = next == null ? 1 : Number(next) + 2\n            const document = new HtmlDocument(await this.get('/photos-index-page-' + page + '-aid-' + id + '.html'))\n            try {\n                if (document.querySelector('#sr_pub')) {\n                    return { thumbnails: [], next: null }\n                }\n                const thumbnails = document.querySelectorAll('div.pic_box.tb > a > img')\n                    .map(image => this.absoluteUrl(image.attributes.src))\n                const maxPage = this._thumbnailPages.get(id)\n                return { thumbnails, next: (maxPage != null ? page < maxPage : thumbnails.length === 12)\n                    ? String(page - 1) : null }\n            } finally {\n                document.dispose()\n            }\n        },\n        loadEp: async (id, epId) => {\n            const body = await this.get('/photos-gallery-aid-' + this.comicId(epId || id) + '.html')\n            // Same extraction as Dart; retain the ?verify= signature in full.\n            const images = []\n            const pattern = /\\/\\/([\\w./\\[\\]()?&=%+-]+)/g\n            let match\n            while ((match = pattern.exec(body)) !== null) images.push(this.absoluteUrl('//' + match[1]))\n            return { images }\n        },\n        onImageLoad: () => ({ headers: this.headers }),\n        onThumbnailLoad: () => ({ headers: this.headers }),\n        onClickTag: (namespace, tag) => ({ page: 'search', attributes: { keyword: tag } }),\n        idMatch: '^\\\\d+$',\n        link: {\n            domains: ['www.wnacg.com', 'wnacg.com'],\n            linkToId: url => /-aid-(\\d+)\\.html/.exec(url)?.[1] || null,\n        },\n    }\n\n    favoriteKey(id, folder) {\n        return JSON.stringify([this.baseUrl, this.loadData('name'), String(folder ?? '0'), String(id)])\n    }\n\n    favorites = {\n        multiFolder: true,\n        loadFolders: async (comicId) => {\n            const document = new HtmlDocument(await this.get('/users-addfav-id-210814.html'))\n            try {\n                // The JS adapter has no allFavoritesId; expose \"0\" only in folder browsing.\n                const folders = comicId == null ? { '0': '\u5168\u90e8' } : {}\n                for (const option of document.querySelectorAll('option')) {\n                    if (option.attributes.value) folders[option.attributes.value] = option.text\n                }\n                return { folders }\n            } finally {\n                document.dispose()\n            }\n        },\n        addFolder: async name => {\n            await this.post('/users-favc_save-id.html', 'favc_name=' + encodeURIComponent(name))\n            return true\n        },\n        deleteFolder: async id => {\n            if (String(id) === '0') throw new Error('\u4e0d\u80fd\u5220\u9664\u5168\u90e8\u6536\u85cf\u5165\u53e3')\n            await this.get('/users-favclass_del-id-' + id + '.html?ajax=true&_t=' + Math.random())\n            return true\n        },\n        loadComics: async (page, folder) => {\n            const document = new HtmlDocument(await this.get(\n                '/users-users_fav-page-' + page + '-c-' + (folder ?? '0') + '.html'))\n            try {\n                const comics = document.querySelectorAll('div.asTB').map(element => {\n                    const title = element.querySelector('div.box_cel.u_listcon > p.l_title > a')\n                    const id = this.comicId(title.attributes.href)\n                    const info = element.querySelector('div.box_cel.u_listcon > p.l_detla')?.text || ''\n                    const pageMatch = /\u9801\u6578\uff1a(\\d+)/.exec(info)\n                    const pages = pageMatch ? Number(pageMatch[1]) : undefined\n                    const onclick = element.querySelector('div.box_cel.u_listcon > p.alopt > a').attributes.onclick\n                    const favoriteId = /del-id-(\\d+)/.exec(onclick)[1]\n                    this._favoriteIds.set(this.favoriteKey(id, folder), favoriteId)\n                    return {\n                        id, title: title.text.trim(), subTitle: '', tags: [], pages, maxPage: pages, favoriteId,\n                        cover: this.absoluteUrl(element.querySelector('div.asTBcell.thumb > div > img').attributes.src),\n                        description: element.querySelector('div.box_cel.u_listcon > p.l_catg > span')\n                            .text.replace('\u5275\u5efa\u6642\u9593\uff1a', '').trim(),\n                    }\n                })\n                return { comics, maxPage: this.lastPage(document, page) }\n            } finally {\n                document.dispose()\n            }\n        },\n        addOrDelFavorite: async (id, folder, isAdding, favoriteId) => {\n            if (isAdding) {\n                await this.post('/users-save_fav-id-' + this.comicId(id) + '.html', 'favc_id=' + folder)\n            } else {\n                // PicaComic does not pass Comic.favoriteId through its JS adapter.\n                const context = JSON.parse(this.favoriteKey(id, folder))\n                const records = [...this._favoriteIds.entries()].filter(([key]) => {\n                    const value = JSON.parse(key)\n                    return value[0] === context[0] && value[1] === context[1] && value[3] === context[3]\n                })\n                let recordId = favoriteId\n                if (!recordId && folder && String(folder) !== '0') {\n                    recordId = this._favoriteIds.get(this.favoriteKey(id, folder))\n                }\n                if (!recordId) {\n                    // Some native favorite menus always pass \"0\". Never guess between records.\n                    const ids = new Set(records.map(([, value]) => value))\n                    if (ids.size === 1) recordId = [...ids][0]\n                }\n                if (!recordId) throw new Error('\u8bf7\u5237\u65b0\u6536\u85cf\u5217\u8868\u540e\u518d\u5220\u9664')\n                await this.get('/users-fav_del-id-' + recordId + '.html?ajax=true&_t=' + Math.random())\n                for (const [key, value] of records) {\n                    if (value === recordId) this._favoriteIds.delete(key)\n                }\n            }\n            return true\n        },\n    }\n\n    settings = {\n        htmanga: { type: 'native', page: 'htmanga' },\n    }\n}\n";
__SOURCE_CODE["baozi"] = "class Baozi extends ComicSource {\n    // \u6b64\u6f2b\u753b\u6e90\u7684\u540d\u79f0\n    name = \"\u5305\u5b50\u6f2b\u753b\"\n\n    // \u552f\u4e00\u6807\u8bc6\u7b26\n    key = \"baozi\"\n\n    version = \"1.1.0\"\n\n    minAppVersion = \"4.0.0\"\n\n    // \u66f4\u65b0\u94fe\u63a5\n    url = \"https://raw.githubusercontent.com/ccbkv/pica_configs/master/baozi.js\"\n\n/// APP\u542f\u52a8\u65f6\u6216\u8005\u6dfb\u52a0/\u66f4\u65b0\u6f2b\u753b\u6e90\u65f6\u6267\u884c\u6b64\u51fd\u6570\n  init() {}\n\n  settings = {\n  //language: \"cn\",\n    domains: {\n      title: \"\u4e3b\u57df\u540d\",\n      type: \"select\",\n      options: [\n        { value: \"baozimhcn.com\" },\n        { value: \"webmota.com\" },\n        { value: \"kukuc.co\" },\n        { value: \"twmanga.com\" },\n        { value: \"dinnerku.com\" },\n      ],\n      default: \"baozimhcn.com\",\n    },\n  };\n\n  // \u52a8\u6001\u751f\u6210\u5b8c\u6574\u57df\u540d\n  lang = 'cn';\n\n  get baseUrl(){\n    return `https://${this.lang}.baozimh.com`\n  }\n\n  /// \u8d26\u53f7\n  /// \u8bbe\u7f6e\u4e3anull\u7981\u7528\u8d26\u53f7\u529f\u80fd\n  account = {\n    /// \u767b\u5f55\n    /// \u8fd4\u56de\u4efb\u610f\u503c\u8868\u793a\u767b\u5f55\u6210\u529f\n    login: async (account, pwd) => {\n      let res = await Network.post(\n        \"https://baozimhcn.com/api/bui/signin\",\n        {\n          \"content-type\":\n            \"multipart/form-data; boundary=----WebKitFormBoundaryFUNUxpOwyUaDop8s\",\n        },\n        '------WebKitFormBoundaryFUNUxpOwyUaDop8s\\r\\nContent-Disposition: form-data; name=\"username\"\\r\\n\\r\\n' +\n          account +\n          '\\r\\n------WebKitFormBoundaryFUNUxpOwyUaDop8s\\r\\nContent-Disposition: form-data; name=\"password\"\\r\\n\\r\\n' +\n          pwd +\n          \"\\r\\n------WebKitFormBoundaryFUNUxpOwyUaDop8s--\\r\\n\"\n      );\n      if (res.status !== 200) {\n        throw \"Invalid status code: \" + res.status;\n      }\n      let json = JSON.parse(res.body);\n      let token = json.data;\n      Network.setCookies(\"https://baozimhcn.com\", [\n        Cookie('TSID', token, '.baozimh.com'),\n      ]);\n      return \"ok\";\n    },\n\n    // \u9000\u51fa\u767b\u5f55\u65f6\u5c06\u4f1a\u8c03\u7528\u6b64\u51fd\u6570\n    logout: function () {\n      Network.deleteCookies('.baozimh.com');\n    },\n\n    registerWebsite: \"https://baozimhcn.com/user/signup\",\n  };\n\n  /// \u89e3\u6790\u6f2b\u753b\u5217\u8868\n  parseComic(e) {\n    let url = e.querySelector(\"a\").attributes[\"href\"];\n    let id = url.split(\"/\").pop();\n    let title = e.querySelector(\"h3\").text.trim();\n    let cover = e.querySelector(\"a > amp-img\").attributes[\"src\"];\n    let tags = e.querySelectorAll(\"div.tabs > span\").map((e) => e.text.trim());\n    let description = e.querySelector(\"small\").text.trim();\n    return {\n      id: id,\n      title: title,\n      cover: cover,\n      tags: tags,\n      description: description,\n    };\n  }\n\n  parseJsonComic(e) {\n    return {\n      id: e.comic_id,\n      title: e.name,\n      subTitle: e.author,\n      cover: `https://static-tw.baozimh.com/cover/${e.topic_img}?w=285&h=375&q=100`,\n      tags: e.type_names,\n    };\n  }\n\n  /// \u63a2\u7d22\u9875\u9762\n  /// \u4e00\u4e2a\u6f2b\u753b\u6e90\u53ef\u4ee5\u6709\u591a\u4e2a\u63a2\u7d22\u9875\u9762\n  explore = [\n    {\n      /// \u6807\u9898\n      /// \u6807\u9898\u540c\u65f6\u7528\u4f5c\u6807\u8bc6\u7b26, \u4e0d\u80fd\u91cd\u590d\n      title: \"\u5305\u5b50\u6f2b\u753b\",\n\n      /// singlePageWithMultiPart \u6216\u8005 multiPageComicList\n      type: \"singlePageWithMultiPart\",\n\n      load: async () => {\n        var res = await Network.get(\"https://baozimhcn.com\");\n        if (res.status !== 200) {\n          throw \"Invalid status code: \" + res.status;\n        }\n        let document = new HtmlDocument(res.body);\n        let parts = document.querySelectorAll(\"div.index-recommend-items\");\n        let result = {};\n        for (let part of parts) {\n          let title = part.querySelector(\"div.catalog-title\").text.trim();\n          let comics = part\n            .querySelectorAll(\"div.comics-card\")\n            .map((e) => this.parseComic(e));\n          if (comics.length > 0) {\n            result[title] = comics;\n          }\n        }\n        return result;\n      },\n    },\n  ];\n\n  /// \u5206\u7c7b\u9875\u9762\n  /// \u4e00\u4e2a\u6f2b\u753b\u6e90\u53ea\u80fd\u6709\u4e00\u4e2a\u5206\u7c7b\u9875\u9762, \u4e5f\u53ef\u4ee5\u6ca1\u6709, \u8bbe\u7f6e\u4e3anull\u7981\u7528\u5206\u7c7b\u9875\u9762\n  category = {\n    /// \u6807\u9898, \u540c\u65f6\u4e3a\u6807\u8bc6\u7b26, \u4e0d\u80fd\u4e0e\u5176\u4ed6\u6f2b\u753b\u6e90\u7684\u5206\u7c7b\u9875\u9762\u91cd\u590d\n    title: \"\u5305\u5b50\u6f2b\u753b\",\n    parts: [\n      {\n        name: \"\u7c7b\u578b\",\n\n        // fixed \u6216\u8005 random\n        // random\u7528\u4e8e\u5206\u7c7b\u6570\u91cf\u76f8\u5f53\u591a\u65f6, \u968f\u673a\u663e\u793a\u5176\u4e2d\u4e00\u90e8\u5206\n        type: \"fixed\",\n\n        // \u5982\u679c\u7c7b\u578b\u4e3arandom, \u9700\u8981\u63d0\u4f9b\u6b64\u5b57\u6bb5, \u8868\u793a\u540c\u65f6\u663e\u793a\u7684\u6570\u91cf\n        // randomNumber: 5,\n\n        categories: [\n          \"\u5168\u90e8\",\n          \"\u604b\u7231\",\n          \"\u7eaf\u7231\",\n          \"\u53e4\u98ce\",\n          \"\u5f02\u80fd\",\n          \"\u60ac\u7591\",\n          \"\u5267\u60c5\",\n          \"\u79d1\u5e7b\",\n          \"\u5947\u5e7b\",\n          \"\u7384\u5e7b\",\n          \"\u7a7f\u8d8a\",\n          \"\u5192\u9669\",\n          \"\u63a8\u7406\",\n          \"\u6b66\u4fa0\",\n          \"\u683c\u6597\",\n          \"\u6218\u4e89\",\n          \"\u70ed\u8840\",\n          \"\u641e\u7b11\",\n          \"\u5927\u5973\u4e3b\",\n          \"\u90fd\u5e02\",\n          \"\u603b\u88c1\",\n          \"\u540e\u5bab\",\n          \"\u65e5\u5e38\",\n          \"\u97e9\u6f2b\",\n          \"\u5c11\u5e74\",\n          \"\u5176\u5b83\",\n        ],\n\n        // category\u6216\u8005search\n        // \u5982\u679c\u4e3acategory, \u70b9\u51fb\u540e\u5c06\u8fdb\u5165\u5206\u7c7b\u6f2b\u753b\u9875\u9762, \u4f7f\u7528\u4e0b\u65b9\u7684`categoryComics`\u52a0\u8f7d\u6f2b\u753b\n        // \u5982\u679c\u4e3asearch, \u5c06\u8fdb\u5165\u641c\u7d22\u9875\u9762\n        itemType: \"category\",\n\n        // \u82e5\u63d0\u4f9b, \u6570\u91cf\u9700\u8981\u548c`categories`\u4e00\u81f4, `categoryComics.load`\u65b9\u6cd5\u5c06\u4f1a\u6536\u5230\u6b64\u53c2\u6570\n        categoryParams: [\n          \"all\",\n          \"lianai\",\n          \"chunai\",\n          \"gufeng\",\n          \"yineng\",\n          \"xuanyi\",\n          \"juqing\",\n          \"kehuan\",\n          \"qihuan\",\n          \"xuanhuan\",\n          \"chuanyue\",\n          \"mouxian\",\n          \"tuili\",\n          \"wuxia\",\n          \"gedou\",\n          \"zhanzheng\",\n          \"rexie\",\n          \"gaoxiao\",\n          \"danuzhu\",\n          \"dushi\",\n          \"zongcai\",\n          \"hougong\",\n          \"richang\",\n          \"hanman\",\n          \"shaonian\",\n          \"qita\",\n        ],\n      },\n    ],\n    enableRankingPage: false,\n  };\n\n  /// \u5206\u7c7b\u6f2b\u753b\u9875\u9762, \u5373\u70b9\u51fb\u5206\u7c7b\u6807\u7b7e\u540e\u8fdb\u5165\u7684\u9875\u9762\n  categoryComics = {\n    load: async (category, param, options, page) => {\n      let res = await Network.get(`https://baozimhcn.com/api/bzmhq/amp_comic_list?type=${param}&region=${options[0]}&state=${options[1]}&filter=%2a&page=${page}&limit=36&language=${this.lang}&__amp_source_origin=https%3A%2F%2Fbaozimhcn.com`);\n      if (res.status !== 200) {\n        throw \"Invalid status code: \" + res.status;\n      }\n      let maxPage = null;\n      let json = JSON.parse(res.body);\n      if (!json.next) {\n        maxPage = page;\n      }\n      return {\n        comics: json.items.map((e) => this.parseJsonComic(e)),\n        maxPage: maxPage,\n      };\n    },\n    // \u63d0\u4f9b\u9009\u9879\n    optionList: [\n      {\n        options: [\"all-\u5168\u90e8\", \"cn-\u56fd\u6f2b\", \"jp-\u65e5\u672c\", \"kr-\u97e9\u56fd\", \"en-\u6b27\u7f8e\"],\n      },\n      {\n        options: [\"all-\u5168\u90e8\", \"serial-\u8fde\u8f7d\u4e2d\", \"pub-\u5df2\u5b8c\u7ed3\"],\n      },\n    ],\n  };\n\n  /// \u641c\u7d22\n  search = {\n    load: async (keyword, options, page) => {\n      let res = await Network.get(`https://baozimhcn.com/search?q=${keyword}`);\n      if (res.status !== 200) {\n        throw \"Invalid status code: \" + res.status;\n      }\n      let document = new HtmlDocument(res.body);\n      let comics = document\n        .querySelectorAll(\"div.comics-card\")\n        .map((e) => this.parseComic(e));\n      return {\n        comics: comics,\n        maxPage: 1,\n      };\n    },\n\n    // \u63d0\u4f9b\u9009\u9879\n    optionList: [],\n  };\n\n  /// \u6536\u85cf\n  favorites = {\n    /// \u662f\u5426\u4e3a\u591a\u6536\u85cf\u5939\n    multiFolder: false,\n    /// \u6dfb\u52a0\u6216\u8005\u5220\u9664\u6536\u85cf\n    addOrDelFavorite: async (comicId, folderId, isAdding) => {\n      if (!isAdding) {\n        let res = await Network.post(\n        `https://baozimhcn.com/user/operation_v2?op=del_bookmark&comic_id=${comicId}`\n      );\n        if (!res.status || res.status >= 400) {\n          throw \"Invalid status code: \" + res.status;\n        }\n        return \"ok\";\n      } else {\n        let res = await Network.post(\n          `https://baozimhcn.com/user/operation_v2?op=set_bookmark&comic_id=${comicId}&chapter_slot=0`\n        );\n        if (!res.status || res.status >= 400) {\n          throw \"Invalid status code: \" + res.status;\n        }\n        return \"ok\";\n      }\n    },\n    // \u52a0\u8f7d\u6536\u85cf\u5939, \u4ec5\u5f53multiFolder\u4e3atrue\u65f6\u6709\u6548\n    // \u5f53comicId\u4e0d\u4e3anull\u65f6, \u9700\u8981\u540c\u65f6\u8fd4\u56de\u5305\u542b\u8be5\u6f2b\u753b\u7684\u6536\u85cf\u5939\n    loadFolders: null,\n    /// \u52a0\u8f7d\u6f2b\u753b\n    loadComics: async (page, folder) => {\n      let res = await Network.get(`https://baozimhcn.com/user/my_bookshelf`);\n      if (res.status !== 200) {\n        throw \"Invalid status code: \" + res.status;\n      }\n      let document = new HtmlDocument(res.body);\n      function parseComic(e) {\n        let title = e.querySelector(\"h4 > a\").text.trim();\n        let url = e.querySelector(\"h4 > a\").attributes[\"href\"];\n        let id = url.split(\"/\").pop();\n        let author = e\n          .querySelector(\"div.info > ul\")\n          .children[1].text.split(\"\uff1a\")[1]\n          .trim();\n        let description = e\n          .querySelector(\"div.info > ul\")\n          .children[4].children[0].text.trim();\n\n        return {\n          id: id,\n          title: title,\n          subTitle: author,\n          description: description,\n          cover: e.querySelector(\"amp-img\").attributes[\"src\"],\n        };\n      }\n      let comics = document\n        .querySelectorAll(\"div.bookshelf-items\")\n        .map((e) => parseComic(e));\n      return {\n        comics: comics,\n        maxPage: 1,\n      };\n    },\n  };\n\n  /// \u5355\u4e2a\u6f2b\u753b\u76f8\u5173\n  comic = {\n    // \u52a0\u8f7d\u6f2b\u753b\u4fe1\u606f\n    loadInfo: async (id) => {\n      let res = await Network.get(`https://baozimhcn.com/comic/${id}`);\n      if (res.status !== 200) {\n        throw \"Invalid status code: \" + res.status;\n      }\n      let document = new HtmlDocument(res.body);\n\n      let title = document.querySelector(\"h1.comics-detail__title\").text.trim();\n      let cover = document.querySelector(\"div.l-content > div > div > amp-img\")\n        .attributes[\"src\"];\n      let author = document\n        .querySelector(\"h2.comics-detail__author\")\n        .text.trim();\n      let tags = document\n        .querySelectorAll(\"div.tag-list > span\")\n        .map((e) => e.text.trim());\n      tags = [...tags.filter((e) => e !== \"\")];\n      let updateTime = document\n        .querySelector(\"div.supporting-text > div > span > em\")\n        ?.text.trim()\n        .replace(\"(\", \"\")\n        .replace(\")\", \"\");\n      if (!updateTime) {\n        updateTime = document\n          .querySelector(\"div.supporting-text > div > span > em\")\n          ?.text.trim()\n          .replace(\"(\", \"\")\n          .replace(\")\", \"\");\n      }\n      let description = document\n        .querySelector(\"p.comics-detail__desc\")\n        .text.trim();\n      let chapters = new Map();\n      let i = 0;\n      for (let c of document.querySelectorAll(\n        \"div#chapter-items > div.comics-chapters > a > div > span\"\n      )) {\n        chapters.set(i.toString(), c.text.trim());\n        i++;\n      }\n      for (let c of document.querySelectorAll(\n        \"div#chapters_other_list > div.comics-chapters > a > div > span\"\n      )) {\n        chapters.set(i.toString(), c.text.trim());\n        i++;\n      }\n      let recommend = [];\n      for (let c of document.querySelectorAll(\"div.recommend--item\")) {\n        if (c.querySelectorAll(\"div.tag-comic\").length > 0) {\n          let title = c.querySelector(\"span\").text.trim();\n          let cover = c.querySelector(\"amp-img\").attributes[\"src\"];\n          let url = c.querySelector(\"a\").attributes[\"href\"];\n          let id = url.split(\"/\").pop();\n          recommend.push({\n            id: id,\n            title: title,\n            cover: cover,\n          });\n        }\n      }\n\n      return {\n        title: title,\n        cover: cover,\n        description: description,\n        tags: {\n          \"\u4f5c\u8005\": [author],\n          \"\u66f4\u65b0\": [updateTime],\n          \"\u6807\u7b7e\": tags\n        },\n        chapters: chapters,\n        recommend: recommend\n      };\n    },\n    loadEp: async (comicId, epId) => {\n      let res = await Network.get(`https://${this.lang}.twmanga.com/comic/chapter/${comicId}/0_${epId}.html`);\n      if (res.status !== 200) {\n        throw \"Invalid status code: \" + res.status;\n      }\n      let document = new HtmlDocument(res.body);\n      let images = document.querySelectorAll(\"ul.comic-contain > div > amp-img\").map((e) => e.attributes[\"src\"]);\n      console.log(images);\n      return {\n        images: images,\n      };\n    },\n  };\n}\n";
__SOURCE_CODE["zaimanhua"] = "class Zaimanhua extends ComicSource {\n  // \u57fa\u7840\u4fe1\u606f\n  name = \"\u518d\u6f2b\u753b\";\n  key = \"zaimanhua\";\n  version = \"1.0.2\";\n  minAppVersion = \"1.4.0\";\n  url =\n    \"https://raw.githubusercontent.com/ccbkv/pica_configs/refs/heads/master/zaimanhua.js\";\n\n  // \u521d\u59cb\u5316\u8bf7\u6c42\u5934\n  init() {\n    this.headers = {\n      \"User-Agent\": \"Mozilla/5.0 (Linux; Android) Mobile\",\n      \"authorization\": `Bearer ${this.loadData(\"token\") || \"\"}`,\n    };\n  }\n  // \u6784\u5efa URL\n  buildUrl(path) {\n    this.signTask();\n    return `https://v4api.zaimanhua.com/app/v1/${path}`;\n  }\n  // \u6bcf\u65e5\u7b7e\u5230\n  async signTask() {\n    if (!this.isLogged) {\n      return;\n    }\n    if (!this.loadSetting(\"signTask\")) {\n      return;\n    }\n    const lastSign = this.loadData(\"lastSign\");\n    const newTime = new Date().toISOString().split(\"T\")[0];\n    if (lastSign == newTime) {\n      return;\n    }\n    const res = await Network.post(\"https://i.zaimanhua.com/lpi/v1/task/sign_in\", this.headers);\n    if (res.status !== 200) {\n      return;\n    }\n    this.saveData(\"lastSign\", newTime);\n    if (JSON.parse(res.body)[\"errno\"] == 0) {\n      UI.showMessage(\"\u7b7e\u5230\u6210\u529f\");\n    }\n  }\n\n  //\u8d26\u6237\u7ba1\u7406\n  account = {\n    login: async (username, password) => {\n      try {\n        const encryptedPwd = Convert.hexEncode(\n          Convert.md5(Convert.encodeUtf8(password))\n        );\n        const res = await Network.post(\n          \"https://account-api.zaimanhua.com/v1/login/passwd\",\n          { \"Content-Type\": \"application/x-www-form-urlencoded;charset=utf-8\" },\n          `username=${username}&passwd=${encryptedPwd}`\n        );\n\n        const data = JSON.parse(res.body);\n        if (data.errno !== 0) throw new Error(data.errmsg);\n\n        this.saveData(\"token\", data.data.user.token);\n        this.headers.authorization = `Bearer ${data.data.user.token}`;\n        return true;\n      } catch (e) {\n        UI.showMessage(`\u767b\u5f55\u5931\u8d25: ${e.message}`);\n        throw e;\n      }\n    },\n    logout: () => {\n      this.deleteData(\"token\");\n    },\n  };\n\n  // \u72b6\u6001\u68c0\u67e5\n  checkResponseStatus(res) {\n    if (res.status === 401) {\n      throw new Error(\"\u767b\u5f55\u5931\u6548\");\n    }\n    if (res.status !== 200) {\n      throw new Error(`\u8bf7\u6c42\u5931\u8d25: ${res.status}`);\n    }\n  }\n\n  // \u6f2b\u753b\u89e3\u6790\n  parseComic(comic) {\n    // const safeString = (value) => (value || \"\").toString().trim();\n    const safeString = (value) => (value != null ? value.toString() : \"\");\n    const resolveId = () =>\n      [comic.comic_id, comic.id].find((id) => id && id !== \"0\") || \"\";\n    const resolveTags = () =>\n      [comic.status, ...safeString(comic.types).split(\"/\")].filter(Boolean);\n    const resolveDescription = () => {\n      const candidates = [\n        comic.description,\n        comic.last_update_chapter_name,\n        comic.last_name,\n      ];\n      return candidates.find((text) => text) || \"\";\n    };\n\n    return {\n      id: safeString(resolveId()),\n      title: comic.title || comic.name,\n      subTitle: comic.authors,\n      cover: comic.cover,\n      tags: resolveTags(),\n      description: resolveDescription(),\n    };\n  }\n\n  //\u63a2\u7d22\u9875\u9762\n  explore = [\n    {\n      title: \"\u518d\u6f2b\u753b \u66f4\u65b0\",\n      type: \"multiPageComicList\",\n      load: async (page) => {\n        const res = await Network.get(\n          this.buildUrl(`comic/update/list/0/${page}`),\n          this.headers\n        );\n        const data = JSON.parse(res.body).data;\n        return {\n          comics: data.map((item) => this.parseComic(item)),\n        };\n      },\n    },\n  ];\n\n  static categoryParamMap = {\n    \"\u5168\u90e8\": \"0\",\n    \"\u5192\u9669\": \"4\",\n    \"\u6b22\u4e50\u5411\": \"5\",\n    \"\u683c\u6597\": \"6\",\n    \"\u79d1\u5e7b\": \"7\",\n    \"\u7231\u60c5\": \"8\",\n    \"\u4fa6\u63a2\": \"9\",\n    \"\u7ade\u6280\": \"10\",\n    \"\u9b54\u6cd5\": \"11\",\n    \"\u795e\u9b3c\": \"12\",\n    \"\u6821\u56ed\": \"13\",\n    \"\u60ca\u609a\": \"14\",\n    \"\u5176\u4ed6\": \"16\",\n    \"\u56db\u683c\": \"17\",\n    \"\u4eb2\u60c5\": \"3242\",\n    \"\u767e\u5408\": \"3243\",\n    \"\u79c0\u5409\": \"3244\",\n    \"\u60ac\u7591\": \"3245\",\n    \"\u7eaf\u7231\": \"3246\",\n    \"\u70ed\u8840\": \"3248\",\n    \"\u6cdb\u7231\": \"3249\",\n    \"\u5386\u53f2\": \"3250\",\n    \"\u6218\u4e89\": \"3251\",\n    \"\u840c\u7cfb\": \"3252\",\n    \"\u5b85\u7cfb\": \"3253\",\n    \"\u6cbb\u6108\": \"3254\",\n    \"\u52b1\u5fd7\": \"3255\",\n    \"\u6b66\u4fa0\": \"3324\",\n    \"\u673a\u6218\": \"3325\",\n    \"\u97f3\u4e50\u821e\u8e48\": \"3326\",\n    \"\u7f8e\u98df\": \"3327\",\n    \"\u804c\u573a\": \"3328\",\n    \"\u897f\u65b9\u9b54\u5e7b\": \"3365\",\n    \"\u9ad8\u6e05\u5355\u884c\": \"4459\",\n    \"TS\": \"4518\",\n    \"\u4e1c\u65b9\": \"5077\",\n    \"\u9b54\u5e7b\": \"5806\",\n    \"\u5947\u5e7b\": \"5848\",\n    \"\u8282\u64cd\": \"6219\",\n    \"\u8f7b\u5c0f\u8bf4\": \"6316\",\n    \"\u989c\u827a\": \"6437\",\n    \"\u641e\u7b11\": \"7568\",\n    \"\u4ed9\u4fa0\": \"23388\",\n    \"\u8230\u5a18\": \"7900\",\n    \"\u52a8\u753b\": \"13627\",\n    \"AA\": \"17192\",\n    \"\u798f\u745e\": \"18522\",\n    \"\u751f\u5b58\": \"23323\",\n    \"\u65e5\u5e38\": \"23388\",\n    \"\u753b\u96c6\": \"30788\",\n    \"C100\": \"31137\",\n  };\n\n  //\u5206\u7c7b\u9875\u9762\n  category = {\n    title: \"\u518d\u6f2b\u753b\",\n    parts: [\n      {\n        name: \"\u6392\u884c\u699c\",\n        type: \"fixed\",\n        categories: [\"\u65e5\u6392\u884c\", \"\u5468\u6392\u884c\", \"\u6708\u6392\u884c\", \"\u603b\u6392\u884c\"],\n        itemType: \"category\",\n        categoryParams: [\"0\", \"1\", \"2\", \"3\"],\n      },\n      {\n        name: \"\u5206\u7c7b\",\n        type: \"fixed\",\n        categories: Object.keys(Zaimanhua.categoryParamMap),\n        categoryParams: Object.values(Zaimanhua.categoryParamMap),\n        itemType: \"category\",\n      },\n    ],\n  };\n\n  //\u5206\u7c7b\u6f2b\u753b\u52a0\u8f7d\n  categoryComics = {\n    load: async (category, param, options, page) => {\n      if (category.includes(\"\u6392\u884c\")) {\n        let res = await Network.get(\n          this.buildUrl(\n            `comic/rank/list?page=${page}&rank_type=${options}&by_time=${param}`\n          ),\n          this.headers\n        );\n        return {\n          comics: JSON.parse(res.body).data.map((item) =>\n            this.parseComic(item)\n          ),\n          maxPage: 10,\n        };\n      } else {\n        param = Zaimanhua.categoryParamMap[category] || \"0\";\n        let res = await Network.get(\n          this.buildUrl(\n            `comic/filter/list?status=${options[2]}&theme=${param}&zone=${options[3]}&cate=${options[1]}&sortType=${options[0]}&page=${page}&size=20`\n          ),\n          this.headers\n        );\n        const data = JSON.parse(res.body).data;\n        return {\n          comics: data.comicList.map((item) => this.parseComic(item)),\n          maxPage: Math.ceil(data.totalNum / 20),\n        };\n      }\n    },\n    optionList: [\n      {\n        options: [\"1-\u66f4\u65b0\", \"2-\u4eba\u6c14\"],\n        notShowWhen: null,\n        showWhen: Object.keys(Zaimanhua.categoryParamMap),\n      },\n      {\n        options: [\n          \"0-\u5168\u90e8\",\n          \"3262-\u5c11\u5e74\u6f2b\u753b\",\n          \"3263-\u5c11\u5973\u6f2b\u753b\",\n          \"3264-\u9752\u5e74\u6f2b\u753b\",\n          \"13626-\u5973\u9752\u6f2b\u753b\",\n        ],\n        notShowWhen: null,\n        showWhen: Object.keys(Zaimanhua.categoryParamMap),\n      },\n      {\n        options: [\"0-\u5168\u90e8\", \"2309-\u8fde\u8f7d\u4e2d\", \"2310-\u5df2\u5b8c\u7ed3\", \"29205-\u77ed\u7bc7\"],\n        notShowWhen: null,\n        showWhen: Object.keys(Zaimanhua.categoryParamMap),\n      },\n      {\n        options: [\n          \"0-\u5168\u90e8\",\n          \"2304-\u65e5\u672c\",\n          \"2305-\u97e9\u56fd\",\n          \"2306-\u6b27\u7f8e\",\n          \"2307-\u6e2f\u53f0\",\n          \"2308-\u5185\u5730\",\n          \"8435-\u5176\u4ed6\",\n        ],\n        notShowWhen: null,\n        showWhen: Object.keys(Zaimanhua.categoryParamMap),\n      },\n      {\n        options: [\"0-\u4eba\u6c14\", \"1-\u5410\u69fd\", \"2-\u8ba2\u9605\"],\n        notshowWhen: null,\n        showWhen: [\"\u65e5\u6392\u884c\", \"\u5468\u6392\u884c\", \"\u6708\u6392\u884c\", \"\u603b\u6392\u884c\"],\n      },\n    ],\n  };\n\n  //\u641c\u7d22\n  search = {\n    load: async (keyword, options, page) => {\n      const res = await Network.get(\n        this.buildUrl(\n          `search/index?keyword=${encodeURIComponent(\n            keyword\n          )}&page=${page}&sort=0&size=20`\n        ),\n        this.headers\n      );\n      const data = JSON.parse(res.body).data.list;\n      return {\n        comics: data.map((item) => this.parseComic(item)),\n      };\n    },\n    optionList: [],\n  };\n\n  //\u6536\u85cf\n  favorites = {\n    multiFolder: false,\n    addOrDelFavorite: async (comicId, folderId, isAdding) => {\n      const path = isAdding ? \"add\" : \"del\";\n      const res = await Network.get(\n        this.buildUrl(`comic/sub/${path}?comic_id=${comicId}`),\n        this.headers\n      );\n      const data = JSON.parse(res.body);\n      if (data.errno !== 0) {\n        throw new Error(data.errmsg || \"\u64cd\u4f5c\u5931\u8d25\");\n      }\n      return \"ok\";\n    },\n    loadComics: async (page) => {\n      try {\n        const res = await Network.get(\n          this.buildUrl(`comic/sub/list?status=0&page=${page}&size=20`),\n          this.headers\n        );\n        const data = JSON.parse(res.body).data;\n        return {\n          comics: data.subList.map((item) => this.parseComic(item)) ?? [],\n          maxPage: Math.ceil(data.total / 20),\n        };\n      } catch (e) {\n        console.error(\"\u52a0\u8f7d\u6536\u85cf\u5931\u8d25:\", e);\n        return { comics: [], maxPage: null };\n      }\n    },\n  };\n\n  // \u65f6\u95f4\u6233\u8f6c\u6362\n  formatTimestamp(ts) {\n    const date = new Date(ts * 1000);\n    return date.toISOString().split(\"T\")[0];\n  }\n\n  //\u6f2b\u753b\u8be6\u60c5\n  comic = {\n    loadInfo: async (id) => {\n      const getFavoriteStatus = async (id) => {\n        let res = await Network.get(\n          this.buildUrl(`comic/sub/checkIsSub?objId=${id}&source=1`),\n          this.headers\n        );\n        this.checkResponseStatus(res);\n        return JSON.parse(res.body).data.isSub;\n      };\n      let results = await Promise.all([\n        Network.get(\n          this.buildUrl(`comic/detail/${id}?channel=android`),\n          this.headers\n        ),\n        getFavoriteStatus.bind(this)(id),\n      ]);\n      const response = JSON.parse(results[0].body);\n      if (response.errno !== 0) throw new Error(response.errmsg || \"\u52a0\u8f7d\u5931\u8d25\");\n      const data = response.data.data;\n\n      function processChapters(groups) {\n        return (groups || []).reduce((result, group) => {\n          const groupTitle = group.title || \"\u9ed8\u8ba4\";\n          const chapters = (group.data || [])\n            .reverse()\n            .map((ch) => [\n              String(ch.chapter_id),\n              `${ch.chapter_title.replace(\n                /^(?:\u8fde\u8f7d\u7248?)?(\\d+\\.?\\d*)([\u8bdd\u5377])?$/,\n                (_, n, t) => `\u7b2c${n}${t || \"\u8bdd\"}`\n              )}`,\n            ]);\n          result.set(groupTitle, new Map(chapters));\n          return result;\n        }, new Map());\n      }\n      // \u5206\u7c7b\u6807\u7b7e\n      const { authors, status, types } = data;\n      const tagMapper = (arr) => arr.map((t) => t.tag_name);\n      return {\n        title: data.title,\n        cover: data.cover,\n        description: data.description,\n        tags: {\n          \"\u4f5c\u8005\": tagMapper(authors),\n          \"\u72b6\u6001\": [...tagMapper(status), data.last_update_chapter_name],\n          \"\u6807\u7b7e\": tagMapper(types),\n        },\n        updateTime: this.formatTimestamp(data.last_updatetime),\n        chapters: processChapters(data.chapters),\n        isFavorite: results[1],\n        subId: id,\n      };\n    },\n    loadEp: async (comicId, epId) => {\n      const res = await Network.get(\n        this.buildUrl(`comic/chapter/${comicId}/${epId}`),\n        this.headers\n      );\n      const data = JSON.parse(res.body).data.data;\n      return { images: data.page_url_hd || data.page_url };\n    },\n    \n    loadComments: async (comicId, subId, page, replyTo) => {\n      try {\n        // \u6784\u5efa\u8bf7\u6c42URL\n        const url = this.buildUrl(\n          `comment/list?page=${page}&size=30&type=4&objId=${\n            subId || comicId\n          }&sortBy=1`\n        );\n        const res = await Network.get(url, this.headers);\n        this.checkResponseStatus(res);\n\n        const response = JSON.parse(res.body);\n        const data = response.data;\n\n        /* \u7a7a\u6570\u636e\u68c0\u67e5 */\n        if (!data || !data.commentIdList || !data.commentList) {\n          UI.showMessage(\"\u6682\u65f6\u6ca1\u6709\u8bc4\u8bba\uff0c\u5feb\u6765\u53d1\u8868\u7b2c\u4e00\u6761\u5427~\");\n          return { comments: [], maxPage: 0 };\n        }\n\n        /* \u5904\u7406\u8bc4\u8bbaID\u5217\u8868 */\n        // \u6807\u51c6\u5316ID\u6570\u7ec4\uff1a\u5904\u7406null/\u5b57\u7b26\u4e32/\u6570\u7ec4\u7b49\u591a\u79cd\u60c5\u51b5\n        const rawIds = Array.isArray(data.commentIdList)\n          ? data.commentIdList\n          : [];\n\n        // \u5c55\u5f00\u6240\u6709ID\u5e76\u8fc7\u6ee4\u65e0\u6548\u503c\n        const allCommentIds = rawIds\n          .map((idStr) => `${idStr || \"\"}`.split(\",\")) // \u8f6c\u6362\u4e3a\u5b57\u7b26\u4e32\u518d\u5206\u5272\n          .flat()\n          .filter((id) => id.trim() !== \"\");\n\n        // \u6700\u7ec8ID\u5904\u7406\u6d41\u7a0b\n        const processComments = () => {\n          // \u53bb\u91cd\u5e76\u9a8c\u8bc1ID\u6709\u6548\u6027\n          const validIds = [...new Set(allCommentIds)].filter((id) =>\n            data.commentList.hasOwnProperty(id)\n          );\n\n          // \u8fc7\u6ee4\u56de\u590d\u8bc4\u8bba\n          const filteredIds = replyTo\n            ? validIds.filter(\n                (id) => data.commentList[id]?.to_comment_id == replyTo\n              )\n            : validIds;\n\n          // \u8f6c\u6362\u4e3a\u8bc4\u8bba\u5bf9\u8c61\n          return filteredIds.map((id) => {\n            const comment = data.commentList[id];\n            return new Comment({\n              userName: comment.nickname || \"\u533f\u540d\u7528\u6237\",\n              avatar: comment.photo || \"\",\n              content: comment.content || \"[\u5185\u5bb9\u5df2\u5220\u9664]\",\n              time: this.formatTimestamp(comment.create_time),\n              replyCount: comment.reply_amount || 0,\n              score: comment.like_amount || 0,\n              id: String(id),\n              parentId: comment.to_comment_id || null,\n            });\n          });\n        };\n\n        // \u5f53\u6ca1\u6709\u6709\u6548\u8bc4\u8bba\u65f6\u663e\u793a\u63d0\u793a\n        const comments = processComments();\n        if (comments.length === 0) {\n          UI.showMessage(replyTo ? \"\u8be5\u8bc4\u8bba\u6682\u65e0\u56de\u590d\" : \"\u8fd9\u91cc\u8fd8\u6ca1\u6709\u8bc4\u8bba\u54e6~\");\n        }\n\n        return {\n          comments: comments,\n          maxPage: Math.ceil((data.total || 0) / 30),\n        };\n      } catch (e) {\n        console.error(\"\u8bc4\u8bba\u52a0\u8f7d\u5931\u8d25:\", e);\n        UI.showMessage(`\u52a0\u8f7d\u8bc4\u8bba\u5931\u8d25: ${e.message}`);\n        return { comments: [], maxPage: 0 };\n      }\n    },\n  \n    // \u53d1\u9001\u8bc4\u8bba, \u8fd4\u56de\u4efb\u610f\u503c\u8868\u793a\u6210\u529f.\n    sendComment: async (comicId, subId, content, replyTo) => {\n      if (!replyTo) {\n        replyTo = 0;\n      }\n      let res = await Network.post(\n        this.buildUrl(`comment/add`),\n        {\n          ...this.headers,\n          \"Content-Type\": \"application/x-www-form-urlencoded;charset=utf-8\",\n        },\n        `obj_id=${subId}&content=${encodeURIComponent(\n          content\n        )}&to_comment_id=${replyTo}&type=4`\n      );\n      this.checkResponseStatus(res);\n      let response = JSON.parse(res.body);\n      if (response.errno !== 0) throw new Error(response.errmsg || \"\u52a0\u8f7d\u5931\u8d25\");\n      return \"ok\";\n    },\n    // \u70b9\u8d5e\n    likeComment: async (comicId, subId, commentId, isLike) => {\n      let res = await Network.post(\n        this.buildUrl(`comment/addLike`),\n        {\n          ...this.headers,\n          \"Content-Type\": \"application/x-www-form-urlencoded;charset=utf-8\",\n        },\n        `commentId=${commentId}&type=4`\n      );\n      this.checkResponseStatus(res);\n      return \"ok\";\n    },\n  };\n\n  settings = {\n    signTask: {\n      title: \"\u6bcf\u65e5\u7b7e\u5230\",\n      type: \"switch\",\n      default: false\n    }\n  };\n}\n";
__SOURCE_CODE["comick"] = "class Comick extends ComicSource {\n    name = \"comick\"\n    key = \"comick\"\n    version = \"1.2.0\"\n    minAppVersion = \"1.4.0\"\n    // update url\n    url = \"https://raw.githubusercontent.com/ccbkv/pica_configs/refs/heads/master/comick.js\"\n\n    settings = {\n        domains: {\n            title: \"\u4e3b\u9875\u6e90\",\n            type: \"select\",\n            options: [\n                {value: \"comick.art\"},\n            ],\n            default: \"comick.art\"\n        },\n    }\n\n    get baseUrl() {\n        // let domain = this.loadSetting('domains') || this.settings.domains.default;\n        return `https://comick.art`;\n    }\n\n    static comic_status = {\n        \"1\": \"\u8fde\u8f7d\",\n        \"2\": \"\u5b8c\u7ed3\",\n        \"3\": \"\u4f11\u520a\",\n        \"4\": \"\u6682\u505c\u66f4\u65b0\",\n    }\n    static category_param_dict = {\n        \"romance\": \"\u6d6a\u6f2b\",\n        \"comedy\": \"\u559c\u5267\",\n        \"drama\": \"\u5267\u60c5\",\n        \"fantasy\": \"\u5947\u5e7b\",\n        \"slice-of-life\": \"\u65e5\u5e38\",\n        \"action\": \"\u52a8\u4f5c\",\n        \"adventure\": \"\u5192\u9669\",\n        \"psychological\": \"\u5fc3\u7406\",\n        \"mystery\": \"\u60ac\u7591\",\n        \"historical\": \"\u5386\u53f2\",\n        \"tragedy\": \"\u60b2\u5267\",\n        \"sci-fi\": \"\u79d1\u5e7b\",\n        \"horror\": \"\u6050\u6016\",\n        \"isekai\": \"\u5f02\u4e16\u754c\",\n        \"sports\": \"\u8fd0\u52a8\",\n        \"thriller\": \"\u60ca\u609a\",\n        \"mecha\": \"\u673a\u7532\",\n        \"philosophical\": \"\u54f2\u5b66\",\n        \"wuxia\": \"\u6b66\u4fa0\",\n        \"medical\": \"\u533b\u7597\",\n        \"magical-girls\": \"\u9b54\u6cd5\u5c11\u5973\",\n        \"superhero\": \"\u8d85\u7ea7\u82f1\u96c4\",\n        \"shounen-ai\": \"\u5c11\u5e74\u7231\",\n        \"mature\": \"\u6210\u5e74\",\n        \"gender-bender\": \"\u6027\u8f6c\",\n        \"shoujo-ai\": \"\u5c11\u5973\u7231\",\n        \"oneshot\": \"\u5355\u7bc7\",\n        \"web-comic\": \"\u7f51\u7edc\u6f2b\u753b\",\n        \"doujinshi\": \"\u540c\u4eba\u5fd7\",\n        \"full-color\": \"\u5168\u5f69\",\n        \"long-strip\": \"\u957f\u6761\",\n        \"adaptation\": \"\u6539\u7f16\",\n        \"anthology\": \"\u9009\u96c6\",\n        \"4-koma\": \"\u56db\u683c\",\n        \"user-created\": \"\u7528\u6237\u521b\u4f5c\",\n        \"award-winning\": \"\u83b7\u5956\",\n        \"official-colored\": \"\u5b98\u65b9\u4e0a\u8272\",\n        \"fan-colored\": \"\u7c89\u4e1d\u4e0a\u8272\",\n        \"school-life\": \"\u6821\u56ed\u751f\u6d3b\",\n        \"supernatural\": \"\u8d85\u81ea\u7136\",\n        \"magic\": \"\u9b54\u6cd5\",\n        \"monsters\": \"\u602a\u7269\",\n        \"martial-arts\": \"\u6b66\u672f\",\n        \"animals\": \"\u52a8\u7269\",\n        \"demons\": \"\u6076\u9b54\",\n        \"harem\": \"\u540e\u5bab\",\n        \"reincarnation\": \"\u8f6c\u751f\",\n        \"office-workers\": \"\u4e0a\u73ed\u65cf\",\n        \"survival\": \"\u751f\u5b58\",\n        \"military\": \"\u519b\u4e8b\",\n        \"crossdressing\": \"\u5973\u88c5\",\n        \"loli\": \"\u841d\u8389\",\n        \"shota\": \"\u6b63\u592a\",\n        \"yuri\": \"\u767e\u5408\",\n        \"yaoi\": \"\u803d\u7f8e\",\n        \"video-games\": \"\u7535\u5b50\u6e38\u620f\",\n        \"monster-girls\": \"\u9b54\u7269\u5a18\",\n        \"delinquents\": \"\u4e0d\u826f\u5c11\u5e74\",\n        \"ghosts\": \"\u5e7d\u7075\",\n        \"time-travel\": \"\u65f6\u95f4\u65c5\u884c\",\n        \"cooking\": \"\u70f9\u996a\",\n        \"police\": \"\u8b66\u5bdf\",\n        \"aliens\": \"\u5916\u661f\u4eba\",\n        \"music\": \"\u97f3\u4e50\",\n        \"mafia\": \"\u9ed1\u5e2e\",\n        \"vampires\": \"\u5438\u8840\u9b3c\",\n        \"samurai\": \"\u6b66\u58eb\",\n        \"post-apocalyptic\": \"\u540e\u672b\u65e5\",\n        \"gyaru\": \"\u8fa3\u59b9\",\n        \"villainess\": \"\u6076\u5f79\u5343\u91d1\",\n        \"reverse-harem\": \"\u9006\u540e\u5bab\",\n        \"ninja\": \"\u5fcd\u8005\",\n        \"zombies\": \"\u50f5\u5c38\",\n        \"traditional-games\": \"\u4f20\u7edf\u6e38\u620f\",\n        \"virtual-reality\": \"\u865a\u62df\u73b0\u5b9e\",\n        \"adult\": \"\u6210\u4eba\",\n        \"ecchi\": \"\u60c5\u8272\",\n        \"sexual-violence\": \"\u6027\u66b4\u529b\",\n        \"smut\": \"\u8089\u6b32\",\n    }\n    static reversed_category_param_dict = {\n    \"\u6d6a\u6f2b\": \"romance\",\n    \"\u559c\u5267\": \"comedy\",\n    \"\u5267\u60c5\": \"drama\",\n    \"\u5947\u5e7b\": \"fantasy\",\n    \"\u65e5\u5e38\": \"slice-of-life\",\n    \"\u52a8\u4f5c\": \"action\",\n    \"\u5192\u9669\": \"adventure\",\n    \"\u5fc3\u7406\": \"psychological\",\n    \"\u60ac\u7591\": \"mystery\",\n    \"\u5386\u53f2\": \"historical\",\n    \"\u60b2\u5267\": \"tragedy\",\n    \"\u79d1\u5e7b\": \"sci-fi\",\n    \"\u6050\u6016\": \"horror\",\n    \"\u5f02\u4e16\u754c\": \"isekai\",\n    \"\u8fd0\u52a8\": \"sports\",\n    \"\u60ca\u609a\": \"thriller\",\n    \"\u673a\u7532\": \"mecha\",\n    \"\u54f2\u5b66\": \"philosophical\",\n    \"\u6b66\u4fa0\": \"wuxia\",\n    \"\u533b\u7597\": \"medical\",\n    \"\u9b54\u6cd5\u5c11\u5973\": \"magical-girls\",\n    \"\u8d85\u7ea7\u82f1\u96c4\": \"superhero\",\n    \"\u5c11\u5e74\u7231\": \"shounen-ai\",\n    \"\u6210\u5e74\": \"mature\",\n    \"\u6027\u8f6c\": \"gender-bender\",\n    \"\u5c11\u5973\u7231\": \"shoujo-ai\",\n    \"\u5355\u7bc7\": \"oneshot\",\n    \"\u7f51\u7edc\u6f2b\u753b\": \"web-comic\",\n    \"\u540c\u4eba\u5fd7\": \"doujinshi\",\n    \"\u5168\u5f69\": \"full-color\",\n    \"\u957f\u6761\": \"long-strip\",\n    \"\u6539\u7f16\": \"adaptation\",\n    \"\u9009\u96c6\": \"anthology\",\n    \"\u56db\u683c\": \"4-koma\",\n    \"\u7528\u6237\u521b\u4f5c\": \"user-created\",\n    \"\u83b7\u5956\": \"award-winning\",\n    \"\u5b98\u65b9\u4e0a\u8272\": \"official-colored\",\n    \"\u7c89\u4e1d\u4e0a\u8272\": \"fan-colored\",\n    \"\u6821\u56ed\u751f\u6d3b\": \"school-life\",\n    \"\u8d85\u81ea\u7136\": \"supernatural\",\n    \"\u9b54\u6cd5\": \"magic\",\n    \"\u602a\u7269\": \"monsters\",\n    \"\u6b66\u672f\": \"martial-arts\",\n    \"\u52a8\u7269\": \"animals\",\n    \"\u6076\u9b54\": \"demons\",\n    \"\u540e\u5bab\": \"harem\",\n    \"\u8f6c\u751f\": \"reincarnation\",\n    \"\u4e0a\u73ed\u65cf\": \"office-workers\",\n    \"\u751f\u5b58\": \"survival\",\n    \"\u519b\u4e8b\": \"military\",\n    \"\u5973\u88c5\": \"crossdressing\",\n    \"\u841d\u8389\": \"loli\",\n    \"\u6b63\u592a\": \"shota\",\n    \"\u767e\u5408\": \"yuri\",\n    \"\u803d\u7f8e\": \"yaoi\",\n    \"\u7535\u5b50\u6e38\u620f\": \"video-games\",\n    \"\u9b54\u7269\u5a18\": \"monster-girls\",\n    \"\u4e0d\u826f\u5c11\u5e74\": \"delinquents\",\n    \"\u5e7d\u7075\": \"ghosts\",\n    \"\u65f6\u95f4\u65c5\u884c\": \"time-travel\",\n    \"\u70f9\u996a\": \"cooking\",\n    \"\u8b66\u5bdf\": \"police\",\n    \"\u5916\u661f\u4eba\": \"aliens\",\n    \"\u97f3\u4e50\": \"music\",\n    \"\u9ed1\u5e2e\": \"mafia\",\n    \"\u5438\u8840\u9b3c\": \"vampires\",\n    \"\u6b66\u58eb\": \"samurai\",\n    \"\u540e\u672b\u65e5\": \"post-apocalyptic\",\n    \"\u8fa3\u59b9\": \"gyaru\",\n    \"\u6076\u5f79\u5343\u91d1\": \"villainess\",\n    \"\u9006\u540e\u5bab\": \"reverse-harem\",\n    \"\u5fcd\u8005\": \"ninja\",\n    \"\u50f5\u5c38\": \"zombies\",\n    \"\u4f20\u7edf\u6e38\u620f\": \"traditional-games\",\n    \"\u865a\u62df\u73b0\u5b9e\": \"virtual-reality\",\n    \"\u6210\u4eba\": \"adult\",\n    \"\u60c5\u8272\": \"ecchi\",\n    \"\u6027\u66b4\u529b\": \"sexual-violence\",\n    \"\u8089\u6b32\": \"smut\"\n    }\n    static language_dict = {\n        'en': '\u82f1\u6587',\n        'pt-br': '\u5df4\u897f\u8461\u8404\u7259\u6587',\n        'es-419': '\u62c9\u4e01\u7f8e\u6d32\u897f\u73ed\u7259\u6587',\n        'ru': '\u4fc4\u6587',\n        'vi': '\u8d8a\u5357\u6587',\n        'fr': '\u6cd5\u6587',\n        'pl': '\u6ce2\u5170\u6587',\n        'id': '\u5370\u5ea6\u5c3c\u897f\u4e9a\u6587',\n        'tr': '\u571f\u8033\u5176\u6587',\n        'it': '\u610f\u5927\u5229\u6587',\n        'es': '\u897f\u73ed\u7259\u6587',\n        'uk': '\u4e4c\u514b\u5170\u6587',\n        'ar': '\u963f\u62c9\u4f2f\u6587',\n        'zh-hk': '\u7e41\u4f53\u4e2d\u6587',\n        'hu': '\u5308\u7259\u5229\u6587',\n        'zh': '\u4e2d\u6587',\n        'de': '\u5fb7\u6587',\n        'ko': '\u97e9\u6587',\n        'th': '\u6cf0\u6587',\n        'bg': '\u4fdd\u52a0\u5229\u4e9a\u6587',\n        'ca': '\u52a0\u6cf0\u7f57\u5c3c\u4e9a\u6587',\n        'fa': '\u6ce2\u65af\u6587',\n        'ro': '\u7f57\u9a6c\u5c3c\u4e9a\u6587',\n        'cs': '\u6377\u514b\u6587',\n        'mn': '\u8499\u53e4\u6587',\n        'he': '\u5e0c\u4f2f\u6765\u6587',\n        'pt': '\u8461\u8404\u7259\u6587',\n        'hi': '\u5370\u5730\u6587',\n        'tl': '\u4ed6\u52a0\u7984\u6587',\n        'fi': '\u82ac\u5170\u6587',\n        'ms': '\u9a6c\u6765\u6587',\n        'eu': '\u5df4\u65af\u514b\u6587',\n        'kk': '\u54c8\u8428\u514b\u6587',\n        'sr': '\u585e\u5c14\u7ef4\u4e9a\u6587',\n        'my': '\u7f05\u7538\u6587',\n        'el': '\u5e0c\u814a\u6587',\n        'nl': '\u8377\u5170\u6587',\n        'ja': '\u65e5\u6587',\n        'uz': '\u4e4c\u5179\u522b\u514b\u6587',\n        'eo': '\u4e16\u754c\u8bed',\n        'bn': '\u5b5f\u52a0\u62c9\u6587',\n        'lt': '\u7acb\u9676\u5b9b\u6587',\n        'ka': '\u683c\u9c81\u5409\u4e9a\u6587',\n        'da': '\u4e39\u9ea6\u6587',\n        'ta': '\u6cf0\u7c73\u5c14\u6587',\n        'sv': '\u745e\u5178\u6587',\n        'be': '\u767d\u4fc4\u7f57\u65af\u6587',\n        'cv': '\u695a\u74e6\u4ec0\u6587',\n        'hr': '\u514b\u7f57\u5730\u4e9a\u6587',\n        'la': '\u62c9\u4e01\u6587',\n        'ne': '\u5c3c\u6cca\u5c14\u6587',\n        'ur': '\u4e4c\u5c14\u90fd\u6587',\n        'gl': '\u52a0\u5229\u897f\u4e9a\u6587',\n        'no': '\u632a\u5a01\u6587',\n        'sq': '\u963f\u5c14\u5df4\u5c3c\u4e9a\u6587',\n        'ga': '\u7231\u5c14\u5170\u6587',\n        'te': '\u6cf0\u5362\u56fa\u6587',\n        'jv': '\u722a\u54c7\u6587',\n        'sl': '\u65af\u6d1b\u6587\u5c3c\u4e9a\u6587',\n        'et': '\u7231\u6c99\u5c3c\u4e9a\u6587',\n        'az': '\u963f\u585e\u62dc\u7586\u6587',\n        'sk': '\u65af\u6d1b\u4f10\u514b\u6587',\n        'af': '\u5357\u975e\u8377\u5170\u6587',\n        'lv': '\u62c9\u8131\u7ef4\u4e9a\u6587'\n    }\n    static reversed_language_dict = {\n        '\u82f1\u6587': 'en',\n        '\u5df4\u897f\u8461\u8404\u7259\u6587': 'pt-br',\n        '\u62c9\u4e01\u7f8e\u6d32\u897f\u73ed\u7259\u6587': 'es-419',\n        '\u4fc4\u6587': 'ru',\n        '\u8d8a\u5357\u6587': 'vi',\n        '\u6cd5\u6587': 'fr',\n        '\u6ce2\u5170\u6587': 'pl',\n        '\u5370\u5ea6\u5c3c\u897f\u4e9a\u6587': 'id',\n        '\u571f\u8033\u5176\u6587': 'tr',\n        '\u610f\u5927\u5229\u6587': 'it',\n        '\u897f\u73ed\u7259\u6587': 'es',\n        '\u4e4c\u514b\u5170\u6587': 'uk',\n        '\u963f\u62c9\u4f2f\u6587': 'ar',\n        '\u9999\u6e2f\u7e41\u4f53\u4e2d\u6587': 'zh-hk',\n        '\u5308\u7259\u5229\u6587': 'hu',\n        '\u4e2d\u6587': 'zh',\n        '\u5fb7\u6587': 'de',\n        '\u97e9\u6587': 'ko',\n        '\u6cf0\u6587': 'th',\n        '\u4fdd\u52a0\u5229\u4e9a\u6587': 'bg',\n        '\u52a0\u6cf0\u7f57\u5c3c\u4e9a\u6587': 'ca',\n        '\u6ce2\u65af\u6587': 'fa',\n        '\u7f57\u9a6c\u5c3c\u4e9a\u6587': 'ro',\n        '\u6377\u514b\u6587': 'cs',\n        '\u8499\u53e4\u6587': 'mn',\n        '\u5e0c\u4f2f\u6765\u6587': 'he',\n        '\u8461\u8404\u7259\u6587': 'pt',\n        '\u5370\u5730\u6587': 'hi',\n        '\u83f2\u5f8b\u5bbe\u6587/\u4ed6\u52a0\u7984\u6587': 'tl',\n        '\u82ac\u5170\u6587': 'fi',\n        '\u9a6c\u6765\u6587': 'ms',\n        '\u5df4\u65af\u514b\u6587': 'eu',\n        '\u54c8\u8428\u514b\u6587': 'kk',\n        '\u585e\u5c14\u7ef4\u4e9a\u6587': 'sr',\n        '\u7f05\u7538\u6587': 'my',\n        '\u5e0c\u814a\u6587': 'el',\n        '\u8377\u5170\u6587': 'nl',\n        '\u65e5\u6587': 'ja',\n        '\u4e4c\u5179\u522b\u514b\u6587': 'uz',\n        '\u4e16\u754c\u8bed': 'eo',\n        '\u5b5f\u52a0\u62c9\u6587': 'bn',\n        '\u7acb\u9676\u5b9b\u6587': 'lt',\n        '\u683c\u9c81\u5409\u4e9a\u6587': 'ka',\n        '\u4e39\u9ea6\u6587': 'da',\n        '\u6cf0\u7c73\u5c14\u6587': 'ta',\n        '\u745e\u5178\u6587': 'sv',\n        '\u767d\u4fc4\u7f57\u65af\u6587': 'be',\n        '\u695a\u74e6\u4ec0\u6587': 'cv',\n        '\u514b\u7f57\u5730\u4e9a\u6587': 'hr',\n        '\u62c9\u4e01\u6587': 'la',\n        '\u5c3c\u6cca\u5c14\u6587': 'ne',\n        '\u4e4c\u5c14\u90fd\u6587': 'ur',\n        '\u52a0\u5229\u897f\u4e9a\u6587': 'gl',\n        '\u632a\u5a01\u6587': 'no',\n        '\u963f\u5c14\u5df4\u5c3c\u4e9a\u6587': 'sq',\n        '\u7231\u5c14\u5170\u6587': 'ga',\n        '\u6cf0\u5362\u56fa\u6587': 'te',\n        '\u722a\u54c7\u6587': 'jv',\n        '\u65af\u6d1b\u6587\u5c3c\u4e9a\u6587': 'sl',\n        '\u7231\u6c99\u5c3c\u4e9a\u6587': 'et',\n        '\u963f\u585e\u62dc\u7586\u6587': 'az',\n        '\u65af\u6d1b\u4f10\u514b\u6587': 'sk',\n        '\u5357\u975e\u8377\u5170\u6587': 'af',\n        '\u62c9\u8131\u7ef4\u4e9a\u6587': 'lv'\n    }\n\n    static getRandomHeaders() {\n        let userAgents = [\n            \"Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36\",\n            \"Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36\",\n            \"Mozilla/5.0 (iPhone; CPU iPhone OS 14_6 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/14.0 Mobile/15E148 Safari/604.1\",\n            \"Mozilla/5.0 (Linux; Android 10; SM-G973F) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Mobile Safari/537.36\",\n            \"Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36\"\n        ];\n\n        return {\n            \"User-Agent\": userAgents[Math.floor(Math.random() * userAgents.length)],\n            \"Accept\": \"application/json, text/plain, */*\",\n            \"Accept-Language\": \"en-US,en;q=0.9\",\n            \"Connection\": \"keep-alive\",\n            'referer': 'https://comick.art/'\n        };\n    }\n\n    transReformBookList(bookList, descriptionPrefix = \"\u66f4\u65b0\u81f3\uff1a\") {\n        return bookList.map(book => ({\n            id: `${book.relates?.slug || 'unknown'}//${book.relates?.title || '\u672a\u77e5\u6807\u9898'}`,\n            title: book.relates?.title || '\u672a\u77e5\u6807\u9898',\n            cover: book.relates?.md_covers?.[0]?.b2key\n                ? `https://cdn1.comicknew.pictures/${book.relates.slug}/covers/${book.relates.md_covers[0].b2key}`\n                : 'w7xqzd.jpg',\n        }));\n    }\n\n    \n    transformBookList(bookList, descriptionPrefix = \"\u66f4\u65b0\u81f3\uff1a\") {\n        return bookList.map(book => ({\n            id: `${book.slug || 'unknown'}//${book.title || '\u672a\u77e5\u6807\u9898'}`,\n            title: book.title || '\u672a\u77e5\u6807\u9898',\n            cover: book.default_thumbnail ? book.default_thumbnail : book.full_image_path ? book.full_image_path : 'https://comick.art/images/default-thumbnail.webp',\n            tags: [],\n            description: `${descriptionPrefix}${book.last_chapter || \"\u672a\u77e5\"}`\n        }));\n    }\n\n    getFormattedManga(manga) {\n        return {\n            id: `${manga.slug || 'unknown'}//${manga.title || '\u672a\u77e5\u6807\u9898'}`,\n            title: manga.title || \"\u65e0\u6807\u9898\",\n            cover: manga.default_thumbnail ? manga.default_thumbnail : manga.full_image_path ? manga.full_image_path : 'https://comick.art/images/default-thumbnail.webp',\n            tags: [\n                `\u66f4\u65b0\u65f6\u95f4: ${manga.uploaded_at ? new Date(manga.uploaded_at).toISOString().split('T')[0] : new Date(manga.created_at).toISOString().split('T')[0]}`\n            ],\n            description: manga.description || \"\u6682\u65e0\u63cf\u8ff0\"\n        };\n    }\n\n    explore = [{\n        title: \"comick\",\n        type: \"singlePageWithMultiPart\",\n        load: async () => {\n            // let url = this.baseUrl === \"https://comick.art\"\n            //     ? \"https://comick.art/home2\"\n            //     : this.baseUrl;\n            let url = 'https://comick.art/home'\n\n            let res = await Network.get(url);\n            if (res.status !== 200) throw \"Request Error: \" + res.status;\n\n            let document = new HtmlDocument(res.body);\n            let jsonData = JSON.parse(document.getElementById('sv-data').text);\n            let mangaData = jsonData.data;\n\n            // \u4f7f\u7528\u7edf\u4e00\u51fd\u6570\u8f6c\u6362\u6570\u636e\n            let result = {\n                \"\u6700\u8fd1\u66f4\u65b0\": this.transformBookList(mangaData.most_follow_new['7']),\n                \"\u6700\u8fd1\u4e0a\u4f20\": this.transformBookList(mangaData.recent_add),\n                // \"\u6700\u8fd1\u70ed\u95e8\": this.transformBookList(mangaData.follows['7']),\n                \"\u6700\u8fd1\u70ed\u95e8\": this.transformBookList(mangaData.popular_ongoing),\n                \"\u5b8c\u7ed3\": this.transformBookList(mangaData.completed)\n            };\n\n            return result;\n        }\n    }]\n\n    // categories\n    category = {\n        title: \"comick\",\n        parts: [{\n            name: \"\u7c7b\u578b\",\n            type: \"fixed\",\n            categories: Object.values(Comick.category_param_dict), // \u4f7f\u7528\u4e0a\u65b9\u7684\u5b57\u5178\n            itemType: \"category\",\n            categoryParams: Object.keys(Comick.category_param_dict),\n        }],\n        enableRankingPage: false,\n    }\n\n    categoryComics = {\n        load: async (category, param, options, page) => {\n            // \u57fa\u7840URL\n            let url = \"https://comick.art/api/search?\";\n            let params = [\n                `genres[]=${encodeURIComponent(param)}`,\n                `page=${encodeURIComponent(page)}`\n            ];\n\n            if (options[0]) {\n                params.push(`order_by=${encodeURIComponent(options[0].split(\"-\")[0])}`);\n            }\n\n            if (options[1] && options[1] !== \"-\u5168\u90e8\") {\n                params.push(`country[]=${encodeURIComponent(options[1].split(\"-\")[0])}`);\n            }\n\n            if (options[2]) {\n                params.push(`status=${encodeURIComponent(options[2].split(\"-\")[0])}`);\n            }\n\n            url += params.join('&');\n            let headers = Comick.getRandomHeaders();\n            let res = await Network.get(url=url, headers=headers);\n            if (res.status !== 200) throw \"Request Error: \" + res.status;\n\n            let mangaList = JSON.parse(res.body).data;\n            if (!Array.isArray(mangaList)) throw \"Invalid data format\";\n            let maxpage = mangaList.total/mangaList.per_page\n            return {\n                comics: mangaList.map(this.getFormattedManga),\n                maxPage: maxpage\n            };\n        },\n        optionList: [\n            {options: [\"created_at-\u66f4\u65b0\u6392\u5e8f\",\"user_follow_count-\u5173\u6ce8\u6392\u5e8f\", \"rating-\u8bc4\u5206\u6392\u5e8f\", \"uploaded-\u521b\u5efa\u6392\u5e8f\"]},\n            {options: [\"-\u5168\u90e8\", \"cn-\u56fd\u6f2b\", \"jp-\u65e5\u672c\", \"kr-\u97e9\u56fd\", \"others-\u6b27\u7f8e\"]},\n            {options: [\"1-\u8fde\u8f7d\", \"2-\u5b8c\u7ed3\", \"3-\u4f11\u520a\", \"4-\u6682\u505c\u66f4\u65b0\"]},\n        ]\n    }\n\n    /// search related\n    search = {\n        load: async (keyword, options, page) => {\n            let headers = Comick.getRandomHeaders();\n            let url = `https://comick.art/search?q=${keyword}&page=${page}`;\n            let res = await Network.get(url=url, headers=headers);\n            if (res.status !== 200) {\n                throw `Invalid status code: ${res.status}`;\n            }\n            let document = new HtmlDocument(res.body)\n            let jsonData = JSON.parse(document.getElementById('sv-data').text);\n            let mangaList = jsonData.data;\n            if (!Array.isArray(mangaList)) throw \"Invalid data format\";\n            let maxpage = mangaList.total/mangaList.per_page\n            return {\n                comics: mangaList.map(this.getFormattedManga),\n                maxPage: Math.ceil(maxpage)\n            };\n        },\n        optionList: []\n    }\n\n    /// single comic related\n    comic = {\n        id: null,\n        buildId: null,\n\n        loadInfo: async (id) => {\n            let headers = Comick.getRandomHeaders();\n\n\n            let [cId, cTitle] = id.split(\"//\");\n            if (!cId) {\n                throw \"ID error: \";\n            }\n\n            let res = await Network.get(\n                `https://comick.art/comic/${cId}`, \n                headers \n            );\n            if (res.status !== 200) {\n                throw \"Invalid status code: \" + res.status\n            }\n\n            let load_chapter = async (slug, comicData) => {\n                let langBuckets = new Map();\n                let latestTimestamp = null;\n                let page = 1;\n                let lastPage = 1;\n\n                let collectChapters = (items) => {\n                    items.forEach(item => {\n                        let langCode = item?.lang || 'unknown';\n                        if (!langBuckets.has(langCode)) {\n                            langBuckets.set(langCode, []);\n                        }\n                        langBuckets.get(langCode).push(item);\n                    });\n                };\n\n                console.log(`\u5f00\u59cb\u52a0\u8f7d\u7ae0\u8282\u5217\u8868\uff0c\u6f2b\u753bslug: ${slug}`);\n                while (page <= lastPage) {\n                    let url = `https://comick.art/api/comics/${slug}/chapter-list?page=${page}`;\n                    let resCh = await Network.get(url=url, headers=Comick.getRandomHeaders());\n                    console.log(`\u8bf7\u6c42\u7ae0\u8282\u5217\u8868\u9875\u9762 ${page}\uff0cURL: ${resCh}`);\n                    if (resCh.status !== 200) {\n                        throw `Invalid status code: ${resCh.status}`;\n                    }\n\n                    let payload;\n                    try {\n                        payload = JSON.parse(resCh.body);\n                    } catch (err) {\n                        throw \"Invalid chapter list response\";\n                    }\n\n                    let data = Array.isArray(payload?.data) ? payload.data : [];\n                    if (page === 1 && data.length > 0) {\n                        latestTimestamp = data[0].updated_at || data[0].publish_at || data[0].created_at || null;\n                    }\n                    collectChapters(data);\n\n                    let pagination = payload?.pagination;\n                    if (pagination && pagination.last_page != null) {\n                        let parsed = parseInt(pagination.last_page, 10);\n                        if (!Number.isNaN(parsed) && parsed > 0) {\n                            lastPage = parsed;\n                        }\n                    }\n                    page += 1;\n                }\n\n                let result = new Map();\n\n                langBuckets.forEach((items, langCode) => {\n                    let chaptersMap = new Map();\n                    let orderedItems = items.slice().reverse(); // API \u6309\u6700\u65b0\u5728\u524d\uff0c\u53cd\u8f6c\u4fbf\u4e8e\u6b63\u5e8f\u6d4f\u89c8\n\n                    orderedItems.forEach(item => {\n                        let lang = item?.lang || 'unknown';\n                        let hid = item?.hid || 'unknown';\n                        let hasChap = item?.chap != null && item.chap !== \"\";\n                        let hasVol = item?.vol != null && item.vol !== \"\";\n                        let key;\n                        let label;\n\n                        if (hasChap) {\n                            key = `${hid}//chapter//${item.chap}//${lang}`;\n                            label = `\u7b2c${item.chap}\u8bdd`;\n                        } else if (hasVol) {\n                            key = `${hid}//volume//${item.vol}//${lang}`;\n                            label = `\u7b2c${item.vol}\u5377`;\n                        } else {\n                            key = `${hid}//no//-1//${lang}`;\n                            label = item?.title ? item.title : '\u65e0\u6807\u5377';\n                        }\n\n                        chaptersMap.set(key, label);\n                    });\n\n                    let displayLang = Comick.language_dict[langCode] || langCode || '\u672a\u77e5\u8bed\u8a00';\n                    result.set(displayLang, chaptersMap);\n                });\n\n                let updateTime = \"\u6682\u65e0\u66f4\u65b0\";\n                if (latestTimestamp) {\n                    let date = new Date(latestTimestamp);\n                    if (!isNaN(date.getTime())) {\n                        updateTime = date.toISOString().split('T')[0];\n                    } else {\n                        updateTime = latestTimestamp;\n                    }\n                } else if (comicData?.last_chapter) {\n                    updateTime = `\u7b2c${comicData.last_chapter}\u8bdd`;\n                }\n                return [result, updateTime];\n            };\n\n            //\u586b\u5145\u6587\u7ae0id\uff1a\n            this.comic.id = id;\n            let document = new HtmlDocument(res.body)\n            let jsonData = JSON.parse(document.getElementById('comic-data').text);\n            let comicData = jsonData;\n            let authorData = comicData.authors || [];\n            let title = cTitle || comicData?.title || \"\u672a\u77e5\u6807\u9898\";\n            let status = comicData?.status || \"1\"; // \u9ed8\u8ba4\u8fde\u8f7d\n            let cover = comicData.default_thumbnail ? comicData.default_thumbnail : comicData.full_image_path ? comicData.full_image_path : 'https://comick.art/images/default-thumbnail.webp';\n            let author = authorData[0]?.name || \"\u672a\u77e5\u4f5c\u8005\";\n\n            // \u63d0\u53d6\u6807\u7b7e\u7684slug\u6570\u7ec4\u7684\u4ee3\u7801\n            let extractSlugs = (comicData) => {\n                try {\n                    // \u83b7\u53d6md_comic_md_genres\u6570\u7ec4\n                    let genres = comicData?.md_comic_md_genres;\n                    if (!genres || !Array.isArray(genres)) {\n                        return [];\n                    }\n                    // \u4f7f\u7528map\u63d0\u53d6\u6bcf\u4e2amd_genres\u4e2d\u7684slug\n                    let slugs = genres.map(genre => genre?.md_genres?.slug).filter(slug => slug != null);\n                    return slugs;\n                } catch (error) {\n                    return []; // \u8fd4\u56de\u7a7a\u6570\u7ec4\u4f5c\u4e3a\u5bb9\u9519\u5904\u7406\n                }\n            };\n\n            let tags = extractSlugs(comicData);\n            // \u8f6c\u6362 tags \u6570\u7ec4\uff0c\u5982\u679c\u627e\u4e0d\u5230\u5bf9\u5e94\u503c\u5219\u4fdd\u7559\u539f\u503c\n            let translatedTags = tags.map(tag => {\n                return Comick.category_param_dict[tag] || tag; // \u5982\u679c\u5b57\u5178\u91cc\u6ca1\u6709\uff0c\u5c31\u8fd4\u56de\u539f\u503c\n            });\n            let description = comicData?.desc || \"\u6682\u65e0\u63cf\u8ff0\";\n\n            // //\u5904\u7406\u63a8\u8350\u5217\u8868\n            // let recommends = this.transReformBookList(comicData?.relate_from || []);\n            // //\u53ea\u8981recommends\u6570\u7ec4\u524d\u9762\u5341\u4e2a\uff0c\u4e0d\u591f\u5341\u4e2a\u5219\u5c31\u662frecommends\u7684\u957f\u5ea6\n            // recommends = recommends.slice(0, Math.min(recommends.length, 10));\n\n            let fallbackUpdate = comicData?.last_chapter ? `\u7b2c${comicData.last_chapter}\u8bdd` : \"\u6682\u65e0\u66f4\u65b0\";\n            let chapters = new Map();\n            let updateTime = fallbackUpdate;\n\n            try {\n                let temp = await load_chapter(cId, comicData);\n                if (Array.isArray(temp)) {\n                    chapters = temp[0] instanceof Map ? temp[0] : chapters;\n                    updateTime = typeof temp[1] === 'string' && temp[1].length > 0 ? temp[1] : updateTime;\n                }\n            } catch (error) {\n                chapters = new Map();\n            }\n\n            if (chapters.size === 0) {\n                return {\n                    title: title,\n                    cover: cover,\n                    description: description,\n                    tags: {\n                        \"\u8bed\u8a00\": [],\n                        \"\u4f5c\u8005\": [author],\n                        \"\u66f4\u65b0\": [updateTime],\n                        \"\u6807\u7b7e\": translatedTags,\n                        \"\u72b6\u6001\": [Comick.comic_status[status]]\n                    },\n                    chapters: chapters,\n                };\n            }\n\n            return {\n                title: title,\n                cover: cover,\n                description: description,\n                tags: {\n                    \"\u4f5c\u8005\": [author],\n                    \"\u66f4\u65b0\": [updateTime],\n                    \"\u6807\u7b7e\": translatedTags,\n                    \"\u72b6\u6001\": [Comick.comic_status[status]],\n                },\n                chapters: chapters,\n                //recommend: recommends || []\n            }\n        },\n        loadEp: async (comicId, epId) => {\n            let [cId, cTitle] = comicId.split(\"//\");\n            if (!cId) {\n                throw \"ID error: \";\n            }\n\n            let images = [];\n            let [hid, type, chapter, lang] = epId.split(\"//\");\n\n            // \u68c0\u67e5\u5206\u5272\u7ed3\u679c\u662f\u5426\u6709\u6548\n            if (!hid || !type || !chapter || !lang) {\n                console.error(\"Invalid epId format. Expected 'hid//chapter'\");\n                return {images};  // \u8fd4\u56de\u7a7a\u6570\u7ec4\n            }\n\n            let url = \" \";\n            if(type==\"no\"){\n                // \u5982\u679c\u662f\u65e0\u6807\u5377, \u53ea\u770b\u7b2c\u4e00\u4e2a\n                url = `https://comick.art/comic/${cId}/${hid}`;\n            }else{\n                url = `https://comick.art/comic/${cId}/${hid}-${type}-${chapter}-${lang}`;\n            }\n\n            let maxAttempts = 100;\n\n            while (maxAttempts > 0) {\n                let res = await Network.get(url);\n                if (res.status !== 200) break;\n\n                let document = new HtmlDocument(res.body)\n\n                let jsonData = JSON.parse(document.getElementById('sv-data').text); //json\u89e3\u6790\u65b9\u5f0f\n                let imagesData = jsonData.chapter?.images;\n\n                // \u68c0\u67e5\u56fe\u7247\u6570\u636e\u662f\u5426\u5b58\u5728\n                if (!imagesData || !Array.isArray(imagesData)) {\n                    break;\n                }\n\n                // \u89e3\u6790\u5f53\u524d\u9875\u56fe\u7247\n                imagesData.forEach(image => {\n                        // \u5904\u7406\u56fe\u7247\u94fe\u63a5\n                        let imageUrl = `${image.url}`;\n                        images.push(imageUrl);\n                });\n\n                // \u67e5\u627e\u4e0b\u4e00\u9875\u94fe\u63a5\n                let nextLink = document.querySelector(\"a#next-chapter\");\n                if (nextLink?.text?.match(/\u4e0b\u4e00\u9875|\u4e0b\u4e00\u9801/)) {\n                    let nextUrl = nextLink.attributes?.['href'];\n                    if (nextUrl) {\n                        url = nextUrl;\n                    } else {\n                        break;\n                    }\n                } else {\n                    break;\n                }\n                maxAttempts--;\n            }\n            return {images};\n        },\n\n        onImageLoad: (url, comicId, epId) => {\n            let headers = Comick.getRandomHeaders();\n           return {\n                url,\n                method: \"GET\",\n                headers,\n                onLoadFailed: () => ({ url })\n           }\n        },\n\n        onThumbnailLoad: (url) => { \n            let headers = Comick.getRandomHeaders();\n           return {\n                url : url,\n                method: \"GET\",\n                headers : headers,\n                onLoadFailed: () => ({ url })\n           }\n        },\n\n        onClickTag: (namespace, tag) => {\n            if (namespace === \"\u6807\u7b7e\") {\n                let r_tag = Comick.reversed_category_param_dict[tag] || tag;\n                return {\n                    action: 'category',\n                    keyword: `${tag}`,\n                    param: r_tag,\n                }\n            }\n            throw \"Click Tag Error\"\n        },\n    }\n}";
__SOURCE_CODE["mh18"] = "/** @type {import('./_venera_.js')} */\nclass MH18 extends ComicSource {\n  // Note: The fields which are marked as [Optional] should be removed if not used\n\n  // name of the source\n  name = \"18\u6f2b\u753b\"\n\n  // unique id of the source\n  key = \"mh18\"\n\n  version = \"1.0.0\"\n\n  minAppVersion = \"1.4.0\"\n\n  // update url\n  url = \"https://raw.githubusercontent.com/ccbkv/pica_configs/refs/heads/master/mh18.js\"\n\n  settings = {\n    domains: {\n      title: \"\u57df\u540d\",\n      type: \"input\",\n      default: \"18mh.org\"\n    }\n  }\n\n  get baseUrl() {\n    return `https://${this.loadSetting(\"domains\")}`;\n  }\n\n  get headers() {\n    return {\n      \"User-Agent\": \"Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:144.0) Gecko/20100101 Firefox/144.0\",\n      \"Referer\": this.baseUrl\n    };\n  }\n\n  parseComics(doc) {\n    console.warn(doc)\n    const result = [];\n    for (let item of doc.querySelectorAll(\".pb-2\")) {\n      result.push(new Comic({\n        id: item.querySelector(\"a\").attributes[\"href\"],\n        title: item.querySelector(\"h3\").text,\n        cover: item.querySelector(\"img\").attributes[\"src\"]\n      }))\n    }\n    return result;\n  }\n\n  // explore page list\n  explore = [\n    {\n      // title of the page.\n      // title is used to identify the page, it should be unique\n      title: this.name,\n\n      /// multiPartPage or multiPageComicList or mixed\n      type: \"multiPartPage\",\n\n      load: async () => {\n        const res = await Network.get(this.baseUrl, this.headers);\n        const document = new HtmlDocument(res.body);\n        const result = [{ title: \"\u8fd1\u671f\u66f4\u65b0\", comics: [], viewMore: null }];\n        for (let item of document.querySelector(\".pb-unit-md\").querySelectorAll(\".slicarda\")) {\n          result[0].comics.push(new Comic({\n            id: item.attributes[\"href\"],\n            title: item.querySelector(\"h3\").text,\n            cover: item.querySelector(\"img\").attributes[\"src\"]\n          }))\n        }\n        const cardlists = document.querySelectorAll(\".cardlist\");\n        const hometitles = document.querySelectorAll(\".hometitle\");\n        for (let i = 0; i < hometitles.length; i++) {\n          result.push({\n            title: hometitles[i].querySelector(\"h2\").text,\n            comics: this.parseComics(cardlists[i]),\n            viewMore: {\n              page: \"category\",\n              attributes: {\n                category: hometitles[i].querySelector(\"h2\").text,\n                param: hometitles[i].attributes[\"href\"]\n              },\n            }\n          });\n        }\n        return result;\n      }\n    }\n  ]\n\n  // categories\n  category = {\n    /// title of the category page, used to identify the page, it should be unique\n    title: this.name,\n    parts: [\n      {\n        name: \"\u7c7b\u578b\",\n        type: \"fixed\",\n        categories: [\n          \"\u5168\u90e8\",\n          \"\u97d3\u6f2b\",\n          \"\u771f\u4eba\u5beb\u771f\",\n          \"\u65e5\u6f2b\",\n          \"AI\u5beb\u771f\",\n          \"\u71b1\u9580\u6f2b\u756b\"\n        ],\n        itemType: \"category\",\n        categoryParams: [\n          \"/manga\",\n          \"/manga-genre/hanman\",\n          \"/manga-genre/zhenrenxiezhen\",\n          \"/manga-genre/riman\",\n          \"/manga-genre/aixiezhen\",\n          \"/manga-genre/hots\"\n        ],\n      },\n      {\n        name: \"\u6807\u7b7e\",\n        type: \"fixed\",\n        categories: [\n          \"\u591a\u4eba\",\n          \"\u617e\u671b\",\n          \"\u6b63\u59b9\",\n          \"\u540c\u5c45\",\n          \"\u5973\u5b78\u751f\",\n          \"\u5287\u60c5\",\n          \"\u5077\u60c5\",\n          \"\u6821\u56ed\",\n          \"\u9006\u8972\",\n          \"\u529e\u516c\u5ba4\",\n          \"\u8a98\u60d1\",\n          \"\u53cd\u8f6c\",\n          \"\u719f\u5973\",\n          \"\u4eba\u59bb\",\n          \"\u521d\u6200\",\n          \"\u5c11\u5987\",\n          \"\u523a\u6fc0\",\n          \"\u5973\u5927\u5b66\u751f\",\n          \"\u6cbb\u7597\",\n          \"\u8d85\u80fd\u529b\",\n          \"\u6d6a\u6f2b\u6821\u56ed\",\n          \"\u620f\u5267\",\n          \"\u5b66\u59d0\",\n          \"\u5927\u5b66\u751f\",\n          \"\u6cf3\u8863\",\n          \"\u66a7\u6627\",\n          \"\u5199\u771f\",\n          \"\u5973\u795e\",\n          \"\u5927\u5c3a\u5ea6\",\n          \"\u7eaf\u60c5\u8b66\u5bdf\"\n        ],\n        itemType: \"category\",\n        categoryParams: [\n          \"/manga-tag/duoren\",\n          \"/manga-tag/yuwang\",\n          \"/manga-tag/zhengmei\",\n          \"/manga-tag/tongju\",\n          \"/manga-tag/nxuesheng\",\n          \"/manga-tag/juqing\",\n          \"/manga-tag/touqing\",\n          \"/manga-tag/xiaoyuan\",\n          \"/manga-tag/nixi\",\n          \"/manga-tag/bangongshi\",\n          \"/manga-tag/youhuo\",\n          \"/manga-tag/fanzhuan\",\n          \"/manga-tag/shun\",\n          \"/manga-tag/renqi\",\n          \"/manga-tag/chulian\",\n          \"/manga-tag/shaofu\",\n          \"/manga-tag/ciji\",\n          \"/manga-tag/ndaxuesheng\",\n          \"/manga-tag/zhiliao\",\n          \"/manga-tag/chaonengli\",\n          \"/manga-tag/langmanxiaoyuan\",\n          \"/manga-tag/xiju\",\n          \"/manga-tag/xuejie\",\n          \"/manga-tag/daxuesheng\",\n          \"/manga-tag/yongyi\",\n          \"/manga-tag/aimei\",\n          \"/manga-tag/xiezhen\",\n          \"/manga-tag/nshen\",\n          \"/manga-tag/dachidu\",\n          \"/manga-tag/chunqingjingcha\"\n        ],\n      }\n    ],\n    // enable ranking page\n    enableRankingPage: false,\n  }\n\n  /// category comic loading related\n  categoryComics = {\n    load: async (category, params, options, page) => {\n      const res = await Network.get(`${this.baseUrl}${params}/page/${page}`, this.headers);\n      if (res.status !== 200) {\n        throw `Invalid status code: ${res.status}`;\n      }\n      const document = new HtmlDocument(res.body);\n      let maxPage = null;\n      try {\n        maxPage = parseInt(document.querySelectorAll(\"button.text-small\").pop().text.replaceAll(\"\\n\", \"\").replaceAll(\" \", \"\"));\n      } catch (_) {\n        maxPage = 1;\n      }\n      return {\n        comics: this.parseComics(document),\n        maxPage: maxPage\n      };\n    }\n  }\n\n  /// search related\n  search = {\n    load: async (keyword, options, page) => {\n      const res = await Network.get(`${this.baseUrl}/s/${keyword}?page=${page}`);\n      if (res.status !== 200) {\n        throw `Invalid status code: ${res.status}`;\n      }\n      const document = new HtmlDocument(res.body);\n      let maxPage = null;\n      try {\n        maxPage = parseInt(document.querySelectorAll(\"button.text-small\").pop().text.replaceAll(\"\\n\", \"\").replaceAll(\" \", \"\"));\n      } catch (_) {\n        maxPage = 1;\n      }\n      return {\n        comics: this.parseComics(document),\n        maxPage: maxPage\n      };\n    },\n    // enable tags suggestions\n    enableTagsSuggestions: false,\n  }\n\n  /// single comic related\n  comic = {\n    onThumbnailLoad: (url) => {\n      return {\n        headers: this.headers\n      }\n    },\n    loadInfo: async (id) => {\n      if (!id.startsWith(\"http\")) {\n        id = this.baseUrl + id;\n      }\n      const res = await Network.get(id);\n      if (res.status !== 200) {\n        throw `Invalid status code: ${res.status}`;\n      }\n      const document = new HtmlDocument(res.body);\n      const title = document.querySelector(\".text-xl\").text.trim().split(\"   \")[0]\n      const cover = document.querySelector(\".object-cover\").attributes[\"src\"];\n      const description = document.querySelector(\"p.text-medium\").text;\n      const infos = document.querySelectorAll(\"div.py-1\");\n      const tags = { \"\u4f5c\u8005\": [], \"\u7c7b\u578b\": [], \"\u6807\u7b7e\": [] };\n      for (let author of infos[0].querySelectorAll(\"a > span\")) {\n        let author_name = author.text.trim();\n        if (author_name.endsWith(\",\")) {\n          author_name = author_name.slice(0, -1).trim();\n        }\n        tags[\"\u4f5c\u8005\"].push(author_name);\n      }\n      for (let category of infos[1].querySelectorAll(\"a > span\")) {\n        let category_name = category.text.trim();\n        if (category_name.endsWith(\",\")) {\n          category_name = category_name.slice(0, -1).trim();\n        }\n        tags[\"\u7c7b\u578b\"].push(category_name);\n      }\n      for (let tag of infos[2].querySelectorAll(\"a\")) {\n        tags[\"\u6807\u7b7e\"].push(tag.text.replace(\"\\n\", \"\").replaceAll(\" \", \"\").replace(\"#\", \"\"));\n      }\n      const mangaId = document.querySelector(\"#mangachapters\").attributes[\"data-mid\"];\n      const chapterRes = await Network.get(`${this.baseUrl}/manga/get?mid=${mangaId}&mode=all&t=${Date.now()}`, this.headers);\n      const chapterDoc = new HtmlDocument(chapterRes.body);\n      const chapters = {};\n      for (let ch of chapterDoc.querySelectorAll(\".chapteritem\")) {\n        const info = ch.querySelector(\"a\");\n        chapters[`${info.attributes[\"data-ms\"]}@${info.attributes[\"data-cs\"]}`] = ch.querySelector(\".chaptertitle\").text;\n      }\n      const recommend = [];\n      for (let item of document.querySelectorAll(\"div.cardlist > div.pb-2\")) {\n        recommend.push(new Comic({\n          id: item.querySelector(\"a\").attributes[\"href\"],\n          title: item.querySelector(\"h3\").text,\n          cover: item.querySelector(\"img\").attributes[\"src\"]\n        }));\n      }\n      return new ComicDetails({\n        title: title,\n        cover: cover,\n        description: description,\n        tags: tags,\n        chapters: chapters,\n        recommend: recommend,\n      });\n    },\n\n    loadEp: async (comicId, epId) => {\n      const ids = epId.split(\"@\");\n      const res = await Network.get(`${this.baseUrl}/chapter/getcontent?m=${ids[0]}&c=${ids[1]}`, this.headers);\n      if (res.status !== 200) {\n        throw `Invalid status code: ${res.status}`;\n      }\n      const document = new HtmlDocument(res.body);\n      const images = [];\n      for (let i of document.querySelector(\"#chapcontent\").querySelectorAll(\"img\")) {\n        images.push(i.attributes[\"data-src\"] ? i.attributes[\"data-src\"] : i.attributes[\"src\"]);\n      }\n      return { images };\n    },\n\n    // enable tags translate\n    enableTagsTranslate: false,\n  }\n}";
__SOURCE_CODE["mxs"] = "class MXS extends ComicSource {\n    // \u6f2b\u753b\u6e90\u57fa\u672c\u4fe1\u606f\n    name = \"\u6f2b\u5c0f\u8086\";\n    key = \"mxs\";\n    version = \"1.0.0\";\n    minAppVersion = \"1.5.0\";\n    url = \"https://raw.githubusercontent.com/ccbkv/pica_configs/refs/heads/master/mxs.js\";\n\n    // \u6f2b\u753b\u6e90\u8bbe\u7f6e\u9879\n    settings = {\n        // \u57df\u540d\u9009\u62e9\u529f\u80fd\n        domains: {\n            title: \"\u9009\u62e9\u57df\u540d\",\n            type: \"select\",\n            options: [\n                { value: \"https://www.mxshm.top\", text: \"mxshm.top\" },\n                { value: \"https://www.jjmhw1.top\", text: \"jjmhw1.top\" },\n                { value: \"https://www.jjmh.top\", text: \"jjmh.top\" },\n                { value: \"https://www.jjmh.cc\", text: \"jjmh.cc\" },\n                { value: \"https://www.wzd1.cc\", text: \"wzd1.cc\" },\n                { value: \"https://www.wzdhm1.cc\", text: \"wzdhm1.cc\" },\n                { value: \"https://www.ikanwzd.cc\", text: \"ikanwzd.cc\" }\n            ],\n            default: \"https://www.mxshm.top\"\n        },\n        \n        // \u57df\u540d\u68c0\u6d4b\u529f\u80fd\n        domainCheck: {\n            title: \"\u68c0\u6d4b\u5f53\u524d\u57df\u540d\",\n            type: \"callback\",\n            buttonText: \"\u68c0\u6d4b\",\n            callback: () => {\n                const currentDomain = this.loadSetting(\"domains\");\n                const startTime = Date.now();\n                let isCompleted = false;\n                \n                // \u663e\u793a\u52a0\u8f7d\u5bf9\u8bdd\u6846\n                const loadingId = UI.showLoading(() => {\n                    UI.showMessage(\"\u68c0\u6d4b\u5df2\u53d6\u6d88\");\n                    isCompleted = true;\n                });\n                \n                // 10\u79d2\u8d85\u65f6\u68c0\u6d4b\n                setTimeout(() => {\n                    if (!isCompleted) {\n                        UI.cancelLoading(loadingId);\n                        UI.showMessage(\"\u274c \u8fde\u63a5\u8d85\u65f6\uff0c\u53ef\u80fd\u9700\u8981 \ud83d\ude80\");\n                        isCompleted = true;\n                    }\n                }, 10000);\n                \n                // \u6d4b\u8bd5\u7f51\u7edc\u8fde\u63a5\n                Network.get(currentDomain).then(res => {\n                    if (isCompleted) return;\n                    const delay = Date.now() - startTime;\n                    UI.cancelLoading(loadingId);\n                    UI.showMessage(`\u2705 \u8fde\u63a5\u6b63\u5e38\uff0c\u5ef6\u8fdf: ${delay}ms`);\n                    isCompleted = true;\n                }).catch(() => {\n                    if (isCompleted) return;\n                    UI.cancelLoading(loadingId);\n                    UI.showMessage(\"\u274c \u8fde\u63a5\u5931\u8d25\uff0c\u53ef\u80fd\u9700\u8981 \ud83d\ude80\");\n                    isCompleted = true;\n                });\n            }\n        }\n    };\n\n    // \u83b7\u53d6\u57fa\u7840URL\n    get baseUrl() {\n        return this.loadSetting(\"domains\");\n    }\n\n    // \u89e3\u6790\u666e\u901a\u6f2b\u753b\u5217\u8868\n    parseComicList(items) {\n        const comics = [];\n        \n        for (let item of items) {\n            // \u63d0\u53d6\u6f2b\u753bID\n            const linkElem = item.querySelector(\"a[href^='/book/']\");\n            const id = linkElem.attributes.href.split(\"/\").pop();\n            \n            // \u63d0\u53d6\u6807\u9898\u548c\u4f5c\u8005\n            const title = item.querySelector(\".title a\")?.text?.trim();\n            const author = item.querySelector(\"span a\")?.text?.trim();\n            \n            // \u63d0\u53d6\u63cf\u8ff0\u4fe1\u606f\n            const description = item.querySelector(\".chapter\")?.text?.replace(/^\u66f4\u65b0/, \"\")?.replace(/\\s+/g, \" \")?.trim() || item.querySelector(\".zl\")?.text?.trim();\n\n            // \u9a8c\u8bc1\u5fc5\u8981\u5b57\u6bb5\u5e76\u521b\u5efa\u6f2b\u753b\u5bf9\u8c61\n            if (id && title) {\n                comics.push(new Comic({\n                    id: id,\n                    title: title,\n                    subTitle: author,\n                    cover: `${this.baseUrl}/static/upload/book/${id}/cover.jpg`,\n                    description: description\n                }));\n            }\n        }\n        \n        return comics;\n    }\n\n    // \u89e3\u6790\u70ed\u95e8\u6f2b\u753b\u5217\u8868\n    parseHotComicList(items) {\n        const comics = [];\n        \n        for (let item of items) {\n            // \u63d0\u53d6\u6f2b\u753bID\n            const linkElem = item.querySelector(\".cover a[href^='/book/']\");\n            const id = linkElem.attributes.href.split(\"/\").pop();\n            \n            // \u63d0\u53d6\u6807\u9898\u3001\u4f5c\u8005\u548c\u70b9\u51fb\u91cf\n            const title = item.querySelector(\".info .title a\")?.text?.trim();\n            const author = item.querySelector(\".info .desc\")?.text?.trim();\n            const clickCount = item.querySelector(\".info .subtitle span a\")?.text?.trim();\n\n            // \u63d0\u53d6\u6807\u7b7e\u4fe1\u606f\n            const tags = [];\n            const tagElems = item.querySelectorAll(\".info .tag a\");\n            for (let tagElem of tagElems) {\n                if (tagElem.text) tags.push(tagElem.text.trim());\n            }\n\n            // \u9a8c\u8bc1\u5fc5\u8981\u5b57\u6bb5\u5e76\u521b\u5efa\u6f2b\u753b\u5bf9\u8c61\n            if (id && title) {\n                comics.push(new Comic({\n                    id: id,\n                    title: title,\n                    subTitle: author,\n                    cover: `${this.baseUrl}/static/upload/book/${id}/cover.jpg`,\n                    tags: tags,\n                    description: `\u70ed\u5ea6: \ud83d\udd25${clickCount}`\n                }));\n            }\n        }\n        \n        return comics;\n    }\n\n    // \u89e3\u6790\u8bc4\u8bba\u5217\u8868\n    parseCommentList(items) {\n        const comments = [];\n        \n        for (let item of items) {\n            // \u63d0\u53d6\u8bc4\u8bba\u4fe1\u606f\n            const userName = item.querySelector(\".title\")?.text?.trim();\n            const content = item.querySelector(\".content\")?.text?.trim();\n            const time = item.querySelector(\".bottom\")?.text?.match(/\\d{4}-\\d{2}-\\d{2}/)?.[0]?.trim();\n            const avatar = item.querySelector(\".cover img\")?.attributes?.src;\n\n            // \u9a8c\u8bc1\u5fc5\u8981\u5b57\u6bb5\u5e76\u521b\u5efa\u8bc4\u8bba\u5bf9\u8c61\n            if (userName && content) {\n                comments.push(new Comment({\n                    userName: userName,\n                    avatar: `${this.baseUrl}${avatar}`,\n                    content: content,\n                    time: time\n                }));\n            }\n        }\n        \n        return comments;\n    }\n\n    // \u6267\u884c\u7f51\u7edc\u8bf7\u6c42\u5e76\u8fd4\u56deHTML\u6587\u6863\u5bf9\u8c61\n    async fetchDocument(url) {\n        const res = await Network.get(url, {\n            \"User-Agent\": \"Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36\"\n        });\n        \n        if (res.status !== 200) {\n            throw `\u8bf7\u6c42\u5931\u8d25: ${res.status}`;\n        }\n        \n        return new HtmlDocument(res.body);\n    }\n\n    // === \u63a2\u7d22\u9875\u9762\u914d\u7f6e ===\n    explore = [\n        {\n            title: \"\u6f2b\u5c0f\u8086\",\n            type: \"multiPartPage\",\n            load: async (page) => {\n                const doc = await this.fetchDocument(this.baseUrl);\n\n                // \u6700\u8fd1\u66f4\u65b0\u90e8\u5206\n                const updateSection = {\n                    title: \"\u6700\u8fd1\u66f4\u65b0\",\n                    comics: this.parseComicList(doc.querySelectorAll(\".index-manga .mh-item\")),\n                    viewMore: {\n                        page: \"category\",\n                        attributes: { category: \"\u6700\u8fd1\u66f4\u65b0\" }\n                    }\n                };\n\n                // \u70ed\u95e8\u6f2b\u753b\u90e8\u5206\n                const hotSection = {\n                    title: \"\u70ed\u95e8\u6f2b\u753b\",\n                    comics: this.parseHotComicList(doc.querySelectorAll(\".index-original .index-original-list li\")),\n                    viewMore: {\n                        page: \"category\", \n                        attributes: { category: \"\u6392\u884c\u699c\" }\n                    }\n                };\n\n                // \u5b8c\u7ed3\u4f18\u9009\u90e8\u5206\n                const endSection = {\n                    title: \"\u5b8c\u7ed3\u4f18\u9009\",\n                    comics: this.parseComicList(doc.querySelectorAll(\".box-body .mh-item\")),\n                    viewMore: {\n                        page: \"category\",\n                        attributes: { category: \"\u5168\u90e8\u6f2b\u753b\" }\n                    }\n                };\n\n                doc.dispose();\n                return [updateSection, hotSection, endSection];\n            }\n        }\n    ];\n\n    // === \u5206\u7c7b\u9875\u9762\u914d\u7f6e ===\n    category = {\n        title: \"\u6f2b\u5c0f\u8086\",\n        parts: [\n            {\n                name: \"\u63a8\u8350\",\n                type: \"fixed\",\n                categories: [\"\u6700\u8fd1\u66f4\u65b0\", \"\u6392\u884c\u699c\", \"\u5168\u90e8\u6f2b\u753b\"],\n                itemType: \"category\"\n            },\n            {\n                name: \"\u9898\u6750\",\n                type: \"fixed\",\n                categories: [\n                    \"\u90fd\u5e02\", \"\u6821\u56ed\", \"\u9752\u6625\", \"\u6027\u611f\", \"\u957f\u817f\", \"\u591a\u4eba\", \"\u5fa1\u59d0\", \"\u5de8\u4e73\",\n                    \"\u65b0\u5a5a\", \"\u5ab3\u5987\", \"\u66a7\u6627\", \"\u6e05\u7eaf\", \"\u8c03\u6559\", \"\u5c11\u5987\", \"\u98ce\u9a9a\", \"\u540c\u5c45\", \n                    \"\u6deb\u4e71\", \"\u597d\u53cb\", \"\u5973\u795e\", \"\u8bf1\u60d1\", \"\u5077\u60c5\", \"\u51fa\u8f68\", \"\u6b63\u59b9\", \"\u5bb6\u6559\"\n                ],\n                itemType: \"category\"\n            }\n        ],\n        enableRankingPage: false\n    };\n\n    // === \u5206\u7c7b\u6f2b\u753b\u52a0\u8f7d\u914d\u7f6e ===\n    categoryComics = {\n        // \u52a0\u8f7d\u5206\u7c7b\u6f2b\u753b\n        load: async (category, param, options, page) => {\n            // \u6839\u636e\u5206\u7c7b\u6784\u5efa\u4e0d\u540c\u7684\u8bf7\u6c42URL\n            let url;\n            if (category === \"\u6700\u8fd1\u66f4\u65b0\") {\n                url = `${this.baseUrl}/update?page=${page}`;\n            } else if (category === \"\u6392\u884c\u699c\") {\n                url = `${this.baseUrl}/rank`;\n            } else {\n                const tag = (category !== \"\u5168\u90e8\u6f2b\u753b\") ? category : \"\u5168\u90e8\";\n                const area = options[0] || \"-1\";\n                const end = options[1] || \"-1\";\n                url = `${this.baseUrl}/booklist?tag=${encodeURIComponent(tag)}&area=${area}&end=${end}&page=${page}`;\n            }\n\n            const doc = await this.fetchDocument(url);\n            let comics = [];\n\n            // \u6392\u884c\u699c\u7279\u6b8a\u5904\u7406\n            if (category === \"\u6392\u884c\u699c\") {\n                const selectedRank = options[0] || \"new\";\n                const rankMapping = {\n                    \"new\": \"\u65b0\u4e66\u699c\",\n                    \"popular\": \"\u4eba\u6c14\u699c\", \n                    \"end\": \"\u5b8c\u7ed3\u699c\",\n                    \"recommend\": \"\u63a8\u8350\u699c\"\n                };\n\n                // \u67e5\u627e\u5bf9\u5e94\u7684\u6392\u884c\u699c\u5217\u8868\n                const rankLists = doc.querySelectorAll(\".mh-list.col3.top-cat li\");\n                let targetList = null;\n\n                for (let list of rankLists) {\n                    const titleElem = list.querySelector(\".title\");\n                    if (titleElem) {\n                        const title = titleElem.text.trim();\n                        if (title === rankMapping[selectedRank]) {\n                            targetList = list;\n                            break;\n                        }\n                    }\n                }\n\n                if (!targetList) {\n                    doc.dispose();\n                    throw \"\u672a\u627e\u5230\u5bf9\u5e94\u7684\u6392\u884c\u699c\";\n                }\n\n                comics = this.parseComicList(targetList.querySelectorAll(\".mh-item.horizontal, .mh-itme-top\"));\n            } else {\n                // \u666e\u901a\u5206\u7c7b\u5904\u7406\n                comics = this.parseComicList(doc.querySelectorAll(\".mh-list.col7 .mh-item\"));\n            }\n\n            // \u89e3\u6790\u6700\u5927\u9875\u6570\uff08\u6392\u884c\u699c\u4e0d\u5206\u9875\uff09\n            let maxPage = 1;\n            if (category !== \"\u6392\u884c\u699c\") {\n                const pageLinks = doc.querySelectorAll(\".pagination a[href*='page=']\");\n                for (let link of pageLinks) {\n                    const match = link.attributes.href.match(/page=(\\d+)/);\n                    if (match) {\n                        const pageNum = parseInt(match[1]);\n                        if (!isNaN(pageNum) && pageNum > maxPage) {\n                            maxPage = pageNum;\n                        }\n                    }\n                }\n            }\n\n            doc.dispose();\n            return { comics, maxPage };\n        },\n\n        // \u52a8\u6001\u52a0\u8f7d\u5206\u7c7b\u9009\u9879\n        optionLoader: async (category, param) => {\n            if (category === \"\u6700\u8fd1\u66f4\u65b0\") {\n                return [];\n            } else if (category === \"\u6392\u884c\u699c\") {\n                return [{\n                    options: [\n                        \"new-\u65b0\u4e66\u699c\",\n                        \"popular-\u4eba\u6c14\u699c\", \n                        \"end-\u5b8c\u7ed3\u699c\",\n                        \"recommend-\u63a8\u8350\u699c\"\n                    ]\n                }];\n            } else {\n                return [\n                    {\n                        label: \"\u5730\u533a\",\n                        options: [\n                            \"-\u5168\u90e8\",\n                            \"1-\u97e9\u56fd\", \n                            \"2-\u65e5\u672c\",\n                            \"3-\u53f0\u6e7e\"\n                        ]\n                    },\n                    {\n                        label: \"\u72b6\u6001\",\n                        options: [\n                            \"-\u5168\u90e8\",\n                            \"0-\u8fde\u8f7d\",\n                            \"1-\u5b8c\u7ed3\"\n                        ]\n                    }\n                ];\n            }\n        }\n    };\n\n    // === \u641c\u7d22\u529f\u80fd\u914d\u7f6e ===\n    search = {\n        // \u641c\u7d22\u6f2b\u753b\n        load: async (keyword, options, page) => {\n            const url = `${this.baseUrl}/search?keyword=${encodeURIComponent(keyword)}`;\n            const doc = await this.fetchDocument(url);\n            const comics = this.parseComicList(doc.querySelectorAll(\".mh-item\"));\n            \n            doc.dispose();\n            return {\n                comics: comics,\n                maxPage: 1\n            };\n        },\n        enableTagsSuggestions: false\n    };\n\n    // === \u6f2b\u753b\u8be6\u60c5\u548c\u9605\u8bfb\u529f\u80fd\u914d\u7f6e ===\n    comic = {\n        // \u52a0\u8f7d\u6f2b\u753b\u8be6\u60c5\n        loadInfo: async (id) => {\n            const url = `${this.baseUrl}/book/${id}`;\n            const doc = await this.fetchDocument(url);\n\n            // \u63d0\u53d6\u6807\u9898\u4fe1\u606f\n            const title = doc.querySelector(\".info h1\")?.text?.trim();\n\n            // \u63d0\u53d6\u526f\u6807\u9898\u4fe1\u606f\uff08\u522b\u540d\u548c\u4f5c\u8005\uff09\n            let author = \"\";\n            let subTitle = \"\";\n            const subTitleElems = doc.querySelectorAll(\".info .subtitle\");\n            for (let elem of subTitleElems) {\n                const text = elem.text;\n                if (text.includes(\"\u522b\u540d\uff1a\")) subTitle = text.replace(\"\u522b\u540d\uff1a\", \"\").trim();\n                if (text.includes(\"\u4f5c\u8005\uff1a\")) author = text.replace(\"\u4f5c\u8005\uff1a\", \"\").trim();\n            }\n            const authors = author ? author.split(\"&\").map(a => a.trim()).filter(a => a) : [];\n\n            // \u63d0\u53d6\u5176\u4ed6\u4fe1\u606f\uff08\u72b6\u6001\u3001\u5730\u533a\u3001\u66f4\u65b0\u65f6\u95f4\u3001\u70b9\u51fb\u91cf\u548c\u63cf\u8ff0\u4fe1\u606f\uff09\n            let status = \"\";\n            let area = \"\";\n            let updateTime = \"\";\n            let clickCount = \"\";\n            const tipElems = doc.querySelectorAll(\".info .tip span\");\n            for (let elem of tipElems) {\n                const text = elem.text;\n                if (text.includes(\"\u72b6\u6001\uff1a\")) status = elem.querySelector(\"span\")?.text?.trim();\n                if (text.includes(\"\u5730\u533a\uff1a\")) area = elem.querySelector(\"a\")?.text?.trim();\n                if (text.includes(\"\u66f4\u65b0\u65f6\u95f4\uff1a\")) updateTime = elem.text.replace(\"\u66f4\u65b0\u65f6\u95f4\uff1a\", \"\").trim();\n                if (text.includes(\"\u70b9\u51fb\uff1a\")) clickCount = elem.text.replace(\"\u70b9\u51fb\uff1a\", \"\").trim();\n            }\n            const description = doc.querySelector(\".info .content\")?.text?.trim();\n\n            // \u63d0\u53d6\u6807\u7b7e\u4fe1\u606f\n            const tagList = [];\n            const tagElems = doc.querySelectorAll(\".info .tip a[href*='tag=']\");\n            for (let elem of tagElems) {\n                const tagName = elem.text?.trim();\n                if (tagName) tagList.push(tagName);\n            }\n\n            // \u63d0\u53d6\u7ae0\u8282\u5217\u8868\n            const chapters = {};\n            const chapterElems = doc.querySelectorAll(\"#detail-list-select li a\");\n            for (let elem of chapterElems) {\n                const chapterUrl = elem.attributes?.href;\n                const chapterTitle = elem.text?.trim();\n                if (chapterUrl && chapterTitle) {\n                    const chapterId = chapterUrl.split(\"/\").pop();\n                    if (chapterId) chapters[chapterId] = chapterTitle;\n                }\n            }\n\n            // \u63d0\u53d6\u8bc4\u8bba\u548c\u63a8\u8350\u6f2b\u753b\n            const comments = this.parseCommentList(doc.querySelectorAll(\".view-comment-main .postlist li.dashed\"));\n            const recommend = this.parseComicList(doc.querySelectorAll(\".index-manga .mh-item\"));\n\n            doc.dispose();\n            \n            // \u521b\u5efa\u5e76\u8fd4\u56de\u6f2b\u753b\u8be6\u60c5\u5bf9\u8c61\n            return new ComicDetails({\n                title: title,\n                subTitle: subTitle,\n                cover: `${this.baseUrl}/static/upload/book/${id}/cover.jpg`,\n                description: description,\n                tags: {\n                    \"\u4f5c\u8005\": authors,\n                    \"\u9898\u6750\": tagList,\n                    \"\u5730\u533a\": [area],\n                    \"\u72b6\u6001\": [status],\n                    \"\u70ed\u5ea6\": [`\ud83d\udd25${clickCount}`]\n                },\n                chapters: chapters,\n                recommend: recommend,\n                commentCount: comments.length,\n                updateTime: updateTime,\n                url: url,\n                comments: comments\n            });\n        },\n\n        // \u52a0\u8f7d\u7ae0\u8282\u56fe\u7247\n        loadEp: async (comicId, epId) => {\n            const url = `${this.baseUrl}/chapter/${epId}`;\n            const doc = await this.fetchDocument(url);\n\n            // \u63d0\u53d6\u61d2\u52a0\u8f7d\u56fe\u7247\n            const images = [];\n            const imageElems = doc.querySelectorAll(\"img.lazy\");\n            for (let img of imageElems) {\n                const src = img.attributes?.[\"data-original\"];\n                const image = src.replace(/https?:\\/\\/[^\\/]+/, this.baseUrl);\n                if (image) images.push(image);\n            }\n\n            if (images.length === 0) {\n                doc.dispose();\n                throw \"\u672c\u7ae0\u4e2d\u672a\u627e\u5230\u56fe\u7247\";\n            }\n\n            doc.dispose();\n            return {\n                images: images\n            };\n        },\n\n        // \u52a0\u8f7d\u8bc4\u8bba\u5217\u8868\n        loadComments: async (comicId, subId, page, replyTo) => {\n            const url = `${this.baseUrl}/book/${comicId}`;\n            const doc = await this.fetchDocument(url);\n\n            const comments = this.parseCommentList(doc.querySelectorAll(\".view-comment-main .postlist li.dashed\"));\n\n            doc.dispose();\n            return {\n                comments: comments,\n                maxPage: 1\n            };\n        },\n\n        // \u5904\u7406\u6807\u7b7e\u70b9\u51fb\u4e8b\u4ef6\n        onClickTag: (namespace, tag) => {\n            // \u4f5c\u8005\u6807\u7b7e\u8df3\u8f6c\u5230\u641c\u7d22\u9875\u9762\n            if (namespace === \"\u4f5c\u8005\") {\n                return {\n                    page: \"search\",\n                    attributes: {\n                        keyword: tag\n                    }\n                };\n            }\n            // \u9898\u6750\u6807\u7b7e\u8df3\u8f6c\u5230\u5206\u7c7b\u9875\u9762\n            else if (namespace === \"\u9898\u6750\") {\n                return {\n                    page: \"category\",\n                    attributes: {\n                        category: tag\n                    }\n                };\n            }\n        },\n        enableTagsTranslate: false\n    };\n}";
__SOURCE_CODE["goda"] = "/**\n * \u7ae0\u8282\u56fe\u7247\u89e3\u7801\u5668 \u2014 \u79fb\u690d\u81ea keiyoushi/extensions-source (PR #16898)\n *\n * /api/v2/chapter/getinfo \u63a5\u53e3\u8fd4\u56de\u7684\u56fe\u7247\u5217\u8868\u662f\u6df7\u6dc6\u540e\u7684\u5b57\u7b26\u4e32\uff0c\u800c\u975e\u666e\u901a\u6570\u7ec4\u3002\n * \u6b64\u89e3\u7801\u5668\u5c06\u8be5\u5b57\u7b26\u4e32\u8fd8\u539f\u4e3a\u539f\u59cb JSON \u56fe\u7247\u6570\u7ec4\u3002\n *\n * \u89e3\u7801\u6d41\u7a0b\uff1a\u53bb\u9664 \"J7r\" \u524d\u7f00 / \"nQ\" \u540e\u7f00 \u2192 \u6309 \"kD\" \u548c \"W4s\" \u6807\u8bb0\u62c6\u5206\u4e3a 3 \u6bb5\n * \u2192 \u91cd\u65b0\u6392\u5e8f\u4e3a \u6bb53+\u6bb51+\u6bb52 \u2192 \u6bcf\u9694\u4e00\u4e2a 7 \u5b57\u7b26\u5757\u53cd\u8f6c \u2192 \u5c06\u81ea\u5b9a\u4e49\u5b57\u6bcd\u8868\u6620\u5c04\u56de\n * \u6807\u51c6 base64url \u2192 base64 \u89e3\u7801 \u2192 UTF-8 JSON\u3002\n */\nconst STD = \"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_\";\nconst CUSTOM = \"_-9876543210abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ\";\nconst DECODE_PREFIX = \"J7r\";\nconst DECODE_MARKER1 = \"kD\";\nconst DECODE_MARKER2 = \"W4s\";\nconst DECODE_SUFFIX = \"nQ\";\nconst DECODE_GROUP = 7;\n\n// \u9884\u8ba1\u7b97\u7684\u67e5\u627e\u8868\uff1a\u81ea\u5b9a\u4e49\u5b57\u6bcd\u8868\u5b57\u7b26\u7801 \u2192 \u6807\u51c6 base64url \u5b57\u7b26\u7801\uff08-1 \u8868\u793a\u65e0\u6548\uff09\nconst DECODE_TABLE = new Array(128).fill(-1);\nfor (let i = 0; i < CUSTOM.length; i++) {\n    DECODE_TABLE[CUSTOM.charCodeAt(i)] = STD.charCodeAt(i);\n}\n\nfunction decodeChapterImages(input) {\n    if (typeof input !== \"string\" || !input.startsWith(DECODE_PREFIX) || !input.endsWith(DECODE_SUFFIX)) {\n        throw \"\u672a\u77e5\u7684\u7ae0\u8282\u6570\u636e\u683c\u5f0f\";\n    }\n    const body = input.substring(DECODE_PREFIX.length, input.length - DECODE_SUFFIX.length);\n    const payloadLen = body.length - DECODE_MARKER1.length - DECODE_MARKER2.length;\n    if (payloadLen <= 0) {\n        throw \"\u672a\u77e5\u7684\u7ae0\u8282\u6570\u636e\u683c\u5f0f\";\n    }\n\n    const aLen = Math.floor(payloadLen / 3);\n    const bLen = Math.floor((payloadLen - aLen) / 2);\n    const cLen = payloadLen - aLen - bLen;\n\n    const part1 = body.substring(0, bLen);\n    const marker1 = body.substring(bLen, bLen + DECODE_MARKER1.length);\n    const part2 = body.substring(bLen + DECODE_MARKER1.length, bLen + DECODE_MARKER1.length + cLen);\n    const marker2 = body.substring(bLen + DECODE_MARKER1.length + cLen, bLen + DECODE_MARKER1.length + cLen + DECODE_MARKER2.length);\n    const part3 = body.substring(bLen + DECODE_MARKER1.length + cLen + DECODE_MARKER2.length);\n\n    if (marker1 !== DECODE_MARKER1 || marker2 !== DECODE_MARKER2 || part3.length !== aLen) {\n        throw \"\u672a\u77e5\u7684\u7ae0\u8282\u6570\u636e\u683c\u5f0f\";\n    }\n\n    // \u91cd\u65b0\u6392\u5e8f\uff1a\u6bb53 + \u6bb51 + \u6bb52\n    const reordered = part3 + part1 + part2;\n\n    // \u53bb\u952f\u9f7f\uff1a\u6bcf\u9694\u4e00\u4e2a GROUP \u957f\u5ea6\u7684\u5757\u505a\u53cd\u8f6c\n    let unzigzagged = \"\";\n    for (let i = 0, block = 0; i < reordered.length; i += DECODE_GROUP, block++) {\n        const chunk = reordered.substring(i, Math.min(i + DECODE_GROUP, reordered.length));\n        unzigzagged += (block % 2 === 1) ? chunk.split('').reverse().join('') : chunk;\n    }\n\n    // \u5c06\u81ea\u5b9a\u4e49\u5b57\u6bcd\u8868\u6620\u5c04\u4e3a\u6807\u51c6 base64url\n    let standard = \"\";\n    for (let i = 0; i < unzigzagged.length; i++) {\n        const code = unzigzagged.charCodeAt(i);\n        const mapped = code < DECODE_TABLE.length ? DECODE_TABLE[code] : -1;\n        if (mapped < 0) {\n            throw \"\u65e0\u6548\u7684\u7ae0\u8282\u6570\u636e\u5b57\u7b26\";\n        }\n        standard += String.fromCharCode(mapped);\n    }\n\n    // Base64 \u89e3\u7801\uff08\u7eaf JS \u5b9e\u73b0\uff0cvenera \u8fd0\u884c\u65f6\u4e0d\u652f\u6301 atob\uff09\u3002\u5148\u5c06 base64url \u8f6c\u4e3a\u6807\u51c6 base64\u3002\n    const standardBase64 = standard.replace(/-/g, '+').replace(/_/g, '/');\n    const json = decodeBase64(standardBase64);\n    return JSON.parse(json);\n}\n\n/**\n * \u7eaf JavaScript base64 \u89e3\u7801\u5668\uff08venera \u8fd0\u884c\u65f6\u7f3a\u5c11 atob\uff09\u3002\n * \u5c06 base64 \u89e3\u7801\u4e3a\u5b57\u8282\u5b57\u7b26\uff0c\u4f9b JSON \u89e3\u6790\u4f7f\u7528\u3002\n */\nfunction decodeBase64(str) {\n    const chars = \"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/\";\n    str = str.replace(/=+$/, \"\");\n\n    let result = \"\";\n    let i = 0;\n    while (i < str.length) {\n        const enc1 = chars.indexOf(str.charAt(i));\n        const enc2 = chars.indexOf(str.charAt(i + 1));\n        const enc3 = str.charAt(i + 2) ? chars.indexOf(str.charAt(i + 2)) : -1;\n        const enc4 = str.charAt(i + 3) ? chars.indexOf(str.charAt(i + 3)) : -1;\n\n        if (enc1 < 0 || enc2 < 0) {\n            throw \"Invalid base64 character\";\n        }\n\n        result += String.fromCharCode((enc1 << 2) | (enc2 >> 4));\n        if (enc3 >= 0) {\n            result += String.fromCharCode(((enc2 & 15) << 4) | (enc3 >> 2));\n        }\n        if (enc4 >= 0) {\n            result += String.fromCharCode(((enc3 & 3) << 6) | enc4);\n        }\n\n        i += 4;\n    }\n    return result;\n}\n\n/** @type {import('./_venera_.js')} */\nclass Goda extends ComicSource {\n  // \u6ce8\u610f\uff1a\u6807\u8bb0\u4e3a [\u53ef\u9009] \u7684\u5b57\u6bb5\u5982\u679c\u4e0d\u4f7f\u7528\uff0c\u5e94\u5c06\u5176\u5220\u9664\n\n  // \u6e90\u540d\u79f0\n  name = \"GoDa\u6f2b\u753b\"\n\n  // \u6e90\u552f\u4e00\u6807\u8bc6\n  key = \"goda\"\n\n  version = \"1.2.1\"\n\n  minAppVersion = \"1.4.0\"\n\n  // \u66f4\u65b0\u5730\u5740\n  url = \"https://raw.githubusercontent.com/ccbkv/pica_configs/refs/heads/master/goda.js\"\n\n  settings = {\n    domains: {\n      title: \"\u57df\u540d\",\n      type: \"input\",\n      default: \"godamh.com\"\n    },\n    api: {\n      title: \"API\u57df\u540d\",\n      type: \"input\",\n      default: \"v2.apikk.top\"\n    },\n    image: {\n      title: \"\u56fe\u7247\u57df\u540d\",\n      type: \"input\",\n      default: \"c-nd3-1.6wm.top\"\n    }\n  }\n\n  get baseUrl() {\n    return `https://${this.loadSetting(\"domains\")}`;\n  }\n\n  get apiUrl() {\n    return `https://${this.loadSetting(\"api\")}/api/v2`;\n  }\n\n  get imageUrl() {\n    return `https://${this.loadSetting(\"image\")}`;\n  }\n\n  get headers() {\n    return {\n      \"User-Agent\": \"Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:144.0) Gecko/20100101 Firefox/144.0\",\n      \"Referer\": this.baseUrl\n    };\n  }\n\n  parseComics(doc) {\n    const result = [];\n    for (let item of doc.querySelectorAll(\".pb-2\")) {\n      const link = item.querySelector(\"a\");\n      const titleEl = item.querySelector(\"h3\");\n      const img = item.querySelector(\"img\");\n      if (link && titleEl && img && link.attributes[\"href\"] && img.attributes[\"src\"]) {\n        result.push(new Comic({\n          id: link.attributes[\"href\"],\n          title: titleEl.text,\n          cover: img.attributes[\"src\"]\n        }));\n      }\n    }\n    return result;\n  }\n\n  // \u53d1\u73b0\u9875\u5217\u8868\n  explore = [\n    {\n      // \u9875\u9762\u6807\u9898\n      // title \u7528\u4e8e\u6807\u8bc6\u9875\u9762\uff0c\u5fc5\u987b\u552f\u4e00\n      title: this.name,\n\n      /// multiPartPage \u6216 multiPageComicList \u6216 mixed\n      type: \"multiPartPage\",\n\n      load: async () => {\n        const res = await Network.get(this.baseUrl, this.headers);\n        const document = new HtmlDocument(res.body);\n        const result = [{ title: \"\u8fd1\u671f\u66f4\u65b0\", comics: [], viewMore: null }];\n        for (let item of document.querySelector(\".pb-unit-md\").querySelectorAll(\".slicarda\")) {\n          result[0].comics.push(new Comic({\n            id: item.attributes[\"href\"],\n            title: item.querySelector(\"h3\").text,\n            cover: item.querySelector(\"img\").attributes[\"src\"]\n          }))\n        }\n        const cardlists = document.querySelectorAll(\".cardlist\");\n        const hometitles = document.querySelectorAll(\".hometitle\");\n        for (let i = 0; i < hometitles.length; i++) {\n          result.push({\n            title: hometitles[i].querySelector(\"h2\").text,\n            comics: this.parseComics(cardlists[i]),\n            viewMore: {\n              page: \"category\",\n              attributes: {\n                category: hometitles[i].querySelector(\"h2\").text,\n                param: hometitles[i].attributes[\"href\"]\n              },\n            }\n          });\n        }\n        return result;\n      }\n    }\n  ]\n\n  // \u5206\u7c7b\n  category = {\n    /// \u5206\u7c7b\u9875\u6807\u9898\uff0c\u7528\u4e8e\u6807\u8bc6\u9875\u9762\uff0c\u5fc5\u987b\u552f\u4e00\n    title: this.name,\n    parts: [\n      {\n        name: \"\u7c7b\u578b\",\n        type: \"fixed\",\n        categories: [\n          \"\u5168\u90e8\",\n          \"\u97e9\u6f2b\",\n          \"\u70ed\u95e8\u6f2b\u753b\",\n          \"\u56fd\u6f2b\",\n          \"\u5176\u4ed6\",\n          \"\u65e5\u6f2b\",\n          \"\u6b27\u7f8e\"\n        ],\n        itemType: \"category\",\n        categoryParams: [\n          \"/manga\",\n          \"/manga-genre/kr\",\n          \"/manga-genre/hots\",\n          \"/manga-genre/cn\",\n          \"/manga-genre/qita\",\n          \"/manga-genre/jp\",\n          \"/manga-genre/ou-mei\"\n        ],\n      },\n      {\n        name: \"\u6807\u7b7e\",\n        type: \"fixed\",\n        categories: [\n          \"\u590d\u4ec7\",\n          \"\u53e4\u98ce\",\n          \"\u5947\u5e7b\",\n          \"\u9006\u88ad\",\n          \"\u5f02\u80fd\",\n          \"\u5b85\u5411\",\n          \"\u7a7f\u8d8a\",\n          \"\u70ed\u8840\",\n          \"\u7eaf\u7231\",\n          \"\u7cfb\u7edf\",\n          \"\u91cd\u751f\",\n          \"\u5192\u9669\",\n          \"\u7075\u5f02\",\n          \"\u5927\u5973\u4e3b\",\n          \"\u5267\u60c5\",\n          \"\u604b\u7231\",\n          \"\u7384\u5e7b\",\n          \"\u5973\u795e\",\n          \"\u79d1\u5e7b\",\n          \"\u9b54\u5e7b\",\n          \"\u63a8\u7406\",\n          \"\u730e\u5947\",\n          \"\u6cbb\u6108\",\n          \"\u90fd\u5e02\",\n          \"\u5f02\u5f62\",\n          \"\u9752\u6625\",\n          \"\u672b\u65e5\",\n          \"\u60ac\u7591\",\n          \"\u4fee\u4ed9\",\n          \"\u6218\u6597\"\n        ],\n        itemType: \"category\",\n        categoryParams: [\n          \"/manga-tag/fuchou\",\n          \"/manga-tag/gufeng\",\n          \"/manga-tag/qihuan\",\n          \"/manga-tag/nixi\",\n          \"/manga-tag/yineng\",\n          \"/manga-tag/zhaixiang\",\n          \"/manga-tag/chuanyue\",\n          \"/manga-tag/rexue\",\n          \"/manga-tag/chunai\",\n          \"/manga-tag/xitong\",\n          \"/manga-tag/zhongsheng\",\n          \"/manga-tag/maoxian\",\n          \"/manga-tag/lingyi\",\n          \"/manga-tag/danvzhu\",\n          \"/manga-tag/juqing\",\n          \"/manga-tag/lianai\",\n          \"/manga-tag/xuanhuan\",\n          \"/manga-tag/nvshen\",\n          \"/manga-tag/kehuan\",\n          \"/manga-tag/mohuan\",\n          \"/manga-tag/tuili\",\n          \"/manga-tag/lieqi\",\n          \"/manga-tag/zhiyu\",\n          \"/manga-tag/doushi\",\n          \"/manga-tag/yixing\",\n          \"/manga-tag/qingchun\",\n          \"/manga-tag/mori\",\n          \"/manga-tag/xuanyi\",\n          \"/manga-tag/xiuxian\",\n          \"/manga-tag/zhandou\"\n        ],\n      }\n    ],\n    // \u662f\u5426\u542f\u7528\u6392\u884c\u699c\u9875\u9762\n    enableRankingPage: false,\n  }\n\n  /// \u5206\u7c7b\u6f2b\u753b\u52a0\u8f7d\n  categoryComics = {\n    load: async (category, params, options, page) => {\n      const res = await Network.get(`${this.baseUrl}${params}/page/${page}`, this.headers);\n      if (res.status !== 200) {\n        throw `Invalid status code: ${res.status}`;\n      }\n      const document = new HtmlDocument(res.body);\n      let maxPage = null;\n      try {\n        maxPage = parseInt(document.querySelectorAll(\"button.text-small\").pop().text.replaceAll(\"\\n\", \"\").replaceAll(\" \", \"\"));\n      } catch(_) {\n        maxPage = 1;\n      }\n      return {\n        comics: this.parseComics(document),\n        maxPage: maxPage\n      };\n    }\n  }\n\n  /// \u641c\u7d22\u76f8\u5173\n  search = {\n    load: async (keyword, options, page) => {\n      const res = await Network.get(`${this.baseUrl}/s/${keyword}?page=${page}`, this.headers);\n      if (res.status !== 200) {\n        throw `Invalid status code: ${res.status}`;\n      }\n      const document = new HtmlDocument(res.body);\n      let maxPage = null;\n      try {\n        maxPage = parseInt(document.querySelectorAll(\"button.text-small\").pop().text.replaceAll(\"\\n\", \"\").replaceAll(\" \", \"\"));\n      } catch(_) {\n        maxPage = 1;\n      }\n      return {\n        comics: this.parseComics(document),\n        maxPage: maxPage\n      };\n    },\n    // \u662f\u5426\u542f\u7528\u6807\u7b7e\u5efa\u8bae\n    enableTagsSuggestions: false,\n  }\n\n  /// \u5355\u90e8\u6f2b\u753b\u76f8\u5173\n  comic = {\n    onThumbnailLoad: (url) => {\n      return {\n        headers: this.headers\n      }\n    },\n    loadInfo: async (id) => {\n      const res = await Network.get(this.baseUrl + id, this.headers);\n      if (res.status !== 200) {\n        throw `Invalid status code: ${res.status}`;\n      }\n      const document = new HtmlDocument(res.body);\n\n      const titleEl = document.querySelector(\".text-xl\");\n      const title = titleEl ? (titleEl.text || \"\").trim().split(\"   \")[0] : \"\";\n\n      const coverEl = document.querySelector(\".object-cover\");\n      const cover = (coverEl && coverEl.attributes && coverEl.attributes[\"src\"]) || \"\";\n\n      const descEl = document.querySelector(\"p.text-medium\");\n      const description = descEl ? (descEl.text || \"\") : \"\";\n\n      const infos = document.querySelectorAll(\"div.py-1\");\n      const tags = { \"\u4f5c\u8005\": [], \"\u7c7b\u578b\": [], \"\u6807\u7b7e\": [] };\n      if (infos && infos.length >= 3) {\n        if (infos[0]) {\n          for (let author of infos[0].querySelectorAll(\"a > span\")) {\n            let author_name = (author.text || \"\").trim();\n            if (author_name.endsWith(\",\")) {\n              author_name = author_name.slice(0, -1).trim();\n            }\n            if (author_name) tags[\"\u4f5c\u8005\"].push(author_name);\n          }\n        }\n        if (infos[1]) {\n          for (let category of infos[1].querySelectorAll(\"a > span\")) {\n            let category_name = (category.text || \"\").trim();\n            if (category_name.endsWith(\",\")) {\n              category_name = category_name.slice(0, -1).trim();\n            }\n            if (category_name) tags[\"\u7c7b\u578b\"].push(category_name);\n          }\n        }\n        if (infos[2]) {\n          for (let tag of infos[2].querySelectorAll(\"a\")) {\n            const tagText = (tag.text || \"\").replace(\"\\n\", \"\").replaceAll(\" \", \"\").replace(\"#\", \"\");\n            if (tagText) tags[\"\u6807\u7b7e\"].push(tagText);\n          }\n        }\n      }\n\n      const mangaEl = document.querySelector(\"#mangachapters\");\n      const mangaId = mangaEl && mangaEl.attributes ? mangaEl.attributes[\"data-mid\"] : null;\n      if (!mangaId) {\n        throw \"\u65e0\u6cd5\u83b7\u53d6\u6f2b\u753bID\";\n      }\n\n      const chapters = {};\n      const jsonRes = await Network.get(`${this.apiUrl}/manga/get?mid=${mangaId}&mode=all&t=${Date.now()}`, this.headers);\n      if (jsonRes.status !== 200) {\n        throw `Invalid status code: ${jsonRes.status}`;\n      }\n      try {\n        const jsonData = JSON.parse(jsonRes.body);\n        if (jsonData && jsonData[\"data\"] && jsonData[\"data\"][\"chapters\"]) {\n          for (let ch of jsonData[\"data\"][\"chapters\"]) {\n            if (ch[\"id\"] != null && ch[\"attributes\"] && ch[\"attributes\"][\"title\"] != null) {\n              chapters[`${mangaId}@${ch[\"id\"]}`] = ch[\"attributes\"][\"title\"];\n            }\n          }\n        }\n      } catch (e) {\n        throw \"\u7ae0\u8282\u6570\u636e\u89e3\u6790\u5931\u8d25\";\n      }\n\n      const recommend = [];\n      for (let item of document.querySelectorAll(\"div.cardlist > div.pb-2\")) {\n        const recLink = item.querySelector(\"a\");\n        const recTitle = item.querySelector(\"h3\");\n        const recImg = item.querySelector(\"img\");\n        if (recLink && recTitle && recImg && recLink.attributes[\"href\"] && recImg.attributes[\"src\"]) {\n          recommend.push(new Comic({\n            id: recLink.attributes[\"href\"],\n            title: recTitle.text,\n            cover: recImg.attributes[\"src\"]\n          }));\n        }\n      }\n      return new ComicDetails({\n        title: title,\n        cover: cover,\n        description: description,\n        tags: tags,\n        chapters: chapters,\n        recommend: recommend,\n      });\n    },\n\n    loadEp: async (comicId, epId) => {\n      if (!epId || !epId.includes(\"@\")) {\n        throw \"\u65e0\u6548\u7684\u7ae0\u8282ID\";\n      }\n      const ids = epId.split(\"@\");\n      const res = await Network.get(`${this.apiUrl}/chapter/getinfo?m=${ids[0]}&c=${ids[1]}`, this.headers);\n      if (res.status !== 200) {\n        throw `Invalid status code: ${res.status}`;\n      }\n      let jsonData;\n      try {\n        jsonData = JSON.parse(res.body);\n      } catch (e) {\n        throw \"\u7ae0\u8282\u6570\u636e\u89e3\u6790\u5931\u8d25\";\n      }\n\n      // \u7a7a\u503c\u5b89\u5168\u68c0\u67e5\uff1a\u9632\u6b62 API \u8fd4\u56de\u5f02\u5e38\u6570\u636e\u7ed3\u6784\u5bfc\u81f4\u5d29\u6e83\n      if (!jsonData || !jsonData[\"data\"] || !jsonData[\"data\"][\"info\"]\n          || !jsonData[\"data\"][\"info\"][\"images\"]) {\n        throw \"\u7ae0\u8282\u56fe\u7247\u6570\u636e\u4e3a\u7a7a\";\n      }\n      const imagesRaw = jsonData[\"data\"][\"info\"][\"images\"][\"images\"];\n\n      let imagesList;\n      if (typeof imagesRaw === \"string\") {\n        // v2 API\uff1a\u6df7\u6dc6\u5b57\u7b26\u4e32 \u2014 \u89e3\u7801\u8fd8\u539f\u4e3a JSON \u6570\u7ec4\n        imagesList = decodeChapterImages(imagesRaw);\n      } else if (Array.isArray(imagesRaw)) {\n        // v1 API\uff08\u5411\u540e\u517c\u5bb9\uff09\uff1a{url: \"...\", order: N} \u6570\u7ec4\n        imagesList = imagesRaw;\n      } else {\n        // \u672a\u77e5\u683c\u5f0f\u7684\u56fe\u7247\u6570\u636e\n        throw \"\u672a\u77e5\u7684\u56fe\u7247\u6570\u636e\u683c\u5f0f\";\n      }\n\n      const images = [];\n      for (let i of imagesList) {\n        if (i && i[\"url\"]) {\n          images.push(this.imageUrl + i[\"url\"]);\n        }\n      }\n      return { images };\n    },\n\n    // \u662f\u5426\u542f\u7528\u6807\u7b7e\u7ffb\u8bd1\n    enableTagsTranslate: false,\n  }\n}\n";

// ===== 门面 =====
/*
 * SourceEngine 门面：给原生侧调的稳定 API。
 * 所有方法同步返回 taskId，结果通过 __emit（原生事件队列）异步回传，
 * 避免 Android 的 evaluateJavascript 拿不到 Promise 返回值。
 *
 * 原生侧只需：
 *   1. 提供 __hostCall(msg) -> any（同步）
 *   2. 收到 event { id, ok, data | error } 后按 id 派发
 */
(function () {
  var S = this || globalThis;
  var loaded = {};
  var taskSeq = 0;
  var evtSink = null;

  function setEventSink(fn) { evtSink = fn; }

  function emit(o) {
    try {
      if (evtSink) evtSink(o);
      else if (typeof prompt === 'function') prompt('__event__', JSON.stringify(o));
    } catch (e) { }
  }

  function nextId() { return 't' + (++taskSeq) + '_' + Date.now(); }

  /** 深遍历把字符串 URL 过一遍线路改写（封面/正文图片也要跟着线路走） */
  function mapUrls(v, depth) {
    depth = depth || 0;
    if (depth > 6 || v == null) return v;
    if (typeof v === 'string') {
      if (v.indexOf('://') > 0) return R.rewrite(v);
      return v;
    }
    if (Array.isArray(v)) return v.map(function (x) { return mapUrls(x, depth + 1); });
    if (typeof v === 'object') {
      var o = {};
      for (var k in v) if (Object.prototype.hasOwnProperty.call(v, k)) o[k] = mapUrls(v[k], depth + 1);
      return o;
    }
    return v;
  }

  var R = null;

  function inst(key) {
    if (!loaded[key]) throw new Error('源未加载: ' + key);
    return loaded[key];
  }

  // 源脚本的 init() 必须跑一次（禁漫要在里面拉官方线路表，否则 baseUrl 是 undefined）
  var inited = {};
  // 惰性取图登记表：pages() 登记页数，pageImage() 逐页解真地址
  var LAZY = {};
  async function ensureInit(s, key) {
    if (inited[key]) return;
    inited[key] = true;
    if (typeof s.init === 'function') {
      try { await s.init(); } catch (e) { }
    }
  }

  /** 把 vendor 里的源码注册成实例 */
  function register(key, code) {
    try {
      var m = /class\s+(\w+)\s+extends\s+ComicSource/.exec(code);
      if (!m) throw new Error('找不到 ComicSource 子类');
      var factory = new Function(code + '\n;return ' + m[1] + ';');
      var Cls = factory();
      loaded[key] = new Cls();
      return true;
    } catch (e) {
      emit({ id: 'load', ok: false, error: String(e && e.message || e), key: key });
      return false;
    }
  }

  function run(fn) {
    var id = nextId();
    Promise.resolve().then(fn).then(function (data) {
      emit({ id: id, ok: true, data: mapUrls(data) });
    }, function (e) {
      var msg = String(e && e.message ? e.message : e);
      var code = errCode(msg);
      // 源脚本里「要登录」的原文五花八门（noyacg 直接 throw 'login'），
      // 界面不该去猜英文，这里统一成一句能直接显示的中文。
      if (code === 'login' && /^\s*(login|need ?login|not ?logged( ?in)?|login ?required)\s*$/i.test(msg)) {
        msg = '该源需要登录才能看内容';
      }
      emit({
        id: id, ok: false, error: msg, code: code,
        stack: e && e.stack ? String(e.stack).slice(0, 800) : ''
      });
    });
    return id;
  }

  /**
   * 失败原因归类。界面不该再去字符串里找「登录」，源脚本报的原文五花八门：
   *   net        连不上（DNS / TLS / 超时 / status 0）——多半是线路问题，提示开关代理
   *   login      要登录 / 登录态过期
   *   forbidden  站点拒绝（403，多为 Cloudflare 风控或防盗链）
   *   badjson    返回空串/HTML，通常是接口换了或者被拦
   */
  function errCode(msg) {
    var s = String(msg || '');
    var lo = s.toLowerCase();
    if (/invalid status code: *0\b|^http 0$|status code: *0\b|请求失败: *0|etimedout|econnrefused|enotfound|unable to resolve|could not resolve|socket|ssl|timed out|网络|超时/.test(lo)) return 'net';
    if (lo === 'login' || /未登录|需要登录|请先登录|not logged|login required|先登入/.test(lo)) return 'login';
    if (/status code: *40[13]|http 40[13]|forbidden/.test(lo)) return 'forbidden';
    if (/unexpected end of json|empty body|not valid json|读取为空/.test(lo)) return 'badjson';
    return 'other';
  }

  var API = {
    setEventSink: setEventSink,

    /** routesJson: routes.json 文本；storeJson: 上次保存的线路状态（可为 null） */
    init: function (routesJson, storeJson) {
      R = ComicRoute;
      var store = {
        load: function () { try { return storeJson ? JSON.parse(storeJson) : null; } catch (e) { return null; } },
        save: function (o) { emit({ id: 'route_state', ok: true, data: o }); }
      };
      R.init(routesJson, store);
      return true;
    },

    register: register,

    /** 探测交给原生做（原生能真并发；JS 侧同步 http 只能串行，5 个源要等十几秒） */
    routeCandidates: function () {
      if (!R) return [];
      var out = [];
      var st = R._state();
      (st.table ? st.table.sources : []).forEach(function (s) {
        s.channels.forEach(function (c, ci) {
          out.push({
            key: s.key, ci: ci, line: c.line, name: c.name,
            hosts: c.hosts || [],
            probe: c.probe || '',            // 探测路径，空=用 /；有的站首页能开但接口不通
            rewrite: s.rewrite !== false,
            current: (st.state[s.key] || {}).ci === ci
          });
        });
      });
      return out;
    },

    /** 原生探测完调这个：key + 通道序 + 可用 host（空串=该通道不通） */
    routeApply: function (key, ci, host) {
      if (!R) return false;
      var st = R._state();
      var cur = st.state[key];
      if (!host) {
        // 该通道没探通。只有在「还没有任何通道探通」时才记不通，
        // 否则「直连通了、VPN 没通」会把刚选好的直连状态冲掉。
        if (cur && cur.pick) return false;
        return R.markOffline(key);
      }
      // 已经由探测选中的通道更靠前（直连优先），后面的 VPN 不再顶掉它。
      // 原来的写法用默认 ci=0 判断，导致「直连探不通、VPN 探通」时永远拒绝采纳，
      // 源会一直停在「直连」，实际一条线路都没用上。
      if (cur && cur.pick && typeof cur.ci === 'number' && cur.ci < ci) return false;
      return R.setChannel(key, ci, host);
    },

    /** 探测某个源之前先重置成「未选」，否则上一轮选中的直连会挡住新结果 */
    routeBeginProbe: function (key) {
      if (!R) return false;
      var st = R._state().state[key];
      if (!st) return false;
      st.pick = false;
      st.offline = false;
      st.bad = [];
      st.probed = false;
      return true;
    },

    /** 探测结束：只把传进来的源标成已探过，没探通的直接记 offline（界面显示「不通」） */
    routeMarkProbed: function (key) {
      if (!R) return false;
      var st = R._state();
      var keys = key ? [key] : Object.keys(st.state);
      keys.forEach(function (k) {
        var s = st.state[k];
        if (!s) return;
        s.probed = true;
        s.at = Date.now();
        if (!s.pick) s.offline = true;
      });
      return true;
    },

    /** 同步拿线路状态（列表页直接显示，不用等探测） */
    routeStatus: function () { return R ? R.status() : []; },

    setHost: function (key, host) { return R ? R.setHost(key, host) : false; },

    /** 源列表 + 每个源支持的能力 */
    sources: function () {
      return Object.keys(loaded).map(function (k) {
        var s = loaded[k];
        var logged = false;
        try { logged = !!sendMessage({ method: 'load_data', key: k, data_key: '__logged' }); } catch (e) { }
        return {
          key: k,
          name: s.name || k,
          version: s.version || '',
          canLogin: !!(s.account && s.account.login),
          logged: logged,
          registerUrl: (s.account && s.account.registerWebsite) || '',
          canSearch: !!(s.search && (s.search.load || s.search.loadNext)),
          canExplore: !!s.explore,
          canFavorites: !!s.favorites,
          needLogin: !!(s.account && s.account.login && s.settings && s.settings.loginRequired)
        };
      });
    },

    isLogged: function (key) {
      // 登录成功时 login() 会 save_data('__logged')，登出时清掉。
      // 不能问宿主的 isLogged——宿主没有写入这条状态的入口。
      try { return !!sendMessage({ method: 'load_data', key: key, data_key: '__logged' }); } catch (e) { return false; }
    },

    /**
     * 账号页要的全部信息：登录态、能否账号登录、注册地址、以及源自己声明的信息项。
     * infoItems 里的 data 可能是函数（有的源还返回 Promise），一律求值成字符串；
     * action 型（例如 E-Hentai 的 cookies 管理）只回标题，界面按「不可点」显示。
     */
    account: function (key) {
      return run(async function () {
        var s = inst(key);
        await ensureInit(s, key);
        var acc = s.account || null;
        var saved = null;
        try { saved = sendMessage({ method: 'load_data', key: key, data_key: '__logged' }); } catch (e) { }
        var items = [];
        var list = (acc && acc.infoItems) || [];
        for (var i = 0; i < list.length; i++) {
          var it = list[i] || {};
          var val = '';
          try {
            if (typeof it.data === 'function') val = it.data();
            if (val && typeof val.then === 'function') val = await val;
          } catch (e) { val = ''; }
          if (val === null || val === undefined) val = '';
          if (typeof val === 'object') { try { val = JSON.stringify(val); } catch (e) { val = ''; } }
          items.push({ title: it.title || '', value: String(val), action: it.action || '' });
        }
        return {
          key: key,
          name: s.name || key,
          version: s.version || '',
          logged: !!saved,
          savedAccount: (saved && saved.account) || '',
          canLogin: !!(acc && acc.login),
          canWebLogin: !!(acc && acc.loginWithWebview),
          loginWebUrl: (acc && acc.loginWithWebview && acc.loginWithWebview.url) || '',
          registerUrl: (acc && acc.registerWebsite) || '',
          needLogin: !!(acc && acc.login && s.settings && s.settings.loginRequired),
          items: items
        };
      });
    },

    search: function (key, kw, optionsJson, page) {
      return run(async function () {
        var s = inst(key);
        if (!s.search) throw new Error('该源不支持搜索');
        await ensureInit(s, key);
        var given = optionsJson ? JSON.parse(optionsJson) : [];
        // 原生没传的组用默认值补满——源脚本普遍直接读 options[0]
        var groups = s.search.optionList || [];
        var opts = groups.map(function (g, i) {
          if (given[i] !== undefined && given[i] !== null) return given[i];
          return defaultOptionValue(g);
        });
        var r = (typeof s.search.load === 'function')
          ? await s.search.load(kw, opts, page || 1)
          : await s.search.loadNext(kw, opts, page > 1 ? String(page) : null);
        return { comics: (r && r.comics) || [], maxPage: r && r.maxPage, next: r && r.next };
      });
    },

    searchOptions: function (key) {
      try {
        var s = inst(key);
        var list = s.search && s.search.optionList;
        if (!Array.isArray(list)) return [];
        return list.map(function (g) {
          if (!g) return null;
          return {
            title: g.title || '',
            options: (g.options || []).map(function (o) {
              return typeof o === 'string' ? { value: o, text: o } : { value: o.value, text: o.text || o.value };
            })
          };
        }).filter(Boolean);
      } catch (e) { return []; }
    },

    explore: function (key, index) {
      return run(async function () {
        var s = inst(key);
        if (!Array.isArray(s.explore) || !s.explore.length) throw new Error('该源没有探索页');
        await ensureInit(s, key);
        var e0 = s.explore[index || 0];
        // multiPageComicList 的 load(page) 是 1-based，原来不传参 → (undefined-1)*20 = NaN
        // → Komiic 的 GraphQL 收到 offset: null 直接报错，界面就是一片空。
        var arg = (e0.type === 'mixed') ? 0 : 1;
        var r = (typeof e0.load === 'function') ? await e0.load(arg) : await e0.loadNext(null);
        return normalizeExplore(r, e0.title || '');
      });
    },

    exploreTitles: function (key) {
      try {
        var s = inst(key);
        if (!Array.isArray(s.explore)) return [];
        return s.explore.map(function (e) { return e.title || ''; });
      } catch (e) { return []; }
    },

    detail: function (key, id) {
      return run(async function () {
        var s = inst(key);
        if (!s.comic || !s.comic.loadInfo) throw new Error('该源不支持详情');
        await ensureInit(s, key);
        var d = await s.comic.loadInfo(id);
        var groups = normalizeChapters(d.chapters);
        // 图库类源（E-Hentai）没有章节，正文就是 maxPage 张图。
        // 不合成一个章节，界面会永远停在「0 章」进不去。
        if (!groups.length || !groups.some(function (g) { return g.items && g.items.length; })) {
          groups = [{ title: '', items: [{ id: '', title: '全部页面' }] }];
        }
        return {
          id: id,
          title: d.title || '',
          cover: d.cover || '',
          description: d.description || '',
          tags: d.tags || {},
          groups: groups,
          subId: d.subId || ''
        };
      });
    },

    pages: function (key, comicId, epId) {
      return run(async function () {
        var s = inst(key);
        if (!s.comic || !s.comic.loadEp) throw new Error('该源不支持取图');
        await ensureInit(s, key);
        var ep = await s.comic.loadEp(comicId, epId);
        var images = (ep && ep.images) || [];
        // 有的源 loadEp 只给页码，真地址要逐张过 onImageLoad（E-Hentai 就是）。
        // 一页一次 API + 1.5 秒，165 页预解要十几分钟——所以这里只登记页数，
        // 真地址由 pageImage 逐页解（原生按需拉 + 预取）。
        var onLoad = s.comic && s.comic.onImageLoad;
        var looksLikeUrl = images.length && /^https?:\/\//i.test(String(images[0]));
        if (typeof onLoad === 'function' && images.length && !looksLikeUrl) {
          LAZY[String(key) + '|' + String(comicId) + '|' + String(epId)] = {
            key: key, s: s, comicId: comicId, epId: epId, images: images, nl: null
          };
          return { title: (ep && ep.title) || '', lazy: true, count: images.length, images: [] };
        }
        return { title: (ep && ep.title) || '', images: images };
      });
    },

    /** 惰性取图：解第 index 页的真地址。原生可以乱序/并发调，nl 只做锦上添花。 */
    pageImage: function (key, comicId, epId, index) {
      return run(async function () {
        var s = inst(key);
        if (!s.comic || !s.comic.onImageLoad) throw new Error('该源不支持逐页取图');
        var lk = String(key) + '|' + String(comicId) + '|' + String(epId);
        var st = LAZY[lk];
        if (!st) {
          await ensureInit(s, key);
          var ep = await s.comic.loadEp(comicId, epId);
          st = LAZY[lk] = { key: key, s: s, comicId: comicId, epId: epId, images: (ep && ep.images) || [], nl: null };
        }
        var i = Number(index) || 0;
        if (i < 0 || i >= st.images.length) throw new Error('页码越界: ' + i);
        var r = await st.s.comic.onImageLoad(st.images[i], comicId, epId, st.nl);
        var url = '', headers = null;
        if (r && typeof r === 'object') {
          url = r.url ? String(r.url) : '';
          headers = r.headers || null;
          if (r.nl) st.nl = r.nl;
        } else if (typeof r === 'string') {
          url = r;
        }
        return { index: i, url: url, headers: headers };
      });
    },

    login: function (key, account, password) {
      return run(async function () {
        var s = inst(key);
        if (!s.account || !s.account.login) throw new Error('该源不支持登录');
        await ensureInit(s, key);
        var r = await s.account.login(account, password);
        if (!r) throw new Error('账号或密码不对');
        try {
          sendMessage({
            method: 'save_data', key: key, data_key: '__logged',
            data: { at: Date.now(), account: String(account || '') }
          });
        } catch (e) { }
        return { ok: true, account: String(account || '') };
      });
    },

    logout: function (key) {
      return run(async function () {
        var s = inst(key);
        if (s.account && s.account.logout) await s.account.logout();
        try { sendMessage({ method: 'delete_data', key: key, data_key: '__logged' }); } catch (e) { }
        return { ok: true };
      });
    },

    /** 设置项（给原生渲染表单） */
    settings: function (key) {
      try {
        var s = inst(key);
        var out = [];
        var st = s.settings || {};
        Object.keys(st).forEach(function (k) {
          var d = st[k];
          if (!d || typeof d !== 'object') return;
          out.push({
            key: k,
            title: d.title || k,
            type: d.type || 'input',
            default: d.default === undefined ? null : d.default,
            options: (d.options || []).map(function (o) {
              return typeof o === 'string' ? { value: o, text: o } : { value: o.value, text: o.text || o.value };
            })
          });
        });
        return out;
      } catch (e) { return []; }
    },

    getSetting: function (key, settingKey) {
      try { return sendMessage({ method: 'load_setting', setting_key: key + '.' + settingKey }); } catch (e) { return null; }
    },

    setSetting: function (key, settingKey, value) {
      try { return sendMessage({ method: 'save_setting', setting_key: key + '.' + settingKey, value: value }); } catch (e) { return null; }
    }
  };


  /**
   * 章节结构兼容层。见到的形态：
   *   Map<id,title>（禁漫）、Map<分组,Map<id,title>>、{分组:[{id,title}]}（拷贝）、
   *   {id:title}、纯数组、以及 value 为 undefined 的残废对象。
   * 一律摊成 [{ title: 分组名, items: [{ id, title }] }]。
   */
  function normalizeChapters(ch) {
    var groups = [];
    if (!ch) return groups;
    function isMap(x) { return x && typeof x.forEach === 'function' && typeof x.get === 'function'; }
    function titleOf(v, fallback) {
      if (typeof v === 'string') return v;
      if (v && typeof v === 'object' && typeof v.title === 'string') return v.title;
      return fallback || '';
    }
    function mapToItems(m) {
      var items = [];
      m.forEach(function (v, k) {
        items.push({ id: String(k), title: titleOf(v, String(k)) });
      });
      return items;
    }
    if (isMap(ch)) {
      var flat = [], hasNested = false;
      ch.forEach(function (v, k) {
        if (isMap(v)) { hasNested = true; groups.push({ title: String(k), items: mapToItems(v) }); }
        else flat.push({ id: String(k), title: titleOf(v, String(k)) });
      });
      if (flat.length) groups.unshift({ title: '', items: flat });
      return groups;
    }
    if (Array.isArray(ch)) {
      groups.push({ title: '', items: ch.filter(Boolean).map(function (c) { return { id: String(c.id), title: titleOf(c, '') }; }) });
      return groups;
    }
    if (typeof ch === 'object') {
      Object.keys(ch).forEach(function (g) {
        var v = ch[g];
        if (v === undefined || v === null) return;
        if (Array.isArray(v)) {
          groups.push({ title: g, items: v.filter(Boolean).map(function (c) { return { id: String(c.id), title: titleOf(c, '') }; }) });
        } else if (isMap(v)) {
          groups.push({ title: g, items: mapToItems(v) });
        } else if (typeof v === 'object') {
          groups.push({ title: g, items: [{ id: String(v.id === undefined ? g : v.id), title: titleOf(v, g) }] });
        } else if (typeof v === 'string') {
          groups.push({ title: g, items: [{ id: g, title: v }] });
        }
      });
    }
    return groups;
  }


  /**
   * 探索页结构兼容层。Venera 的 explore 各类型返回值不一样：
   *   multiPartPage        -> [{ title, comics, viewMore }]
   *   multiPageComicList   -> { comics, maxPage }
   *   singlePageWithMultiPart -> { 分区名: Comic[] }
   *   mixed                -> { data: [Comic[] | {title, comics}] }
   * 原生侧不该为这四种各写一遍解析，统一摊成
   *   { sections: [{ title, comics: [{id,title,cover,subTitle}] }], maxPage }
   */
  function normalizeExplore(r, fallbackTitle) {
    var sections = [];
    var maxPage = 0;
    function toList(arr) {
      if (!Array.isArray(arr)) return [];
      var out = [];
      for (var i = 0; i < arr.length; i++) {
        var c = arr[i];
        if (!c || typeof c !== 'object') continue;
        var id = (c.id === undefined || c.id === null) ? '' : String(c.id);
        if (!id) continue;
        out.push({
          id: id,
          title: c.title === undefined || c.title === null ? '' : String(c.title),
          cover: c.cover === undefined || c.cover === null ? '' : String(c.cover),
          subTitle: c.subTitle === undefined || c.subTitle === null ? '' : String(c.subTitle)
        });
      }
      return out;
    }
    function push(title, comics, viewMore) {
      var list = toList(comics);
      if (list.length) sections.push({ title: title || '', comics: list, viewMore: viewMore || '' });
    }
    function walk(node, title) {
      if (!node) return;
      if (Array.isArray(node)) {
        // 数组有两种意思：Comic[]，或者 [{title, comics}]（multiPartPage）
        var asSections = false;
        for (var k = 0; k < node.length; k++) {
          var el = node[k];
          if (el && typeof el === 'object' && (Array.isArray(el.comics) || Array.isArray(el.data))) {
            asSections = true;
            break;
          }
        }
        if (asSections) { for (var m = 0; m < node.length; m++) walk(node[m], title); }
        else push(title, node);
        return;
      }
      if (typeof node !== 'object') return;
      if (node.maxPage) maxPage = Math.max(maxPage, Number(node.maxPage) || 0);
      if (Array.isArray(node.comics)) { push(node.title || title, node.comics, node.viewMore); return; }
      if (Array.isArray(node.data)) {
        for (var i = 0; i < node.data.length; i++) walk(node.data[i], title);
        return;
      }
      var keys = Object.keys(node);
      for (var j = 0; j < keys.length; j++) {
        var v = node[keys[j]];
        if (Array.isArray(v)) push(keys[j], v);
        else if (v && typeof v === 'object') walk(v, v.title || keys[j]);
      }
    }
    walk(r, fallbackTitle);
    return { sections: sections, maxPage: maxPage };
  }

  /** "0-Misc" -> "0"；{value,text} -> value 的字符串 */
  function optionValue(o) {
    if (o === undefined || o === null) return '';
    if (typeof o === 'object') return (o.value === undefined || o.value === null) ? '' : String(o.value);
    var str = String(o);
    var i = str.indexOf('-');
    return i >= 0 ? str.slice(0, i) : str;
  }

  /**
   * 按 option 的 type 生成源脚本期望的默认值：
   *   multi-select 收到的是 JSON 数组字符串（E-Hentai 直接 JSON.parse(options[0])，
   *                 所以这里不能给单个值，更不能给 "0-Misc" 这种带文本的）；
   *   dropdown     没有 default 时给空串，等价于 "<none>"；
   *   select       有 default 用 default，否则取第一项。
   */
  function defaultOptionValue(g) {
    if (!g) return '';
    var type = g.type || 'select';
    var d = g.default;
    if (type === 'multi-select') {
      if (Array.isArray(d)) return JSON.stringify(d.map(String));
      if (d === undefined || d === null) return '[]';
      return typeof d === 'string' ? d : JSON.stringify([String(d)]);
    }
    if (type === 'dropdown') {
      if (d !== undefined && d !== null) return String(d);
      return optionValue(g.options ? g.options[0] : '');
    }
    if (d !== undefined && d !== null) return String(d);
    return optionValue(g.options ? g.options[0] : '');
  }

  function c2s(o) { return o && o.title ? o.title : ''; }

  S.SourceEngine = API;
})();

(function(){
  var fixed = ["jm", "copy_manga", "Komiic", "noyacg", "picacg", "ehentai", "htmanga"], extra = ["baozi", "zaimanhua", "comick", "mh18", "mxs", "goda"];
  SourceEngine.__fixed = fixed;
  SourceEngine.__extra = extra;
  function boot(){
    for (var k in __SOURCE_CODE) SourceEngine.register(k, __SOURCE_CODE[k]);
  }
  SourceEngine.boot = boot;
})();
