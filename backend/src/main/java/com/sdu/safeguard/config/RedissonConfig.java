package com.sdu.safeguard.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.redisson.config.SentinelServersConfig;
import org.redisson.config.SingleServerConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

@Configuration
public class RedissonConfig {

    @Bean(destroyMethod = "shutdown")
    @ConditionalOnProperty(prefix = "infra.redis", name = "enabled", havingValue = "true")
    public RedissonClient redissonClient(
            RedisProperties redisProperties,
            @Value("${infra.coordination-redis.host:}") String coordinationHost,
            @Value("${infra.coordination-redis.port:6379}") int coordinationPort,
            @Value("${infra.coordination-redis.password:}") String coordinationPassword,
            @Value("${infra.coordination-redis.database:0}") int coordinationDatabase,
            @Value("${infra.redisson.lock-watchdog-timeout-ms:120000}") long lockWatchdogTimeoutMs) {
        Config config = new Config();
        config.setLockWatchdogTimeout(lockWatchdogTimeoutMs);

        RedisProperties.Sentinel sentinel = redisProperties.getSentinel();
        if (StringUtils.hasText(coordinationHost)) {
            SingleServerConfig server = config.useSingleServer()
                    .setAddress(redisAddress(coordinationHost + ":" + coordinationPort))
                    .setDatabase(coordinationDatabase);
            if (StringUtils.hasText(coordinationPassword)) {
                server.setPassword(coordinationPassword);
            }
        } else if (sentinel != null && StringUtils.hasText(sentinel.getMaster())) {
            SentinelServersConfig servers = config.useSentinelServers()
                    .setMasterName(sentinel.getMaster())
                    .setDatabase(redisProperties.getDatabase());
            sentinel.getNodes().stream()
                    .map(this::redisAddress)
                    .forEach(servers::addSentinelAddress);
            if (StringUtils.hasText(redisProperties.getUsername())) {
                servers.setUsername(redisProperties.getUsername());
            }
            if (redisProperties.getPassword() != null) {
                servers.setPassword(redisProperties.getPassword());
                servers.setSentinelPassword(redisProperties.getPassword());
            }
        } else {
            SingleServerConfig server = config.useSingleServer()
                    .setAddress(redisAddress(redisProperties.getHost() + ":" + redisProperties.getPort()))
                    .setDatabase(redisProperties.getDatabase());
            if (StringUtils.hasText(redisProperties.getUsername())) {
                server.setUsername(redisProperties.getUsername());
            }
            if (redisProperties.getPassword() != null) {
                server.setPassword(redisProperties.getPassword());
            }
        }
        return Redisson.create(config);
    }

    private String redisAddress(String address) {
        return address.startsWith("redis://") || address.startsWith("rediss://")
                ? address : "redis://" + address;
    }
}
