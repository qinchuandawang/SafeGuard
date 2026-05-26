package com.sdu.safeguard.config;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "wechat")
@Data
@Slf4j
public class WeChatConfig {
    private String appid;
    private String secret;
    private String loginUrl = "https://api.weixin.qq.com/sns/jscode2session";

    @PostConstruct
    public void validate() {
        if (appid == null || appid.isBlank()) {
            log.warn("微信小程序 appid 未配置，登录功能将使用默认测试模式");
        }
    }
}
