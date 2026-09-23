package info.wesite.core.diagnostics;

import java.util.*;
import javax.sql.DataSource;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Repository
public class IssueStore {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final DiagnosticsProperties config;

    public IssueStore(DataSource dataSource, PlatformTransactionManager manager, DiagnosticsProperties config) {
        this.jdbc = new JdbcTemplate(dataSource);
        jdbc.setQueryTimeout(3);
        this.tx = new TransactionTemplate(manager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.setTimeout(5);
        this.config = config;
    }

    public void record(DiagnosticEvent event) {
        for (int attempt = 0; ; attempt++) {
            try { tx.executeWithoutResult(status -> write(event)); return; }
            catch (TransientDataAccessException failure) { if (attempt >= 2) throw failure; }
        }
    }

    private void write(DiagnosticEvent e) {
        String app = config.getApp(), env = config.getEnvironment();
        // This upsert takes the occurrence lock before any issue lock.
        jdbc.update("INSERT INTO WEB_SYSTEM_ISSUE_RECEIPT(app,environment,occurrence_key,client_reported,created_at) VALUES(?,?,?,?,?) "
            + "ON DUPLICATE KEY UPDATE client_reported=client_reported OR VALUES(client_reported)",
            app, env, e.key(), e.correlationOnly(), e.time());
        if (e.correlationOnly()) return;
        if (jdbc.queryForObject("SELECT COUNT(*) FROM WEB_SYSTEM_ISSUE_EVENT WHERE app=? AND environment=? AND occurrence_key=?",
                Long.class, app, env, e.key()) > 0) return;
        String fingerprint = DiagnosticSanitizer.fingerprint(e);
        jdbc.update("INSERT INTO WEB_SYSTEM_ISSUE(id,app,environment,fingerprint,source,summary,route,exception_type,first_seen_at,last_seen_at,last_release) "
            + "VALUES(?,?,?,?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE id=id", UUID.randomUUID().toString(), app, env,
            fingerprint, e.source(), DiagnosticSanitizer.truncate(e.exceptionType()+" · "+e.route(),400),
            e.route(), e.exceptionType(), e.time(), e.time(), "");
        String id = jdbc.queryForObject("SELECT id FROM WEB_SYSTEM_ISSUE WHERE app=? AND environment=? AND fingerprint=? FOR UPDATE",
            String.class, app, env, fingerprint);
        jdbc.update("INSERT INTO WEB_SYSTEM_ISSUE_EVENT(id,issue_id,app,environment,occurrence_key,route,method,http_status,exception_type,safe_frames,request_id,release_name,occurred_at) "
            + "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)", UUID.randomUUID().toString(), id, app, env, e.key(), e.route(), e.method(),
            e.status(), e.exceptionType(), e.frames(), e.requestId(), "", e.time());
        jdbc.update("UPDATE WEB_SYSTEM_ISSUE SET occurrence_count=occurrence_count+1,first_seen_at=LEAST(first_seen_at,?),"
            + "last_release=IF(last_seen_at<=?,?,last_release),last_seen_at=GREATEST(last_seen_at,?),"
            + "status=IF(status='resolved' AND ?>resolved_at,'open',status),version=version+1 WHERE id=?",
            e.time(), e.time(), "", e.time(), e.time(), id);
    }

    public Map<String,Object> list(int page, int size, String status, String app, String source, Long from, Long to, String keyword) {
        StringBuilder where = new StringBuilder(" WHERE environment=?");
        List<Object> args = new ArrayList<>(List.of(config.getEnvironment()));
        for (String[] pair : new String[][]{{"status",status},{"app",app},{"source",source}}) {
            if (pair[1] != null && !pair[1].isBlank()) { where.append(" AND ").append(pair[0]).append("=?"); args.add(pair[1]); }
        }
        if (from != null) { where.append(" AND last_seen_at>=?"); args.add(from); }
        if (to != null) { where.append(" AND last_seen_at<=?"); args.add(to); }
        if (keyword != null && !keyword.isBlank()) { where.append(" AND LOCATE(?,summary)>0"); args.add(DiagnosticSanitizer.truncate(keyword,100)); }
        long total = jdbc.queryForObject("SELECT COUNT(*) FROM WEB_SYSTEM_ISSUE"+where,Long.class,args.toArray());
        args.add(size); args.add((page-1)*size);
        List<Map<String,Object>> rows = jdbc.queryForList("SELECT * FROM WEB_SYSTEM_ISSUE"+where+" ORDER BY last_seen_at DESC,id DESC LIMIT ? OFFSET ?",args.toArray());
        return Map.of("items",rows,"total",total,"environment",config.getEnvironment(),"enabled",config.isEnabled());
    }

    public Map<String,Object> detail(String id) {
        List<Map<String,Object>> rows = jdbc.queryForList("SELECT * FROM WEB_SYSTEM_ISSUE WHERE id=? AND environment=?",id,config.getEnvironment());
        if (rows.isEmpty()) throw new NoSuchElementException("Issue not found");
        return rows.get(0);
    }

    public Map<String,Object> children(String id, boolean notes, int page, int size) {
        detail(id);
        String table = notes ? "WEB_SYSTEM_ISSUE_NOTE" : "WEB_SYSTEM_ISSUE_EVENT";
        String time = notes ? "created_at" : "occurred_at";
        long total = jdbc.queryForObject("SELECT COUNT(*) FROM "+table+" WHERE issue_id=?",Long.class,id);
        String select = notes ? "SELECT e.* FROM "+table+" e" : "SELECT e.*,COALESCE(r.client_reported,false) AS client_reported FROM "+table
            + " e LEFT JOIN WEB_SYSTEM_ISSUE_RECEIPT r ON r.app=e.app AND r.environment=e.environment AND r.occurrence_key=e.occurrence_key";
        return Map.of("total",total,"items",jdbc.queryForList(select+" WHERE e.issue_id=? ORDER BY e."+time+" DESC,e.id DESC LIMIT ? OFFSET ?",id,size,(page-1)*size));
    }

    public void manage(String id, long version, String status, String note, String release, String actor) {
        if (!Set.of("open","in_progress","resolved","ignored").contains(status) || note == null || note.length()>1000
            || release == null || !release.matches("[a-zA-Z0-9.:_+\\-]{0,64}")) throw new IllegalArgumentException("Invalid update");
        tx.executeWithoutResult(transaction -> {
            Map<String,Object> before = detail(id);
            long now = System.currentTimeMillis();
            int changed = jdbc.update("UPDATE WEB_SYSTEM_ISSUE SET resolved_at=IF(?='resolved' AND status<>'resolved',?,resolved_at),status=?,resolved_release=?,managed_at=?,version=version+1 WHERE id=? AND environment=? AND version=?",
                status,now,status,release,now,id,config.getEnvironment(),version);
            if (changed != 1) throw new ConcurrentModificationException("Issue changed; refresh before saving");
            jdbc.update("INSERT INTO WEB_SYSTEM_ISSUE_NOTE(id,issue_id,actor_id,old_status,new_status,note,resolved_release,created_at) VALUES(?,?,?,?,?,?,?,?)",
                UUID.randomUUID().toString(),id,actor,before.get("status"),status,note,release,now);
        });
    }

    public void cleanup(long now) {
        long eventBefore = now - Math.max(1,config.getEventRetentionDays())*86400000L;
        long issueBefore = now - Math.max(config.getEventRetentionDays(),config.getIssueRetentionDays())*86400000L;
        // Small independent batches; no long transaction holding the ingestion locks.
        jdbc.update("DELETE FROM WEB_SYSTEM_ISSUE_EVENT WHERE environment=? AND occurred_at<? LIMIT 500",config.getEnvironment(),eventBefore);
        jdbc.update("DELETE FROM WEB_SYSTEM_ISSUE_RECEIPT WHERE environment=? AND created_at<? LIMIT 500",config.getEnvironment(),eventBefore);
        jdbc.update("DELETE FROM WEB_SYSTEM_ISSUE WHERE environment=? AND last_seen_at<? AND managed_at<? LIMIT 100",config.getEnvironment(),issueBefore,issueBefore);
    }
}
