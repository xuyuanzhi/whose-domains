package info.wesite.admin.task;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import info.wesite.core.diagnostics.DiagnosticsProperties;
import info.wesite.core.diagnostics.IssueStore;

@Component
public class IssueCleanupTask {
    private final IssueStore store;
    private final DiagnosticsProperties config;
    public IssueCleanupTask(IssueStore store,DiagnosticsProperties config) { this.store=store; this.config=config; }
    @Scheduled(fixedDelay=60000,initialDelay=60000)
    public void cleanup() { if (config.isEnabled()) store.cleanup(System.currentTimeMillis()); }
}
