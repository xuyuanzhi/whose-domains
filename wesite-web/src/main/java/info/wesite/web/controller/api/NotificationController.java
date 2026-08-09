package info.wesite.web.controller.api;

import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

import info.wesite.core.config.AccessControl;
import info.wesite.core.config.AccessControl.Level;
import info.wesite.core.config.UserHolder;
import info.wesite.core.entity.UserNotification;
import info.wesite.core.service.UserNotificationService;
import info.wesite.core.view.ResponseJson;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "Notification API")
@RestController
@RequestMapping("/api/notifications")
@AccessControl(level = Level.SESSION)
public class NotificationController {

    private static final int PAGE_SIZE = 20;
    private static final Set<String> CATEGORIES = Set.of(
            "all", "expiry", "security", "domain-change", "investment");

    @Autowired
    private UserNotificationService notificationService;

    @Operation(summary = "List current user's notifications")
    @GetMapping
    public ResponseJson<Map<String, Object>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "all") String category) {
        if (page < 1) {
            return ResponseJson.failure("Page must be positive.");
        }
        if (!CATEGORIES.contains(category)) {
            return ResponseJson.failure("Unknown notification category.");
        }
        if ("investment".equals(category)) {
            return ResponseJson.success(pageData(List.of(), 0, page));
        }

        var query = Wrappers.<UserNotification>lambdaQuery()
                .eq(UserNotification::getUserId, UserHolder.get().getId())
                .orderByDesc(UserNotification::getCreateTime);
        String eventTypes = eventTypesFor(category);
        if (eventTypes != null) {
            query.inSql(UserNotification::getEventId,
                    "SELECT ID FROM WEB_MONITOR_EVENT WHERE EVENT_TYPE IN (" + eventTypes + ")");
        }
        Page<UserNotification> result = notificationService.page(new Page<>(page, PAGE_SIZE), query);
        List<Map<String, Object>> items = result.getRecords().stream()
                .map(NotificationController::toDto)
                .collect(Collectors.toList());
        return ResponseJson.success(pageData(items, result.getTotal(), page));
    }

    @Operation(summary = "Get current user's unread notification count")
    @GetMapping("/unread-count")
    public ResponseJson<Map<String, Long>> unreadCount() {
        long unread = notificationService.count(Wrappers.<UserNotification>lambdaQuery()
                .eq(UserNotification::getUserId, UserHolder.get().getId())
                .isNull(UserNotification::getReadAt));
        return ResponseJson.success(Map.of("unreadCount", unread));
    }

    @Operation(summary = "Mark one notification read")
    @PutMapping("/{id}/read")
    public ResponseJson<Void> markRead(@PathVariable String id) {
        String userId = UserHolder.get().getId();
        Date readAt = new Date();
        boolean marked = notificationService.update(null, Wrappers.<UserNotification>lambdaUpdate()
                .set(UserNotification::getReadAt, readAt)
                .eq(UserNotification::getId, id)
                .eq(UserNotification::getUserId, userId)
                .eq(UserNotification::getDeleted, 0)
                .isNull(UserNotification::getReadAt));
        if (marked) {
            return ResponseJson.success(null);
        }
        UserNotification alreadyRead = notificationService.getOne(Wrappers.<UserNotification>lambdaQuery()
                .select(UserNotification::getId)
                .eq(UserNotification::getId, id)
                .eq(UserNotification::getUserId, userId)
                .eq(UserNotification::getDeleted, 0)
                .isNotNull(UserNotification::getReadAt));
        return alreadyRead != null
                ? ResponseJson.success(null)
                : ResponseJson.failure("Notification not found.");
    }

    @Operation(summary = "Mark all current-user notifications read")
    @PutMapping("/read-all")
    public ResponseJson<Void> markAllRead() {
        String userId = UserHolder.get().getId();
        if (unreadCount(userId) == 0) {
            return ResponseJson.success(null);
        }
        boolean updated = notificationService.update(null, Wrappers.<UserNotification>lambdaUpdate()
                .set(UserNotification::getReadAt, new Date())
                .eq(UserNotification::getUserId, userId)
                .eq(UserNotification::getDeleted, 0)
                .isNull(UserNotification::getReadAt));
        if (updated || unreadCount(userId) == 0) {
            return ResponseJson.success(null);
        }
        return ResponseJson.failure("Failed to mark notifications read.");
    }

    @Operation(summary = "Delete one current-user notification")
    @DeleteMapping("/{id}")
    public ResponseJson<Void> delete(@PathVariable String id) {
        boolean removed = notificationService.remove(Wrappers.<UserNotification>lambdaQuery()
                .eq(UserNotification::getId, id)
                .eq(UserNotification::getUserId, UserHolder.get().getId()));
        return removed ? ResponseJson.success(null) : ResponseJson.failure("Notification not found.");
    }

    private static Map<String, Object> pageData(List<Map<String, Object>> items, long total, int page) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("items", items);
        data.put("total", total);
        data.put("page", page);
        data.put("size", PAGE_SIZE);
        return data;
    }

    private long unreadCount(String userId) {
        return notificationService.count(Wrappers.<UserNotification>lambdaQuery()
                .eq(UserNotification::getUserId, userId)
                .eq(UserNotification::getDeleted, 0)
                .isNull(UserNotification::getReadAt));
    }

    private static Map<String, Object> toDto(UserNotification notification) {
        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("id", notification.getId());
        dto.put("title", notification.getTitle());
        dto.put("content", notification.getContent());
        if (isInternalTarget(notification.getTargetPath())) {
            dto.put("targetPath", notification.getTargetPath());
        }
        dto.put("readAt", notification.getReadAt());
        dto.put("createTime", notification.getCreateTime());
        return dto;
    }

    private static boolean isInternalTarget(String targetPath) {
        return targetPath != null
                && targetPath.startsWith("/")
                && !targetPath.startsWith("//")
                && !targetPath.startsWith("/\\");
    }

    private static String eventTypesFor(String category) {
        return switch (category) {
            case "all" -> null;
            case "expiry" -> "'DOMAIN_EXPIRING','SSL_EXPIRING'";
            case "security" -> "'DOMAIN_STATUS_CHANGED','DNS_CHANGED','WEBSITE_DOWN','WEBSITE_RECOVERED'";
            case "domain-change" -> "'DOMAIN_STATUS_CHANGED','DNS_CHANGED'";
            default -> null;
        };
    }
}
