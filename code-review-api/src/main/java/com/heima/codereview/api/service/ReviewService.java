package com.heima.codereview.api.service;

import com.heima.codereview.api.persistence.ReviewTaskPersistenceRepository;
import com.heima.codereview.api.sse.SseEmitterManager;
import com.heima.codereview.common.exception.BizException;
import com.heima.codereview.common.model.chat.ChatMessage;
import com.heima.codereview.common.model.review.ReviewStatus;
import com.heima.codereview.common.model.review.ReviewSubmitRequest;
import com.heima.codereview.common.model.review.ReviewTaskDetail;
import com.heima.codereview.common.model.review.ReviewTaskSummary;
import com.heima.codereview.common.model.review.ReviewType;
import com.heima.codereview.common.utils.IdUtils;
import com.heima.codereview.core.agent.AgentEventListener;
import com.heima.codereview.core.agent.AgentExecutionContext;
import com.heima.codereview.core.agent.FlowAgent;
import com.heima.codereview.core.agent.FlowResult;
import com.heima.codereview.core.cache.ReviewResultCache;
import com.heima.codereview.core.chain.ReviewContext;
import com.heima.codereview.core.enhance.CodeSimilarityDetector;
import com.heima.codereview.core.memory.ChatMemory;
import com.heima.codereview.core.memory.SessionCodeContext;
import com.heima.codereview.core.memory.SessionCodeContextMemory;
import com.heima.codereview.rag.knowledge.ReviewKnowledgeBase;
import com.heima.codereview.rag.model.ReviewRecord;
import com.heima.codereview.tools.git.GitDiffFetcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

@Service
public class ReviewService {

    private static final Logger log = LoggerFactory.getLogger(ReviewService.class);
    private static final int LOG_PREVIEW_MAX = 200;

    private final FlowAgent flowAgent;
    private final SseEmitterManager sseEmitterManager;
    private final ReviewResultCache reviewResultCache;
    private final GitDiffFetcher gitDiffFetcher;
    private final ReviewKnowledgeBase reviewKnowledgeBase;
    private final CodeSimilarityDetector codeSimilarityDetector;
    private final ChatMemory chatMemory;
    private final SessionCodeContextMemory sessionCodeContextMemory;
    private final ReviewTaskPersistenceRepository reviewTaskPersistenceRepository;
    private final Executor reviewTaskExecutor;

    private final Map<String, Long> sessionCancelVersions = new ConcurrentHashMap<>();

    public ReviewService(FlowAgent flowAgent,
                         SseEmitterManager sseEmitterManager,
                         ReviewResultCache reviewResultCache,
                         GitDiffFetcher gitDiffFetcher,
                         ReviewKnowledgeBase reviewKnowledgeBase,
                         CodeSimilarityDetector codeSimilarityDetector,
                         ChatMemory chatMemory,
                         SessionCodeContextMemory sessionCodeContextMemory,
                         ReviewTaskPersistenceRepository reviewTaskPersistenceRepository,
                         @Qualifier("reviewTaskExecutor") Executor reviewTaskExecutor) {
        this.flowAgent = flowAgent;
        this.sseEmitterManager = sseEmitterManager;
        this.reviewResultCache = reviewResultCache;
        this.gitDiffFetcher = gitDiffFetcher;
        this.reviewKnowledgeBase = reviewKnowledgeBase;
        this.codeSimilarityDetector = codeSimilarityDetector;
        this.chatMemory = chatMemory;
        this.sessionCodeContextMemory = sessionCodeContextMemory;
        this.reviewTaskPersistenceRepository = reviewTaskPersistenceRepository;
        this.reviewTaskExecutor = reviewTaskExecutor;
    }

    public ReviewTaskSummary submit(ReviewSubmitRequest request) {
        String reviewId = IdUtils.withPrefix("review");
        String sessionId = request.sessionId() == null || request.sessionId().isBlank()
                ? IdUtils.withPrefix("session")
                : request.sessionId();

        String codeContent = resolveCodeContent(request);
        if (codeContent == null || codeContent.isBlank()) {
            throw new BizException("提交内容为空，无法开始审查");
        }
        validateSubmittedCode(codeContent, request);

        ReviewSubmitRequest effectiveRequest = normalizeRequest(request, codeContent);
        rememberCodeContext(sessionId, effectiveRequest, codeContent);
        log.info("收到审查请求。reviewId={}, sessionId={}, projectId={}, type={}, language={}, codeLength={}, codePreview={}",
                reviewId,
                sessionId,
                effectiveRequest.projectId(),
                effectiveRequest.type() == null ? ReviewType.PASTE_CODE : effectiveRequest.type(),
                safeLanguage(effectiveRequest.language()),
                codeContent.length(),
                preview(codeContent));

        ReviewTaskDetail detail = new ReviewTaskDetail();
        detail.setReviewId(reviewId);
        detail.setSessionId(sessionId);
        detail.setStatus(ReviewStatus.PROCESSING);
        detail.setCodeContent(codeContent);
        reviewTaskPersistenceRepository.createTask(detail, effectiveRequest);
        chatMemory.append(sessionId, new ChatMessage("user",
                "提交了一次代码审查请求，语言=" + safeLanguage(effectiveRequest.language()) + "，项目ID=" + safeText(effectiveRequest.projectId()),
                System.currentTimeMillis()));

        long taskCancelVersion = currentCancelVersion(sessionId);
        reviewTaskExecutor.execute(() -> executeReview(detail, effectiveRequest, codeContent, taskCancelVersion));
        return new ReviewTaskSummary(reviewId, sessionId, ReviewStatus.PROCESSING);
    }

