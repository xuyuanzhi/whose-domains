package info.wesite.web.controller.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import com.baomidou.mybatisplus.core.conditions.Wrapper;

import info.wesite.core.config.UserHolder;
import info.wesite.core.entity.User;
import info.wesite.core.entity.UserQueryHistory;
import info.wesite.core.service.UserQueryHistoryService;

@SuppressWarnings({ "rawtypes", "unchecked" })
class QueryHistoryRecorderTest {

    private UserQueryHistoryService history;
    private QueryHistoryRecorder recorder;

    @BeforeEach
    void setUp() {
        history = mock(UserQueryHistoryService.class);
        recorder = new QueryHistoryRecorder();
        ReflectionTestUtils.setField(recorder, "historyService", history);
    }

    @AfterEach
    void tearDown() {
        UserHolder.remove();
    }

    @Test
    void capturedRequestUserIdIsPersistedEvenWhenTheAsyncThreadHasNoUserHolder() {
        User requestUser = new User();
        requestUser.setId("request-user");
        UserHolder.set(requestUser);
        String capturedUserId = QueryHistoryRecorder.currentUserId();
        UserHolder.remove();
        when(history.count(any(Wrapper.class))).thenReturn(0L);

        recorder.recordAsync(capturedUserId, UserQueryHistory.TYPE_WHOIS, "example.com", "Known registrar");

        ArgumentCaptor<UserQueryHistory> saved = ArgumentCaptor.forClass(UserQueryHistory.class);
        verify(history).save(saved.capture());
        assertEquals("request-user", saved.getValue().getUserId());
        assertEquals(UserQueryHistory.TYPE_WHOIS, saved.getValue().getQueryType());
    }

    @Test
    void anonymousRequestIsAQuietNoOpBeforeAnyHistoryQuery() {
        recorder.recordAsync(null, UserQueryHistory.TYPE_WHOIS, "example.com", "Ignored");

        verifyNoInteractions(history);
        verify(history, never()).save(any(UserQueryHistory.class));
    }
}
