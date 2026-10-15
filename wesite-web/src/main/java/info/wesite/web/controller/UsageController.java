package info.wesite.web.controller;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;

import info.wesite.core.config.AccessControl;
import info.wesite.core.config.UserHolder;
import info.wesite.core.entity.ApiPlan;
import info.wesite.core.entity.ApiUsageDaily;
import info.wesite.core.entity.User;
import info.wesite.core.model.QuotaDecision;
import info.wesite.core.service.ApiPlanService;
import info.wesite.core.service.ApiQuotaService;
import info.wesite.core.service.ApiUsageDailyService;
import info.wesite.core.view.ResponseJson;

/**
 * Read-only usage/plan endpoint for the signed-in account. Surfaces the effective plan, current
 * daily/monthly allowance and recent usage history recorded by {@code ApiUsageFlushTask}.
 */
@RestController
@RequestMapping("/api/user")
@AccessControl(level = AccessControl.Level.SESSION)
public class UsageController {

    private final ApiPlanService apiPlanService;
    private final ApiQuotaService apiQuotaService;
    private final ApiUsageDailyService apiUsageDailyService;

    public UsageController(ApiPlanService apiPlanService, ApiQuotaService apiQuotaService,
            ApiUsageDailyService apiUsageDailyService) {
        this.apiPlanService = apiPlanService;
        this.apiQuotaService = apiQuotaService;
        this.apiUsageDailyService = apiUsageDailyService;
    }

    @GetMapping("/usage")
    public ResponseJson<Map<String, Object>> usage() {
        User user = UserHolder.get();
        if (user == null || user.getId() == null) {
            return ResponseJson.response(ResponseJson.CODE_NOAUTH, "Not signed in.");
        }
        String userId = user.getId();
        ApiPlan plan = apiPlanService.resolveForUser(userId);
        QuotaDecision quota = apiQuotaService.peek(userId, plan);

        Map<String, Object> planBody = new LinkedHashMap<>();
        planBody.put("code", plan.getCode());
        planBody.put("name", plan.getName());
        planBody.put("dailyLimit", plan.getDailyRequestLimit());
        planBody.put("monthlyLimit", plan.getMonthlyRequestLimit());
        planBody.put("maxDomainsPerBatch", plan.getMaxDomainsPerBatch());
        planBody.put("requestsPerMinute", plan.getRequestsPerMinute());
        planBody.put("maxActiveKeys", plan.getMaxActiveKeys());

        Map<String, Object> quotaBody = new LinkedHashMap<>();
        quotaBody.put("dailyUsed", quota.getDailyUsed());
        quotaBody.put("dailyLimit", quota.getDailyLimit());
        quotaBody.put("monthlyUsed", quota.getMonthlyUsed());
        quotaBody.put("monthlyLimit", quota.getMonthlyLimit());

        List<Map<String, Object>> history = new ArrayList<>();
        List<ApiUsageDaily> rows = apiUsageDailyService.list(new QueryWrapper<ApiUsageDaily>()
                .eq("USER_ID", userId).orderByDesc("USAGE_DATE").last("LIMIT 30"));
        for (ApiUsageDaily row : rows) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("date", row.getUsageDate());
            item.put("count", row.getRequestCount());
            history.add(item);
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("plan", planBody);
        body.put("quota", quotaBody);
        body.put("history", history);
        return ResponseJson.success(body);
    }
}
