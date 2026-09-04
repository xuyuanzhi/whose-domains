package info.wesite.admin.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.lang.reflect.Constructor;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.baomidou.mybatisplus.core.conditions.Wrapper;

import info.wesite.core.entity.DomainTld;
import info.wesite.core.entity.DomainTldExt;
import info.wesite.core.service.DomainTldExtService;
import info.wesite.core.service.DomainTldService;
import info.wesite.core.view.ResponseJson;

class DomainControllerTest {

    private DomainTldService tlds;
    private DomainTldExtService slds;
    private DomainController controller;

    @BeforeEach
    void setUp() throws ReflectiveOperationException {
        tlds = mock(DomainTldService.class);
        slds = mock(DomainTldExtService.class);
        controller = controllerWith(tlds, slds);
    }

    @Test
    void usesConstructorInjectionForBothDomainServices() {
        assertTrue(Arrays.stream(DomainController.class.getDeclaredConstructors())
            .anyMatch(constructor -> Arrays.equals(constructor.getParameterTypes(),
                new Class<?>[] {DomainTldService.class, DomainTldExtService.class})));
    }

    @Test
    void tldDetailRejectsBlankIdWithChineseMessage() {
        DomainTld request = new DomainTld();
        request.setId("   ");

        ResponseJson<?> response = controller.tldDetail(request);

        assertFailure(response, "顶级域名ID不能为空");
        verify(tlds, never()).getById(any(String.class));
    }

    @Test
    void tldSaveTrimsIdAndRejectsUnknownTldWithChineseMessage() {
        DomainTld request = new DomainTld();
        request.setId("  tld-1  ");
        when(tlds.getById("tld-1")).thenReturn(null);

        ResponseJson<?> response = controller.tldSave(request);

        assertFailure(response, "顶级域名不存在");
        verify(tlds).getById("tld-1");
    }

    @Test
    void sldDetailRejectsBlankIdWithChineseMessage() {
        DomainTldExt request = new DomainTldExt();
        request.setId("\t");

        ResponseJson<?> response = controller.sldDetail(request);

        assertFailure(response, "二级保留域名ID不能为空");
        verify(slds, never()).getById(any(String.class));
    }

    @Test
    void sldSaveRejectsBlankNameWithChineseMessage() {
        DomainTldExt request = new DomainTldExt();
        request.setName("   ");

        ResponseJson<?> response = controller.sldSave(request);

        assertFailure(response, "二级保留域名名称不能为空");
        verify(slds, never()).saveOrUpdate(any(DomainTldExt.class));
    }

    @Test
    void createsSldWithTrimmedLowercaseNameAndActiveStatus() {
        DomainTld tld = new DomainTld();
        tld.setDotName(".cn");
        when(slds.getOne(any(Wrapper.class))).thenReturn(null);
        when(tlds.getOne(any(Wrapper.class))).thenReturn(tld);
        when(slds.saveOrUpdate(any(DomainTldExt.class))).thenReturn(true);

        DomainTldExt request = sld(null, "  GOV.CN  ", DomainTldExt.STATUS_INACTIVE);
        request.setCountryName("中国");
        request.setNote("政府机构");

        ResponseJson<?> response = controller.sldSave(request);

        assertEquals(ResponseJson.CODE_SUCCESS, response.getCode());
        verify(slds).saveOrUpdate(argThat(saved ->
            "gov.cn".equals(saved.getName())
                && ".gov.cn".equals(saved.getDotName())
                && ".cn".equals(saved.getTldName())
                && saved.getStatus() == DomainTldExt.STATUS_ACTIVE
                && saved.getId() != null
                && "admin".equals(saved.getCreateBy())
                && saved.getCreateTime() != null));
    }

