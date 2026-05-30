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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.BindException;
import java.net.ServerSocket;
import java.util.LinkedHashSet;
import java.util.Set;

@SpringBootApplication
@EnableConfigurationProperties(VideoProperties.class)
@EnableAsync
@MapperScan("com.sdu.safeguard.mapper")
public class SafeGuardApplication {

    public static void main(String[] args) {
        releasePortIfOccupied(8080);
        SpringApplication.run(SafeGuardApplication.class, args);
    }

    // ========== 端口自动释放 ==========

    private static void releasePortIfOccupied(int port) {
        System.out.println("[SafeGuard] 检测端口 " + port + " ...");
        // 尝试绑定，判断端口是否被占用
        try (ServerSocket ignored = new ServerSocket(port)) {
            System.out.println("[SafeGuard] 端口 " + port + " 可用");
        } catch (BindException e) {
            System.out.println("[SafeGuard] 端口 " + port + " 被占用，尝试自动释放...");
            Set<String> pids = findProcessOnPort(port);
            if (pids.isEmpty()) {
                System.out.println("[SafeGuard] netstat 未找到占用端口的进程，可能为系统保留，跳过释放");
                return;
            }
            boolean anyKilled = false;
            for (String pid : pids) {
                if (killProcessByPid(pid)) {
                    anyKilled = true;
                }
            }
            if (!anyKilled) {
                System.out.println("[SafeGuard] 无法终止任何占用进程，请手动关闭后重试");
                return;
            }
            waitForPortReleased(port);
        } catch (IOException e) {
            System.out.println("[SafeGuard] 检测端口异常: " + e.getMessage());
        }
    }

    /**
     * 通过 netstat 查找占用端口的所有 PID（去重）
     */
    private static Set<String> findProcessOnPort(int port) {
        Set<String> pids = new LinkedHashSet<>();
        try {
            Process process = new ProcessBuilder("cmd", "/c", "netstat -ano | findstr :" + port)
                    .redirectErrorStream(true)
                    .start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (line.contains("LISTENING")) {
                        String[] parts = line.split("\\s+");
                        String pid = parts[parts.length - 1];
                        if (!pid.equals("0")) {
                            pids.add(pid);
                        }
                    }
                }
            }
            int exitCode = process.waitFor();
            if (exitCode != 0 && pids.isEmpty()) {
                System.out.println("[SafeGuard] netstat 退出码=" + exitCode + "，未找到进程");
            }
        } catch (Exception e) {
            System.out.println("[SafeGuard] 查询端口失败: " + e.getMessage());
        }
        return pids;
    }

    /**
     * 终止指定 PID 的进程，返回是否成功
     */
    private static boolean killProcessByPid(String pid) {
        System.out.println("[SafeGuard] 终止进程 PID: " + pid);
        try {
            Process process = new ProcessBuilder("taskkill", "/F", "/PID", pid)
                    .redirectErrorStream(true)
                    .start();

            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            }
            int exitCode = process.waitFor();

            String result = output.toString().trim();
            if (exitCode == 0) {
                System.out.println("[SafeGuard] 终止成功: " + result);
                return true;
            } else {
                System.out.println("[SafeGuard] 终止失败 (退出码=" + exitCode + "): " + result);
                return false;
            }
        } catch (Exception e) {
            System.out.println("[SafeGuard] 终止进程异常 PID:" + pid + " - " + e.getMessage());
            return false;
        }
    }

    /**
     * 等待端口真正释放，最多等 10 秒
     */
    private static void waitForPortReleased(int port) {
        long deadline = System.currentTimeMillis() + 10_000;
        int attempt = 0;
        while (System.currentTimeMillis() < deadline) {
            attempt++;
            // 先用 ServerSocket 检查
            try (ServerSocket ignored = new ServerSocket(port)) {
                System.out.println("[SafeGuard] 端口 " + port + " 已释放 (用时 " + (attempt * 1000) + "ms)");
                return;
            } catch (BindException e) {
                // 端口还没释放，等 1 秒再试
                sleep(1000);
            } catch (IOException e) {
                sleep(1000);
            }
        }
        // 超时后使用 netstat 确认
        Set<String> remaining = findProcessOnPort(port);
        if (remaining.isEmpty()) {
            System.out.println("[SafeGuard] 端口 " + port + " 已释放 (netstat 确认)");
            return;
        }
        System.out.println("[SafeGuard] 警告: 端口 " + port + " 仍有进程占用: " + String.join(",", remaining) +
                "，尝试强制启动...");
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
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
}
