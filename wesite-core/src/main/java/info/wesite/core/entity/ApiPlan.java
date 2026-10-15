package info.wesite.core.entity;

import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * A configurable API plan. Prices and limits live in the database (see {@code doc/alter_api_plan.sql})
 * and are never hard-coded in business logic. Amounts are integer minor currency units.
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("WEB_API_PLAN")
public class ApiPlan extends BaseEntity {

    public static final String PERIOD_NONE = "NONE";

    private String code;
    private String name;
    private String description;
    private Integer priceCents;
    private String currency;
    private String billingPeriod;
    private Integer dailyRequestLimit;
    private Integer monthlyRequestLimit;
    private Integer maxDomainsPerBatch;
    private Integer requestsPerMinute;
    private Integer maxActiveKeys;
    private Integer sortOrder;

    public static final String FREE_CODE = "free";

    /** Daily request allowance, or {@link Integer#MAX_VALUE} when unset/non-positive (unlimited). */
    public int effectiveDailyLimit() {
        return dailyRequestLimit == null || dailyRequestLimit <= 0 ? Integer.MAX_VALUE : dailyRequestLimit;
    }

    /** Monthly request allowance, or {@link Integer#MAX_VALUE} when unset/non-positive (unlimited). */
    public int effectiveMonthlyLimit() {
        return monthlyRequestLimit == null || monthlyRequestLimit <= 0 ? Integer.MAX_VALUE : monthlyRequestLimit;
    }

    public int effectiveMaxActiveKeys() {
        return maxActiveKeys == null || maxActiveKeys <= 0 ? Integer.MAX_VALUE : maxActiveKeys;
    }
}
