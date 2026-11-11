package com.example.installlock;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

/** Immutable literal scanner for normalized, unique keywords. No page text is retained.
 * Small lists use String.indexOf; larger lists share one Aho-Corasick traversal. */
public final class KeywordScanner {
    private static final int MULTI_PATTERN_THRESHOLD = 16;
    // Keep the platform regex engine's Unicode boundary semantics (including its
    // supplementary-character handling); only ASCII boundaries use the fast path.
    private static final Pattern LEFT = Pattern.compile("(?<![\\p{L}\\p{N}_])");
    private static final Pattern RIGHT = Pattern.compile("(?![\\p{L}\\p{N}_])");
    private final String[] words;
    private final Pattern[] supplementaryPatterns;
    private final Node root;

    /** ASCII is already NFKC and contains no Cf characters. Null requests the Unicode path. */
    public static String normalizeAscii(String text) {
        boolean changed = !text.isEmpty() && (trimSpace(text.charAt(0)) || trimSpace(text.charAt(text.length() - 1)));
        boolean space = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= 128) return null;
            if (c >= 'A' && c <= 'Z' || c >= 9 && c <= 13 || c == ' ' && space) changed = true;
            space = c == ' ';
        }
        if (!changed) return text;
        int start = 0, end = text.length();
        while (start < end && trimSpace(text.charAt(start))) start++;
        while (end > start && trimSpace(text.charAt(end - 1))) end--;
        StringBuilder result = new StringBuilder(end - start);
        space = false;
        for (int i = start; i < end; i++) {
            char c = text.charAt(i);
            boolean whitespace = c == ' ' || c >= 9 && c <= 13;
            if (whitespace) {
                if (!space) result.append(' ');
            } else result.append(c >= 'A' && c <= 'Z' ? (char) (c + ('a' - 'A')) : c);
            space = whitespace;
        }
        return result.toString();
    }

    private static boolean trimSpace(char c) {
        return c >= 9 && c <= 13 || c >= 28 && c <= 32;
    }

    private static final class Node {
        Map<Character, Node> edges = new HashMap<>(); // Construction only.
        char[] labels;
        Node[] targets;
        Node[] ascii;
        Node failure;
        Node output;
        int word = -1;

        Node next(char c) {
            if (ascii != null && c < ascii.length) return ascii[c];
            if (labels.length == 1) return labels[0] == c ? targets[0] : null;
            int index = Arrays.binarySearch(labels, c);
            return index < 0 ? null : targets[index];
        }

        void freeze(boolean root) {
            labels = new char[edges.size()];
            int at = 0;
            for (char c : edges.keySet()) labels[at++] = c;
            Arrays.sort(labels);
            targets = new Node[labels.length];
            if (root) ascii = new Node[128];
            for (int i = 0; i < labels.length; i++) {
                targets[i] = edges.get(labels[i]);
                if (ascii != null && labels[i] < ascii.length) ascii[labels[i]] = targets[i];
            }
            edges = null; // Retain compact arrays, not per-character boxed hash tables.
        }
    }

    public KeywordScanner(String[] normalizedWords) {
        words = normalizedWords.clone();
        supplementaryPatterns = new Pattern[words.length];
        for (int i = 0; i < words.length; i++) {
            String word = words[i];
            if (word == null || word.isEmpty()) throw new IllegalArgumentException("Empty keyword");
            // The regex engine chooses different lookbehind code for literals
            // containing supplementary characters. Preserve that platform detail.
            for (int j = 0; j < word.length(); j++) if (Character.isSurrogate(word.charAt(j))) {
                supplementaryPatterns[i] = Pattern.compile(
                    "(?<![\\p{L}\\p{N}_])" + Pattern.quote(word) + "(?![\\p{L}\\p{N}_])");
                break;
            }
        }
        root = words.length < MULTI_PATTERN_THRESHOLD ? null : compile();
    }

    private Node compile() {
        Node start = new Node();
        start.failure = start;
        for (int i = 0; i < words.length; i++) {
            Node node = start;
            for (int j = 0; j < words[i].length(); j++)
                node = node.edges.computeIfAbsent(words[i].charAt(j), ignored -> new Node());
            node.word = i;
        }
        ArrayDeque<Node> queue = new ArrayDeque<>();
        ArrayList<Node> nodes = new ArrayList<>();
        nodes.add(start);
        for (Node child : start.edges.values()) {
            child.failure = start;
            queue.add(child);
        }
        while (!queue.isEmpty()) {
            Node parent = queue.removeFirst();
            nodes.add(parent);
            for (Map.Entry<Character, Node> edge : parent.edges.entrySet()) {
                char c = edge.getKey();
                Node child = edge.getValue();
                Node failure = parent.failure;
                while (failure != start && !failure.edges.containsKey(c)) failure = failure.failure;
                Node next = failure.edges.get(c);
                child.failure = next == null ? start : next;
                child.output = child.failure.word >= 0 ? child.failure : child.failure.output;
                queue.add(child);
            }
        }
        for (Node node : nodes) node.freeze(node == start);
        return start;
    }

    public int firstMatch(String text) {
        if (root == null) {
            for (int i = 0; i < words.length; i++) if (contains(text, i)) return i;
            return -1;
        }
        int first = scan(text, null);
        for (int i = 0; i < (first < 0 ? words.length : first); i++)
            if (supplementaryPatterns[i] != null && supplementaryPatterns[i].matcher(text).find()) return i;
        return first;
    }

    public int[] matchingIndices(String text) {
        boolean[] found = new boolean[words.length];
        if (root == null) {
            for (int i = 0; i < words.length; i++) found[i] = contains(text, i);
        } else {
            scan(text, found);
            for (int i = 0; i < words.length; i++) if (supplementaryPatterns[i] != null)
                found[i] = supplementaryPatterns[i].matcher(text).find();
        }
        int count = 0;
        for (boolean hit : found) if (hit) count++;
        int[] result = new int[count];
        int at = 0;
        for (int i = 0; i < found.length; i++) if (found[i]) result[at++] = i;
        return result;
    }

    private int scan(String text, boolean[] found) {
        Node state = root;
        int first = Integer.MAX_VALUE;
        int remaining = words.length;
        for (int pos = 0; pos < text.length(); pos++) {
            char c = text.charAt(pos);
            Node next = state.next(c);
            while (next == null && state != root) {
                state = state.failure;
                next = state.next(c);
            }
            state = next == null ? root : next;
            for (Node match = state; match != null; match = match.output) {
                int index = match.word;
                if (index < 0 || supplementaryPatterns[index] != null || found != null && found[index]) continue;
                int end = pos + 1;
                if (!boundary(text, end - words[index].length(), end)) continue;
                if (found != null) { found[index] = true; if (--remaining == 0) return 0; }
                else { first = Math.min(first, index); if (first == 0) return 0; }
            }
        }
        return first == Integer.MAX_VALUE ? -1 : first;
    }

    private boolean contains(String text, int index) {
        if (supplementaryPatterns[index] != null) return supplementaryPatterns[index].matcher(text).find();
        String word = words[index];
        int from = 0;
        while (from <= text.length() - word.length()) {
            int at = text.indexOf(word, from);
            if (at < 0) return false;
            if (boundary(text, at, at + word.length())) return true;
            from = at + 1;
        }
        return false;
    }

    private static boolean boundary(String text, int start, int end) {
        if (start > 0) {
            char before = text.charAt(start - 1);
            if (before < 128 ? asciiWord(before) :
                !LEFT.matcher(text).region(start, start).useTransparentBounds(true).lookingAt()) return false;
        }
        if (end < text.length()) {
            char after = text.charAt(end);
            if (after < 128 ? asciiWord(after) :
                !RIGHT.matcher(text).region(end, end).useTransparentBounds(true).lookingAt()) return false;
        }
        return true;
    }

    private static boolean asciiWord(char c) {
        return c == '_' || c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9';
    }
}
