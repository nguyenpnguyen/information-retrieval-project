package vn.hust.search;

/** Cấu hình xếp hạng: expand = thêm trường bigram/ascii, rerank = chấm lại cụm từ + pagerank, clean = bỏ rác. */
public enum Profile {
    A(false, false, false), B(true, false, false), C(true, true, false), D(true, true, true);

    public final boolean expand, rerank, clean;

    Profile(boolean expand, boolean rerank, boolean clean) {
        this.expand = expand;
        this.rerank = rerank;
        this.clean = clean;
    }

    /** Không phân biệt hoa thường; null/rỗng thì D. */
    public static Profile parse(String s) {
        return s == null || s.isBlank() ? D : valueOf(s.trim().toUpperCase());
    }
}
