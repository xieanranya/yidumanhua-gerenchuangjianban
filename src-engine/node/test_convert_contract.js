// 宿主 convert / cookie 契约对照测试。
// 目的：把「Node 里通过、真机失败」这类问题挡在发版前。
//   参考实现 = host_node.js 的 convert（curl/crypto，跟站点实际行为一致）
//   旧安卓    = 1.9.17 及之前 SourceEngine.kt 的 convert（utf8 方向写反、base64 用 optString）
//   新安卓    = 本次修复后的逻辑
// 用法：node test_convert_contract.js
const crypto = require('crypto');
const assert = require('assert');

// ---------- 工具 ----------
const b64 = b => Buffer.from(b).toString('base64');
const unb64 = s => Buffer.from(String(s), 'base64');

// 引擎 prelude 把 Uint8Array 打包成 {"__b64":...} 传给宿主
const pack = b => (Buffer.isBuffer(b) ? { __b64: b64(b) } : b);

// ---------- 参考实现（与 host_node.js 一致）----------
function refConvert(msg) {
  const t = String(msg.type || '').toLowerCase();
  const v = msg.value;
  const toBuf = x => {
    if (x == null) return Buffer.alloc(0);
    if (Buffer.isBuffer(x)) return x;
    if (typeof x === 'object' && typeof x.__b64 === 'string') return unb64(x.__b64);
    if (typeof x === 'string') return Buffer.from(x, 'utf8');
    return Buffer.from(String(x));
  };
  switch (t) {
    case 'utf8': return msg.isEncode ? toBuf(v) : toBuf(v).toString('utf8');
    case 'base64': return msg.isEncode ? toBuf(v).toString('base64') : unb64(toBuf(v).toString('utf8'));
    case 'md5': return crypto.createHash('md5').update(toBuf(v)).digest();
    case 'aes-ecb': {
      const d = crypto.createDecipheriv('aes-' + toBuf(msg.key).length * 8 + '-ecb', toBuf(msg.key), null);
      return Buffer.concat([d.update(toBuf(v)), d.final()]);
    }
    default: throw new Error('ref 未实现 ' + t);
  }
}

// ---------- 旧安卓实现（照抄 1.9.17 的 Kotlin）----------
// 注意 Kotlin 的 JSONObject.toString()/optString() 对对象返回的是紧凑 JSON 文本，
// 不是 "[object Object]"——这里要照实模拟，否则测不出真机行为。
const optString = v => (typeof v === 'string' ? v : v == null ? '' : JSON.stringify(v));
function androidConvertOld(msg) {
  const bytes = v => {
    if (v == null) return Buffer.alloc(0);
    if (Buffer.isBuffer(v)) return v;
    if (typeof v === 'object' && typeof v.__b64 === 'string') return unb64(v.__b64);
    if (typeof v === 'string') return Buffer.from(v, 'utf8');
    return Buffer.from(String(v));
  };
  const type = String(msg.type || '').toLowerCase();
  const encode = !!msg.isEncode;
  const value = msg.value;
  switch (type) {
    // 两处都是错的：encode 该给字节却给了字符串；decode 直接把 {"__b64"} 的 JSON 文本当结果
    case 'utf8': return encode ? bytes(value).toString('utf8') : optString(value);
    case 'base64': return encode ? b64(bytes(optString(msg.value))) : unb64(optString(msg.value));
    case 'md5': return crypto.createHash('md5').update(bytes(value)).digest();
    case 'aes-ecb': {
      const d = crypto.createDecipheriv('aes-' + bytes(msg.key).length * 8 + '-ecb', bytes(msg.key), null);
      return Buffer.concat([d.update(bytes(value)), d.final()]);
    }
    default: throw new Error('old 未实现 ' + type);
  }
}

