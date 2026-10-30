# PageRank

Mã: `LinkGraph.pageRank`.

## Ý tưởng

Trang được nhiều trang quan trọng trỏ tới thì quan trọng. Đây là xác suất dừng của người duyệt web ngẫu nhiên: ở mỗi bước, với xác suất 0,85 người đó bấm một liên kết ngẫu nhiên trên trang hiện tại, với xác suất 0,15 nhảy đến một trang bất kỳ.

```
PR(v) = (1 − d)/N + d · ( Σ_{u→v} PR(u)/outdeg(u)  +  dangling/N ),   d = 0,85
```

`dangling` là tổng PageRank của các trang không có liên kết ra; chia đều cho mọi trang để điểm không bị mất.

## Đồ thị

Đọc từ collection `graph`, mỗi bản ghi một cạnh `source → destinations`. Khi nạp:

- Bỏ cạnh thiếu đầu mút và cạnh tự trỏ (`source == destinations`).
- Bỏ cạnh trùng (khoá `(a << 32) | b` trên chỉ số nút).
- Mỗi url là một nút, đánh chỉ số theo thứ tự gặp. Các cạnh lưu trong mảng `int`.

## Lặp

Khởi tạo `1/N` cho mọi nút, lặp tối đa 50 vòng, dừng sớm khi tổng chênh lệch tuyệt đối giữa hai vòng nhỏ hơn `1e-6`. Mỗi vòng:

1. Cộng `PR` của các nút cụt (`outdeg = 0`) vào `dangling`.
2. Mọi nút nhận `(0,15 + 0,85 · dangling) / N`.
3. Mỗi cạnh `u → v` cộng `0,85 · PR(u) / outdeg(u)` cho `v`.

Tổng PageRank luôn bằng 1. Độ phức tạp `O(vòng × (N + E))`, chạy trong bộ nhớ.

## Đưa vào điểm tìm kiếm

Lúc index: `sig:pagerank = max(PR × N, 1e-4)` (xem [01](01-indexing.md)). Nhân với `N` để trung bình bằng 1. Lúc chấm lại (profile C, D): cộng `2 · s/(s + pivot)` với `s` là giá trị tín hiệu ([02](02-ranking.md)).

PageRank còn được dùng để **chọn bản giữ lại** trong nhóm trang trùng nội dung ([04](04-junk-and-duplicates.md)).

## Giới hạn

- Đồ thị chỉ gồm các trang đã crawl và cạnh reader tìm được; trang chưa crawl nhưng được trỏ tới vẫn là nút (có PageRank) nhưng không có tài liệu để xếp hạng.
- Chưa dùng anchor text (bảng `graph` không còn lưu text liên kết).
- Mọi liên kết có trọng số như nhau; liên kết điều hướng chung (menu, chân trang) làm tăng PageRank của các trang gốc.
