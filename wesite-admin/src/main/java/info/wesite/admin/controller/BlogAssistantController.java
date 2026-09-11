package info.wesite.admin.controller;

import java.util.Map;
import java.util.function.Supplier;
import org.springframework.web.bind.annotation.*;
import info.wesite.admin.blog.*;
import info.wesite.core.blog.BlogEditorialException;
import info.wesite.core.config.AccessControl;
import info.wesite.core.config.UserHolder;
import info.wesite.core.view.ResponseJson;

@RestController
@RequestMapping("/blog/assist")
@AccessControl(level = AccessControl.Level.SESSION)
public class BlogAssistantController {
    private final BlogAssistantJobs jobs;
    private final BlogOptimizationService optimizer;
    public BlogAssistantController(BlogAssistantJobs jobs, BlogOptimizationService optimizer) {
        this.jobs = jobs; this.optimizer = optimizer;
    }
    @PostMapping("/capabilities") public ResponseJson<?> capabilities() {
        return operation(() -> { actor(); return Map.of("optimizationEnabled", optimizer.isConfigured()); });
    }
    @PostMapping("/scan") public ResponseJson<?> scan() { return operation(() -> jobs.scan(actor())); }
    @PostMapping("/optimize") public ResponseJson<?> optimize(@RequestBody BlogOptimizationService.Request request) {
        return operation(() -> jobs.optimize(actor(), request));
    }
    @PostMapping("/job") public ResponseJson<?> job(@RequestBody BlogAdminModels.IdRequest request) {
        return operation(() -> jobs.get(actor(), request == null ? null : request.id()));
    }
    private String actor() {
        var user = UserHolder.get();
        if (user == null || user.getId() == null || user.getId().isBlank()) throw new BlogEditorialException("请先登录管理员账号。");
        return user.getId();
    }
    private ResponseJson<?> operation(Supplier<Object> action) {
        try { return ResponseJson.success(action.get()); }
        catch (BlogEditorialException e) { return ResponseJson.failure(e.getMessage()); }
        catch (Exception e) { return ResponseJson.error("博客助手暂时不可用，请稍后重试。"); }
    }
}
