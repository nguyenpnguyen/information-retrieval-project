# Truy vấn và xếp hạng

Mã: `Searcher`, `Profile`, `Analyzers.similarity`.

## Hai giai đoạn

```
q ─▶ NFC, trim ─▶ dựng truy vấn BM25 nhiều trường ─▶ top 100 ─▶ (B+) chấm lại ─▶ cắt trang ─▶ snippet
```

1. **Truy hồi**: một `BooleanQuery` gồm nhiều mệnh đề `SHOULD` trên các trường, mỗi mệnh đề có trọng số. Lấy 100 tài liệu điểm cao nhất.
2. **Chấm lại** (profile C, D): cộng thêm điểm cụm từ, PageRank, độ mới cho đúng 100 tài liệu đó.

`total` trong kết quả là số tài liệu khớp thật (`IndexSearcher.count`), không phải chỉ 100. Nhưng chỉ 100 đầu được xếp hạng, nên `page × size` tối đa 100.

## Dựng truy vấn truy hồi

Với mỗi trường, `QueryBuilder` phân tích truy vấn bằng analyzer của trường đó và tạo OR của các token (`Occur.SHOULD`): tài liệu chỉ cần khớp một token. Điểm của tài liệu là tổng điểm BM25 các trường nhân trọng số.

| Trường | Trọng số | Từ profile |
|---|---|---|
| `url_tokens` | 1,5 | A |
| `title` | 3 | A |
| `content` | 1 | A |
| `title_bi` | 3 | B |
| `content_bi` | 1,5 | B |
| `title_ascii` | 2 | B |
| `content_ascii` | 0,7 | B |

Tiêu đề nặng hơn nội dung 3 lần; bigram trong nội dung nặng hơn unigram 1,5 lần vì khớp cụm hai âm tiết là bằng chứng mạnh hơn khớp rời rạc; bản bỏ dấu nhẹ hơn bản có dấu để trang đúng dấu xếp trên trang chỉ khớp sau khi bỏ dấu.

### Truy vấn không dấu

Từ profile B, nếu truy vấn không chứa dấu (`q` bằng `q` sau khi bỏ dấu, ví dụ "lich thi") thì chỉ hỏi trường bỏ dấu: `title_ascii` (trọng số 3) và `content_ascii` (trọng số 1), cộng `url_tokens`. Làm vậy để trang có dấu ("Lịch thi") và trang chỉ có tên file không dấu được ngang hàng, không bị trường có dấu ưu ái. Profile A không có nhánh này nên truy vấn không dấu không khớp trang có dấu.

### Bộ lọc

Chạy ở dạng `FILTER` (không tham gia chấm điểm):

- `type` → `collection`; `host` → `host` (chữ thường).
- Profile D: `MUST_NOT` các tài liệu có `junk:spam` hoặc `junk:dup` ([04](04-junk-and-duplicates.md)).

## Chấm lại

`QueryRescorer` chấm lại 100 tài liệu bằng truy vấn phụ, điểm mới:

```
điểm_mới = điểm_BM25 + (khớp truy vấn phụ ? điểm_truy_vấn_phụ : 0)
```

Truy vấn phụ là OR của:

| Mệnh đề | Trọng số | Ghi chú |
|---|---|---|
| Cụm từ trong `title`, độ lệch (slop) 2 | 2 | Các token của truy vấn xuất hiện gần nhau, đúng thứ tự (cho phép chèn tối đa 2 vị trí). Bỏ qua nếu truy vấn chỉ có một token. |
| Cụm từ trong `content`, slop 2 | 1 | Như trên. |
| PageRank, `FeatureField` dạng bão hoà | 2 (`PR_BOOST`) | Mọi tài liệu có tín hiệu này. |
| Độ mới, dạng bão hoà | 2 | Chỉ khi `fresh=1`. |

Dạng bão hoà của `FeatureField` cho điểm `w · s / (s + pivot)` với `s` là giá trị tín hiệu. Hàm tăng nhanh ở giá trị thấp rồi phẳng dần, nên một trang có PageRank gấp 1000 lần trung bình không áp đảo hoàn toàn điểm văn bản. `pivot` do Lucene ước lượng từ phân bố giá trị trong chỉ mục.

## Profile

| Profile | Truy hồi | Chấm lại | Lọc rác/trùng |
|---|---|---|---|
| A | `url_tokens`, `title`, `content` | không | không |
| B | A + bigram + bỏ dấu, nhánh truy vấn không dấu | không | không |
| C | như B | cụm từ + PageRank (+ độ mới) | không |
| D (mặc định) | như B | như C | có |

Các profile cộng dồn từng tầng để đo đóng góp của mỗi kỹ thuật bằng [đánh giá offline](05-evaluation.md).

## Snippet

Lấy tối đa 2 đoạn từ `content` bằng `UnifiedHighlighter`, bọc từ khớp bằng `<b>…</b>`, nối đoạn bằng "… ". Nếu không có đoạn nào (truy vấn không dấu chỉ khớp `content_ascii`) thì highlight trên `content_ascii`: offset của bản bỏ dấu trùng với văn bản gốc nên dùng lại được trên nội dung gốc. Vẫn không có thì lấy 200 ký tự đầu của nội dung, đã escape HTML.

## Ví dụ

Truy vấn `học phí` với profile D:

1. Truy hồi: `url_tokens:(học phí)^1.5`, `title:(học phí)^3`, `content:(học phí)`, `title_bi:"học phí"^3`, `content_bi:"học phí"^1.5`, `title_ascii:(hoc phi)^2`, `content_ascii:(hoc phi)^0.7`, lọc bỏ rác/trùng. Top 100 theo tổng điểm.
2. Chấm lại: trang có tiêu đề chứa đúng "học phí" cộng thêm điểm cụm từ ×2; trang được nhiều trang khác trỏ tới cộng thêm điểm PageRank.
3. Trả 10 trang đầu kèm snippet.
