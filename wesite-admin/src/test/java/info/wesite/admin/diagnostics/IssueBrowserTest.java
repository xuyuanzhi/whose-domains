package info.wesite.admin.diagnostics;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.core.io.FileSystemResource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.config.annotation.*;
import org.springframework.http.ResponseEntity;
import info.wesite.admin.controller.IssueController;
import info.wesite.admin.interceptor.AdminInterceptor;
import info.wesite.core.config.AccessControl;
import info.wesite.core.diagnostics.*;
import info.wesite.core.entity.User;
import info.wesite.core.service.UserService;
import info.wesite.core.utils.TokenUtils;

@EnabledIfSystemProperty(named="issueBrowser",matches="true")
class IssueBrowserTest {
    static Path root;
    @Configuration(proxyBeanMethods=false)
    @EnableAutoConfiguration
    @Import({DiagnosticsProperties.class,DiagnosticCatalog.class,DiagnosticRecorder.class,DiagnosticFilter.class,DiagnosticIngestController.class,IssueStore.class,IssueController.class,Fixture.class})
    static class Config implements WebMvcConfigurer {
        @Bean DataSource dataSource() {
            String url=System.getProperty("issueTestJdbc");
            if(url==null || !url.contains("/wesite_issue_center_test"))throw new IllegalArgumentException("An isolated test database is required");
            return new DriverManagerDataSource(url,System.getProperty("issueTestUser","root"),System.getProperty("issueTestPassword",""));
        }
        @Bean DataSourceTransactionManager transactionManager(DataSource ds){return new DataSourceTransactionManager(ds);}
        @Override public void addInterceptors(InterceptorRegistry registry){
            var users=mock(UserService.class);var user=new User();user.setId("browser-admin");user.setUserType(User.TYPE_ADMIN);user.setStatus(User.STATUS_ACTIVE);user.setDeleted(0);
            when(users.getById("browser-admin")).thenReturn(user);
            registry.addInterceptor(new AdminInterceptor(users)).addPathPatterns("/admin/issues/**");
        }
    }
    @RestController
    @AccessControl(level=AccessControl.Level.NONE)
    static class Fixture {
        @GetMapping(value="/test-page",produces="text/html") String page() throws Exception {
            return "<html><head><meta charset='UTF-8'><script src='/diagnostics/bootstrap.js'></script><script src='/static/js/diagnostics.js'></script></head><body>"
                +"<input id='domainInput' value='www.chinabbs.com'><button onclick='performSearch()'>Lookup</button><div id='loader'></div>"
                +"<script>window.$=function(){return {ready:function(){}}};</script><script src='/test-search.js'></script></body></html>";
        }
        @GetMapping(value="/test-search.js",produces="application/javascript") String searchScript() throws Exception {
            return Files.readString(root.resolve("wesite-web/src/main/resources/static/js/common.js"));
        }
        @GetMapping(value="/test-issues",produces="text/html") String issues() throws Exception {
            return "<html><head><meta charset='UTF-8'><link rel='stylesheet' href='/static/layuiadmin/layui/css/layui.css'><script src='/static/js/issue-center.js'></script></head><body>"
                +Files.readString(root.resolve("wesite-admin/src/main/resources/static/layuiadmin/views/issues/index.html"))+"</body></html>";
        }
        @PostMapping("/domain/{name}/search") ResponseEntity<?> fail(jakarta.servlet.http.HttpServletRequest request) {
            DiagnosticRecorder.mark(request,new IllegalStateException("sensitive information must never appear in the issue"));
            return ResponseEntity.status(500).body(java.util.Map.of("error","fixture"));
        }
    }
    @Test void browserExercisesLookupCaptureAndAdminLifecycle() throws Exception {
        root=Path.of("").toAbsolutePath();while(!Files.exists(root.resolve("doc/alter_issue_center.sql")))root=root.getParent();
        var tokens=new TokenUtils();ReflectionTestUtils.setField(tokens,"jwtSecret","isolated-browser-test-key-not-a-real-credential");ReflectionTestUtils.setField(tokens,"jwtIssuer","issue-browser-test");tokens.init();
        var user=new User();user.setId("browser-admin");user.setName("Browser test");
        try(var context=new SpringApplicationBuilder(Config.class).run("--spring.config.name=issue-center-test","--server.port=0","--server.address=127.0.0.1",
            "--spring.mvc.static-path-pattern=/static/**","--wesite.diagnostics.enabled=true","--wesite.diagnostics.app=admin","--wesite.diagnostics.environment=browser-test")) {
            var ds=context.getBean(DataSource.class);
            try(var connection=ds.getConnection()){ScriptUtils.executeSqlScript(connection,new FileSystemResource(root.resolve("doc/alter_issue_center.sql")));}
            var jdbc=new JdbcTemplate(ds);jdbc.update("DELETE FROM WEB_SYSTEM_ISSUE WHERE environment='browser-test'");jdbc.update("DELETE FROM WEB_SYSTEM_ISSUE_RECEIPT WHERE environment='browser-test'");
            int port=((ServletWebServerApplicationContext)context).getWebServer().getPort();
            var builder=new ProcessBuilder("node",root.resolve("wesite-admin/src/test/js/issue-center-browser.cjs").toString()).redirectErrorStream(true);
            Path output=Files.createTempFile("issue-browser-", ".log");
            builder.redirectOutput(output.toFile());
            builder.environment().put("ISSUE_TEST_BASE","http://127.0.0.1:"+port);builder.environment().put("ISSUE_TEST_TOKEN",TokenUtils.createToken(user,5));
            var process=builder.start();
            if(!process.waitFor(90,TimeUnit.SECONDS)){process.destroyForcibly();fail("Browser test timed out");}
            String browserOutput=Files.readString(output);
            System.out.println(browserOutput);
            Files.deleteIfExists(output);
            assertEquals(0,process.exitValue(),browserOutput);
        }
    }
}
