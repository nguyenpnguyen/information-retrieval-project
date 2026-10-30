package vn.hust.search;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Nhận diện trang rác và khoá nội dung để khử trùng.
 * Rác = (chỉ với html) "độ lạ" vượt ngưỡng: IDF trung bình các từ của trang so với cả kho.
 * Trang thật dùng từ vựng chung của kho (đào tạo, sinh viên…), trang bị chèn rác dùng từ gần như không trang nào khác có.
 */
public final class Junk {
    private Junk() {}

    /** Trang ít từ hơn thế thì không đủ để ước lượng độ lạ. */
    static final int MIN_WORDS = 50;
    private static final Pattern WORD = Pattern.compile("\\p{L}+");
    private static final double BIN = 0.1;

    /** Các từ (chỉ chữ cái) của tiêu đề + nội dung, chữ thường, NFC. */
    public static List<String> words(String title, String content) {
        String text = Analyzers.nfc((title == null ? "" : title) + "\n" + (content == null ? "" : content)).toLowerCase();
        List<String> out = new ArrayList<>();
        for (Matcher m = WORD.matcher(text); m.find(); ) out.add(m.group());
        return out;
    }

    /** Độ lạ = trung bình log(n / df(w)) trên mọi từ của trang; NaN khi trang quá ngắn. */
    public static double oddity(List<String> words, Map<String, Integer> df, int n) {
        if (words.size() < MIN_WORDS || n == 0) return Double.NaN;
        double s = 0;
        for (String w : words) s += Math.log((double) n / Math.max(1, df.getOrDefault(w, 1)));
        return s / words.size();
    }

    /**
     * Ngưỡng tách cụm rác: làm mượt histogram (bin 0,1) cho tới khi còn đúng 2 đỉnh,
     * ngưỡng = đáy thung lũng giữa hai đỉnh, đo trên histogram gốc (kiểu Prewitt–Mendelsohn).
     * Không có 2 đỉnh tách bằng một thung lũng sâu thì +∞ (không gắn cờ trang nào).
     * ponytail: rác ít quá (không thành đỉnh riêng) thì bỏ qua; cần thì đổi sang ngưỡng cố định theo phân vị.
     */
    public static double valley(double[] scores) {
        double lo = Double.MAX_VALUE, hi = -Double.MAX_VALUE;
        for (double s : scores) {
            if (Double.isNaN(s)) continue;
            lo = Math.min(lo, s);
            hi = Math.max(hi, s);
        }
        if (lo >= hi) return Double.POSITIVE_INFINITY;
        int bins = (int) Math.ceil((hi - lo) / BIN) + 1;
        double[] h = new double[bins];
        for (double s : scores) if (!Double.isNaN(s)) h[(int) ((s - lo) / BIN)]++;
        double[] raw = h.clone();
        for (int round = 0; round < 200; round++) {
            List<Integer> peaks = peaks(h);
            if (peaks.size() < 2) return Double.POSITIVE_INFINITY;
            if (peaks.size() == 2) {
                int a = peaks.get(0), b = peaks.get(1), v = a;
                if (h[b] > h[a]) return Double.POSITIVE_INFINITY; // cụm bên phải đông hơn thì không phải rác
                // đáy đo trên histogram gốc: làm mượt nhiều vòng thì thung lũng cũng bị lấp theo
                for (int i = a; i <= b; i++) if (raw[i] < raw[v]) v = i;
                if (raw[v] <= 0.5 * h[b]) return lo + (v + 0.5) * BIN; // thung lũng nông là nhiễu, làm mượt tiếp
            }
            double[] g = new double[bins];
            for (int i = 0; i < bins; i++) g[i] = (h[Math.max(0, i - 1)] + h[i] + h[Math.min(bins - 1, i + 1)]) / 3;
            h = g;
        }
        return Double.POSITIVE_INFINITY;
    }

    /** Đỉnh địa phương (cao hơn hẳn bên trái, không thấp hơn bên phải); dải bằng nhau tính một đỉnh. */
    private static List<Integer> peaks(double[] h) {
        List<Integer> p = new ArrayList<>();
        for (int i = 0; i < h.length; i++) {
            double left = i == 0 ? -1 : h[i - 1], right = i == h.length - 1 ? -1 : h[i + 1];
            if (h[i] > 0 && h[i] > left && h[i] >= right) {
                int j = i;
                while (j + 1 < h.length && h[j + 1] == h[i]) j++;
                if (j == h.length - 1 || h[j + 1] < h[i]) p.add(i);
            }
        }
        return p;
    }

    /** SHA-1 của nội dung đã hạ chữ thường, gộp khoảng trắng; null nếu quá ngắn (&lt; 200 ký tự). */
    public static String contentKey(String content) {
        if (content == null) return null;
        String n = Analyzers.nfc(content).toLowerCase().replaceAll("\\s+", " ").trim();
        if (n.length() < 200) return null;
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(n.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
