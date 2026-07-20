package com.stackwatch.feedback;

import com.stackwatch.domain.AntiPattern;
import com.stackwatch.domain.FewShotSample;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * FeedbackController 纯单元测试：横切B反馈层正负双向飞轮落库逻辑。
 *
 * <p>覆盖三条路径：
 * <ul>
 *   <li>携带 wrongRootCause（纠错反馈）-> 同落 few-shot 正样本 + anti-pattern 负样本；</li>
 *   <li>无 wrongRootCause（确认反馈）-> 仅落 few-shot 正样本；</li>
 *   <li>correctRootCause 空白 -> 400 拒绝，两个仓储都不落。</li>
 * </ul>
 */
class FeedbackControllerUnitTest {

    private FewShotRepository fewShotRepository;
    private AntiPatternRepository antiPatternRepository;
    private FeedbackController controller;

    @BeforeEach
    void setUp() {
        fewShotRepository = mock(FewShotRepository.class);
        antiPatternRepository = mock(AntiPatternRepository.class);
        controller = new FeedbackController(fewShotRepository, antiPatternRepository);
    }

    @Test
    void feedbackWithWrongRootCauseSavesBothFewShotAndAntiPattern() {
        FeedbackRequest req = new FeedbackRequest(
            "cluster-1", "NullPointerException", "OrderService.process 调 length()",
            "order 字段未初始化", "误判为配置缺失");

        ResponseEntity<Map<String, String>> resp = controller.feedback(req);

        assertEquals(200, resp.getStatusCode().value());
        verify(fewShotRepository).save(any(FewShotSample.class));
        verify(antiPatternRepository).save(any(AntiPattern.class));
    }

    @Test
    void feedbackWithoutWrongRootCauseSavesOnlyFewShot() {
        FeedbackRequest req = new FeedbackRequest(
            "cluster-1", "NullPointerException", "OrderService.process 调 length()",
            "order 字段未初始化", null);

        ResponseEntity<Map<String, String>> resp = controller.feedback(req);

        assertEquals(200, resp.getStatusCode().value());
        verify(fewShotRepository).save(any(FewShotSample.class));
        verify(antiPatternRepository, never()).save(any(AntiPattern.class));
    }

    @Test
    void feedbackWithBlankCorrectRootCauseRejected() {
        FeedbackRequest req = new FeedbackRequest(
            "cluster-1", "NullPointerException", "stack...",
            "  ", "误判为配置缺失");

        ResponseEntity<Map<String, String>> resp = controller.feedback(req);

        assertEquals(400, resp.getStatusCode().value());
        verify(fewShotRepository, never()).save(any());
        verify(antiPatternRepository, never()).save(any());
    }
}
