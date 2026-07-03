package com.heima.codereview.tools.git;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 将 git diff 原始输出清洗为类 GitHub 的代码差异视图：
 * 仅保留 diff --git 文件路径、@@ hunk header、上下文与 +/-/! 变更行，
 * 移除 index、---、+++、提交日志、策略说明等无关元数据。
 */
public final class DiffCleaner {

    private static final int MAX_FILE_LINES_IN_HEADER = 60;
    private static final Set<String> META_PREFIXES = Set.of(
            "index ",
            "--- ",
            "+++ ",
            "old mode ",
            "new mode ",
            "deleted file mode ",
            "new file mode ",
            "similarity index ",
            "rename from ",
            "rename to ",
            "dissimilarity index "
    );

    private DiffCleaner() {
    }

    /**
     * 清洗原始 git diff 字符串。
     *
     * @param rawDiff git diff 原始输出
     * @return 保留代码差异结构的精简 diff
     */
    public static String clean(String rawDiff) {
        if (rawDiff == null || rawDiff.isBlank()) {
            return "";
        }
        String[] lines = rawDiff.split("\\R");
        List<String> result = new ArrayList<>();
        boolean insideFileDiff = false;
        String pendingFileHeader = null;

        for (String rawLine : lines) {
            String line = rawLine.stripTrailing();
            if (line.isBlank()) {
                continue;
            }

            if (line.startsWith("diff --git ")) {
                insideFileDiff = true;
                pendingFileHeader = extractFilePath(line);
                continue;
            }

            if (!insideFileDiff) {
                continue;
            }

            if (isMetaLine(line)) {
                continue;
            }

            if (line.startsWith("@@")) {
                if (pendingFileHeader != null) {
                    result.add(pendingFileHeader);
                    pendingFileHeader = null;
                }
                String header = extractHunkHeader(line);
                result.add(header);
                continue;
            }

            if (pendingFileHeader != null && result.size() > MAX_FILE_LINES_IN_HEADER) {
                // 文件头后迟迟没有 hunk header（如 binary / empty diff），则输出一次文件头避免丢失
                result.add(pendingFileHeader);
                pendingFileHeader = null;
            }

            if (isChangeLine(line) || isContextLine(line)) {
                if (pendingFileHeader != null) {
                    result.add(pendingFileHeader);
                    pendingFileHeader = null;
                }
                result.add(line);
            }
        }

        if (result.isEmpty()) {
            return "";
        }
        return String.join("\n", result);
    }

    private static String extractFilePath(String diffHeaderLine) {
        String[] parts = diffHeaderLine.split(" ");
        if (parts.length >= 4) {
            return parts[3].replaceFirst("^b/", "").trim();
        }
        return diffHeaderLine;
    }

    private static String extractHunkHeader(String line) {
        int at2 = line.indexOf("@@", 2);
        if (at2 > 0) {
            return line.substring(0, at2 + 2).trim();
        }
        return line;
    }

    private static boolean isMetaLine(String line) {
        String lower = line.toLowerCase(Locale.ROOT);
        for (String prefix : META_PREFIXES) {
            if (lower.startsWith(prefix.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private static boolean isContextLine(String line) {
        return line.startsWith(" ");
    }

    private static boolean isChangeLine(String line) {
        return line.startsWith("+") || line.startsWith("-") || line.startsWith("!");
    }
}
