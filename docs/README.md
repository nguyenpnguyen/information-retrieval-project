# Thuật toán tìm kiếm

Tài liệu mô tả dịch vụ `search/` (Lucene 10, Java 21). Không mô tả phần crawler.

## Tổng quan

```
Mongo (html, other, picture, graph)
        │
        ▼  IndexBuilder.build  (lúc khởi động nếu chưa có chỉ mục, hoặc POST /reindex)
  ┌───────────────────────────────────────────────────────────────┐
  │ 1. PageRank trên graph                          → 03-pagerank │
  │ 2. Độ lạ + ngưỡng → đánh dấu trang rác (spam)   → 04-junk     │
  │ 3. SHA-1 nội dung → đánh dấu trang trùng (dup)  → 04-junk     │
  │ 4. Ghi tài liệu Lucene, nhiều trường            → 01-indexing │
  └───────────────────────────────────────────────────────────────┘
        │
        ▼  Searcher.search  (GET /search)
  ┌───────────────────────────────────────────────────────────────┐
  │ Giai đoạn 1: BM25 nhiều trường, lấy top 100     → 02-ranking  │
  │ Giai đoạn 2: chấm lại bằng cụm từ + PageRank    → 02-ranking  │
  │ Cắt trang, tạo snippet                                        │
  └───────────────────────────────────────────────────────────────┘
        │
        ▼  Eval (P@10, nDCG@10 trên qrels gán tay)      → 05-evaluation
```

| File | Nội dung |
|---|---|
| [01-indexing.md](01-indexing.md) | Phân tích văn bản tiếng Việt, các trường của chỉ mục |
| [02-ranking.md](02-ranking.md) | Dựng truy vấn, BM25, chấm lại, profile A–D, snippet |
| [03-pagerank.md](03-pagerank.md) | PageRank trên đồ thị liên kết và cách đưa vào điểm |
| [04-junk-and-duplicates.md](04-junk-and-duplicates.md) | Lọc trang rác bằng "độ lạ", khử trang trùng |
| [05-evaluation.md](05-evaluation.md) | Pooling, P@10, nDCG@10 |

Mã nguồn tương ứng: `search/src/main/java/vn/hust/search/`.

| Lớp | Việc |
|---|---|
| `Analyzers` | Các bộ phân tích văn bản và similarity theo trường |
| `IndexBuilder` | Quy trình dựng chỉ mục |
| `LinkGraph` | PageRank |
| `Junk` | Độ lạ, ngưỡng thung lũng, khoá nội dung |
| `Searcher` | Truy vấn hai giai đoạn, snippet |
| `Profile` | Bốn cấu hình xếp hạng A–D |
| `Eval` | Đánh giá offline |
| `Main` | HTTP: `/search`, `/reindex`, `/ping` |

## Các quyết định chính

| Quyết định | Lý do |
|---|---|
| Không tách từ tiếng Việt, dùng unigram theo âm tiết cộng bigram | Không cần thêm thư viện; bigram bắt được phần lớn từ ghép như "bách khoa", "học phí". |
| Giữ cả bản có dấu và bản bỏ dấu của mỗi trường | Người dùng hay gõ không dấu; trang cũng hay có url và tên file không dấu. |
| Hai giai đoạn: BM25 rẻ cho 100 kết quả, chấm lại đắt trên 100 kết quả đó | Truy vấn cụm từ và tín hiệu ngoài văn bản chỉ cần tính cho số ít tài liệu. |
| Trang rác và trang trùng vẫn được index, chỉ bị loại ở lúc truy vấn (profile D) | Có thể so sánh có/không lọc bằng cùng một chỉ mục. |
| Ngưỡng rác tự chọn mỗi lần index, không đặt tay | Ngưỡng phụ thuộc kho dữ liệu, kho đổi thì ngưỡng đổi theo. |
