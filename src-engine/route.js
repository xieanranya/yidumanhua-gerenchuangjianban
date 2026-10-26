/*
 * 域名别名层（三端共用，纯逻辑、无平台依赖）
 *
 * 只有一个目的：源脚本里写死的主机如果国内打不开，就换成同一套站点的另一个域名。
 * **没有「直连 / VPN」概念，也没有探测、没有离线态**——就像普通聚合软件那样：
 * 请求该发哪儿发哪儿，发不通就换个域名再发一次，用户不需要知道。
 *
 * 概念：
 *   source = 一个源（jm / copy_manga / Komiic ...）
 *   map[]  = 别名规则，每条 { re: 原域名正则, to: 首选域名, alts: [备用域名] }
 *
 * 生命周期：
 *   R.init(table)            读 routes.json（obj 或 JSON 串）
 *   R.rewrite(url)           请求前调用（prelude 的 sendMessage 里），换成可用域名
 *   R.report(url, status)    请求失败后调用；返回 { key, next } 表示还能换下一个域名再试一次
 *
 * 状态只有一份内存里的「坏点表」：某个目标域名失败后 10 分钟内不再选它，
 * 到点自动失效。不落盘、不上报界面，重启即回到首选域名。
 */
(function (root, factory) {
  if (typeof module === 'object' && module.exports) module.exports = factory();
  else root.ComicRoute = factory();
})(typeof globalThis !== 'undefined' ? globalThis : this, function () {
  var S = {
    table: null,
    bad: {},          // key -> { host: 失效时间戳 }
    ttlMs: 10 * 60 * 1000
  };

  function now() { return Date.now(); }

  function init(table) {
    S.table = typeof table === 'string' ? JSON.parse(table) : table;
    S.bad = {};
    return S;
  }

  function sources() {
    return (S.table && S.table.sources) || [];
  }

  function hostOf(url) {
    try { return new URL(url).hostname; } catch (e) {
      var m = /^[a-z][a-z0-9+.-]*:\/\/([^\/:?#]+)/i.exec(url);
      return m ? m[1] : '';
    }
  }

  function isBad(key, host) {
    var m = S.bad[key];
    if (!m) return false;
    var until = m[host];
    if (!until) return false;
    if (until <= now()) { delete m[host]; return false; }
    return true;
  }

  function markBad(key, host) {
    if (!S.bad[key]) S.bad[key] = {};
    S.bad[key][host] = now() + S.ttlMs;
  }

  function clearBad(key) { S.bad[key] = {}; }

  /** 该规则的候选目标（首选 + 备用） */
  function targetsOf(rule) {
    var out = [];
    [rule.to].concat(rule.alts || []).forEach(function (h) {
      if (h && out.indexOf(h) < 0) out.push(h);
    });
    return out;
  }

  /** 按「原域名」找规则 */
  function ruleFor(host) {
    var list = sources();
    for (var i = 0; i < list.length; i++) {
      var s = list[i];
      if (s.rewrite === false) continue;
      var map = s.map || [];
      for (var j = 0; j < map.length; j++) {
        try { if (new RegExp(map[j].re).test(host)) return { src: s, rule: map[j] }; } catch (e) { }
      }
    }
    return null;
  }

  /** 按「已改写的目标域名」反查规则 */
  function ruleForTarget(host) {
    var list = sources();
    for (var i = 0; i < list.length; i++) {
      var s = list[i];
      if (s.rewrite === false) continue;
      var map = s.map || [];
      for (var j = 0; j < map.length; j++) {
        if (targetsOf(map[j]).indexOf(host) >= 0) return { src: s, rule: map[j], target: true };
      }
    }
    return null;
  }

  function rewrite(url) {
    if (!S.table || typeof url !== 'string') return url;
    var host = hostOf(url);
    if (!host) return url;
    var hit = ruleFor(host);
    if (!hit) return url;
    var cands = targetsOf(hit.rule);
    for (var i = 0; i < cands.length; i++) {
      if (cands[i] === host) return url;
      if (isBad(hit.src.key, cands[i])) continue;
      return url.replace('://' + host, '://' + cands[i]);
    }
    // 候选全被标坏：不改，保持源脚本写的原域名
    return url;
  }

  /**
   * 请求失败（连不上 / 5xx）后调用。返回 { key, next } 表示还有别的域名可试，
   * 调用方据此重试一次；返回 null 表示没得换（CDN / 图片域名一律忽略）。
   */
  function report(url, status) {
    if (!S.table) return null;
    var code = Number(status);
    if (code >= 200 && code <= 499) return null;   // 4xx 是站点在说话，不是域名不通
    var host = hostOf(url);
    if (!host) return null;

    var hit = ruleForTarget(host);
    if (hit) {
      markBad(hit.src.key, host);
      var cands = targetsOf(hit.rule);
      for (var i = 0; i < cands.length; i++) {
        if (cands[i] !== host && !isBad(hit.src.key, cands[i])) {
          return { key: hit.src.key, from: host, next: cands[i] };
        }
      }
      return null;
    }

    // 打到的是源脚本写死的原域名：清掉这家源的坏点，让它改回首选域名再试
    var hit2 = ruleFor(host);
    if (hit2) {
      clearBad(hit2.src.key);
      var c2 = targetsOf(hit2.rule);
      if (c2.length && c2[0] !== host) {
        return { key: hit2.src.key, from: host, next: c2[0] };
      }
    }
    return null;
  }

  return {
    init: init,
    rewrite: rewrite,
    report: report,
    hostOf: hostOf,
    _state: function () { return S; },
    _reset: function () { S.bad = {}; }
  };
});
