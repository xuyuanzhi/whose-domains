package info.wesite.web.controller.api;

import java.util.Date;
import java.util.List;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

import info.wesite.core.config.UserHolder;
import info.wesite.core.entity.User;
import info.wesite.core.entity.UserQueryHistory;
import info.wesite.core.service.UserQueryHistoryService;
import info.wesite.core.utils.RandomUtils;

/**
 * 查询历史异步写入工具
 * 在各 API 控制器中注入并调用 recordAsync() 即可，不阻塞请求线程
 */
@Component
public class QueryHistoryRecorder {

    private static final int MAX_PER_USER = 50;

    @Autowired
    private UserQueryHistoryService historyService;

    /**
     * 异步写入查询历史，只在用户已登录时记录
     */
    @Async
    public void recordAsync(String queryType, String queryValue, String resultSummary) {
        try {
            User user = UserHolder.get();
            if (user == null) return;   // 未登录不记录

            String userId = user.getId();

            // 防重：同一用户同类型同值 10 分钟内不重复写入
            long recentCount = historyService.count(
                    Wrappers.<UserQueryHistory>lambdaQuery()
                            .eq(UserQueryHistory::getUserId, userId)
                            .eq(UserQueryHistory::getQueryType, queryType)
                            .eq(UserQueryHistory::getQueryValue, queryValue)
                            .ge(UserQueryHistory::getCreateTime,
                                    new Date(System.currentTimeMillis() - 10 * 60 * 1000)));
            if (recentCount > 0) return;

            // 写入新记录
            UserQueryHistory h = new UserQueryHistory();
            h.setId(RandomUtils.generateId());
            h.setUserId(userId);
            h.setQueryType(queryType);
            h.setQueryValue(queryValue);
            h.setResultSummary(StringUtils.abbreviate(resultSummary, 200));
            h.setStatus(UserQueryHistory.STATUS_ACTIVE);
            h.setCreateTime(new Date());
            historyService.save(h);

            // 超过上限时删除最旧的
            long total = historyService.count(
                    Wrappers.<UserQueryHistory>lambdaQuery()
                            .eq(UserQueryHistory::getUserId, userId));
            if (total > MAX_PER_USER) {
                List<UserQueryHistory> oldest = historyService.list(
                        new Page<>(1, (int)(total - MAX_PER_USER)),
                        Wrappers.<UserQueryHistory>lambdaQuery()
                                .eq(UserQueryHistory::getUserId, userId)
                                .orderByAsc(UserQueryHistory::getCreateTime));
                for (UserQueryHistory old : oldest) {
                    historyService.removeById(old.getId());
                }
            }
        } catch (Exception e) {
            // 历史记录失败不影响主流程
        }
    }
}
