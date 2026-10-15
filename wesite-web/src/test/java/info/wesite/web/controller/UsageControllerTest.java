package info.wesite.web.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import info.wesite.core.config.UserHolder;
import info.wesite.core.entity.ApiPlan;
import info.wesite.core.entity.ApiUsageDaily;
import info.wesite.core.entity.User;
import info.wesite.core.model.QuotaDecision;
import info.wesite.core.service.ApiPlanService;
import info.wesite.core.service.ApiQuotaService;
import info.wesite.core.service.ApiUsageDailyService;
import info.wesite.core.view.ResponseJson;

class UsageControllerTest {

    private ApiPlanService plans;
    private ApiQuotaService quota;
    private ApiUsageDailyService usage;
    private UsageController controller;

    @BeforeEach
    void setUp() {
        plans = mock(ApiPlanService.class);
        quota = mock(ApiQuotaService.class);
        usage = mock(ApiUsageDailyService.class);
        controller = new UsageController(plans, quota, usage);

        User user = new User();
        user.setId("user-1");
        UserHolder.set(user);

        ApiPlan plan = new ApiPlan();
        plan.setCode("free");
        plan.setName("Free");
        plan.setDailyRequestLimit(100);
        plan.setMonthlyRequestLimit(3000);
        plan.setMaxActiveKeys(3);
        when(plans.resolveForUser("user-1")).thenReturn(plan);
        when(quota.peek("user-1", plan)).thenReturn(QuotaDecision.allowed(10, 100, 20, 3000));

        ApiUsageDaily row = new ApiUsageDaily();
        row.setUsageDate("2026-10-14");
        row.setRequestCount(7);
        when(usage.list(org.mockito.ArgumentMatchers
                .<com.baomidou.mybatisplus.core.conditions.Wrapper<ApiUsageDaily>>any())).thenReturn(List.of(row));
    }

    @AfterEach
    void tearDown() {
        UserHolder.remove();
    }

    @Test
    @SuppressWarnings("unchecked")
    void returnsPlanQuotaAndHistory() {
        ResponseJson<Map<String, Object>> response = controller.usage();

        assertEquals(ResponseJson.CODE_SUCCESS, response.getCode());
        Map<String, Object> data = (Map<String, Object>) response.getData();
        Map<String, Object> plan = (Map<String, Object>) data.get("plan");
        Map<String, Object> quotaBody = (Map<String, Object>) data.get("quota");
        List<Map<String, Object>> history = (List<Map<String, Object>>) data.get("history");

        assertEquals("free", plan.get("code"));
        assertEquals(10, quotaBody.get("dailyUsed"));
        assertEquals(1, history.size());
        assertEquals("2026-10-14", history.get(0).get("date"));
    }

    @Test
    void missingUserIsUnauthorized() {
        UserHolder.remove();

        ResponseJson<Map<String, Object>> response = controller.usage();

        assertEquals(ResponseJson.CODE_NOAUTH, response.getCode());
    }
}
