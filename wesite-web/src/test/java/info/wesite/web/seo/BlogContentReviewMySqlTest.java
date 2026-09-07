package info.wesite.web.seo;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mybatis.spring.mapper.MapperFactoryBean;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import info.wesite.core.blog.*;
import info.wesite.core.mapper.BlogPostMapper;

@Testcontainers(disabledWithoutDocker = true)
@Timeout(60)
class BlogContentReviewMySqlTest {
    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4.0")
        .withDatabaseName("blog_review").withUsername("blog").withPassword("blog")
        .withCommand("--transaction-isolation=REPEATABLE-READ");
    private static DataSource source;
    private static TransactionTemplate transactions;
    private static BlogEditorialService editorial;
    private static BlogPostMapper posts;
    private static BlogReviewService reviews;

    @BeforeAll
    static void setup() throws Exception {
        source = new DriverManagerDataSource(MYSQL.getJdbcUrl() + "?allowMultiQueries=true", MYSQL.getUsername(), MYSQL.getPassword());
        transactions = new TransactionTemplate(new DataSourceTransactionManager(source));
        String schema = Files.readString(Path.of("..", "doc", "create.sql"), StandardCharsets.UTF_8);
        int start = schema.indexOf("CREATE TABLE `WEB_BLOG_POST`");
        execute(schema.substring(start, schema.indexOf(';', start) + 1));
        migrate();
        var factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(source); factory.afterPropertiesSet();
        var mapperFactory = new MapperFactoryBean<>(BlogPostMapper.class);
        mapperFactory.setSqlSessionFactory(factory.getObject()); mapperFactory.afterPropertiesSet();
        var mapper = mapperFactory.getObject();
        posts = mapper;
        var sanitizer = new BlogHtmlSanitizer();
        reviews = new BlogReviewService(mapper, sanitizer);
        editorial = new BlogEditorialService(mapper, sanitizer, new BlogTimeProvider(), reviews);
    }

    @BeforeEach
    void reset() throws Exception { execute("DELETE FROM WEB_BLOG_POST"); }

    @Test
    void archivingLegacyDuplicateUnblocksRetainedArticleAndRestoringRejoinsDeduplication() throws Exception {
        String body = "<h2>Steps</h2><p>" + "Inspect records and compare authoritative DNS responses carefully. ".repeat(30)
            + "</p><ol><li>Query the authoritative server.</li></ol><h2>Limitations</h2><p>Cached data may differ.</p>"
            + "<a href=\"https://www.rfc-editor.org/rfc/rfc1035\">Reference</a>";
        var retained = transactions.execute(status -> editorial.createAiDraft(draft("retained", "DNS checks", body)));
        transactions.execute(status -> editorial.publish(retained.getId(), "editor"));
        try (var connection = source.getConnection(); var statement = connection.prepareStatement(
                "INSERT INTO WEB_BLOG_POST (ID, SLUG, TITLE, SUMMARY, CONTENT, META_DESCRIPTION, STATUS, DELETED) "
                + "SELECT 'duplicate', 'duplicate', TITLE, SUMMARY, CONTENT, META_DESCRIPTION, 1, 0 FROM WEB_BLOG_POST WHERE ID = ?")) {
            statement.setString(1, retained.getId()); statement.executeUpdate();
        }
        var edit = new BlogEditCommand(retained.getId(), retained.getSlug(), retained.getTitle(), retained.getSummary(),
            body, "Verified Editor", retained.getCategory(), retained.getTags(), retained.getMetaTitle(), retained.getMetaDescription());
        assertThrows(BlogEditorialException.class, () -> transactions.execute(status -> editorial.save(edit, "editor")));
        transactions.execute(status -> editorial.archive("duplicate", "editor"));
        assertDoesNotThrow(() -> transactions.execute(status -> editorial.save(edit, "editor")));
        assertEquals(body, posts.selectById("duplicate").getContent());
        assertEquals(2, posts.selectById("duplicate").getStatus());
        assertEquals(1, reviews.audit().scanned());
        assertTrue(posts.selectById(retained.getId()).isAiAssisted());
        transactions.execute(status -> editorial.restore("duplicate", "editor"));
        assertEquals(0, posts.selectById("duplicate").getStatus());
        assertTrue(reviews.review(posts.selectById(retained.getId())).hasDuplicate());
        assertThrows(BlogEditorialException.class, () -> transactions.execute(status -> editorial.publish("duplicate", "editor")));
    }

