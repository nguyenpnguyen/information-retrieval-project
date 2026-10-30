# Máy tìm kiếm cho hust.edu.vn

Đồ án môn Tìm kiếm thông tin (IT4863). Hệ gồm hai phần chạy chung một Mongo:

1. **Crawler** theo kiến trúc dịch vụ nhỏ: thu thập trang web, tài liệu và ảnh của `*.hust.edu.vn`, lưu vào MongoDB cùng đồ thị liên kết.
2. **Search**: dựng chỉ mục Lucene từ Mongo và trả kết quả tìm kiếm qua HTTP, xếp hạng bằng BM25 nhiều trường, PageRank, lọc trang rác và trang trùng.

Các dịch vụ giao tiếp bằng HTTP và JSON.

```
Điều khiển :5200 ──/load──▶ Tải về :8800 ──/parse──▶ Đọc nội dung :6600 ──/add──▶ Lưu trữ :2222 ──▶ Mongo :27018
     ▲                                                     │                                          │
     └──────────────── /add {urls} (url hust mới) ─────────┘                                          ▼
                                                                                  Tìm kiếm :7700 (chỉ mục Lucene) ◀── dashboard.html
```

## Cấu trúc thư mục

| Thư mục / file | Vai trò | Công nghệ | Cổng |
|---|---|---|---|
| `mediator/` | Điều khiển. Giữ hàng đợi url (SQLite), gửi lần lượt cho các downloader theo vòng, giới hạn tốc độ theo host, crawl lại định kỳ. | Python 3.13, uv | 5200 |
| `downloader/` | Tải về. Puppeteer + Chromium headless cho trang web, `fetch` cho tài liệu và ảnh. Mỗi lúc tải một url. | Node 24 | 8800 |
| `reader/` | Đọc nội dung. Bóc tiêu đề, nội dung, liên kết; gửi kết quả sang storage, gửi url hust mới về mediator. | Java 21 | 6600 |
| `storage/` | Lưu trữ. Ghi vào collection `html`/`picture`/`other`, thêm cạnh vào `graph`. | Java 21, Spring Boot | 2222 |
| `search/` | Tìm kiếm. Dựng chỉ mục Lucene từ Mongo, API `/search`. | Java 21, Lucene 10 | 7700 |
| `mongodb/` | Ghi chú kết nối Mongo dùng chung. Schema nằm ở `storage/document/ddl_create_document.js`. | MongoDB 7 | 27018 (host) |
| `docs/` | Mô tả thuật toán tìm kiếm. | | |
| `compose.yaml` | Chạy toàn bộ hệ trên một máy. Mỗi dịch vụ chỉ có `Dockerfile` riêng. | | |
| `dashboard.html` | Trang theo dõi crawl và giao diện tìm kiếm (mở thẳng bằng trình duyệt). | | |
| `feed.py`, `link_all.txt` | Nạp danh sách url mầm (khoảng 100 nghìn dòng) cho mediator. | | |
| `search/eval/` | Truy vấn và nhãn (qrels) để đánh giá các profile xếp hạng. | | |

Dữ liệu lưu ở bốn collection của database `HUST_2026_IT4863_TEST`:

| Collection | Nội dung |
|---|---|
| `html` | Trang web: `url`, `format`, `title`, `content` (văn bản đã bóc), `createdAt`, `updatedAt` |
| `other` | Tài liệu (pdf, docx...): như trên, có thể kèm `base64` |
| `picture` | Ảnh: như trên, có `base64` |
| `graph` | Một cạnh liên kết mỗi bản ghi: `source`, `destinations` |

## Chạy nhanh

Yêu cầu Docker và Docker Compose.

```bash
docker compose up -d --build
```

Nạp url mầm, crawl sẽ tự lan ra theo các liên kết hust mà reader tìm được:

```bash
curl -X POST localhost:5200/add -H 'Content-Type: application/json' -d '{"urls":["https://hust.edu.vn/"]}'
```

Hoặc nạp cả `link_all.txt` (mỗi dòng một url, nạp theo lô, nạp lại nhiều lần không sao vì url đã có bị bỏ qua):

```bash
python3 feed.py
python3 feed.py ds.txt --mediator http://10.177.29.102:5200 --batch 2000
```

Sau khi đã có dữ liệu, tìm kiếm:

```bash
curl -G localhost:7700/search --data-urlencode 'q=học phí' -d profile=D -d size=5
```

Hoặc mở `dashboard.html` trong trình duyệt (tab "Tìm kiếm": `dashboard.html#tab=search`). Đổi địa chỉ dịch vụ bằng query `?mediator=`, `?storage=`, `?reader=`, `?search=`, ví dụ `dashboard.html?search=http://ip:7700`.

## Ví dụ sử dụng

### Mediator (:5200)

```bash
curl localhost:5200/status                  # trạng thái, tổng số url, số url chờ gửi
curl -X POST localhost:5200/stop            # dừng gửi url (hàng đợi giữ nguyên)
curl -X POST localhost:5200/start           # chạy tiếp (khởi động lại luôn ở trạng thái chạy)
```

Cấu hình ở `mediator/config/controller.compose.yaml`: danh sách downloader, `host_delay_seconds` (mỗi host tối đa 1 url mỗi 2 giây), `recrawl_days` (trang hub 1 ngày, trang thường 7 ngày, tài liệu 30 ngày).

Hệ chạy 4 downloader. Muốn đổi số lượng thì sửa hai chỗ cho khớp nhau: `deploy.replicas` trong `compose.yaml` và danh sách `downloaders` trong file config trên, rồi:

```bash
docker compose up -d --build downloader mediator
```

