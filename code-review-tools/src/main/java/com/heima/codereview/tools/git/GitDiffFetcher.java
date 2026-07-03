package com.heima.codereview.tools.git;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

@Component
public class GitDiffFetcher {

    private static final Logger log = LoggerFactory.getLogger(GitDiffFetcher.class);
    private static final int DEFAULT_RECENT_COMMIT_WINDOW = 5;
    private static final int MAX_DIFF_LENGTH = 30000;
    private static final int MAX_SUMMARY_FILES = 20;
    private static final int MAX_SECTION_LENGTH = 6000;
    private static final int MIN_RESERVED_LENGTH = 400;
    private static final String EMPTY_DIFF_MESSAGE = "未获取到可用的仓库差异：当前仓库没有检测到可用差异。";
    private static final int CLEAN_DIFF_TRUNCATE_THRESHOLD = 30000;

    public String fetchDiff(String repoUrl, String branch) {
        return fetchDiff(repoUrl, branch, null);
    }

    public String fetchDiff(String repoUrl, String branch, String language) {
        return fetchDiff(repoUrl, branch, null, null, language);
    }

    public String fetchDiff(String repoUrl, String branch, String baseCommit, String headCommit) {
        return fetchDiff(repoUrl, branch, baseCommit, headCommit, null);
    }

    public String fetchDiff(String repoUrl, String branch, String baseCommit, String headCommit, String language) {
        if (repoUrl == null || repoUrl.isBlank()) {
            return "";
        }
        try {
            RepositorySnapshot snapshot = resolveRepository(repoUrl, branch);
            String strategy = resolveStrategy(snapshot, baseCommit, headCommit);
            log.info("开始获取Git差异。repoUrl={}, branch={}, baseCommit={}, headCommit={}, language={}, localPath={}, remoteRepo={}, strategy={}",
                    repoUrl, blankToEmpty(branch), blankToEmpty(baseCommit), blankToEmpty(headCommit),
                    blankToEmpty(language), snapshot.path(), snapshot.remote(), strategy);

            String rawDiff = buildDiffContent(snapshot, branch, baseCommit, headCommit).trim();
            if (rawDiff.isBlank()) {
                log.info("Git差异为空。repoUrl={}, branch={}, localPath={}, strategy={}",
                        repoUrl, blankToEmpty(branch), snapshot.path(), strategy);
                return EMPTY_DIFF_MESSAGE;
            }

            String diff = DiffCleaner.clean(rawDiff);
            if (diff.isBlank()) {
                log.info("Git差异清洗后为空，可能只包含非代码变更。repoUrl={}, branch={}, localPath={}, strategy={}",
                        repoUrl, blankToEmpty(branch), snapshot.path(), strategy);
                return EMPTY_DIFF_MESSAGE;
            }

            diff = prepareReviewContext(diff, language);
            if (diff.length() > MAX_DIFF_LENGTH) {
                log.info("Git差异过长，执行最终截断。repoUrl={}, branch={}, originalLength={}, keptLength={}",
                        repoUrl, blankToEmpty(branch), diff.length(), MAX_DIFF_LENGTH);
                diff = truncateAtHunkBoundary(diff, MAX_DIFF_LENGTH);
            }

            log.info("获取Git差异成功。repoUrl={}, branch={}, localPath={}, diffLength={}, strategy={}",
                    repoUrl, blankToEmpty(branch), snapshot.path(), diff.length(), strategy);
            return diff;
        } catch (Exception e) {
            log.warn("获取Git差异失败。repoUrl={}, branch={}, reason={}", repoUrl, branch, e.getMessage());
            return "未获取到可用的仓库差异：" + e.getMessage();
        }
    }

    private RepositorySnapshot resolveRepository(String repoUrl, String branch) throws Exception {
        if (isRemoteRepository(repoUrl)) {
            return prepareRemoteRepository(repoUrl, branch);
        }
        try {
            Path localPath = Path.of(repoUrl);
            if (Files.exists(localPath) && Files.isDirectory(localPath)) {
                return new RepositorySnapshot(localPath, false);
            }
        } catch (InvalidPathException e) {
            throw new IllegalStateException("仓库地址格式非法: " + repoUrl, e);
        }
        throw new IllegalStateException("仓库地址无效，既不是本地目录，也不是可识别的远程Git地址");
    }

