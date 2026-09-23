package info.wesite.core.service.impl;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import java.nio.file.*;
import java.util.List;
import java.util.concurrent.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.FileSystemResource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.support.TransactionTemplate;
import org.xbill.DNS.*;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import info.wesite.core.handler.Geoip2Handler;
import info.wesite.core.mapper.DomainDnsMapper;

/** Opt in only against the disposable local database named below. */
@EnabledIfSystemProperty(named="dnsTestJdbc", matches=".+")
class DomainDnsUniqueMySqlTest {
    private static JdbcTemplate jdbc;
    private static DataSource source;
    private static DomainDnsMapper mapper;
    private static DomainDnsRecordWriter writer;
    private static TransactionTemplate tx;
    private static Path migration;

    @BeforeAll static void connect() throws Exception {
        String url=System.getProperty("dnsTestJdbc");
        if (!url.startsWith("jdbc:mysql://127.0.0.1:") || !url.contains("/wesite_dns_unique_test?"))
            throw new IllegalArgumentException("Use an isolated local wesite_dns_unique_test database");
        source=new DriverManagerDataSource(url,"root","");
        jdbc=new JdbcTemplate(source);
        tx=new TransactionTemplate(new DataSourceTransactionManager(source));
        var config=new MybatisConfiguration();
        config.setMapUnderscoreToCamelCase(true);
        config.addMapper(DomainDnsMapper.class);
        var factory=new MybatisSqlSessionFactoryBean();
        factory.setDataSource(source); factory.setConfiguration(config);
        mapper=new SqlSessionTemplate(factory.getObject()).getMapper(DomainDnsMapper.class);
        writer=new DomainDnsRecordWriter(mapper,mock(Geoip2Handler.class));
        Path root=Path.of("").toAbsolutePath();
        while(!Files.exists(root.resolve("doc/alter_domain_dns_unique_fingerprint.sql"))) root=root.getParent();
        migration=root.resolve("doc/alter_domain_dns_unique_fingerprint.sql");
        System.out.println("DNS database integration version: "+jdbc.queryForObject("SELECT VERSION()",String.class));
    }
    @BeforeEach void schema() throws Exception {
        jdbc.execute("DROP TABLE IF EXISTS WEB_DOMAIN_DNS");
        jdbc.execute("CREATE TABLE WEB_DOMAIN_DNS (ID VARCHAR(32) PRIMARY KEY, DOMAIN_ID VARCHAR(32), NAME VARCHAR(100), TYPE VARCHAR(20), VALUE VARCHAR(1500), STATUS SMALLINT DEFAULT 1, DELETED SMALLINT DEFAULT 0, TTL BIGINT, CREATE_BY VARCHAR(50), CREATE_TIME DATETIME, UPDATE_BY VARCHAR(50), UPDATE_TIME DATETIME, ASN_JSON TEXT, CITY_JSON TEXT, KEY IDX_DOMAIN_STATUS(DOMAIN_ID, STATUS, DELETED)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci");
        try(var c=source.getConnection()){ScriptUtils.executeSqlScript(c,new FileSystemResource(migration));}
    }
    @Test void rejectsExactDuplicatesButPreservesByteDistinctLongTxt() {
        String prefix="X".repeat(1400);
        insert("a","domain","n.","TXT",prefix+"A",0,1);
        assertThrows(DuplicateKeyException.class,()->insert("b","domain","n.","TXT",prefix+"A",0,2));
        insert("c","domain","n.","TXT",prefix+"a",0,1);
        insert("d","domain","n.","TXT",prefix+"A ",0,1);
        insert("e","Domain","n.","TXT",prefix+"A",0,1);
        insert("f","domain","N.","TXT",prefix+"A",0,1);
        insert("g","domain","n.","txt",prefix+"A",0,1);
        assertEquals(6,count());
    }
    @Test void deletedRowsAndNullIdentitiesDoNotBlockNewLiveRecords() {
        insert("old","d","n.","TXT","v",1,1);
        insert("old2","d","n.","TXT","v",1,1);
        insert("new","d","n.","TXT","v",0,1);
        insert("null1","d","n.","TXT",null,0,1);
        insert("null2","d","n.","TXT",null,0,1);
        assertThrows(DuplicateKeyException.class,()->jdbc.update("UPDATE WEB_DOMAIN_DNS SET DELETED=0 WHERE ID='old'"));
        jdbc.update("UPDATE WEB_DOMAIN_DNS SET DELETED=1 WHERE ID='new'");
        jdbc.update("UPDATE WEB_DOMAIN_DNS SET DELETED=0 WHERE ID='old'");
        assertEquals(5,count());
    }
    @Test void fingerprintEncodingHasUnambiguousBoundariesAndUnicodeBytes() {
        insert("a","a","bc","TXT","\u4e2d\u6587",0,1);
        insert("b","ab","c","TXT","\u4e2d\u6587",0,1);
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(DISTINCT DNS_LIVE_FINGERPRINT) FROM WEB_DOMAIN_DNS",Integer.class));
        assertEquals(32,jdbc.queryForObject("SELECT LENGTH(DNS_LIVE_FINGERPRINT) FROM WEB_DOMAIN_DNS WHERE ID='a'",Integer.class));
    }
    @Test void codeDistinguishesCaseAndWhitespaceAndReactivatesExisting() throws Exception {
        TXTRecord a=txt("Token"); TXTRecord b=txt("token"); TXTRecord c=txt("Token ");
        tx.executeWithoutResult(s->writer.saveObserved("d",a));
        tx.executeWithoutResult(s->writer.saveObserved("d",b));
        tx.executeWithoutResult(s->writer.saveObserved("d",c));
        jdbc.update("UPDATE WEB_DOMAIN_DNS SET STATUS=2");
        tx.executeWithoutResult(s->writer.saveObserved("d",a));
        assertEquals(3,count());
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM WEB_DOMAIN_DNS WHERE STATUS=1",Integer.class));
    }
    @RepeatedTest(5) void concurrentTransactionsWithOldSnapshotsConvergeOnOneRecord() throws Exception {
        TXTRecord record=txt("parallel");
        CyclicBarrier barrier=new CyclicBarrier(3);
        var pool=Executors.newFixedThreadPool(3);
        try {
            Callable<Void> task=()->{
                tx.executeWithoutResult(s->{
                    assertTrue(mapper.findExact("d","example.com.","TXT",record.rdataToString(),false).isEmpty());
                    try {barrier.await(10,TimeUnit.SECONDS);} catch(Exception e){throw new RuntimeException(e);}
                    writer.saveObserved("d",record);
                }); return null;
            };
            var first=pool.submit(task); var second=pool.submit(task); var third=pool.submit(task);
            first.get(20,TimeUnit.SECONDS); second.get(20,TimeUnit.SECONDS); third.get(20,TimeUnit.SECONDS);
        } finally {pool.shutdownNow();}
        assertEquals(1,count());
        assertEquals(1,jdbc.queryForObject("SELECT STATUS FROM WEB_DOMAIN_DNS",Integer.class));
    }
    @Test void oldSnapshotOfDeletedRecordDoesNotLoseNewObservation() throws Exception {
        TXTRecord record=txt("returned");
        tx.executeWithoutResult(s->writer.saveObserved("d",record));
        tx.executeWithoutResult(s->{
            assertEquals(1,mapper.findExact("d","example.com.","TXT",record.rdataToString(),false).size());
            var other=new TransactionTemplate(new DataSourceTransactionManager(source));
            other.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            other.executeWithoutResult(inner->jdbc.update("UPDATE WEB_DOMAIN_DNS SET DELETED=1"));
            writer.saveObserved("d",record);
        });
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM WEB_DOMAIN_DNS WHERE DELETED=0 AND STATUS=1",Integer.class));
    }
    @Test void unrelatedUniqueConflictDoesNotOverwriteDifferentValue() throws Exception {
        jdbc.execute("ALTER TABLE WEB_DOMAIN_DNS ADD UNIQUE INDEX PROBE_OTHER_KEY(NAME)");
        TXTRecord a=txt("first"); TXTRecord b=txt("different");
        tx.executeWithoutResult(s->writer.saveObserved("d",a));
        assertThrows(DuplicateKeyException.class,()->tx.executeWithoutResult(s->writer.saveObserved("d",b)));
        assertEquals(a.rdataToString(),jdbc.queryForObject("SELECT VALUE FROM WEB_DOMAIN_DNS",String.class));
    }
    @Test void indexBuildRefusesExistingDuplicatesWithoutDeletingThem() {
        jdbc.execute("ALTER TABLE WEB_DOMAIN_DNS DROP INDEX UK_DNS_LIVE_FINGERPRINT");
        insert("a","d","n.","TXT","v",0,1); insert("b","d","n.","TXT","v",0,1);
        assertThrows(DuplicateKeyException.class,()->jdbc.execute("ALTER TABLE WEB_DOMAIN_DNS ADD UNIQUE INDEX UK_DNS_LIVE_FINGERPRINT(DNS_LIVE_FINGERPRINT), ALGORITHM=INPLACE, LOCK=NONE"));
        assertEquals(2,count());
    }
    @Test void migrationReplacesLegacyPrefixConstraint() throws Exception {
        jdbc.execute("ALTER TABLE WEB_DOMAIN_DNS DROP INDEX UK_DNS_LIVE_FINGERPRINT, DROP COLUMN DNS_LIVE_FINGERPRINT");
        jdbc.execute("ALTER TABLE WEB_DOMAIN_DNS ADD UNIQUE INDEX IDX_NAME_VALUE_TYPE(DOMAIN_ID,NAME,VALUE(255),TYPE)");
        insert("old","d","n.","TXT","X".repeat(300)+"A",1,1);
        try(var c=source.getConnection()){ScriptUtils.executeSqlScript(c,new FileSystemResource(migration));}
        insert("live","d","n.","TXT","X".repeat(300)+"A",0,1);
        insert("different","d","n.","TXT","X".repeat(300)+"B",0,1);
        assertEquals(3,count());
        assertThrows(DuplicateKeyException.class,()->insert("duplicate","d","n.","TXT","X".repeat(300)+"A",0,1));
        assertEquals(0,indexParts("IDX_NAME_VALUE_TYPE"));
    }
    @Test void freshDatabaseBaselineUsesFullLiveIdentity() throws Exception {
        jdbc.execute("DROP TABLE WEB_DOMAIN_DNS");
        String sql=Files.readString(migration.getParent().resolve("create.sql"));
        int begin=sql.indexOf("CREATE TABLE `WEB_DOMAIN_DNS`");
        jdbc.execute(sql.substring(begin,sql.indexOf(';',begin)));
        insert("old","d","n.","TXT","X".repeat(300)+"A",1,1);
        insert("new","d","n.","TXT","X".repeat(300)+"A",0,1);
        insert("different","d","n.","TXT","X".repeat(300)+"B",0,1);
        assertThrows(DuplicateKeyException.class,()->insert("duplicate","d","n.","TXT","X".repeat(300)+"A",0,1));
        assertEquals(1,indexParts("UK_DNS_LIVE_FINGERPRINT"));
        assertEquals(0,indexParts("IDX_NAME_VALUE_TYPE"));
    }
    @Test void legacyIndexIsRetainedUntilNewUniqueIndexIsReady() throws Exception {
        jdbc.execute("ALTER TABLE WEB_DOMAIN_DNS ADD UNIQUE INDEX IDX_NAME_VALUE_TYPE(DOMAIN_ID,NAME,VALUE(255),TYPE)");
        jdbc.execute("ALTER TABLE WEB_DOMAIN_DNS DROP INDEX UK_DNS_LIVE_FINGERPRINT");
        retireLegacyIndex();
        assertEquals(4,indexParts("IDX_NAME_VALUE_TYPE"));
        jdbc.execute("CREATE INDEX UK_DNS_LIVE_FINGERPRINT ON WEB_DOMAIN_DNS(DNS_LIVE_FINGERPRINT)");
        retireLegacyIndex();
        assertEquals(4,indexParts("IDX_NAME_VALUE_TYPE"));
    }
    @Test void unexpectedSameNameIndexIsNotRemoved() throws Exception {
        jdbc.execute("CREATE UNIQUE INDEX IDX_NAME_VALUE_TYPE ON WEB_DOMAIN_DNS(NAME)");
        retireLegacyIndex();
        assertEquals(1,indexParts("IDX_NAME_VALUE_TYPE"));
    }
    @Test void retirementCanResumeAndPreservesUnrelatedIndexes() throws Exception {
        jdbc.execute("ALTER TABLE WEB_DOMAIN_DNS ADD UNIQUE INDEX IDX_NAME_VALUE_TYPE(DOMAIN_ID,NAME,VALUE(255),TYPE)");
        jdbc.execute("CREATE INDEX PROBE_UNRELATED ON WEB_DOMAIN_DNS(TYPE)");
        retireLegacyIndex(); retireLegacyIndex();
        assertEquals(0,indexParts("IDX_NAME_VALUE_TYPE"));
        assertEquals(1,indexParts("UK_DNS_LIVE_FINGERPRINT"));
        assertEquals(1,indexParts("PROBE_UNRELATED"));
    }
    private void retireLegacyIndex() throws Exception {
        String sql=Files.readString(migration);
        sql=sql.substring(sql.indexOf("-- Retire legacy prefix index"));
        try(var c=source.getConnection()) {
            ScriptUtils.executeSqlScript(c,new org.springframework.core.io.ByteArrayResource(sql.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        }
    }
    private int indexParts(String name) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='WEB_DOMAIN_DNS' AND INDEX_NAME=?",Integer.class,name);
    }
    private TXTRecord txt(String value) throws Exception {return new TXTRecord(Name.fromString("example.com."),DClass.IN,300,value);}
    private int count(){return jdbc.queryForObject("SELECT COUNT(*) FROM WEB_DOMAIN_DNS",Integer.class);}
    private void insert(String id,String domain,String name,String type,String value,int deleted,int status){
        jdbc.update("INSERT INTO WEB_DOMAIN_DNS(ID,DOMAIN_ID,NAME,TYPE,VALUE,DELETED,STATUS) VALUES(?,?,?,?,?,?,?)",id,domain,name,type,value,deleted,status);
    }
}