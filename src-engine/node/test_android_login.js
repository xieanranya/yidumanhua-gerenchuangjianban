// 端到端：用真引擎 bundle + 「安卓宿主语义」把禁漫登录跑一遍。
// 不打真网络：把 jm 的 /login 拦下来，按站点的方式返回 AES 加密的 {uid,...}，
// 然后看引擎能不能解出来。convert 用两套实现各跑一次：
//   old = 1.9.17 及之前的 SourceEngine.kt（utf8 解码返回 {"__b64"} 这层壳）
//   new = 1.9.18
// 用法：node test_android_login.js
const fs = require('fs');
const path = require('path');
const vm = require('vm');
const crypto = require('crypto');
const { makeHost, md5 } = require('./host_android_sim');

const BUNDLE = fs.readFileSync(path.join(__dirname, '..', 'bundle.js'), 'utf8');
const ROUTES = fs.readFileSync(path.join(__dirname, '..', 'routes.json'), 'utf8');

// 站点：登录响应 = AES-256-ECB(JSON) 的 base64，包在 {data: "..."} 里。
// key 由请求头 tokenparam 里的时间戳算出，跟源脚本完全一致。
function jmApi(msg) {
  const url = String(msg.url || '');
  const headers = msg.headers || {};
  const time = parseInt(String(headers.tokenparam || '').split(',')[0], 10);

  if (/\/login$/.test(url)) {
    const key = Buffer.from(md5(Buffer.from(`${time}185Hcomic3PAPP7R`, 'utf8')).toString('hex'), 'utf8');
    const c = crypto.createCipheriv('aes-' + key.length * 8 + '-ecb', key, null);
    const payload = JSON.stringify({ uid: 123456, user: 'tester' });
    const cipher = Buffer.concat([c.update(Buffer.from(payload, 'utf8')), c.final()]);
    return { status: 200, headers: {}, body: JSON.stringify({ data: cipher.toString('base64') }) };
  }
  if (/\/setting\?/.test(url)) {
    return { status: 200, headers: {}, body: JSON.stringify({ img_host: '' }) };
  }
  return { status: 200, headers: {}, body: '{}' };
}

function runLogin(convertMode) {
  const host = makeHost({ convertMode, cookieSet: true, http: jmApi });
  const waiters = {};
  const sandbox = {
    __hostRaw: host.__hostRaw,
    prompt: (kind, payload) => {
      if (kind === '__event__') {
        const o = JSON.parse(payload);
        if (o.id !== 'route_state' && waiters[o.id]) { waiters[o.id](o); delete waiters[o.id]; }
        return '';
      }
      if (kind === '__host__') return host.__hostRaw(payload);
      return '';
    },
    console: { log: () => { }, error: () => { }, warn: () => { } },
    TextDecoder, TextEncoder, URL, URLSearchParams,
    setTimeout: (fn, t) => setTimeout(fn, Math.min(t || 0, 5)),
    clearTimeout, setInterval: () => 0, clearInterval: () => { },
    atob: s => Buffer.from(s, 'base64').toString('binary'),
    btoa: s => Buffer.from(s, 'binary').toString('base64'),
  };
  sandbox.globalThis = sandbox;
  const ctx = vm.createContext(sandbox);
  vm.runInContext(BUNDLE, ctx, { filename: 'bundle.js' });
  vm.runInContext('SourceEngine.init(' + JSON.stringify(ROUTES) + ', null)', ctx);
  vm.runInContext('SourceEngine.setEventSink(function(o){ prompt("__event__", JSON.stringify(o)); })', ctx);
  vm.runInContext('SourceEngine.boot()', ctx);

  const id = vm.runInContext('SourceEngine.login("jm", "tester", "pass123")', ctx);
  return new Promise(res => {
    waiters[id] = o => res({ result: o, logged: vm.runInContext('SourceEngine.isLogged("jm")', ctx) });
    setTimeout(() => res({ result: { ok: false, error: '测试超时' }, logged: null }), 8000);
  });
}

(async () => {
  const oldRun = await runLogin('old');
  console.log('[旧实现 1.9.17] 禁漫登录:', JSON.stringify({ ok: oldRun.result.ok, error: oldRun.result.error }));
  const newRun = await runLogin('new');
  console.log('[新实现 1.9.18] 禁漫登录:', JSON.stringify({ ok: newRun.result.ok, error: newRun.result.error }), 'isLogged:', newRun.logged);

  const ok = !oldRun.result.ok && /账号或密码不对/.test(String(oldRun.result.error)) && newRun.result.ok && newRun.logged === true;
  console.log(ok ? '\n✓ 复现了旧版「密码对也报账号或密码不对」，并确认新版能登录' : '\n✗ 结果不符合预期');
  process.exit(ok ? 0 : 1);
})();
