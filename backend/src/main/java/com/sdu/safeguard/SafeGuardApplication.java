package com.sdu.safeguard;

import com.sdu.safeguard.config.VideoProperties;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.tomcat.TomcatConnectorCustomizer;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableAsync;

import java.io.IOException;
import java.net.BindException;
import java.net.ServerSocket;

@SpringBootApplication
@EnableConfigurationProperties(VideoProperties.class)
@EnableAsync
@MapperScan("com.sdu.safeguard.mapper")
public class SafeGuardApplication {

    public static void main(String[] args) {
        SpringApplication.run(SafeGuardApplication.class, args);
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
