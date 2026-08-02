package com.sdu.safeguard.service;

import com.sdu.safeguard.config.TokenCostProperties;
import com.sdu.safeguard.mapper.LlmUsageRecordMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
class TokenCostServiceTest {

    @Mock
    private ObjectProvider<LlmUsageRecordMapper> mapperProvider;
    @Mock
    private ObjectProvider<RedissonClient> redissonProvider;

    private TokenCostProperties properties;
    private TokenCostService service;

    @BeforeEach
    void setUp() {
        properties = new TokenCostProperties();
        properties.setDailyTokenBudget(10);
        properties.setMaxPromptTokens(8);
        service = new TokenCostService(properties, mapperProvider, redissonProvider,
                new SimpleMeterRegistry());
        ReflectionTestUtils.setField(service, "redisEnabled", false);
        lenient().when(mapperProvider.getIfAvailable()).thenReturn(null);
    }

    @Test
    void 调用前原子预占并拒绝超出每日预算的请求() {
        service.reserve("text-analysis", "model", "1234", 4);

        assertThatThrownBy(() -> service.reserve("text-analysis", "model", "12", 2))
                .isInstanceOf(TokenBudgetExceededException.class)
                .hasMessageContaining("预算");
    }

    @Test
    void 实际用量完成后归还多预占额度() {
        TokenCostService.Reservation reservation = service.reserve(
                "text-analysis", "model", "1234", 4);

        service.complete(reservation, 4, 1, false, "success");

        assertThat(service.snapshot().get("usedAndReservedTokens")).isEqualTo(5L);
    }

    @Test
    void 输入上下文超过上限时直接拒绝() {
        assertThatThrownBy(() -> service.reserve(
                "text-analysis", "model", "123456789", 0))
                .isInstanceOf(TokenBudgetExceededException.class)
                .hasMessageContaining("输入上下文");
    }

    @Test
    void Redis模式下协调客户端不可用时失败关闭() {
        ReflectionTestUtils.setField(service, "redisEnabled", true);
        when(redissonProvider.getIfAvailable()).thenReturn(null);

        assertThatThrownBy(() -> service.reserve("text-analysis", "model", "12", 1))
                .isInstanceOf(TokenBudgetExceededException.class)
                .hasMessageContaining("协调服务不可用");
    }
}
