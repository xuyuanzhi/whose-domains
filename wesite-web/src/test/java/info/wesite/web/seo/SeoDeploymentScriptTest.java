package info.wesite.web.seo;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

class SeoDeploymentScriptTest {

    @Test
    void deploymentScriptChecksEverySeoContractWithoutWritingRemoteState() throws Exception {
        String script = Files.readString(Path.of("..", "scripts", "check-seo.ps1"));

        assertTrue(script.contains("/robots.txt"));
        assertTrue(script.contains("/sitemap_all.xml"));
        assertTrue(script.contains("AllowAutoRedirect = $false"));
        assertTrue(script.contains("rel=[\"']canonical"));
        assertTrue(script.contains("https://www.whose.domains/"));
        assertTrue(script.contains("/tools/whois-lookup/"));
        assertTrue(script.contains("exit 1"));
        assertTrue(script.contains("exit 0"));
        assertTrue(script.contains("Write-Error $_ -ErrorAction Continue"));
        assertTrue(!script.contains("Invoke-RestMethod -Method Post"));
    }
}
