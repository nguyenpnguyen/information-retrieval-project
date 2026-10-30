# downloader (N5 · Tải về)

Nhận url từ mediator qua `POST /load`, tải ở nền rồi gửi thẳng sang reader (`POST /parse`). Mỗi lúc chỉ tải một url.

- Trang web: Puppeteer (Chrome dùng chung, mở một tab mỗi lần tải) -> `format: "html"`, `encoding: "text"`.
- Tài liệu/ảnh (pdf, docx, jpg...; hoặc trang trả về không phải HTML): `fetch` -> `format: "other"`, `encoding: "base64"`.
- Lỗi (hết giờ, HTTP >= 400, reader chết) chỉ ghi log, không gửi gì, service quay lại "đang chờ".

| Endpoint | Trả lời |
|---|---|
| `GET /ping` | `{"status":"đang chờ"}` hoặc `{"status":"đang bận"}` |
| `POST /load` `{"url":"https://..."}` | `202 {"status":"đã nhận"}`; đang bận `409`; body sai `400` |

## Biến môi trường

| Biến | Mặc định |
|---|---|
| `PORT` | `8800` |
| `READER_URL` | `http://localhost:6600/parse` |
| `NAV_TIMEOUT_MS` | `30000` |
| `MAX_BYTES` | `52428800` (50 MB) |

## Chạy

```bash
npm install && npm test
READER_URL=http://<ip-N1>:6600/parse npm start

docker build -t downloader .
docker run --rm -p 8800:8800 -e READER_URL=http://<ip-N1>:6600/parse downloader
```

## Tự kiểm tra

```bash
curl http://localhost:8800/ping
# {"status":"đang chờ"}
curl -i -X POST http://localhost:8800/load -d '{"url": "https://hust.edu.vn/"}'
# HTTP/1.1 202 Accepted ...      (trả về ngay)
curl http://localhost:8800/ping
# {"status":"đang bận"}          (trong lúc đang tải)
```