    private RepositorySnapshot prepareRemoteRepository(String repoUrl, String branch) throws Exception {
        Path cacheRoot = Path.of(System.getProperty("java.io.tmpdir"), "code-review-agent", "git-cache");
        Files.createDirectories(cacheRoot);
        Path repoPath = cacheRoot.resolve(Integer.toHexString(repoUrl.hashCode()));
        if (Files.exists(repoPath.resolve(".git"))) {
            execute(new String[]{"git", "-C", repoPath.toString(), "fetch", "--all", "--prune"});
        } else if (branch != null && !branch.isBlank()) {
            execute(new String[]{"git", "clone", "--depth", "50", "--branch", branch, repoUrl, repoPath.toString()});
        } else {
            execute(new String[]{"git", "clone", "--depth", "50", repoUrl, repoPath.toString()});
        }
        if (branch != null && !branch.isBlank()) {
            checkoutRemoteBranch(repoPath, branch);
        }
        return new RepositorySnapshot(repoPath, true);
    }

    private void checkoutRemoteBranch(Path repoPath, String branch) throws Exception {
        try {
            execute(new String[]{"git", "-C", repoPath.toString(), "checkout", branch});
        } catch (Exception ignored) {
            execute(new String[]{"git", "-C", repoPath.toString(), "checkout", "-B", branch, "origin/" + branch});
        }
        execute(new String[]{"git", "-C", repoPath.toString(), "pull", "--ff-only", "origin", branch});
    }

    private String buildDiffContent(RepositorySnapshot snapshot, String branch, String baseCommit, String headCommit) throws Exception {
        if (hasText(baseCommit) || hasText(headCommit)) {
            if (!hasText(baseCommit) || !hasText(headCommit)) {
                throw new IllegalStateException("增量审查需要同时提供 baseCommit 和 headCommit");
            }
            String commitLog = execute(new String[]{"git", "-C", snapshot.path().toString(), "log", "--oneline", baseCommit + ".." + headCommit});
            String diff = execute(new String[]{"git", "-C", snapshot.path().toString(), "diff", "--find-renames", "--unified=3", baseCommit, headCommit});
            return formatDiff("指定提交区间", commitLog, diff);
        }

        int commitWindow = resolveCommitWindow(snapshot.path());
        String commitLog = execute(new String[]{"git", "-C", snapshot.path().toString(), "log", "--oneline", "-n", String.valueOf(commitWindow)});

        if (snapshot.remote()) {
            String diff = commitWindow <= 1
                    ? execute(new String[]{"git", "-C", snapshot.path().toString(), "show", "HEAD", "--format="})
                    : execute(new String[]{"git", "-C", snapshot.path().toString(), "diff", "--find-renames", "--unified=3", "HEAD~" + commitWindow, "HEAD"});
            return formatDiff("远程仓库最近 " + commitWindow + " 次提交", commitLog, diff);
        }

        if (hasText(branch)) {
            try {
                String diff = execute(new String[]{"git", "-C", snapshot.path().toString(), "diff", "--find-renames", "--unified=3", "origin/" + branch + "...HEAD"});
                return formatDiff("本地分支相对 origin/" + branch + " 的差异", commitLog, diff);
            } catch (Exception e) {
                log.info("按分支比较失败，回退到最近提交窗口。path={}, branch={}, reason={}", snapshot.path(), branch, e.getMessage());
            }
        }

        String diff = commitWindow <= 1
                ? execute(new String[]{"git", "-C", snapshot.path().toString(), "show", "HEAD", "--format="})
                : execute(new String[]{"git", "-C", snapshot.path().toString(), "diff", "--find-renames", "--unified=3", "HEAD~" + commitWindow, "HEAD"});
        return formatDiff("本地仓库最近 " + commitWindow + " 次提交", commitLog, diff);
    }

    private int resolveCommitWindow(Path repoPath) throws Exception {
        String countText = execute(new String[]{"git", "-C", repoPath.toString(), "rev-list", "--count", "HEAD"}).trim();
        int commitCount = Integer.parseInt(countText);
        if (commitCount <= 1) {
            return 1;
        }
        return Math.min(DEFAULT_RECENT_COMMIT_WINDOW, commitCount - 1);
    }

    private String formatDiff(String title, String commitLog, String diff) {
        // 保持最小元数据，实际清洗由 DiffCleaner 完成
        StringBuilder builder = new StringBuilder();
        builder.append("# Git审查上下文\n");
        builder.append("策略: ").append(title).append("\n\n");
        if (commitLog != null && !commitLog.isBlank()) {
            builder.append("## 最近提交\n");
            builder.append(commitLog.trim()).append("\n\n");
        }
        builder.append("## 代码差异\n");
        builder.append(diff == null ? "" : diff.trim());
        return builder.toString();
    }

    private String truncateAtHunkBoundary(String diff, int maxLength) {
        if (diff == null || diff.length() <= maxLength) {
            return diff;
        }
        int lastBoundary = diff.lastIndexOf("\n@@", maxLength);
        if (lastBoundary <= 0) {
            lastBoundary = maxLength;
        }
        return diff.substring(0, lastBoundary)
                + "\n\n[差异内容仍然过长，已按 hunk 边界截断，仅保留前 "
                + lastBoundary + " 个字符用于AI审查]";
    }

