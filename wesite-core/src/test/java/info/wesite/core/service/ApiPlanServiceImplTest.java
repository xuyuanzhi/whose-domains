package info.wesite.core.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import info.wesite.core.entity.ApiPlan;
import info.wesite.core.entity.User;
import info.wesite.core.service.impl.ApiPlanServiceImpl;

class ApiPlanServiceImplTest {

    private UserService userService;
    private ApiPlanServiceImpl service;
    private ApiPlan free;
    private ApiPlan developer;

    @BeforeEach
    void setUp() {
        userService = mock(UserService.class);
        service = spy(new ApiPlanServiceImpl(userService));
        free = new ApiPlan();
        free.setCode("free");
        developer = new ApiPlan();
        developer.setCode("developer");
        doReturn(free).when(service).getByCode(ApiPlan.FREE_CODE);
        doReturn(developer).when(service).getById("plan-dev");
    }

    @Test
    void returnsTheUsersAssignedPlan() {
        User user = new User();
        user.setPlanId("plan-dev");
        when(userService.getById("user-1")).thenReturn(user);

        assertEquals("developer", service.resolveForUser("user-1").getCode());
    }

    @Test
    void accountsWithoutAPlanFallBackToFree() {
        User user = new User();
        when(userService.getById(anyString())).thenReturn(user);

        assertEquals("free", service.resolveForUser("user-2").getCode());
    }

    @Test
    void unknownUserFallsBackToFree() {
        when(userService.getById(anyString())).thenReturn(null);

        assertEquals("free", service.resolveForUser("missing").getCode());
    }
}
