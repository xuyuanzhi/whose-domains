package info.wesite.admin.maintenance;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

class BlogEditorialDeploymentDocumentationTest {

    @Test
    void readmeDocumentsSafeEditorialDeploymentOrder() throws Exception {
        String readme = Files.readString(Path.of("..", "README.md"));

        int backup = readme.indexOf("Back up WEB_BLOG_POST");
        int migration = readme.indexOf("alter_blog_editorial_workflow.sql");
        int dryRun = readme.indexOf("wesite.blog.sanitization.mode=dry-run");
        int inspect = readme.indexOf("Inspect the dry-run report");
        int apply = readme.indexOf("wesite.blog.sanitization.mode=apply");
        int deploy = readme.indexOf(
                "Deploy and check Admin independently, then deploy and check Web independently");

        assertTrue(backup >= 0 && backup < migration);
        assertTrue(migration < dryRun && dryRun < inspect);
        assertTrue(inspect < apply && apply < deploy);
        assertTrue(readme.contains("--spring.main.web-application-type=none"));
        assertTrue(readme.contains("set -o pipefail"));
        assertTrue(readme.contains("CONTENT_UPDATED_AT"));
        assertTrue(readme.contains("preview iframe sandbox"));
        assertTrue(readme.contains("public HTML and JSON-LD"));
        assertTrue(readme.matches("(?s).*Leave the nullable column\\s+in\\s+place.*"));
        assertTrue(readme.contains("restore CONTENT and the original CONTENT_UPDATED_AT"));
    }
}
