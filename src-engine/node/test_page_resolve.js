// 取图链路回归：pages() 是否走惰性、pageImage() 有没有把「地址 + 请求头 + 打乱参数」带全。
// 1.9.18 之前这里有两个 bug：
//   ① 只有「images 不像地址」才走惰性 → Komiic/NoyACG/禁漫 的 onImageLoad 从不执行，
//      Referer 丢掉（403）、禁漫的乱序参数丢掉（页面是错位横条）；
//   ② onImageLoad 只回 {headers} 不改地址时，url 被写成空串 → 界面「服务端没给地址」。
// 用法：node test_page_resolve.js
const fs = require('fs');
const path = require('path');
const vm = require('vm');
const crypto = require('crypto');
const { makeHost, md5 } = require('./host_android_sim');

const BUNDLE = fs.readFileSync(path.join(__dirname, '..', 'bundle.js'), 'utf8');
const ROUTES = fs.readFileSync(path.join(__dirname, '..', 'routes.json'), 'utf8');

const b64 = b => Buffer.from(b).toString('base64');

// 禁漫：/chapter 的返回也是 AES-256-ECB(JSON) + base64，包在 {data} 里
function jmCipher(time, obj) {
  const key = Buffer.from(md5(Buffer.from(`${time}185Hcomic3PAPP7R`, 'utf8')).toString('hex'), 'utf8');
  const c = crypto.createCipheriv('aes-' + key.length * 8 + '-ecb', key, null);
  return b64(Buffer.concat([c.update(Buffer.from(JSON.stringify(obj), 'utf8')), c.final()]));
}

function api(msg) {
  const url = String(msg.url || '');
  const headers = msg.headers || {};
  const time = parseInt(String(headers.tokenparam || '').split(',')[0], 10);

  // 禁漫：给 3 张图的名字（真地址由源拼 imageUrl）
  if (/\/chapter\?id=/.test(url)) {
    return { status: 200, headers: {}, body: JSON.stringify({ data: jmCipher(time, { images: ['00001.jpg', '00002.jpg', '00003.jpg'] }) }) };
  }
  if (/\/setting\?/.test(url)) {
    return { status: 200, headers: {}, body: JSON.stringify({ data: jmCipher(time, { img_host: 'https://cdn.example.test' }) }) };
  }
  // Komiic：GraphQL，取图返回 kid
  if (/komiic\.(com|cc)\/api\/query/.test(url)) {
    return { status: 200, headers: {}, body: JSON.stringify({ data: { imagesByChapterId: [{ id: 1, kid: 'kid-aaa' }, { id: 2, kid: 'kid-bbb' }] } }) };
  }
  // 拷贝：登录页/接口一律 200 空壳，避免 ensureInit 抛错
  return { status: 200, headers: {}, body: '{}' };
}

function boot() {
  const host = makeHost({ convertMode: 'new', cookieSet: true, http: api });
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
  return {
    call(name, args) {
      const id = vm.runInContext(`SourceEngine.${name}(${args.map(a => JSON.stringify(a)).join(',')})`, ctx);
      return new Promise(res => {
        waiters[id] = res;
        setTimeout(() => res({ ok: false, error: '超时' }), 8000);
      });
    },
  };
}

let pass = 0, fail = 0;
function check(name, cond, detail) {
  if (cond) { console.log('  ✓ ' + name); pass++; }
  else { console.log('  ✗ ' + name + (detail ? '\n      ' + detail : '')); fail++; }
}

(async () => {
  const e = boot();

  console.log('[1] 禁漫：pages() 要求走惰性（否则拿不到请求头/乱序参数）');
  const p = await e.call('pages', ['jm', '123', '421926']);
  const pd = p.data || {};
  check('lazy = true', pd.lazy === true, JSON.stringify(p).slice(0, 200));
  check('count = 3', pd.count === 3, String(pd.count));

  console.log('[2] 禁漫：pageImage() 要给真地址 + Referer + descramble.num');
  const i0 = await e.call('pageImage', ['jm', '123', '421926', 0]);
  const d0 = i0.data || {};
  check('地址非空', typeof d0.url === 'string' && d0.url.startsWith('http'), JSON.stringify(d0).slice(0, 200));
  check('Referer 在', !!(d0.headers && (d0.headers['Referer'] || d0.headers['referer'])), JSON.stringify(d0.headers));
  check('descramble.num 是 2..20 的偶数', !!(d0.descramble && d0.descramble.num >= 2 && d0.descramble.num <= 20 && d0.descramble.num % 2 === 0), JSON.stringify(d0.descramble));

  console.log('[3] 禁漫：老章节（epId < 220980）不打乱，但请求头仍要给');
  const i1 = await e.call('pageImage', ['jm', '123', '100001', 1]);
  const d1 = i1.data || {};
  check('地址非空', typeof d1.url === 'string' && d1.url.length > 0);
  check('descramble 为空', d1.descramble == null, JSON.stringify(d1.descramble));
  check('Referer 在', !!(d1.headers && (d1.headers['Referer'] || d1.headers['referer'])));

  console.log('[4] Komiic：onImageLoad 只给 Referer 不改地址，地址不能被清空');
  const i2 = await e.call('pageImage', ['Komiic', 'c1', 'ep1', 0]);
  const d2 = i2.data || {};
  if (!i2.ok) console.log('      komiic 报错:', i2.error, String(i2.stack || '').split('\n').slice(0, 4).join(' | '));
  check('地址仍是 komiic 的图片接口', /komiic\.(com|cc)\/api\/image\/kid-aaa/.test(String(d2.url)), String(d2.url));
  check('referer 带上了章节页', /komiic\.(com|cc)\/comic\/c1\/chapter\/ep1/.test(String((d2.headers || {}).referer)), JSON.stringify(d2.headers));

  console.log(`\n结果：通过 ${pass}，失败 ${fail}\n`);
  process.exit(fail ? 1 : 0);
})();
