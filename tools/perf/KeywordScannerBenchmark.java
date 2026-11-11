import com.example.installlock.KeywordScanner;
import java.util.*;
import java.util.regex.Pattern;
import java.text.Normalizer;

/** Compare normalized-page scanning only; no network, UI or normalization speedup is claimed. */
public class KeywordScannerBenchmark {
    private static volatile int sink;
    private static final Pattern FORMAT = Pattern.compile("\\p{Cf}");
    private static final Pattern SPACE = Pattern.compile("\\s+");
    private static String originalNormalization(String text) {
        return SPACE.matcher(FORMAT.matcher(Normalizer.normalize(text, Normalizer.Form.NFKC))
            .replaceAll("").toLowerCase(Locale.ROOT)).replaceAll(" ").strip();
    }
    private static String optimizedNormalization(String text) {
        String ascii = KeywordScanner.normalizeAscii(text);
        return ascii == null ? originalNormalization(text) : ascii;
    }
    private static void equivalence() {
        Random random = new Random(12452);
        String[] vocabulary = {"a", "art", "artist", "comic", "book", "review", "manga", "தமிழ்", "中文", "𐐨", "x-ray"};
        String[] gaps = {" ", "_", "2", "²", "Ⅳ", ".", "-", "\n", "\u200b", "🙂", "\u0301"};
        for (int sample = 0; sample < 500; sample++) {
            TreeSet<String> set = new TreeSet<>();
            for (int i = 0, n = 1 + random.nextInt(45); i < n; i++) {
                String word = vocabulary[random.nextInt(vocabulary.length)];
                if (random.nextBoolean()) word += " " + vocabulary[random.nextInt(vocabulary.length)];
                set.add(word);
            }
            String[] words = set.toArray(new String[0]);
            StringBuilder page = new StringBuilder();
            for (int i = 0; i < 40; i++) page.append(vocabulary[random.nextInt(vocabulary.length)])
                .append(gaps[random.nextInt(gaps.length)]);
            String text = page.toString();
            List<Integer> expected = new ArrayList<>();
            for (int i = 0; i < words.length; i++) if (Pattern.compile(
                "(?<![\\p{L}\\p{N}_])" + Pattern.quote(words[i]) + "(?![\\p{L}\\p{N}_])").matcher(text).find()) expected.add(i);
            KeywordScanner scanner = new KeywordScanner(words);
            if (!Arrays.equals(expected.stream().mapToInt(Integer::intValue).toArray(), scanner.matchingIndices(text)) ||
                scanner.firstMatch(text) != (expected.isEmpty() ? -1 : expected.get(0)))
                throw new AssertionError("Platform regex equivalence failed at case " + sample);
        }
        for (int sample = 0; sample < 500; sample++) {
            StringBuilder text = new StringBuilder();
            for (int i = 0; i < 256; i++) text.append((char) random.nextInt(128));
            if (!originalNormalization(text.toString()).equals(optimizedNormalization(text.toString())))
                throw new AssertionError("ASCII normalization equivalence failed");
        }
        System.out.println("# Passed 500 regex-equivalence cases and 500 normalization cases on this runtime.");
    }
    private static long measure(Runnable action) {
        for (int i = 0; i < 30; i++) action.run();
        long[] samples = new long[7];
        for (int i = 0; i < samples.length; i++) {
            long start = System.nanoTime();
            for (int j = 0; j < 10; j++) action.run();
            samples[i] = (System.nanoTime() - start) / 10;
        }
        Arrays.sort(samples);
        return samples[samples.length / 2];
    }
    public static void main(String[] args) {
        equivalence();
        System.out.println("keywords,chars,case,regex_ns,java_ns,speedup");
        for (int count : new int[] {3, 32, 128}) {
            String[] words = new String[count];
            for (int i = 0; i < count; i++) words[i] = String.format(Locale.ROOT, "blockedword%03d", i);
            KeywordScanner scanner = new KeywordScanner(words);
            Pattern[] patterns = Arrays.stream(words).map(w -> Pattern.compile(
                "(?<![\\p{L}\\p{N}_])" + Pattern.quote(w) + "(?![\\p{L}\\p{N}_])")).toArray(Pattern[]::new);
            for (int size : new int[] {4096, 131072}) {
                String safe = "ordinary page text with useful information. ".repeat(size / 40 + 1).substring(0, size);
                for (String scenario : new String[] {"absent", "late-hit"}) {
                    String text = scenario.equals("absent") ? safe : safe + " " + words[count - 1];
                    Runnable old = () -> { int first = -1; for (int i = 0; i < patterns.length; i++)
                        if (patterns[i].matcher(text).find()) { first = i; break; } sink = first; };
                    Runnable optimized = () -> sink = scanner.firstMatch(text);
                    old.run(); int expected = sink; optimized.run();
                    if (expected != sink) throw new AssertionError("Scanner mismatch");
                    long before = measure(old), after = measure(optimized);
                    System.out.printf(Locale.ROOT, "%d,%d,%s,%d,%d,%.2f%n", count, text.length(), scenario, before, after, (double) before / after);
                }
            }
        }
        String ascii = "Ordinary PAGE\ttext with useful information.\n".repeat(3000);
        for (String text : new String[] {ascii, ascii + " தமிழ் ＡＮＩＭＥ 🙂"}) {
            long before = measure(() -> sink = originalNormalization(text).length());
            long after = measure(() -> sink = optimizedNormalization(text).length());
            System.out.printf(Locale.ROOT, "0,%d,normalization-%s,%d,%d,%.2f%n", text.length(),
                text.equals(ascii) ? "ascii" : "unicode", before, after, (double) before / after);
        }
    }
}
