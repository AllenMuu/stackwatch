package com.stackwatch.metrics;

import com.stackwatch.domain.AnalysisPath;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 横切A度量层：{@code ErrorAnalyzer} 三层级联归并的指标埋点入口。
 *
 * <p>四类指标（统一 {@code stackwatch.} 前缀）：
 * <ul>
 *   <li>{@code stackwatch.analysis_duration_seconds}（Timer，tag: path）—— 定位耗时，
 *       支撑「定位耗时缩短 40%」的度量底气：没有这层埋点，「40%」只是话术；
 *       有了 Timer + path tag，可在 Prometheus 按 path 切片算 P50/P95，量化缓存命中率对耗时的贡献。</li>
 *   <li>{@code stackwatch.analysis_path_total}（Counter，tag: path/appName）——
 *       归并路径分布 {@code path=cache_hit/vector_merged/llm_new}，支撑
 *       「95% 走缓存、4% 走向量归并、仅 1% 真调 LLM」的成本故事。</li>
 *   <li>{@code stackwatch.root_cause_confidence}（DistributionSummary，Prometheus 以 histogram 桶暴露）——
 *       根因置信度分布，配合 {@code confidence-threshold=0.6} 兜底阈值观察低置信度占比。</li>
 *   <li>{@code stackwatch.token_cost_total}（Counter）—— L3 调用 token 消耗，当前占位 0，
 *       待 LLM 响应 usage 接入后累加真实值（见 {@link #recordTokens(int)}）。</li>
 * </ul>
 *
 * <p>对应架构层：横切A度量层。埋点位置由 {@link com.stackwatch.analyzer.ErrorAnalyzer}
 * 在 L1/L2/L3 三处 return 前调用（精确改法见本次返回的 {@code existingFileChanges}）。
 *
 * <p>实现说明：micrometer 1.14.x（Spring Boot 3.4.0 引入版本）无独立 Histogram 顶层计量类型，
 * 置信度分布使用 {@link DistributionSummary}——Prometheus 仍以 {@code _bucket/_count/_sum}
 * 暴露，语义等价直方图。{@link MeterRegistry} bean 与 /prometheus 端点由 spring-boot-starter-actuator
 * 自动装配，PrometheusMeterRegistry 由 micrometer-registry-prometheus 提供。
 */
@Component
public class AnalysisMetrics {

    private static final Logger log = LoggerFactory.getLogger(AnalysisMetrics.class);

    /** 指标命名空间前缀，统一所有 stackwatch 指标。 */
    private static final String METRIC_PREFIX = "stackwatch.";
    /** Timer 指标名（秒）。 */
    private static final String DURATION_METRIC = METRIC_PREFIX + "analysis_duration_seconds";
    /** 归并路径分布 Counter 指标名。 */
    private static final String PATH_METRIC = METRIC_PREFIX + "analysis_path_total";
    /** 根因置信度分布指标名（DistributionSummary）。 */
    private static final String CONFIDENCE_METRIC = METRIC_PREFIX + "root_cause_confidence";
    /** token 消耗 Counter 指标名。 */
    private static final String TOKEN_METRIC = METRIC_PREFIX + "token_cost_total";

    private static final String TAG_PATH = "path";
    private static final String TAG_APP = "appName";
    private static final String UNKNOWN_APP = "unknown";

    /** 置信度合法下界（含），与 RootCauseAnalysis 语义对齐。 */
    private static final double CONFIDENCE_MIN = 0.0;
    /** 置信度合法上界（含）。 */
    private static final double CONFIDENCE_MAX = 1.0;

    private final MeterRegistry meterRegistry;

    public AnalysisMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    /**
     * 记录归并路径计数。在 L1/L2/L3 三处 return 前调用。
     *
     * @param path    归并路径枚举
     * @param appName 来源应用名，null 时记为 {@code unknown}（防御 MDC 缺失）
     */
    public void recordPath(AnalysisPath path, String appName) {
        Counter.builder(PATH_METRIC)
            .description("Error analysis merge path distribution (cache_hit / vector_merged / llm_new)")
            .tag(TAG_PATH, pathName(path))
            .tag(TAG_APP, appName == null ? UNKNOWN_APP : appName)
            .register(meterRegistry)
            .increment();
    }

    /**
     * 记录单次分析耗时（纳秒，内部转秒）。Timer 按 path 标签聚合 P50/P95。
     *
     * @param nanos {@code analyze()} 全流程纳秒耗时（{@code System.nanoTime()} 差值）
     * @param path  归并路径枚举
     */
    public void recordDuration(long nanos, AnalysisPath path) {
        if (nanos < 0) {
            // 时钟回拨或调用方传错：防御性跳过，避免 Timer.record 抛 IllegalArgumentException
            log.warn("Negative analysis duration {} ns for path {}; skipping", nanos, path);
            return;
        }
        Timer.builder(DURATION_METRIC)
            .description("Error analysis latency by merge path (seconds)")
            .tag(TAG_PATH, pathName(path))
            .register(meterRegistry)
            .record(Duration.ofNanos(nanos));
    }

    /**
     * 记录根因置信度分布。仅 L3（LLM 产出）调用；L1/L2 复用历史置信度不重复记录。
     *
     * @param confidence LLM 自评置信度，期望 [0.0, 1.0]；越界或 NaN 防御性跳过
     */
    public void recordConfidence(double confidence) {
        if (Double.isNaN(confidence) || confidence < CONFIDENCE_MIN || confidence > CONFIDENCE_MAX) {
            log.warn("Confidence {} out of [0,1]; skipping distribution record", confidence);
            return;
        }
        DistributionSummary.builder(CONFIDENCE_METRIC)
            .description("LLM root cause confidence distribution")
            .register(meterRegistry)
            .record(confidence);
    }

    /**
     * 记录 L3 调用 token 消耗。当前占位 0：Spring AI {@code ChatClient.entity()} 不直接返回 usage，
     * 待改用 {@code chatClient.call().chatResponse().metadata().usage()} 后累加真实值。
     *
     * @param tokens 本次 L3 调用消耗 token 数；负数按 0 计（防御异常上游）
     */
    public void recordTokens(int tokens) {
        int safeTokens = Math.max(0, tokens);
        Counter.builder(TOKEN_METRIC)
            .description("LLM token cost for L3 root cause analysis")
            .register(meterRegistry)
            .increment(safeTokens);
    }

    /** 枚举转 Prometheus tag 小写蛇形值，与口径 cache_hit/vector_merged/llm_new 对齐。 */
    private static String pathName(AnalysisPath path) {
        return switch (path) {
            case CACHE_HIT -> "cache_hit";
            case VECTOR_MERGED -> "vector_merged";
            case LLM_NEW -> "llm_new";
        };
    }
}
