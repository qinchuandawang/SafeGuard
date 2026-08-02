package com.sdu.safeguard;

import com.sdu.safeguard.config.VideoProperties;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.embedded.tomcat.TomcatConnectorCustomizer;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.net.BindException;
import java.net.ServerSocket;
import java.util.LinkedHashMap;
import java.util.Map;

@SpringBootApplication
@EnableConfigurationProperties(VideoProperties.class)
@EnableAsync
@EnableScheduling
@MapperScan("com.sdu.safeguard.mapper")
public class SafeGuardApplication {

    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(SafeGuardApplication.class);
        Map<String, Object> dotEnvDefaults = loadDotEnvDefaults();
        if (!dotEnvDefaults.isEmpty()) {
            application.setDefaultProperties(dotEnvDefaults);
        }
        application.run(args);
    }

    /**
     * 开发/演示环境直接读取项目根目录 .env。
     * 这样从 IDE、Maven 或脚本启动时都能拿到 LLM_API_KEY、DB_PASSWORD 等配置。
     */
    private static Map<String, Object> loadDotEnvDefaults() {
        Map<String, Object> defaults = new LinkedHashMap<>();
        File dotEnv = findDotEnv();
        if (dotEnv == null || !dotEnv.isFile()) {
            return defaults;
        }
        try (BufferedReader reader = new BufferedReader(new FileReader(dotEnv))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#") || !trimmed.contains("=")) {
                    continue;
                }
                int idx = trimmed.indexOf('=');
                String key = trimmed.substring(0, idx).trim();
                String value = trimmed.substring(idx + 1).trim();
                if (key.isEmpty() || System.getenv(key) != null || System.getProperty(key) != null) {
                    continue;
                }
                defaults.put(key, stripQuotes(value));
            }
            System.out.println("[SafeGuard] 已加载环境配置: " + dotEnv.getAbsolutePath());
        } catch (IOException e) {
            System.out.println("[SafeGuard][WARN] .env 读取失败: " + e.getMessage());
        }
        return defaults;
    }

    private static File findDotEnv() {
        File dir = new File(System.getProperty("user.dir")).getAbsoluteFile();
        for (int i = 0; i < 4 && dir != null; i++) {
            File candidate = new File(dir, ".env");
            if (candidate.isFile()) {
                return candidate;
            }
            dir = dir.getParentFile();
        }
        return null;
    }

    private static String stripQuotes(String value) {
        if (value.length() >= 2
                && ((value.startsWith("\"") && value.endsWith("\""))
                || (value.startsWith("'") && value.endsWith("'")))) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    // ========== DevTools 热重启兼容：设置 SO_REUSEADDR ==========

    /**
     * 配置 Tomcat 启用 SO_REUSEADDR，提高 DevTools 热重启时端口的复用成功率
     */
    @Bean
    public WebServerFactoryCustomizer<TomcatServletWebServerFactory> tomcatPortReuseCustomizer() {
        return factory -> factory.addConnectorCustomizers(
                (TomcatConnectorCustomizer) connector ->
                        connector.setProperty("soReuseAddress", "true")
        );
    }

    // ========== 保留端口探测工具（仅诊断，不执行任何 kill 操作） ==========

    /**
     * 探测指定端口是否可用。仅做 bind 测试，不会 kill 任何进程，避免误杀用户机器上其他服务。
     * 若端口被占用，会打印提示信息，但不会阻止 Spring Boot 后续的 bind 失败。
     *
     * 注意：之前版本曾对占用进程执行 taskkill /F，这会强制终止用户在该端口运行的其他服务
     * （如调试中的另一组 Spring Boot、API 工具等），属于严重反模式，已移除。
     */
    @SuppressWarnings("unused")
    private static void probePort(int port) {
        try (ServerSocket ignored = new ServerSocket(port)) {
            System.out.println("[SafeGuard] 端口 " + port + " 可用");
        } catch (BindException e) {
            System.out.println("[SafeGuard][WARN] 端口 " + port + " 被占用。请手动关闭占用进程或修改 server.port 后重试。");
        } catch (IOException e) {
            System.out.println("[SafeGuard][WARN] 端口 " + port + " 检测失败: " + e.getMessage());
        }
    }
}
