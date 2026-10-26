const fs=require('fs'),path=require('path'),vm=require('vm');
const host=require('./host_node');
const BUNDLE=fs.readFileSync(path.join(__dirname,'..','bundle.js'),'utf8');
const ROUTES=fs.readFileSync(path.join(__dirname,'..','routes.json'),'utf8');
const waiters={};
function emit(o){ if(o.id==='route_state')return; const w=waiters[o.id]; if(w){delete waiters[o.id]; w(o);} }
const ctx=(()=>{const s={__hostRaw:host.__hostRaw,prompt:(k,p)=>{if(k==='__event__'){emit(JSON.parse(p));return '';} if(k==='__host__')return host.__hostRaw(p); return '';},console:{log(){},error(){},warn(){}},TextDecoder,TextEncoder,URL,URLSearchParams,setTimeout:(f,t)=>setTimeout(f,Math.min(t||0,5)),clearTimeout,setInterval:()=>0,clearInterval(){},atob:s=>Buffer.from(s,'base64').toString('binary'),btoa:s=>Buffer.from(s,'binary').toString('base64')};s.globalThis=s;const c=vm.createContext(s);vm.runInContext(BUNDLE,c,{filename:'bundle.js'});return c;})();
function call(key,name,args){host.setSourceKey(key);const id=vm.runInContext(`SourceEngine.${name}(${(args||[]).map(a=>JSON.stringify(a)).join(',')})`,ctx);return new Promise(r=>{waiters[id]=r;setTimeout(()=>{if(waiters[id]){delete waiters[id];r({ok:false,error:'timeout'});}},45000);});}
(async()=>{
host.initRoute();
vm.runInContext('SourceEngine.init('+JSON.stringify(ROUTES)+', null)',ctx);
vm.runInContext('SourceEngine.boot()',ctx);
const r=await call('copy_manga','login',['copy_manga','probe@example.com','wrongpass123']);
console.log('RESULT',JSON.stringify(r).slice(0,300));
})();
