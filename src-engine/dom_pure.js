// ============================================================================
// 纯 JS 的 HTML 解析 + CSS 选择器 —— 两端共用，不依赖 Jsoup / DOM
// 目标：行为对齐 Jsoup（源脚本作者按 Jsoup 语义写的选择器）
//   1) 容错解析：乱序标签、未闭合标签、隐式 tbody
//   2) 选择器：tag / #id / .class / [attr] / [attr=v] / [attr*=v] / [attr^=v]
//              / [attr$=v] / [attr~=v] / 后代(空格) / 子代(>) / 逗号分组 / :nth-child(n)
//   3) .text 归一化：块级元素之间补空格，其余直接拼接，最后压缩空白并 trim
// ============================================================================

const __VOID = new Set(['area', 'base', 'br', 'col', 'embed', 'hr', 'img', 'input',
  'link', 'meta', 'param', 'source', 'track', 'wbr', 'basefont', 'frame', 'isindex', 'keygen']);

const __RAW = new Set(['script', 'style', 'textarea', 'title', 'xmp', 'plaintext']);

// 这些标签出现时，会把未闭合的 <p> 关掉
const __P_CLOSERS = new Set(['address', 'article', 'aside', 'blockquote', 'div', 'dl', 'fieldset',
  'footer', 'form', 'h1', 'h2', 'h3', 'h4', 'h5', 'h6', 'header', 'hr', 'menu', 'nav', 'ol',
  'p', 'pre', 'section', 'table', 'ul', 'li', 'dd', 'dt', 'tr', 'td', 'th']);

// 同标签自动闭合（li 套 li、td 套 td 这类）
const __SELF_CLOSE_SAME = new Set(['li', 'dd', 'dt', 'tr', 'td', 'th', 'option', 'thead', 'tbody', 'tfoot', 'p']);

const __BLOCK = new Set(['address', 'article', 'aside', 'blockquote', 'br', 'dd', 'div', 'dl', 'dt',
  'fieldset', 'figcaption', 'figure', 'footer', 'form', 'h1', 'h2', 'h3', 'h4', 'h5', 'h6',
  'header', 'hr', 'li', 'main', 'nav', 'ol', 'p', 'pre', 'section', 'table', 'tbody', 'td',
  'tfoot', 'th', 'thead', 'tr', 'ul', 'img', 'option', 'select']);

const __ENTITIES = {
  amp: '&', lt: '<', gt: '>', quot: '"', apos: "'", nbsp: '\u00a0', copy: '\u00a9',
  reg: '\u00ae', hellip: '\u2026', mdash: '\u2014', ndash: '\u2013', middot: '\u00b7',
  laquo: '\u00ab', raquo: '\u00bb', ldquo: '\u201c', rdquo: '\u201d', lsquo: '\u2018',
  rsquo: '\u2019', times: '\u00d7', divide: '\u00f7', deg: '\u00b0', plusmn: '\u00b1',
  yen: '\u00a5', euro: '\u20ac', pound: '\u00a3', sect: '\u00a7', para: '\u00b6',
  bull: '\u2022', dagger: '\u2020', prime: '\u2032', Prime: '\u2033', ensp: '\u2002',
  emsp: '\u2003', thinsp: '\u2009', zwnj: '\u200c', zwj: '\u200d', larr: '\u2190',
  rarr: '\u2192', uarr: '\u2191', darr: '\u2193', harr: '\u2194', infin: '\u221e',
  ne: '\u2260', le: '\u2264', ge: '\u2265', hearts: '\u2665', spades: '\u2660',
  clubs: '\u2663', diams: '\u2666', check: '\u2713', cross: '\u2717', star: '\u2606',
};

function __decodeEntities(str) {
  if (str.indexOf('&') < 0) return str;
  return str.replace(/&(#x?[0-9a-fA-F]+|[a-zA-Z][a-zA-Z0-9]*);?/g, (whole, body) => {
    if (body[0] === '#') {
      const hex = body[1] === 'x' || body[1] === 'X';
      const code = parseInt(hex ? body.slice(2) : body.slice(1), hex ? 16 : 10);
      if (!isFinite(code) || code <= 0 || code > 0x10ffff) return whole;
      try { return String.fromCodePoint(code); } catch (e) { return whole; }
    }
    const v = __ENTITIES[body];
    return v === undefined ? whole : v;
  });
}

