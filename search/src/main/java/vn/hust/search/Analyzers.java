package vn.hust.search;

import java.text.Normalizer;
import java.util.Map;
import java.util.regex.Pattern;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.LowerCaseFilter;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.Tokenizer;
import org.apache.lucene.analysis.miscellaneous.ASCIIFoldingFilter;
import org.apache.lucene.analysis.pattern.PatternTokenizer;
import org.apache.lucene.analysis.shingle.ShingleFilter;
import org.apache.lucene.analysis.standard.StandardTokenizer;
import org.apache.lucene.analysis.miscellaneous.PerFieldAnalyzerWrapper;
import org.apache.lucene.search.similarities.BM25Similarity;
import org.apache.lucene.search.similarities.PerFieldSimilarityWrapper;
import org.apache.lucene.search.similarities.Similarity;

/** Các analyzer của chỉ mục. Mọi chữ phải qua {@link #nfc} trước khi index và khi truy vấn. */
public final class Analyzers {
    private Analyzers() {}

    public static String nfc(String s) {
        return Normalizer.normalize(s, Normalizer.Form.NFC);
    }

    /** StandardTokenizer + chữ thường. */
    public static Analyzer vi() {
        return new Analyzer() {
            @Override
            protected TokenStreamComponents createComponents(String f) {
                Tokenizer t = new StandardTokenizer();
                return new TokenStreamComponents(t, new LowerCaseFilter(t));
            }
        };
    }

    /** vi + bigram (không giữ unigram), ví dụ "bách khoa". */
    public static Analyzer viBi() {
        return new Analyzer() {
            @Override
            protected TokenStreamComponents createComponents(String f) {
                Tokenizer t = new StandardTokenizer();
                ShingleFilter sh = new ShingleFilter(new LowerCaseFilter(t), 2, 2);
                sh.setOutputUnigrams(false);
                return new TokenStreamComponents(t, sh);
            }
        };
    }

    /** vi + bỏ dấu (đ thành d). */
    public static Analyzer viAscii() {
        return new Analyzer() {
            @Override
            protected TokenStreamComponents createComponents(String f) {
                Tokenizer t = new StandardTokenizer();
                return new TokenStreamComponents(t, new ASCIIFoldingFilter(new LowerCaseFilter(t)));
            }
        };
    }

    private static final Pattern NON_ALNUM = Pattern.compile("[^\\p{L}\\p{N}]+");

    /** Tách URL theo mọi ký tự không phải chữ/số, chữ thường, bỏ dấu. */
    public static Analyzer urlTokens() {
        return new Analyzer() {
            @Override
            protected TokenStreamComponents createComponents(String f) {
                Tokenizer t = new PatternTokenizer(NON_ALNUM, -1);
                TokenStream ts = new ASCIIFoldingFilter(new LowerCaseFilter(t));
                return new TokenStreamComponents(t, ts);
            }
        };
    }

    public static Analyzer perField() {
        Analyzer bi = viBi(), ascii = viAscii();
        return new PerFieldAnalyzerWrapper(vi(), Map.of(
                "title_bi", bi, "content_bi", bi,
                "title_ascii", ascii, "content_ascii", ascii,
                "url_tokens", urlTokens()));
    }

    /** Trường content dùng b=0.5 để PDF dài không bị phạt quá nặng. */
    public static Similarity similarity() {
        Similarity def = new BM25Similarity(), content = new BM25Similarity(1.2f, 0.5f);
        return new PerFieldSimilarityWrapper() {
            @Override
            public Similarity get(String field) {
                return field.startsWith("content") ? content : def;
            }
        };
    }
}
