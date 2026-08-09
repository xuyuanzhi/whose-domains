package info.wesite.web.view;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;

import info.wesite.core.config.AccessControl;
import info.wesite.core.config.AccessControl.Level;
import info.wesite.web.controller.MainController;

class NotificationCenterTemplateTest {

    @Test
    void authenticatedNavigationExposesAnAccessibleNotificationEntry() throws IOException {
        Document header = Jsoup.parse(resource("/views/template.html"));

        assertNotNull(header.selectFirst(".notification-bell[href=/user/notifications]"));
        assertNotNull(header.selectFirst(".notification-count[role=status][aria-live=polite][aria-atomic=true]"));
        assertNotNull(header.selectFirst("a[href=/user/notifications]"));
        assertNotNull(header.selectFirst("a[href=/user/notification-settings]"));
    }

    @Test
    void notificationCenterHasActionControlsSignalLabelsAndCompleteStates() throws IOException {
        Document page = Jsoup.parse(resource("/views/user/notifications.html"));

        assertNotNull(page.selectFirst("main[aria-labelledby=notificationCenterTitle]"));
        assertFalse(page.select("button[data-category]").isEmpty());
        assertNotNull(page.selectFirst("button#markAllRead"));
        assertNotNull(page.selectFirst("template#notificationRowTemplate button[data-action=read]"));
        assertNotNull(page.selectFirst("template#notificationRowTemplate button[data-action=delete]"));
        assertNotNull(page.selectFirst("template#notificationRowTemplate time[datetime]"));
        assertTrue(page.select("template#notificationRowTemplate [data-risk]").size() >= 3);
        assertNotNull(page.selectFirst("#notificationLoading[role=status]"));
        assertNotNull(page.selectFirst("#notificationError[role=alert]"));
        assertNotNull(page.selectFirst("#notificationEmpty"));
    }

    @Test
    void settingsFormOnlyOffersFieldsAcceptedByThePreferenceApi() throws IOException {
        Document page = Jsoup.parse(resource("/views/user/notification-settings.html"));
        Set<String> names = page.select("#notificationSettingsForm [name]").stream()
                .map(element -> element.attr("name"))
                .collect(Collectors.toSet());

        assertEquals(Set.of("emailMode", "domainExpiryEnabled", "sslExpiryEnabled",
                "domainStatusEnabled", "dnsChangeEnabled", "websiteAvailabilityEnabled"), names);
        assertNotNull(page.selectFirst("#settingsLoading[role=status]"));
        assertNotNull(page.selectFirst("#settingsError[role=alert]"));
        assertNotNull(page.selectFirst("#settingsStatus[role=status][aria-live=polite]"));
        assertNotNull(page.selectFirst("fieldset.cadence-fieldset > legend"));
        assertEquals(4, page.select("fieldset.cadence-fieldset input[type=radio][name=emailMode]").size());
    }

    @Test
    void notificationPagesAreSessionProtectedRoutes() {
        assertSessionRoute("/user/notifications");
        assertSessionRoute("/user/notification-settings");
    }

    @Test
    void visuallyCustomInputsRetainVisibleKeyboardFocus() throws IOException {
        String css = resource("/static/style/common.css");

        assertTrue(css.contains(".cadence-grid input:focus-visible + span"));
        assertTrue(css.contains(".channel-list input:focus-visible + .signal-switch"));
    }

    @Test
    void deletionFocusTargetsHaveAVisibleIndicator() throws IOException {
        String css = resource("/static/style/common.css");

        assertTrue(css.contains(".signal-event:focus-visible"));
        assertTrue(css.contains(".signal-timeline:focus-visible"));
    }

    private void assertSessionRoute(String path) {
        Method route = Arrays.stream(MainController.class.getDeclaredMethods())
                .filter(method -> {
                    GetMapping mapping = method.getAnnotation(GetMapping.class);
                    return mapping != null && Arrays.asList(mapping.value()).contains(path);
                })
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing route " + path));
        AccessControl access = route.getAnnotation(AccessControl.class);
        assertNotNull(access, path + " must require a session");
        assertEquals(Level.SESSION, access.level());
    }

    private String resource(String path) throws IOException {
        try (InputStream input = getClass().getResourceAsStream(path)) {
            assertNotNull(input, "Missing resource " + path);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
