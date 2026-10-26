// 在 Node 里跑真实源脚本：验证引擎 + 摸清每个源的实际可用性
// 用法：node run_node.js <sourceKey> [关键词...]
const fs = require('fs');
const path = require('path');
const vm = require('vm');
const host = require('./host_node');
const { probeHost } = require('./probe');

const ROOT = path.join(__dirname, '..');
const VENDOR = path.join(ROOT, 'vendor');
const PRELUDE = fs.readFileSync(path.join(ROOT, 'prelude.js'), 'utf8');

const FILE_OF = {
  jm: 'jm.js', picacg: 'picacg.js', ehentai: 'ehentai.js', htmanga: 'wnacg.js',
  copy_manga: 'copy_manga.js', Komiic: 'komiic.js', noyacg: 'noyacg.js',
  baozi: 'baozi.js', zaimanhua: 'zaimanhua.js', comick: 'comick.js', mh18: 'mh18.js', mxs: 'mxs.js', goda: 'goda.js',
};

// ---- 域名别名：只读表，不再探测 ----
async function initRoutes() {
  host.initRoute();
  return [];
}

function printRoutes(list) {
  console.log('线路表（direct=国内直连 / proxy=需 VPN）:');
  list.forEach(s => {
    const tag = s.offline ? 'OFFLINE' : s.line;
    console.log(`  ${(s.label + '            ').slice(0, 12)} ${tag.padEnd(8)} ${s.host}`);
  });
}

function makeContext() {
  const sandbox = {
    __hostCall: host.__hostCall,
    console: {
      log: (...a) => console.error('[js]', ...a.map(x => typeof x === 'string' ? x : JSON.stringify(x))),
      error: (...a) => console.error('[js!]', ...a),
      warn: (...a) => console.error('[js?]', ...a),
    },
    TextDecoder, TextEncoder, URL, URLSearchParams, setTimeout, clearTimeout,
    setInterval: () => 0, clearInterval: () => { },
    atob: s => Buffer.from(s, 'base64').toString('binary'),
    btoa: s => Buffer.from(s, 'binary').toString('base64'),
  };
  const ctx = vm.createContext(sandbox);
  vm.runInContext(PRELUDE, ctx, { filename: 'prelude.js' });
  return ctx;
}

function loadSource(key) {
  const file = FILE_OF[key];
  if (!file) throw new Error('未知源: ' + key);
  const code = fs.readFileSync(path.join(VENDOR, file), 'utf8');
  const m = /class\s+(\w+)\s+extends\s+ComicSource/.exec(code);
  if (!m) throw new Error('找不到 ComicSource 子类: ' + file);
  const name = m[1];
  const ctx = makeContext();
  host.setSourceKey(key);
  const cls = vm.runInContext('(function(){\n' + code + '\n;return ' + name + ';})()', ctx, { filename: file });
  return { inst: new cls(), name, ctx };
}

function settingsDefaults(inst) {
  const out = {};
  const s = inst.settings || {};
  for (const k of Object.keys(s)) {
    const d = s[k];
    if (d && Object.prototype.hasOwnProperty.call(d, 'default')) out[k] = d.default;
  }
  return out;
}

function firstOptions(optionList) {
  if (!Array.isArray(optionList)) return [];
  return optionList.map(g => (g && Array.isArray(g.options) && g.options.length ? g.options[0] : ''));
}

function brief(v, n = 60) {
  const s = typeof v === 'string' ? v : JSON.stringify(v);
  return s && s.length > n ? s.slice(0, n) + '…' : s;
}