// ---------------------------------------------------------------- 节点模型

class __El {
  constructor(tag, attrs) {
    this.tag = tag;
    this.attrs = attrs || {};
    this.parent = null;
    this.children = [];
    this.isText = false;
  }
}

class __Tx {
  constructor(data) {
    this.data = data;
    this.parent = null;
    this.children = [];
    this.isText = true;
    this.tag = '';
    this.attrs = {};
  }
}

function __append(parent, node) {
  node.parent = parent;
  parent.children.push(node);
}

// ---------------------------------------------------------------- 解析器

function __parseHTML(html) {
  const root = new __El('#root', {});
  const stack = [root];
  let i = 0;
  const n = html.length;
  const top = () => stack[stack.length - 1];

  const pushText = (txt) => {
    if (!txt) return;
    __append(top(), new __Tx(__decodeEntities(txt)));
  };

  while (i < n) {
    const lt = html.indexOf('<', i);
    if (lt < 0) { pushText(html.slice(i)); break; }
    if (lt > i) pushText(html.slice(i, lt));

    // 注释 / doctype / CDATA
    if (html.startsWith('<!--', lt)) {
      const end = html.indexOf('-->', lt + 4);
      i = end < 0 ? n : end + 3;
      continue;
    }
    if (html.startsWith('<![CDATA[', lt)) {
      const end = html.indexOf(']]>', lt + 9);
      const raw = html.slice(lt + 9, end < 0 ? n : end);
      pushText(raw);
      i = end < 0 ? n : end + 3;
      continue;
    }
    if (html[lt + 1] === '!' || html[lt + 1] === '?') {
      const end = html.indexOf('>', lt);
      i = end < 0 ? n : end + 1;
      continue;
    }

    // 结束标签
    if (html[lt + 1] === '/') {
      const end = html.indexOf('>', lt);
      if (end < 0) { i = n; break; }
      const name = html.slice(lt + 2, end).trim().toLowerCase().split(/\s/)[0];
      i = end + 1;
      // 从栈顶往下找同名标签，找到就弹到它为止；找不到就忽略
      for (let k = stack.length - 1; k > 0; k--) {
        if (stack[k].tag === name) { stack.length = k; break; }
      }
      continue;
    }

    // 开始标签
    const m = /^<([a-zA-Z][^\s/>]*)/.exec(html.slice(lt, lt + 128));
    if (!m) { pushText(html.slice(lt, lt + 1)); i = lt + 1; continue; }
    const tag = m[1].toLowerCase();
    let p = lt + m[0].length;

    // 属性
    const attrs = {};
    let selfClose = false;
    while (p < n) {
      while (p < n && /\s/.test(html[p])) p++;
      if (p >= n) break;
      if (html[p] === '>') { p++; break; }
      if (html[p] === '/' && html[p + 1] === '>') { selfClose = true; p += 2; break; }
      if (html[p] === '/') { p++; continue; }
      // 属性名
      let ns = p;
      while (p < n && !/[\s=>\/]/.test(html[p])) p++;
      const name = html.slice(ns, p).toLowerCase();
      if (!name) { p++; continue; }
      while (p < n && /\s/.test(html[p])) p++;
      let val = '';
      if (html[p] === '=') {
        p++;
        while (p < n && /\s/.test(html[p])) p++;
        const q = html[p];
        if (q === '"' || q === "'") {
          const end = html.indexOf(q, p + 1);
          val = html.slice(p + 1, end < 0 ? n : end);
          p = end < 0 ? n : end + 1;
        } else {
          ns = p;
          while (p < n && !/[\s>]/.test(html[p])) p++;
          val = html.slice(ns, p);
        }
        val = __decodeEntities(val);
      }
      if (!(name in attrs)) attrs[name] = val;
    }
    i = p;

    if (tag === 'html' || tag === 'head' || tag === 'body' || tag === 'tbody' && top().tag === 'tbody') {
      // 这些包裹层不影响查询，但保留节点结构
    }

    // 隐式 tbody：tr 直接挂在 table 下时补一层
    if (tag === 'tr' && top().tag === 'table') {
      const tb = new __El('tbody', {});
      __append(top(), tb);
      stack.push(tb);
    }
    if ((tag === 'td' || tag === 'th') && top().tag === 'table') {
      const tb = new __El('tbody', {});
      __append(top(), tb);
      stack.push(tb);
      const tr = new __El('tr', {});
      __append(top(), tr);
      stack.push(tr);
    }

    // 同标签自动闭合
    if (__SELF_CLOSE_SAME.has(tag)) {
      while (stack.length > 1 && top().tag === tag) stack.pop();
    }
    // <p> 遇到块级元素先关掉
    if (__P_CLOSERS.has(tag)) {
      while (stack.length > 1 && top().tag === 'p') stack.pop();
    }

    const el = new __El(tag, attrs);
    __append(top(), el);

    if (selfClose || __VOID.has(tag)) continue;

    if (__RAW.has(tag)) {
      const close = '</' + tag;
      const end = html.toLowerCase().indexOf(close, i);
      const raw = html.slice(i, end < 0 ? n : end);
      if (raw) {
        const t = new __Tx(raw);
        __append(el, t);
      }
      if (end < 0) { i = n; } else {
        const gt = html.indexOf('>', end);
        i = gt < 0 ? n : gt + 1;
      }
      continue;
    }

    stack.push(el);
  }

  return root;
}

