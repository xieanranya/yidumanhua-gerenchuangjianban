// 端到端：E-Hentai 的 Cookie 登录。真引擎 bundle + 安卓宿主语义。
// 站点行为：带对了 ipb_member_id / ipb_pass_hash 才在导航栏出现用户名。
//   old = 1.9.17 及之前（cookie set 是空实现 → 4 个 Cookie 被丢掉）
//   new = 1.9.18（set 真写进 jar，父域 Cookie 会带到子域请求）
// 用法：node test_cookie_login.js
const fs = require('fs');
const path = require('path');
const vm = require('vm');
const { makeHost } = require('./host_android_sim');

const BUNDLE = fs.readFileSync(path.join(__dirname, '..', 'bundle.js'), 'utf8');
const ROUTES = fs.readFileSync(path.join(__dirname, '..', 'routes.json'), 'utf8');

// 登录后主站右上角有指向论坛个人页的链接（showuser=<id>），游客没有 —— 源靠这个判断
const MEMBER_HTML = '<html><body><p class="ip">Visited: <a href="https://forums.e-hentai.org/index.php?showuser=123">tester</a></p></body></html>';
// 游客页没有 showuser 链接
const GUEST_HTML = '<html><body><p class="ip">Not logged in</p></body></html>';

function ehApi(msg) {
  const url = String(msg.url || '');
  if (url.indexOf('e-hentai.org') >= 0 && url.indexOf('forums.e-hentai.org') < 0) {
    const ck = (msg.headers && msg.headers['Cookie']) || '';
    const member = /ipb_member_id=123/.test(ck) && /ipb_pass_hash=abc/.test(ck);
    return { status: 200, headers: {}, body: member ? MEMBER_HTML : GUEST_HTML };
  }
  return { status: 200, headers: {}, body: '<html></html>' };
}

function runLogin(cookieSet) {
  const host = makeHost({ convertMode: 'new', cookieSet, http: ehApi });
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

  // 账号页声明了 4 个字段：ipb_member_id / ipb_pass_hash / igneous / star
  const vals = JSON.stringify(['123', 'abc', '', '']);
  const id = vm.runInContext(`SourceEngine.login("ehentai", ${JSON.stringify(vals)}, "")`, ctx);
  return new Promise(res => {
    waiters[id] = o => res({
      result: o,
      logged: vm.runInContext('SourceEngine.isLogged("ehentai")', ctx),
      // 源脚本里的 this.isLogged 走的是宿主 isLogged 方法（曾经恒 false，EH 的收藏/观看页就是被它挡住的）
      hostLogged: JSON.parse(host.__hostRaw(JSON.stringify({ method: 'isLogged', key: 'ehentai' }))).v,
    });
    setTimeout(() => res({ result: { ok: false, error: '测试超时' }, logged: null, hostLogged: null }), 8000);
  });
}

(async () => {
  const oldRun = await runLogin(false);
  console.log('[旧实现 1.9.17] Cookie 登录:', JSON.stringify({ ok: oldRun.result.ok, error: oldRun.result.error }));
  const newRun = await runLogin(true);
  console.log('[新实现 1.9.18] Cookie 登录:', JSON.stringify({ ok: newRun.result.ok, error: newRun.result.error }), 'isLogged:', newRun.logged);

  console.log('           宿主 isLogged（源脚本看到的那条）:', newRun.hostLogged);
  const ok = !oldRun.result.ok && /Cookie 无效/.test(String(oldRun.result.error))
    && newRun.result.ok && newRun.logged === true && newRun.hostLogged === true;
  console.log(ok ? '\n✓ 复现了旧版「Cookie 填对也提示无效」，并确认新版能登录' : '\n✗ 结果不符合预期');
  process.exit(ok ? 0 : 1);
})();
