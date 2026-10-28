// Node 版宿主：实现 __hostCall（同步），用于在没有 Swift/Kotlin 的机器上先验证源能不能跑通。
// 生产端（Android/iOS）实现同一套 method，行为必须与这里一致。
const { spawnSync } = require('child_process');
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const Route = require('../route.js');

const DATA = path.join(__dirname, '..', '..', '.srcdata');
fs.mkdirSync(DATA, { recursive: true });

let CURRENT_KEY = 'default';
let COOKIE_MODE = false;

// ---- 线路层：routes.json + 持久化状态 ----
const ROUTES_PATH = path.join(__dirname, '..', 'routes.json');
const ROUTE_STATE = path.join(DATA, 'route_state.json');
const routeStore = {
  load: () => { try { return JSON.parse(fs.readFileSync(ROUTE_STATE, 'utf8')); } catch (e) { return null; } },
  save: o => { try { fs.writeFileSync(ROUTE_STATE, JSON.stringify(o, null, 1)); } catch (e) { } },
};
function initRoute() {
  let table = null;
  try { table = JSON.parse(fs.readFileSync(ROUTES_PATH, 'utf8')); } catch (e) { }
  if (table) Route.init(table, routeStore);
  return Route;
}

function p(...a) { return path.join(DATA, ...a); }
function kvPath(key) { return p('kv_' + key + '.json'); }
function loadKV(key) { try { return JSON.parse(fs.readFileSync(kvPath(key), 'utf8')); } catch (e) { return {}; } }
function saveKV(key, obj) { fs.writeFileSync(kvPath(key), JSON.stringify(obj, null, 1)); }
function jarPath(key) { return p('jar_' + key + '.txt'); }

const DEFAULT_UA = 'Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36';

function toBuf(v) {
  if (v == null) return Buffer.alloc(0);
  if (Buffer.isBuffer(v)) return v;
  if (v instanceof Uint8Array) return Buffer.from(v);
  if (v instanceof ArrayBuffer) return Buffer.from(new Uint8Array(v));
  if (typeof v === 'string') return Buffer.from(v, 'utf8');
  return Buffer.from(String(v));
}

// ---- 桥协议：二进制用 {"__b64":"..."} 表示（与两端原生宿主一致）----
function packBytes(v) {
  if (v == null) return v;
  if (Buffer.isBuffer(v) || v instanceof Uint8Array) return { __b64: Buffer.from(v).toString('base64') };
  if (v instanceof ArrayBuffer) return { __b64: Buffer.from(new Uint8Array(v)).toString('base64') };
  if (Array.isArray(v)) return v.map(packBytes);
  if (typeof v === 'object') {
    const o = {};
    for (const k of Object.keys(v)) o[k] = packBytes(v[k]);
    return o;
  }
  return v;
}

function unpackBytes(v) {
  if (v == null || typeof v !== 'object') return v;
  if (typeof v.__b64 === 'string') return Buffer.from(v.__b64, 'base64');
  if (Array.isArray(v)) return v.map(unpackBytes);
  for (const k of Object.keys(v)) v[k] = unpackBytes(v[k]);
  return v;
}

// curlOnce 里 msg.data 可能是 Buffer（二进制上传），不能 JSON.stringify
function bodyArg(d) {
  if (typeof d === 'string') return d;
  if (Buffer.isBuffer(d)) return d;
  return JSON.stringify(d);
}
// 宿主必须负责 URL 规范化：源脚本经常把中文选项值（如 o=mr-最新）直接拼进 URL，
// 原始 UTF-8 字节发给服务器会 400。iOS 的 URL(string:) 更是直接返回 nil。
// 只编码非 ASCII 与空格，已有的 %XX 保持不动。
function normalizeUrl(u) {
  if (typeof u !== 'string') return u;
  return u.replace(/[^\x00-\x7F]|\s/g, m => encodeURIComponent(m));
}

