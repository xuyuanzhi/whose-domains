package info.wesite.web.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import info.wesite.core.entity.Domain;
import info.wesite.core.entity.DomainSite;
import info.wesite.core.service.DomainDnsService;
import info.wesite.core.service.DomainService;
import info.wesite.core.service.DomainSiteService;
import info.wesite.core.utils.DomainUtils;

class MainControllerDomainSearchTest {
    @ParameterizedTest
    @ValueSource(strings = {"www.chinabbs.com", "blog.chinabbs.com", "chinabbs.com"})
    void firstLookupSavesEachSiteOnlyOnce(String name) throws Exception {
        DomainService domains = mock(DomainService.class);
        DomainSiteService sites = mock(DomainSiteService.class);
        Domain domain = new Domain();
        domain.setName("chinabbs.com");
        domain.setId("domain-id");
        Map<String, DomainSite> saved = new HashMap<>();
        when(sites.getOne(any())).thenAnswer(invocation -> saved.get(name));
        when(sites.save(any(DomainSite.class))).thenAnswer(invocation -> {
            DomainSite site = invocation.getArgument(0);
            if (saved.putIfAbsent(site.getName(), site) != null) {
                throw new DuplicateKeyException("IDX_SUB_DOMAIN_NAME");
            }
            return true;
        });
        MainController controller = new MainController();
        ReflectionTestUtils.setField(controller, "domainService", domains);
        ReflectionTestUtils.setField(controller, "domainSiteService", sites);
        ReflectionTestUtils.setField(controller, "domainDnsService", mock(DomainDnsService.class));

        try (MockedStatic<DomainUtils> lookup = mockStatic(DomainUtils.class)) {
            lookup.when(() -> DomainUtils.getMainDomain(name)).thenReturn("chinabbs.com");
            lookup.when(() -> DomainUtils.getDomainInfoByMainName("chinabbs.com")).thenReturn(domain);
            lookup.when(() -> DomainUtils.getDomainSiteByName(any())).thenAnswer(invocation -> {
                DomainSite site = new DomainSite();
                site.setName(invocation.getArgument(0));
                return site;
            });
            MockMvcBuilders.standaloneSetup(controller).build()
                .perform(post("/domain/{name}/search", name))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
        }
        assertEquals(name.startsWith("blog.") ? 2 : 1, saved.size());
        verify(sites, times(saved.size())).save(any(DomainSite.class));
        assertEquals("domain-id", saved.get("www.chinabbs.com").getDomainId());
    }
}
