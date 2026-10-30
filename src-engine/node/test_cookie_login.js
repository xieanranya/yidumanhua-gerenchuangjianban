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

// 登录后 home.php 给个人页（GP 统计），游客 302 被宿主跟随回首页（首页无登录态）
// —— 源靠页面里有没有 "GP Gained" 判断
const MEMBER_HTML = '<html><body><h2>GP Gained</h2><table><tr><td>from gallery visits</td></tr></table></body></html>';
// 游客被 302 弹回首页：列表页 HTML，没有任何 GP 统计
const GUEST_HTML = '<html><body><div id="nb"><a href="/">Front Page</a><a href="/watched">Watched</a></div><div class="gl1c">list</div></body></html>';

function ehApi(msg) {
  const url = String(msg.url || '');
  if (url.indexOf('e-hentai.org') >= 0 && url.indexOf('forums.e-hentai.org') < 0) {
    const h = msg.headers || {};
    const ck = h['Cookie'] || h['cookie'] || '';
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
  // 1.9.41 起 validate 把 Cookie 直接放进请求头（双保险）：
  // 即使宿主 Cookie 表是空实现（旧 1.9.17 的 bug），校验请求也带得上 Cookie。
  // 所以两条链路都应当登录成功 —— 这条测试现在守的是「手动 Cookie 头兜底有效」。
  const ok = oldRun.result.ok && /旧/.test('') === false
    && newRun.result.ok && newRun.logged === true && newRun.hostLogged === true;
  console.log(ok ? '\n✓ 两条链路都登录成功（手动 Cookie 头兜底生效，jar 实现不再影响校验）' : '\n✗ 结果不符合预期');
  process.exit(ok ? 0 : 1);
})();
