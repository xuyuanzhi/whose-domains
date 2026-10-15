package info.wesite.core.service.impl;

import java.time.Duration;
import java.time.LocalDate;
import java.time.YearMonth;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import info.wesite.core.entity.ApiPlan;
import info.wesite.core.model.QuotaDecision;
import info.wesite.core.service.ApiQuotaService;

/**
 * Redis-backed {@link ApiQuotaService}.
 *
 * <p>Daily keys keep the historical format {@code api:usage:{subject}:{date}} so the existing
 * {@code ApiUsageFlushTask} continues to work unchanged; monthly usage uses a separate key. Overage
 * is rolled back immediately, which bounds the counter at the limit without a Lua script.</p>
 */
@Service
public class ApiQuotaServiceImpl implements ApiQuotaService {

    private static final String DAILY_PREFIX = "api:usage:";
    private static final String MONTHLY_PREFIX = "api:usage:m:";
    private static final String ACTIVE_SET = "api:usage:active";
    private static final String IDEMPOTENCY_PREFIX = "api:quota:idem:";
    private static final Duration DAILY_TTL = Duration.ofDays(2);
    private static final Duration MONTHLY_TTL = Duration.ofDays(35);
    private static final Duration IDEMPOTENCY_TTL = Duration.ofDays(1);

    private final StringRedisTemplate redis;

    public ApiQuotaServiceImpl(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public QuotaDecision consume(String subject, ApiPlan plan, String idempotencyKey, int units) {
        if (plan == null) {
            throw new IllegalStateException("api plan is required");
        }
        String key = normalizeSubject(subject);
        int amount = Math.max(1, units);

        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            Boolean first = redis.opsForValue()
                    .setIfAbsent(IDEMPOTENCY_PREFIX + key + ":" + idempotencyKey, "1", IDEMPOTENCY_TTL);
            if (Boolean.FALSE.equals(first)) {
                return peek(key, plan);
            }
        }

        String date = LocalDate.now().toString();
        String dailyKey = DAILY_PREFIX + key + ":" + date;
        Long daily = redis.opsForValue().increment(dailyKey, amount);
        if (daily != null && daily == amount) {
            redis.expire(dailyKey, DAILY_TTL);
        }
        redis.opsForSet().add(ACTIVE_SET, key + ":" + date);

        int dailyLimit = plan.effectiveDailyLimit();
        if (daily != null && daily > dailyLimit) {
            redis.opsForValue().decrement(dailyKey, amount);
            return QuotaDecision.denied("daily", dailyLimit, dailyLimit, 0, plan.effectiveMonthlyLimit());
        }

        String monthKey = MONTHLY_PREFIX + key + ":" + YearMonth.now();
        Long monthly = redis.opsForValue().increment(monthKey, amount);
        if (monthly != null && monthly == amount) {
            redis.expire(monthKey, MONTHLY_TTL);
        }
        int monthlyLimit = plan.effectiveMonthlyLimit();
        if (monthly != null && monthly > monthlyLimit) {
            redis.opsForValue().decrement(monthKey, amount);
            redis.opsForValue().decrement(dailyKey, amount);
            return QuotaDecision.denied("monthly", toInt(daily) - amount, dailyLimit, monthlyLimit, monthlyLimit);
        }

        return QuotaDecision.allowed(toInt(daily), dailyLimit, toInt(monthly), monthlyLimit);
    }

    @Override
    public QuotaDecision peek(String subject, ApiPlan plan) {
        String key = normalizeSubject(subject);
        int dailyUsed = toInt(parse(redis.opsForValue().get(DAILY_PREFIX + key + ":" + LocalDate.now())));
        int monthlyUsed = toInt(parse(redis.opsForValue().get(MONTHLY_PREFIX + key + ":" + YearMonth.now())));
        return QuotaDecision.allowed(dailyUsed, plan.effectiveDailyLimit(), monthlyUsed, plan.effectiveMonthlyLimit());
    }

    private static String normalizeSubject(String subject) {
        return subject == null || subject.isBlank() ? "anonymous" : subject;
    }

    private static Long parse(String value) {
        if (value == null) {
            return null;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static int toInt(Long value) {
        return value == null ? 0 : value.intValue();
    }
}
