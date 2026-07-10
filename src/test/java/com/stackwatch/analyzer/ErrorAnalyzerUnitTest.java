package com.stackwatch.analyzer;

import com.stackwatch.config.AnalysisProperties;
import com.stackwatch.domain.AnalysisPath;
import com.stackwatch.domain.AnalysisResult;
import com.stackwatch.domain.ErrorCluster;
import com.stackwatch.domain.ErrorEvent;
import com.stackwatch.domain.RootCauseAnalysis;
import com.stackwatch.preprocess.EmbeddingService;
import com.stackwatch.preprocess.Fingerprinter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ErrorAnalyzer 纯单元测试：不依赖 LLM API Key，CI 可跑。
 * 对应 ③分析层 L1/L2/L3 三层归并核心逻辑 + 置信度兜底 + LLM 异常兜底。
 *
 * 真实依赖（无外部 IO，可直接 new）：
 *  - Fingerprinter（SHA-256 纯计算）
 *  - AnalysisProperties（record，阈值常量）
 *  - PromptTemplateHolder（加载 classpath:/prompts/root-cause.st，仅字符串渲染）
 *
 * Mock 依赖（org.mockito.Mockito）：
 *  - EmbeddingService / FingerprintCache / ClusterRepository / AnalysisTools
 *  - ChatClient 链式 mock：prompt() -> user() -> tools() -> call() -> entity()
 *
 * 不使用 @SpringBootTest / @ExtendWith(MockitoExtension.class)，避免 strict stubs
 * 干扰共享链路 stub；采用手动 mock() + lenient stubbing。
 */
class ErrorAnalyzerUnitTest {

    /** L2 向量归并相似度阈值，与 application.yml stackwatch.analysis.similarity-threshold 对齐。 */
    private static final double SIMILARITY_THRESHOLD = 0.92;
    /** L3 LLM 根因置信度兜底阈值，低于此值转人工。 */
    private static final double CONFIDENCE_THRESHOLD = 0.6;
    /** 指纹参与 hash 的栈顶业务帧数。 */
    private static final int FINGERPRINT_TOP_N = 5;

    private EmbeddingService embeddingService;
    private FingerprintCache fingerprintCache;
    private ClusterRepository clusterRepository;
    private ChatClient chatClient;
    private AnalysisTools analysisTools;

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

