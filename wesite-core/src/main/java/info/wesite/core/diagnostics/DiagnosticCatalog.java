package info.wesite.core.diagnostics;

import java.util.*;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.context.event.EventListener;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

@Component
public class DiagnosticCatalog {
    private volatile Set<String> routes = Set.of("unmatched");
    private volatile Set<String> scripts = Set.of();
    private final ListableBeanFactory beans;
    public DiagnosticCatalog(ListableBeanFactory beans) { this.beans = beans; }

    @EventListener(ApplicationReadyEvent.class)
    public void initialize() {
        Set<String> paths = new TreeSet<>(); paths.add("unmatched");
        beans.getBeansOfType(RequestMappingHandlerMapping.class).values().forEach(mapping ->
            mapping.getHandlerMethods().keySet().forEach(info -> paths.addAll(info.getPatternValues())));
        paths.removeIf(p -> p.startsWith("/diagnostics/"));
        routes = Set.copyOf(paths);
        Set<String> files = new TreeSet<>();
        try {
            for (var resource : new PathMatchingResourcePatternResolver().getResources("classpath*:/static/**/*.js")) {
                String url = resource.getURL().toString();
                int index = url.lastIndexOf("/static/");
                if (index >= 0) files.add(url.substring(index));
            }
        } catch (java.io.IOException ignored) { /* Empty list disables client script capture, not the app. */ }
        scripts = Set.copyOf(files);
    }
    public String route(String route) { return routes.contains(route) ? route : "unmatched"; }
    public boolean script(String script) { return scripts.contains(script); }
    public Set<String> routes() { return routes; }
    public Set<String> scripts() { return scripts; }
}
