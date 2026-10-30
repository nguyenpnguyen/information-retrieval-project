# Dựng chỉ mục

Mã: `IndexBuilder`, `Analyzers`, `MongoSource`.

## Nguồn dữ liệu

`MongoSource` đọc ba collection `html`, `other`, `picture` (chỉ các trường `url`, `format`, `title`, `content`, `updatedAt`, không bao giờ lấy `base64`) và collection `graph` (`source`, `destinations`). Mỗi tài liệu Lucene tương ứng một bản ghi.

## Chuẩn hoá và phân tích văn bản

Mọi chuỗi qua `Analyzers.nfc` (Unicode NFC) trước khi index và trước khi truy vấn. Tiếng Việt có hai cách viết cùng một chữ có dấu (ký tự dựng sẵn và ký tự gốc cộng dấu rời); không chuẩn hoá thì "ệ" gõ ở máy này không khớp "ệ" lưu ở máy khác.

Có bốn analyzer, đều dựa trên `StandardTokenizer` (tách theo UAX #29, mỗi âm tiết tiếng Việt là một token):

| Analyzer | Chuỗi xử lý | Ví dụ "Bách Khoa Hà Nội" |
|---|---|---|
| `vi` | tokenizer → chữ thường | `bách`, `khoa`, `hà`, `nội` |
| `viBi` | `vi` → bigram, không giữ unigram | `bách khoa`, `khoa hà`, `hà nội` |
| `viAscii` | `vi` → bỏ dấu (`ASCIIFoldingFilter`) | `bach`, `khoa`, `ha`, `noi` |
| `urlTokens` | tách theo mọi ký tự không phải chữ/số → chữ thường → bỏ dấu | url `…/hoc-phi-654580.html` → `hoc`, `phi`, `654580`, `html` |

`ASCIIFoldingFilter` đổi cả `đ` thành `d`.

Không có bước tách từ: "học phí" được biểu diễn bằng hai unigram `học`, `phí` và một bigram `học phí`. Bigram thay cho từ ghép, nên cụm "phí học" ngược thứ tự không khớp bigram.

## Các trường của tài liệu

| Trường | Loại | Analyzer | Lưu | Mục đích |
|---|---|---|---|---|
| `url`, `host`, `collection`, `format` | StringField (nguyên văn) | không | có | Định danh, lọc theo `host`/`type` |
| `title` | TextField | `vi` | có | Khớp tiêu đề |
| `content` | Text, có vị trí và offset | `vi` | có | Khớp nội dung, tạo snippet |
| `title_bi`, `content_bi` | TextField | `viBi` | không | Khớp cụm hai âm tiết |
| `title_ascii`, `content_ascii` | TextField | `viAscii` | không | Khớp truy vấn không dấu |
| `url_tokens` | TextField | `urlTokens` | không | Khớp từ trong url |
| `sig:pagerank` | FeatureField | | không | Tín hiệu liên kết (xem [03](03-pagerank.md)) |
| `sig:recency` | FeatureField | | không | Tín hiệu độ mới |
| `updatedAt` | StoredField | | có | Trả về cho client |
| `junk` | StringField | | có | `spam` hoặc `dup`, chỉ có khi bị đánh dấu |

`content` được index với `DOCS_AND_FREQS_AND_POSITIONS_AND_OFFSETS`. Vị trí cần cho truy vấn cụm từ ở giai đoạn chấm lại, offset cần cho `UnifiedHighlighter`.

## Điểm theo trường (similarity)

Mọi trường dùng BM25 với `k1 = 1.2`. Tham số độ dài `b` khác nhau theo trường:

| Trường | k1 | b |
|---|---|---|
| `title*`, `url_tokens` | 1.2 | 0.75 |
| `content*` (kể cả `content_bi`, `content_ascii`) | 1.2 | 0.5 |

`b` nhỏ hơn ở nội dung để tài liệu dài như PDF không bị phạt quá nặng khi chuẩn hoá theo độ dài.

## Tín hiệu ngoài văn bản

`sig:pagerank` = `max(pagerank × n, 1e-4)`, với `n` là số nút của đồ thị. Nhân với `n` đưa trung bình về khoảng 1 để không phụ thuộc kích thước đồ thị. Tài liệu không có trong đồ thị nhận giá trị sàn `1e-4`.

`sig:recency` = `1 / (1 + tuổi_ngày / 30)`, với tuổi tính từ `updatedAt` đến lúc index. Giá trị 1 là vừa cập nhật, 0,5 là 30 ngày, giảm dần về 0. Không có `updatedAt` thì nhận giá trị sàn `1e-4`. Tín hiệu này tính tại lúc index, nên chỉ mục càng cũ thì "độ mới" càng lệch; reindex để làm tươi.

## Quy trình `IndexBuilder.build`

Dựng lại toàn bộ (`OpenMode.CREATE`), duyệt Mongo nhiều lượt vì không giữ nội dung trong bộ nhớ:

1. Đếm cạnh, tính PageRank ([03](03-pagerank.md)).
2. Lượt 1 trên các trang html đủ dài: đếm tần suất tài liệu `df` của từng từ.
3. Lượt 2: tính độ lạ từng trang html, SHA-1 nội dung mọi tài liệu; chọn ngưỡng rác; chọn bản giữ lại cho mỗi nhóm trùng ([04](04-junk-and-duplicates.md)). Chỉ giữ url và số, không giữ nội dung.
4. Lượt 3: ghi từng tài liệu, kèm cờ `junk`.

Chỉ mục nằm ở `INDEX_DIR` (`/index`, volume `search_index`). `POST /reindex` dựng ở luồng nền rồi `SearcherManager.maybeRefresh()`, nên tìm kiếm vẫn chạy trong lúc dựng. Hiện tại `IndexWriter` mở lại ở chế độ tạo mới nên chưa có cập nhật từng phần.

Log sau mỗi lần dựng:

```
index: docs=… spam=… (ngưỡng độ lạ …) dup=… edges=… pagerank=…% trong … s
```

`pagerank=…%` là tỉ lệ tài liệu có mặt trong đồ thị liên kết (nếu thấp, nhiều trang chưa được crawl đủ link).
