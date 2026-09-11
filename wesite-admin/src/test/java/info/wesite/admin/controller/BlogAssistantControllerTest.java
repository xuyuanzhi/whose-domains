package info.wesite.admin.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import info.wesite.admin.blog.*;
import info.wesite.core.config.UserHolder;
import info.wesite.core.entity.User;
import info.wesite.core.view.ResponseJson;

class BlogAssistantControllerTest {
    @AfterEach void clear() { UserHolder.remove(); }
    @Test void endpointsRequireAnActorBeforeLaunchingAnyWork() {
        UserHolder.remove();
        var jobs = mock(BlogAssistantJobs.class);
        var optimizer = mock(BlogOptimizationService.class);
        var controller = new BlogAssistantController(jobs, optimizer);
        assertEquals(ResponseJson.CODE_FAILURE, controller.scan().getCode());
        assertEquals(ResponseJson.CODE_FAILURE, controller.optimize(null).getCode());
        assertEquals(ResponseJson.CODE_FAILURE, controller.job(null).getCode());
        verifyNoInteractions(jobs, optimizer);
    }
    @Test void scanAndPollingUseTheAuthenticatedOwner() {
        var user = new User(); user.setId("admin"); UserHolder.set(user);
        var jobs = mock(BlogAssistantJobs.class);
        var controller = new BlogAssistantController(jobs, mock(BlogOptimizationService.class));
        controller.scan(); controller.job(new BlogAdminModels.IdRequest("job"));
        verify(jobs).scan("admin"); verify(jobs).get("admin", "job");
    }
}