// ---------- 新安卓实现（本次修复后的 Kotlin）----------
function androidConvertNew(msg) {
  const bytes = v => {
    if (v == null) return Buffer.alloc(0);
    if (Buffer.isBuffer(v)) return v;
    if (typeof v === 'object' && typeof v.__b64 === 'string') return unb64(v.__b64);
    if (Array.isArray(v)) return Buffer.from(v.map(n => n & 0xff));
    if (typeof v === 'string') return Buffer.from(v, 'utf8');
    return Buffer.from(String(v));
  };
  const type = String(msg.type || '').toLowerCase();
  const encode = !!msg.isEncode;
  const raw = bytes(msg.value);
  switch (type) {
    case 'utf8': return encode ? raw : raw.toString('utf8');
    case 'base64': return encode ? b64(raw) : unb64(raw.toString('utf8').trim());
    case 'md5': return crypto.createHash('md5').update(raw).digest();
    case 'aes-ecb': {
      const d = crypto.createDecipheriv('aes-' + bytes(msg.key).length * 8 + '-ecb', bytes(msg.key), null);
      return Buffer.concat([d.update(raw), d.final()]);
    }
    default: throw new Error('new 未实现 ' + type);
  }
}

// ---------- 用例 ----------
let pass = 0, fail = 0;
function check(name, fn) {
  try { fn(); console.log('  ✓ ' + name); pass++; }
  catch (e) { console.log('  ✗ ' + name + '\n      ' + String(e.message).slice(0, 300)); fail++; }
}
const asBytes = v => (typeof v === 'string' ? Buffer.from(v, 'utf8') : Buffer.isBuffer(v) ? v : unb64(v.__b64));
const eqBytes = (a, b) => assert.ok(asBytes(a).equals(asBytes(b)), '字节不一致');

console.log('\n[1] utf8 编码（字符串 → 字节）');
check('参考：返回字节', () => eqBytes(refConvert({ type: 'utf8', value: '密码abc', isEncode: true }), Buffer.from('密码abc')));
check('旧安卓：返回字符串，且被当成字节（值相同，侥幸对）', () => eqBytes(androidConvertOld({ type: 'utf8', value: '密码abc', isEncode: true }), Buffer.from('密码abc')));
check('新安卓：返回字节', () => eqBytes(androidConvertNew({ type: 'utf8', value: '密码abc', isEncode: true }), Buffer.from('密码abc')));

console.log('\n[2] utf8 解码（字节 → 字符串）—— 禁漫解包就走这条');
const cipherBytes = Buffer.from('{"uid":123456,"user":"tester"}', 'utf8');
check('参考：还原原文', () => assert.strictEqual(refConvert({ type: 'utf8', value: pack(cipherBytes), isEncode: false }), cipherBytes.toString('utf8')));
check('旧安卓：返回 {"__b64":"..."} 这层壳（= 登录报「账号或密码不对」的原因）', () => {
  const out = androidConvertOld({ type: 'utf8', value: pack(cipherBytes), isEncode: false });
  assert.ok(String(out).startsWith('{"__b64"'), '预期是壳，实际: ' + out);
});
check('新安卓：还原原文', () => assert.strictEqual(androidConvertNew({ type: 'utf8', value: pack(cipherBytes), isEncode: false }), cipherBytes.toString('utf8')));

console.log('\n[3] base64 编码二进制（二进制 → base64）');
const blob = crypto.randomBytes(48);
check('参考：对字节编码', () => assert.strictEqual(refConvert({ type: 'base64', value: pack(blob), isEncode: true }), blob.toString('base64')));
check('旧安卓：把 JSON 文本当内容编码（全错）', () => {
  const out = androidConvertOld({ type: 'base64', value: pack(blob), isEncode: true });
  assert.notStrictEqual(out, blob.toString('base64'));
});
check('新安卓：对字节编码', () => assert.strictEqual(androidConvertNew({ type: 'base64', value: pack(blob), isEncode: true }), blob.toString('base64')));

