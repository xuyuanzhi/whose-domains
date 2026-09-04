package info.wesite.admin.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.lang.reflect.Constructor;
import java.util.Arrays;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import org.apache.ibatis.builder.MapperBuilderAssistant;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;

import info.wesite.core.entity.BaseEntity;
import info.wesite.core.entity.ContactInfo;
import info.wesite.core.service.ContactInfoService;
import info.wesite.core.view.ResponseJson;

class ContactControllerTest {

    private ContactInfoService contacts;
    private ContactController controller;
    private MockMvc mvc;

    @BeforeEach
    void setUp() throws ReflectiveOperationException {
        TableInfoHelper.initTableInfo(
            new MapperBuilderAssistant(new MybatisConfiguration(), "contact-test"), ContactInfo.class);
        contacts = mock(ContactInfoService.class);
        controller = controllerWith(contacts);
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void usesConstructorInjectionForContactService() {
        assertTrue(Arrays.stream(ContactController.class.getDeclaredConstructors())
            .anyMatch(constructor -> Arrays.equals(constructor.getParameterTypes(),
                new Class<?>[] {ContactInfoService.class})));
    }

    @Test
    void deleteRejectsAnEmptySelectionInChinese() throws Exception {
        mvc.perform(delete("/admin/contacts/delete")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"ids\":[]}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(ResponseJson.CODE_FAILURE))
            .andExpect(jsonPath("$.msg").value("请选择要删除的联系消息"));

        verify(contacts, never()).removeByIds(any());
    }

    @Test
    void deleteUsesTheDeleteRouteAndRemovesSelectedIds() throws Exception {
        when(contacts.removeByIds(Arrays.asList("contact-1", "contact-2"))).thenReturn(true);

        mvc.perform(delete("/admin/contacts/delete")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"ids\":[\"contact-1\",\"contact-2\"]}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(ResponseJson.CODE_SUCCESS))
            .andExpect(jsonPath("$.msg").value("联系消息删除成功"));

        verify(contacts).removeByIds(Arrays.asList("contact-1", "contact-2"));
    }

    @Test
    void missingDetailFailsInChinese() throws Exception {
        when(contacts.getById("missing")).thenReturn(null);

        mvc.perform(get("/admin/contacts/missing"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(ResponseJson.CODE_FAILURE))
            .andExpect(jsonPath("$.msg").value("联系消息不存在"));
    }

    @Test
    void detailRejectsABlankIdBeforeCallingTheService() {
        ResponseJson<ContactInfo> response = controller.getContactDetail("   ");

        assertEquals(ResponseJson.CODE_FAILURE, response.getCode());
        assertEquals("联系消息ID不能为空", response.getMsg());
        verify(contacts, never()).getById(any(String.class));
    }

    @ParameterizedTest
    @ValueSource(ints = {BaseEntity.STATUS_ACTIVE, BaseEntity.STATUS_INACTIVE})
    void statusAcceptsOnlyDefinedValues(int requestedStatus) throws Exception {
        ContactInfo existing = new ContactInfo();
        existing.setId("contact-1");
        existing.setStatus(BaseEntity.STATUS_ACTIVE);
        when(contacts.getById("contact-1")).thenReturn(existing);
        when(contacts.updateById(any(ContactInfo.class))).thenReturn(true);

        mvc.perform(post("/admin/contacts/status")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"id\":\"contact-1\",\"status\":" + requestedStatus + "}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(ResponseJson.CODE_SUCCESS))
            .andExpect(jsonPath("$.msg").value("联系消息状态更新成功"));

        assertEquals(requestedStatus, existing.getStatus());
        verify(contacts).updateById(existing);
    }

    @Test
    void statusRejectsUnsupportedValuesWithoutUpdating() throws Exception {
        mvc.perform(post("/admin/contacts/status")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"id\":\"contact-1\",\"status\":99}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(ResponseJson.CODE_FAILURE))
            .andExpect(jsonPath("$.msg").value("联系消息状态只能是待处理或已处理"));

        verify(contacts, never()).getById(any(String.class));
        verify(contacts, never()).updateById(any(ContactInfo.class));
    }

    @Test
    void statusRejectsABlankIdWithoutReadingTheDatabase() throws Exception {
        mvc.perform(post("/admin/contacts/status")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"id\":\"  \",\"status\":1}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(ResponseJson.CODE_FAILURE))
            .andExpect(jsonPath("$.msg").value("联系消息ID不能为空"));

        verify(contacts, never()).getById(any(String.class));
    }

    @Test
    void statsCountRealPendingAndProcessedRows() {
        when(contacts.count()).thenReturn(9L);
        when(contacts.count(any(Wrapper.class))).thenAnswer(invocation -> {
            Wrapper<?> wrapper = invocation.getArgument(0);
            return containsStatus(wrapper, BaseEntity.STATUS_ACTIVE) ? 4L : 5L;
        });

        ResponseJson<Map<String, Object>> response = controller.getContactStats();

        assertEquals(ResponseJson.CODE_SUCCESS, response.getCode());
        @SuppressWarnings("unchecked")
        Map<String, Object> stats = (Map<String, Object>) response.getData();
        assertEquals(9L, stats.get("total"));
        assertEquals(4L, stats.get("pending"));
        assertEquals(5L, stats.get("processed"));
        verify(contacts).count(org.mockito.ArgumentMatchers.argThat(wrapper ->
            containsStatus(wrapper, BaseEntity.STATUS_ACTIVE)));
        verify(contacts).count(org.mockito.ArgumentMatchers.argThat(wrapper ->
            containsStatus(wrapper, BaseEntity.STATUS_INACTIVE)));
    }

    private static boolean containsStatus(Wrapper<?> wrapper, int status) {
        if (!(wrapper instanceof AbstractWrapper<?, ?, ?> query)) {
            return false;
        }
        query.getSqlSegment();
        return query.getParamNameValuePairs().containsValue(status);
    }

    private static ContactController controllerWith(ContactInfoService contacts)
            throws ReflectiveOperationException {
        for (Constructor<?> constructor : ContactController.class.getDeclaredConstructors()) {
            if (Arrays.equals(constructor.getParameterTypes(),
                    new Class<?>[] {ContactInfoService.class})) {
                return (ContactController) constructor.newInstance(contacts);
            }
        }
        Constructor<ContactController> constructor = ContactController.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        ContactController fallback = constructor.newInstance();
        ReflectionTestUtils.setField(fallback, "contactInfoService", contacts);
        return fallback;
    }
}