// ---------------------------------------------------------------- 选择器

function __parseSelector(sel) {
  // 返回 [[{compound, combinator}, ...], ...]  逗号分组
  const groups = [];
  let cur = [];
  let buf = '';
  let depth = 0;
  let quote = null;
  const flushCompound = () => {
    const c = buf.trim();
    buf = '';
    return c;
  };
  const pushGroup = () => {
    const c = flushCompound();
    if (c) cur.push({ compound: __parseCompound(c), combinator: cur.length === 0 ? null : ' ' });
    if (cur.length) groups.push(cur);
    cur = [];
  };

  for (let i = 0; i < sel.length; i++) {
    const ch = sel[i];
    if (quote) { buf += ch; if (ch === quote) quote = null; continue; }
    if (ch === '"' || ch === "'") { quote = ch; buf += ch; continue; }
    if (ch === '[') { depth++; buf += ch; continue; }
    if (ch === ']') { depth--; buf += ch; continue; }
    if (depth > 0) { buf += ch; continue; }
    if (ch === ',') { pushGroup(); continue; }
    if (/\s/.test(ch)) {
      // 空格是后代组合器。以前这里只 clear 了 buf、没把片段推进 cur，
      // 结果 "table.x > tbody > tr" 会被解析成纯 "tr"，所有带空格的选择器全部失效。
      const c = flushCompound();
      if (c) cur.push({ compound: __parseCompound(c), combinator: cur.length === 0 ? null : ' ' });
      continue;
    }
    if (ch === '>') {
      const c = flushCompound();
      if (c) cur.push({ compound: __parseCompound(c), combinator: cur.length === 0 ? null : ' ' });
      cur.push({ combinator: '>', compound: null });
      continue;
    }
    buf += ch;
  }
  pushGroup();

  // 修正：把 {"combinator":'>'} 这种占位符与后一个 compound 合并
  const out = [];
  for (const g of groups) {
    const seq = [];
    let comb = null;
    for (const item of g) {
      if (item.compound === null) { comb = '>'; continue; }
      seq.push({ compound: item.compound, combinator: seq.length === 0 ? null : (comb || ' ') });
      comb = null;
    }
    if (seq.length) out.push(seq);
  }
  return out;
}

function __parseCompound(str) {
  const c = { tag: null, id: null, classes: [], attrs: [], nth: [] };
  let i = 0;
  const n = str.length;
  while (i < n) {
    const ch = str[i];
    if (ch === '*') { i++; continue; }
    if (ch === '#') {
      let j = i + 1; let s = '';
      while (j < n && /[\w\-\u00a0-\uffff]/.test(str[j])) s += str[j++];
      c.id = s; i = j; continue;
    }
    if (ch === '.') {
      let j = i + 1; let s = '';
      while (j < n && /[\w\-\u00a0-\uffff]/.test(str[j])) s += str[j++];
      c.classes.push(s); i = j; continue;
    }
    if (ch === '[') {
      const end = str.indexOf(']', i);
      if (end < 0) break;
      const body = str.slice(i + 1, end);
      const am = /^\s*([\w\-:]+)\s*(?:([*^$~|]?=)\s*(.*?)\s*)?$/.exec(body);
      if (am) {
        let v = am[3] === undefined ? null : am[3];
        if (v != null) {
          if ((v[0] === '"' && v[v.length - 1] === '"') || (v[0] === "'" && v[v.length - 1] === "'")) {
            v = v.slice(1, -1);
          }
        }
        c.attrs.push({ name: am[1].toLowerCase(), op: am[2] || null, value: v });
      }
      i = end + 1; continue;
    }
    if (ch === ':') {
      const m = /^:([\w-]+)(?:\(([^)]*)\))?/.exec(str.slice(i));
      if (m) {
        c.nth.push({ name: m[1], arg: m[2] });
        i += m[0].length; continue;
      }
      i++; continue;
    }
    // 标签名
    let j = i; let s = '';
    while (j < n && /[\w\-\u00a0-\uffff]/.test(str[j])) s += str[j++];
    if (s) { c.tag = s.toLowerCase(); i = j; continue; }
    i++;
  }
  return c;
}

