package com.sdu.safeguard.config;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.Socket;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 自动管理 Qdrant Docker 容器的生命周期。
 * <p>
 * 后端启动时检查 localhost:6333，如果 Qdrant 未运行则自动通过 Docker 启动容器；
 * 后端关闭时停止容器。仅当配置的 Qdrant 地址为 localhost/127.0.0.1 时生效。
 */
@Component
@Slf4j
public class QdrantContainerManager {

    private static final String CONTAINER_NAME = "safeguard-qdrant";
    private static final String IMAGE = "qdrant/qdrant:latest";
    private static final int QDRANT_HTTP_PORT = 6333;
    private static final int START_TIMEOUT_SECONDS = 60;
    private static final int HEALTH_CHECK_INTERVAL_MS = 2000;
    private static final String[] DOCKER_DESKTOP_PATHS = {
        "C:\\Program Files\\Docker\\Docker\\Docker Desktop.exe",
        "D:\\DockerData\\Docker Desktop.exe",
    };

    @Value("${rag.qdrant-host:localhost}")
    private String qdrantHost;

    @Value("${rag.qdrant-port:6333}")
    private int qdrantPort;

    @Value("${rag.allow-in-memory-fallback:false}")
    private boolean allowInMemoryFallback;

    @Value("${rag.auto-manage-container:false}")
    private boolean autoManageContainer;

    private boolean startedByMe = false;

    @PostConstruct
    public void init() {
        if (!autoManageContainer) {
            log.info("Qdrant 容器自动管理已关闭，基础设施生命周期由部署平台负责");
            return;
        }
        if (!isLocalhost(qdrantHost)) {
            log.info("Qdrant 指向远程 {}:{}, 跳过自动容器管理", qdrantHost, qdrantPort);
            return;
        }
        if (isPortOpen(QDRANT_HTTP_PORT)) {
            log.info("Qdrant 已在 localhost:{} 上运行", QDRANT_HTTP_PORT);
            return;
        }
        if (!isDockerAvailable()) {
            if (allowInMemoryFallback) {
                log.warn("Qdrant 未运行且 Docker 不可用, 使用内存回退模式。"
                        + "生产环境请启动 Qdrant 并设置 rag.allow-in-memory-fallback=false");
                return;
            }
            throw new IllegalStateException(
                "Qdrant 未运行 且 Docker 不可用。请执行以下任一操作启动 Qdrant：\n" +
                "  1) docker compose up -d qdrant\n" +
                "  2) 或直接运行: docker run -d --name safeguard-qdrant -p 6333:6333 -p 6334:6334 qdrant/qdrant\n" +
                "  3) 或手动下载 Qdrant 二进制文件运行\n" +
                "若要在没有 Qdrant 的环境下开发调试，请在 application.yml 中设置: rag.allow-in-memory-fallback: true"
            );
        }
        startQdrantContainer();
    }

    @PreDestroy
    public void shutdown() {
        if (!startedByMe) return;
        try {
            log.info("停止 Qdrant 容器...");
            exec("docker", "stop", CONTAINER_NAME);
            log.info("Qdrant 容器已停止, 下次启动将自动恢复。数据持久化在卷 safeguard_qdrant 中");
        } catch (Exception e) {
            log.warn("停止 Qdrant 容器失败: {}", e.getMessage());
        }
    }

    // ======================== 容器管理 ========================

    private void startQdrantContainer() {
        try {
            String existing = exec("docker", "ps", "-a",
                    "--filter", "name=" + CONTAINER_NAME,
                    "--format", "{{.Names}}");

            boolean exists = existing != null && existing.trim().equals(CONTAINER_NAME);
            if (exists) {
                String status = exec("docker", "inspect",
                        "--format", "{{.State.Status}}", CONTAINER_NAME);
                if ("running".equals(status)) {
                    log.info("Qdrant 容器已在运行");
                    return;
                }
                log.info("启动已存在的 Qdrant 容器...");
                exec("docker", "start", CONTAINER_NAME);
            } else {
                log.info("创建并启动 Qdrant 容器 ({}), 首次约需 10-20s...", IMAGE);
                createAndRunContainer();
            }

            startedByMe = true;
            if (waitForQdrantReady()) return;

            // Qdrant 未就绪 → 可能是旧容器状态坏了, 清理重建
            log.warn("Qdrant 容器未就绪, 清理并重建...");
            exec("docker", "rm", "-f", CONTAINER_NAME);
            createAndRunContainer();
            waitForQdrantReady();

        } catch (Exception e) {
            log.error("启动 Qdrant 容器失败: {}", e.getMessage());
        }
    }

    private void createAndRunContainer() throws Exception {
        List<String> cmd = new ArrayList<>(List.of(
                "docker", "run", "-d",
                "--name", CONTAINER_NAME,
                "--memory", "512m",
                "-p", "6333:6333",
                "-p", "6334:6334",
                "-v", "safeguard_qdrant:/qdrant/storage"
        ));
        String configMount = resolveConfigMount();
        if (!configMount.isEmpty()) {
            cmd.add("-v");
            cmd.add(configMount);
        }
        cmd.add(IMAGE);
        exec(cmd.toArray(new String[0]));
    }

