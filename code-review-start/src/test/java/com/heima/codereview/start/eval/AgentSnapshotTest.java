package com.heima.codereview.start.eval;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class AgentSnapshotTest {

    private static final Pattern CJK_PATTERN = Pattern.compile("\\p{IsHan}");
    private static final Pattern CJK_WORD_PATTERN = Pattern.compile("[\\p{IsHan}]{2,}");

    private AgentSnapshotTest() {}

    /**
     * 使用 Jaccard 相似度比较两段文本
     * 允许非语义差异（如空格、标点变化）
     */
    public static double computeTextSimilarity(String textA, String textB) {
        if (textA == null && textB == null) return 1.0;
        if (textA == null || textB == null) return 0.0;

        Set<String> tokensA = tokenize(textA);
        Set<String> tokensB = tokenize(textB);

        if (tokensA.isEmpty() && tokensB.isEmpty()) return 1.0;

        Set<String> intersection = new HashSet<>(tokensA);
        intersection.retainAll(tokensB);

        Set<String> union = new HashSet<>(tokensA);
        union.addAll(tokensB);

        return (double) intersection.size() / Math.max(1, union.size());
    }

    /**
     * 标准化文本用于对比
     */
    public static String normalizeOutput(String text) {
        if (text == null) return "";
        return text.replaceAll("[，,。\\.、；;：:！!？?]+", "")
                   .replaceAll("\\s+", " ")
                   .trim()
                   .toLowerCase();
    }

    private static Set<String> tokenize(String text) {
        Set<String> tokens = new HashSet<>();
        if (text == null) return tokens;
        String normalized = normalizeOutput(text);

        boolean hasCJK = CJK_PATTERN.matcher(normalized).find();

        if (hasCJK) {
            for (int i = 0; i + 1 < normalized.length(); i++) {
                tokens.add(normalized.substring(i, Math.min(i + 2, normalized.length())));
            }
        }

        String[] words = normalized.split("\\s+");
        for (String word : words) {
            word = word.trim();
            if (word.length() > 1 && word.length() < 30) {
                tokens.add(word);
            }
        }

        return tokens;
    }
}
