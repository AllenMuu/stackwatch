package com.stackwatch.analyzer;

import com.stackwatch.config.AnalysisProperties;
import com.stackwatch.domain.AnalysisPath;
import com.stackwatch.domain.AnalysisResult;
import com.stackwatch.domain.AntiPattern;
import com.stackwatch.domain.ErrorCluster;
import com.stackwatch.domain.ErrorEvent;
import com.stackwatch.domain.ErrorFingerprint;
import com.stackwatch.domain.FewShotSample;
import com.stackwatch.domain.ReviewLevel;
import com.stackwatch.domain.RootCauseAnalysis;
import com.stackwatch.feedback.AntiPatternRepository;
import com.stackwatch.feedback.FewShotRepository;
import com.stackwatch.metrics.AnalysisMetrics;
import com.stackwatch.preprocess.EmbeddingService;
import com.stackwatch.preprocess.Fingerprinter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 错误分析器：③分析层核心，三层级联归并。
 *
 * L1 指纹精确命中缓存   -> 0 token
 * L2 向量近似归并       -> 0 token
 * L3 LLM 簇代表根因分析 -> 真正调 LLM（仅占异常总量约 1%）
 *
 * 对应 B1 设计：③分析层 + 置信度兜底（高危点 3）。
 * 横切A：埋点 AnalysisPath / 耗时 / 置信度 / 复核级别（支撑"40%"度量底气）。
 * 横切B：callLlm 注入 few-shot 正样本（「该这么答」）+ anti-pattern 负样本（「别那么答」），
 *        正负双向数据飞轮，越用越准。
 *
 * 复核级别三档门控（借鉴 PagePilot 置信度分档思想）：
 * 高置信 {@link ReviewLevel#AUTO_CONFIRMED} / 中置信 {@link ReviewLevel#NEEDS_CONFIRMATION}
 * / 低置信或无证据 {@link ReviewLevel#NEEDS_HUMAN_REVIEW}。
 *
 * 注意：Spring AI ChatClient 的 .tools() / .entity()API 签名可能随版本变化，以官方文档为准。
 */
@Service
public class ErrorAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(ErrorAnalyzer.class);

    /** few-shot 历史样本注入条数（消除魔法数字）。 */
    private static final int HISTORICAL_SAMPLE_LIMIT = 3;
    /** 无历史样本时的占位文本（与 root-cause.st 的 historicalSamples 占位兼容）。 */
    private static final String NO_HISTORICAL_SAMPLES = "无历史样本";
    /** 无已知误判模式时的占位文本（与 root-cause.st 的 antiPatterns 占位兼容）。 */
    private static final String NO_ANTI_PATTERNS = "无已知易错模式";
    /** anti-pattern 负样本注入条数（消除魔法数字）。 */
    private static final int ANTI_PATTERN_LIMIT = 3;

    private final Fingerprinter fingerprinter;
    private final EmbeddingService embeddingService;
    private final FingerprintCache fingerprintCache;
    private final ClusterRepository clusterRepository;
    private final ChatClient chatClient;
    private final AnalysisTools analysisTools;
    private final AnalysisProperties properties;
    private final PromptTemplateHolder promptTemplate;
    private final AnalysisMetrics metrics;
    private final FewShotRepository fewShotRepository;
    private final AntiPatternRepository antiPatternRepository;

    public ErrorAnalyzer(Fingerprinter fingerprinter,
                         EmbeddingService embeddingService,
                         FingerprintCache fingerprintCache,
                         ClusterRepository clusterRepository,
                         ChatClient chatClient,
                         AnalysisTools analysisTools,
                         AnalysisProperties properties,
                         PromptTemplateHolder promptTemplate,
                         AnalysisMetrics metrics,
                         FewShotRepository fewShotRepository,
                         AntiPatternRepository antiPatternRepository) {
        this.fingerprinter = fingerprinter;
        this.embeddingService = embeddingService;
        this.fingerprintCache = fingerprintCache;
        this.clusterRepository = clusterRepository;
        this.chatClient = chatClient;
        this.analysisTools = analysisTools;
        this.properties = properties;
        this.promptTemplate = promptTemplate;
        this.metrics = metrics;
        this.fewShotRepository = fewShotRepository;
        this.antiPatternRepository = antiPatternRepository;
    }

    public AnalysisResult analyze(ErrorEvent event) {
        long start = System.nanoTime();
        ErrorFingerprint fp = fingerprinter.generate(event);

        // L1: 指纹精确命中缓存
        Optional<RootCauseAnalysis> cached = fingerprintCache.lookup(fp.hash());
        if (cached.isPresent()) {
            log.debug("L1 cache hit: {}", fp.hash());
            metrics.recordAnalysis(AnalysisPath.CACHE_HIT, event.appName(), System.nanoTime() - start);
            return AnalysisResult.cacheHit(fp.hash(), cached.get());
        }

        // L2: 向量近似归并
        float[] vec = embeddingService.embed(event);
        if (vec != null) {
            Optional<ErrorCluster> matched = clusterRepository.findSimilar(vec, properties.similarityThreshold());
            if (matched.isPresent()) {
                ErrorCluster updated = matched.get().increment(event.occurredAt());
                clusterRepository.save(updated);
                fingerprintCache.put(fp.hash(), updated.analysis());
                log.debug("L2 vector merged: {} -> cluster {}", fp.hash(), updated.clusterId());
                metrics.recordAnalysis(AnalysisPath.VECTOR_MERGED, event.appName(), System.nanoTime() - start);
                return AnalysisResult.vectorMerged(fp.hash(), updated.clusterId(), updated.analysis());
            }
        }

        // L3: 新簇，调 LLM 分析
        RootCauseAnalysis raw = callLlm(event, fp);
        ReviewLevel reviewLevel = classifyReview(raw);
        RootCauseAnalysis analyzed = applyReview(raw, reviewLevel);
        metrics.recordConfidence(analyzed.confidence());
        metrics.recordReviewLevel(reviewLevel);
        String clusterId = "cluster-" + UUID.randomUUID();
        ErrorCluster newCluster = ErrorCluster.newOne(
            clusterId, event.appName(), event.exceptionType(),
            fp.hash(), event.occurredAt(), analyzed, vec);
        clusterRepository.save(newCluster);
        fingerprintCache.put(fp.hash(), analyzed);
        log.info("L3 llm new: {} -> cluster {} (confidence={}, reviewLevel={})",
            fp.hash(), clusterId, analyzed.confidence(), reviewLevel);
        metrics.recordAnalysis(AnalysisPath.LLM_NEW, event.appName(), System.nanoTime() - start);
        return AnalysisResult.llmNew(fp.hash(), clusterId, analyzed, reviewLevel);
    }

    private RootCauseAnalysis callLlm(ErrorEvent event, ErrorFingerprint fp) {
        // 横切B：注入 few-shot 历史已确认正样本，提升根因准确率（正样本飞轮）
        List<FewShotSample> samples = fewShotRepository.findByExceptionType(
            event.exceptionType(), HISTORICAL_SAMPLE_LIMIT);
        String historicalSamples = samples.isEmpty()
            ? NO_HISTORICAL_SAMPLES
            : String.join("\n", samples.stream()
                .map(s -> "- 堆栈摘要: " + s.stackText() + "\n  正确根因: " + s.correctRootCause())
                .toList());

        // 横切B：注入 anti-pattern 历史误判模式负样本，警示 LLM「别这么答」（负样本飞轮，与 few-shot 对偶）
        List<AntiPattern> antiPatternSamples = antiPatternRepository.findByExceptionType(
            event.exceptionType(), ANTI_PATTERN_LIMIT);
        String antiPatterns = antiPatternSamples.isEmpty()
            ? NO_ANTI_PATTERNS
            : String.join("\n", antiPatternSamples.stream()
                .map(a -> "- 堆栈摘要: " + a.stackText() + "\n  易误判为: " + a.wrongRootCause()
                    + "\n  实际是: " + a.correctRootCause())
                .toList());

        Map<String, Object> vars = new HashMap<>();
        vars.put("appName", event.appName());
        vars.put("env", event.env() == null ? "unknown" : event.env());
        vars.put("occurredAt", String.valueOf(event.occurredAt()));
        vars.put("exceptionType", event.exceptionType());
        vars.put("exceptionMessage", event.exceptionMessage());
        vars.put("stackFrames", String.join("\n", fp.topFrames()));
        vars.put("mdc", String.valueOf(event.mdc()));
        vars.put("historicalSamples", historicalSamples);
        vars.put("antiPatterns", antiPatterns);

        String promptText = promptTemplate.render(vars);
        try {
            return chatClient.prompt()
                .user(promptText)
                .tools(analysisTools)
                .call()
                .entity(RootCauseAnalysis.class);
        } catch (Exception e) {
            log.warn("LLM call failed for {}: {}", fp.hash(), e.getMessage());
            return null;
        }
    }

    /**
     * 复核级别三档分类（借鉴 PagePilot 置信度分档门控）。
     * 基于 LLM 自评置信度 + 证据校验：
     * <ul>
     *   <li>raw 为 null（LLM 失败）/ 无证据 / 置信度 &lt; 兜底阈值 -> {@link ReviewLevel#NEEDS_HUMAN_REVIEW}；</li>
     *   <li>兜底阈值 &le; 置信度 &lt; 高阈值 -> {@link ReviewLevel#NEEDS_CONFIRMATION}（带存疑求确认）；</li>
     *   <li>置信度 &ge; 高阈值 -> {@link ReviewLevel#AUTO_CONFIRMED}。</li>
     * </ul>
     * 这是 B1 高危点 3「置信度兜底」的细化：从二元「执行/转人工」升级为三档，
     * 中档不再直接转人工，而是输出根因 + 标记待确认，驱动飞书卡片低介入确认回流 few-shot。
     */
    private ReviewLevel classifyReview(RootCauseAnalysis raw) {
        if (raw == null) {
            return ReviewLevel.NEEDS_HUMAN_REVIEW;
        }
        if (raw.evidence() == null || raw.evidence().isEmpty()) {
            return ReviewLevel.NEEDS_HUMAN_REVIEW;
        }
        if (raw.confidence() < properties.confidenceThreshold()) {
            return ReviewLevel.NEEDS_HUMAN_REVIEW;
        }
        if (raw.confidence() < properties.confidenceHighThreshold()) {
            return ReviewLevel.NEEDS_CONFIRMATION;
        }
        return ReviewLevel.AUTO_CONFIRMED;
    }

    /**
     * 按复核级别设置 {@code needHumanReview} 标志（保持不可变）。
     * {@link ReviewLevel#AUTO_CONFIRMED} -> false；其余两档 -> true。
     * raw 为 null（LLM 失败）时返回 UNKNOWN 兜底根因，对应 {@link ReviewLevel#NEEDS_HUMAN_REVIEW}。
     */
    private RootCauseAnalysis applyReview(RootCauseAnalysis raw, ReviewLevel level) {
        if (raw == null) {
            return new RootCauseAnalysis(
                "LLM 未返回有效结果", "UNKNOWN", "HIGH", 0.0,
                "建议人工排查", List.of(), true
            );
        }
        boolean needReview = level != ReviewLevel.AUTO_CONFIRMED;
        return raw.needHumanReview() == needReview ? raw : raw.withReviewFlag(needReview);
    }
}
