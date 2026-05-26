package com.sdu.safeguard.util;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Component
public class PromptLoader {

    public String loadPrompt(String templateName, Map<String, Object> variables) {
        String template = readTemplate(templateName);
        if (template == null) {
            return "提示词模板加载失败：" + templateName;
        }
        for (Map.Entry<String, Object> entry : variables.entrySet()) {
            String value = entry.getValue() != null ? entry.getValue().toString() : "";
            template = template.replace("{" + entry.getKey() + "}", value);
        }
        return template;
    }

    private String readTemplate(String templateName) {
        String path = "prompts/" + templateName + ".txt";
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new ClassPathResource(path).getInputStream(), StandardCharsets.UTF_8))) {
            return reader.lines().collect(Collectors.joining("\n"));
        } catch (IOException e) {
            log.error("读取提示词模板失败: {}", path, e);
            return null;
        }
    }
}
