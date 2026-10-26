// 域名别名层回归：改写生效 / 失败换域名 / 全坏时保持原样 / CDN 不参与
const fs = require('fs');
const path = require('path');
const vm = require('vm');
const host = require('./host_node');

const BUNDLE = fs.readFileSync(path.join(__dirname, '..', 'bundle.js'), 'utf8');
const ROUTES = fs.readFileSync(path.join(__dirname, '..', 'routes.json'), 'utf8');

const ctx = (() => {
  const sandbox = {
    __hostRaw: host.__hostRaw,
    prompt: () => '',
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

const run = js => vm.runInContext(js, ctx);
let bad = 0;
function ok(cond, msg, extra) {
  if (cond) console.log('  ✓ ' + msg);
  else { bad++; console.log('  ✗ ' + msg + (extra === undefined ? '' : '  → ' + JSON.stringify(extra))); }
}

// 用 bundle 里的引擎初始化（跟真机同一条路径）
run(`SourceEngine.init(${JSON.stringify(ROUTES)})`);
const route = run('ComicRoute');

console.log('— 改写 —');
{
  const r = route.rewrite('https://komiic.com/api/query');
  ok(r === 'https://komiic.cc/api/query', 'Komiic 主域名 → komiic.cc', r);
}
{
  const r = route.rewrite('https://public.komiic.com/x.jpg');
  ok(r === 'https://public.komiic.cc/x.jpg', 'Komiic 封面 CDN → public.komiic.cc', r);
}
{
  const r = route.rewrite('https://www.2026copy.com/api/v3/login');
  ok(r === 'https://www.copy5000.com/api/v3/login', '拷贝登录 → copy5000', r);
}
{
  const r = route.rewrite('https://api.copy-manga.com/api/v3/user/profile');
  ok(r === 'https://api.copy-manga.com/api/v3/user/profile', 'api.copy-manga.com 不改写', r);
}
{
  const r = route.rewrite('https://www.cdngwc.net/');
  ok(r === 'https://www.cdngwc.net/', '禁漫（rewrite:false）不改写', r);
}
{
  const r = route.rewrite('https://noy1.top/api');
  ok(r === 'https://noy1.top/api', '无别名规则的原样返回', r);
}

console.log('— 失败换域名 —');
{
  route._reset();
  const m = route.report('https://www.copy5000.com/api/v3/login', 0);
  ok(m && m.next === 'www.copy2000.site', 'copy5000 挂了 → 换成 copy2000', m);
  const r = route.rewrite('https://www.2026copy.com/api/v3/login');
  ok(r === 'https://www.copy2000.site/api/v3/login', '下次请求直接走备用域名', r);
}
{
  route._reset();
  route.report('https://komiic.cc/api/query', 0);
  const r = route.rewrite('https://komiic.com/api/query');
  ok(r === 'https://komiic.com/api/query', 'Komiic 备选是原域名（开代理时可用）', r);
}
{
  route._reset();
  route.report('https://www.copy5000.com/a', 0);
  route.report('https://www.copy2000.site/a', 0);
  route.report('https://www.copy20.com/a', 0);
  const r = route.rewrite('https://www.2026copy.com/a');
  ok(r === 'https://www.2026copy.com/a', '候选全坏时保持原域名', r);
}
{
  route._reset();
  // 封面 CDN 挂了只影响它自己那条规则，主接口仍走 komiic.cc
  route.report('https://public.komiic.cc/x.jpg', 0);
  const api = route.rewrite('https://komiic.com/api/query');
  ok(api === 'https://komiic.cc/api/query', 'CDN 失败不误伤主接口', api);
  const img = route.rewrite('https://public.komiic.com/x.jpg');
  ok(img === 'https://public.komiic.com/x.jpg', 'CDN 自己的备选是原域名', img);
}
{
  route._reset();
  const m = route.report('https://www.copy5000.com/api', 200);
  ok(m === null, '200 不记坏点', m);
  const m2 = route.report('https://www.copy5000.com/api', 401);
  ok(m2 === null, '401 是站点在说话，也不换域名', m2);
}
{
  route._reset();
  const m = route.report('https://img.noy.asia/x.jpg', 0);
  ok(m === null, '不在别名表里的域名返回 null', m);
}

console.log(bad === 0 ? '\n全部通过' : `\n失败 ${bad} 项`);
process.exit(bad === 0 ? 0 : 1);
