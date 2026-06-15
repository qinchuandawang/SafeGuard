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
        String[] sqls = {
                "ALTER TABLE user ADD COLUMN email VARCHAR(100) DEFAULT NULL COMMENT '邮箱' AFTER avatar_url",
                "ALTER TABLE user ADD COLUMN phone VARCHAR(20) DEFAULT NULL COMMENT '手机号' AFTER email",
                "ALTER TABLE user ADD COLUMN department VARCHAR(100) DEFAULT NULL COMMENT '部门' AFTER phone",
                "ALTER TABLE user ADD COLUMN bio VARCHAR(500) DEFAULT NULL COMMENT '个人简介' AFTER department"
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
