package info.wesite.web.seo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.function.Predicate;

import org.junit.jupiter.api.Test;

class SeoDeploymentScriptTest {

    static String selectPowerShellExecutable(Predicate<String> canStart) {
        for (String candidate : List.of("pwsh", "powershell.exe")) {
            if (canStart.test(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    @Test
    void powerShellSelectionPrefersPwsh() {
        List<String> attempts = new ArrayList<>();

        String executable = selectPowerShellExecutable(candidate -> {
            attempts.add(candidate);
            return true;
        });

        assertEquals("pwsh", executable);
        assertEquals(List.of("pwsh"), attempts);
    }

    @Test
    void powerShellSelectionFallsBackToWindowsPowerShell() {
        List<String> attempts = new ArrayList<>();

        String executable = selectPowerShellExecutable(candidate -> {
            attempts.add(candidate);
            return candidate.equals("powershell.exe");
        });

        assertEquals("powershell.exe", executable);
        assertEquals(List.of("pwsh", "powershell.exe"), attempts);
    }

    @Test
    void offlineSelfTestValidatesCanonicalParsingRedirectNormalizationAndFailureAggregation() throws Exception {
        Path scriptPath = Path.of("..", "scripts", "check-seo.ps1").toAbsolutePath();
        String executable = selectPowerShellExecutable(SeoDeploymentScriptTest::canStart);
        assumeTrue(executable != null,
                "Neither pwsh nor powershell.exe is installed; skipping only the process-based SEO offline self-test");
        Process process = new ProcessBuilder(executable, "-NoProfile", "-File", scriptPath.toString(), "-SelfTest")
                .redirectErrorStream(true)
                .start();

        String output = new String(process.getInputStream().readAllBytes());

        assertEquals(0, process.waitFor(), output);
        assertTrue(output.contains("Indexable HTML self-test cases passed: 6."), output);
        assertTrue(output.contains("SEO offline self-test passed."), output);
    }

    @Test
    void offlineInvocationRejectsNonProductionBaseUrl() throws Exception {
        Path scriptPath = Path.of("..", "scripts", "check-seo.ps1").toAbsolutePath();
        String executable = selectPowerShellExecutable(SeoDeploymentScriptTest::canStart);
        assumeTrue(executable != null,
                "Neither pwsh nor powershell.exe is installed; skipping only the process-based SEO offline self-test");
        Process process = new ProcessBuilder(
                executable,
                "-NoProfile",
                "-File",
                scriptPath.toString(),
                "-SelfTest",
                "-BaseUrl",
                "https://example.com")
                .redirectErrorStream(true)
                .start();

        String output = new String(process.getInputStream().readAllBytes());

        assertNotEquals(0, process.waitFor(), output);
        assertTrue(output.contains("production-only and requires https://whose.domains"), output);
        assertFalse(output.contains("GET failed:"), output);
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
        int productionBaseUrlValidation = script.indexOf("Get-ProductionBaseUrl $BaseUrl");
        int httpClientConstruction = script.indexOf("[System.Net.Http.HttpClient]::new");
        assertTrue(productionBaseUrlValidation >= 0, "production BaseUrl validation is missing");
        assertTrue(productionBaseUrlValidation < httpClientConstruction,
                "production BaseUrl must be validated before constructing the HTTP client");
        assertFalse(Pattern.compile("(?i)\\b(?:postasync|putasync|patchasync|deleteasync|sendasync|httpmethod\\.(?:post|put|patch|delete)|invoke-(?:restmethod|webrequest))\\b")
                .matcher(script)
                .find());
    }

    private static boolean canStart(String executable) {
        try {
            Process probe = new ProcessBuilder(executable, "-NoProfile", "-Command", "exit 0")
                    .redirectErrorStream(true)
                    .start();
            probe.getInputStream().readAllBytes();
            probe.waitFor();
            return true;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while locating PowerShell", e);
        }
    }
}
