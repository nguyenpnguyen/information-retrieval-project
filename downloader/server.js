// Downloader (N5): nhận url qua /load, tải ở nền, gửi thẳng sang reader /parse.
import http from 'node:http';
import { fileURLToPath } from 'node:url';
import puppeteer from 'puppeteer';

const IDLE = 'đang chờ'.normalize('NFC');
const BUSY = 'đang bận'.normalize('NFC');
const ACCEPTED = 'đã nhận'.normalize('NFC');

// Đuôi tài liệu/ảnh: tải bằng fetch, không cần trình duyệt.
const BINARY_EXT = new Set(['pdf', 'doc', 'docx', 'xls', 'xlsx', 'ppt', 'pptx',
  'jpg', 'jpeg', 'png', 'gif', 'webp', 'svg', 'bmp']);

const BLOCKED = new Set(['image', 'media', 'font', 'stylesheet']);
const SETTLE_MS = 2000;

const num = (v, d) => (Number(v) > 0 ? Number(v) : d);

export function createServer({
  readerUrl = process.env.READER_URL || 'http://localhost:6600/parse',
  navTimeoutMs = num(process.env.NAV_TIMEOUT_MS, 30000),
  maxBytes = num(process.env.MAX_BYTES, 50 * 1024 * 1024),
} = {}) {
  let busy = false;
  let browserPromise = null;

  async function getBrowser() {
    if (!browserPromise) {
      browserPromise = puppeteer
        .launch({ headless: true, args: ['--no-sandbox', '--disable-dev-shm-usage'] })
        .then((b) => {
          // Chrome chết/ngắt kết nối: bỏ bản cache để job sau mở lại.
          b.on('disconnected', () => { browserPromise = null; });
          return b;
        })
        .catch((e) => { browserPromise = null; throw e; });
    }
    return browserPromise;
  }

  async function postToReader(target, contentType, body, url) {
    const r = await fetch(target, {
      method: 'POST',
      headers: { 'Content-Type': contentType },
      body,
      signal: AbortSignal.timeout(60000),
    });
    const text = await r.text().catch(() => '');
    if (!r.ok) console.error(`reader trả ${r.status} cho ${url}: ${text.slice(0, 300)}`);
  }

  const sendToReader = (payload) =>
    postToReader(readerUrl, 'application/json; charset=utf-8', JSON.stringify(payload), payload.url);

  async function fetchBinary(url) {
    const r = await fetch(url, { redirect: 'follow', signal: AbortSignal.timeout(navTimeoutMs) });
    if (r.status >= 400) return console.error(`HTTP ${r.status}: ${url}`);
    const len = Number(r.headers.get('content-length'));
    if (len > maxBytes) return console.error(`quá lớn (${len} > ${maxBytes}): ${url}`);
    const buf = Buffer.from(await r.arrayBuffer());
    if (buf.length > maxBytes) return console.error(`quá lớn (${buf.length} > ${maxBytes}): ${url}`);
    const ct = r.headers.get('content-type');
    if (ct && !/^application\/json/i.test(ct)) {
      // Có kiểu HTTP: gửi nguyên byte tệp, url đi theo query.
      const target = new URL(readerUrl);
      target.searchParams.set('url', url);
      target.searchParams.set('final_url', r.url);
      return postToReader(target, ct, buf, url);
    }
    // Không có kiểu HTTP: gói JSON, nội dung base64.
    await sendToReader({
      url, final_url: r.url, format: 'other', encoding: 'base64', content: buf.toString('base64'),
    });
  }

  async function fetchHtml(url) {
    const page = await (await getBrowser()).newPage();
    let fallback = false;
    try {
      // Reader chỉ cần HTML: không tải ảnh/font/css/video của trang, đỡ chậm và đỡ số request vào site.
      await page.setRequestInterception(true);
      page.on('request', (r) => (BLOCKED.has(r.resourceType()) ? r.abort() : r.continue()));
      let resp;
      try {
        resp = await page.goto(url, { waitUntil: 'load', timeout: navTimeoutMs });
        // Chờ thêm ajax nạp nội dung, tối đa SETTLE_MS.
        await page.waitForNetworkIdle({ idleTime: 300, timeout: SETTLE_MS }).catch(() => {});
      } catch (e) {
        // Chrome hủy điều hướng khi url trả về file tải xuống (pdf ở url không có đuôi...).
        if (!/ERR_ABORTED/.test(e?.message)) throw e;
        fallback = true;
        return;
      }
      if (resp && resp.status() >= 400) return console.error(`HTTP ${resp.status()}: ${url}`);
      const ct = resp?.headers()['content-type'] || '';
      if (!/text\/html/i.test(ct)) { fallback = true; return; }
      await sendToReader({
        url, final_url: page.url(), format: 'html', content_type: ct,
        encoding: 'text', content: await page.content(),
      });
    } finally {
      await page.close().catch(() => {});
    }
    if (fallback) await fetchBinary(url);
  }

  async function job(url) {
    try {
      const ext = new URL(url).pathname.split('.').pop().toLowerCase();
      await (BINARY_EXT.has(ext) ? fetchBinary(url) : fetchHtml(url));
    } catch (e) {
      console.error(`lỗi tải ${url}:`, e?.message || e);
    } finally {
      busy = false;
    }
  }

  const reply = (res, code, obj) => {
    res.writeHead(code, { 'Content-Type': 'application/json; charset=utf-8' });
    res.end(JSON.stringify(obj));
  };

  const server = http.createServer((req, res) => {
    const path = new URL(req.url, 'http://x').pathname;
    if (req.method === 'GET' && path === '/ping') return reply(res, 200, { status: busy ? BUSY : IDLE });
    if (req.method !== 'POST' || path !== '/load') return reply(res, 404, { error: 'not found' });
    let raw = '';
    req.on('data', (c) => { raw += c; if (raw.length > 1e6) req.destroy(); });
    req.on('end', () => {
      if (busy) return reply(res, 409, { status: BUSY });
      let url;
      try {
        url = new URL(JSON.parse(raw).url);
        if (!['http:', 'https:'].includes(url.protocol)) throw 0;
      } catch {
        return reply(res, 400, { error: 'body phải là JSON {"url": "http(s)://..."}' });
      }
      busy = true;
      reply(res, 202, { status: ACCEPTED });
      job(url.href);
    });
  });

  server.closeAll = async () => {
    server.close();
    if (browserPromise) await (await browserPromise.catch(() => null))?.close().catch(() => {});
  };
  return server;
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  const server = createServer();
  const port = num(process.env.PORT, 8800);
  server.listen(port, () => console.log(`downloader nghe cổng ${port}`));
  const stop = () => server.closeAll().finally(() => process.exit(0));
  process.on('SIGTERM', stop);
  process.on('SIGINT', stop);
}