async function main() {
  const argv = process.argv.slice(2);
  const forceProbe = argv.indexOf('--probe') >= 0;
  const rest = argv.filter(a => a !== '--probe');
  const routes = await initRoutes(forceProbe);
  if (forceProbe && !rest.length) { printRoutes(routes); return; }

  const key = rest[0];
  const kw = rest.slice(1).join(' ') || 'test';
  printRoutes(routes);
  const { inst, name } = loadSource(key);

  const defs = settingsDefaults(inst);
  host.setSourceKey(key);
  global.__defaults = defs;
  // 把默认设置写进 KV（模拟「用户没改过设置」）
  const kvPath = path.join(host.DATA, 'kv_' + key + '.json');
  let kv = {}; try { kv = JSON.parse(fs.readFileSync(kvPath, 'utf8')); } catch (e) { }
  kv.setting = Object.assign({}, defs, kv.setting || {});
  fs.mkdirSync(host.DATA, { recursive: true });
  fs.writeFileSync(kvPath, JSON.stringify(kv, null, 1));

  console.log('源:', key, '| 类:', name, '| version:', inst.version, '| minAppVersion:', inst.minAppVersion);
  console.log('能力: explore=' + (Array.isArray(inst.explore) ? inst.explore.length : !!inst.explore)
    + ' category=' + !!inst.category
    + ' search=' + !!inst.search
    + ' favorites=' + !!inst.favorites
    + ' account=' + !!inst.account);

  try {
    if (typeof inst.init === 'function') {
      const t0 = Date.now();
      await inst.init();
      console.log('init() OK', (Date.now() - t0) + 'ms');
    }
  } catch (e) {
    console.log('init() 失败:', brief(String(e && e.message || e), 120));
  }

  if (typeof inst.loadSetting === 'function') {
    // 让 loadSetting 读到默认值：宿主的 load_setting 已从 KV 里返回
  }

  let first = null;

  // 搜索（两种协议都支持：load(kw,opts,page) / loadNext(kw,opts,next)）
  if (inst.search && (typeof inst.search.load === 'function' || typeof inst.search.loadNext === 'function')) {
    try {
      const opts = firstOptions(inst.search.optionList);
      console.log('搜索:', JSON.stringify(kw), 'options=', JSON.stringify(opts));
      const t0 = Date.now();
      const r = typeof inst.search.load === 'function'
        ? await inst.search.load(kw, opts, 1)
        : await inst.search.loadNext(kw, opts, null);
      const comics = (r && r.comics) || [];
      console.log('  → 命中', comics.length, '本, maxPage=', r && r.maxPage, 'next=', brief(r && r.next, 24), (Date.now() - t0) + 'ms');
      comics.slice(0, 3).forEach((c, i) => console.log(`  [${i}] ${brief(c.title, 40)} | id=${brief(c.id, 40)} | cover=${brief(c.cover, 50)}`));
      if (comics.length) { first = comics[0]; console.log("  完整封面:", comics[0].cover); }
    } catch (e) {
      console.log('  搜索失败:', brief(String(e && e.message || e), 200));
    }
  }

  // 探索页
  if (!first && Array.isArray(inst.explore) && inst.explore.length) {
    try {
      const e0 = inst.explore[0];
      const r = typeof e0.load === 'function' ? await e0.load()
        : (typeof e0.loadNext === 'function' ? await e0.loadNext(null) : null);
      if (r && r.comics) {
        console.log('探索页:', brief(e0.title, 20), '→', r.comics.length, '本');
        if (r.comics.length) { first = r.comics[0]; console.log('  首本:', brief(r.comics[0].title, 40), 'id=', brief(r.comics[0].id, 40)); }
      } else if (r && typeof r === 'object') {
        const keys = Object.keys(r);
        console.log('探索页分区:', keys.join(','));
        const arr = r[keys[0]] || [];
        if (arr.length) { first = arr[0]; console.log('  首本:', brief(arr[0].title, 40), 'id=', brief(arr[0].id, 40)); }
      }
    } catch (e) {
      console.log('  探索页失败:', brief(String(e && e.message || e), 200));
    }
  }

  // 详情
  if (first && inst.comic && inst.comic.loadInfo) {
    try {
      const t0 = Date.now();
      const d = await inst.comic.loadInfo(first.id);
      console.log('详情:', brief(d && d.title, 40), '| 标签组:', d && d.tags ? Object.keys(d.tags).length : 0,
        '| 章节:', d && d.chapters ? Object.keys(d.chapters).length : 0, (Date.now() - t0) + 'ms');
      if (d && d.description) console.log('  简介:', brief(String(d.description).replace(/\s+/g, ' '), 100));
      const ck = d && d.chapters ? Object.keys(d.chapters) : [];
      if (ck.length && inst.comic.loadEp) {
        try {
          const t1 = Date.now();
          const ep = await inst.comic.loadEp(first.id, ck[0]);
          const imgs = (ep && ep.images) || [];
          console.log('  章节图片:', imgs.length, '张', (Date.now() - t1) + 'ms', '| 首张:', brief(imgs[0], 80));
        } catch (e) {
          console.log('  取图失败:', brief(String(e && e.message || e), 200));
        }
      }
    } catch (e) {
      console.log('  详情失败:', brief(String(e && e.message || e), 200));
    }
  }
}

main().catch(e => { console.error('FATAL', e); process.exit(1); });
