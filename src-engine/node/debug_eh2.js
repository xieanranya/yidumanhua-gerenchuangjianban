// ehentai 全链路：搜索 → 详情 → 取图（onImageLoad 链式 token 只能顺序解）
const fs = require('fs');
const path = require('path');
const vm = require('vm');
const host = require('./host_node');

const LOG = path.join(__dirname, '..', '..', '.srcdata', 'eh_trace2.log');
try { fs.unlinkSync(LOG); } catch (e) { }
const trace = (s) => fs.appendFileSync(LOG, new Date().toISOString().slice(11, 23) + ' ' + s + '\n');

const BUNDLE = fs.readFileSync(path.join(__dirname, '..', 'bundle.js'), 'utf8');
const ROUTES = fs.readFileSync(path.join(__dirname, '..', 'routes.json'), 'utf8');

const waiters = {};
function onEvent(o) {
  if (o.id === 'route_state') return;
  trace('event ' + String(o.id) + ' ok=' + o.ok);
  const w = waiters[o.id];
  if (w) { delete waiters[o.id]; w(o); }
}
function wrapHost(msg) {
  trace('  host ' + msg.method + ' ' + String(msg.url || msg.type || msg.data_key || '').slice(0, 110));
  const r = host.__hostCall(msg);
  return r;
}
const sandbox = {
  __hostCall: wrapHost,
  prompt: (k, p) => {
    if (k === '__event__') { onEvent(JSON.parse(p)); return ''; }
    if (k === '__host__') return JSON.stringify(wrapHost(JSON.parse(p)));
    return '';
  },
  console: { log: (...a) => trace('jslog ' + a.join(' ')), error: (...a) => trace('jserr ' + a.join(' ')), warn: () => { } },
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
  const id = vm.runInContext(js, ctx);
  return new Promise(res => { waiters[id] = res; setTimeout(() => { if (waiters[id]) { delete waiters[id]; res({ ok: false, error: 'timeout' }); } }, 120000); });
}

(async () => {
  const r = await callRaw('SourceEngine.search("ehentai","love","[]",1)');
  const list = (r.data && r.data.comics) || [];
  trace('搜索 ' + list.length + ' 本');
  if (!list.length) return process.exit(1);
  const gid = list[0].id;
  trace('详情 ' + gid);
  const d = await callRaw('SourceEngine.detail("ehentai",' + JSON.stringify(gid) + ')');
  if (!d.ok) { trace('详情失败 ' + d.error + ' ' + (d.stack || '')); return process.exit(1); }
  trace('详情 ok title=' + d.data.title + ' groups=' + d.data.groups.length);
  const items = d.data.groups[0] ? d.data.groups[0].items : [];
  trace('章节 ' + items.length + ' ' + JSON.stringify(items.slice(0, 2)));
  if (!items.length) return process.exit(1);
  const p = await callRaw('SourceEngine.pages("ehentai",' + JSON.stringify(gid) + ',' + JSON.stringify(items[0].id) + ')');
  if (!p.ok) { trace('取图失败 ' + p.error + ' ' + (p.stack || '')); return process.exit(1); }
  const imgs = p.data.images || [];
  trace('取图 ok ' + imgs.length + ' 张 首张=' + String(imgs[0]).slice(0, 90));
  imgs.slice(0, 3).forEach((u, i) => trace('  [' + i + '] ' + String(u).slice(0, 100)));
  process.exit(0);
})();
