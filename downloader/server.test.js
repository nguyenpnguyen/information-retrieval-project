import test from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import { createServer } from './server.js';

const MARKER = 'xin chào-marker-123';
const PDF = Buffer.from('%PDF-1.4 fake \x00\x01\xff bytes');

const listen = (s) => new Promise((r) => s.listen(0, '127.0.0.1', () => r(s.address().port)));

test('downloader', async (t) => {
  const site = http.createServer((req, res) => {
    if (req.url.endsWith('.pdf')) {
      res.writeHead(200, { 'Content-Type': 'application/pdf' });
      return res.end(PDF);
    }
    if (req.url.endsWith('.png')) return res.end(PDF);  // không có Content-Type
    res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
    // trễ nhẹ để kịp quan sát trạng thái bận
    setTimeout(() => res.end(`<html><body><p>${MARKER}</p></body></html>`), 500);
  });
  const received = [];
  const reader = http.createServer((req, res) => {
    const chunks = [];
    req.on('data', (c) => chunks.push(c));
    req.on('end', () => {
      const body = Buffer.concat(chunks);
      const type = req.headers['content-type'];
      const q = new URL(req.url, 'http://x').searchParams;
      received.push(type.startsWith('application/json') ? JSON.parse(body) : { type, url: q.get('url'), body });
      res.end('{}');
    });
  });
  const sitePort = await listen(site);
  const readerPort = await listen(reader);
  const dl = createServer({ readerUrl: `http://127.0.0.1:${readerPort}/parse`, navTimeoutMs: 15000 });
  const base = `http://127.0.0.1:${await listen(dl)}`;
  t.after(async () => { await dl.closeAll(); site.close(); reader.close(); });

  const get = (p) => fetch(base + p).then(async (r) => [r.status, await r.json()]);
  const post = (body) => fetch(base + '/load', { method: 'POST', body }).then(async (r) => [r.status, await r.json()]);
  const until = async (fn) => {
    for (let i = 0; i < 200; i++) { if (await fn()) return; await new Promise((r) => setTimeout(r, 100)); }
    assert.fail('timeout');
  };
  const idle = () => until(async () => (await get('/ping'))[1].status === 'đang chờ');

  assert.deepEqual(await get('/ping'), [200, { status: 'đang chờ' }]);
  assert.equal((await get('/nope'))[0], 404);

  const htmlUrl = `http://127.0.0.1:${sitePort}/page`;
  assert.deepEqual(await post(JSON.stringify({ url: htmlUrl })), [202, { status: 'đã nhận' }]);
  assert.equal((await post(JSON.stringify({ url: htmlUrl })))[0], 409);
  assert.deepEqual(await get('/ping'), [200, { status: 'đang bận' }]);
  await until(() => received.length === 1);
  assert.equal(received[0].format, 'html');
  assert.equal(received[0].encoding, 'text');
  assert.ok(received[0].content.includes(MARKER));
  await idle();

  // Có Content-Type: gửi nguyên byte, url theo query.
  const pdfUrl = `http://127.0.0.1:${sitePort}/a.pdf`;
  assert.equal((await post(JSON.stringify({ url: pdfUrl })))[0], 202);
  await until(() => received.length === 2);
  assert.equal(received[1].type, 'application/pdf');
  assert.equal(received[1].url, pdfUrl);
  assert.ok(received[1].body.equals(PDF));
  await idle();

  // Không có Content-Type: JSON base64.
  assert.equal((await post(JSON.stringify({ url: `http://127.0.0.1:${sitePort}/b.png` })))[0], 202);
  await until(() => received.length === 3);
  assert.equal(received[2].format, 'other');
  assert.equal(received[2].encoding, 'base64');
  assert.ok(Buffer.from(received[2].content, 'base64').equals(PDF));
  await idle();

  assert.equal((await post('not json'))[0], 400);
  assert.equal((await post(JSON.stringify({ url: 'ftp://x/y' })))[0], 400);
});
