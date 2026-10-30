# Lọc trang rác và trang trùng

Mã: `Junk`, phần đầu của `IndexBuilder.build`. Chỉ có tác dụng ở profile D; các trang bị đánh dấu vẫn nằm trong chỉ mục (trường `junk`).

## Trang rác: "độ lạ"

Một số trang html của hust bị chèn rác (từ khoá quảng cáo, văn bản lạ) vào nội dung. Từ vựng của chúng gần như không trùng với phần còn lại của kho, trong khi trang thật dùng từ vựng chung ("đào tạo", "sinh viên", "thông báo"...).

### Độ đo

Với trang html có ít nhất 50 từ:

```
words(p)   = các chuỗi chữ cái \p{L}+ của tiêu đề + nội dung, chữ thường, NFC
df(w)      = số trang html đủ dài chứa từ w
N          = số trang html đủ dài
độ_lạ(p)   = trung bình trên mọi từ w của p (kể cả lặp) của  log( N / df(w) )
```

Đây là IDF trung bình của trang. Từ phổ biến cho giá trị gần 0, từ hiếm (`df = 1`) cho `log N`. Trang dưới 50 từ không đủ để ước lượng nên bỏ qua (không bao giờ bị coi là rác). Trang không phải html (`other`, `picture`) không xét.

### Chọn ngưỡng tự động

Không đặt ngưỡng tay. Histogram độ lạ của kho có hai cụm: cụm lớn là trang thật, cụm nhỏ nằm cao hơn là trang rác. Ngưỡng là đáy của thung lũng giữa hai cụm (cách làm của Prewitt–Mendelsohn):

1. Chia độ lạ thành các bin rộng 0,1, đếm số trang mỗi bin → `raw`.
2. Làm mượt bằng trung bình ba bin kề nhau (`[1,1,1]/3`), lặp cho đến khi histogram chỉ còn **đúng 2 đỉnh** (tối đa 200 vòng). Đỉnh là bin cao hơn bên trái và không thấp hơn bên phải; dải bằng nhau tính một đỉnh.
3. Khi còn 2 đỉnh `a < b`:
   - Nếu đỉnh phải cao hơn đỉnh trái thì trả `+∞` (cụm bên phải đông hơn thì không phải rác).
   - Tìm bin `v` thấp nhất trên `raw` giữa `a` và `b`. Đáy đo trên histogram gốc vì làm mượt nhiều vòng cũng lấp mất thung lũng.
   - Nếu `raw[v] ≤ 0,5 · h[b]` (thung lũng đủ sâu) thì ngưỡng = `min + (v + 0,5) · 0,1`. Ngược lại là nhiễu, làm mượt tiếp.
4. Chỉ còn 0 hoặc 1 đỉnh, hoặc hết 200 vòng, thì ngưỡng `+∞`: không trang nào bị gắn cờ.

Trang có độ lạ vượt ngưỡng được gắn `junk:spam`. Ngưỡng của lần dựng gần nhất có trong log (`ngưỡng độ lạ …`) và trong `GET /ping` (`stats.oddityThreshold`).

Giới hạn (ghi trong mã): nếu rác quá ít để thành một đỉnh riêng thì không bị phát hiện; cần thì đổi sang ngưỡng cố định theo phân vị.

## Trang trùng nội dung

Trang mirror, bản in, bản đổi ngôn ngữ giống hệt... làm kết quả lặp.

### Khoá nội dung

```
key(p) = SHA-1( NFC(content), chữ thường, mọi dãy khoảng trắng thành 1 dấu cách, trim )
```

Nội dung dưới 200 ký tự thì không có khoá (quá ngắn để coi là trùng). Khoá tính cho cả ba collection.

### Chọn bản giữ lại

Với mỗi khoá có nhiều url, giữ **một** url:

1. PageRank cao nhất ([03](03-pagerank.md)).
2. Hoà thì url ngắn hơn.
3. Trang đã bị đánh dấu rác không được làm bản giữ lại.

Các url còn lại của nhóm được gắn `junk:dup`. Rác được xét trước, nên một trang vừa rác vừa trùng chỉ tính là `spam`.

## Áp dụng lúc truy vấn

Profile D thêm hai mệnh đề `MUST_NOT` (`junk:spam`, `junk:dup`). Profile A–C không lọc, nên có thể so sánh hiệu quả bằng [đánh giá offline](05-evaluation.md).

Số lượng thống kê: `spam`, `dup` trong log dựng chỉ mục và `GET /ping`.