        // ChatClient 链式 mock：prompt -> user -> tools -> call -> entity
        // entity() 是链终点，在各 L3 测试里单独 stub（返回值或抛异常）
        requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        callSpec = mock(ChatClient.CallResponseSpec.class);
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.tools(any(Object[].class))).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callSpec);

        Fingerprinter fingerprinter = new Fingerprinter(FINGERPRINT_TOP_N);
        AnalysisProperties properties = new AnalysisProperties(
            SIMILARITY_THRESHOLD, CONFIDENCE_THRESHOLD, FINGERPRINT_TOP_N);
        PromptTemplateHolder promptTemplate = new PromptTemplateHolder(
            new ClassPathResource("prompts/root-cause.st"));

        errorAnalyzer = new ErrorAnalyzer(
            fingerprinter, embeddingService, fingerprintCache,
            clusterRepository, chatClient, analysisTools,
            properties, promptTemplate);
    }

    @Test
    void l1CacheHitSkipsLlmAndEmbedding() {
        // Arrange: L1 指纹缓存命中
        RootCauseAnalysis cached = highConfidenceRca();
        when(fingerprintCache.lookup(anyString())).thenReturn(Optional.of(cached));

        // Act
        AnalysisResult result = errorAnalyzer.analyze(npeEvent());

        // Assert: 直接复用缓存，不查 embedding、不调 LLM
        assertEquals(AnalysisPath.CACHE_HIT, result.path());
        assertEquals(cached, result.analysis());
        assertNull(result.clusterId(), "L1 命中不产生新簇 ID");
        verify(chatClient, never()).prompt();
        verify(embeddingService, never()).embed(any());
        verify(clusterRepository, never()).findSimilar(any(float[].class), anyDouble());
    }

    @Test
    void l2VectorMergeSkipsLlm() {
        // Arrange: L1 miss + L2 向量命中
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

        // Act
        AnalysisResult result = errorAnalyzer.analyze(npeEvent());

        // Assert: 复用同簇归因，不调 LLM，回填 L1 缓存
        assertEquals(AnalysisPath.VECTOR_MERGED, result.path());
        assertEquals("cluster-existing", result.clusterId());
        assertEquals(clusterAnalysis, result.analysis());
        verify(chatClient, never()).prompt();
        verify(clusterRepository).save(any(ErrorCluster.class));
        verify(fingerprintCache).put(anyString(), eq(clusterAnalysis));
    }

    @Test
    void l1AndL2MissTriggersLlmNewClusterWithHighConfidence() {
        // Arrange: L1/L2 均 miss，LLM 返回高置信度归因（evidence 非空）
        float[] vec = {0.1f, 0.2f};
        when(fingerprintCache.lookup(anyString())).thenReturn(Optional.empty());
        when(embeddingService.embed(any())).thenReturn(vec);
        when(clusterRepository.findSimilar(any(float[].class), anyDouble()))
            .thenReturn(Optional.empty());
        RootCauseAnalysis llmRca = highConfidenceRca();
        when(callSpec.entity(RootCauseAnalysis.class)).thenReturn(llmRca);

        // Act
        AnalysisResult result = errorAnalyzer.analyze(npeEvent());

        // Assert: 新建簇，置信度达标且证据充分 -> 不转人工
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
        // Arrange: LLM 返回低置信度（evidence 非空，精确触发 lowConfidence 分支而非 noEvidence）
        float[] vec = {0.1f};
        when(fingerprintCache.lookup(anyString())).thenReturn(Optional.empty());
        when(embeddingService.embed(any())).thenReturn(vec);
        when(clusterRepository.findSimilar(any(float[].class), anyDouble()))
            .thenReturn(Optional.empty());
        RootCauseAnalysis lowConfRca = new RootCauseAnalysis(
            "不确定的根因", "UNKNOWN", "MEDIUM", 0.3,
            "需进一步排查", List.of("OrderService.java:42"), false);
        when(callSpec.entity(RootCauseAnalysis.class)).thenReturn(lowConfRca);

        // Act
        AnalysisResult result = errorAnalyzer.analyze(npeEvent());

        // Assert: 置信度 0.3 < 0.6 阈值 -> postProcess 置 needHumanReview=true
        assertEquals(AnalysisPath.LLM_NEW, result.path());
        assertEquals(0.3, result.analysis().confidence());
        assertTrue(result.analysis().needHumanReview());
    }

    @Test
    void llmExceptionReturnsFallbackAnalysis() {
        // Arrange: LLM 链路抛异常（callLlm catch 后返回 null，postProcess 走兜底分支）
        float[] vec = {0.1f};
        when(fingerprintCache.lookup(anyString())).thenReturn(Optional.empty());
        when(embeddingService.embed(any())).thenReturn(vec);
        when(clusterRepository.findSimilar(any(float[].class), anyDouble()))
            .thenReturn(Optional.empty());
        when(callSpec.entity(RootCauseAnalysis.class))
            .thenThrow(new RuntimeException("LLM unavailable"));

        // Act
        AnalysisResult result = errorAnalyzer.analyze(npeEvent());

        // Assert: 兜底归因（rootCause="LLM 未返回有效结果", confidence=0, needHumanReview=true）
        assertEquals(AnalysisPath.LLM_NEW, result.path());
        assertNotNull(result.analysis());
        assertEquals("LLM 未返回有效结果", result.analysis().rootCause());
        assertEquals("UNKNOWN", result.analysis().category());
        assertEquals(0.0, result.analysis().confidence());
        assertTrue(result.analysis().needHumanReview());
        verify(clusterRepository).save(any(ErrorCluster.class));
        verify(fingerprintCache).put(anyString(), any());
    }

    /** 构造 NPE 错误事件（固定时间，便于稳定断言）。 */
    private ErrorEvent npeEvent() {
        return new ErrorEvent(
            "test-1", "order-service", "prod",
            Instant.parse("2026-07-08T10:00:00Z"),
            "NullPointerException",
            "Cannot invoke \"String.length()\" because \"order\" is null",
            List.of(
                "at com.foo.OrderService.process(OrderService.java:42)",
                "at com.foo.OrderController.handle(OrderController.java:17)"
            ),
            Map.of("traceId", "trace-123")
        );
    }

    /** 高置信度归因（confidence=0.9，evidence 非空，needHumanReview=false）。 */
    private RootCauseAnalysis highConfidenceRca() {
        return new RootCauseAnalysis(
            "order 字段未初始化导致 NPE", "空指针", "HIGH", 0.9,
            "初始化 order 字段或加空判断",
            List.of("OrderService.java:42 调用 length()"), false);
    }
}
