const fs=require('fs'),path=require('path'),vm=require('vm');
const host=require('./host_node');
const BUNDLE=fs.readFileSync(path.join(__dirname,'..','bundle.js'),'utf8');
const ROUTES=fs.readFileSync(path.join(__dirname,'..','routes.json'),'utf8');
const waiters={};
function emit(o){ if(o.id==='route_state')return; const w=waiters[o.id]; if(w){delete waiters[o.id]; w(o);} }
const ctx=(()=>{const s={__hostRaw:host.__hostRaw,prompt:(k,p)=>{if(k==='__event__'){emit(JSON.parse(p));return '';} if(k==='__host__')return host.__hostRaw(p); return '';},console:{log(){},error(){},warn(){}},TextDecoder,TextEncoder,URL,URLSearchParams,setTimeout:(f,t)=>setTimeout(f,Math.min(t||0,5)),clearTimeout,setInterval:()=>0,clearInterval(){},atob:s=>Buffer.from(s,'base64').toString('binary'),btoa:s=>Buffer.from(s,'binary').toString('base64')};s.globalThis=s;const c=vm.createContext(s);vm.runInContext(BUNDLE,c,{filename:'bundle.js'});return c;})();
host.initRoute();
vm.runInContext('SourceEngine.init('+JSON.stringify(ROUTES)+', null)',ctx);
vm.runInContext('SourceEngine.boot()',ctx);
// 探内部实例
const out = vm.runInContext(`(function(){
  try{
    var s = SourceEngine.__probe ? SourceEngine.__probe('jm') : null;
    return 'no probe';
  }catch(e){return String(e)}
})()`, ctx);
console.log(out);
console.log('loadSetting apiDomain =', vm.runInContext(`sendMessage({method:'load_setting', key:'jm', setting_key:'apiDomain'})`, ctx));