console.log('\n[4] 禁漫 convertData 全链路（utf8 → md5 → hex → utf8 → AES-ECB → decodeUtf8）');
const plain = JSON.stringify({ uid: 987654, user: 'tester' });
const time = 1750000000;
const secret = `${time}185Hcomic3PAPP7R`;
function jmChain(host) {
  const md5 = host({ type: 'md5', value: host({ type: 'utf8', value: secret, isEncode: true }) });
  const keyBytes = host({ type: 'utf8', value: asBytes(md5).toString('hex'), isEncode: true });
  // 服务端：用同样的 key 加密
  const c = crypto.createCipheriv('aes-' + asBytes(keyBytes).length * 8 + '-ecb', asBytes(keyBytes), null);
  const payload = { data: Buffer.concat([c.update(Buffer.from(plain)), c.final()]).toString('base64') };
  const decrypted = host({ type: 'aes-ecb', value: pack(unb64(payload.data)), key: pack(asBytes(keyBytes)), isEncode: false });
  return host({ type: 'utf8', value: pack(asBytes(decrypted)), isEncode: false });
}
check('参考实现：解出 uid', () => assert.strictEqual(JSON.parse(jmChain(refConvert)).uid, 987654));
check('旧安卓：解出 {"__b64":...}，没有 uid（登录必失败）', () => {
  const parsed = JSON.parse(jmChain(androidConvertOld));
  assert.ok(!parsed.uid, '居然解出了 uid');
});
check('新安卓：解出 uid', () => assert.strictEqual(JSON.parse(jmChain(androidConvertNew)).uid, 987654));

// ---------- cookie 契约 ----------
console.log('\n[C] cookie set / get / 父域匹配 / 按名删除');
function makeJar() {
  const jar = {}; // host -> [{name,value,domain}]
  const hostMatch = (host, key) => {
    const h = host.toLowerCase(), k = key.toLowerCase().replace(/^\.+/, '');
    return !!h && !!k && (h === k || h.endsWith('.' + k));
  };
  return {
    set(url, cookies) {
      const host = new URL(url).hostname;
      const names = new Set(cookies.map(c => c.name));
      const old = (jar[host] || []).filter(c => !names.has(c.name));
      jar[host] = old.concat(cookies.map(c => ({ name: c.name, value: c.value, domain: c.domain || host })));
    },
    get(url) {
      const host = new URL(url).hostname;
      const out = new Map();
      for (const key of Object.keys(jar)) {
        if (!hostMatch(host, key)) continue;
        for (const c of jar[key]) if (!out.has(c.name)) out.set(c.name, c.value);
      }
      return [...out].map(([name, value]) => ({ name, value }));
    },
    del(url, names) {
      const host = new URL(url).hostname;
      for (const key of Object.keys(jar)) {
        if (!hostMatch(host, key)) continue;
        if (!names || !names.length) delete jar[key];
        else jar[key] = jar[key].filter(c => !names.includes(c.name));
      }
    },
  };
}
const EH = [
  { name: 'ipb_member_id', value: '123', domain: '.e-hentai.org' },
  { name: 'ipb_pass_hash', value: 'abc', domain: '.e-hentai.org' },
  { name: 'igneous', value: '', domain: '.e-hentai.org' },
  { name: 'star', value: '', domain: '.e-hentai.org' },
];
check('set 之后，父域 Cookie 能带到子域请求（forums.e-hentai.org）', () => {
  const j = makeJar();
  j.set('https://e-hentai.org', EH);
  const got = j.get('https://forums.e-hentai.org/');
  assert.strictEqual(got.length, 4);
  assert.strictEqual(got.find(c => c.name === 'ipb_member_id').value, '123');
});
check('按名删除只删指定项', () => {
  const j = makeJar();
  j.set('https://e-hentai.org', EH.concat([{ name: 'keep', value: '1' }]));
  j.del('https://e-hentai.org', ['ipb_pass_hash']);
  const got = j.get('https://e-hentai.org/');
  assert.ok(!got.find(c => c.name === 'ipb_pass_hash'));
  assert.ok(got.find(c => c.name === 'ipb_member_id'));
  assert.ok(got.find(c => c.name === 'keep'));
});

console.log(`\n结果：通过 ${pass}，失败 ${fail}\n`);
process.exit(fail ? 1 : 0);
