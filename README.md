# Crawler theo kiến trúc dịch vụ nhỏ

Dựa theo sơ đồ cuối của slide "Thiết kế hệ thống thu thập dựa trên kiến trúc dịch vụ nhỏ". Các dịch vụ giao tiếp với nhau bằng HTTP và JSON.

```
Điều khiển :5200 ──/load──▶ Tải về :8800 ──/parse──▶ Đọc nội dung :6600 ──/add──▶ Lưu trữ :2222 ──▶ Mongo
     ▲                                                     │
     └──────────────── /add {urls} (url hust mới) ─────────┘
```

| Thư mục | Dịch vụ |
|---|---|
| `mediator/` | Điều khiển. Giữ hàng đợi url, gửi lần lượt cho từng downloader theo vòng. |
| `downloader/` | Tải về. Dùng Puppeteer + Chrome headless cho trang web, `fetch` cho tài liệu. |
| `reader/` | Đọc nội dung. Bóc tiêu đề, nội dung và liên kết, gửi kết quả sang storage, gửi url hust về mediator. |
| `storage/` | Lưu trữ. Ghi vào các collection `html`/`picture`/`other` và thêm cạnh vào `graph`. |

## Chạy cả hệ trên một máy

Khởi động các dịch vụ:

```bash
docker compose up -d --build
```

Nạp url mầm cho mediator:

```bash
curl -X POST localhost:5200/add -H 'Content-Type: application/json' -d '{"urls":["https://hust.edu.vn/"]}'
```

Nạp cả danh sách `link_all.txt`, mỗi dòng một url. Script nạp theo lô rồi bật crawl. Nạp lại nhiều lần cũng không sao, vì url đã có sẽ bị bỏ qua:

```bash
python3 feed.py
```

Từ đó crawl chạy liên tục và lan ra theo các link hust mà reader tìm được. Crawl chỉ dừng khi tắt service hoặc khi gọi lệnh dừng.

Dừng gửi url (hàng đợi giữ nguyên):

```bash
curl -X POST localhost:5200/stop
```

Chạy tiếp:

```bash
curl -X POST localhost:5200/start
```

Xem trạng thái, tổng số url và số url chờ gửi:

```bash
curl localhost:5200/status
```

Khi khởi động lại, mediator luôn ở trạng thái chạy.

Hệ chạy 4 downloader. Muốn đổi số lượng thì sửa hai chỗ cho khớp nhau: `deploy.replicas` trong `compose.yaml` và danh sách `downloaders` trong `mediator/config/controller.compose.yaml`. Sau đó chạy:

```bash
docker compose up -d --build downloader mediator
```

Mỗi host nhận tối đa 1 url mỗi 2 giây, chỉnh bằng `host_delay_seconds` trong cùng file config đó.

Xem log trong lúc chạy:

```bash
docker compose logs -f downloader reader storage
```

Đếm số bản ghi đã lưu:

```bash
docker compose exec mongo mongosh HUST_2026_IT4863_TEST --quiet --eval 'printjson({html: db.html.countDocuments(), other: db.other.countDocuments(), graph: db.graph.countDocuments()})'
```

Từ máy host có thể kết nối Mongo qua `localhost:27018`. Script schema chỉ chạy khi volume còn mới, nên nếu đổi schema thì chạy `docker compose down -v` trước khi khởi động lại.

## Tìm kiếm (search :7700)

Dịch vụ `search/` đọc Mongo, dựng chỉ mục Lucene (lưu ở volume `search_index`) và trả kết quả qua HTTP. Lần khởi động đầu tiên sẽ dựng chỉ mục, nên chờ log `index: docs=...` rồi mới gọi được.

```bash
docker compose up -d --build search
```

Mở `dashboard.html` trong trình duyệt và chọn tab "Tìm kiếm" (hoặc `dashboard.html#tab=search`). Bấm vào một kết quả để xem nội dung lưu trong Mongo ở khung xem trước (lấy qua `storage` `GET /record`). Đổi địa chỉ service bằng query `?mediator=`, `?storage=`, `?reader=`, `?search=` (ví dụ `dashboard.html?search=http://ip:7700`). Gọi thẳng API:

```bash
curl -G localhost:7700/search --data-urlencode 'q=học phí' --data-urlencode 'type=html' -d profile=D -d fresh=1 -d page=1 -d size=10
curl localhost:7700/ping
```

`type` là `html`, `other` hoặc `picture` (bỏ trống là tất cả). Máy chỉ xếp lại 100 kết quả đầu nên `page * size` tối đa 100. Crawl thêm dữ liệu thì dựng lại chỉ mục, tìm kiếm vẫn chạy trong lúc dựng:

```bash
curl -X POST localhost:7700/reindex
```

| Profile | Cách xếp hạng |
|---|---|
| A | BM25 thuần (tiêu đề, url, nội dung) |
| B | A + bigram, bỏ dấu |
| C | B + chấm lại theo cụm từ, PageRank |
| D | C + lọc trang rác, trang trùng nội dung (mặc định) |

Trang rác (chỉ xét html, từ 50 từ trở lên): "độ lạ" = IDF trung bình các từ của trang, `trung bình log(N / df(w))`. Trang bị chèn rác dùng từ gần như không trang nào khác trong kho có, nên độ lạ cao hẳn. Ngưỡng tự chọn mỗi lần index: làm mượt histogram độ lạ tới khi còn 2 đỉnh, lấy đáy thung lũng giữa chúng (log ghi `ngưỡng độ lạ`, lần gần nhất 4,85). Trang trùng: cùng SHA-1 nội dung, giữ bản có PageRank cao nhất.

Đánh giá: sinh danh sách ứng viên, gán nhãn tay, rồi chấm các profile.

```bash
docker compose exec search java -cp search-service.jar vn.hust.search.Eval --pool eval/queries.tsv eval/qrels.tsv
# gán nhãn cột grade (0/1/2) trong search/eval/qrels.tsv, rồi:
docker compose exec search java -cp search-service.jar vn.hust.search.Eval eval/queries.tsv eval/qrels.tsv
```

Chưa làm:

- Anchor text (bảng graph không còn lưu).
- Tách từ tiếng Việt bằng pyvi.
- Gợi ý khi gõ, sửa chính tả.
- Cập nhật chỉ mục từng phần (hiện dựng lại toàn bộ).
