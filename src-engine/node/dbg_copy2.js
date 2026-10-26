const host=require('./host_node');
host.initRoute();
function post(url, body){
  const r = host.__hostRaw(JSON.stringify({method:'http', url, http_method:'POST', headers:{'Content-Type':'application/x-www-form-urlencoded; charset=UTF-8','User-Agent':'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36','platform':'2','Referer':'https://www.copy5000.com/web/login','Accept':'application/json, text/plain, */*'}, data: body}));
  const j = JSON.parse(r);
  return j;
}
const salt = 123456;
function b64(s){const c='ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/';let out='',i=0;while(i<s.length){const a=s.charCodeAt(i++);const b=i<s.length?s.charCodeAt(i++):0;const d=i<s.length?s.charCodeAt(i++):0;const bm=(a<<16)|(b<<8)|d;out+=c.charAt((bm>>18)&63);out+=c.charAt((bm>>12)&63);out+=(i>s.length+1)?'=':c.charAt((bm>>6)&63);out+=(i>s.length)?'=':c.charAt(bm&63);}return out;}
const body = `username=${encodeURIComponent('probe@example.com')}&password=${encodeURIComponent(b64('wrongpass123-'+salt))}&salt=${salt}&platform=2&version=2025.12.10&source=freeSite`;
for (const p of ['/api/kb/web/login','/api/v1/login']) {
  const r = post('https://www.copy5000.com'+p, body);
  const v = r.v || {};
  console.log(p, 'status=', v.status, 'body=', String(v.body||'').slice(0,400));
}