    private String prepareReviewContext(String diff, String language) {
        if (diff == null || diff.isBlank() || diff.length() <= MAX_DIFF_LENGTH) {
            return diff;
        }
        int diffStartIndex = diff.indexOf("diff --git ");
        if (diffStartIndex < 0) {
            return diff;
        }

        String preamble = diff.substring(0, diffStartIndex).trim();
        String diffBody = diff.substring(diffStartIndex).trim();
        List<DiffSection> sections = parseDiffSections(diffBody, language);
        if (sections.isEmpty()) {
            return diff;
        }

        String normalizedLanguage = normalizeLanguage(language);
        long languageMatchCount = sections.stream().filter(DiffSection::languageMatched).count();
        StringBuilder builder = new StringBuilder();
        if (!preamble.isBlank()) {
            builder.append(preamble).append("\n\n");
        }
        builder.append("## 变更文件概览\n");
        if (!normalizedLanguage.isBlank()) {
            builder.append("- 已按 ").append(normalizedLanguage).append(" 优先重组审查片段，命中 ")
                    .append(languageMatchCount).append(" 个相关文件。\n");
        } else {
            builder.append("- 已优先保留代码文件、后端配置和更适合审查的差异片段。\n");
        }
        sections.stream()
                .limit(MAX_SUMMARY_FILES)
                .forEach(section -> builder.append("- ")
                        .append(section.displayName())
                        .append(section.languageMatched() ? " [match]" : "")
                        .append('\n'));
        builder.append("\n## 优先审查片段\n");

        int omittedSections = 0;
        boolean appendedSection = false;
        for (DiffSection section : sections) {
            String snippet = shortenSection(section.content());
            int remaining = MAX_DIFF_LENGTH - builder.length() - MIN_RESERVED_LENGTH;
            if (remaining <= 0) {
                omittedSections++;
                continue;
            }
            if (snippet.length() > remaining) {
                if (appendedSection) {
                    omittedSections++;
                    continue;
                }
                snippet = snippet.substring(0, Math.max(remaining, 0));
            }
            if (snippet.isBlank()) {
                omittedSections++;
                continue;
            }
            builder.append(snippet.trim()).append("\n\n");
            appendedSection = true;
        }

        if (omittedSections > 0) {
            builder.append("[为保证关键代码片段优先可见，已省略 ").append(omittedSections)
                    .append(" 个较低优先级或过长的文件片段]\n");
        }
        return builder.toString().trim();
    }

    private List<DiffSection> parseDiffSections(String diffBody, String language) {
        String normalizedLanguage = normalizeLanguage(language);
        List<DiffSection> sections = new ArrayList<>();
        String[] lines = diffBody.split("\\R");
        StringBuilder current = null;
        String currentPath = "";
        int originalIndex = 0;
        for (String line : lines) {
            if (line.startsWith("diff --git ")) {
                if (current != null && current.length() > 0) {
                    sections.add(buildSection(currentPath, current.toString(), normalizedLanguage, originalIndex++));
                }
                currentPath = extractFilePath(line);
                current = new StringBuilder();
            }
            if (current != null) {
                current.append(line).append('\n');
            }
        }
        if (current != null && current.length() > 0) {
            sections.add(buildSection(currentPath, current.toString(), normalizedLanguage, originalIndex));
        }

        return sections.stream()
                .sorted(Comparator.comparingInt(DiffSection::priority).reversed()
                        .thenComparingInt(DiffSection::order))
                .toList();
    }

    private DiffSection buildSection(String path, String content, String normalizedLanguage, int order) {
        boolean languageMatched = matchesLanguage(path, normalizedLanguage);
        int priority = calculatePriority(path, normalizedLanguage, languageMatched);
        return new DiffSection(path, safeDisplayPath(path), content, languageMatched, priority, order);
    }

    private String extractFilePath(String headerLine) {
        String[] parts = headerLine.split(" ");
        if (parts.length >= 4) {
            return parts[3].replaceFirst("^b/", "").trim();
        }
        return headerLine;
    }

    private int calculatePriority(String path, String normalizedLanguage, boolean languageMatched) {
        String normalizedPath = blankToEmpty(path).toLowerCase(Locale.ROOT);
        int priority = 0;
        if (languageMatched) {
            priority += 100;
        }
        if (normalizedPath.contains("/src/main/java/") || normalizedPath.contains("\\src\\main\\java\\")) {
            priority += 60;
        }
        if (normalizedPath.endsWith(".java")) {
            priority += 50;
        }
        if (normalizedPath.endsWith("pom.xml")
                || normalizedPath.endsWith("application.yml")
                || normalizedPath.endsWith("application.yaml")
                || normalizedPath.endsWith("application.properties")
                || normalizedPath.endsWith(".sql")
                || normalizedPath.endsWith(".xml")) {
            priority += 35;
        }
        if (normalizedPath.endsWith("package-lock.json")
                || normalizedPath.endsWith("pnpm-lock.yaml")
                || normalizedPath.endsWith("yarn.lock")
                || normalizedPath.endsWith(".md")
                || normalizedPath.endsWith("license")) {
            priority -= 25;
        }
        if (normalizedLanguage.isBlank() && isCodeLikePath(normalizedPath)) {
            priority += 20;
        }
        return priority;
    }

