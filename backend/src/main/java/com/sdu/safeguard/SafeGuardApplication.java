package com.sdu.safeguard;

import com.sdu.safeguard.config.VideoProperties;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableConfigurationProperties(VideoProperties.class)
@EnableAsync
@MapperScan("com.sdu.safeguard.mapper")
public class SafeGuardApplication {

    public static void main(String[] args) {
        SpringApplication.run(SafeGuardApplication.class, args);
    }
}