    @Test
    void provenanceMigrationBackfillsOnlyKnownOriginsAndSurvivesBylineEditingAndReruns() throws Exception {
        execute("ALTER TABLE WEB_BLOG_POST DROP COLUMN AI_GENERATED");
        execute("INSERT INTO WEB_BLOG_POST (ID, SLUG, TITLE, AUTHOR, CREATE_BY, STATUS, DELETED) VALUES "
            + "('legacy','legacy','Legacy','James Chen',NULL,0,0),"
            + "('generated','generated','Generated','Editor','ai',0,0),"
            + "('unknown','unknown','Unknown','Whose.Domains',NULL,0,0),"
            + "('human','human','Human','Ada Example','editor',0,0)");
        String migration = Files.readString(Path.of("..", "doc", "alter_blog_ai_provenance.sql"), StandardCharsets.UTF_8);
        execute(migration);
        assertEquals(Boolean.TRUE, posts.selectById("legacy").getAiGenerated());
        assertEquals(Boolean.TRUE, posts.selectById("generated").getAiGenerated());
        assertNull(posts.selectById("unknown").getAiGenerated());
        assertNull(posts.selectById("human").getAiGenerated());
        execute("UPDATE WEB_BLOG_POST SET AUTHOR = 'Whose.Domains' WHERE ID = 'legacy'");
        execute(migration);
        assertTrue(posts.selectById("legacy").isAiAssisted());
        assertNull(posts.selectById("legacy").getCreateBy());
        assertEquals("Whose.Domains", posts.selectById("legacy").getAuthor());
        assertEquals(4, count("WEB_BLOG_POST"));
    }

    @Test
    void migrationIsIdempotentAndDoesNotModifyArticles() throws Exception {
        transactions.execute(status -> editorial.createAiDraft(draft("original", "Original", "<p>Original content</p>")));
        migrate(); migrate();
        assertEquals(1, count("WEB_BLOG_EDITORIAL_LOCK"));
        assertEquals(1, count("WEB_BLOG_POST"));
    }

    @Test
    void concurrentDifferentSlugsCannotInsertTheSameNormalizedBody() throws Exception {
        var start = new CyclicBarrier(2);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> create(start, "one", "First title", "<p>Copied content</p>"));
            var second = pool.submit(() -> create(start, "two", "Second title", "<p><strong>Copied</strong> content!</p>"));
            assertEquals(1, first.get(20, TimeUnit.SECONDS) + second.get(20, TimeUnit.SECONDS));
            assertEquals(1, count("WEB_BLOG_POST"));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void concurrentNormalizedTitlesCannotInsertDifferentBodies() throws Exception {
        var start = new CyclicBarrier(2);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> create(start, "one", "DNS: Checks!", "<p>First content</p>"));
            var second = pool.submit(() -> create(start, "two", "dns checks", "<p>Second content</p>"));
            assertEquals(1, first.get(20, TimeUnit.SECONDS) + second.get(20, TimeUnit.SECONDS));
            assertEquals(1, count("WEB_BLOG_POST"));
        } finally {
            pool.shutdownNow();
        }
    }

    private static int create(CyclicBarrier start, String slug, String title, String body) throws Exception {
        start.await(10, TimeUnit.SECONDS);
        try {
            transactions.execute(status -> editorial.createAiDraft(draft(slug, title, body)));
            return 1;
        } catch (BlogEditorialException expected) {
            assertTrue(expected.getMessage().startsWith("Duplicate article"), expected.getMessage());
            return 0;
        }
    }

    private static BlogDraftCommand draft(String slug, String title, String body) {
        return new BlogDraftCommand(slug, title, "Summary", body, "dns", "dns", title, "Description");
    }

    private static void migrate() throws Exception {
        execute(Files.readString(Path.of("..", "doc", "alter_blog_content_review.sql"), StandardCharsets.UTF_8));
    }
    private static void execute(String sql) throws Exception {
        try (Connection c = source.getConnection(); Statement s = c.createStatement()) { s.execute(sql); }
    }
    private static long count(String table) throws Exception {
        try (Connection c = source.getConnection(); Statement s = c.createStatement(); var rows = s.executeQuery("SELECT COUNT(*) FROM " + table)) {
            rows.next(); return rows.getLong(1);
        }
    }
}
