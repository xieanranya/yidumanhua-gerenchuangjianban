/*
 * SourceEngine 门面：给原生侧调的稳定 API。
 * 所有方法同步返回 taskId，结果通过 __emit（原生事件队列）异步回传，
 * 避免 Android 的 evaluateJavascript 拿不到 Promise 返回值。
 *
 * 原生侧只需：
 *   1. 提供 __hostCall(msg) -> any（同步）
 *   2. 收到 event { id, ok, data | error } 后按 id 派发
 */
(function () {
  var S = this || globalThis;
  var loaded = {};
  var taskSeq = 0;
  var evtSink = null;

  function setEventSink(fn) { evtSink = fn; }

  function emit(o) {
    try {
      if (evtSink) evtSink(o);
      else if (typeof prompt === 'function') prompt('__event__', JSON.stringify(o));
    } catch (e) { }
  }

  function nextId() { return 't' + (++taskSeq) + '_' + Date.now(); }

  /** 深遍历把字符串 URL 过一遍线路改写（封面/正文图片也要跟着线路走） */
  function mapUrls(v, depth) {
    depth = depth || 0;
    if (depth > 6 || v == null) return v;
    if (typeof v === 'string') {
      if (v.indexOf('://') > 0) return R ? R.rewrite(v) : v;
      return v;
    }
    if (Array.isArray(v)) return v.map(function (x) { return mapUrls(x, depth + 1); });
    if (typeof v === 'object') {
      var o = {};
      for (var k in v) if (Object.prototype.hasOwnProperty.call(v, k)) o[k] = mapUrls(v[k], depth + 1);
      return o;
    }
    return v;
  }

  var R = null;

  /**
   * 源的登录方式。
   * - 'account'：account.login(username, password)，绝大多数源
   * - 'cookie' ：account.loginWithCookies.fields + validate(values)，E-Hentai 这种
   * - ''       ：不支持登录
   */
  function loginModeOf(acc) {
    if (!acc) return '';
    if (acc.login) return 'account';
    if (acc.loginWithCookies && acc.loginWithCookies.fields) return 'cookie';
    return '';
  }

  function loginFieldsOf(acc) {
    if (loginModeOf(acc) !== 'cookie') return [];
    return (acc.loginWithCookies.fields || []).map(function (f) { return String(f); });
  }

  function inst(key) {
    if (!loaded[key]) throw new Error('源未加载: ' + key);
    return loaded[key];
  }

  // 源脚本的 init() 必须跑一次（禁漫要在里面拉官方线路表，否则 baseUrl 是 undefined）
  var inited = {};
  // 惰性取图登记表：pages() 登记页数，pageImage() 逐页解真地址
  var LAZY = {};
  async function ensureInit(s, key) {
    if (inited[key]) return;
    inited[key] = true;
    if (typeof s.init === 'function') {
      try { await s.init(); } catch (e) { }
    }
  }

  /** 把 vendor 里的源码注册成实例 */
  function register(key, code) {
    try {
      var m = /class\s+(\w+)\s+extends\s+ComicSource/.exec(code);
      if (!m) throw new Error('找不到 ComicSource 子类');
      var factory = new Function(code + '\n;return ' + m[1] + ';');
      var Cls = factory();
      loaded[key] = new Cls();
      return true;
    } catch (e) {
      emit({ id: 'load', ok: false, error: String(e && e.message || e), key: key });
      return false;
    }
  }

  function run(fn) {
    var id = nextId();
    Promise.resolve().then(fn).then(function (data) {
      emit({ id: id, ok: true, data: mapUrls(data) });
    }, function (e) {
      var msg = String(e && e.message ? e.message : e);
      var code = errCode(msg);
      // 源脚本里「要登录」的原文五花八门（noyacg 直接 throw 'login'），
      // 界面不该去猜英文，这里统一成一句能直接显示的中文。
      if (code === 'login' && /^\s*(login|need ?login|not ?logged( ?in)?|login ?required)\s*$/i.test(msg)) {
        msg = '该源需要登录才能看内容';
      }
      emit({
        id: id, ok: false, error: msg, code: code,
        stack: e && e.stack ? String(e.stack).slice(0, 800) : ''
      });
    });
    return id;
  }

  /**
   * 失败原因归类。界面不该再去字符串里找「登录」，源脚本报的原文五花八门：
   *   net        连不上（DNS / TLS / 超时 / status 0）——多半是线路问题，提示开关代理
   *   login      要登录 / 登录态过期
   *   forbidden  站点拒绝（403，多为 Cloudflare 风控或防盗链）
   *   badjson    返回空串/HTML，通常是接口换了或者被拦
   */
  function errCode(msg) {
    var s = String(msg || '');
    var lo = s.toLowerCase();
    if (/invalid status code: *0\b|^http 0$|status code: *0\b|请求失败: *0|etimedout|econnrefused|enotfound|unable to resolve|could not resolve|socket|ssl|timed out|网络|超时/.test(lo)) return 'net';
    if (lo === 'login' || /未登录|需要登录|请先登录|not logged|login required|先登入/.test(lo)) return 'login';
    if (/status code: *40[13]|http 40[13]|forbidden/.test(lo)) return 'forbidden';
    if (/unexpected end of json|empty body|not valid json|读取为空/.test(lo)) return 'badjson';
    return 'other';
  }

  var API = {
    setEventSink: setEventSink,

    /**
     * routesJson: routes.json 文本（域名别名表）。
     * 只有「源码写死的域名换成可用的别名」这一件事，没有通道、没有探测、没有离线态。
     */
    init: function (routesJson) {
      try {
        R = ComicRoute;
        R.init(routesJson);
      } catch (e) { R = null; }
      return true;
    },

    register: register,

    /** 源列表 + 每个源支持的能力 */
    sources: function () {
      return Object.keys(loaded).map(function (k) {
        var s = loaded[k];
        var logged = false;
        try { logged = !!sendMessage({ method: 'load_data', key: k, data_key: '__logged' }); } catch (e) { }
        var acc = s.account || null;
        return {
          key: k,
          name: s.name || k,
          version: s.version || '',
          canLogin: !!(acc && (acc.login || acc.loginWithCookies)),
          logged: logged,
          loginMode: loginModeOf(acc),
          loginFields: loginFieldsOf(acc),
          registerUrl: (acc && acc.registerWebsite) || '',
          canSearch: !!(s.search && (s.search.load || s.search.loadNext)),
          canExplore: !!s.explore,
          canFavorites: !!s.favorites,
          needLogin: !!(acc && (acc.login || acc.loginWithCookies) && s.settings && s.settings.loginRequired)
        };
      });
    },

    isLogged: function (key) {
      // 登录成功时 login() 会 save_data('__logged')，登出时清掉。
      // 不能问宿主的 isLogged——宿主没有写入这条状态的入口。
      try { return !!sendMessage({ method: 'load_data', key: key, data_key: '__logged' }); } catch (e) { return false; }
    },

    /**
     * 账号页要的全部信息：登录态、能否账号登录、注册地址、以及源自己声明的信息项。
     * infoItems 里的 data 可能是函数（有的源还返回 Promise），一律求值成字符串；
     * action 型（例如 E-Hentai 的 cookies 管理）只回标题，界面按「不可点」显示。
     */
    account: function (key) {
      return run(async function () {
        var s = inst(key);
        await ensureInit(s, key);
        var acc = s.account || null;
        var saved = null;
        try { saved = sendMessage({ method: 'load_data', key: key, data_key: '__logged' }); } catch (e) { }
        var items = [];
        var list = (acc && acc.infoItems) || [];
        for (var i = 0; i < list.length; i++) {
          var it = list[i] || {};
          var val = '';
          try {
            if (typeof it.data === 'function') val = it.data();
            if (val && typeof val.then === 'function') val = await val;
          } catch (e) { val = ''; }
          if (val === null || val === undefined) val = '';
          if (typeof val === 'object') { try { val = JSON.stringify(val); } catch (e) { val = ''; } }
          items.push({ title: it.title || '', value: String(val), action: it.action || '' });
        }
        return {
          key: key,
          name: s.name || key,
          version: s.version || '',
          logged: !!saved,
          savedAccount: (saved && saved.account) || '',
          canLogin: !!(acc && (acc.login || acc.loginWithCookies)),
          loginMode: loginModeOf(acc),
          loginFields: loginFieldsOf(acc),
          canWebLogin: !!(acc && acc.loginWithWebview),
          loginWebUrl: (acc && acc.loginWithWebview && acc.loginWithWebview.url) || '',
          registerUrl: (acc && acc.registerWebsite) || '',
          needLogin: !!(acc && (acc.login || acc.loginWithCookies) && s.settings && s.settings.loginRequired),
          items: items
        };
      });
    },

    search: function (key, kw, optionsJson, page) {
      return run(async function () {
        var s = inst(key);
        if (!s.search) throw new Error('该源不支持搜索');
        await ensureInit(s, key);
        var given = optionsJson ? JSON.parse(optionsJson) : [];
        // 原生没传的组用默认值补满——源脚本普遍直接读 options[0]
        var groups = s.search.optionList || [];
        var opts = groups.map(function (g, i) {
          if (given[i] !== undefined && given[i] !== null) return given[i];
          return defaultOptionValue(g);
        });
        var r = (typeof s.search.load === 'function')
          ? await s.search.load(kw, opts, page || 1)
          : await s.search.loadNext(kw, opts, page > 1 ? String(page) : null);
        return { comics: (r && r.comics) || [], maxPage: r && r.maxPage, next: r && r.next };
      });
    },

    searchOptions: function (key) {
      try {
        var s = inst(key);
        var list = s.search && s.search.optionList;
        if (!Array.isArray(list)) return [];
        return list.map(function (g) {
          if (!g) return null;
          return {
            title: g.title || '',
            options: (g.options || []).map(function (o) {
              return typeof o === 'string' ? { value: o, text: o } : { value: o.value, text: o.text || o.value };
            })
          };
        }).filter(Boolean);
      } catch (e) { return []; }
    },

    explore: function (key, index) {
      return run(async function () {
        var s = inst(key);
        if (!Array.isArray(s.explore) || !s.explore.length) throw new Error('该源没有探索页');
        await ensureInit(s, key);
        var e0 = s.explore[index || 0];
        // multiPageComicList 的 load(page) 是 1-based，原来不传参 → (undefined-1)*20 = NaN
        // → Komiic 的 GraphQL 收到 offset: null 直接报错，界面就是一片空。
        var arg = (e0.type === 'mixed') ? 0 : 1;
        var r = (typeof e0.load === 'function') ? await e0.load(arg) : await e0.loadNext(null);
        return normalizeExplore(r, e0.title || '');
      });
    },

    exploreTitles: function (key) {
      try {
        var s = inst(key);
        if (!Array.isArray(s.explore)) return [];
        return s.explore.map(function (e) { return e.title || ''; });
      } catch (e) { return []; }
    },

    detail: function (key, id) {
      return run(async function () {
        var s = inst(key);
        if (!s.comic || !s.comic.loadInfo) throw new Error('该源不支持详情');
        await ensureInit(s, key);
        var d = await s.comic.loadInfo(id);
        var groups = normalizeChapters(d.chapters);
        // 图库类源（E-Hentai）没有章节，正文就是 maxPage 张图。
        // 不合成一个章节，界面会永远停在「0 章」进不去。
        if (!groups.length || !groups.some(function (g) { return g.items && g.items.length; })) {
          groups = [{ title: '', items: [{ id: '', title: '全部页面' }] }];
        }
        return {
          id: id,
          title: d.title || '',
          cover: d.cover || '',
          description: d.description || '',
          tags: d.tags || {},
          groups: groups,
          subId: d.subId || ''
        };
      });
    },

    pages: function (key, comicId, epId) {
      return run(async function () {
        var s = inst(key);
        if (!s.comic || !s.comic.loadEp) throw new Error('该源不支持取图');
        await ensureInit(s, key);
        var ep = await s.comic.loadEp(comicId, epId);
        var images = (ep && ep.images) || [];
        // 只要源声明了 onImageLoad，就一律逐页解——哪怕 images 已经是真地址。
        // 因为「每张图要带什么请求头」「这张图是不是被打乱了」只有 onImageLoad 知道：
        //   Komiic / NoyACG 靠它给 Referer（不给就是 403，图根本出不来）
        //   禁漫靠它给分块乱序的参数（不给就是错位的横条，看着像文件坏了）
        // 这些源的 onImageLoad 是纯计算，不发请求，逐页解的开销可以忽略。
        var onLoad = s.comic && s.comic.onImageLoad;
        if (typeof onLoad === 'function' && images.length) {
          LAZY[String(key) + '|' + String(comicId) + '|' + String(epId)] = {
            key: key, s: s, comicId: comicId, epId: epId, images: images, nl: null
          };
          return { title: (ep && ep.title) || '', lazy: true, count: images.length, images: [] };
        }
        return { title: (ep && ep.title) || '', images: images };
      });
    },

    /**
     * 惰性取图：解第 index 页的真地址 + 请求头 + 是否需要还原打乱。
     * 原生可以乱序/并发调，nl 只做锦上添花。
     */
    pageImage: function (key, comicId, epId, index) {
      return run(async function () {
        var s = inst(key);
        if (!s.comic || !s.comic.onImageLoad) throw new Error('该源不支持逐页取图');
        var lk = String(key) + '|' + String(comicId) + '|' + String(epId);
        var st = LAZY[lk];
        if (!st) {
          await ensureInit(s, key);
          var ep = await s.comic.loadEp(comicId, epId);
          st = LAZY[lk] = { key: key, s: s, comicId: comicId, epId: epId, images: (ep && ep.images) || [], nl: null };
        }
        var i = Number(index) || 0;
        if (i < 0 || i >= st.images.length) throw new Error('页码越界: ' + i);
        var raw = st.images[i];
        var r = await st.s.comic.onImageLoad(raw, comicId, epId, st.nl);
        // onImageLoad 常常只回一个 {headers}（不改地址），这时必须沿用原地址。
        // 以前只认 r.url，没 url 就是空串，界面显示「服务端没给地址」——图全白。
        var url = typeof raw === 'string' ? String(raw) : '';
        var headers = null, descramble = null;
        if (typeof r === 'string') {
          url = r;
        } else if (r && typeof r === 'object') {
          if (r.url) url = String(r.url);
          headers = r.headers || null;
          // 打乱参数是纯数据（{num:n}），不执行源里的 JS：两端原生直接按参数还原。
          if (r.descramble && typeof r.descramble === 'object') descramble = r.descramble;
          if (r.nl) st.nl = r.nl;
        }
        return { index: i, url: url, headers: headers, descramble: descramble };
      });
    },

    /**
     * 登录。
     * - 账号密码源：login(key, 账号, 密码)
     * - Cookie 源（E-Hentai）：login(key, '["值1","值2",...]', '')，值的顺序同 account.loginFields
     */
    login: function (key, account, password) {
      return run(async function () {
        var s = inst(key);
        var acc = s.account || null;
        var mode = loginModeOf(acc);
        if (!mode) throw new Error('该源不支持登录');
        await ensureInit(s, key);

        if (mode === 'cookie') {
          var fields = loginFieldsOf(acc);
          var vals = [];
          try {
            var parsed = JSON.parse(String(account == null ? '' : account));
            if (Array.isArray(parsed)) vals = parsed.map(function (x) { return String(x == null ? '' : x).trim(); });
          } catch (e) { vals = []; }
          if (vals.length !== fields.length) {
            throw new Error('需要填 ' + fields.length + ' 项（' + fields.join(' / ') + '）');
          }
          var empty = fields.filter(function (f, i) { return !vals[i] && f !== 'igneous' && f !== 'star'; });
          if (empty.length) throw new Error('这几项不能空: ' + empty.join(' / '));
          var okc = false;
          try {
            okc = await acc.loginWithCookies.validate(vals);
          } catch (e) {
            throw new Error('Cookie 校验请求失败：' + (e && e.message ? e.message : String(e)));
          }
          if (!okc) throw new Error('Cookie 无效或已过期，重新从浏览器复制一次');
          try {
            sendMessage({
              method: 'save_data', key: key, data_key: '__logged',
              data: { at: Date.now(), account: 'cookies' }
            });
          } catch (e) { }
          return { ok: true, account: 'cookies' };
        }

        if (!acc.login) throw new Error('该源不支持登录');
        var r = await acc.login(account, password);
        if (!r) throw new Error('账号或密码不对');
        try {
          sendMessage({
            method: 'save_data', key: key, data_key: '__logged',
            data: { at: Date.now(), account: String(account || '') }
          });
        } catch (e) { }
        return { ok: true, account: String(account || '') };
      });
    },

    /**
     * 网页登录：把可见 WebView 里登录成功后拿到的 Cookie 交给引擎。
     * 有些站（绅士漫画）的账号密码接口必须过滑块，只能让用户在网页里登一次，
     * 我们把 Cookie 收下来存进本地的 Cookie 表，之后所有请求自动带上。
     *
     * @param url       登录页地址（cookie 会按这个 host 存）
     * @param cookieHeader  形如 "a=1; b=2" 的请求头
     */
    loginWithCookieString: function (key, url, cookieHeader) {
      return run(async function () {
        var s = inst(key);
        var acc = s.account || null;
        if (!url) throw new Error('缺少登录地址');
        var list = [];
        String(cookieHeader || '').split(';').forEach(function (p) {
          var i = p.indexOf('=');
          if (i <= 0) return;
          var n = p.slice(0, i).trim();
          var v = p.slice(i + 1).trim();
          if (n) list.push({ name: n, value: v, domain: '' });
        });
        if (!list.length) throw new Error('没有拿到 Cookie：请确认网页里已经登录成功');
        // Cookie 要写到「所有可能被请求的 host」下：带 www 的、不带 www 的、
        // 以及源自己声明的域名。只写登录页那一个 host 时，源用 baseUrl（可能是
        // 不带 www 的域名或另一个别名）去请求就还是游客 —— 滑块登录「成功但没登上」多半是这个。
        var hosts = [];
        try {
          var u = new URL(url);
          hosts.push(u.host);
          if (u.host.indexOf('www.') === 0) hosts.push(u.host.slice(4));
        } catch (e) { }
        try {
          var doms = (s.link && s.link.domains) || [];
          for (var di = 0; di < doms.length; di++) {
            var d = String(doms[di] || '');
            if (d && hosts.indexOf(d) < 0) hosts.push(d);
          }
        } catch (e) { }
        for (var hi = 0; hi < hosts.length; hi++) {
          try {
            sendMessage({ method: 'cookie', function: 'set', url: 'https://' + hosts[hi] + '/', cookies: list });
          } catch (e) { }
        }
        if (acc && acc.loginWithWebview && typeof acc.loginWithWebview.onLoginSuccess === 'function') {
          await acc.loginWithWebview.onLoginSuccess();
        }
        try {
          sendMessage({
            method: 'save_data', key: key, data_key: '__logged',
            data: { at: Date.now(), account: 'webview' }
          });
        } catch (e) { }
        return { ok: true, count: list.length };
      });
    },

    logout: function (key) {
      return run(async function () {
        var s = inst(key);
        if (s.account && s.account.logout) await s.account.logout();
        try { sendMessage({ method: 'delete_data', key: key, data_key: '__logged' }); } catch (e) { }
        return { ok: true };
      });
    },

    /** 设置项（给原生渲染表单） */
    settings: function (key) {
      try {
        var s = inst(key);
        var out = [];
        var st = s.settings || {};
        Object.keys(st).forEach(function (k) {
          var d = st[k];
          if (!d || typeof d !== 'object') return;
          out.push({
            key: k,
            title: d.title || k,
            type: d.type || 'input',
            default: d.default === undefined ? null : d.default,
            options: (d.options || []).map(function (o) {
              return typeof o === 'string' ? { value: o, text: o } : { value: o.value, text: o.text || o.value };
            })
          });
        });
        return out;
      } catch (e) { return []; }
    },

    getSetting: function (key, settingKey) {
      try { return sendMessage({ method: 'load_setting', setting_key: key + '.' + settingKey }); } catch (e) { return null; }
    },

    setSetting: function (key, settingKey, value) {
      try { return sendMessage({ method: 'save_setting', setting_key: key + '.' + settingKey, value: value }); } catch (e) { return null; }
    }
  };


  /**
   * 章节结构兼容层。见到的形态：
   *   Map<id,title>（禁漫）、Map<分组,Map<id,title>>、{分组:[{id,title}]}（拷贝）、
   *   {id:title}、纯数组、以及 value 为 undefined 的残废对象。
   * 一律摊成 [{ title: 分组名, items: [{ id, title }] }]。
   */
  function normalizeChapters(ch) {
    var groups = [];
    if (!ch) return groups;
    function isMap(x) { return x && typeof x.forEach === 'function' && typeof x.get === 'function'; }
    function titleOf(v, fallback) {
      if (typeof v === 'string') return v;
      if (v && typeof v === 'object' && typeof v.title === 'string') return v.title;
      return fallback || '';
    }
    function mapToItems(m) {
      var items = [];
      m.forEach(function (v, k) {
        items.push({ id: String(k), title: titleOf(v, String(k)) });
      });
      return items;
    }
    function isPlainObj(x) {
      return !!x && typeof x === 'object' && !Array.isArray(x) && !isMap(x);
    }
    /** 普通对象当 Map 用：{ id: '标题' } → items */
    function objToItems(m) {
      var items = [];
      Object.keys(m).forEach(function (k) {
        items.push({ id: String(k), title: titleOf(m[k], String(k)) });
      });
      return items;
    }
    /**
     * 这个对象是不是「id → 标题/章节对象」的 Map。
     *
     * 关键：源脚本用的是**普通对象**（不是 Map），以前只认 Map，
     * 于是 { 分组名: { 章节id: 标题 } } 被当成「一个分组、一个章节」，
     * 章节 id 变成了分组名（拷贝漫画的「默認」就是这么来的，点进去 404）。
     */
    function looksLikeMap(v) {
      if (!isPlainObj(v)) return false;
      var ks = Object.keys(v);
      if (!ks.length) return false;
      return ks.every(function (k) {
        var x = v[k];
        return typeof x === 'string'
          || (!!x && typeof x === 'object' && (typeof x.title === 'string' || x.id !== undefined));
      });
    }
    if (isMap(ch)) {
      var flat = [], hasNested = false;
      ch.forEach(function (v, k) {
        if (isMap(v)) { hasNested = true; groups.push({ title: String(k), items: mapToItems(v) }); }
        else flat.push({ id: String(k), title: titleOf(v, String(k)) });
      });
      if (flat.length) groups.unshift({ title: '', items: flat });
      return groups;
    }
    if (Array.isArray(ch)) {
      groups.push({ title: '', items: ch.filter(Boolean).map(function (c) { return { id: String(c.id), title: titleOf(c, '') }; }) });
      return groups;
    }
    if (typeof ch === 'object') {
      var keys = Object.keys(ch);
      // 全是最内层的 { 章节id: 标题 } → 一个不分组的平铺列表
      var flatStrings = keys.length > 0 && keys.every(function (k) {
        return typeof ch[k] === 'string';
      });
      if (flatStrings) {
        return [{ title: '', items: objToItems(ch) }];
      }
      keys.forEach(function (g) {
        var v = ch[g];
        if (v === undefined || v === null) return;
        if (Array.isArray(v)) {
          groups.push({ title: g, items: v.filter(Boolean).map(function (c) { return { id: String(c.id), title: titleOf(c, '') }; }) });
        } else if (isMap(v)) {
          groups.push({ title: g, items: mapToItems(v) });
        } else if (isPlainObj(v)) {
          // 先看它是不是「单个章节」({id,title})，再看是不是「id → 标题」的表。
          // 顺序反了会把 {id:'a',title:'A'} 当成 {id:…, title:…} 两个章节。
          if (v.id !== undefined || typeof v.title === 'string') {
            groups.push({ title: g, items: [{ id: String(v.id === undefined ? g : v.id), title: titleOf(v, g) }] });
          } else {
            // { 分组名: { 章节id: 标题 } } —— 普通对象当 Map
            groups.push({ title: g, items: objToItems(v) });
          }
        } else if (typeof v === 'string') {
          groups.push({ title: g, items: [{ id: g, title: v }] });
        }
      });
    }
    return groups;
  }


  /**
   * 探索页结构兼容层。Venera 的 explore 各类型返回值不一样：
   *   multiPartPage        -> [{ title, comics, viewMore }]
   *   multiPageComicList   -> { comics, maxPage }
   *   singlePageWithMultiPart -> { 分区名: Comic[] }
   *   mixed                -> { data: [Comic[] | {title, comics}] }
   * 原生侧不该为这四种各写一遍解析，统一摊成
   *   { sections: [{ title, comics: [{id,title,cover,subTitle}] }], maxPage }
   */
  function normalizeExplore(r, fallbackTitle) {
    var sections = [];
    var maxPage = 0;
    function toList(arr) {
      if (!Array.isArray(arr)) return [];
      var out = [];
      for (var i = 0; i < arr.length; i++) {
        var c = arr[i];
        if (!c || typeof c !== 'object') continue;
        var id = (c.id === undefined || c.id === null) ? '' : String(c.id);
        if (!id) continue;
        out.push({
          id: id,
          title: c.title === undefined || c.title === null ? '' : String(c.title),
          cover: c.cover === undefined || c.cover === null ? '' : String(c.cover),
          subTitle: c.subTitle === undefined || c.subTitle === null ? '' : String(c.subTitle)
        });
      }
      return out;
    }
    function push(title, comics, viewMore) {
      var list = toList(comics);
      if (list.length) sections.push({ title: title || '', comics: list, viewMore: viewMore || '' });
    }
    function walk(node, title) {
      if (!node) return;
      if (Array.isArray(node)) {
        // 数组有两种意思：Comic[]，或者 [{title, comics}]（multiPartPage）
        var asSections = false;
        for (var k = 0; k < node.length; k++) {
          var el = node[k];
          if (el && typeof el === 'object' && (Array.isArray(el.comics) || Array.isArray(el.data))) {
            asSections = true;
            break;
          }
        }
        if (asSections) { for (var m = 0; m < node.length; m++) walk(node[m], title); }
        else push(title, node);
        return;
      }
      if (typeof node !== 'object') return;
      if (node.maxPage) maxPage = Math.max(maxPage, Number(node.maxPage) || 0);
      if (Array.isArray(node.comics)) { push(node.title || title, node.comics, node.viewMore); return; }
      if (Array.isArray(node.data)) {
        for (var i = 0; i < node.data.length; i++) walk(node.data[i], title);
        return;
      }
      var keys = Object.keys(node);
      for (var j = 0; j < keys.length; j++) {
        var v = node[keys[j]];
        if (Array.isArray(v)) push(keys[j], v);
        else if (v && typeof v === 'object') walk(v, v.title || keys[j]);
      }
    }
    walk(r, fallbackTitle);
    return { sections: sections, maxPage: maxPage };
  }

  /** "0-Misc" -> "0"；{value,text} -> value 的字符串 */
  function optionValue(o) {
    if (o === undefined || o === null) return '';
    if (typeof o === 'object') return (o.value === undefined || o.value === null) ? '' : String(o.value);
    var str = String(o);
    var i = str.indexOf('-');
    return i >= 0 ? str.slice(0, i) : str;
  }

  /**
   * 按 option 的 type 生成源脚本期望的默认值：
   *   multi-select 收到的是 JSON 数组字符串（E-Hentai 直接 JSON.parse(options[0])，
   *                 所以这里不能给单个值，更不能给 "0-Misc" 这种带文本的）；
   *   dropdown     没有 default 时给空串，等价于 "<none>"；
   *   select       有 default 用 default，否则取第一项。
   */
  function defaultOptionValue(g) {
    if (!g) return '';
    var type = g.type || 'select';
    var d = g.default;
    if (type === 'multi-select') {
      if (Array.isArray(d)) return JSON.stringify(d.map(String));
      if (d === undefined || d === null) return '[]';
      return typeof d === 'string' ? d : JSON.stringify([String(d)]);
    }
    if (type === 'dropdown') {
      if (d !== undefined && d !== null) return String(d);
      return optionValue(g.options ? g.options[0] : '');
    }
    if (d !== undefined && d !== null) return String(d);
    return optionValue(g.options ? g.options[0] : '');
  }

  function c2s(o) { return o && o.title ? o.title : ''; }

  // 仅供本地回归测试用（运行时没人读它）
  API.__normalizeChapters = normalizeChapters;

  S.SourceEngine = API;
})();