function __matchCompound(el, c) {
  if (!el || el.isText) return false;
  if (c.tag && el.tag !== c.tag) return false;
  if (c.id != null) {
    if ((el.attrs.id || '') !== c.id) return false;
  }
  if (c.classes.length) {
    const cls = (el.attrs['class'] || '').split(/\s+/);
    for (const want of c.classes) {
      if (!cls.includes(want)) return false;
    }
  }
  for (const a of c.attrs) {
    const v = el.attrs[a.name];
    if (v === undefined) return false;
    if (a.op === null || a.value === null) continue;
    const target = a.value;
    switch (a.op) {
      case '=': if (v !== target) return false; break;
      case '*=': if (!v.includes(target)) return false; break;
      case '^=': if (!v.startsWith(target)) return false; break;
      case '$=': if (!v.endsWith(target)) return false; break;
      case '~=': if (!v.split(/\s+/).includes(target)) return false; break;
      case '|=': if (!(v === target || v.startsWith(target + '-'))) return false; break;
      default: break;
    }
  }
  for (const p of c.nth) {
    if (p.name === 'nth-child') {
      const idx = parseInt(p.arg, 10);
      if (!isFinite(idx)) continue;
      const parent = el.parent;
      if (!parent) return false;
      const siblings = parent.children.filter(x => !x.isText);
      if (siblings.indexOf(el) + 1 !== idx) return false;
    } else if (p.name === 'first-child') {
      const parent = el.parent;
      if (!parent) return false;
      const siblings = parent.children.filter(x => !x.isText);
      if (siblings[0] !== el) return false;
    } else if (p.name === 'last-child') {
      const parent = el.parent;
      if (!parent) return false;
      const siblings = parent.children.filter(x => !x.isText);
      if (siblings[siblings.length - 1] !== el) return false;
    }
  }
  return true;
}

function __matchSeq(el, seq, idx) {
  if (!__matchCompound(el, seq[idx].compound)) return false;
  if (idx === 0) return true;
  const comb = seq[idx].combinator;
  if (comb === '>') {
    const p = el.parent;
    if (!p || p.tag === '#root') return false;
    return __matchSeq(p, seq, idx - 1);
  }
  let p = el.parent;
  while (p && p.tag !== '#root') {
    if (__matchSeq(p, seq, idx - 1)) return true;
    p = p.parent;
  }
  return false;
}

function __matches(el, groups) {
  for (const seq of groups) {
    if (__matchSeq(el, seq, seq.length - 1)) return true;
  }
  return false;
}

function __walk(el, cb) {
  for (const child of el.children) {
    if (child.isText) continue;
    if (cb(child) === false) return false;
    if (__walk(child, cb) === false) return false;
  }
  return true;
}

function __collect(el, groups, out, limit) {
  __walk(el, (child) => {
    if (__matches(child, groups)) {
      out.push(child);
      if (limit && out.length >= limit) return false;
    }
    return true;
  });
}

// ---------------------------------------------------------------- 文本

function __nodeText(node, out) {
  if (node.isText) { out.push(node.data); return; }
  const block = __BLOCK.has(node.tag);
  if (block) out.push(' ');
  for (const c of node.children) __nodeText(c, out);
  if (block) out.push(' ');
}

function __normText(s) {
  return s.replace(/[\u00a0\u3000]/g, ' ').replace(/\s+/g, ' ').trim();
}

