// 逐个源看：账号能力 + 探索页是否能出内容（不登录的状态）
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
  const srcs = vm.runInContext('SourceEngine.sources()', ctx);
  const only = process.argv[2];
  for (const s of srcs) {
    if (only && s.key !== only) continue;
    const a = await call(s.key, 'account', [s.key]);
    const info = a.ok ? a.data : null;
    let line = `${s.key.padEnd(12)} 账号:`;
    if (!info) line += ' 读取失败 ' + a.error;
    else line += ` 可密码登录=${info.canLogin} 网页登录=${info.canWebLogin} 已登录=${info.logged}` +
      ` 注册=${info.registerUrl ? '有' : '无'} 信息项=${info.items.map(i => i.title + '=' + i.value).join(',') || '无'}`;
    console.log(line);

    const t0 = Date.now();
    const e = await call(s.key, 'explore', [s.key, 0]);
    const cost = Date.now() - t0;
    if (!e.ok) {
      console.log(`${''.padEnd(12)} 探索: 失败 ${String(e.error).slice(0,60)}  [${cost} ms]`);
    } else {
      const d = e.data || {};
      const parts = Object.keys(d).map(k => `${k}:${Array.isArray(d[k]) ? d[k].length : typeof d[k]}`);
      console.log(`${''.padEnd(12)} 探索: ${parts.join(' ') || '空'}  [${cost} ms]`);
    }
  }
}

main().catch(e => { console.error('FATAL', e); process.exit(1); });
