// 安卓宿主模拟（SourceEngine.kt 的行为字典），给回归测试用。
// 真机宿主 = 同步 __hostRaw：入参 JSON，返回 {"v":...} / {"__err":...}，字节打成 {"__b64":...}。
const crypto = require('crypto');

const b64 = b => Buffer.from(b).toString('base64');
const unb64 = s => Buffer.from(String(s), 'base64');
const md5 = b => crypto.createHash('md5').update(b).digest();

function packBytes(v) {
  if (v == null) return v;
  if (Buffer.isBuffer(v) || ArrayBuffer.isView(v)) return { __b64: Buffer.from(v).toString('base64') };
  if (Array.isArray(v)) return v.map(packBytes);
  if (typeof v === 'object') {
    const o = {};
    for (const k of Object.keys(v)) o[k] = packBytes(v[k]);
    return o;
  }
  return v;
}

function toBytes(v) {
  if (v == null) return Buffer.alloc(0);
  if (Buffer.isBuffer(v)) return v;
  if (typeof v === 'object' && typeof v.__b64 === 'string') return unb64(v.__b64);
  if (Array.isArray(v)) return Buffer.from(v.map(n => n & 0xff));
  if (typeof v === 'string') return Buffer.from(v, 'utf8');
  return Buffer.from(String(v));
}
const optString = v => (typeof v === 'string' ? v : v == null ? '' : JSON.stringify(v));

// convertMode: 'old' = 1.9.17 及之前（utf8 方向写反、base64 用 optString），'new' = 1.9.18
function makeConvert(convertMode) {
  return function convert(msg) {
    const t = String(msg.type || '').toLowerCase();
    const enc = !!msg.isEncode;
    if (convertMode === 'old') {
      switch (t) {
        case 'utf8': return enc ? toBytes(msg.value).toString('utf8') : optString(msg.value);
        case 'gbk': return enc ? toBytes(msg.value) : toBytes(msg.value).toString('utf8');
        case 'base64': return enc ? b64(toBytes(optString(msg.value))) : unb64(optString(msg.value));
        case 'md5': return md5(toBytes(msg.value));
        case 'aes-ecb': {
          const d = crypto.createDecipheriv('aes-' + toBytes(msg.key).length * 8 + '-ecb', toBytes(msg.key), null);
          return Buffer.concat([d.update(toBytes(msg.value)), d.final()]);
        }
        default: throw new Error('old convert 未实现 ' + t);
      }
    }
    const raw = toBytes(msg.value);
    switch (t) {
      case 'utf8': return enc ? raw : raw.toString('utf8');
      case 'gbk': return enc ? raw : raw.toString('utf8');
      case 'base64': return enc ? b64(raw) : unb64(raw.toString('utf8').trim());
      case 'md5': return md5(raw);
      case 'aes-ecb': {
        const d = crypto.createDecipheriv('aes-' + toBytes(msg.key).length * 8 + '-ecb', toBytes(msg.key), null);
        return Buffer.concat([d.update(raw), d.final()]);
      }
      default: throw new Error('new convert 未实现 ' + t);
    }
  };
}

/**
 * @param opts.convertMode 'old' | 'new'
 * @param opts.cookieSet    是否实现 cookie set（1.9.17 及之前两端都是空实现）
 * @param opts.http         (msg, jar) => {status, headers, body}
 */
function makeHost(opts) {
  const mode = opts.convertMode || 'new';
  const cookieSet = opts.cookieSet !== false;
  const http = opts.http;
  const kv = {};
  const settings = {};
  const jar = {}; // host -> [{name,value,domain}]

  const convert = makeConvert(mode);
  const hostMatch = (host, key) => {
    const h = String(host || '').toLowerCase();
    const k = String(key || '').toLowerCase().replace(/^\.+/, '');
    return !!h && !!k && (h === k || h.endsWith('.' + k));
  };
  const jarList = host => {
    const out = new Map();
    for (const key of Object.keys(jar).sort((a, b) => b.length - a.length)) {
      if (!hostMatch(host, key)) continue;
      for (const c of jar[key]) if (!out.has(c.name)) out.set(c.name, c.value);
    }
    return [...out].map(([name, value]) => ({ name, value }));
  };

  function hostCall(msg) {
    const key = msg.key || 'default';
    const host = (() => { try { return new URL(msg.url).hostname; } catch (e) { return ''; } })();
    switch (msg.method) {
      case 'http': {
        // 宿主会自动带上 jar 里的 Cookie，跟两端原生实现一致
        const withCookie = Object.assign({}, msg);
        withCookie.headers = Object.assign({}, msg.headers || {});
        const ck = jarList(host).map(c => `${c.name}=${c.value}`).join('; ');
        if (ck) withCookie.headers['Cookie'] = ck;
        return http(withCookie, jar);
      }
      case 'convert': return convert(msg);
      case 'cookie': {
        if (msg.function === 'get') return jarList(host).map(c => ({ name: c.name, value: c.value, domain: host }));
        if (msg.function === 'set') {
          if (!cookieSet) return null; // 旧实现的空分支
          const list = (msg.cookies || []).map(c => ({ name: c.name, value: c.value, domain: c.domain || host }));
          const names = new Set(list.map(c => c.name));
          jar[host] = (jar[host] || []).filter(c => !names.has(c.name)).concat(list);
          return null;
        }
        if (msg.function === 'delete') {
          const names = msg.names || [];
          for (const k of Object.keys(jar)) {
            if (!hostMatch(host, k)) continue;
            if (!names.length) delete jar[k];
            else jar[k] = jar[k].filter(c => !names.includes(c.name));
          }
          return null;
        }
        return null;
      }
      case 'load_data': return kv['d_' + key + '_' + msg.data_key] ?? null;
      case 'save_data': kv['d_' + key + '_' + msg.data_key] = msg.data; return null;
      case 'delete_data': delete kv['d_' + key + '_' + msg.data_key]; return null;
      case 'load_setting': return settings['s_' + key + '_' + msg.setting_key] ?? null;
      case 'save_setting': settings['s_' + key + '_' + msg.setting_key] = msg.value; return null;
      case 'isLogged': return false;
      case 'UI': return msg.function === 'showDialog' ? 0 : null;
      case 'random': return Math.random();
      case 'uuid': return crypto.randomUUID();
      case 'delay': return null;
      case 'getPlatform': return 'android';
      case 'getLocale': return 'zh';
      case 'log': return null;
      case 'jm_ui': return false;
      case 'htmanga_state': return {};
      default: throw new Error('未实现的宿主方法: ' + msg.method);
    }
  }

  return {
    jar, kv,
    __hostRaw(json) {
      try {
        const out = hostCall(JSON.parse(json));
        return JSON.stringify({ v: out === undefined ? null : packBytes(out) });
      } catch (e) {
        return JSON.stringify({ __err: String((e && e.message) || e) });
      }
    },
  };
}

module.exports = { makeHost, packBytes, b64, unb64, md5, toBytes };