function curlOnce(msg, url) {
  url = normalizeUrl(url);
  const method = (msg.http_method || 'GET').toUpperCase();
  const headers = msg.headers || {};
  const args = ['-sS', '-L', '--compressed', '--max-time', '30',
    '-X', method, '-b', jarPath(CURRENT_KEY), '-c', jarPath(CURRENT_KEY)];
  // 验证用：SRC_NOPROXY=1 模拟「国内直连」，SRC_PROXY=http://127.0.0.1:7897 模拟「手机开 VPN」。
  // 注意必须用 --proxy 显式参数——本机环境变量被沙箱的透明代理占着，改了也不生效。
  if (process.env.SRC_PROXY) args.push('--proxy', process.env.SRC_PROXY);
  else if (process.env.SRC_NOPROXY === '1') args.push('--noproxy', '*');

  const lower = {};
  for (const k of Object.keys(headers)) lower[k.toLowerCase()] = headers[k];
  if (!lower['user-agent']) args.push('-H', 'User-Agent: ' + DEFAULT_UA);
  if (!lower['accept']) args.push('-H', 'Accept: */*');
  for (const k of Object.keys(headers)) {
    if (headers[k] === undefined || headers[k] === null) continue;
    args.push('-H', k + ': ' + String(headers[k]));
  }
  if (msg.data !== undefined && msg.data !== null && method !== 'GET' && method !== 'HEAD') {
    args.push('--data-binary', bodyArg(msg.data));
  }
  args.push('-w', '\n__STATUS__%{http_code}', '--output', '-', url);

  const r = spawnSync('curl', args, { maxBuffer: 128 * 1024 * 1024, timeout: 45000 });
  if (r.error) return { error: String(r.error.message || r.error) };
  const buf = r.stdout || Buffer.alloc(0);
  const txt = buf.toString('latin1');
  const marker = txt.lastIndexOf('\n__STATUS__');
  if (process.env.SRC_DEBUG === '1') {
    // 注意：r.status 是 curl 的退出码，0 = 请求成功发出，不是 HTTP 状态码。
    // 以前日志只打退出码，看到「→ 0」会误以为请求失败。
    const hs = marker < 0 ? 'ERR' : (txt.slice(marker + 11).trim() || '0');
    console.error('[http]', method, url, '→ HTTP', hs, (r.status !== 0 ? 'curl=' + r.status : ''),
      (r.stderr || '').toString().slice(0, 160));
  }
  if (marker < 0) return { status: 0, headers: {}, body: msg.bytes ? buf : buf.toString('utf8'), error: 'no status' };
  const status = parseInt(txt.slice(marker + 11).trim(), 10) || 0;
  const body = buf.slice(0, marker);
  return { status, headers: {}, body: msg.bytes ? body : body.toString('utf8'), url };
}

// 带线路改写 + 失败回落：第一次不通就切下一条线路再试一次
// SRC_HOST_ROUTE=0 关掉宿主自己的改写，用来模拟安卓/iOS 那种「宿主只发请求、
// 线路交给引擎 prelude 处理」的形态，验证 prelude 里的改写是不是真的生效。
function doHttp(msg) {
  if (process.env.SRC_HOST_ROUTE === '0') {
    const r = curlOnce(msg, msg.url);
    return r;
  }
  const original = msg.url;
  const url1 = Route.rewrite(original);
  const r1 = curlOnce(msg, url1);
  const ok1 = r1 && r1.status >= 200 && r1.status <= 499;
  if (ok1) { Route.report(url1, r1.status); return r1; }

  const changed = Route.report(url1, r1 && r1.status);
  if (!changed) return r1;

  const url2 = Route.rewrite(original);
  console.error('[route] 回落', changed.from, '→', changed.to, '(' + changed.line + ')');
  const r2 = curlOnce(msg, url2);
  Route.report(url2, r2 && r2.status);
  return r2;
}

function convert(msg) {
  const t = String(msg.type || '').toLowerCase();
  const v = msg.value;
  switch (t) {
    case 'utf8':
      return msg.isEncode ? toBuf(String(v)) : toBuf(v).toString('utf8');
    case 'gbk':
      if (msg.isEncode) return toBuf(String(v)); // 原型足够：站点基本用 UTF-8
      try { return new TextDecoder('gbk').decode(toBuf(v)); } catch (e) { return toBuf(v).toString('utf8'); }
    case 'base64':
      return msg.isEncode ? toBuf(v).toString('base64') : toBuf(String(v) && !Buffer.isBuffer(v) ? Buffer.from(String(v), 'base64') : v);
    case 'md5': return crypto.createHash('md5').update(toBuf(v)).digest();
    case 'sha1': return crypto.createHash('sha1').update(toBuf(v)).digest();
    case 'sha256': return crypto.createHash('sha256').update(toBuf(v)).digest();
    case 'sha512': return crypto.createHash('sha512').update(toBuf(v)).digest();
    case 'hmac': {
      const d = crypto.createHmac(String(msg.hash || 'sha256').toLowerCase(), toBuf(msg.key)).update(toBuf(v)).digest();
      return msg.isString ? d.toString('hex') : d;
    }
    case 'aes-ecb': {
      const d = crypto.createDecipheriv('aes-' + toBuf(msg.key).length * 8 + '-ecb', toBuf(msg.key), null);
      d.setAutoPadding(true);
      return Buffer.concat([d.update(toBuf(v)), d.final()]);
    }
    case 'aes-cbc': {
      const key = toBuf(msg.key);
      const d = crypto.createDecipheriv('aes-' + key.length * 8 + '-cbc', key, toBuf(msg.iv));
      d.setAutoPadding(true);
      return Buffer.concat([d.update(toBuf(v)), d.final()]);
    }
    default:
      throw new Error('convert 未实现: ' + t);
  }
}

