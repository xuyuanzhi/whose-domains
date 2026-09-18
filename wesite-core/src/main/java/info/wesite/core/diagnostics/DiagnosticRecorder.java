package info.wesite.core.diagnostics;

import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import jakarta.annotation.PreDestroy;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.servlet.HandlerMapping;

@Component
public class DiagnosticRecorder {
    public static final String REQUEST_ID = DiagnosticRecorder.class.getName()+".id";
    public static final String ERROR = DiagnosticRecorder.class.getName()+".error";
    static final String ORIGINAL_ROUTE = DiagnosticRecorder.class.getName()+".route";
    private static final Logger LOG = LoggerFactory.getLogger(DiagnosticRecorder.class);
    private final DiagnosticsProperties config;
    private final IssueStore store;
    private final DiagnosticCatalog catalog;
    private final ThreadPoolExecutor worker;
    private final AtomicLong lastWarning = new AtomicLong();

    public DiagnosticRecorder(DiagnosticsProperties config, IssueStore store, DiagnosticCatalog catalog) {
        this.config = config; this.store = store; this.catalog = catalog;
        this.worker = new ThreadPoolExecutor(1,1,30,TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(Math.max(1,Math.min(config.getQueueCapacity(),10000))), runnable -> {
                Thread thread = new Thread(runnable,"issue-recorder"); thread.setDaemon(true); return thread;
            }, new ThreadPoolExecutor.AbortPolicy());
        worker.allowCoreThreadTimeOut(true);
    }
    public boolean submit(DiagnosticEvent event) {
        if (!config.isEnabled()) return false;
        try {
            worker.execute(() -> { try { store.record(event); } catch (RuntimeException failure) { warn(); } });
            return true;
        } catch (RejectedExecutionException full) { warn(); return false; }
    }
    private void warn() {
        long now = System.currentTimeMillis(), previous = lastWarning.get();
        if (now-previous>60000 && lastWarning.compareAndSet(previous,now)) LOG.warn("Diagnostic event dropped; check issue database connectivity and queue capacity");
    }
    public static void mark(HttpServletRequest request, Throwable error) {
        if (request != null && request.getAttribute(ERROR) == null) request.setAttribute(ERROR,error);
    }
    public static void markCurrent(Throwable error) {
        var attributes = org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();
        if (attributes instanceof org.springframework.web.context.request.ServletRequestAttributes servlet) mark(servlet.getRequest(), error);
    }
    public void request(HttpServletRequest request, int status, Throwable failure) {
        String id = (String)request.getAttribute(REQUEST_ID);
        if (id == null) return;
        Object originalRoute = request.getAttribute(ORIGINAL_ROUTE);
        String route = catalog.route(String.valueOf(originalRoute != null ? originalRoute
            : request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE)));
        String method = request.getMethod();
        if (!java.util.Set.of("GET","POST","PUT","PATCH","DELETE","HEAD","OPTIONS").contains(method)) method = "OTHER";
        submit(new DiagnosticEvent("http:"+id,"server_error",route,method,status,
            DiagnosticSanitizer.type(failure),DiagnosticSanitizer.frames(failure),id,System.currentTimeMillis(),false));
    }
    public void task(String name, Throwable failure) {
        if (!name.matches("[A-Za-z0-9_.-]{1,100}")) return;
        submit(new DiagnosticEvent("task:"+UUID.randomUUID(),"task_error",name,"",0,
            DiagnosticSanitizer.type(failure),DiagnosticSanitizer.frames(failure),"",System.currentTimeMillis(),false));
    }
    @PreDestroy public void close() { worker.shutdown(); }
}
