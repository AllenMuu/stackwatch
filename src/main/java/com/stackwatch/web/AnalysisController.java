package com.stackwatch.web;

import com.stackwatch.analyzer.ErrorAnalyzer;
import com.stackwatch.domain.AnalysisResult;
import com.stackwatch.domain.ErrorEvent;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.UUID;

/**
 * 端到端入口：POST /analyze。
 * 输入异常信息 -> 生成指纹 -> 三层级联归并 -> 返回根因。
 */
@RestController
@RequestMapping("/analyze")
public class AnalysisController {

    private final ErrorAnalyzer errorAnalyzer;

    public AnalysisController(ErrorAnalyzer errorAnalyzer) {
        this.errorAnalyzer = errorAnalyzer;
    }

    @PostMapping
    public AnalysisResult analyze(@RequestBody AnalyzeRequest request) {
        if (request.hasMixedExceptionForms()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "exception and legacy exception fields are mutually exclusive");
        }
        ErrorEvent event = new ErrorEvent(
            UUID.randomUUID().toString(),
            request.appName(),
            request.env(),
            Instant.now(),
            request.exceptionOrLegacy(),
            request.mdc()
        );
        return errorAnalyzer.analyze(event);
    }
}
