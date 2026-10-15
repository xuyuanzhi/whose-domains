package info.wesite.core.service;

import com.baomidou.mybatisplus.extension.service.IService;

import info.wesite.core.entity.ApiPlan;

/**
 * Resolves API plans. Plans are data, not code: this service reads them from {@code WEB_API_PLAN}
 * and only falls back to a conservative default when the table has not been configured yet.
 */
public interface ApiPlanService extends IService<ApiPlan> {

    ApiPlan getByCode(String code);

    /** The effective plan for an account; accounts without a plan use the free plan. */
    ApiPlan resolveForUser(String userId);
}
