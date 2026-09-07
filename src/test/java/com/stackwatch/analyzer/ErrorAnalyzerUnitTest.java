package com.stackwatch.analyzer;

import com.stackwatch.config.AnalysisProperties;
import com.stackwatch.config.ContextOptimizerProperties;
import com.stackwatch.domain.AnalysisPath;
import com.stackwatch.domain.AnalysisResult;
import com.stackwatch.domain.AntiPattern;
import com.stackwatch.domain.ErrorCluster;
import com.stackwatch.domain.ErrorEvent;
import com.stackwatch.domain.ErrorFingerprint;
import com.stackwatch.domain.RootCauseAnalysis;
import com.stackwatch.domain.ReviewLevel;
import com.stackwatch.domain.ThrowableInfo;
import com.stackwatch.feedback.AntiPatternRepository;
import com.stackwatch.feedback.FewShotRepository;
import com.stackwatch.history.ErrorGroup;
import com.stackwatch.history.ErrorGroupKey;
import com.stackwatch.history.ErrorGroupRepository;
import com.stackwatch.history.RecordOccurrenceCommand;
import com.stackwatch.history.RecordOccurrenceResult;
import com.stackwatch.metrics.AnalysisMetrics;
import com.stackwatch.preprocess.EmbeddingService;
import com.stackwatch.preprocess.ErrorNormalizer;
import com.stackwatch.preprocess.Fingerprinter;
import com.stackwatch.preprocess.NormalizedError;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;

/**
 * ErrorAnalyzer 纯单元测试：不依赖 LLM API Key，CI 可跑。
 * 对应 ③分析层 L1/L2/L3 三层归并核心逻辑 + 置信度兜底 + LLM 异常兜底。
 *
 * 真实依赖（无外部 IO，可直接 new）：Fingerprinter / AnalysisProperties / PromptTemplateHolder
 * Mock 依赖：EmbeddingService / FingerprintCache / ClusterRepository / AnalysisTools /
 *           AnalysisMetrics / FewShotRepository / ChatClient 链式 mock
 */
class ErrorAnalyzerUnitTest {

    private static final double SIMILARITY_THRESHOLD = 0.92;
    private static final double CONFIDENCE_THRESHOLD = 0.6;
    private static final double CONFIDENCE_HIGH_THRESHOLD = 0.9;
    private static final int FINGERPRINT_TOP_N = 5;

    private EmbeddingService embeddingService;
    private FingerprintCache fingerprintCache;
    private ClusterRepository clusterRepository;
    private ChatClient chatClient;
    private AnalysisTools analysisTools;
    private AntiPatternRepository antiPatternRepository;

    private ChatClient.ChatClientRequestSpec requestSpec;
    private ChatClient.CallResponseSpec callSpec;

    private ErrorAnalyzer errorAnalyzer;