Xuất các url mediator đã nhận và đã gửi ra `mediator/out/`:

```bash
cd mediator && ./export_urls.sh
```

### Downloader (:8800)

```bash
curl http://localhost:8800/ping                                                  # {"status":"đang chờ"} hoặc "đang bận"
curl -i -X POST http://localhost:8800/load -d '{"url": "https://hust.edu.vn/"}'  # 202, tải ở nền rồi gửi sang reader
```

Chi tiết biến môi trường ở `downloader/README.md`.

### Reader (:6600)

```bash
curl -X POST localhost:6600/parse -H 'Content-Type: application/json' \
  -d '{"url":"https://hust.edu.vn/a","format":"html","encoding":"text","content":"<h1>Tiêu đề</h1><p>Xem <a href=b.html>bài khác</a></p>"}'
```

Ví dụ gửi pdf (base64 hoặc byte thô) ở `reader/README.md`. Đặt `DEMO_FETCH=1` để bật `POST /fetch` cho `reader/demo.html`.

### Storage (:2222)

```bash
curl -X POST localhost:2222/add -H 'Content-Type: application/json' -d '{
  "url": "https://hust.edu.vn/tin-tuc1", "format": "html", "title": "Tin tức 1",
  "content": "Cơ hội thực tập có lương tại Nhật cho sinh viên HUST.",
  "out-links": [{"url": "https://hust.edu.vn/tin-tuc2", "text": "Thông tin tuyển dụng"}]
}'
curl localhost:2222/stats                                                         # số bản ghi từng collection, dung lượng, bản ghi mới nhất
curl -G localhost:2222/record --data-urlencode 'collection=html' --data-urlencode 'url=https://hust.edu.vn/tin-tuc1'
```

### Mongo

```bash
docker compose exec mongo mongosh HUST_2026_IT4863_TEST --quiet --eval 'printjson({html: db.html.countDocuments(), other: db.other.countDocuments(), graph: db.graph.countDocuments()})'
```

Từ máy host kết nối `mongodb://localhost:27018`. Script schema chỉ chạy khi volume còn mới; đổi schema thì `docker compose down -v` trước khi khởi động lại.

### Tìm kiếm (:7700)

Lần khởi động đầu tiên, dịch vụ dựng chỉ mục từ Mongo (lưu ở volume `search_index`); chờ log `index: docs=...` rồi mới gọi được.

```bash
curl -G localhost:7700/search --data-urlencode 'q=học phí' -d type=html -d profile=D -d fresh=1 -d page=1 -d size=10
curl -G localhost:7700/search --data-urlencode 'q=lich thi' -d profile=B     # gõ không dấu vẫn khớp
curl -G localhost:7700/search --data-urlencode 'q=tuyển sinh' --data-urlencode 'host=soict.hust.edu.vn'
curl localhost:7700/ping                                                      # số tài liệu, lần index cuối, thống kê
curl -X POST localhost:7700/reindex                                           # dựng lại chỉ mục ở nền (202; đang dựng thì 409)
```

| Tham số | Ý nghĩa |
|---|---|
| `q` | Truy vấn |
| `type` | `html`, `other` hoặc `picture`; bỏ trống là tất cả |
| `host` | Chỉ lấy kết quả của host này, ví dụ `soict.hust.edu.vn` |
| `profile` | `A`–`D`, mặc định `D` (bảng dưới) |
| `fresh` | `1` thì cộng điểm cho trang mới cập nhật |
| `page`, `size` | Phân trang; `size` tối đa 50. Máy chỉ xếp hạng 100 kết quả đầu nên `page * size` tối đa 100 |

Kết quả:

```json
{"q": "học phí", "profile": "D", "total": 412, "tookMs": 18, "page": 1,
 "hits": [{"url": "https://hust.edu.vn/...", "title": "HỌC PHÍ", "collection": "html", "format": "html",
           "host": "hust.edu.vn", "score": 9.31, "snippet": "… mức <b>học</b> <b>phí</b> …", "updatedAt": 1761200000000}]}
```

| Profile | Cách xếp hạng |
|---|---|
| A | BM25 thuần (tiêu đề, url, nội dung) |
| B | A + bigram, bỏ dấu |
| C | B + chấm lại theo cụm từ, PageRank |
| D | C + lọc trang rác, trang trùng nội dung (mặc định) |

Thuật toán chi tiết ở [docs/](docs/README.md).

### Đánh giá chất lượng xếp hạng

Sinh danh sách ứng viên từ top 10 của cả 4 profile, gán nhãn tay cột `grade` (0/1/2) trong `search/eval/qrels.tsv`, rồi chấm P@10 và nDCG@10:

```bash
docker compose exec search java -cp search-service.jar vn.hust.search.Eval --pool eval/queries.tsv eval/qrels.tsv
docker compose exec search java -cp search-service.jar vn.hust.search.Eval --detail eval/queries.tsv eval/qrels.tsv
```

## Vận hành

```bash
docker compose logs -f downloader reader storage search     # xem log
docker compose down                                         # dừng, giữ dữ liệu
docker compose down -v                                      # dừng và xoá cả volume (Mongo, chỉ mục, hàng đợi)
```

Mỗi dịch vụ có thể chạy riêng không cần Docker; xem README trong từng thư mục (`downloader/`, `reader/`, `mediator/`).

## Chưa làm

- Anchor text (bảng `graph` không còn lưu).
- Tách từ tiếng Việt bằng pyvi (hiện dùng bigram thay thế).
- Gợi ý khi gõ, sửa chính tả.
- Cập nhật chỉ mục từng phần (hiện dựng lại toàn bộ).
