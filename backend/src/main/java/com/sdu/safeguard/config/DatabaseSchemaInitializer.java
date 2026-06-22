package com.sdu.safeguard.config;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

@Slf4j
@Component
@RequiredArgsConstructor
public class DatabaseSchemaInitializer {

    private final DataSource dataSource;

    @PostConstruct
    public void patchDemoSchema() {
        String demoAdminHash = "$2b$10$8t70ROuVfMOGoA1/wC0qIeeFxqhO.3b2UXXNLzu42wgmrZJpVDnfW";
        String[] sqls = {
                "ALTER TABLE user ADD COLUMN email VARCHAR(100) DEFAULT NULL COMMENT '邮箱' AFTER avatar_url",
                "ALTER TABLE user ADD COLUMN phone VARCHAR(20) DEFAULT NULL COMMENT '手机号' AFTER email",
                "ALTER TABLE user ADD COLUMN department VARCHAR(100) DEFAULT NULL COMMENT '部门' AFTER phone",
                "ALTER TABLE user ADD COLUMN bio VARCHAR(500) DEFAULT NULL COMMENT '个人简介' AFTER department",
                "ALTER TABLE user ADD COLUMN password_hash VARCHAR(255) DEFAULT NULL COMMENT '管理员密码的BCrypt哈希' AFTER role",
                "INSERT INTO user (openid, nickname, avatar_url, role, password_hash, last_login_at) "
                        + "SELECT 'admin_default', '管理员', '', 'admin', '" + demoAdminHash + "', NOW() "
                        + "WHERE NOT EXISTS (SELECT 1 FROM user WHERE openid = 'admin_default')",
                "UPDATE user SET password_hash = '" + demoAdminHash + "' "
                        + "WHERE openid = 'admin_default' AND (password_hash IS NULL OR password_hash = '')"
        };
        try (Connection conn = dataSource.getConnection();
             Statement statement = conn.createStatement()) {
            for (String sql : sqls) {
                executeIgnoreDuplicate(statement, sql);
            }
        } catch (SQLException e) {
            log.warn("演示数据库结构兼容补丁执行失败: {}", e.getMessage());
        }
    }

    private void executeIgnoreDuplicate(Statement statement, String sql) {
        try {
            statement.execute(sql);
            log.info("演示数据库结构已补齐: {}", sql);
        } catch (SQLException e) {
            if (!isDuplicateColumn(e)) {
                log.warn("演示数据库结构补丁跳过: {}, reason={}", sql, e.getMessage());
            }
        }
    }

    private boolean isDuplicateColumn(SQLException e) {
        return e.getErrorCode() == 1060 || (e.getMessage() != null && e.getMessage().contains("Duplicate column"));
    }
}