    public ReviewTaskDetail getById(String reviewId) {
        return reviewTaskPersistenceRepository.findByReviewId(reviewId);
    }

    public void cancelReview(String sessionId) {
        sessionCancelVersions.merge(sessionId, 1L, Long::sum);
        sseEmitterManager.send(sessionId, "cancelled", Map.of("reason", "user_cancelled"));
        reviewTaskPersistenceRepository.markCancelledBySession(sessionId);
        log.info("用户已取消会话审查。sessionId={}", sessionId);
    }

    public List<ReviewTaskDetail> listAllTasks() {
        return new ArrayList<>(reviewTaskPersistenceRepository.findAll());
    }

    private void executeReview(ReviewTaskDetail detail,
                               ReviewSubmitRequest request,
                               String codeContent,
                               long taskCancelVersion) {
        String reviewId = detail.getReviewId();
        String sessionId = detail.getSessionId();
        long start = System.currentTimeMillis();

        waitForSseRegistration(sessionId);

        try {
            throwIfCancelled(sessionId, taskCancelVersion);
            String cacheKey = reviewResultCache.generateCacheKey(codeContent, safeLanguage(request.language()), "v1");
            var cachedReport = reviewResultCache.get(cacheKey);
            if (cachedReport != null) {
                throwIfCancelled(sessionId, taskCancelVersion);
                detail.setReport(cachedReport);
                detail.setStatus(ReviewStatus.COMPLETED);
                reviewTaskPersistenceRepository.saveCompletedTask(detail);
                sseEmitterManager.send(sessionId, "done", Map.of("reviewId", reviewId, "cached", true));
                log.info("命中审查缓存，直接返回。reviewId={}, sessionId={}, costMs={}",
                        reviewId, sessionId, System.currentTimeMillis() - start);
                return;
            }

            List<ChatMessage> recentMessages = chatMemory.recent(sessionId);
            List<String> historicalReviews = reviewKnowledgeBase.search(codeContent, request.projectId(), sessionId, 5)
                    .stream()
                    .map(ReviewRecord::content)
                    .toList();

            ReviewContext context = new ReviewContext(
                    reviewId,
                    sessionId,
                    request.projectId(),
                    safeLanguage(request.language()),
                    request.type() == null ? ReviewType.PASTE_CODE : request.type(),
                    safeText(request.repoUrl()),
                    safeText(request.branch()),
                    codeContent,
                    recentMessages,
                    historicalReviews
            );

            AgentEventListener eventListener = (sid, eventName, data) -> {
                throwIfCancelled(sid, taskCancelVersion);
                sseEmitterManager.send(sid, eventName, data);
            };

            log.info("开始执行AI审查流程。reviewId={}, sessionId={}", reviewId, sessionId);
            FlowResult result = flowAgent.execute(new AgentExecutionContext(sessionId, context), eventListener);
            throwIfCancelled(sessionId, taskCancelVersion);
            result.getReport().setSimilarCodeGroups(codeSimilarityDetector.detect(codeContent));
            detail.setReport(result.getReport());
            detail.setRefactoredCode(result.getRefactoredCode());
            detail.setAgentOutputs(result.getAgentOutputs());
            detail.setStatus(ReviewStatus.COMPLETED);
            reviewTaskPersistenceRepository.saveCompletedTask(detail);

            Map<String, Object> reviewMetadata = new LinkedHashMap<>();
            reviewMetadata.put("agentOutputs", result.getAgentOutputs());
            reviewMetadata.put("score", result.getReport().getScore());
            reviewMetadata.put("totalIssues", result.getReport().getTotalIssues());
            reviewTaskPersistenceRepository.saveReviewHistory(
                    reviewId,
                    sessionId,
                    request.projectId(),
                    result.getSummary(),
                    reviewMetadata
            );

            reviewResultCache.put(cacheKey, result.getReport());
            reviewKnowledgeBase.saveRecord(
                    new ReviewRecord(
                            IdUtils.withPrefix("record"),
                            reviewId,
                            sessionId,
                            result.getSummary(),
                            request.projectId(),
                            System.currentTimeMillis()
                    ),
                    reviewMetadata
            );
            chatMemory.append(sessionId, new ChatMessage("assistant",
                    "代码审查已完成，reviewId=" + reviewId + "，评分=" + result.getReport().getScore()
                            + "，问题数=" + result.getReport().getTotalIssues() + "。总结：" + result.getSummary(),
                    System.currentTimeMillis()));

            log.info("审查任务完成。reviewId={}, sessionId={}, totalIssues={}, score={}, summaryLength={}, costMs={}",
                    reviewId,
                    sessionId,
                    result.getReport().getTotalIssues(),
                    result.getReport().getScore(),
                    result.getSummary() == null ? 0 : result.getSummary().length(),
                    System.currentTimeMillis() - start);
        } catch (CancellationException e) {
            detail.setStatus(ReviewStatus.CANCELLED);
            reviewTaskPersistenceRepository.markCancelledBySession(sessionId);
            sseEmitterManager.send(sessionId, "cancelled", Map.of("reason", "user_cancelled"));
            log.info("审查任务已取消。reviewId={}, sessionId={}, costMs={}",
                    reviewId, sessionId, System.currentTimeMillis() - start);
        } catch (Exception e) {
            detail.setStatus(ReviewStatus.FAILED);
            reviewTaskPersistenceRepository.markFailed(reviewId);
            sseEmitterManager.send(sessionId, "error", Map.of("message", e.getMessage()));
            log.error("审查任务执行失败。reviewId={}, sessionId={}, costMs={}",
                    reviewId, sessionId, System.currentTimeMillis() - start, e);
        }
    }

