// 用同一份 bundle.js 验证「原生宿主最小契约」：
// 只需要 __hostCall（同步）+ 事件回调，就能跑搜索/详情/取图。
// Android/iOS 的桥必须照这个做。
const fs = require('fs');
const path = require('path');
const vm = require('vm');
const host = require('./host_node');

const BUNDLE = fs.readFileSync(path.join(__dirname, '..', 'bundle.js'), 'utf8');
const ROUTES = fs.readFileSync(path.join(__dirname, '..', 'routes.json'), 'utf8');

const events = [];
const waiters = {};

function emit(o) {
  events.push(o);
  if (o.id === 'route_state') return;
  const w = waiters[o.id];
  if (w) { delete waiters[o.id]; w(o); }
}

function makeCtx() {
  const sandbox = {
    __hostRaw: host.__hostRaw,
    prompt: (kind, payload) => {
      if (kind === '__event__') { emit(JSON.parse(payload)); return ''; }
      if (kind === '__host__') return host.__hostRaw(payload);
      return '';
    },
    console: {
      log: (...a) => console.error('[js]', ...a.map(x => typeof x === 'string' ? x : JSON.stringify(x))),
      error: (...a) => console.error('[js!]', ...a),
      warn: () => { },
    },
    TextDecoder, TextEncoder, URL, URLSearchParams,
    setTimeout: (fn, t) => setTimeout(fn, Math.min(t || 0, 5)),
    clearTimeout,
    setInterval: () => 0,
    clearInterval: () => { },
    atob: s => Buffer.from(s, 'base64').toString('binary'),
    btoa: s => Buffer.from(s, 'binary').toString('base64'),
  };
  sandbox.globalThis = sandbox;
  const ctx = vm.createContext(sandbox);
  vm.runInContext(BUNDLE, ctx, { filename: 'bundle.js' });
  return ctx;
}

function call(ctx, name, args) {
  const js = `SourceEngine.${name}(${(args || []).map(a => JSON.stringify(a)).join(',')})`;
  const t0 = Date.now();
  const id = vm.runInContext(js, ctx);
  console.error('[call]', name, '同步部分耗时', Date.now() - t0, 'ms, id=', id);
  return new Promise(res => { waiters[id] = res; setTimeout(() => { if (waiters[id]) { delete waiters[id]; res({ ok: false, error: 'timeout' }); } }, 60000); });
}

async function main() {
  const key = process.argv[2] || 'jm';
  const kw = process.argv.slice(3).join(' ') || 'love';
  const ctx = makeCtx();
  host.setSourceKey(key);
  host.initRoute();
  vm.runInContext('SourceEngine.init(' + JSON.stringify(ROUTES) + ', null)', ctx);
  vm.runInContext('SourceEngine.boot()', ctx);

  const srcs = vm.runInContext('SourceEngine.sources()', ctx);
  console.log('已加载源:', srcs.map(s => s.key + '(' + s.name + ')').join(', '));

  const opts = vm.runInContext(`JSON.stringify(SourceEngine.searchOptions(${JSON.stringify(key)}))`, ctx);
  console.log('搜索选项:', opts);

  const r = await call(ctx, 'search', [key, kw, '[]', 1]);
  if (!r.ok) { console.log('搜索失败:', r.error); if (r.stack) console.log(r.stack); return; }
  const list = (r.data && r.data.comics) || [];
  console.log('命中', list.length, '本');
  list.slice(0, 3).forEach((c, i) => console.log(`  [${i}] ${c.title} | ${c.id} | ${(c.cover || '').slice(0, 60)}`));
  if (!list.length) return;

  const d = await call(ctx, 'detail', [key, list[0].id]);
  if (!d.ok && d.stack) console.log('详情堆栈:', d.stack);
  console.log('详情:', JSON.stringify(d.ok ? {t:d.data._chType,k:d.data._chKeys} : null), d.ok ? `${d.data.title} | 标签组 ${Object.keys(d.data.tags || {}).length} | 章节组 ${d.data.groups.length}` : d.error);

  if (d.ok && d.data.groups.length && d.data.groups[0].items.length) {
    const ep = d.data.groups[0].items[0];
    const p = await call(ctx, 'pages', [key, list[0].id, ep.id]);
    console.log('取图:', p.ok ? `${p.data.images.length} 张 | 首张 ${(p.data.images[0] || '').slice(0, 70)}` : p.error);
  }
}

main().catch(e => { console.error('FATAL', e); process.exit(1); });
