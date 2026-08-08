package info.wesite.web.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.ModelAndView;

import com.baomidou.mybatisplus.core.conditions.Wrapper;

import info.wesite.core.entity.Domain;
import info.wesite.core.entity.DomainSite;
import info.wesite.core.service.DomainDnsService;
import info.wesite.core.service.DomainService;
import info.wesite.core.service.DomainSiteService;
import info.wesite.core.service.DomainSnapshotService;
import info.wesite.web.seo.DomainReportIndexPolicy;
import jakarta.servlet.http.HttpServletRequest;

class MainControllerDomainReportRobotsTest {

    @Test
    void appliesNoindexToThinMainDomainReportsEvenWhenDnsQueryReturnsAnEmptyList() {
        Domain domain = new Domain();
        domain.setId("domain-id");
        domain.setName("thin.example");
        domain.setStatus(Domain.STATUS_ACTIVE);
        domain.setRegistrar("Example Registrar");
        domain.setRefreshDnsTime(new java.util.Date());
        domain.setUpdateTime(new java.util.Date());

        DomainService domains = mock(DomainService.class);
        DomainSiteService sites = mock(DomainSiteService.class);
        DomainDnsService dns = mock(DomainDnsService.class);
        when(domains.getOne(any())).thenReturn(domain);
        when(sites.list(org.mockito.ArgumentMatchers.<Wrapper<DomainSite>>any()))
                .thenReturn(List.of(new DomainSite()));
        when(dns.list(org.mockito.ArgumentMatchers.<Wrapper<info.wesite.core.entity.DomainDns>>any()))
                .thenReturn(List.of());

        MainController controller = new MainController();
        ReflectionTestUtils.setField(controller, "domainService", domains);
        ReflectionTestUtils.setField(controller, "domainSiteService", sites);
        ReflectionTestUtils.setField(controller, "domainDnsService", dns);
        ReflectionTestUtils.setField(controller, "domainSnapshotService", mock(DomainSnapshotService.class));
        ReflectionTestUtils.setField(controller, "domainReportIndexPolicy", new DomainReportIndexPolicy());

        ModelAndView result = (ModelAndView) controller.detail("thin.example", mock(HttpServletRequest.class));

        assertEquals("noindex, follow", result.getModel().get("_page_robots"));
    }

    @Test
    void appliesNoindexToSubdomainReports() {
        DomainSite site = new DomainSite();
        site.setDomainId("domain-id");
        site.setName("www.thin.example");
        site.setStatus(DomainSite.STATUS_ACTIVE);
        site.setRefreshDnsTime(java.time.LocalDateTime.now());
        site.setRefreshWebTime(java.time.LocalDateTime.now());
        site.setUpdateTime(new java.util.Date());

        DomainSiteService sites = mock(DomainSiteService.class);
        DomainDnsService dns = mock(DomainDnsService.class);
        when(sites.getOne(any())).thenReturn(site);
        when(dns.list(org.mockito.ArgumentMatchers.<Wrapper<info.wesite.core.entity.DomainDns>>any()))
                .thenReturn(List.of());

        MainController controller = new MainController();
        ReflectionTestUtils.setField(controller, "domainSiteService", sites);
        ReflectionTestUtils.setField(controller, "domainDnsService", dns);
        ReflectionTestUtils.setField(controller, "domainService", mock(DomainService.class));

        ModelAndView result = (ModelAndView) controller.detail("www.thin.example", mock(HttpServletRequest.class));

        assertEquals("noindex, follow", result.getModel().get("_page_robots"));
    }
}