    // ======================== 辅助方法 ========================

    private boolean isLocalhost(String host) {
        return "localhost".equals(host) || "127.0.0.1".equals(host) || "0.0.0.0".equals(host);
    }

    private boolean isPortOpen(int port) {
        try (Socket s = new Socket("127.0.0.1", port)) {
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private boolean isDockerAvailable() {
        if (checkDockerInfo()) {
            return true;
        }
        String dockerDesktop = findDockerDesktop();
        if (dockerDesktop == null) {
            dockerDesktop = findDockerDesktopFromCli();
        }
        if (dockerDesktop == null) {
            log.warn("未找到 Docker Desktop, 请手动启动 Qdrant: docker compose up -d qdrant");
            return false;
        }
        log.info("Docker Desktop 未运行 ({}), 正在自动启动...", dockerDesktop);
        try {
            new ProcessBuilder(dockerDesktop).start();
            long deadline = System.currentTimeMillis() + 60_000;
            while (System.currentTimeMillis() < deadline) {
                Thread.sleep(2000);
                if (checkDockerInfo()) {
                    log.info("Docker Desktop 已就绪");
                    return true;
                }
            }
            log.warn("Docker Desktop 启动超时(60s), 请手动启动后重试");
        } catch (Exception e) {
            log.warn("自动启动 Docker Desktop 失败: {}", e.getMessage());
        }
        return false;
    }

    private boolean checkDockerInfo() {
        try {
            Process p = new ProcessBuilder("docker", "info")
                    .redirectErrorStream(true)
                    .start();
            return p.waitFor(10, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private String findDockerDesktop() {
        for (String path : DOCKER_DESKTOP_PATHS) {
            if (new File(path).exists()) {
                return path;
            }
        }
        return null;
    }

    private String findDockerDesktopFromCli() {
        try {
            Process p = new ProcessBuilder("where", "docker")
                    .redirectErrorStream(true)
                    .start();
            String output = new String(p.getInputStream().readAllBytes()).trim();
            for (String line : output.split("\\r?\\n")) {
                line = line.trim();
                if (line.toLowerCase().contains("resources\\bin\\docker.exe")) {
                    Path candidate = Paths.get(line).getParent();
                    if (candidate != null) candidate = candidate.getParent();
                    if (candidate != null) candidate = candidate.getParent();
                    if (candidate != null) {
                        Path exe = candidate.resolve("Docker Desktop.exe");
                        if (Files.exists(exe)) {
                            return exe.toAbsolutePath().normalize().toString();
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.debug("从 CLI 路径反推 Docker Desktop 失败: {}", e.getMessage());
        }
        return null;
    }

    private String resolveConfigMount() {
        for (String dir : new String[]{
                System.getProperty("user.dir"),
                System.getProperty("user.dir") + "/backend",
                System.getProperty("user.dir") + "/..",
        }) {
            Path candidate = Paths.get(dir, "qdrant-config", "config.yaml");
            if (Files.exists(candidate)) {
                return candidate.toAbsolutePath().normalize() + ":/qdrant/config/config.yaml";
            }
        }
        log.debug("未找到 qdrant-config/config.yaml, Qdrant 将使用默认配置");
        return "";
    }

    private String exec(String... cmd) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String output = new String(p.getInputStream().readAllBytes()).trim();
        if (!p.waitFor(30, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            throw new RuntimeException("命令超时: " + String.join(" ", cmd));
        }
        return output;
    }

    private boolean waitForQdrantReady() throws InterruptedException {
        log.info("等待 Qdrant 就绪 (最多 {}s)...", START_TIMEOUT_SECONDS);
        long deadline = System.currentTimeMillis() + START_TIMEOUT_SECONDS * 1000;

        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(HEALTH_CHECK_INTERVAL_MS);
            if (isQdrantHealthy()) {
                log.info("Qdrant 就绪 (localhost:{})", QDRANT_HTTP_PORT);
                return true;
            }
        }
        log.warn("Qdrant 未在 {}s 内就绪", START_TIMEOUT_SECONDS);
        return false;
    }

    /** 检查 Qdrant 是否真正可用：TCP 端口通 + HTTP 健康检查返回 200 */
    private boolean isQdrantHealthy() {
        if (!isPortOpen(QDRANT_HTTP_PORT)) {
            return false;
        }
        try {
            HttpURLConnection conn = (HttpURLConnection) URI.create("http://127.0.0.1:6333/healthz").toURL().openConnection();
            conn.setConnectTimeout(2000);
            conn.setReadTimeout(2000);
            int code = conn.getResponseCode();
            return code == 200;
        } catch (IOException e) {
            return false;
        }
    }
}