    @Test
    void rejectsDuplicateNormalizedSldName() {
        DomainTldExt duplicate = sld("sld-existing", "gov.cn", DomainTldExt.STATUS_ACTIVE);
        when(slds.getOne(any(Wrapper.class))).thenReturn(duplicate);

        ResponseJson<?> response = controller.sldSave(
            sld(null, "  GOV.CN  ", DomainTldExt.STATUS_ACTIVE));

        assertFailure(response, "二级保留域名名称已存在");
        verify(slds, never()).saveOrUpdate(any(DomainTldExt.class));
    }

    @Test
    void rejectsSldWhoseTldDoesNotExist() {
        when(slds.getOne(any(Wrapper.class))).thenReturn(null);
        when(tlds.getOne(any(Wrapper.class))).thenReturn(null);

        ResponseJson<?> response = controller.sldSave(
            sld(null, "  GOV.INVALID  ", DomainTldExt.STATUS_ACTIVE));

        assertFailure(response, "所属顶级域名不存在");
        verify(slds, never()).saveOrUpdate(any(DomainTldExt.class));
    }

    @Test
    void editedSldHonorsActiveOrInactiveStatus() {
        DomainTldExt existing = sld("sld-1", "gov.cn", DomainTldExt.STATUS_ACTIVE);
        DomainTld tld = new DomainTld();
        tld.setDotName(".cn");
        when(slds.getOne(any(Wrapper.class))).thenReturn(null);
        when(tlds.getOne(any(Wrapper.class))).thenReturn(tld);
        when(slds.getById("sld-1")).thenReturn(existing);
        when(slds.saveOrUpdate(any(DomainTldExt.class))).thenReturn(true);

        ResponseJson<?> response = controller.sldSave(
            sld("  sld-1  ", "  GOV.CN ", DomainTldExt.STATUS_INACTIVE));

        assertEquals(ResponseJson.CODE_SUCCESS, response.getCode());
        verify(slds).getById("sld-1");
        verify(slds).saveOrUpdate(argThat(saved ->
            "sld-1".equals(saved.getId())
                && saved.getStatus() == DomainTldExt.STATUS_INACTIVE
                && saved.getUpdateTime() != null));
    }

    @Test
    void editedSldRejectsUnsupportedStatus() {
        DomainTldExt existing = sld("sld-1", "gov.cn", DomainTldExt.STATUS_ACTIVE);
        DomainTld tld = new DomainTld();
        tld.setDotName(".cn");
        when(slds.getOne(any(Wrapper.class))).thenReturn(null);
        when(tlds.getOne(any(Wrapper.class))).thenReturn(tld);
        when(slds.getById("sld-1")).thenReturn(existing);

        ResponseJson<?> response = controller.sldSave(sld("sld-1", "gov.cn", 99));

        assertFailure(response, "二级保留域名状态只能是启用或禁用");
        verify(slds, never()).saveOrUpdate(any(DomainTldExt.class));
    }

    private static DomainTldExt sld(String id, String name, Integer status) {
        DomainTldExt value = new DomainTldExt();
        value.setId(id);
        value.setName(name);
        value.setStatus(status);
        return value;
    }

    private static DomainController controllerWith(DomainTldService tlds,
            DomainTldExtService slds) throws ReflectiveOperationException {
        for (Constructor<?> constructor : DomainController.class.getDeclaredConstructors()) {
            if (Arrays.equals(constructor.getParameterTypes(),
                    new Class<?>[] {DomainTldService.class, DomainTldExtService.class})) {
                return (DomainController) constructor.newInstance(tlds, slds);
            }
        }
        Constructor<DomainController> constructor = DomainController.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        DomainController fallback = constructor.newInstance();
        ReflectionTestUtils.setField(fallback, "domainTldService", tlds);
        ReflectionTestUtils.setField(fallback, "domainTldExtService", slds);
        return fallback;
    }

    private static void assertFailure(ResponseJson<?> response, String message) {
        assertEquals(ResponseJson.CODE_FAILURE, response.getCode());
        assertEquals(message, response.getMsg());
    }
}
