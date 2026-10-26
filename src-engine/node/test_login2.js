// 假账号走一遍登录：验证错误文案 + 失败时不会把源标成「已登录」
const fs = require('fs');
const path = require('path');
const vm = require('vm');
const host = require('./host_node');

const BUNDLE = fs.readFileSync(path.join(__dirname, '..', 'bundle.js'), 'utf8');
const ROUTES = fs.readFileSync(path.join(__dirname, '..', 'routes.json'), 'utf8');
const waiters = {};
function emit(o) {
  if (o.id === 'route_state') return;
  const w = waiters[o.id];
  if (w) { delete waiters[o.id]; w(o); }
}
const ctx = (() => {
  const sandbox = {
    __hostRaw: host.__hostRaw,
    prompt: (kind, payload) => {
      if (kind === '__event__') { emit(JSON.parse(payload)); return ''; }
      if (kind === '__host__') return host.__hostRaw(payload);
      return '';
    },
    console: { log() { }, error() { }, warn() { } },
    TextDecoder, TextEncoder, URL, URLSearchParams,
    setTimeout: (fn, t) => setTimeout(fn, Math.min(t || 0, 5)),
    clearTimeout, setInterval: () => 0, clearInterval: () => { },
    atob: s => Buffer.from(s, 'base64').toString('binary'),
    btoa: s => Buffer.from(s, 'binary').toString('base64'),
  };
  sandbox.globalThis = sandbox;
  const c = vm.createContext(sandbox);
  vm.runInContext(BUNDLE, c, { filename: 'bundle.js' });
  return c;
})();

function call(key, name, args) {
  host.setSourceKey(key);
  const js = `SourceEngine.${name}(${(args || []).map(a => JSON.stringify(a)).join(',')})`;
  const id = vm.runInContext(js, ctx);
  return new Promise(res => {
    waiters[id] = res;
    setTimeout(() => { if (waiters[id]) { delete waiters[id]; res({ ok: false, error: 'timeout' }); } }, 45000);
  });
}

async function main() {
  host.initRoute();
  vm.runInContext('SourceEngine.init(' + JSON.stringify(ROUTES) + ', null)', ctx);
  vm.runInContext('SourceEngine.boot()', ctx);
  for (const key of ['jm', 'noyacg', 'Komiic', 'copy_manga']) {
    const before = await call(key, 'account', [key]);
    const r = await call(key, 'login', [key, 'probe@example.com', 'wrongpass123']);
    const after = await call(key, 'account', [key]);
    console.log(
      `${key.padEnd(12)} 登录=${r.ok ? '成功(?!' : '失败'} 文案="${r.ok ? '' : r.error}"` +
      ` 登录前=${before.ok ? before.data.logged : '?'} 登录后=${after.ok ? after.data.logged : '?'}`
    );
    if (after.ok && after.data.logged) await call(key, 'logout', [key]);
  }
}
main().catch(e => { console.error('FATAL', e); process.exit(1); });
