package info.wesite.web.controller.api;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import info.wesite.core.view.ResponseJson;
import info.wesite.web.task.AiBlogTask;
import jakarta.servlet.http.HttpSession;

/**
 * AI 任务管理接口（仅管理员可调用）
 */
@RestController
@RequestMapping("/api/admin/ai")
public class AiTaskController {

    @Autowired
    private AiBlogTask aiBlogTask;

    /**
     * 手动触发一次 AI 博客生成（用于测试 / 调试）
     * 需要登录态，生产环境应加管理员权限校验
     */
    @PostMapping("/blog/generate")
    public ResponseJson generateBlog(HttpSession session) {
        if (session.getAttribute("user") == null) {
            return ResponseJson.failure("Unauthorized");
        }
        try {
            aiBlogTask.generateBlogPost();
            return ResponseJson.success("Blog generation triggered.");
        } catch (Exception e) {
            return ResponseJson.failure("Error: " + e.getMessage());
        }
    }
}