    private boolean matchesLanguage(String path, String normalizedLanguage) {
        if (normalizedLanguage.isBlank()) {
            return false;
        }
        String normalizedPath = blankToEmpty(path).toLowerCase(Locale.ROOT);
        return switch (normalizedLanguage) {
            case "java" -> normalizedPath.endsWith(".java") || normalizedPath.endsWith("pom.xml");
            case "python" -> normalizedPath.endsWith(".py");
            case "javascript" -> normalizedPath.endsWith(".js") || normalizedPath.endsWith(".jsx");
            case "typescript" -> normalizedPath.endsWith(".ts") || normalizedPath.endsWith(".tsx");
            case "go" -> normalizedPath.endsWith(".go");
            case "kotlin" -> normalizedPath.endsWith(".kt");
            case "rust" -> normalizedPath.endsWith(".rs");
            case "vue" -> normalizedPath.endsWith(".vue");
            default -> normalizedPath.contains(normalizedLanguage);
        };
    }

    private boolean isCodeLikePath(String normalizedPath) {
        return normalizedPath.endsWith(".java")
                || normalizedPath.endsWith(".py")
                || normalizedPath.endsWith(".js")
                || normalizedPath.endsWith(".jsx")
                || normalizedPath.endsWith(".ts")
                || normalizedPath.endsWith(".tsx")
                || normalizedPath.endsWith(".go")
                || normalizedPath.endsWith(".kt")
                || normalizedPath.endsWith(".rs")
                || normalizedPath.endsWith(".sql")
                || normalizedPath.endsWith(".xml")
                || normalizedPath.endsWith(".yml")
                || normalizedPath.endsWith(".yaml")
                || normalizedPath.endsWith(".properties");
    }

    private String shortenSection(String content) {
        String normalized = content == null ? "" : content.trim();
        if (normalized.length() <= MAX_SECTION_LENGTH) {
            return normalized;
        }
        return normalized.substring(0, MAX_SECTION_LENGTH)
                + "\n[当前文件差异片段过长，已截断到更适合 AI 审查的长度]";
    }

    private String normalizeLanguage(String language) {
        return language == null ? "" : language.trim().toLowerCase(Locale.ROOT);
    }

    private String safeDisplayPath(String path) {
        String normalized = blankToEmpty(path);
        return normalized.isBlank() ? "unknown-file" : normalized;
    }

    private String resolveStrategy(RepositorySnapshot snapshot, String baseCommit, String headCommit) {
        if (hasText(baseCommit) && hasText(headCommit)) {
            return "commit-range";
        }
        return snapshot.remote() ? "recent-remote-commits" : "local-branch-or-recent-commits";
    }

    private String execute(String[] command) throws Exception {
        String[] effectiveCommand = disableGitProxy(command);
        ProcessBuilder processBuilder = new ProcessBuilder(effectiveCommand);
        processBuilder.redirectErrorStream(true);
        Process process = processBuilder.start();
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append('\n');
            }
        }
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new IllegalStateException(output.toString().trim().isEmpty()
                    ? "git命令执行失败，exitCode=" + exitCode
                    : output.toString().trim());
        }
        return output.toString();
    }

    private String[] disableGitProxy(String[] command) {
        if (command.length == 0 || !"git".equals(command[0])) {
            return command;
        }
        String[] effective = new String[command.length + 4];
        effective[0] = "git";
        effective[1] = "-c";
        effective[2] = "http.proxy=";
        effective[3] = "-c";
        effective[4] = "https.proxy=";
        System.arraycopy(command, 1, effective, 5, command.length - 1);
        return effective;
    }

    private boolean isRemoteRepository(String repoUrl) {
        return repoUrl.startsWith("http://")
                || repoUrl.startsWith("https://")
                || repoUrl.startsWith("git@");
    }

    private boolean hasText(String text) {
        return text != null && !text.isBlank();
    }

    private String blankToEmpty(String text) {
        return text == null ? "" : text;
    }

    private record RepositorySnapshot(Path path, boolean remote) {
    }

    private record DiffSection(String path,
                               String displayName,
                               String content,
                               boolean languageMatched,
                               int priority,
                               int order) {
    }
}