    private String resolveCodeContent(ReviewSubmitRequest request) {
        ReviewType type = request.type() == null ? ReviewType.PASTE_CODE : request.type();
        if (type == ReviewType.GIT_DIFF) {
            if (request.codeContent() != null && !request.codeContent().isBlank()) {
                return request.codeContent();
            }
            return gitDiffFetcher.fetchDiff(request.repoUrl(), request.branch(), safeLanguage(request.language()));
        }
        return request.codeContent();
    }

    private void validateSubmittedCode(String codeContent, ReviewSubmitRequest request) {
        ReviewType type = request.type() == null ? ReviewType.PASTE_CODE : request.type();
        String trimmed = codeContent.trim();
        if (type == ReviewType.PASTE_CODE) {
            boolean tooShort = trimmed.length() < 3;
            boolean notCodeLike = !trimmed.contains("\n")
                    && !trimmed.contains("{")
                    && !trimmed.contains("}")
                    && !trimmed.contains("(")
                    && !trimmed.contains(")")
                    && !trimmed.contains(";")
                    && !trimmed.contains("=")
                    && !trimmed.contains("class")
                    && !trimmed.contains("public")
                    && !trimmed.contains("private")
                    && !trimmed.contains("return");
            if (tooShort || notCodeLike) {
                throw new BizException("提交内容不像可审查的代码，请提供有效代码片段或切换为 GIT_DIFF 模式。");
            }
        }
        if (type == ReviewType.GIT_DIFF && trimmed.startsWith("未获取到可用的仓库差异")) {
            throw new BizException(trimmed);
        }
    }

    private ReviewSubmitRequest normalizeRequest(ReviewSubmitRequest request, String codeContent) {
        String resolvedLanguage = resolveEffectiveLanguage(request, codeContent);
        return new ReviewSubmitRequest(
                request.type(),
                request.repoUrl(),
                request.branch(),
                request.codeContent(),
                request.projectId(),
                request.sessionId(),
                resolvedLanguage,
                request.templateId()
        );
    }

    private String resolveEffectiveLanguage(ReviewSubmitRequest request, String codeContent) {
        String requestedLanguage = safeLanguage(request.language());
        ReviewType type = request.type() == null ? ReviewType.PASTE_CODE : request.type();
        if (type != ReviewType.GIT_DIFF) {
            return requestedLanguage;
        }
        String inferredLanguage = inferLanguageFromDiff(codeContent);
        if (inferredLanguage.isBlank()) {
            return requestedLanguage;
        }
        String normalizedRequested = requestedLanguage.toLowerCase(Locale.ROOT);
        if (!normalizedRequested.equals(inferredLanguage)
                && ("java".equals(normalizedRequested) || "text".equals(normalizedRequested) || "plain".equals(normalizedRequested))) {
            log.info("Git差异语言自动纠正。requestedLanguage={}, inferredLanguage={}, repoUrl={}",
                    requestedLanguage, inferredLanguage, safeText(request.repoUrl()));
            return inferredLanguage;
        }
        return requestedLanguage;
    }

