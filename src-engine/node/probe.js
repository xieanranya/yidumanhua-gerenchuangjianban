// Node 侧探测实现：用 curl 探一个 host 是否可连（TCP+TLS 通就算通，403/404 不算失败）
const { spawn } = require('child_process');

const UA = 'Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36';

function probeHost(host, timeoutMs) {
  const t = timeoutMs || 3000;
  return new Promise((resolve) => {
    // 探测「这条线路本身能不能连」。默认绕开本机 HTTP 代理；SRC_PROXY 指定时按该代理探（模拟手机开 VPN）。
    const args = ['-sS', ...(process.env.SRC_PROXY ? ['--proxy', process.env.SRC_PROXY] : ['--noproxy', '*']),
      '-o', '/dev/null', '--max-time', String(t / 1000), '-A', UA,
      '-w', '%{http_code}', `https://${host}/`];
    const p = spawn('curl', args);
    let out = '';
    let settled = false;
    const fin = (ok) => { if (!settled) { settled = true; resolve(ok); } };
    p.stdout.on('data', d => { out += d.toString(); });
    p.on('error', () => fin(false));
    p.on('close', () => {
      const code = parseInt(out.trim(), 10);
      // 只要拿到 HTTP 状态码（含 301/403/404）就说明 TCP+TLS 通了
      fin(code >= 200 && code <= 499);
    });
    setTimeout(() => { try { p.kill('SIGKILL'); } catch (e) { } fin(false); }, t + 800);
  });
}

module.exports = { probeHost };
