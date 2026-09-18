package info.wesite.core.diagnostics;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.core.io.FileSystemResource;

@EnabledIfSystemProperty(named="issueTestJdbc",matches=".+")
class IssueStoreMySqlTest {
    private static IssueStore store;
    private static JdbcTemplate jdbc;
    private static DiagnosticsProperties config;
    private static long now;
    @BeforeAll static void initialize() throws Exception {
        String url=System.getProperty("issueTestJdbc");
        if (!url.contains("/wesite_issue_center_test")) throw new IllegalArgumentException("Use an isolated wesite_issue_center_test database");
        var ds=new DriverManagerDataSource(url,System.getProperty("issueTestUser","root"),System.getProperty("issueTestPassword",""));
        config=new DiagnosticsProperties();config.setEnabled(true);
        store=new IssueStore(ds,new DataSourceTransactionManager(ds),config);jdbc=new JdbcTemplate(ds);
        Path root=Path.of("").toAbsolutePath();while(!Files.exists(root.resolve("doc/alter_issue_center.sql")))root=root.getParent();
        try(var connection=ds.getConnection()){ScriptUtils.executeSqlScript(connection,new FileSystemResource(root.resolve("doc/alter_issue_center.sql")));ScriptUtils.executeSqlScript(connection,new FileSystemResource(root.resolve("doc/upgrade_issue_center_resolved_at.sql")));}
    }
    @BeforeEach void clear() {
        jdbc.update("DELETE FROM WEB_SYSTEM_ISSUE");jdbc.update("DELETE FROM WEB_SYSTEM_ISSUE_RECEIPT");now=System.currentTimeMillis();
    }
    private DiagnosticEvent event(String key,long time) {return new DiagnosticEvent(key,"server_error","/domain/{name}/search","POST",500,"java.lang.IllegalStateException","info.wesite.web.Lookup.run:123",key.substring(key.indexOf(':')+1),time,false);}
    private Map<String,Object> issue() {return jdbc.queryForMap("SELECT * FROM WEB_SYSTEM_ISSUE");}
    @Test void concurrentDistinctOccurrencesHaveOneIssueAndExactCount() throws Exception {
        var pool=Executors.newFixedThreadPool(8);
        try {
            List<Callable<Void>> jobs=new ArrayList<>();
            for(int i=0;i<32;i++)jobs.add(()->{store.record(event("http:"+UUID.randomUUID(),now));return null;});
            for(var result:pool.invokeAll(jobs))result.get();
        }finally{pool.shutdownNow();}
        assertEquals(32L,((Number)issue().get("occurrence_count")).longValue());
        assertEquals(32,jdbc.queryForObject("SELECT COUNT(*) FROM WEB_SYSTEM_ISSUE_EVENT",Integer.class));
    }
    @Test void concurrentRetriesAreIdempotent() throws Exception {
        String key="http:"+UUID.randomUUID();var pool=Executors.newFixedThreadPool(8);
        try {
            List<Callable<Void>> jobs=new ArrayList<>();for(int i=0;i<24;i++)jobs.add(()->{store.record(event(key,now));return null;});
            for(var result:pool.invokeAll(jobs))result.get();
        }finally{pool.shutdownNow();}
        assertEquals(1L,((Number)issue().get("occurrence_count")).longValue());
    }
    @Test void clientFirstAndServerFirstOnlyCountServerOnce() {
        for(boolean clientFirst:List.of(true,false)) {
            String key="http:"+UUID.randomUUID();var server=event(key,now);
            var client=new DiagnosticEvent(key,"request_failure","unmatched","POST",500,"HTTP_5XX","","",now,true);
            store.record(clientFirst?client:server);store.record(clientFirst?server:client);store.record(client);
        }
        assertEquals(2L,((Number)issue().get("occurrence_count")).longValue());
        var data=store.children((String)issue().get("id"),false,1,20);
        assertEquals(2L,data.get("total"));
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM WEB_SYSTEM_ISSUE_RECEIPT WHERE client_reported=1",Integer.class));
    }
    @Test void resolutionReopensOnNewFailureButDuplicatesDoNotAndStaleSavesConflict() {
        var first=event("http:"+UUID.randomUUID(),now);store.record(first);var issue=issue();String id=(String)issue.get("id");
        store.manage(id,((Number)issue.get("version")).longValue(),"resolved","Fixed query","v1","admin-id");
        store.record(first);assertEquals("resolved",issue().get("status"));
        long version=((Number)issue().get("version")).longValue();
        store.record(event("http:"+UUID.randomUUID(),System.currentTimeMillis()+1));assertEquals("open",issue().get("status"));
        assertThrows(ConcurrentModificationException.class,()->store.manage(id,version,"resolved","stale","v1","admin-id"));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM WEB_SYSTEM_ISSUE_NOTE",Integer.class));
        store.manage(id,((Number)issue().get("version")).longValue(),"ignored","known","","admin-id");
        store.record(event("http:"+UUID.randomUUID(),now+2));assertEquals("ignored",issue().get("status"));
    }
    @Test void lateArrivalFromBeforeResolutionDoesNotReopen() {
        store.record(event("http:"+UUID.randomUUID(),now-5000));var issue=issue();
        store.manage((String)issue.get("id"),((Number)issue.get("version")).longValue(),"resolved","fixed","v1","admin-id");
        store.record(event("http:"+UUID.randomUUID(),now-1000));
        assertEquals("resolved",issue().get("status"));
        assertEquals(2L,((Number)issue().get("occurrence_count")).longValue());
    }
    @Test void editingResolvedNoteDoesNotHideQueuedRecurrenceAndEachResolutionResetsCutoff() {
        store.record(event("http:"+UUID.randomUUID(),now-5000));var initial=issue();String id=(String)initial.get("id");
        store.manage(id,((Number)initial.get("version")).longValue(),"resolved","fixed","v1","admin-id");
        // Establish a deterministic earlier resolution without timing-dependent sleeps.
        long resolved=now-3000;
        jdbc.update("UPDATE WEB_SYSTEM_ISSUE SET resolved_at=?,managed_at=? WHERE id=?",resolved,resolved,id);
        store.manage(id,((Number)issue().get("version")).longValue(),"resolved","extra note","v2","admin-id");
        assertEquals(resolved,((Number)issue().get("resolved_at")).longValue());
        assertTrue(((Number)issue().get("managed_at")).longValue()>now-1000);
        store.record(event("http:"+UUID.randomUUID(),now-2000));
        assertEquals("open",issue().get("status"));
        store.manage(id,((Number)issue().get("version")).longValue(),"resolved","fixed again","v3","admin-id");
        long second=((Number)issue().get("resolved_at")).longValue();assertTrue(second>resolved);
        store.record(event("http:"+UUID.randomUUID(),second-1));assertEquals("resolved",issue().get("status"));
        store.record(event("http:"+UUID.randomUUID(),second+1));assertEquals("open",issue().get("status"));
    }
    @Test void upgradeBackfillsResolutionFromTransitionAndCanBeRepeated() throws Exception {
        store.record(event("http:"+UUID.randomUUID(),now-5000));var initial=issue();String id=(String)initial.get("id");
        store.manage(id,((Number)initial.get("version")).longValue(),"resolved","fixed","v1","admin-id");
        jdbc.update("UPDATE WEB_SYSTEM_ISSUE_NOTE SET created_at=? WHERE issue_id=?",now-3000,id);
        store.manage(id,((Number)issue().get("version")).longValue(),"resolved","later note","v1","admin-id");
        jdbc.execute("ALTER TABLE WEB_SYSTEM_ISSUE DROP COLUMN resolved_at");
        Path root=Path.of("").toAbsolutePath();while(!Files.exists(root.resolve("doc/upgrade_issue_center_resolved_at.sql")))root=root.getParent();
        try(var connection=Objects.requireNonNull(jdbc.getDataSource()).getConnection()) {
            for(int i=0;i<2;i++)ScriptUtils.executeSqlScript(connection,new FileSystemResource(root.resolve("doc/upgrade_issue_center_resolved_at.sql")));
        }
        assertEquals(now-3000,((Number)issue().get("resolved_at")).longValue());
        store.record(event("http:"+UUID.randomUUID(),now-2000));assertEquals("open",issue().get("status"));
    }
    @Test void cleanupKeepsCumulativeCountAndRecentlyManagedIssues() {
        long old=now-200L*86400000;store.record(event("http:"+UUID.randomUUID(),old));var issue=issue();
        store.manage((String)issue.get("id"),((Number)issue.get("version")).longValue(),"in_progress","investigating","","admin-id");
        store.cleanup(now);assertEquals(1L,((Number)issue().get("occurrence_count")).longValue());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM WEB_SYSTEM_ISSUE_EVENT",Integer.class));
        store.cleanup(now+181L*86400000);assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM WEB_SYSTEM_ISSUE",Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM WEB_SYSTEM_ISSUE_NOTE",Integer.class));
    }
    @Test void environmentIsolationAndBoundParameters() {
        store.record(event("http:"+UUID.randomUUID(),now));String id=(String)issue().get("id");
        assertEquals(0L,store.list(1,20,null,null,null,null,null,"' OR 1=1 --").get("total"));
        config.setEnvironment("other");
        try {assertEquals(0L,store.list(1,20,null,null,null,null,null,null).get("total"));assertThrows(NoSuchElementException.class,()->store.detail(id));}
        finally{config.setEnvironment("local");}
    }
}
