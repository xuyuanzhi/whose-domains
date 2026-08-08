package info.wesite.web.seo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

class SeoDeploymentScriptTest {

    @Test
    void offlineSelfTestValidatesCanonicalParsingRedirectNormalizationAndFailureAggregation() throws Exception {
        Path scriptPath = Path.of("..", "scripts", "check-seo.ps1").toAbsolutePath();
        Process process = new ProcessBuilder("powershell.exe", "-NoProfile", "-File", scriptPath.toString(), "-SelfTest")
                .redirectErrorStream(true)
                .start();

        String output = new String(process.getInputStream().readAllBytes());

        assertEquals(0, process.waitFor(), output);
        assertTrue(output.contains("SEO offline self-test passed."), output);
    }

    @Test
    void deploymentScriptUsesOnlyGetRequestsForRemoteChecks() throws IOException {
        String script = Files.readString(Path.of("..", "scripts", "check-seo.ps1"));

        assertTrue(script.contains("/robots.txt"));
        assertTrue(script.contains("/sitemap_all.xml"));
        assertTrue(script.contains("AllowAutoRedirect = $false"));
        assertTrue(script.contains("https://www.whose.domains/"));
        assertTrue(script.contains("/tools/whois-lookup/"));
        assertTrue(script.contains("exit 1"));
        assertTrue(script.contains("exit 0"));
        assertTrue(script.contains("Write-Error $_ -ErrorAction Continue"));
        assertTrue(script.contains("GetAsync"));
        assertFalse(Pattern.compile("(?i)\\b(?:postasync|putasync|patchasync|deleteasync|sendasync|httpmethod\\.(?:post|put|patch|delete)|invoke-(?:restmethod|webrequest))\\b")
                .matcher(script)
                .find());
    }
}
