package info.wesite.core.diagnostics;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import org.springframework.web.servlet.HandlerMapping;

class DiagnosticCaptureTest {
    @Test void framesAndFingerprintExcludeMessagesUserDataAndLineChanges() {
        var error=new IllegalArgumentException("password=secret domain=private.example SELECT token FROM users");
        error.setStackTrace(new StackTraceElement[]{new StackTraceElement("driver.SQL","run","secret",12),new StackTraceElement("info.wesite.web.Query","lookup","Query.java",42)});
        String frames=DiagnosticSanitizer.frames(error);
        assertEquals("info.wesite.web.Query.lookup:42",frames);
        var a=new DiagnosticEvent("a","server_error","/domain/{name}","POST",500,DiagnosticSanitizer.type(error),frames,"",0,false);
        var b=new DiagnosticEvent("b","server_error","/domain/{name}","POST",500,DiagnosticSanitizer.type(error),frames.replace(":42",":99"),"",1,false);
        assertEquals(DiagnosticSanitizer.fingerprint(a),DiagnosticSanitizer.fingerprint(b));
    }
    @Test void browserFingerprintKeepsLineAndColumn() {
        var a=new DiagnosticEvent("a","client_error","/","",0,"TypeError","/static/js/common.js:12:3","",0,false);
        var duplicate=new DiagnosticEvent("b","client_error","/","",0,"TypeError",a.frames(),"",1,false);
        assertEquals(DiagnosticSanitizer.fingerprint(a),DiagnosticSanitizer.fingerprint(duplicate));
        for(String frame:List.of("/static/js/common.js:350:3","/static/js/common.js:12:9")) {
            var other=new DiagnosticEvent("c","client_error","/","",0,"TypeError",frame,"",1,false);
            assertNotEquals(DiagnosticSanitizer.fingerprint(a),DiagnosticSanitizer.fingerprint(other));
        }
    }
    @Test void asyncTimeoutAndErrorWaitForFinalResponseAcrossRedispatch() throws Exception {
        for(boolean timeout:List.of(true,false)) {
            var recorder=mock(DiagnosticRecorder.class);var config=new DiagnosticsProperties();config.setEnabled(true);
            var filter=new DiagnosticFilter().diagnosticRequestFilter(recorder,config).getFilter();
            var request=new MockHttpServletRequest("GET","/async");request.setAsyncSupported(true);
            var response=new MockHttpServletResponse();
            filter.doFilter(request,response,(req,res)->req.startAsync(req,res));
            var context=(MockAsyncContext)request.getAsyncContext();
            var failure=new IllegalStateException();
            var event=new jakarta.servlet.AsyncEvent(context,request,response,failure);
            for(var listener:List.copyOf(context.getListeners())) {
                if(timeout)listener.onTimeout(event);else listener.onError(event);
            }
            verifyNoInteractions(recorder);
            request.setAsyncStarted(false);request.setDispatcherType(jakarta.servlet.DispatcherType.ERROR);
            filter.doFilter(request,response,(req,res)->{});
            verifyNoInteractions(recorder);
            response.setStatus(timeout?503:500);
            context.complete();
            verify(recorder).request(eq(request),eq(timeout?503:500),timeout?isA(TimeoutException.class):same(failure));
            filter.doFilter(request,response,(req,res)->{});
            verifyNoMoreInteractions(recorder);
        }
    }
    @Test void asyncErrorDispatchPreservesOriginalRouteAndFinalStatus() throws Exception {
        for (String original : List.of("/domain/{name}/search", "/api/{name}", "unmatched")) {
            for (boolean timeout : List.of(true, false)) {
                var config = new DiagnosticsProperties(); config.setEnabled(true);
                var catalog = mock(DiagnosticCatalog.class);
                when(catalog.route(anyString())).thenAnswer(call -> {
                    String route = call.getArgument(0);
                    return Set.of("/domain/{name}/search", "/api/{name}", "/error").contains(route) ? route : "unmatched";
                });
                var events = new ArrayList<DiagnosticEvent>();
                var recorder = new DiagnosticRecorder(config, mock(IssueStore.class), catalog) {
                    @Override public boolean submit(DiagnosticEvent event) { events.add(event); return true; }
                };
                try {
                    var filter = new DiagnosticFilter().diagnosticRequestFilter(recorder, config).getFilter();
                    var request = new MockHttpServletRequest("POST", "/domain/private.example/search");
                    request.setAsyncSupported(true);
                    var response = new MockHttpServletResponse();
                    filter.doFilter(request, response, (req, res) -> {
                        if (!original.equals("unmatched")) req.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, original);
                        req.startAsync(req, res);
                    });
                    String requestId = response.getHeader("X-Request-ID");
                    var context = (MockAsyncContext) request.getAsyncContext();
                    var failure = new IllegalStateException();
                    var asyncEvent = new jakarta.servlet.AsyncEvent(context, request, response, failure);
                    for (var listener : List.copyOf(context.getListeners())) {
                        if (timeout) listener.onTimeout(asyncEvent); else listener.onError(asyncEvent);
                    }
                    request.setAsyncStarted(false);
                    request.setDispatcherType(jakarta.servlet.DispatcherType.ERROR);
                    filter.doFilter(request, response, (req, res) -> {
                        req.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/error");
                        response.setStatus(timeout ? 503 : 500);
                    });
                    assertTrue(events.isEmpty());
                    context.complete();
                    filter.doFilter(request, response, (req, res) -> {});
                    assertEquals(1, events.size());
                    assertEquals(original, events.get(0).route());
                    assertEquals(timeout ? 503 : 500, events.get(0).status());
                    assertEquals(requestId, events.get(0).requestId());
                    assertEquals("POST", events.get(0).method());
                } finally { recorder.close(); }
            }
        }
    }
    @Test void filterCapturesMarkedHttp200AndDoesNotCaptureBusinessFailure() throws Exception {
        var recorder=mock(DiagnosticRecorder.class);var config=new DiagnosticsProperties();config.setEnabled(true);
        var filter=new DiagnosticFilter().diagnosticRequestFilter(recorder,config).getFilter();
        var request=new MockHttpServletRequest("POST","/domain/private.example/search");var response=new MockHttpServletResponse();
        var error=new IllegalStateException("secret");
        filter.doFilter(request,response,(req,res)->DiagnosticRecorder.mark((jakarta.servlet.http.HttpServletRequest)req,error));
        assertNotNull(response.getHeader("X-Request-ID"));verify(recorder).request(request,200,error);
        clearInvocations(recorder);
        filter.doFilter(new MockHttpServletRequest("POST","/domain/missing.example/search"),new MockHttpServletResponse(),(req,res)->{});
        verifyNoInteractions(recorder);
    }
    @Test void filterCapturesUnhandledAndExplicit500AndExcludesItsOwnEndpoint() throws Exception {
        var recorder=mock(DiagnosticRecorder.class);var config=new DiagnosticsProperties();config.setEnabled(true);
        var filter=new DiagnosticFilter().diagnosticRequestFilter(recorder,config).getFilter();
        var request=new MockHttpServletRequest("GET","/broken");var response=new MockHttpServletResponse();
        filter.doFilter(request,response,(req,res)->((jakarta.servlet.http.HttpServletResponse)res).setStatus(503));
        verify(recorder).request(request,503,null);
        clearInvocations(recorder);
        filter.doFilter(new MockHttpServletRequest("POST","/diagnostics/events"),new MockHttpServletResponse(),(req,res)->((jakarta.servlet.http.HttpServletResponse)res).setStatus(500));
        verifyNoInteractions(recorder);
    }
    @Test void asyncCompletionAndErrorRedispatchAreCapturedOnce() throws Exception {
        var recorder=mock(DiagnosticRecorder.class);var config=new DiagnosticsProperties();config.setEnabled(true);
        var filter=new DiagnosticFilter().diagnosticRequestFilter(recorder,config).getFilter();
        var request=new MockHttpServletRequest("GET","/async");request.setAsyncSupported(true);
        var response=new MockHttpServletResponse();
        filter.doFilter(request,response,(req,res)->req.startAsync(req,res));
        verifyNoInteractions(recorder);
        response.setStatus(503);request.getAsyncContext().complete();
        verify(recorder).request(request,503,null);
        request.setAsyncStarted(false);request.setDispatcherType(jakarta.servlet.DispatcherType.ERROR);
        filter.doFilter(request,response,(req,res)->{});
        verifyNoMoreInteractions(recorder);
    }
    @Test void thrownFailureIsCapturedAs500AndRethrown() {
        var recorder=mock(DiagnosticRecorder.class);var config=new DiagnosticsProperties();config.setEnabled(true);
        var filter=new DiagnosticFilter().diagnosticRequestFilter(recorder,config).getFilter();
        var request=new MockHttpServletRequest("GET","/broken");var response=new MockHttpServletResponse();
        var failure=new IllegalStateException("secret");
        assertSame(failure,assertThrows(IllegalStateException.class,()->filter.doFilter(request,response,(req,res)->{throw failure;})));
        verify(recorder).request(request,500,failure);
    }
    @Test void boundedQueueNeverRunsDatabaseOnCallerAndFailureIsContained() throws Exception {
        var store=mock(IssueStore.class);var config=new DiagnosticsProperties();config.setEnabled(true);config.setQueueCapacity(1);
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        doAnswer(call->{entered.countDown();release.await(3,TimeUnit.SECONDS);throw new IllegalStateException();}).when(store).record(any());
        var recorder=new DiagnosticRecorder(config,store,mock(DiagnosticCatalog.class));
        var event=new DiagnosticEvent("key","task_error","test","",0,"Error","","",0,false);
        try {assertTrue(recorder.submit(event));assertTrue(entered.await(2,TimeUnit.SECONDS));assertTrue(recorder.submit(event));assertFalse(recorder.submit(event));}
        finally{release.countDown();recorder.close();}
    }
}
