package info.wesite.core.diagnostics;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.core.Ordered;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerMapping;

@Configuration(proxyBeanMethods = false)
public class DiagnosticFilter {
    @Bean
    FilterRegistrationBean<Filter> diagnosticRequestFilter(DiagnosticRecorder recorder, DiagnosticsProperties config) {
        Filter filter = (rawRequest, rawResponse, chain) -> {
            HttpServletRequest request = (HttpServletRequest)rawRequest;
            HttpServletResponse response = (HttpServletResponse)rawResponse;
            if (!config.isEnabled() || request.getRequestURI().startsWith(request.getContextPath()+"/diagnostics/")
                    || request.getRequestURI().startsWith(request.getContextPath()+"/static/")) {
                chain.doFilter(request,response); return;
            }
            AtomicBoolean recorded = (AtomicBoolean)request.getAttribute("diagnostic.recorded");
            if (recorded == null) {
                recorded = new AtomicBoolean();
                request.setAttribute("diagnostic.recorded",recorded);
                request.setAttribute(DiagnosticRecorder.REQUEST_ID,UUID.randomUUID().toString());
            }
            response.setHeader("X-Request-ID",(String)request.getAttribute(DiagnosticRecorder.REQUEST_ID));
            AtomicBoolean once = recorded;
            Runnable finish = () -> {
                Throwable error = (Throwable)request.getAttribute(DiagnosticRecorder.ERROR);
                if (error == null) error = (Throwable)request.getAttribute(RequestDispatcher.ERROR_EXCEPTION);
                if ((error != null || response.getStatus()>=500) && once.compareAndSet(false,true)) recorder.request(request,Boolean.TRUE.equals(request.getAttribute("diagnostic.unhandled")) ? Math.max(500,response.getStatus()) : response.getStatus(),error);
            };
            try { chain.doFilter(request,response); }
            catch (IOException | ServletException | RuntimeException error) { request.setAttribute("diagnostic.unhandled",true); DiagnosticRecorder.mark(request,error); throw error; }
            finally {
                // Error/async dispatches may replace MVC's matching pattern with /error.
                // Snapshot only the initial mapping, never the URL containing user input.
                if (request.getDispatcherType() == DispatcherType.REQUEST
                        && request.getAttribute(DiagnosticRecorder.ORIGINAL_ROUTE) == null) {
                    Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
                    request.setAttribute(DiagnosticRecorder.ORIGINAL_ROUTE, pattern == null ? "unmatched" : pattern.toString());
                }
                if (request.isAsyncStarted() && request.getAttribute("diagnostic.asyncListener") == null) {
                    AsyncListener listener = new AsyncListener() {
                        public void onComplete(AsyncEvent e) { finish.run(); }
                        public void onTimeout(AsyncEvent e) { DiagnosticRecorder.mark(request,new java.util.concurrent.TimeoutException()); }
                        public void onError(AsyncEvent e) { DiagnosticRecorder.mark(request,e.getThrowable()); }
                        public void onStartAsync(AsyncEvent e) { e.getAsyncContext().addListener(this); }
                    };
                    try {
                        request.getAsyncContext().addListener(listener);
                        request.setAttribute("diagnostic.asyncListener",true);
                    }
                    catch (IllegalStateException completed) { finish.run(); }
                } else if (request.getAttribute("diagnostic.asyncListener") == null) finish.run();
            }
        };
        FilterRegistrationBean<Filter> bean = new FilterRegistrationBean<>(filter);
        bean.setOrder(Ordered.HIGHEST_PRECEDENCE+10);
        bean.setAsyncSupported(true);
        bean.setDispatcherTypes(DispatcherType.REQUEST,DispatcherType.ASYNC,DispatcherType.ERROR);
        return bean;
    }
}
