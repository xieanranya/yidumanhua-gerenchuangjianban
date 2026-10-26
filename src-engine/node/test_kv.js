// 验证「登录态按源隔离」：只给 picacg 写登录标记，其他源必须是未登录
const fs = require('fs'), path = require('path'), vm = require('vm');
const host = require('./host_node');
const B = fs.readFileSync(path.join(__dirname, '..', 'bundle.js'), 'utf8');
const R = fs.readFileSync(path.join(__dirname, '..', 'routes.json'), 'utf8');
const s = { __hostRaw: host.__hostRaw, prompt: () => '', console: { log(){}, error(){}, warn(){} },
  TextDecoder, TextEncoder, URL, URLSearchParams, setTimeout: (f) => 0, clearTimeout,
  setInterval: () => 0, clearInterval: () => {}, atob: x => Buffer.from(x, 'base64').toString('binary'),
  btoa: x => Buffer.from(x, 'binary').toString('base64') };
s.globalThis = s;
const c = vm.createContext(s);
vm.runInContext(B, c);
vm.runInContext('SourceEngine.init(' + JSON.stringify(R) + ',null)', c);
vm.runInContext('SourceEngine.boot()', c);
host.setSourceKey('picacg');
vm.runInContext('__hostRaw(JSON.stringify({method:"save_data",key:"picacg",data_key:"__logged",data:{at:1}}))', c);
host.setSourceKey('jm');
const jm = vm.runInContext('SourceEngine.isLogged("jm")', c);
const pica = (() => { host.setSourceKey('picacg'); return vm.runInContext('SourceEngine.isLogged("picacg")', c); })();
console.log('jm 登录态 =', jm, '(应为 false)');
console.log('picacg 登录态 =', pica, '(应为 true)');
