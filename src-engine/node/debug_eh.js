// 定位 ehentai 卡死点：每一步都同步落盘，进程被杀也能看到最后一步。
const fs = require('fs');
const path = require('path');
const vm = require('vm');
const host = require('./host_node');

const LOG = path.join(__dirname, '..', '..', '.srcdata', 'eh_trace.log');
try { fs.unlinkSync(LOG); } catch (e) { }
const trace = (s) => fs.appendFileSync(LOG, Date.now() + ' ' + s + '\n');

const BUNDLE = fs.readFileSync(path.join(__dirname, '..', 'bundle.js'), 'utf8');
const ROUTES = fs.readFileSync(path.join(__dirname, '..', 'routes.json'), 'utf8');

const waiters = {};
function onEvent(o) {
  trace('event ' + JSON.stringify(o).slice(0, 300));
  const w = waiters[o.id];
  if (w) { delete waiters[o.id]; w(o); }
}

function wrapHost(msg) {
  trace('host ' + msg.method + ' ' + String(msg.url || msg.type || msg.data_key || '').slice(0, 120));
  const r = host.__hostCall(msg);
  trace('host<- ' + msg.method + ' status=' + (r && r.status));
  return r;
}

const sandbox = {
  __hostCall: wrapHost,
  prompt: (kind, payload) => {
    if (kind === '__event__') { onEvent(JSON.parse(payload)); return ''; }
    if (kind === '__host__') return JSON.stringify(wrapHost(JSON.parse(payload)));
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
trace('run bundle');
vm.runInContext(BUNDLE, ctx, { filename: 'bundle.js' });
trace('bundle ok');

host.setSourceKey('ehentai');
host.initRoute();
vm.runInContext('SourceEngine.init(' + JSON.stringify(ROUTES) + ', null)', ctx);
trace('init ok');
vm.runInContext('SourceEngine.boot()', ctx);
trace('boot ok');
trace('opts ' + JSON.stringify(vm.runInContext('SourceEngine.searchOptions("ehentai")', ctx)));

const id = vm.runInContext('SourceEngine.search("ehentai","love","[]",1)', ctx);
trace('search returned id=' + id);
const t = setInterval(() => { }, 1000);
setTimeout(() => {
  trace('TIMEOUT no result');
  clearInterval(t);
  process.exit(2);
}, 90000);
const w = o => {
  trace('RESULT ' + JSON.stringify(o).slice(0, 800));
  clearInterval(t);
  process.exit(0);
};
waiters[id] = w;