function __innerHTML(node) {
  let s = '';
  for (const c of node.children) {
    if (c.isText) {
      s += c.data.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
    } else {
      s += '<' + c.tag;
      for (const k in c.attrs) s += ' ' + k + '="' + String(c.attrs[k]).replace(/"/g, '&quot;') + '"';
      s += '>' + __innerHTML(c) + '</' + c.tag + '>';
    }
  }
  return s;
}

// ---------------------------------------------------------------- 对外类

class HtmlDocument {
  constructor(html) {
    this._root = __parseHTML(html == null ? '' : String(html));
    this._cache = new Map();
  }

  _groups(query) {
    let g = this._cache.get(query);
    if (!g) { g = __parseSelector(query); this._cache.set(query, g); }
    return g;
  }

  querySelector(query) {
    const out = [];
    __collect(this._root, this._groups(query), out, 1);
    return out.length ? new HtmlElement(out[0], this) : null;
  }

  querySelectorAll(query) {
    const out = [];
    __collect(this._root, this._groups(query), out, 0);
    return out.map(x => new HtmlElement(x, this));
  }

  getElementById(id) {
    const out = [];
    __walk(this._root, (el) => {
      if ((el.attrs.id || '') === id) { out.push(el); return false; }
      return true;
    });
    return out.length ? new HtmlElement(out[0], this) : null;
  }

  dispose() { this._root = null; this._cache.clear(); }
}

class HtmlElement {
  constructor(node, doc) {
    this._n = node;
    this.doc = doc;
  }

  get _groups() { return this.doc._groups.bind(this.doc); }

  get text() {
    const out = [];
    __nodeText(this._n, out);
    // 官方 text 是 textContent 语义，不做 trim。
    // E-Hentai 会拿 " Add to Favorites"（前导空格）做比较，trim 过就判断反了。
    return out.join('').replace(/[\u00a0\u3000]/g, ' ');
  }

  get attributes() {
    const o = {};
    for (const k in this._n.attrs) o[k] = this._n.attrs[k];
    return o;
  }

  querySelector(query) {
    const out = [];
    __collect(this._n, this.doc._groups(query), out, 1);
    return out.length ? new HtmlElement(out[0], this.doc) : null;
  }

  querySelectorAll(query) {
    const out = [];
    __collect(this._n, this.doc._groups(query), out, 0);
    return out.map(x => new HtmlElement(x, this.doc));
  }

  get children() {
    return this._n.children.filter(x => !x.isText).map(x => new HtmlElement(x, this.doc));
  }

  get nodes() {
    return this._n.children.map(x => new HtmlNode(x, this.doc));
  }

  get innerHTML() { return __innerHTML(this._n); }

  get parent() {
    const p = this._n.parent;
    if (!p || p.tag === '#root') return null;
    return new HtmlElement(p, this.doc);
  }

  get classNames() {
    const v = this._n.attrs['class'];
    return v ? v.split(/\s+/).filter(Boolean) : [];
  }

  get id() {
    const v = this._n.attrs.id;
    return v === undefined ? null : v;
  }

  get localName() { return this._n.tag; }

  get tag() { return this._n.tag; }

  get className() { return this._n.attrs['class'] || ''; }

  getAttribute(name) {
    const v = this._n.attrs[String(name).toLowerCase()];
    return v === undefined ? null : v;
  }

  hasAttribute(name) { return Object.prototype.hasOwnProperty.call(this._n.attrs, String(name).toLowerCase()); }

  _sibling(dir) {
    const p = this._n.parent;
    if (!p) return null;
    const sib = p.children.filter(x => !x.isText);
    const idx = sib.indexOf(this._n) + dir;
    if (idx < 0 || idx >= sib.length) return null;
    return new HtmlElement(sib[idx], this.doc);
  }

  get previousElementSibling() { return this._sibling(-1); }

  get nextElementSibling() { return this._sibling(1); }
}

class HtmlNode {
  constructor(node, doc) {
    this._n = node;
    this.doc = doc;
  }

  get text() {
    if (this._n.isText) return __normText(this._n.data);
    const out = [];
    __nodeText(this._n, out);
    return __normText(out.join(''));
  }

  get type() { return this._n.isText ? 'text' : 'element'; }

  toElement() {
    if (this._n.isText) return null;
    return new HtmlElement(this._n, this.doc);
  }
}
