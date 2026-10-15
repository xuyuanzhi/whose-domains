package info.wesite.core.service.impl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;

import info.wesite.core.entity.ApiPlan;
import info.wesite.core.entity.User;
import info.wesite.core.mapper.ApiPlanMapper;
import info.wesite.core.service.ApiPlanService;
import info.wesite.core.service.UserService;

@Service
public class ApiPlanServiceImpl extends ServiceImpl<ApiPlanMapper, ApiPlan> implements ApiPlanService {

    private static final Logger log = LoggerFactory.getLogger(ApiPlanServiceImpl.class);

    private final UserService userService;

    /**
     * Safety net used only before {@code doc/alter_api_plan.sql} has been applied. It matches the
     * previous hard-coded behaviour (100/day) and is configurable so nothing is truly hard-coded.
     */
    @Value("${wesite.api.fallback-daily-limit:100}")
    private int fallbackDailyLimit;

    public ApiPlanServiceImpl(UserService userService) {
        this.userService = userService;
    }

    @Override
    public ApiPlan getByCode(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        return getOne(Wrappers.<ApiPlan>lambdaQuery().eq(ApiPlan::getCode, code));
    }

    @Override
    public ApiPlan resolveForUser(String userId) {
        ApiPlan plan = null;
        if (userId != null && !userId.isBlank()) {
            User user = userService.getById(userId);
            if (user != null && user.getPlanId() != null && !user.getPlanId().isBlank()) {
                plan = getById(user.getPlanId());
            }
        }
        if (plan == null) {
            plan = getByCode(ApiPlan.FREE_CODE);
        }
        if (plan == null) {
            plan = fallbackPlan();
        }
        return plan;
    }

    private ApiPlan fallbackPlan() {
        ApiPlan plan = new ApiPlan();
        plan.setCode(ApiPlan.FREE_CODE);
        plan.setName("Free");
        plan.setBillingPeriod(ApiPlan.PERIOD_NONE);
        plan.setDailyRequestLimit(fallbackDailyLimit);
        plan.setMonthlyRequestLimit(0);
        plan.setMaxDomainsPerBatch(10);
        plan.setRequestsPerMinute(120);
        plan.setMaxActiveKeys(3);
        log.warn("WEB_API_PLAN is not configured; falling back to a default free plan (daily={}). "
                + "Apply doc/alter_api_plan.sql.", fallbackDailyLimit);
        return plan;
    }
}
