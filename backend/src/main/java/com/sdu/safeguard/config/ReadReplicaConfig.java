package com.sdu.safeguard.config;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration
public class ReadReplicaConfig {

    @Bean(name = "readReplicaJdbcTemplate")
    @ConditionalOnProperty(prefix = "app.datasource.replica", name = "enabled", havingValue = "true")
    public JdbcTemplate readReplicaJdbcTemplate(
            @Qualifier("readReplicaDataSourceProperties") DataSourceProperties properties) {
        HikariDataSource dataSource = properties.initializeDataSourceBuilder()
                .type(HikariDataSource.class).build();
        dataSource.setReadOnly(true);
        dataSource.setMaximumPoolSize(5);
        dataSource.setMinimumIdle(1);
        dataSource.setPoolName("SafeGuardReadReplica");
        return new ManagedJdbcTemplate(dataSource);
    }

    @Bean(name = "readReplicaDataSourceProperties")
    @ConfigurationProperties("app.datasource.replica")
    @ConditionalOnProperty(prefix = "app.datasource.replica", name = "enabled", havingValue = "true")
    public DataSourceProperties readReplicaDataSourceProperties() {
        return new DataSourceProperties();
    }

    private static final class ManagedJdbcTemplate extends JdbcTemplate
            implements org.springframework.beans.factory.DisposableBean {
        private final HikariDataSource dataSource;

        private ManagedJdbcTemplate(HikariDataSource dataSource) {
            super(dataSource);
            this.dataSource = dataSource;
        }

        @Override
        public void destroy() {
            dataSource.close();
        }
    }
}
