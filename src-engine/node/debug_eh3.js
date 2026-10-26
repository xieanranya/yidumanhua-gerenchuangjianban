// ehentai 惰性取图验证：pages 只登记页数，pageImage 逐页解，计每页耗时
const fs = require('fs');
const path = require('path');
const vm = require('vm');
const host = require('./host_node');

const BUNDLE = fs.readFileSync(path.join(__dirname, '..', 'bundle.js'), 'utf8');
const ROUTES = fs.readFileSync(path.join(__dirname, '..', 'routes.json'), 'utf8');

const waiters = {};
function onEvent(o) { if (o.id === 'route_state') return; const w = waiters[o.id]; if (w) { delete waiters[o.id]; w(o); } }
const sandbox = {
  __hostCall: host.__hostCall,
  prompt: (k, p) => {
    if (k === '__event__') { onEvent(JSON.parse(p)); return ''; }
    if (k === '__host__') return JSON.stringify(host.__hostCall(JSON.parse(p)));
    return '';
  },
  console: { log: (...a) => console.error('[js]', a.join(' ')), error: (...a) => console.error('[js!]', a.join(' ')), warn: () => { } },
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

function callRaw(js) {
  const t0 = Date.now();
  const id = vm.runInContext(js, ctx);
  return new Promise(res => { waiters[id] = o => res(Object.assign({ ms: Date.now() - t0 }, o)); setTimeout(() => { if (waiters[id]) { delete waiters[id]; res({ ok: false, error: 'timeout', ms: Date.now() - t0 }); } }, 120000); });
}

(async () => {
  const r = await callRaw('SourceEngine.search("ehentai","love","[]",1)');
  const list = (r.data && r.data.comics) || [];
  console.log('搜索:', r.ok ? list.length + ' 本 / ' + r.ms + 'ms' : r.error);
  if (!list.length) process.exit(1);
  const gid = list[0].id;
  const d = await callRaw('SourceEngine.detail("ehentai",' + JSON.stringify(gid) + ')');
  console.log('详情:', d.ok ? 'ok / ' + d.ms + 'ms' : d.error);
  if (!d.ok) process.exit(1);
  const ep = d.data.groups[0].items[0];
  const p = await callRaw('SourceEngine.pages("ehentai",' + JSON.stringify(gid) + ',' + JSON.stringify(ep.id) + ')');
  console.log('pages:', JSON.stringify(p.data), '/', p.ms + 'ms');
  for (const idx of [0, 1, 2, 5, 40]) {
    const pi = await callRaw('SourceEngine.pageImage("ehentai",' + JSON.stringify(gid) + ',' + JSON.stringify(ep.id) + ',' + idx + ')');
    console.log('  第' + (idx + 1) + '页:', pi.ok ? pi.data.url.slice(0, 80) + ' / ' + pi.ms + 'ms' : pi.error + ' / ' + pi.ms + 'ms');
  }
  process.exit(0);
})();
