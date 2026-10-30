# Đánh giá

Mã: `Eval`. Dữ liệu: `search/eval/queries.tsv`, `search/eval/qrels.tsv`.

## Mục tiêu

So sánh bốn profile A–D ([02](02-ranking.md)) trên cùng một tập truy vấn để biết bigram/bỏ dấu, chấm lại và lọc rác có thực sự cải thiện thứ hạng.

## Dữ liệu

`queries.tsv` (có dòng tiêu đề), 25 truy vấn, trộn có dấu, không dấu, tên đơn vị:

```
qid   query
q01   học phí 2025
q02   lich thi
q03   quy chế đào tạo
q04   soict tuyển sinh
```

`qrels.tsv`: `qid  grade  url  title`. `grade` do người gán:

| Grade | Ý nghĩa |
|---|---|
| 0 | không liên quan |
| 1 | liên quan một phần |
| 2 | liên quan hoàn toàn |

Dòng để trống `grade` là chưa gán nhãn.

## Quy trình

**1. Pooling.** Chạy cả 4 profile cho mỗi truy vấn, gộp top 10 của từng profile vào `qrels.tsv`:

```bash
docker compose exec search java -cp search-service.jar vn.hust.search.Eval --pool eval/queries.tsv eval/qrels.tsv
```

Nhãn cũ giữ nguyên; chỉ thêm cặp `(qid, url)` mới, nên chạy lại sau khi sửa thuật toán không mất công gán nhãn. Lệnh in số dòng mới và số dòng còn chưa gán nhãn. Pooling tránh thiên vị về một hệ: tài liệu nào bất kỳ profile nào xếp cao đều được người gán nhãn nhìn thấy.

**2. Gán nhãn tay** cột `grade` (0/1/2).

**3. Chấm điểm:**

```bash
docker compose exec search java -cp search-service.jar vn.hust.search.Eval eval/queries.tsv eval/qrels.tsv
docker compose exec search java -cp search-service.jar vn.hust.search.Eval --detail eval/queries.tsv eval/qrels.tsv
```

`--detail` in thêm điểm từng truy vấn. Chạy trên cùng `INDEX_DIR` của dịch vụ.

## Độ đo

Mỗi profile chạy mỗi truy vấn không lọc `type`/`host`, `fresh = 0`, lấy top `K = 10`. Kết quả chưa có nhãn tính là grade 0 (và được đếm ở cột "chưa gán nhãn" để biết số liệu còn thiếu nhãn đến đâu).

**P@10** = (số kết quả top 10 có `grade ≥ 1`) / 10. Đo độ chính xác, không phân biệt thứ tự.

**nDCG@10**: độ lợi `2^g − 1`, chiết khấu `log₂(i + 1)` theo vị trí `i` (từ 1):

```
DCG@10  = Σ_{i=1..10} (2^{g_i} − 1) / log₂(i + 1)
nDCG@10 = DCG@10 / IDCG@10
```

IDCG là DCG của thứ hạng lý tưởng, lấy từ **mọi** nhãn đã gán của truy vấn (sắp giảm dần, cắt 10). Grade 2 có độ lợi 3, gấp ba grade 1, và trang tốt xếp cao được thưởng nhiều hơn. Truy vấn chưa có nhãn nào thì IDCG = 0, nDCG = 0 nhưng vẫn tính vào trung bình (bảng kết quả ghi rõ số truy vấn này).

Điểm của profile là trung bình trên các truy vấn.

## Đọc kết quả

```
profile  P@10   nDCG@10  chưa gán nhãn
A        …      …        …
B        …      …        …
C        …      …        …
D        …      …        …
```

- B so với A: đóng góp của bigram và bỏ dấu (rõ nhất ở truy vấn không dấu như `lich thi`).
- C so với B: đóng góp của cụm từ và PageRank.
- D so với C: đóng góp của lọc rác và khử trùng; kỳ vọng thấy ở truy vấn mà kết quả lặp hoặc có trang rác.

Số liệu chỉ đáng tin khi cột "chưa gán nhãn" gần 0. File `qrels.tsv` hiện đã có ứng viên nhưng cột `grade` chưa được gán, nên chưa có số liệu ở đây.
