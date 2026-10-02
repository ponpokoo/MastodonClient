// Local fixture for CustomTabsBrowserDeviceTest. No Mastodon credentials or account needed.
// node tools/web-browser-fixture.mjs
// adb -s emulator-5554 reverse tcp:8765 tcp:8765
import http from 'node:http';

const base = 'http://127.0.0.1:8765';
const app = 'intent://www.youtube.com/#Intent;scheme=https;package=com.google.android.youtube;end';
const missing = `intent://missing/#Intent;scheme=nagisa-missing;package=io.github.ponpokoo.missing;S.browser_fallback_url=${encodeURIComponent(`${base}/fallback`)};end`;
http.createServer((request, response) => {
  response.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8', 'Cache-Control': 'no-store' });
  const title = request.url === '/fallback' ? 'Browser fallback reached' :
    request.url === '/next' ? 'Second browser page' : 'Nagisa browser fixture';
  response.end(`<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1"><title>${title}</title></head>
    <body style="font:24px sans-serif"><h1>${title}</h1>
    <p><a href="${base}/next">Next page</a></p>
    <p><a href="${app}">Open YouTube app</a></p>
    <p><a href="${missing}">Open missing app</a></p>
    ${request.url === '/automatic' ? `<script>setTimeout(() => location.href = ${JSON.stringify(app)}, 500);</script>` : ''}
    </body></html>`);
}).listen(8765, '127.0.0.1', () => console.log(`Browser fixture listening at ${base}`));
