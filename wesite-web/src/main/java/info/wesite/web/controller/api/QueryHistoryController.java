package info.wesite.web.controller.api;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

import info.wesite.core.config.AccessControl;
import info.wesite.core.config.UserHolder;
import info.wesite.core.entity.User;
import info.wesite.core.entity.UserQueryHistory;
import info.wesite.core.service.UserQueryHistoryService;
import info.wesite.core.view.ResponseJson;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * 用户查询历史 API
 */
@Tag(name = "Query History API")
@RestController
@RequestMapping("/api/user/query-history")
public class QueryHistoryController {

    @Autowired
    private UserQueryHistoryService historyService;

    @Operation(summary = "获取查询历史列表")
    @AccessControl(level = AccessControl.Level.SESSION)
    @GetMapping
    public ResponseJson<Map<String, Object>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String type) {

        User user = UserHolder.get();
        var query = Wrappers.<UserQueryHistory>lambdaQuery()
                .eq(UserQueryHistory::getUserId, user.getId())
                .eq(type != null && !type.isBlank(), UserQueryHistory::getQueryType, type)
                .orderByDesc(UserQueryHistory::getCreateTime);

        Page<UserQueryHistory> pageResult = historyService.page(new Page<>(page, size), query);

        List<Map<String, Object>> items = pageResult.getRecords().stream().map(h -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", h.getId());
            m.put("queryType", h.getQueryType());
            m.put("queryValue", h.getQueryValue());
            m.put("resultSummary", h.getResultSummary());
            m.put("createTime", h.getCreateTime());
            return m;
        }).collect(Collectors.toList());

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("items", items);
        data.put("total", pageResult.getTotal());
        data.put("page", page);
        data.put("size", size);
        return ResponseJson.success(data);
    }

    @Operation(summary = "删除单条查询历史")
    @AccessControl(level = AccessControl.Level.SESSION)
    @DeleteMapping("/{id}")
    public ResponseJson<Void> delete(@PathVariable String id) {
        User user = UserHolder.get();
        long count = historyService.count(
                Wrappers.<UserQueryHistory>lambdaQuery()
                        .eq(UserQueryHistory::getId, id)
                        .eq(UserQueryHistory::getUserId, user.getId()));
        if (count == 0) return ResponseJson.failure("Record not found");
        historyService.removeById(id);
        return ResponseJson.success(null);
    }

    @Operation(summary = "清空所有查询历史")
    @AccessControl(level = AccessControl.Level.SESSION)
    @DeleteMapping("/clear")
    public ResponseJson<Void> clear(@RequestParam(required = false) String type) {
        User user = UserHolder.get();
        historyService.remove(
                Wrappers.<UserQueryHistory>lambdaQuery()
                        .eq(UserQueryHistory::getUserId, user.getId())
                        .eq(type != null && !type.isBlank(), UserQueryHistory::getQueryType, type));
        return ResponseJson.success(null);
    }
}
