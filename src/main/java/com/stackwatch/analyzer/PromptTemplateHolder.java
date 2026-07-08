package com.stackwatch.analyzer;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Prompt 模板持有者：加载 resources/prompts/root-cause.st，做简单 {var} 替换。
 *
 * 不依赖 Spring AI template API（版本敏感），自行渲染降低 API 风险。
 * 变量值需预先格式化为字符串。
 */
@Component
public class PromptTemplateHolder {

    private final String template;

    public PromptTemplateHolder(@Value("classpath:/prompts/root-cause.st") Resource resource) throws IOException {
        this.template = new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    }

    public String render(Map<String, Object> vars) {
        String result = template;
        for (Map.Entry<String, Object> e : vars.entrySet()) {
            result = result.replace("{" + e.getKey() + "}", String.valueOf(e.getValue()));
        }
        return result;
    }
}
