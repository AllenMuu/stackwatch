package com.stackwatch.notifier;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 周报手动触发入口：⑤投递层测试用。
 * POST /report/weekly -> 同步执行一次周报聚合 + LLM 总结 + 飞书推送。
 *
 * 不等周一 9:00 定时，便于联调与演示。
 */
@RestController
@RequestMapping("/report")
public class ReportController {

    private final WeeklyReportScheduler scheduler;

    public ReportController(WeeklyReportScheduler scheduler) {
        this.scheduler = scheduler;
    }

    @PostMapping("/weekly")
    public void triggerWeekly() {
        scheduler.runWeeklyReport();
    }
}
