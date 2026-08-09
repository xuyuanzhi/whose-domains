package info.wesite.web.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

class RetentionRolloutVerifierScriptTest {

    @Test
    void staticFixtureRunsOfflineAndReportsItsTerminalContract() throws Exception {
        Path repository = repositoryRoot();
        Path script = repository.resolve("scripts/verify-retention-rollout.ps1");
        Process process = new ProcessBuilder(List.of(
            powerShellHost(),
            "-NoProfile",
            "-ExecutionPolicy", "Bypass",
            "-File", script.toString(),
            "-Fixture", "Static"))
            .directory(repository.toFile())
            .redirectErrorStream(true)
            .start();

        assertTrue(process.waitFor(30, TimeUnit.SECONDS), "static verifier timed out");
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        assertEquals(0, process.exitValue(), output);
        assertTrue(output.contains("RETENTION_ROLLOUT_VERIFY|PASS|FIXTURE=Static"), output);
    }

    @Test
    void pathAFixtureProvesRetentionMaturityAndKSuppressionAgainstMySql() throws Exception {
        Path repository = repositoryRoot();
        Path script = repository.resolve("scripts/verify-retention-rollout.ps1");
        Process process = new ProcessBuilder(List.of(
            powerShellHost(),
            "-NoProfile",
            "-ExecutionPolicy", "Bypass",
            "-File", script.toString(),
            "-Fixture", "PathA"))
            .directory(repository.toFile())
            .redirectErrorStream(true)
            .start();

        assertTrue(process.waitFor(120, TimeUnit.SECONDS), "PathA verifier timed out");
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        assertEquals(0, process.exitValue(), output);
        assertTrue(output.contains("RETENTION_REPORT_DATA|PASS|PATH=PathA|IMMATURE=EMPTY|GAP=INSUFFICIENT|CLEANUP=INSUFFICIENT|MATURE=5|SUPPRESSED=4"), output);
        assertTrue(output.contains("RETENTION_ROLLOUT_VERIFY|PASS|FIXTURE=PathA"), output);
    }

    private static Path repositoryRoot() throws IOException {
        Path candidate = Path.of("").toAbsolutePath().normalize();
        while (candidate != null) {
            if (Files.isDirectory(candidate.resolve("scripts"))
                && Files.isDirectory(candidate.resolve("wesite-web"))) {
                return candidate;
            }
            candidate = candidate.getParent();
        }
        throw new IOException("repository root not found");
    }

    private static String powerShellHost() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")
            ? "powershell.exe"
            : "pwsh";
    }
}