    private String inferLanguageFromDiff(String diffContent) {
        if (diffContent == null || diffContent.isBlank()) {
            return "";
        }
        Map<String, Integer> languageCounter = new HashMap<>();
        for (String line : diffContent.split("\\R")) {
            if ((!line.startsWith("+++ ") && !line.startsWith("--- ")) || line.contains("/dev/null")) {
                continue;
            }
            int pathIndex = line.indexOf("a/");
            if (pathIndex < 0) {
                pathIndex = line.indexOf("b/");
            }
            if (pathIndex < 0 || pathIndex + 2 >= line.length()) {
                continue;
            }
            String path = line.substring(pathIndex + 2).trim();
            String language = detectLanguageByPath(path);
            if (!language.isBlank()) {
                languageCounter.merge(language, 1, Integer::sum);
            }
        }
        return languageCounter.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse("");
    }

    private String detectLanguageByPath(String path) {
        String normalizedPath = path.toLowerCase(Locale.ROOT);
        if (normalizedPath.endsWith(".py")) {
            return "python";
        }
        if (normalizedPath.endsWith(".java")) {
            return "java";
        }
        if (normalizedPath.endsWith(".ts") || normalizedPath.endsWith(".tsx")) {
            return "typescript";
        }
        if (normalizedPath.endsWith(".js") || normalizedPath.endsWith(".jsx")) {
            return "javascript";
        }
        if (normalizedPath.endsWith(".go")) {
            return "go";
        }
        if (normalizedPath.endsWith(".kt")) {
            return "kotlin";
        }
        if (normalizedPath.endsWith(".rs")) {
            return "rust";
        }
        if (normalizedPath.endsWith(".php")) {
            return "php";
        }
        if (normalizedPath.endsWith(".rb")) {
            return "ruby";
        }
        if (normalizedPath.endsWith(".sql")) {
            return "sql";
        }
        if (normalizedPath.endsWith(".vue")) {
            return "vue";
        }
        if (normalizedPath.endsWith(".yml") || normalizedPath.endsWith(".yaml")) {
            return "yaml";
        }
        if (normalizedPath.endsWith(".xml")) {
            return "xml";
        }
        if (normalizedPath.endsWith(".json")) {
            return "json";
        }
        if (normalizedPath.endsWith(".c") || normalizedPath.endsWith(".cpp")
                || normalizedPath.endsWith(".cc") || normalizedPath.endsWith(".cxx")
                || normalizedPath.endsWith(".h") || normalizedPath.endsWith(".hpp")) {
            return "cpp";
        }
        if (normalizedPath.endsWith(".cs")) {
            return "csharp";
        }
        return "";
    }

    private String safeLanguage(String language) {
        if (language == null || language.isBlank()) {
            return "java";
        }
        return language;
    }

    private String preview(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String normalized = text.replace("\r", " ").replace("\n", " ").trim();
        if (normalized.length() <= LOG_PREVIEW_MAX) {
            return normalized;
        }
        return normalized.substring(0, LOG_PREVIEW_MAX) + "...";
    }

    private String safeText(String text) {
        return text == null ? "" : text;
    }

    private long currentCancelVersion(String sessionId) {
        return sessionCancelVersions.getOrDefault(sessionId, 0L);
    }

    private void throwIfCancelled(String sessionId, long taskCancelVersion) {
        if (currentCancelVersion(sessionId) > taskCancelVersion) {
            throw new CancellationException("用户已取消任务");
        }
    }

    private void waitForSseRegistration(String sessionId) {
        long maxWaitMs = 5000L;
        long waitStart = System.currentTimeMillis();
        try {
            while (!sseEmitterManager.isRegistered(sessionId)
                    && System.currentTimeMillis() - waitStart < maxWaitMs) {
                Thread.sleep(100L);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (!sseEmitterManager.isRegistered(sessionId)) {
            log.warn("SSE等待超时，早期事件可能丢失。maxWaitMs={}, sessionId={}", maxWaitMs, sessionId);
        }
    }

    private void rememberCodeContext(String sessionId, ReviewSubmitRequest request, String codeContent) {
        sessionCodeContextMemory.remember(sessionId, new SessionCodeContext(
                safeText(request.projectId()),
                safeText(request.repoUrl()),
                safeText(request.branch()),
                safeLanguage(request.language()),
                safeText(codeContent),
                System.currentTimeMillis()
        ));
    }
}
