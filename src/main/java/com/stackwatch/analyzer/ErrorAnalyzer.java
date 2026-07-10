package com.stackwatch.analyzer;

import com.stackwatch.config.AnalysisProperties;
import com.stackwatch.domain.AnalysisPath;
import com.stackwatch.domain.AnalysisResult;
import com.stackwatch.domain.ErrorCluster;
import com.stackwatch.domain.ErrorEvent;
import com.stackwatch.domain.ErrorFingerprint;
import com.stackwatch.domain.FewShotSample;
import com.stackwatch.domain.RootCauseAnalysis;
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
import java.util.stream.Collectors;

/**
 * 错误分析器：③分析层核心，三层级联归并。
 *
 * L1 指纹精确命中缓存   -> 0 token
 * L2 向量近似归并       -> 0 token
 * L3 LLM 簇代表根因分析 -> 真正调 LLM（仅占异常总量约 1%）
 *
 * 对应 B1 设计：③分析层 + 置信度兜底（高危点 3）。
 * 横切A：埋点 AnalysisPath / 耗时 / 置信度（对应简历"40%"度量底气）。
 * 横切B：callLlm 注入 few-shot 历史样本（数据飞轮，越用越准）。
 *
 * 注意：Spring AI ChatClient 的 .tools() / .entity() API 签名可能随版本变化，以官方文档为准。
 */
@Service
public class ErrorAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(ErrorAnalyzer.class);

    /** few-shot 历史样本注入条数（消除魔法数字）。 */
    private static final int HISTORICAL_SAMPLE_LIMIT = 3;
    /** 无历史样本时的占位文本（与 root-cause.st 的 historicalSamples 占位兼容）。 */
    private static final String NO_HISTORICAL_SAMPLES = "无历史样本";

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

    public ErrorAnalyzer(Fingerprinter fingerprinter,
                         EmbeddingService embeddingService,
                         FingerprintCache fingerprintCache,
                         ClusterRepository clusterRepository,
                         ChatClient chatClient,
                         AnalysisTools analysisTools,
                         AnalysisProperties properties,
                         PromptTemplateHolder promptTemplate,
                         AnalysisMetrics metrics,
                         FewShotRepository fewShotRepository) {
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
    }

    public AnalysisResult analyze(ErrorEvent event) {
        long start = System.nanoTime();
        ErrorFingerprint fp = fingerprinter.generate(event);

        // L1: 指纹精确命中缓存
        Optional<RootCauseAnalysis> cached = fingerprintCache.lookup(fp.hash());
        if (cached.isPresent()) {
            log.debug("L1 cache hit: {}", fp.hash());
            metrics.recordPath(AnalysisPath.CACHE_HIT, event.appName());
            metrics.recordDuration(System.nanoTime() - start, AnalysisPath.CACHE_HIT);
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
                metrics.recordPath(AnalysisPath.VECTOR_MERGED, event.appName());
                metrics.recordDuration(System.nanoTime() - start, AnalysisPath.VECTOR_MERGED);
                return AnalysisResult.vectorMerged(fp.hash(), updated.clusterId(), updated.analysis());
            }
        }

        // L3: 新簇，调 LLM 分析
        RootCauseAnalysis raw = callLlm(event, fp);
        RootCauseAnalysis analyzed = postProcess(raw);
        metrics.recordConfidence(analyzed.confidence());
        String clusterId = "cluster-" + UUID.randomUUID();
        ErrorCluster newCluster = ErrorCluster.newOne(
            clusterId, event.appName(), event.exceptionType(),
            fp.hash(), event.occurredAt(), analyzed, vec);
        clusterRepository.save(newCluster);
        fingerprintCache.put(fp.hash(), analyzed);
        log.info("L3 llm new: {} -> cluster {} (confidence={}, review={})",
            fp.hash(), clusterId, analyzed.confidence(), analyzed.needHumanReview());
        metrics.recordPath(AnalysisPath.LLM_NEW, event.appName());
        metrics.recordDuration(System.nanoTime() - start, AnalysisPath.LLM_NEW);
        return AnalysisResult.llmNew(fp.hash(), clusterId, analyzed);
    }

    private RootCauseAnalysis callLlm(ErrorEvent event, ErrorFingerprint fp) {
        // 横切B：注入 few-shot 历史已确认样本，提升根因准确率（数据飞轮）
        List<FewShotSample> samples = fewShotRepository.findByExceptionType(
            event.exceptionType(), HISTORICAL_SAMPLE_LIMIT);
        String historicalSamples = samples.isEmpty()
            ? NO_HISTORICAL_SAMPLES
            : samples.stream()
                .map(s -> "- 堆栈摘要: " + s.stackText() + "\n  正确根因: " + s.correctRootCause())
                .collect(Collectors.joining("\n"));

        Map<String, Object> vars = new HashMap<>();
        vars.put("appName", event.appName());
        vars.put("env", event.env() == null ? "unknown" : event.env());
        vars.put("occurredAt", String.valueOf(event.occurredAt()));
        vars.put("exceptionType", event.exceptionType());
        vars.put("exceptionMessage", event.exceptionMessage());
        vars.put("stackFrames", String.join("\n", fp.topFrames()));
        vars.put("mdc", String.valueOf(event.mdc()));
        vars.put("historicalSamples", historicalSamples);

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
     * 置信度兜底 + 规则双判（B1 高危点 3）。
     * LLM 自评置信度 < 阈值 或 无证据 或 LLM 调用失败 -> 转人工。
     */
    private RootCauseAnalysis postProcess(RootCauseAnalysis raw) {
        if (raw == null) {
            return new RootCauseAnalysis(
                "LLM 未返回有效结果", "UNKNOWN", "HIGH", 0.0,
                "建议人工排查", List.of(), true
            );
        }
        boolean lowConfidence = raw.confidence() < properties.confidenceThreshold();
        boolean noEvidence = raw.evidence() == null || raw.evidence().isEmpty();
        return (lowConfidence || noEvidence) ? raw.withReviewFlag(true) : raw;
    }
}