function __hostCall(msg) {
  if (process.env.SRC_DEBUG === '1') console.error('[host]', msg && msg.method, (msg && msg.url ? msg.url : '').slice(0, 90));
  if (!msg || typeof msg !== 'object') return null;
  switch (msg.method) {
    case 'http': return doHttp(msg);
    case 'convert': return convert(msg);
    case 'cookie': {
      const kv = loadKV('cookie_' + CURRENT_KEY);
      let host = msg.url;
      try { const u = new URL(msg.url); host = u.hostname; } catch (e) { }
      if (msg.function === 'get') return (kv[host] || []).map(c => ({ name: c.name, value: c.value, domain: c.domain }));
      if (msg.function === 'set') {
        kv[host] = (msg.cookies || []).map(c => ({ name: c.name, value: c.value, domain: c.domain }));
        saveKV('cookie_' + CURRENT_KEY, kv);
        fs.writeFileSync(jarPath(CURRENT_KEY),
          '# Netscape HTTP Cookie File\n' + kv[host].map(c => host + '\tFALSE\t/\tFALSE\t0\t' + c.name + '\t' + c.value).join('\n') + '\n');
        return null;
      }
      if (msg.function === 'delete') {
        if (msg.names && msg.names.length) kv[host] = (kv[host] || []).filter(c => !msg.names.includes(c.name));
        else delete kv[host];
        saveKV('cookie_' + CURRENT_KEY, kv);
        return null;
      }
      return null;
    }
    case 'load_data': { const kv = loadKV(CURRENT_KEY); return kv.data && msg.data_key in kv.data ? kv.data[msg.data_key] : null; }
    case 'save_data': { const kv = loadKV(CURRENT_KEY); kv.data = kv.data || {}; kv.data[msg.data_key] = msg.data; saveKV(CURRENT_KEY, kv); return null; }
    case 'delete_data': { const kv = loadKV(CURRENT_KEY); if (kv.data) delete kv.data[msg.data_key]; saveKV(CURRENT_KEY, kv); return null; }
    case 'load_setting': { const kv = loadKV(CURRENT_KEY); return kv.setting && msg.setting_key in kv.setting ? kv.setting[msg.setting_key] : null; }
    case 'isLogged': { const kv = loadKV(CURRENT_KEY); return !!(kv.data && kv.data['__logged']); }
    case 'UI': console.error('[UI]', msg.function, msg.title || '', msg.message || msg.content || ''); return msg.function === 'showDialog' ? 0 : null;
    case 'random': return Math.random() * ((msg.max ?? 1) - (msg.min ?? 0)) + (msg.min ?? 0);
    case 'uuid': return crypto.randomUUID();
    case 'delay': return null;
    case 'getPlatform': return 'android';
    case 'getLocale': return 'zh';
    case 'log': console.error('[log]', msg.level, msg.title, msg.content); return null;
    // 绅士漫画源会问原生侧「现在用哪个 API 域名」；没这个桥就用它自己的 fallbackApi。
    // 这里回别名层改写后的基地址，跟真机行为一致。
    case 'htmanga_state': {
      const base = Route.rewrite('https://www.wnacg.com/');
      const m = /^https?:\/\/([^\/]+)/.exec(base);
      return m ? { baseUrl: 'https://' + m[1] } : {};
    }
    default: throw new Error('未实现的宿主方法: ' + msg.method);
  }
}

// 真机走的入口：入参是 JSON 文本，返回也是 JSON 文本，
// 二进制字段用 {"__b64":"..."} 表示。与两端原生宿主语义完全一致。
function __hostRaw(json) {
  let msg;
  try { msg = JSON.parse(json); } catch (e) { return JSON.stringify({ __err: 'bad json' }); }
  msg = unpackBytes(msg);
  if (msg && typeof msg.key === 'string' && msg.key) CURRENT_KEY = msg.key;
  try {
    return JSON.stringify({ v: packBytes(__hostCall(msg)) });
  } catch (e) {
    return JSON.stringify({ __err: String((e && e.message) || e) });
  }
}

module.exports = { __hostCall, __hostRaw, setSourceKey: k => { CURRENT_KEY = k; }, DATA, toBuf, initRoute, Route };
