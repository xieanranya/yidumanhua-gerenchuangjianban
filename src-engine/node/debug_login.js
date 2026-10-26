// 登录接口通不通：用假账号看是否返回「明确的错误」而不是卡死/崩。
// 真账号由用户在 App 里输，这里只验证链路（网络 + 请求格式 + 错误回传）。
const fs = require('fs');
const path = require('path');
const vm = require('vm');
const host = require('./host_node');

const BUNDLE = fs.readFileSync(path.join(__dirname, '..', 'bundle.js'), 'utf8');
const ROUTES = fs.readFileSync(path.join(__dirname, '..', 'routes.json'), 'utf8');
const waiters = {};
const sandbox = {
  __hostRaw: host.__hostRaw,
  prompt: (k, p) => {
    if (k === '__event__') { const o = JSON.parse(p); if (o.id !== 'route_state' && waiters[o.id]) { const w = waiters[o.id]; delete waiters[o.id]; w(o); } return ''; }
    if (k === '__host__') return host.__hostRaw(p);
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
vm.runInContext('SourceEngine.boot()', ctx);
const call = js => { const id = vm.runInContext(js, ctx); return new Promise(res => { waiters[id] = res; setTimeout(() => { if (waiters[id]) { delete waiters[id]; res({ ok: false, error: 'timeout' }); } }, 40000); }); };

(async () => {
  for (const key of ['noyacg', 'picacg', 'copy_manga']) {
    host.setSourceKey(key);
    host.initRoute();
    const r = await call('SourceEngine.login(' + JSON.stringify(key) + ',"probe@example.com","wrongpass123")');
    console.log(key.padEnd(11), r.ok ? '登录成功(?!' + JSON.stringify(r.data) + ')' : '失败 → ' + String(r.error).slice(0, 110));
    console.log('           isLogged =', vm.runInContext('SourceEngine.isLogged(' + JSON.stringify(key) + ')', ctx));
  }
  process.exit(0);
})();
