// 桥协议一致性测试：真机（Android/iOS）与这里的 __hostRaw 语义必须一致。
//   入参 {"method":...}，二进制字段是 {"__b64":"..."}；返回 {"v":...} / {"__err":...}
// 验证三件事：
//   ① 没登录的源不能显示「已登录」
//   ② Convert.encodeUtf8 / md5 / AES 这类二进制返回不是垃圾（禁漫签名、拷贝正文解密都靠它）
//   ③ fetch() / Network.fetchBytes 这条 bytes 通路能拿到 JSON（拷贝漫画 refreshAppApi）
const fs = require('fs');
const path = require('path');
const vm = require('vm');
const crypto = require('crypto');
const host = require('./host_node');
host.initRoute();   // 真机上线路线路层总是启用的；不启用的话 2026copy 这种镜像不会被改写

const ROOT = path.join(__dirname, '..');
const BUNDLE = fs.readFileSync(path.join(ROOT, 'bundle.js'), 'utf8');
const ROUTES = fs.readFileSync(path.join(ROOT, 'routes.json'), 'utf8');

const events = {};
const sandbox = {
  console: { log() {}, error() {}, warn() {}, info() {} },
  __hostRaw: host.__hostRaw,
  __hostSink: function (o) { if (o && o.id) events[o.id] = o; },
};
sandbox.globalThis = sandbox;
const ctx = vm.createContext(sandbox);

vm.runInContext(BUNDLE, ctx);
vm.runInContext('SourceEngine.setEventSink(function(o){ __hostSink(o); })', ctx);
vm.runInContext(`SourceEngine.init(${JSON.stringify(ROUTES)}, null)`, ctx);
vm.runInContext('SourceEngine.boot()', ctx);

async function call(method, ...args) {
  const id = vm.runInContext(
    `SourceEngine.${method}(${args.map((a) => JSON.stringify(a)).join(',')})`, ctx);
  if (!id || id === 'undefined') return { ok: false, error: '没拿到任务号' };
  const t0 = Date.now();
  while (Date.now() - t0 < 120000) {
    if (events[id]) return events[id];
    await new Promise((r) => setImmediate(r));
    vm.runInContext('0', ctx);
  }
  return { ok: false, error: 'timeout' };
}

(async () => {
  const keys = vm.runInContext('SourceEngine.sources().map(function(s){return s.key})', ctx);

  console.log('== ① 登录态 ==');
  let bad = 0;
  for (const k of keys) {
    vm.runInContext('__hostRaw(JSON.stringify({method:"delete_data",key:' + JSON.stringify(k) + ',data_key:"__logged"}))', ctx);
  }
  const srcs = vm.runInContext('SourceEngine.sources()', ctx);
  for (const s of srcs) {
    if (s.logged) { bad++; console.log('  ❌ ' + s.key + ' 误报已登录'); }
  }
  console.log(bad === 0 ? `  ✅ ${srcs.length} 个源全部未登录` : `  ❌ ${bad} 个误报`);

  console.log('== ② 二进制往返 ==');
  const md5hex = vm.runInContext(
    "Convert.hexEncode(Convert.md5(Convert.encodeUtf8('abc')))", ctx);
  const want = crypto.createHash('md5').update('abc').digest('hex');
  console.log(`  md5(abc) = ${md5hex}  ${md5hex === want ? '✅' : '❌ 期望 ' + want}`);
  const round = vm.runInContext(
    "Convert.decodeUtf8(Convert.encodeUtf8('画质漫画 abc'))", ctx);
  console.log(`  utf8 往返 = ${round}  ${round === '画质漫画 abc' ? '✅' : '❌'}`);
  const b64 = vm.runInContext("Convert.encodeBase64(Convert.encodeUtf8('hello'))", ctx);
  console.log(`  base64('hello') = ${b64}  ${b64 === 'aGVsbG8=' ? '✅' : '❌'}`);

  console.log('== ③ fetch() 取字节再解 JSON（拷贝漫画 refreshAppApi）==');
  const ex = await call('explore', 'copy_manga', 0);
  const secs = (ex.data && ex.data.sections) || [];
  console.log('  copy_manga explore ok=' + ex.ok, 'error=' + (ex.error || '-'),
    '分区=' + secs.length, '首区本数=' + ((secs[0] && secs[0].comics.length) || 0));
  if (String(ex.error || '').includes('Invalid status code')) console.log('  ❌ 还是 undefined status');

  console.log('== ④ 禁漫（token 走 md5 签名）==');
  const se = await call('search', 'jm', 'love', '[]', 1);
  console.log('  jm search ok=' + se.ok, 'error=' + (se.error || '-'),
    '本数=' + ((se.data && se.data.comics && se.data.comics.length) || 0));
})();