    @BeforeEach
    void setUp() throws IOException {
        embeddingService = mock(EmbeddingService.class);
        fingerprintCache = mock(FingerprintCache.class);
        clusterRepository = mock(ClusterRepository.class);
        chatClient = mock(ChatClient.class);
        analysisTools = mock(AnalysisTools.class);
        AnalysisMetrics metrics = mock(AnalysisMetrics.class);
        FewShotRepository fewShotRepository = mock(FewShotRepository.class);
        when(fewShotRepository.findByExceptionType(anyString(), anyInt())).thenReturn(List.of());
        antiPatternRepository = mock(AntiPatternRepository.class);
        when(antiPatternRepository.findByExceptionType(anyString(), anyInt())).thenReturn(List.of());

        // ChatClient 链式 mock：prompt -> user -> tools -> call -> entity
        requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        callSpec = mock(ChatClient.CallResponseSpec.class);
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.tools(any(Object[].class))).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callSpec);

        Fingerprinter fingerprinter = new Fingerprinter(FINGERPRINT_TOP_N);
        AnalysisProperties properties = new AnalysisProperties(
            SIMILARITY_THRESHOLD, CONFIDENCE_THRESHOLD, CONFIDENCE_HIGH_THRESHOLD, FINGERPRINT_TOP_N);
        PromptTemplateHolder promptTemplate = new PromptTemplateHolder(
            new ClassPathResource("prompts/root-cause.st"));

        ContextOptimizer contextOptimizer = new ContextOptimizer(
            new ContextOptimizerProperties(65536));
        errorAnalyzer = new ErrorAnalyzer(
            fingerprinter, embeddingService, fingerprintCache,
            clusterRepository, chatClient, analysisTools,
            properties, promptTemplate, metrics, fewShotRepository, antiPatternRepository,
            contextOptimizer);
    }

    @Test
    void l1CacheHitSkipsLlmAndEmbedding() {
        RootCauseAnalysis cached = highConfidenceRca();
        when(fingerprintCache.lookup(anyString())).thenReturn(Optional.of(cached));

        AnalysisResult result = errorAnalyzer.analyze(npeEvent());

        assertEquals(AnalysisPath.CACHE_HIT, result.path());
        assertEquals(cached, result.analysis());
        assertNull(result.clusterId(), "L1 命中不产生新簇 ID");
        verify(chatClient, never()).prompt();
        verify(embeddingService, never()).embed(any());
        verify(clusterRepository, never()).findSimilar(any(float[].class), anyDouble());
    }

    @Test
    void l2VectorMergeSkipsLlm() {
        float[] vec = {0.1f, 0.2f, 0.3f};
        when(fingerprintCache.lookup(anyString())).thenReturn(Optional.empty());
        when(embeddingService.embed(any())).thenReturn(vec);
        RootCauseAnalysis clusterAnalysis = highConfidenceRca();
        ErrorCluster existing = ErrorCluster.newOne(
            "cluster-existing", "order-service", "NullPointerException",
            "rep-hash", Instant.parse("2026-07-08T08:00:00Z"),
            clusterAnalysis, vec);
        when(clusterRepository.findSimilar(any(float[].class), anyDouble()))
            .thenReturn(Optional.of(existing));

        AnalysisResult result = errorAnalyzer.analyze(npeEvent());

        assertEquals(AnalysisPath.VECTOR_MERGED, result.path());
        assertEquals("cluster-existing", result.clusterId());
        assertEquals(clusterAnalysis, result.analysis());
        verify(chatClient, never()).prompt();
        verify(clusterRepository).save(any(ErrorCluster.class));
        verify(fingerprintCache).put(anyString(), eq(clusterAnalysis));
    }

    @Test
    void l1AndL2MissTriggersLlmNewClusterWithHighConfidence() {
        float[] vec = {0.1f, 0.2f};
        when(fingerprintCache.lookup(anyString())).thenReturn(Optional.empty());
        when(embeddingService.embed(any())).thenReturn(vec);
        when(clusterRepository.findSimilar(any(float[].class), anyDouble()))
            .thenReturn(Optional.empty());
        RootCauseAnalysis llmRca = highConfidenceRca();
        when(callSpec.entity(RootCauseAnalysis.class)).thenReturn(llmRca);

        AnalysisResult result = errorAnalyzer.analyze(npeEvent());

        assertEquals(AnalysisPath.LLM_NEW, result.path());
        assertNotNull(result.clusterId());
        assertTrue(result.clusterId().startsWith("cluster-"));
        assertEquals(0.9, result.analysis().confidence());
        assertFalse(result.analysis().needHumanReview());
        verify(chatClient).prompt();
        verify(clusterRepository).save(any(ErrorCluster.class));
        verify(fingerprintCache).put(anyString(), eq(llmRca));
    }

    @Test
    void llmLowConfidenceTriggersHumanReview() {
        float[] vec = {0.1f};
        when(fingerprintCache.lookup(anyString())).thenReturn(Optional.empty());
        when(embeddingService.embed(any())).thenReturn(vec);
        when(clusterRepository.findSimilar(any(float[].class), anyDouble()))
            .thenReturn(Optional.empty());
        RootCauseAnalysis lowConfRca = new RootCauseAnalysis(
            "不确定的根因", "UNKNOWN", "MEDIUM", 0.3,
            "需进一步排查", List.of("OrderService.java:42"), false);
        when(callSpec.entity(RootCauseAnalysis.class)).thenReturn(lowConfRca);

        AnalysisResult result = errorAnalyzer.analyze(npeEvent());

        assertEquals(AnalysisPath.LLM_NEW, result.path());
        assertEquals(0.3, result.analysis().confidence());
        assertTrue(result.analysis().needHumanReview());
    }

    @Test
    void llmExceptionReturnsFallbackAnalysis() {
        float[] vec = {0.1f};
        when(fingerprintCache.lookup(anyString())).thenReturn(Optional.empty());
        when(embeddingService.embed(any())).thenReturn(vec);
        when(clusterRepository.findSimilar(any(float[].class), anyDouble()))
            .thenReturn(Optional.empty());
        when(callSpec.entity(RootCauseAnalysis.class))
            .thenThrow(new RuntimeException("LLM unavailable"));

        AnalysisResult result = errorAnalyzer.analyze(npeEvent());

        assertEquals(AnalysisPath.LLM_NEW, result.path());
        assertNotNull(result.analysis());
        assertEquals("LLM 未返回有效结果", result.analysis().rootCause());
        assertEquals("UNKNOWN", result.analysis().category());
        assertEquals(0.0, result.analysis().confidence());
        assertTrue(result.analysis().needHumanReview());
        verify(clusterRepository).save(any(ErrorCluster.class));
        verify(fingerprintCache).put(anyString(), any());
    }

    // ===== 复核级别三档门控测试（借鉴 PagePilot 置信度分档） =====

    @Test
    void l3HighConfidenceClassifiedAutoConfirmed() {
        float[] vec = {0.1f, 0.2f};
        when(fingerprintCache.lookup(anyString())).thenReturn(Optional.empty());
        when(embeddingService.embed(any())).thenReturn(vec);
        when(clusterRepository.findSimilar(any(float[].class), anyDouble()))
            .thenReturn(Optional.empty());
        when(callSpec.entity(RootCauseAnalysis.class)).thenReturn(highConfidenceRca());

        AnalysisResult result = errorAnalyzer.analyze(npeEvent());

        assertEquals(AnalysisPath.LLM_NEW, result.path());
        assertEquals(ReviewLevel.AUTO_CONFIRMED, result.reviewLevel());
        assertFalse(result.analysis().needHumanReview());
    }

    @Test
    void l3MidConfidenceClassifiedNeedsConfirmation() {
        float[] vec = {0.1f};
        when(fingerprintCache.lookup(anyString())).thenReturn(Optional.empty());
        when(embeddingService.embed(any())).thenReturn(vec);
        when(clusterRepository.findSimilar(any(float[].class), anyDouble()))
            .thenReturn(Optional.empty());
        RootCauseAnalysis midConfRca = new RootCauseAnalysis(
            "可能的根因", "空指针", "MEDIUM", 0.7,
            "建议加空判断", List.of("OrderService.java:42"), false);
        when(callSpec.entity(RootCauseAnalysis.class)).thenReturn(midConfRca);

        AnalysisResult result = errorAnalyzer.analyze(npeEvent());

        assertEquals(AnalysisPath.LLM_NEW, result.path());
        assertEquals(ReviewLevel.NEEDS_CONFIRMATION, result.reviewLevel());
        assertTrue(result.analysis().needHumanReview());
    }

    @Test
    void l3LowConfidenceClassifiedNeedsHumanReview() {
        float[] vec = {0.1f};
        when(fingerprintCache.lookup(anyString())).thenReturn(Optional.empty());
        when(embeddingService.embed(any())).thenReturn(vec);
        when(clusterRepository.findSimilar(any(float[].class), anyDouble()))
            .thenReturn(Optional.empty());
        RootCauseAnalysis lowConfRca = new RootCauseAnalysis(
            "不确定的根因", "UNKNOWN", "MEDIUM", 0.3,
            "需进一步排查", List.of("OrderService.java:42"), false);
        when(callSpec.entity(RootCauseAnalysis.class)).thenReturn(lowConfRca);

        AnalysisResult result = errorAnalyzer.analyze(npeEvent());

        assertEquals(AnalysisPath.LLM_NEW, result.path());
        assertEquals(ReviewLevel.NEEDS_HUMAN_REVIEW, result.reviewLevel());
        assertTrue(result.analysis().needHumanReview());
    }

    @Test
    void l3HighConfidenceWithoutEvidenceClassifiedNeedsHumanReview() {
        float[] vec = {0.1f};
        when(fingerprintCache.lookup(anyString())).thenReturn(Optional.empty());
        when(embeddingService.embed(any())).thenReturn(vec);
        when(clusterRepository.findSimilar(any(float[].class), anyDouble()))
            .thenReturn(Optional.empty());
        RootCauseAnalysis noEvidenceRca = new RootCauseAnalysis(
            "猜测的根因", "空指针", "HIGH", 0.95,
            "建议排查", List.of(), false);
        when(callSpec.entity(RootCauseAnalysis.class)).thenReturn(noEvidenceRca);

        AnalysisResult result = errorAnalyzer.analyze(npeEvent());

        assertEquals(AnalysisPath.LLM_NEW, result.path());
        assertEquals(ReviewLevel.NEEDS_HUMAN_REVIEW, result.reviewLevel());
        assertTrue(result.analysis().needHumanReview());
    }

    @Test
    void l3LlmFailureClassifiedNeedsHumanReview() {
        float[] vec = {0.1f};
        when(fingerprintCache.lookup(anyString())).thenReturn(Optional.empty());
        when(embeddingService.embed(any())).thenReturn(vec);
        when(clusterRepository.findSimilar(any(float[].class), anyDouble()))
            .thenReturn(Optional.empty());
        when(callSpec.entity(RootCauseAnalysis.class))
            .thenThrow(new RuntimeException("LLM unavailable"));

        AnalysisResult result = errorAnalyzer.analyze(npeEvent());

        assertEquals(AnalysisPath.LLM_NEW, result.path());
        assertEquals(ReviewLevel.NEEDS_HUMAN_REVIEW, result.reviewLevel());
        assertEquals(0.0, result.analysis().confidence());
        assertTrue(result.analysis().needHumanReview());
    }

    @Test
    void l1CacheHitInheritsAutoConfirmedFromHistory() {
        RootCauseAnalysis cached = highConfidenceRca();
        when(fingerprintCache.lookup(anyString())).thenReturn(Optional.of(cached));

        AnalysisResult result = errorAnalyzer.analyze(npeEvent());

        assertEquals(AnalysisPath.CACHE_HIT, result.path());
        assertEquals(ReviewLevel.AUTO_CONFIRMED, result.reviewLevel());
    }

    @Test
    void l1CacheHitWithReviewFlagInheritsNeedsHumanReview() {
        RootCauseAnalysis cached = new RootCauseAnalysis(
            "历史低置信根因", "UNKNOWN", "HIGH", 0.2,
            "建议人工排查", List.of(), true);
        when(fingerprintCache.lookup(anyString())).thenReturn(Optional.of(cached));

        AnalysisResult result = errorAnalyzer.analyze(npeEvent());

        assertEquals(AnalysisPath.CACHE_HIT, result.path());
        assertEquals(ReviewLevel.NEEDS_HUMAN_REVIEW, result.reviewLevel());
    }

    @Test
    void l3InjectsAntiPatternsIntoPromptWhenAvailable() {
        float[] vec = {0.1f};
        when(fingerprintCache.lookup(anyString())).thenReturn(Optional.empty());
        when(embeddingService.embed(any())).thenReturn(vec);
        when(clusterRepository.findSimilar(any(float[].class), anyDouble()))
            .thenReturn(Optional.empty());
        AntiPattern ap = new AntiPattern(
            "ap-1", "cluster-x", "NullPointerException",
            "OrderService.process 调 length()", "误判为配置缺失", "实际是 order 字段未初始化",
            Instant.parse("2026-07-10T08:00:00Z"));
        when(antiPatternRepository.findByExceptionType(eq("NullPointerException"), anyInt()))
            .thenReturn(List.of(ap));
        when(callSpec.entity(RootCauseAnalysis.class)).thenReturn(highConfidenceRca());

        errorAnalyzer.analyze(npeEvent());

        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(requestSpec).user(promptCaptor.capture());
        String promptText = promptCaptor.getValue();
        assertTrue(promptText.contains("误判为配置缺失"), "prompt 应包含 anti-pattern 的误判根因警示");
        assertTrue(promptText.contains("实际是 order 字段未初始化"), "prompt 应包含 anti-pattern 的正确根因");
    }

    @Test
    void typedCacheHitRecordsOccurrenceBeforeReturningRca() {
        ErrorNormalizer normalizer = mock(ErrorNormalizer.class);
        NormalizedError normalized = normalizedError("Order 981273 not found");
        when(normalizer.normalize(any())).thenReturn(normalized);
        ErrorGroupRepository history = mock(ErrorGroupRepository.class);
        ErrorFingerprint fingerprint = v2Fingerprint(normalized);
        ErrorGroupKey key = new ErrorGroupKey("order-service", fingerprint.version(), fingerprint.hash());
        ErrorGroup group = historyGroup(key, fingerprint, normalized, highConfidenceRca());
        ErrorGroup recordedGroup = group.acceptedAt(npeEvent().occurredAt());
        when(fingerprintCache.lookup(key)).thenReturn(Optional.of(group));
        when(history.record(any(RecordOccurrenceCommand.class)))
            .thenReturn(new RecordOccurrenceResult(recordedGroup, true));
        AnalysisMetrics historyMetrics = mock(AnalysisMetrics.class);

        AnalysisResult result = historyAnalyzer(normalizer, history, historyMetrics).analyze(npeEvent());

        assertEquals(AnalysisPath.CACHE_HIT, result.path());
        assertEquals(recordedGroup.analysis(), result.analysis());
        assertEquals(recordedGroup.clusterId(), result.clusterId());
        verify(history).record(argThat(command -> "test-1".equals(command.eventId())));
        verify(fingerprintCache).put(eq(key), eq(recordedGroup));
        verify(history, never()).findExact(any(ErrorGroupKey.class));
        verify(chatClient, never()).prompt();
        verify(embeddingService, never()).embed(any());
        verify(normalizer, times(1)).normalize(any());
    }

    @Test
    void repositoryHitWarmsTypedCacheAfterOccurrenceRecording() {
        ErrorNormalizer normalizer = mock(ErrorNormalizer.class);
        NormalizedError normalized = normalizedError("Order 981273 not found");
        when(normalizer.normalize(any())).thenReturn(normalized);
        ErrorGroupRepository history = mock(ErrorGroupRepository.class);
        ErrorFingerprint fingerprint = v2Fingerprint(normalized);
        ErrorGroupKey key = new ErrorGroupKey("order-service", fingerprint.version(), fingerprint.hash());
        ErrorGroup group = historyGroup(key, fingerprint, normalized, highConfidenceRca());
        when(fingerprintCache.lookup(key)).thenReturn(Optional.empty());
        when(history.findExact(key)).thenReturn(Optional.of(group));
        ErrorGroup recordedGroup = group.acceptedAt(npeEvent().occurredAt());
        when(history.record(any(RecordOccurrenceCommand.class)))
            .thenReturn(new RecordOccurrenceResult(recordedGroup, true));

        AnalysisResult result = historyAnalyzer(normalizer, history, mock(AnalysisMetrics.class))
            .analyze(npeEvent());

        assertEquals(AnalysisPath.CACHE_HIT, result.path());
        assertEquals(fingerprint.hash(), result.fingerprintHash());
        verify(history).record(any(RecordOccurrenceCommand.class));
        verify(fingerprintCache).put(eq(key), eq(recordedGroup));
        verify(chatClient, never()).prompt();
    }

    @Test
    void v1HistoryHitIsReadOnlyCompatibilityAndNotRewrittenAsV2() {
        ErrorNormalizer normalizer = mock(ErrorNormalizer.class);
        NormalizedError normalized = normalizedError("Order 981273 not found");
        when(normalizer.normalize(any())).thenReturn(normalized);
        ErrorGroupRepository history = mock(ErrorGroupRepository.class);
        Fingerprinter fingerprinter = new Fingerprinter(FINGERPRINT_TOP_N);
        ErrorFingerprint v1 = fingerprinter.generate(npeEvent());
        ErrorGroupKey v1Key = new ErrorGroupKey("order-service", v1.version(), v1.hash());
        ErrorGroup group = historyGroup(v1Key, v1, normalized, highConfidenceRca());
        when(fingerprintCache.lookup(any(ErrorGroupKey.class))).thenReturn(Optional.empty());
        when(history.findExact(any(ErrorGroupKey.class))).thenReturn(Optional.empty());
        when(history.findExact(v1Key)).thenReturn(Optional.of(group));
        when(history.record(any(RecordOccurrenceCommand.class)))
            .thenReturn(new RecordOccurrenceResult(group.acceptedAt(npeEvent().occurredAt()), true));

        AnalysisResult result = historyAnalyzer(normalizer, history, mock(AnalysisMetrics.class))
            .analyze(npeEvent());

        assertEquals(AnalysisPath.CACHE_HIT, result.path());
        assertEquals(v1.hash(), result.fingerprintHash());
        verify(fingerprintCache).put(eq(v1Key), any(ErrorGroup.class));
        verify(fingerprintCache, never()).put(
            argThat(cacheKey -> cacheKey.fingerprintVersion().name().equals("V2")), any(ErrorGroup.class));
        verify(chatClient, never()).prompt();
    }

    @Test
    void l2NewGroupIsPersistedAndCacheWarmedWithV2Target() {
        ErrorNormalizer normalizer = mock(ErrorNormalizer.class);
        NormalizedError normalized = normalizedError("Order 981273 not found");
        when(normalizer.normalize(any())).thenReturn(normalized);
        ErrorGroupRepository history = mock(ErrorGroupRepository.class);
        when(fingerprintCache.lookup(any(ErrorGroupKey.class))).thenReturn(Optional.empty());
        when(history.findExact(any(ErrorGroupKey.class))).thenReturn(Optional.empty());
        when(history.record(any(RecordOccurrenceCommand.class))).thenAnswer(invocation -> {
            RecordOccurrenceCommand command = invocation.getArgument(0);
            return new RecordOccurrenceResult(command.group().acceptedAt(command.occurredAt()), true);
        });
        float[] vec = {0.1f, 0.2f};
        when(embeddingService.embed(any())).thenReturn(vec);
        ErrorCluster existing = ErrorCluster.newOne(
            "cluster-existing", "order-service", "NullPointerException", "rep-hash",
            Instant.parse("2026-07-08T08:00:00Z"), highConfidenceRca(), vec);
        when(clusterRepository.findSimilar(any(float[].class), anyDouble()))
            .thenReturn(Optional.of(existing));

        AnalysisResult result = historyAnalyzer(normalizer, history, mock(AnalysisMetrics.class))
            .analyze(npeEvent());

        assertEquals(AnalysisPath.VECTOR_MERGED, result.path());
        ArgumentCaptor<RecordOccurrenceCommand> commandCaptor =
            ArgumentCaptor.forClass(RecordOccurrenceCommand.class);
        verify(history).record(commandCaptor.capture());
        assertEquals(com.stackwatch.domain.FingerprintVersion.V2,
            commandCaptor.getValue().group().key().fingerprintVersion());
        verify(fingerprintCache).put(
            argThat(key -> key.fingerprintVersion().name().equals("V2")), any(ErrorGroup.class));
        verify(chatClient, never()).prompt();
    }

    @Test
    void historyOutageFallsBackToL3AndRecordsDegradation() {
        ErrorNormalizer normalizer = mock(ErrorNormalizer.class);
        when(normalizer.normalize(any())).thenReturn(normalizedError("Order 981273 not found"));
        ErrorGroupRepository history = mock(ErrorGroupRepository.class);
        when(fingerprintCache.lookup(any(ErrorGroupKey.class))).thenReturn(Optional.empty());
        when(history.findExact(any(ErrorGroupKey.class)))
            .thenThrow(new IllegalStateException("history unavailable"));
        doThrow(new IllegalStateException("history unavailable"))
            .when(history).record(any(RecordOccurrenceCommand.class));
        when(callSpec.entity(RootCauseAnalysis.class)).thenReturn(highConfidenceRca());
        AnalysisMetrics historyMetrics = mock(AnalysisMetrics.class);

        AnalysisResult result = historyAnalyzer(normalizer, history, historyMetrics).analyze(npeEvent());

        assertEquals(AnalysisPath.LLM_NEW, result.path());
        assertEquals(highConfidenceRca(), result.analysis());
        verify(historyMetrics).recordHistoryFailure("lookup_v2");
        verify(historyMetrics).recordHistoryFailure("record");
        verify(chatClient).prompt();
    }

    @Test
    void exactHitRecordFailureSkipsV1DurableReuseBeforeFallback() {
        ErrorNormalizer normalizer = mock(ErrorNormalizer.class);
        NormalizedError normalized = normalizedError("Order 981273 not found");
        when(normalizer.normalize(any())).thenReturn(normalized);
        ErrorFingerprint v2 = v2Fingerprint(normalized);
        ErrorGroupKey v2Key = new ErrorGroupKey("order-service", v2.version(), v2.hash());
        ErrorGroup cachedGroup = historyGroup(v2Key, v2, normalized, highConfidenceRca());
        ErrorGroupRepository history = mock(ErrorGroupRepository.class);
        when(fingerprintCache.lookup(v2Key)).thenReturn(Optional.of(cachedGroup));
        doThrow(new IllegalStateException("history unavailable"))
            .when(history).record(any(RecordOccurrenceCommand.class));
        when(callSpec.entity(RootCauseAnalysis.class)).thenReturn(highConfidenceRca());
        AnalysisMetrics historyMetrics = mock(AnalysisMetrics.class);

        AnalysisResult result = historyAnalyzer(normalizer, history, historyMetrics).analyze(npeEvent());

        assertEquals(AnalysisPath.LLM_NEW, result.path());
        verify(history, never()).findExact(any(ErrorGroupKey.class));
        verify(historyMetrics, times(2)).recordHistoryFailure("record");
        verify(chatClient).prompt();
    }

    @Test
    void duplicatePersistenceUsesAuthoritativeStoredRcaAndWarmsCache() {
        ErrorNormalizer normalizer = mock(ErrorNormalizer.class);
        NormalizedError normalized = normalizedError("Order 981273 not found");
        when(normalizer.normalize(any())).thenReturn(normalized);
        ErrorGroupRepository history = mock(ErrorGroupRepository.class);
        when(fingerprintCache.lookup(any(ErrorGroupKey.class))).thenReturn(Optional.empty());
        when(history.findExact(any(ErrorGroupKey.class))).thenReturn(Optional.empty());
        RootCauseAnalysis authoritativeRca = new RootCauseAnalysis(
            "持久化的历史根因", "DATABASE", "HIGH", 0.98,
            "沿用已经确认的根因", List.of("DatabaseClient.java:9"), false);
        ErrorFingerprint v2 = v2Fingerprint(normalized);
        ErrorGroupKey key = new ErrorGroupKey("order-service", v2.version(), v2.hash());
        ErrorGroup authoritativeGroup = historyGroup(key, v2, normalized, authoritativeRca)
            .acceptedAt(npeEvent().occurredAt());
        when(history.record(any(RecordOccurrenceCommand.class)))
            .thenReturn(new RecordOccurrenceResult(authoritativeGroup, false));
        when(embeddingService.embed(any())).thenReturn(new float[] {0.1f, 0.2f});
        ErrorCluster existing = ErrorCluster.newOne(
            "cluster-local", "order-service", "NullPointerException", "rep-hash",
            Instant.parse("2026-07-08T08:00:00Z"), highConfidenceRca(), new float[] {0.1f, 0.2f});
        when(clusterRepository.findSimilar(any(float[].class), anyDouble()))
            .thenReturn(Optional.of(existing));

        AnalysisResult result = historyAnalyzer(normalizer, history, mock(AnalysisMetrics.class))
            .analyze(npeEvent());

        assertEquals(AnalysisPath.VECTOR_MERGED, result.path());
        assertEquals(authoritativeRca, result.analysis());
        assertEquals(authoritativeGroup.clusterId(), result.clusterId());
        verify(fingerprintCache).put(eq(key), eq(authoritativeGroup));
        verify(chatClient, never()).prompt();
    }

    @Test
    void looseFingerprintNeverProvidesAutomaticRcaReuse() {
        ErrorNormalizer normalizer = mock(ErrorNormalizer.class);
        when(normalizer.normalize(any())).thenReturn(normalizedError("Order 981273 not found"));
        ErrorGroupRepository history = mock(ErrorGroupRepository.class);
        when(fingerprintCache.lookup(any(ErrorGroupKey.class))).thenReturn(Optional.empty());
        when(history.findExact(any(ErrorGroupKey.class))).thenReturn(Optional.empty());
        when(callSpec.entity(RootCauseAnalysis.class)).thenReturn(highConfidenceRca());

        AnalysisResult result = historyAnalyzer(normalizer, history, mock(AnalysisMetrics.class))
            .analyze(npeEvent());

        assertEquals(AnalysisPath.LLM_NEW, result.path());
        verify(chatClient).prompt();
        verify(history, times(2)).findExact(any(ErrorGroupKey.class));
    }

    private ErrorAnalyzer historyAnalyzer(ErrorNormalizer normalizer,
                                          ErrorGroupRepository history,
                                          AnalysisMetrics historyMetrics) {
        FewShotRepository fewShotRepository = mock(FewShotRepository.class);
        when(fewShotRepository.findByExceptionType(anyString(), anyInt())).thenReturn(List.of());
        return new ErrorAnalyzer(
            new Fingerprinter(FINGERPRINT_TOP_N), embeddingService, fingerprintCache,
            clusterRepository, chatClient, analysisTools,
            new AnalysisProperties(
                SIMILARITY_THRESHOLD, CONFIDENCE_THRESHOLD, CONFIDENCE_HIGH_THRESHOLD, FINGERPRINT_TOP_N),
            promptTemplate(), historyMetrics,
            fewShotRepository, antiPatternRepository,
            new ContextOptimizer(new ContextOptimizerProperties(65536)),
            normalizer, history);
    }

    private static PromptTemplateHolder promptTemplate() {
        try {
            return new PromptTemplateHolder(new ClassPathResource("prompts/root-cause.st"));
        } catch (IOException exception) {
            throw new IllegalStateException("test prompt template is unavailable", exception);
        }
    }

    private static NormalizedError normalizedError(String message) {
        return new NormalizedError(
            "NullPointerException", "NullPointerException", message, message,
            List.of("com.foo.OrderService#process", "com.foo.OrderController#handle"), List.of(), 0);
    }

    private static ErrorFingerprint v2Fingerprint(NormalizedError normalized) {
        return new Fingerprinter(FINGERPRINT_TOP_N).generateV2(normalized, "order-service");
    }

    private static ErrorGroup historyGroup(ErrorGroupKey key, ErrorFingerprint fingerprint,
                                           NormalizedError normalized, RootCauseAnalysis analysis) {
        return ErrorGroup.newGroup(new ErrorGroup.ErrorGroupSeed(
            new ErrorGroup.GroupIdentity(java.util.UUID.randomUUID(), key),
            new ErrorGroup.GroupFacts(fingerprint.looseHash() == null ? fingerprint.hash()
                : fingerprint.looseHash(), normalized.outerExceptionType(),
                normalized.effectiveExceptionType(), normalized.normalizedRootCauseMessage(),
                normalized.applicationFrames()),
            analysis, "cluster-history"));
    }

    private ErrorEvent npeEvent() {
        return new ErrorEvent(
            new ErrorEvent.Context(
                new ErrorEvent.Identity("test-1", "order-service", "prod"),
                Instant.parse("2026-07-08T10:00:00Z"),
                Map.of("traceId", "trace-123")),
            new ThrowableInfo(
                "NullPointerException",
                "Cannot invoke \"String.length()\" because \"order\" is null",
                List.of(
                    "at com.foo.OrderService.process(OrderService.java:42)",
                    "at com.foo.OrderController.handle(OrderController.java:17)"),
                null)
        );
    }

    private RootCauseAnalysis highConfidenceRca() {
        return new RootCauseAnalysis(
            "order 字段未初始化导致 NPE", "空指针", "HIGH", 0.9,
            "初始化 order 字段或加空判断",
            List.of("OrderService.java:42 调用 length()"), false);
    }
}
