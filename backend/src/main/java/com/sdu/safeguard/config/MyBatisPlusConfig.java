package com.sdu.safeguard.config;

import com.baomidou.mybatisplus.autoconfigure.MybatisPlusProperties;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.session.SqlSessionFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import javax.sql.DataSource;
import java.time.LocalDateTime;

/**
 * MyBatis-Plus 全局配置
 */
@Configuration
@EnableConfigurationProperties(MybatisPlusProperties.class)
public class MyBatisPlusConfig {

    private final MybatisPlusProperties properties;

    public MyBatisPlusConfig(MybatisPlusProperties properties) {
        this.properties = properties;
    }

    /**
     * 手动配置 SqlSessionFactory（兼容 Spring Boot 4.x）
     */
    @Bean
    public SqlSessionFactory sqlSessionFactory(DataSource dataSource) throws Exception {
        MybatisSqlSessionFactoryBean factoryBean = new MybatisSqlSessionFactoryBean();
        factoryBean.setDataSource(dataSource);
        factoryBean.setTypeAliasesPackage(properties.getTypeAliasesPackage());

        // 设置 mapper 资源路径（注解模式无需 XML，跳过不存在的路径避免警告）
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        String[] mapperLocations = properties.getMapperLocations();
        if (mapperLocations != null && mapperLocations.length > 0) {
            Resource[] resources = resolver.getResources(mapperLocations[0]);
            if (resources != null && resources.length > 0 && resources[0].exists()) {
                factoryBean.setMapperLocations(resources);
            }
        }

        // 设置全局配置
        GlobalConfig globalConfig = new GlobalConfig();
        GlobalConfig.DbConfig dbConfig = new GlobalConfig.DbConfig();
        dbConfig.setIdType(com.baomidou.mybatisplus.annotation.IdType.AUTO);
        dbConfig.setLogicDeleteField("deleted");
        dbConfig.setLogicDeleteValue("1");
        dbConfig.setLogicNotDeleteValue("0");
        globalConfig.setDbConfig(dbConfig);
        factoryBean.setGlobalConfig(globalConfig);

        // 设置 MyBatis-Plus 配置（必须使用 MybatisConfiguration 而非原生 Configuration）
        MybatisConfiguration mybatisConfig = new MybatisConfiguration();
        mybatisConfig.setMapUnderscoreToCamelCase(true);
        mybatisConfig.setLogImpl(org.apache.ibatis.logging.slf4j.Slf4jImpl.class);
        factoryBean.setConfiguration(mybatisConfig);

        return factoryBean.getObject();
    }

    /**
     * 自动填充处理器
     */
    @Bean
    public MetaObjectHandler metaObjectHandler() {
        return new MetaObjectHandler() {
            @Override
            public void insertFill(MetaObject metaObject) {
                this.strictInsertFill(metaObject, "createdAt", LocalDateTime.class, LocalDateTime.now());
                this.strictInsertFill(metaObject, "updatedAt", LocalDateTime.class, LocalDateTime.now());
            }

            @Override
            public void updateFill(MetaObject metaObject) {
                this.strictUpdateFill(metaObject, "updatedAt", LocalDateTime.class, LocalDateTime.now());
            }
        };
    }
}
