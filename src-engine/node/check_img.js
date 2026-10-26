// 取一页真地址并用 curl 下载，确认图片不是白板（hath 网络有时要 referer）
const fs = require('fs');
const path = require('path');
const vm = require('vm');
const { spawnSync } = require('child_process');
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
host.setSourceKey('ehentai');
host.initRoute();
vm.runInContext('SourceEngine.init(' + JSON.stringify(ROUTES) + ', null)', ctx);
vm.runInContext('SourceEngine.boot()', ctx);
const call = js => { const id = vm.runInContext(js, ctx); return new Promise(res => { waiters[id] = res; setTimeout(() => { if (waiters[id]) { delete waiters[id]; res({ ok: false, error: 'timeout' }); } }, 120000); }); };

(async () => {
  const r = await call('SourceEngine.search("ehentai","love","[]",1)');
  const gid = r.data.comics[0].id;
  const d = await call('SourceEngine.detail("ehentai",' + JSON.stringify(gid) + ')');
  const ep = d.data.groups[0].items[0];
  await call('SourceEngine.pages("ehentai",' + JSON.stringify(gid) + ',' + JSON.stringify(ep.id) + ')');
  const pi = await call('SourceEngine.pageImage("ehentai",' + JSON.stringify(gid) + ',' + JSON.stringify(ep.id) + ',0)');
  const url = pi.data.url;
  console.log('URL:', url);
  for (const ref of [null, 'https://e-hentai.org/']) {
    const args = ['-sS', '-L', '--max-time', '25', '-o', '/dev/null', '-w', '%{http_code} %{size_download} %{content_type}'];
    if (process.env.SRC_PROXY) args.push('--proxy', process.env.SRC_PROXY);
    if (ref) args.push('-H', 'Referer: ' + ref);
    args.push(url);
    const o = spawnSync('curl', args, { encoding: 'utf8' });
    console.log((ref ? '带 referer' : '不带 referer') + ' →', (o.stdout || o.stderr || '').trim());
  }
  process.exit(0);
})();
