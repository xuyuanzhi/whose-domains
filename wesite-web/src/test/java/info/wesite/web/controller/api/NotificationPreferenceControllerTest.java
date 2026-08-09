package info.wesite.web.controller.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.baomidou.mybatisplus.core.conditions.Wrapper;

import info.wesite.core.config.UserHolder;
import info.wesite.core.entity.NotificationPreference;
import info.wesite.core.entity.User;
import info.wesite.core.service.NotificationPreferenceService;

@SuppressWarnings({ "rawtypes", "unchecked" })
class NotificationPreferenceControllerTest {

    private NotificationPreferenceService preferences;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(
                        new com.baomidou.mybatisplus.core.MybatisConfiguration(), "NotificationPreferenceControllerTest"),
                NotificationPreference.class);
        preferences = mock(NotificationPreferenceService.class);
        NotificationPreferenceController controller = new NotificationPreferenceController();
        ReflectionTestUtils.setField(controller, "preferenceService", preferences);
        mvc = MockMvcBuilders.standaloneSetup(controller).build();

        User user = new User();
        user.setId("user-1");
        UserHolder.set(user);
    }

    @AfterEach
    void tearDown() {
        UserHolder.remove();
    }

    @Test
    void getReturnsCurrentUsersDefaultPreferenceWithoutPersistingIt() throws Exception {
        when(preferences.getOne(any(Wrapper.class))).thenReturn(null);

        mvc.perform(get("/api/notification-preferences"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.emailMode").value("DAILY"))
                .andExpect(jsonPath("$.data.domainExpiryEnabled").value(true))
                .andExpect(jsonPath("$.data.userId").doesNotExist());

        ArgumentCaptor<Wrapper<NotificationPreference>> query = ArgumentCaptor.forClass((Class) Wrapper.class);
        verify(preferences).getOne(query.capture());
        assertTrue(query.getValue().getSqlSegment().contains("user_id"));
    }

    @Test
    void putRejectsAnEmailModeOutsideTheAllowList() throws Exception {
        mvc.perform(put("/api/notification-preferences")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"emailMode\":\"smtp://attacker.example\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            NotificationPreference.MODE_IMMEDIATE,
            NotificationPreference.MODE_DAILY,
            NotificationPreference.MODE_WEEKLY,
            NotificationPreference.MODE_IN_APP_ONLY
    })
    void putAcceptsEveryAllowedEmailModeAndUpdatesOnlyTheCurrentUsersPreference(String emailMode) throws Exception {
        NotificationPreference existing = NotificationPreference.defaultsFor("user-1");
        existing.setId("preference-1");
        when(preferences.getOne(any(Wrapper.class))).thenReturn(existing);
        when(preferences.updateById(any(NotificationPreference.class))).thenReturn(true);

        mvc.perform(put("/api/notification-preferences")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"emailMode\":\"" + emailMode + "\",\"dnsChangeEnabled\":false,\"userId\":\"other-user\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.emailMode").value(emailMode))
                .andExpect(jsonPath("$.data.userId").doesNotExist());

        ArgumentCaptor<Wrapper<NotificationPreference>> query = ArgumentCaptor.forClass((Class) Wrapper.class);
        ArgumentCaptor<NotificationPreference> saved = ArgumentCaptor.forClass(NotificationPreference.class);
        verify(preferences).getOne(query.capture());
        verify(preferences).updateById(saved.capture());
        assertTrue(query.getValue().getSqlSegment().contains("user_id"));
        assertEquals("user-1", saved.getValue().getUserId());
        assertEquals(emailMode, saved.getValue().getEmailMode());
        assertEquals(Boolean.FALSE, saved.getValue().getDnsChangeEnabled());
    }
}
