package info.wesite.core.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import info.wesite.core.entity.ApiPlan;
import info.wesite.core.model.QuotaDecision;
import info.wesite.core.service.impl.ApiQuotaServiceImpl;

class ApiQuotaServiceImplTest {

    private StringRedisTemplate redis;
    private ValueOperations<String, String> values;
    private SetOperations<String, String> sets;
    private ApiQuotaServiceImpl service;
    private ApiPlan plan;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        values = mock(ValueOperations.class);
        sets = mock(SetOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(redis.opsForSet()).thenReturn(sets);
        service = new ApiQuotaServiceImpl(redis);
        plan = new ApiPlan();
        plan.setCode("free");
        plan.setDailyRequestLimit(100);
        plan.setMonthlyRequestLimit(3000);
    }

    @Test
    void allowsWhenUnderBothLimits() {
        when(values.increment(anyString(), anyLong())).thenReturn(1L);

        QuotaDecision decision = service.consume("user-1", plan, null, 1);

        assertTrue(decision.isAllowed());
        assertEquals(1, decision.getDailyUsed());
        assertEquals(100, decision.getDailyLimit());
    }

    @Test
    void dailyOverageIsRolledBackAndDenied() {
        when(values.increment(anyString(), anyLong())).thenReturn(101L);

        QuotaDecision decision = service.consume("user-2", plan, null, 1);

        assertFalse(decision.isAllowed());
        assertEquals("daily", decision.getReason());
        verify(values).decrement(
                argThat(k -> k != null && k.startsWith("api:usage:") && !k.startsWith("api:usage:m:")), anyLong());
    }

    @Test
    void monthlyOverageRollsBackBothCounters() {
        when(values.increment(argThat(k -> k != null && k.startsWith("api:usage:m:")), anyLong())).thenReturn(3001L);
        when(values.increment(argThat(k -> k != null && k.startsWith("api:usage:") && !k.startsWith("api:usage:m:")),
                anyLong())).thenReturn(50L);

        QuotaDecision decision = service.consume("user-3", plan, null, 1);

        assertFalse(decision.isAllowed());
        assertEquals("monthly", decision.getReason());
        verify(values).decrement(argThat(k -> k != null && k.startsWith("api:usage:m:")), anyLong());
        verify(values).decrement(argThat(k -> k != null && k.startsWith("api:usage:") && !k.startsWith("api:usage:m:")),
                anyLong());
    }

    @Test
    void repeatedIdempotencyKeyIsNotChargedAgain() {
        when(values.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(false);
        when(values.get(anyString())).thenReturn("5");

        QuotaDecision decision = service.consume("user-4", plan, "retry-1", 1);

        assertTrue(decision.isAllowed());
        assertEquals(5, decision.getDailyUsed());
        verify(values, never()).increment(anyString(), anyLong());
    }
}
