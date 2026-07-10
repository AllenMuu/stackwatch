package com.stackwatch.feedback;

import com.stackwatch.domain.FewShotSample;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * 反馈层 HTTP 入口：{@code POST /feedback}。横切B反馈层。
 *
 * <p>职责：接收飞书卡片【根因对/错】回调，把人工确认/修正的根因
 * 规范化为 {@link FewShotSample} 存入 {@link FewShotRepository}，
 * 供 {@code ErrorAnalyzer.callLlm} 下次分析相似堆栈时注入 prompt（数据飞轮）。
 *
 * <p>校验：{@code correctRootCause} 为空 -> 400（拒绝无"正确答案"的反馈）。
 * 其余字段缺失不阻断（clusterId/exceptionType 可为空，仓储内部归一化兜底）。
 */
@RestController
@RequestMapping("/feedback")
public class FeedbackController {

    private static final Logger log = LoggerFactory.getLogger(FeedbackController.class);

    private static final String STATUS_SAVED = "saved";
    private static final String STATUS_REJECTED = "rejected";
    private static final String REASON_EMPTY_ROOT_CAUSE = "correctRootCause 必填且不能为空白";

    private final FewShotRepository fewShotRepository;

    public FeedbackController(FewShotRepository fewShotRepository) {
        this.fewShotRepository = fewShotRepository;
    }

    /**
     * 接收一条人工反馈，落库为 few-shot 样本。
     *
     * @param request 反馈请求体；correctRootCause 为空时返回 400
     * @return 200 + {@code {status, sampleId}} 或 400 + {@code {status, reason}}
     */
    @PostMapping
    public ResponseEntity<Map<String, String>> feedback(@RequestBody FeedbackRequest request) {
        if (request == null
            || request.correctRootCause() == null
            || request.correctRootCause().isBlank()) {
            return ResponseEntity.badRequest()
                .body(Map.of("status", STATUS_REJECTED, "reason", REASON_EMPTY_ROOT_CAUSE));
        }

        FewShotSample sample = new FewShotSample(
            UUID.randomUUID().toString(),
            request.clusterId(),
            request.exceptionType(),
            request.stackText(),
            request.correctRootCause(),
            Instant.now()
        );
        fewShotRepository.save(sample);

        log.info("Few-shot feedback saved: sampleId={} clusterId={} exceptionType={}",
            sample.id(), request.clusterId(), request.exceptionType());

        return ResponseEntity.ok(Map.of("status", STATUS_SAVED, "sampleId", sample.id()));
    }
}
