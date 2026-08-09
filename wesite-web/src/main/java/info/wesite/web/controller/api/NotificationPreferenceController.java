package info.wesite.web.controller.api;

import java.util.LinkedHashMap;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import info.wesite.core.config.AccessControl;
import info.wesite.core.config.AccessControl.Level;
import info.wesite.core.config.UserHolder;
import info.wesite.core.entity.NotificationPreference;
import info.wesite.core.service.NotificationPreferenceService;
import info.wesite.core.mapper.UserNotificationMapper;
import info.wesite.core.mapper.NotificationDeliveryBatchMapper;
import info.wesite.core.view.ResponseJson;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "Notification Preference API")
@RestController
@RequestMapping("/api/notification-preferences")
@AccessControl(level = Level.SESSION)
public class NotificationPreferenceController {

    private static final Set<String> EMAIL_MODES = Set.of(
            NotificationPreference.MODE_IMMEDIATE,
            NotificationPreference.MODE_DAILY,
            NotificationPreference.MODE_WEEKLY,
            NotificationPreference.MODE_IN_APP_ONLY);

    @Autowired
    private NotificationPreferenceService preferenceService;

    @Autowired
    private UserNotificationMapper notificationMapper;

    @Autowired
    private NotificationDeliveryBatchMapper batchMapper;

    @Operation(summary = "Get current user's notification preference")
    @GetMapping
    public ResponseJson<LinkedHashMap<String, Object>> get() {
        return ResponseJson.success(toDto(currentPreference()));
    }

    @Operation(summary = "Update current user's notification preference")
    @PutMapping
    @Transactional
    public ResponseJson<LinkedHashMap<String, Object>> update(@RequestBody PreferenceRequest request) {
        if (request.emailMode != null && !EMAIL_MODES.contains(request.emailMode)) {
            return ResponseJson.failure("Invalid email notification mode.");
        }
        String userId = UserHolder.get().getId();
        NotificationPreference preference = preferenceService.getOne(Wrappers.<NotificationPreference>lambdaQuery()
                .eq(NotificationPreference::getUserId, userId));
        boolean existing = preference != null;
        if (!existing) {
            preference = NotificationPreference.defaultsFor(userId);
        }
        apply(request, preference);
        preference.setUserId(userId);
        boolean saved = existing ? preferenceService.updateById(preference) : preferenceService.save(preference);
        if (saved && NotificationPreference.MODE_IN_APP_ONLY.equals(preference.getEmailMode())) {
            java.util.Date now = new java.util.Date();
            notificationMapper.cancelUnclaimedForUser(userId, now);
            batchMapper.cancelFailedForUser(userId, now);
            batchMapper.requestCancellationForClaimedUser(userId, now);
        }
        return saved
                ? ResponseJson.success(toDto(preference))
                : ResponseJson.failure("Failed to save notification preferences.");
    }

    private NotificationPreference currentPreference() {
        String userId = UserHolder.get().getId();
        NotificationPreference preference = preferenceService.getOne(Wrappers.<NotificationPreference>lambdaQuery()
                .eq(NotificationPreference::getUserId, userId));
        return preference == null ? NotificationPreference.defaultsFor(userId) : preference;
    }

    private static void apply(PreferenceRequest request, NotificationPreference preference) {
        if (request.emailMode != null) preference.setEmailMode(request.emailMode);
        if (request.domainExpiryEnabled != null) preference.setDomainExpiryEnabled(request.domainExpiryEnabled);
        if (request.sslExpiryEnabled != null) preference.setSslExpiryEnabled(request.sslExpiryEnabled);
        if (request.domainStatusEnabled != null) preference.setDomainStatusEnabled(request.domainStatusEnabled);
        if (request.dnsChangeEnabled != null) preference.setDnsChangeEnabled(request.dnsChangeEnabled);
        if (request.websiteAvailabilityEnabled != null) preference.setWebsiteAvailabilityEnabled(request.websiteAvailabilityEnabled);
    }

    private static LinkedHashMap<String, Object> toDto(NotificationPreference preference) {
        LinkedHashMap<String, Object> dto = new LinkedHashMap<>();
        dto.put("emailMode", preference.getEmailMode());
        dto.put("domainExpiryEnabled", preference.getDomainExpiryEnabled());
        dto.put("sslExpiryEnabled", preference.getSslExpiryEnabled());
        dto.put("domainStatusEnabled", preference.getDomainStatusEnabled());
        dto.put("dnsChangeEnabled", preference.getDnsChangeEnabled());
        dto.put("websiteAvailabilityEnabled", preference.getWebsiteAvailabilityEnabled());
        return dto;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class PreferenceRequest {
        public String emailMode;
        public Boolean domainExpiryEnabled;
        public Boolean sslExpiryEnabled;
        public Boolean domainStatusEnabled;
        public Boolean dnsChangeEnabled;
        public Boolean websiteAvailabilityEnabled;
    }
}
