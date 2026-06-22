package com.sdu.safeguard.controller;

import com.sdu.safeguard.service.DetectionService;
import com.sdu.safeguard.service.DetectionTaskManager;
import com.sdu.safeguard.service.LLMService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class DetectionControllerTextRiskTest {

    private final DetectionController controller = new DetectionController(
            mock(DetectionService.class),
            mock(LLMService.class),
            mock(DetectionTaskManager.class)
    );

    @SuppressWarnings("unchecked")
    private Map<String, Object> analyze(String text) {
        return (Map<String, Object>) ReflectionTestUtils.invokeMethod(
                controller,
                "buildTextDetectionResult",
                text,
                ""
        );
    }

    private String readDemoText(String relativePath) throws IOException {
        Path path = Path.of("..", "test_data", "text", relativePath);
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    @Test
    void shouldMarkRebateScamAsDangerous() throws IOException {
        String text = readDemoText("02_刷单返利骗局_长文本.txt");

        Map<String, Object> result = analyze(text);
        Map<String, Object> probabilities = (Map<String, Object>) result.get("probabilities");
        List<String> features = (List<String>) result.get("features");

        assertEquals("dangerous", result.get("result"));
        assertTrue(((Number) result.get("riskProbability")).doubleValue() >= 0.90);
        assertTrue(((Number) result.get("confidence")).doubleValue() >= 0.75);
        assertTrue(((Number) probabilities.get("fake")).doubleValue() >= 0.90);
        assertTrue(features.stream().anyMatch(item -> item.contains("刷单返利")));
        assertTrue(String.valueOf(result.get("report")).contains("诈骗风险"));
    }

    @Test
    void shouldMarkCustomerServiceAutoRenewScamAsDangerous() throws IOException {
        String text = readDemoText("01_冒充客服退款_长文本.txt");

        Map<String, Object> result = analyze(text);
        Map<String, Object> probabilities = (Map<String, Object>) result.get("probabilities");
        List<String> features = (List<String>) result.get("features");

        assertEquals("dangerous", result.get("result"));
        assertTrue(((Number) result.get("riskProbability")).doubleValue() >= 0.90);
        assertTrue(((Number) probabilities.get("fake")).doubleValue() >= 0.90);
        assertTrue(features.stream().anyMatch(item -> item.contains("冒充电商客服")));
    }

    @Test
    void shouldMarkFakePoliceScamAsDangerous() {
        String text = "您好，我是公安局民警，您涉嫌一起洗钱案件，请配合调查并将资金转入安全账户。";

        Map<String, Object> result = analyze(text);
        Map<String, Object> probabilities = (Map<String, Object>) result.get("probabilities");

        assertEquals("dangerous", result.get("result"));
        assertTrue(((Number) result.get("riskProbability")).doubleValue() >= 0.90);
        assertTrue(((Number) probabilities.get("fake")).doubleValue() >= 0.90);
    }

    @Test
    void shouldMarkFaceSwapTransferScriptAsDangerous() throws IOException {
        String text = readDemoText("03_AI换脸视频诈骗话术.txt");

        Map<String, Object> result = analyze(text);

        assertEquals("dangerous", result.get("result"));
        assertTrue(((Number) result.get("riskProbability")).doubleValue() >= 0.85);
    }

    @Test
    void shouldKeepOfficialClassNoticeLowRisk() {
        String text = "各位同学，明天下午三点在教学楼 B302 开课程汇报，请提前十分钟到场签到。本次通知不涉及任何转账，也不会索要验证码，如有疑问请在班级群联系老师。";

        Map<String, Object> result = analyze(text);
        Map<String, Object> probabilities = (Map<String, Object>) result.get("probabilities");

        assertEquals("safe", result.get("result"));
        assertTrue(((Number) result.get("safeProbability")).doubleValue() >= 0.60);
        assertTrue(((Number) probabilities.get("fake")).doubleValue() <= 0.35);
        assertFalse(String.valueOf(result.get("report")).isBlank());
    }

    @Test
    void shouldKeepNormalLogisticsNoticeLowRisk() {
        String text = "您好，您的快递已送达菜鸟驿站，请凭取件码 3481 在今晚 20:00 前领取。如已领取请忽略本通知。";

        Map<String, Object> result = analyze(text);

        assertNotNull(result);
        assertEquals("safe", result.get("result"));
        assertTrue(((Number) result.get("riskProbability")).doubleValue() < 0.40);
    }

    @Test
    void shouldKeepOfficialRailwayNoticeLowRisk() throws IOException {
        String text = readDemoText("04_真实官方铁路通知_低风险.txt");

        Map<String, Object> result = analyze(text);

        assertNotNull(result);
        assertEquals("safe", result.get("result"));
        assertTrue(((Number) result.get("riskProbability")).doubleValue() <= 0.30);
        assertTrue(String.valueOf(result.get("report")).contains("官方出行通知"));
    }
}
