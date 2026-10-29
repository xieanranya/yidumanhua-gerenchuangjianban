// 章节结构兼容层回归。
//
// 背景：源脚本用的是**普通对象**当 Map（{ 分组名: { 章节id: 标题 } }），
// 而引擎以前只认 JS 的 Map，于是整组被压成「一个章节、id = 分组名」——
// 拷贝漫画的章节 id 变成「默認」，点进去 404。
const fs = require('fs'), vm = require('vm'), path = require('path');
const B = fs.readFileSync(path.join(__dirname, '..', 'bundle.js'), 'utf8');
const R = fs.readFileSync(path.join(__dirname, '..', 'routes.json'), 'utf8');

const sb = {
  __hostRaw: () => JSON.stringify({ v: null }),
  console: { log: () => {}, error: () => {}, warn: () => {} },
  TextDecoder, TextEncoder, URL, URLSearchParams,
  setTimeout: (f) => setTimeout(f, 0), clearTimeout,
  setInterval: () => 0, clearInterval: () => {},
  atob: (s) => Buffer.from(s, 'base64').toString('binary'),
  btoa: (s) => Buffer.from(s, 'binary').toString('base64'),
};
sb.globalThis = sb;
const ctx = vm.createContext(sb);
vm.runInContext(B, ctx);
vm.runInContext('SourceEngine.init(' + JSON.stringify(R) + ',null)', ctx);

function norm(chapters) {
  const json = JSON.stringify(chapters);
  return JSON.parse(vm.runInContext(
    `JSON.stringify(SourceEngine.__normalizeChapters(JSON.parse(${JSON.stringify(json)})))`, ctx
  ));
}

let pass = 0, fail = 0;
function check(name, cond, extra) {
  if (cond) { pass++; console.log('  ✓', name); }
  else { fail++; console.log('  ✗', name, extra === undefined ? '' : JSON.stringify(extra)); }
}

// 1) 拷贝漫画真实结构：普通对象嵌普通对象
{
  const g = norm({ '默認': { 'uuid-1': '第01話', 'uuid-2': '第02話' } });
  check('嵌套普通对象 → 1 组 2 章', g.length === 1 && g[0].items.length === 2, g);
  check('章节 id 是 uuid，不是分组名', g[0].items[0].id === 'uuid-1', g[0].items[0]);
  check('章节标题正确', g[0].items[1].title === '第02話', g[0].items[1]);
}

// 2) 扁平普通对象（DOM 兜底路径）：{ 章节id: 标题 }
{
  const g = norm({ 'cid-1': '第1话', 'cid-2': '第2话' });
  check('扁平对象 → 1 组 2 章（不是 2 组）', g.length === 1 && g[0].items.length === 2, g);
  check('扁平对象 id/标题', g[0].items[0].id === 'cid-1' && g[0].items[1].title === '第2话', g[0].items);
}

// 3) 真 Map 仍然正常（禁漫）
{
  // Map 不能走 JSON，直接在沙箱里构造
  const g = JSON.parse(vm.runInContext(
    "JSON.stringify(SourceEngine.__normalizeChapters(new Map([['1','第1話'],['2','第2話']])))", ctx
  ));
  check('Map → 1 组 2 章', g.length === 1 && g[0].items.length === 2, g);
}

// 4) 数组形态
{
  const g = norm([{ id: 'a', title: 'A' }, { id: 'b', title: 'B' }]);
  check('数组 → 1 组 2 章', g.length === 1 && g[0].items[1].id === 'b', g);
}

// 5) { 分组: [ {id,title} ] }
{
  const g = norm({ '第1卷': [{ id: 'x', title: 'x1' }] });
  check('分组 + 数组 → 组名保留', g.length === 1 && g[0].title === '第1卷' && g[0].items.length === 1, g);
}

// 6) 单个章节对象（{id,title}）仍能被识别
{
  const g = norm({ '第一话': { id: 'only', title: '第一话' } });
  check('单章节对象 → 1 组 1 章', g.length === 1 && g[0].items[0].id === 'only', g);
}

console.log(`  通过 ${pass} / 失败 ${fail}`);
process.exit(fail ? 1 : 0);
